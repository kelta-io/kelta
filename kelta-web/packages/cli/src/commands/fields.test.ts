import { describe, it, expect, vi } from 'vitest';
import { Command } from 'commander';
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
  const patch = vi.fn().mockResolvedValue({ data: { data: { id: 'f1' } } });
  const ctx = {
    profile: { name: 'test' },
    global: { raw: false, quiet: false, yes: true },
    log: vi.fn(),
    client: { getAxiosInstance: () => ({ get, post, patch }) },
  } as unknown as CommandContext;
  return { ctx, post, patch };
}

/** Parses real argv against the command's declared options, as `bindCommands` wires them. */
function parseArgv(name: string, positionals: Record<string, string>, argv: string[]) {
  const def = fieldCommands.find((c) => c.name === name);
  if (!def) throw new Error(`no fields ${name} command`);
  const command = new Command().exitOverride();
  for (const opt of def.options ?? []) command.option(opt.flag, opt.description);
  command.parse(argv, { from: 'user' });
  return { def, input: def.input.parse({ ...command.opts(), ...positionals }) };
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

describe('fields add --values', () => {
  function postBodies(post: ReturnType<typeof vi.fn>) {
    return post.mock.calls.map(([url, body]) => ({
      url: url as string,
      attributes: (body as { data: { attributes: Record<string, unknown> } }).data.attributes,
    }));
  }

  it('creates the field without fieldTypeConfig, then one FIELD-scoped value per entry', async () => {
    const { ctx, post } = fakeCtx();
    const def = addCommand();
    const input = def.input.parse({
      collection: 'deals',
      name: 'stage',
      type: 'picklist',
      values: 'a,b,c',
    });
    await def.handler(ctx, input as never);

    const calls = postBodies(post);
    expect(calls.map((c) => c.url)).toEqual([
      '/api/fields',
      '/api/picklist-values',
      '/api/picklist-values',
      '/api/picklist-values',
    ]);
    expect(calls[0]?.attributes).not.toHaveProperty('fieldTypeConfig');
    expect(calls[0]?.attributes.type).toBe('PICKLIST');
    expect(calls.slice(1).map((c) => c.attributes)).toEqual(
      ['a', 'b', 'c'].map((value, sortOrder) => ({
        value,
        label: value,
        picklistSourceType: 'FIELD',
        picklistSourceId: 'f1',
        sortOrder,
        isActive: true,
      }))
    );
  });

  it('rejects --values together with --picklist before any POST', async () => {
    const { ctx, post } = fakeCtx();
    const def = addCommand();
    const input = def.input.parse({
      collection: 'deals',
      name: 'stage',
      type: 'picklist',
      picklist: 'stages',
      values: 'a,b',
    });
    await expect(def.handler(ctx, input as never)).rejects.toMatchObject({
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
    expect(post).not.toHaveBeenCalled();
  });

  it('names both --picklist and --values when a picklist has neither', async () => {
    const { ctx, post } = fakeCtx();
    const def = addCommand();
    const input = def.input.parse({ collection: 'deals', name: 'stage', type: 'picklist' });
    const run = def.handler(ctx, input as never);
    await expect(run).rejects.toMatchObject({ exitCode: EXIT.USAGE });
    await expect(run).rejects.toThrow(/--picklist.*--values/);
    expect(post).not.toHaveBeenCalled();
  });
});

describe('--system-maintained', () => {
  function sentAttributes(call: unknown[] | undefined): Record<string, unknown> {
    return (call?.[1] as { data: { attributes: Record<string, unknown> } }).data.attributes;
  }

  it('fields add --system-maintained sends systemMaintained: true', async () => {
    const { ctx, post } = fakeCtx();
    const { def, input } = parseArgv('add', { collection: 'inspections' }, [
      '--name',
      'failure_count',
      '--type',
      'number',
      '--system-maintained',
    ]);
    await def.handler(ctx, input as never);

    expect(post).toHaveBeenCalledWith('/api/fields', expect.anything());
    expect(sentAttributes(post.mock.calls[0])).toMatchObject({
      name: 'failure_count',
      systemMaintained: true,
    });
  });

  it('fields add without the switch leaves systemMaintained to the server default', async () => {
    const { ctx, post } = fakeCtx();
    const { def, input } = parseArgv('add', { collection: 'inspections' }, [
      '--name',
      'site',
      '--type',
      'text',
    ]);
    await def.handler(ctx, input as never);

    expect(sentAttributes(post.mock.calls[0])).not.toHaveProperty('systemMaintained');
  });

  it.each([
    ['true', true],
    ['false', false],
  ])('fields update --system-maintained %s sets systemMaintained: %s', async (flag, expected) => {
    const { ctx, patch } = fakeCtx();
    const { def, input } = parseArgv('update', { fieldId: 'f1' }, ['--system-maintained', flag]);
    await def.handler(ctx, input as never);

    expect(patch).toHaveBeenCalledWith('/api/fields/f1', {
      data: { type: 'fields', id: 'f1', attributes: { systemMaintained: expected } },
    });
  });

  it('fields update rejects a --system-maintained value other than true|false', () => {
    expect(() => parseArgv('update', { fieldId: 'f1' }, ['--system-maintained', 'yes'])).toThrow();
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
