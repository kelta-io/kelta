import { Command, CommanderError } from 'commander';
import { OUTPUT_FORMATS, type OutputFormat } from '../render/render.js';
import { VERSION } from '../version.js';
import { bindCommands, dispatch, renderError, type CommandRunner } from './bind.js';
import type { RegisteredCommand } from './types.js';

/** Commander's own exits for help and version — not failures. */
const HELP_CODES = new Set(['commander.help', 'commander.helpDisplayed', 'commander.version']);

/**
 * The root `kelta` program. Commander throws instead of exiting and prints no
 * error text of its own, so an argument error reaches `runCli` and is rendered
 * like every other failure (INVALID_ARGUMENTS, exit 2). Both settings are
 * inherited by every subcommand created after them.
 */
export function createProgram(defs: RegisteredCommand[], run: CommandRunner = dispatch): Command {
  const program = new Command();
  program
    .name('kelta')
    .description('Kelta Platform CLI — manage collections, records, and metadata')
    .version(VERSION)
    .exitOverride()
    .configureOutput({ outputError: () => {} })
    .option('--profile <name>', 'Connection profile to use (default: active profile)')
    .option('--output <format>', 'Output format: table|json|yaml|csv|ndjson')
    .option('--raw', 'Emit the unflattened JSON:API envelope')
    .option('--quiet', 'Print ids only')
    .option('--yes', 'Skip confirmation for destructive commands');
  bindCommands(program, defs, run);
  return program;
}

/**
 * The last valid `--output <fmt>` / `--output=<fmt>` before `--`. A parse
 * error can fire before commander has read any option, so it is scanned here.
 */
export function outputFromArgv(args: string[]): OutputFormat | undefined {
  let output: string | undefined;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--') break;
    if (arg === '--output') output = args[++i];
    else if (arg.startsWith('--output=')) output = arg.slice('--output='.length);
  }
  return OUTPUT_FORMATS.includes(output as OutputFormat) ? (output as OutputFormat) : undefined;
}

/** Parse user args (argv without node and script) and run the matched command. */
export async function runCli(program: Command, args: string[]): Promise<void> {
  try {
    await program.parseAsync(args, { from: 'user' });
  } catch (error) {
    if (error instanceof CommanderError && HELP_CODES.has(error.code)) {
      process.exitCode = error.exitCode;
      return;
    }
    renderError(error, outputFromArgv(args));
  }
}
