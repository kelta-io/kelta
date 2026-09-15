import { Command } from 'commander';
import { createInterface } from 'node:readline/promises';
import { buildContext } from '../context.js';
import { maybeNotifyUpdate } from '../update/passiveCheck.js';
import { CliError, EXIT, mapError, toErrorPayload } from '../errors.js';
import { OUTPUT_FORMATS, pickFormat, renderResult, type OutputFormat } from '../render/render.js';
import type { CommandContext, RegisteredCommand, GlobalOptions } from './types.js';

/**
 * The cross-cutting flags. Registered on every leaf command (commander only
 * parses a flag after the subcommand name when the leaf defines it) and read
 * back via optsWithGlobals().
 */
export function addGlobalOptions(command: Command): Command {
  return command
    .option('--profile <name>', 'Connection profile to use (default: active profile)')
    .option('--output <format>', 'Output format: table|json|yaml|csv|ndjson')
    .option('--raw', 'Emit the unflattened JSON:API envelope')
    .option('--quiet', 'Print ids only')
    .option('--yes', 'Skip confirmation for destructive commands');
}

/** Read the harness-level flags (leaf + inherited root values). */
function globalOptions(command: Command): GlobalOptions {
  const opts = command.optsWithGlobals<{
    profile?: string;
    output?: string;
    raw?: boolean;
    quiet?: boolean;
    yes?: boolean;
  }>();
  if (opts.output !== undefined && !OUTPUT_FORMATS.includes(opts.output as OutputFormat)) {
    throw new CliError(`Invalid --output "${opts.output}" (expected ${OUTPUT_FORMATS.join('|')})`, {
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
  }
  return {
    profile: opts.profile,
    output: opts.output as OutputFormat | undefined,
    raw: opts.raw ?? false,
    quiet: opts.quiet ?? false,
    yes: opts.yes ?? false,
  };
}

async function confirmDangerous(def: RegisteredCommand, global: GlobalOptions): Promise<void> {
  if (global.yes) return;
  const fullName = [def.group, def.name].filter(Boolean).join(' ');
  if (!process.stdin.isTTY || !process.stderr.isTTY) {
    throw new CliError(`"${fullName}" is destructive — pass --yes to proceed`, {
      code: 'CONFIRMATION_REQUIRED',
      exitCode: EXIT.USAGE,
    });
  }
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  try {
    const answer = await rl.question(`${fullName} — proceed? [y/N] `);
    if (answer.trim().toLowerCase() !== 'y') {
      throw new CliError('Aborted', { code: 'ABORTED', exitCode: EXIT.USAGE });
    }
  } finally {
    rl.close();
  }
}

/** Human-readable rendering of every error, not just the first. */
function formatTableError(mapped: CliError): string {
  const entries = mapped.errors && mapped.errors.length > 0 ? mapped.errors : undefined;
  if (!entries) return `Error [${mapped.code}]: ${mapped.message}\n`;
  return (
    entries
      .map((e) => `Error [${e.code ?? mapped.code}]: ${e.detail ?? mapped.message}`)
      .join('\n') + '\n'
  );
}

/** Execute one command definition with already-merged raw input. */
export async function dispatch(
  leaf: Command,
  def: RegisteredCommand,
  rawInput: Record<string, unknown>
): Promise<void> {
  let global: GlobalOptions = { raw: false, quiet: false, yes: false };
  try {
    global = globalOptions(leaf);
    const input: unknown = def.input.parse(rawInput);
    const isDangerous =
      typeof def.dangerous === 'function'
        ? def.dangerous(input as never)
        : (def.dangerous ?? false);
    if (isDangerous) await confirmDangerous(def, global);
    const ctx: CommandContext = buildContext(global);
    const result = await def.handler(ctx, input as never);
    // stdout-owning commands (the stdio MCP server) carry protocol bytes: a
    // rendered result or an update hint would corrupt the JSON-RPC stream and
    // the client rejects the whole connection.
    if (def.ownsStdout) return;
    const format = pickFormat(global.output, process.stdout.isTTY ?? false);
    process.stdout.write(renderResult(result, { format, raw: global.raw, quiet: global.quiet }));
    await maybeNotifyUpdate((message) => process.stderr.write(message + '\n'));
  } catch (error) {
    const mapped = mapError(error);
    const format = pickFormat(global.output, process.stderr.isTTY ?? false);
    process.stderr.write(
      format === 'table' ? formatTableError(mapped) : toErrorPayload(mapped) + '\n'
    );
    process.exitCode = mapped.exitCode;
  }
}

/** Wire every command definition into the commander program. */
export function bindCommands(program: Command, defs: RegisteredCommand[]): void {
  const groups = new Map<string, Command>();

  for (const def of defs) {
    let parent = program;
    if (def.group) {
      let group = groups.get(def.group);
      if (!group) {
        group = program.command(def.group).description(groupSummary(def.group, defs));
        groups.set(def.group, group);
      }
      parent = group;
    }

    const signature = [
      def.name,
      ...(def.positionals ?? []).map((p) => (p.required ? `<${p.name}>` : `[${p.name}]`)),
    ].join(' ');
    const sub = addGlobalOptions(parent.command(signature).description(def.summary));
    for (const opt of def.options ?? []) {
      if (opt.repeatable) {
        sub.option(
          opt.flag,
          opt.description,
          (value: string, previous: string[]) => previous.concat([value]),
          (opt.default as string[] | undefined) ?? []
        );
      } else if (opt.default !== undefined) {
        sub.option(opt.flag, opt.description, opt.default as string | boolean);
      } else {
        sub.option(opt.flag, opt.description);
      }
    }
    sub.action(async (...args: unknown[]) => {
      // commander passes positionals in order, then the options object, then the Command
      const positionals = (def.positionals ?? []).map((p, i) => [p.name, args[i]] as const);
      const opts = (args[(def.positionals ?? []).length] ?? {}) as Record<string, unknown>;
      const rawInput: Record<string, unknown> = { ...opts };
      // globals live in optsWithGlobals, not in the handler input
      for (const key of ['profile', 'output', 'raw', 'quiet', 'yes']) delete rawInput[key];
      for (const [name, value] of positionals) {
        if (value !== undefined) rawInput[name] = value;
      }
      await dispatch(sub, def, rawInput);
    });
  }
}

function groupSummary(group: string, defs: RegisteredCommand[]): string {
  const summaries: Record<string, string> = {
    auth: 'Authentication and token management',
    token: 'Manage personal access tokens',
    profile: 'Manage named connection profiles',
    collections: 'Collection management',
    fields: 'Field management on collections',
    picklists: 'Global picklists and their values',
    'validation-rules': 'Validation rules (record rejected when the formula is TRUE)',
    constraints: 'Composite unique constraints',
    layouts: 'Page layouts',
    'list-views': 'Saved list views',
    flows: 'Flow definitions, executions, and versions',
    users: 'User administration',
    limits: 'Tenant governor limits',
    audit: 'Audit trails (setup, security, logins)',
    records: 'Record CRUD, bulk operations, and search',
    metadata: 'Export, diff, and apply tenant metadata (GitOps for config)',
    sandbox: 'Create and manage sandbox environments',
    promote: 'Promote metadata between environments',
    sdk: 'Generate typed SDK artifacts from this tenant’s schema',
    mcp: 'Local MCP server and client setup',
  };
  return summaries[group] ?? defs.find((d) => d.group === group)?.summary ?? group;
}
