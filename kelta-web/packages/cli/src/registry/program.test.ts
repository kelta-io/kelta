import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { allCommands } from './registry.js';
import { createProgram, outputFromArgv, runCli } from './program.js';
import { VERSION } from '../version.js';

let stdout: string[];
let stderr: string[];
let stderrIsTTY: boolean | undefined;

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
  stderrIsTTY = process.stderr.isTTY;
  process.exitCode = undefined;
});

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(process.stderr, 'isTTY', { value: stderrIsTTY, configurable: true });
  process.exitCode = undefined;
});

function onTty(): void {
  Object.defineProperty(process.stderr, 'isTTY', { value: true, configurable: true });
}

async function kelta(...args: string[]): Promise<void> {
  await runCli(createProgram(allCommands), args);
}

const argumentErrors = [
  {
    case: 'missing required argument',
    args: ['records', 'get', 'customers'],
    detail: "missing required argument 'id'",
  },
  {
    case: 'unknown option',
    args: ['records', 'list', 'customers', '--colour', 'red'],
    detail: "unknown option '--colour'",
  },
  {
    case: 'unknown command',
    args: ['recrods', 'list', 'customers'],
    detail: "unknown command 'recrods'\n(Did you mean records?)",
  },
];

describe('argument errors', () => {
  it.each(argumentErrors)('$case → Error [INVALID_ARGUMENTS] on a TTY, exit 2', async (e) => {
    onTty();
    await kelta(...e.args);
    expect(process.exitCode).toBe(2);
    expect(stderr.join('')).toBe(`Error [INVALID_ARGUMENTS]: ${e.detail}\n`);
    expect(stdout.join('')).toBe('');
  });

  it.each(argumentErrors)(
    '$case → one-line JSON envelope with --output json, exit 2',
    async (e) => {
      onTty();
      await kelta(...e.args, '--output', 'json');
      expect(process.exitCode).toBe(2);
      const lines = stderr.join('').split('\n');
      expect(lines).toHaveLength(2);
      expect(lines[1]).toBe('');
      expect(JSON.parse(lines[0])).toEqual({
        error: { code: 'INVALID_ARGUMENTS', detail: e.detail },
      });
      expect(stdout.join('')).toBe('');
    }
  );

  it('defaults to the JSON envelope off-TTY and reads --output=<fmt>', async () => {
    Object.defineProperty(process.stderr, 'isTTY', { value: false, configurable: true });
    await kelta('records', 'get', 'customers');
    expect(JSON.parse(stderr.join(''))).toMatchObject({ error: { code: 'INVALID_ARGUMENTS' } });

    stderr = [];
    await kelta('records', 'get', 'customers', '--output=table');
    expect(stderr.join('')).toMatch(/^Error \[INVALID_ARGUMENTS\]: /);
    expect(process.exitCode).toBe(2);
  });
});

describe('help and version', () => {
  it('kelta --help prints usage on stdout and exits 0', async () => {
    await kelta('--help');
    expect(process.exitCode).toBe(0);
    expect(stdout.join('')).toContain('Usage: kelta');
    expect(stderr.join('')).toBe('');
  });

  it('kelta --version prints the version on stdout and exits 0', async () => {
    await kelta('--version');
    expect(process.exitCode).toBe(0);
    expect(stdout.join('')).toBe(`${VERSION}\n`);
    expect(stderr.join('')).toBe('');
  });

  it('kelta <group> <cmd> --help lists the command examples', async () => {
    await kelta('records', 'list', '--help');
    expect(process.exitCode).toBe(0);
    const help = stdout.join('');
    expect(help).toContain('Examples:');
    const recordsList = allCommands.find((c) => c.group === 'records' && c.name === 'list');
    for (const example of recordsList?.examples ?? []) {
      expect(help).toContain(`$ ${example.cmd}`);
      if (example.description) expect(help).toContain(example.description);
    }
    expect(recordsList?.examples.length).toBeGreaterThan(0);
  });
});

describe('outputFromArgv', () => {
  it.each([
    [['x', '--output', 'json'], 'json'],
    [['--output=yaml', 'x'], 'yaml'],
    [['--output', 'json', '--output', 'table'], 'table'],
    [['--output', 'xml'], undefined],
    [['--', '--output', 'json'], undefined],
    [['x'], undefined],
  ])('%j → %s', (args, expected) => {
    expect(outputFromArgv(args)).toBe(expected);
  });
});
