import { zodToJsonSchema } from 'zod-to-json-schema';
import type { ZodType } from 'zod';
import { buildContext } from '../context.js';
import { flattenBody } from '../render/flatten.js';
import type { RegisteredCommand } from '../registry/types.js';

/**
 * Groups exposed as LOCAL MCP tools — CLI capabilities with no hosted
 * equivalent. Everything the hosted kelta-mcp toolsets cover stays remote so
 * platform tools remain single-sourced in Java.
 */
const LOCAL_GROUPS = new Set([
  'metadata',
  'sandbox',
  'promote',
  'sdk',
  // pages/menus/dashboards/reports have no hosted kelta-mcp admin tool (unlike
  // layouts/list-views, which do — see CreateLayoutTool/CreateListViewTool et al.)
  'pages',
  'menus',
  'dashboards',
  'reports',
]);
/** Additional group:name picks outside the whole-group list. */
const LOCAL_PICKS = new Set(['profile:list', 'token:list', 'token:revoke', ':docs']);
/** Powerful raw escape hatch — opt-in only (`--enable-api-tool`). */
const API_PICK = ':api';

export interface LocalToolOptions {
  enableApiTool?: boolean;
}

export function selectLocalCommands(
  commands: RegisteredCommand[],
  options: LocalToolOptions = {}
): RegisteredCommand[] {
  return commands.filter((command) => {
    const key = `${command.group}:${command.name}`;
    if (key === API_PICK) return options.enableApiTool === true;
    return LOCAL_GROUPS.has(command.group) || LOCAL_PICKS.has(key);
  });
}

/** `cli_` prefix guarantees no collision with hosted tool names. */
export function localToolName(command: RegisteredCommand): string {
  return ['cli', command.group, command.name].filter(Boolean).join('_').replace(/-/g, '_');
}

export interface McpToolSpec {
  name: string;
  description: string;
  inputSchema: unknown;
  annotations: { destructiveHint: boolean; readOnlyHint: boolean };
}

export function toMcpTool(command: RegisteredCommand): McpToolSpec {
  return {
    name: localToolName(command),
    description: command.summary,
    inputSchema: zodToJsonSchema(command.input as unknown as ZodType, { $refStrategy: 'none' }),
    annotations: {
      destructiveHint: Boolean(command.dangerous),
      readOnlyHint: command.name === 'list' || command.name === 'status' || command.name === 'show',
    },
  };
}

/**
 * Run a local command as an MCP tool call. The MCP client's tool call IS the
 * confirmation, so the dangerous gate is bypassed (`yes`), and output is the
 * flattened JSON the json output mode would print.
 */
export async function runLocalCommand(
  command: RegisteredCommand,
  args: unknown,
  profileFlag: string | undefined
): Promise<string> {
  const input = command.input.parse(args ?? {});
  const ctx = buildContext({
    profile: profileFlag,
    output: 'json',
    raw: false,
    quiet: false,
    yes: true,
  });
  const result = await command.handler(ctx, input as never);
  const payload =
    result.data !== undefined
      ? result.verbatim
        ? result.data
        : flattenBody(result.data)
      : { message: result.message ?? 'ok' };
  return JSON.stringify(payload);
}
