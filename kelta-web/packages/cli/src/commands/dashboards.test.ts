import { AxiosError, AxiosHeaders } from 'axios';
import { Command } from 'commander';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { CommandContext, GlobalOptions } from '../registry/types.js';

const axios = {
  get: vi.fn(),
  post: vi.fn(),
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
const { dashboardCommands } = await import('./dashboards.js');

const DASHBOARD_ID = '11111111-2222-3333-4444-555555555555';

let stdout: string[];
let stderr: string[];
let stdinTty: boolean | undefined;
let stderrTty: boolean | undefined;

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
  stdinTty = process.stdin.isTTY;
  stderrTty = process.stderr.isTTY;
  process.stdin.isTTY = false;
  process.stderr.isTTY = false;
  process.exitCode = undefined;
  axios.get.mockReset();
  axios.post.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  process.stdin.isTTY = stdinTty as boolean;
  process.stderr.isTTY = stderrTty as boolean;
  process.exitCode = undefined;
});

async function kelta(...args: string[]): Promise<void> {
  const program = new Command().name('kelta').exitOverride();
  bindCommands(program, dashboardCommands);
  await program.parseAsync(args, { from: 'user' });
}

describe('dashboards validate / data run off-TTY without --yes', () => {
  it('validate POSTs to the validate endpoint and prints the response unchanged', async () => {
    const response = {
      valid: false,
      components: [{ id: 'c1', errors: [{ field: 'config', message: 'collection required' }] }],
    };
    axios.post.mockResolvedValueOnce({ data: response });

    await kelta('dashboards', 'validate', DASHBOARD_ID, '--output', 'json');

    expect(process.exitCode).toBeUndefined();
    expect(stderr.join('')).toBe('');
    expect(axios.get).not.toHaveBeenCalled();
    expect(axios.post).toHaveBeenCalledWith(`/api/dashboards/${DASHBOARD_ID}/validate`, {});
    expect(JSON.parse(stdout.join(''))).toEqual(response);
  });

  it('data POSTs to the data endpoint and prints the JSON:API envelope unflattened', async () => {
    const response = {
      data: {
        type: 'dashboard-data',
        id: DASHBOARD_ID,
        attributes: {
          dashboardId: DASHBOARD_ID,
          dashboardName: 'Overview',
          widgets: { c1: { type: 'metric', data: { value: 42 } } },
        },
      },
      meta: { widgetCount: 1, errorCount: 0 },
    };
    axios.post.mockResolvedValueOnce({ data: response });

    await kelta('dashboards', 'data', DASHBOARD_ID, '--output', 'json');

    expect(process.exitCode).toBeUndefined();
    expect(stderr.join('')).toBe('');
    expect(axios.post).toHaveBeenCalledWith(`/api/dashboards/${DASHBOARD_ID}/data`, {});
    expect(JSON.parse(stdout.join(''))).toEqual(response);
  });

  it('data resolves a dashboard name to its id and forwards --data as the body', async () => {
    axios.get.mockResolvedValueOnce({ data: { data: [{ id: 'dash-7' }] } });
    axios.post.mockResolvedValueOnce({ data: { data: { id: 'dash-7' } } });

    await kelta(
      'dashboards',
      'data',
      'Sales Overview',
      '--data',
      '{"timeRange":"LAST_30_DAYS"}',
      '--output',
      'json'
    );

    expect(process.exitCode).toBeUndefined();
    expect(axios.get).toHaveBeenCalledWith(
      '/api/dashboards?filter[name][EQ]=Sales%20Overview&page[size]=1'
    );
    expect(axios.post).toHaveBeenCalledWith('/api/dashboards/dash-7/data', {
      timeRange: 'LAST_30_DAYS',
    });
  });

  it('validate reports an unknown dashboard name as NOT_FOUND without POSTing', async () => {
    axios.get.mockResolvedValueOnce({ data: { data: [] } });

    await kelta('dashboards', 'validate', 'Missing', '--output', 'json');

    expect(process.exitCode).toBe(4);
    const payload = JSON.parse(stderr.join('')) as { error: { code: string; detail: string } };
    expect(payload.error.code).toBe('NOT_FOUND');
    expect(payload.error.detail).toBe('Dashboard "Missing" not found');
    expect(axios.post).not.toHaveBeenCalled();
  });

  it('reports a non-2xx response as an error carrying the API detail', async () => {
    const headers = new AxiosHeaders();
    axios.post.mockRejectedValueOnce(
      new AxiosError(
        'Request failed',
        'ERR_BAD_REQUEST',
        { headers },
        {},
        {
          status: 403,
          statusText: 'Forbidden',
          headers: {},
          config: { headers },
          data: {
            errors: [
              { status: '403', code: 'FORBIDDEN', detail: 'VIEW_ANALYTICS permission required' },
            ],
          },
        }
      )
    );

    await kelta('dashboards', 'validate', DASHBOARD_ID, '--output', 'json');

    expect(process.exitCode).toBe(1);
    const payload = JSON.parse(stderr.join('')) as { error: { code: string; detail: string } };
    expect(payload.error.code).toBe('FORBIDDEN');
    expect(payload.error.detail).toBe('VIEW_ANALYTICS permission required');
    expect(stdout.join('')).toBe('');
  });

  it('neither subcommand is marked destructive', () => {
    for (const name of ['validate', 'data']) {
      const def = dashboardCommands.find((c) => c.name === name);
      expect(def?.dangerous ?? false).toBe(false);
    }
  });
});
