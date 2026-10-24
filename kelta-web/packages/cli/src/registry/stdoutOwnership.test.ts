import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Command } from 'commander';
import { z } from 'zod';
import { bindCommands } from './bind.js';
import { defineCommand, type RegisteredCommand } from './types.js';

/**
 * stdout of `mcp serve` IS the JSON-RPC transport. Any extra byte the
 * dispatcher writes — a rendered handler result, an update hint — corrupts the
 * stream and the MCP client rejects the whole connection with a schema error.
 *
 * Regression: `mcp serve` returned {} and the dispatcher rendered it, appending
 * {"message":"ok"} to the protocol stream on shutdown. Claude Desktop failed
 * with invalid_union / unrecognized_keys ["message"].
 */
let written: string[];

beforeEach(() => {
  written = [];
  vi.spyOn(process.stdout, 'write').mockImplementation((chunk: unknown) => {
    written.push(String(chunk));
    return true;
  });
  vi.spyOn(process.stderr, 'write').mockImplementation(() => true);
  process.exitCode = undefined;
});

afterEach(() => {
  vi.restoreAllMocks();
  process.exitCode = undefined;
});

function run(def: RegisteredCommand, argv: string[]) {
  const program = new Command();
  program.name('kelta').exitOverride().option('--output <format>', 'format');
  bindCommands(program, [def]);
  return program.parseAsync(argv, { from: 'user' });
}

const serveLike = defineCommand({
  group: 'mcp',
  name: 'serve',
  summary: 'owns stdout',
  requiresAuth: false,
  ownsStdout: true,
  examples: [{ cmd: 'kelta mcp serve' }],
  input: z.object({}),
  // mirrors the real handler: returns an empty result after the transport closes
  handler: () => Promise.resolve({}),
});

const normal = defineCommand({
  group: 'things',
  name: 'noop',
  summary: 'ordinary command',
  requiresAuth: false,
  examples: [{ cmd: 'kelta things noop' }],
  input: z.object({}),
  handler: () => Promise.resolve({}),
});

describe('stdout ownership', () => {
  it('writes NOTHING to stdout for an ownsStdout command', async () => {
    await run(serveLike as RegisteredCommand, ['mcp', 'serve', '--output', 'json']);
    expect(written.join('')).toBe('');
  });

  it('still renders ordinary commands (guard is not blanket-suppressing output)', async () => {
    await run(normal as RegisteredCommand, ['things', 'noop', '--output', 'json']);
    // this is the exact frame that corrupted the MCP stream — correct here, fatal there
    expect(JSON.parse(written.join(''))).toEqual({ message: 'ok' });
  });

  it('the real mcp serve command is flagged ownsStdout', async () => {
    const { mcpCommands } = await import('../commands/mcp.js');
    const serve = mcpCommands.find((command) => command.name === 'serve');
    expect(serve?.ownsStdout).toBe(true);
    // install prints config for humans and must NOT be suppressed
    expect(mcpCommands.find((command) => command.name === 'install')?.ownsStdout).toBeFalsy();
  });
});
