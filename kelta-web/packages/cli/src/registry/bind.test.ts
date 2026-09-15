import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Command } from 'commander';
import { z } from 'zod';
import { bindCommands } from './bind.js';
import { defineCommand, type RegisteredCommand } from './types.js';
import { CliError } from '../errors.js';

let stdout: string[];
let stderr: string[];

beforeEach(() => {
  stdout = [];
  stderr = [];
  vi.spyOn(process.stdout, 'write').mockImplementation((chunk: unknown) => {
    stdout.push(String(chunk));
    return true;
  });
  vi.spyOn(process.stderr, 'write').mockImplementation((chunk: unknown) => {
    stderr.push(String(chunk));
    return true;
  });
  process.exitCode = undefined;
});

afterEach(() => {
  vi.restoreAllMocks();
  process.exitCode = undefined;
});

function program(defs: RegisteredCommand[]): Command {
  const prog = new Command();
  prog
    .name('kelta')
    .exitOverride()
    .option('--profile <name>', 'profile')
    .option('--output <format>', 'format')
    .option('--raw', 'raw')
    .option('--quiet', 'quiet')
    .option('--yes', 'yes');
  bindCommands(prog, defs);
  return prog;
}

const echo = defineCommand({
  group: 'things',
  name: 'echo',
  summary: 'echo input back',
  requiresAuth: false,
  positionals: [{ name: 'target', description: 'target', required: true }],
  options: [
    { flag: '--count <n>', description: 'count', default: '1' },
    { flag: '--tag <tag>', description: 'tags', repeatable: true },
  ],
  input: z.object({
    target: z.string(),
    count: z.coerce.number().int(),
    tag: z.array(z.string()).default([]),
  }),
  handler: (_ctx, input) => Promise.resolve({ data: input }),
});

const multiInvalid = defineCommand({
  group: 'things',
  name: 'multi-invalid',
  summary: 'fails with two validation errors',
  requiresAuth: false,
  input: z.object({}),
  handler: () => {
    throw new CliError('name is required', {
      code: 'VALIDATION_FAILED',
      exitCode: 1,
      status: 400,
      source: { pointer: '/data/attributes/name' },
      errors: [
        {
          code: 'VALIDATION_FAILED',
          detail: 'name is required',
          source: { pointer: '/data/attributes/name' },
        },
        {
          code: 'VALIDATION_FAILED',
          detail: 'amount must be >= 0',
          source: { pointer: '/data/attributes/amount' },
        },
      ],
    });
  },
});

const boom = defineCommand({
  group: 'things',
  name: 'boom',
  summary: 'destructive',
  requiresAuth: false,
  dangerous: true,
  input: z.object({}),
  handler: () => Promise.resolve({ message: 'boomed' }),
});

describe('bindCommands', () => {
  it('parses positionals, defaults, coercion, and repeatable flags into handler input', async () => {
    await program([echo as RegisteredCommand]).parseAsync(
      ['things', 'echo', 'abc', '--count', '3', '--tag', 'x', '--tag', 'y', '--output', 'json'],
      { from: 'user' }
    );
    expect(process.exitCode).toBeUndefined();
    const printed = JSON.parse(stdout.join('')) as { target: string; count: number; tag: string[] };
    expect(printed).toEqual({ target: 'abc', count: 3, tag: ['x', 'y'] });
  });

  it('maps zod validation failure to exit 2 with a machine-readable stderr line', async () => {
    await program([echo as RegisteredCommand]).parseAsync(
      ['things', 'echo', 'abc', '--count', 'NaN', '--output', 'json'],
      { from: 'user' }
    );
    expect(process.exitCode).toBe(2);
    const payload = JSON.parse(stderr.join('')) as { error: { code: string } };
    expect(payload.error.code).toBe('INVALID_ARGUMENTS');
    expect(stdout.join('')).toBe('');
  });

  it('blocks dangerous commands off-TTY without --yes', async () => {
    await program([boom as RegisteredCommand]).parseAsync(['things', 'boom', '--output', 'json'], {
      from: 'user',
    });
    expect(process.exitCode).toBe(2);
    const payload = JSON.parse(stderr.join('')) as { error: { code: string } };
    expect(payload.error.code).toBe('CONFIRMATION_REQUIRED');
  });

  it('runs dangerous commands with --yes', async () => {
    await program([boom as RegisteredCommand]).parseAsync(
      ['things', 'boom', '--yes', '--output', 'json'],
      { from: 'user' }
    );
    expect(process.exitCode).toBeUndefined();
    expect(JSON.parse(stdout.join(''))).toEqual({ message: 'boomed' });
  });

  it('surfaces every error of a multi-error failure, not just the first (json)', async () => {
    await program([multiInvalid as RegisteredCommand]).parseAsync(
      ['things', 'multi-invalid', '--output', 'json'],
      { from: 'user' }
    );
    expect(process.exitCode).toBe(1);
    const payload = JSON.parse(stderr.join('')) as {
      error: { source?: { pointer?: string } };
      errors: { detail: string }[];
    };
    expect(payload.error.source?.pointer).toBe('/data/attributes/name');
    expect(payload.errors.map((e) => e.detail)).toEqual([
      'name is required',
      'amount must be >= 0',
    ]);
  });

  it('surfaces every error of a multi-error failure, not just the first (table)', async () => {
    await program([multiInvalid as RegisteredCommand]).parseAsync(
      ['things', 'multi-invalid', '--output', 'table'],
      { from: 'user' }
    );
    expect(process.exitCode).toBe(1);
    const printed = stderr.join('');
    expect(printed).toContain('name is required');
    expect(printed).toContain('amount must be >= 0');
  });

  it('rejects an unknown --output format with exit 2', async () => {
    await program([echo as RegisteredCommand]).parseAsync(
      ['things', 'echo', 'abc', '--output', 'xml'],
      { from: 'user' }
    );
    expect(process.exitCode).toBe(2);
  });
});
