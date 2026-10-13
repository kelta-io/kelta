import { Command } from 'commander';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { CommandContext, GlobalOptions } from '../registry/types.js';

const axios = {
  get: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
};

vi.mock('../context.js', () => ({
  buildContext: (global: GlobalOptions): CommandContext =>
    ({
      profile: { name: 'test' },
      global,
      log: vi.fn(),
      client: { getAxiosInstance: () => axios },
    }) as unknown as CommandContext,
}));
vi.mock('../update/passiveCheck.js', () => ({ maybeNotifyUpdate: () => Promise.resolve() }));

const { bindCommands } = await import('../registry/bind.js');
const { flowCommands } = await import('./flows.js');

const FLOW_ID = '11111111-2222-3333-4444-555555555555';
const DEFINITION = '{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}';

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
  axios.get.mockReset();
  axios.post.mockReset();
  axios.patch.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  process.exitCode = undefined;
});

async function kelta(...args: string[]): Promise<void> {
  const program = new Command().name('kelta').exitOverride();
  bindCommands(program, flowCommands);
  await program.parseAsync(args, { from: 'user' });
}

describe('kelta flows --step-logging', () => {
  it('create sends stepLogging, upper-casing the level', async () => {
    axios.post.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta(
      'flows',
      'create',
      '--name',
      'Ingest',
      '--type',
      'scheduled',
      '--definition',
      DEFINITION,
      '--step-logging',
      'errors_only',
      '--output',
      'json'
    );

    expect(process.exitCode).toBeUndefined();
    expect(axios.post).toHaveBeenCalledWith('/api/flows', {
      data: {
        type: 'flows',
        attributes: {
          name: 'Ingest',
          flowType: 'SCHEDULED',
          definition: JSON.parse(DEFINITION),
          active: false,
          stepLogging: 'ERRORS_ONLY',
        },
      },
    });
  });

  it('create without --step-logging leaves the server default (FULL) in place', async () => {
    axios.post.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta(
      'flows',
      'create',
      '--name',
      'Ingest',
      '--type',
      'AUTOLAUNCHED',
      '--definition',
      DEFINITION
    );

    const body = axios.post.mock.calls[0]?.[1] as { data: { attributes: Record<string, unknown> } };
    expect(body.data.attributes).not.toHaveProperty('stepLogging');
  });

  it('update patches stepLogging alone', async () => {
    axios.patch.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta('flows', 'update', FLOW_ID, '--step-logging', 'NONE', '--output', 'json');

    expect(process.exitCode).toBeUndefined();
    expect(axios.patch).toHaveBeenCalledWith(`/api/flows/${FLOW_ID}`, {
      data: { type: 'flows', id: FLOW_ID, attributes: { stepLogging: 'NONE' } },
    });
  });

  it('rejects an unknown level before calling the API', async () => {
    await kelta('flows', 'update', FLOW_ID, '--step-logging', 'VERBOSE');

    expect(process.exitCode).toBe(2);
    expect(axios.patch).not.toHaveBeenCalled();
  });

  it('list shows the STEP LOGGING column', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        data: [
          {
            id: FLOW_ID,
            type: 'flows',
            attributes: {
              name: 'Ingest',
              flowType: 'SCHEDULED',
              active: true,
              stepLogging: 'ERRORS_ONLY',
            },
          },
        ],
      },
    });

    await kelta('flows', 'list', '--output', 'table');

    const out = stdout.join('');
    expect(out).toContain('STEP LOGGING');
    expect(out).toContain('ERRORS_ONLY');
  });
});

describe('kelta flows --retention-days', () => {
  it('create sends retentionDays as a number', async () => {
    axios.post.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta(
      'flows',
      'create',
      '--name',
      'Poll',
      '--type',
      'SCHEDULED',
      '--definition',
      DEFINITION,
      '--retention-days',
      '7',
      '--output',
      'json'
    );

    expect(process.exitCode).toBeUndefined();
    const body = axios.post.mock.calls[0]?.[1] as { data: { attributes: Record<string, unknown> } };
    expect(body.data.attributes.retentionDays).toBe(7);
  });

  it('create without --retention-days leaves the platform-wide window in place', async () => {
    axios.post.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta(
      'flows',
      'create',
      '--name',
      'Poll',
      '--type',
      'SCHEDULED',
      '--definition',
      DEFINITION
    );

    const body = axios.post.mock.calls[0]?.[1] as { data: { attributes: Record<string, unknown> } };
    expect(body.data.attributes).not.toHaveProperty('retentionDays');
  });

  it('update patches retentionDays alone', async () => {
    axios.patch.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta('flows', 'update', FLOW_ID, '--retention-days', '120', '--output', 'json');

    expect(process.exitCode).toBeUndefined();
    expect(axios.patch).toHaveBeenCalledWith(`/api/flows/${FLOW_ID}`, {
      data: { type: 'flows', id: FLOW_ID, attributes: { retentionDays: 120 } },
    });
  });

  it('update --retention-days default clears the override with null', async () => {
    axios.patch.mockResolvedValueOnce({ data: { data: { id: FLOW_ID } } });

    await kelta('flows', 'update', FLOW_ID, '--retention-days', 'default', '--output', 'json');

    expect(axios.patch).toHaveBeenCalledWith(`/api/flows/${FLOW_ID}`, {
      data: { type: 'flows', id: FLOW_ID, attributes: { retentionDays: null } },
    });
  });

  it.each(['0', '-3', '7.5', 'week', '3651'])(
    'rejects %s before calling the API',
    async (value) => {
      await kelta('flows', 'update', FLOW_ID, '--retention-days', value);

      expect(process.exitCode).toBe(2);
      expect(axios.patch).not.toHaveBeenCalled();
    }
  );

  it('create does not accept "default"', async () => {
    await kelta(
      'flows',
      'create',
      '--name',
      'Poll',
      '--type',
      'SCHEDULED',
      '--definition',
      DEFINITION,
      '--retention-days',
      'default'
    );

    expect(process.exitCode).toBe(2);
    expect(axios.post).not.toHaveBeenCalled();
  });

  it('list shows the RETENTION DAYS column', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        data: [
          {
            id: FLOW_ID,
            type: 'flows',
            attributes: { name: 'Poll', flowType: 'SCHEDULED', active: true, retentionDays: 7 },
          },
        ],
      },
    });

    await kelta('flows', 'list', '--output', 'table');

    const out = stdout.join('');
    expect(out).toContain('RETENTION DAYS');
    expect(out).toMatch(/Poll.*7/);
  });
});
