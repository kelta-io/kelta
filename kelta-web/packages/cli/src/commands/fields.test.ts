import { describe, it, expect, vi } from 'vitest';
import type { CommandContext } from '../registry/types.js';
import { CliError, EXIT } from '../errors.js';
import { fieldCommands, typedDefault } from './fields.js';

const COLLECTION_ID = '11111111-2222-3333-4444-555555555555';

function addCommand() {
  const def = fieldCommands.find((c) => c.name === 'add');
  if (!def) throw new Error('no fields add command');
  return def;
}

function fakeCtx() {
  const get = vi.fn().mockResolvedValue({ data: { data: { id: COLLECTION_ID } } });
  const post = vi.fn().mockResolvedValue({ data: { data: { id: 'f1' } } });
  const ctx = {
    profile: { name: 'test' },
    global: { raw: false, quiet: false, yes: true },
    log: vi.fn(),
    client: { getAxiosInstance: () => ({ get, post }) },
  } as unknown as CommandContext;
  return { ctx, post };
}

async function runAdd(type: string, defaultValue: string) {
  const { ctx, post } = fakeCtx();
  const def = addCommand();
  const input = def.input.parse({
    collection: 'settings',
    name: 'flag',
    type,
    default: defaultValue,
  });
  const run = def.handler(ctx, input as never);
  return { run, post };
}

function sentDefault(post: ReturnType<typeof vi.fn>): unknown {
  const body = post.mock.calls[0]?.[1] as { data: { attributes: Record<string, unknown> } };
  return body.data.attributes.defaultValue;
}

describe('fields add --default', () => {
  it('sends a BOOLEAN default as a JSON boolean', async () => {
    const { run, post } = await runAdd('BOOLEAN', 'false');
    await run;
    expect(post).toHaveBeenCalledWith('/api/fields', expect.anything());
    expect(sentDefault(post)).toBe(false);
  });

  it('sends an INTEGER default as a JSON number', async () => {
    const { run, post } = await runAdd('INTEGER', '0');
    await run;
    expect(sentDefault(post)).toBe(0);
  });

  it('rejects a BOOLEAN default other than true/false as a usage error', async () => {
    const { run, post } = await runAdd('BOOLEAN', 'maybe');
    await expect(run).rejects.toMatchObject({
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
    expect(post).not.toHaveBeenCalled();
  });
});

describe('typedDefault', () => {
  it.each([
    ['bool', 'true', true],
    ['number', '42', 42],
    ['LONG', '-7', -7],
    ['decimal', '1.5', 1.5],
    ['currency', '0', 0],
    ['percent', '12.5', 12.5],
    ['text', 'true', 'true'],
    ['DATE', '2026-01-01', '2026-01-01'],
  ])('maps %s %s to %o', (type, value, expected) => {
    expect(typedDefault(type, value)).toBe(expected);
  });

  it.each([
    ['BOOLEAN', 'TRUE'],
    ['INTEGER', 'abc'],
    ['INTEGER', '1.5'],
    ['DOUBLE', ''],
  ])('rejects %s %o', (type, value) => {
    expect(() => typedDefault(type, value)).toThrow(CliError);
  });
});
