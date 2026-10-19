import { describe, it, expect, vi } from 'vitest';
import { AxiosError, AxiosHeaders, type AxiosInstance } from 'axios';
import type { CommandContext } from '../registry/types.js';
import { CliError, EXIT, mapError, toErrorPayload } from '../errors.js';
import {
  environmentCommands,
  parseItemSpec,
  runPromoteApprove,
  runPromoteCreate,
  runPromoteExecute,
  runSandboxCreate,
  runSandboxDelete,
  runSandboxList,
} from './environments.js';

function client(overrides: {
  get?: ReturnType<typeof vi.fn>;
  post?: ReturnType<typeof vi.fn>;
  delete?: ReturnType<typeof vi.fn>;
}) {
  return {
    get: overrides.get ?? vi.fn(),
    post: overrides.post ?? vi.fn(),
    delete: overrides.delete ?? vi.fn(),
  } as unknown as AxiosInstance;
}

describe('parseItemSpec', () => {
  it('splits TYPE:name on the first colon', () => {
    expect(parseItemSpec('collection:orders')).toEqual({
      itemType: 'collection',
      itemName: 'orders',
    });
    expect(parseItemSpec('flow:a:b')).toEqual({ itemType: 'flow', itemName: 'a:b' });
  });

  it('rejects malformed specs', () => {
    expect(() => parseItemSpec('nocolon')).toThrow(/expected TYPE:name/);
    expect(() => parseItemSpec('type:')).toThrow(/expected TYPE:name/);
  });
});

describe('sandbox ops', () => {
  it('creates a sandbox with type SANDBOX and returns the resource', async () => {
    const post = vi.fn().mockResolvedValue({
      status: 202,
      data: { data: { id: 'env1', attributes: { status: 'PENDING' } } },
    });
    const env = await runSandboxCreate(client({ post }), { name: 'dev', description: 'd' });
    expect(post).toHaveBeenCalledWith('/api/environments', {
      data: { attributes: { name: 'dev', type: 'SANDBOX', description: 'd' } },
    });
    expect(env.id).toBe('env1');
  });

  it('creates an empty sandbox with cloneMetadata: false when empty is set', async () => {
    const post = vi.fn().mockResolvedValue({
      status: 202,
      data: { data: { id: 'env1', attributes: { status: 'ACTIVE', clone_mode: 'EMPTY' } } },
    });
    await runSandboxCreate(client({ post }), { name: 'dev', empty: true });
    expect(post).toHaveBeenCalledWith('/api/environments', {
      data: { attributes: { name: 'dev', type: 'SANDBOX', cloneMetadata: false } },
    });
  });

  function createCtx(post: ReturnType<typeof vi.fn>): CommandContext {
    return {
      profile: { name: 'test' },
      global: { raw: false, quiet: false, yes: true },
      log: vi.fn(),
      client: { getAxiosInstance: () => client({ post }) },
    } as unknown as CommandContext;
  }

  it('kelta sandbox create --empty sends cloneMetadata: false and prints the clone mode', async () => {
    const post = vi.fn().mockResolvedValue({
      status: 202,
      data: { data: { id: 'env1', attributes: { status: 'ACTIVE', clone_mode: 'EMPTY' } } },
    });
    const def = environmentCommands.find((c) => c.group === 'sandbox' && c.name === 'create')!;
    expect(def.options?.map((o) => o.flag)).toContain('--empty');

    const result = await def.handler(
      createCtx(post),
      def.input.parse({ name: 'dev', empty: true }) as never
    );

    expect(post).toHaveBeenCalledWith('/api/environments', {
      data: { attributes: { name: 'dev', type: 'SANDBOX', cloneMetadata: false } },
    });
    expect((result as { human: string }).human).toContain('status: ACTIVE, clone: EMPTY');
  });

  it('kelta sandbox create without --empty does not send cloneMetadata', async () => {
    const post = vi.fn().mockResolvedValue({
      status: 202,
      data: { data: { id: 'env1', attributes: { status: 'CREATING', clone_mode: 'CLONED' } } },
    });
    const def = environmentCommands.find((c) => c.group === 'sandbox' && c.name === 'create')!;

    await def.handler(createCtx(post), def.input.parse({ name: 'dev' }) as never);

    const body = post.mock.calls[0][1] as { data: { attributes: Record<string, unknown> } };
    expect(body.data.attributes).not.toHaveProperty('cloneMetadata');
    expect(body.data.attributes).toEqual({ name: 'dev', type: 'SANDBOX' });
  });

  it('sandbox status and list show the clone mode', async () => {
    const env = {
      id: 'env1',
      attributes: { name: 'dev', type: 'SANDBOX', status: 'ACTIVE', clone_mode: 'EMPTY' },
    };
    const get = vi.fn().mockResolvedValue({ status: 200, data: { data: [env] } });
    const ctx = {
      profile: { name: 'test' },
      global: { raw: false, quiet: false, yes: true },
      log: vi.fn(),
      client: { getAxiosInstance: () => client({ get }) },
    } as unknown as CommandContext;
    const status = environmentCommands.find((c) => c.group === 'sandbox' && c.name === 'status')!;
    const list = environmentCommands.find((c) => c.group === 'sandbox' && c.name === 'list')!;

    const statusResult = await status.handler(ctx, status.input.parse({ envId: 'env1' }) as never);
    const listResult = await list.handler(ctx, list.input.parse({}) as never);

    expect((statusResult as { human: string }).human).toContain('Clone:  EMPTY');
    expect((listResult as { columns: { key: string }[] }).columns.map((c) => c.key)).toContain(
      'clone_mode'
    );
  });

  it('sandbox create prints the sign-in and API URLs with the one-time credentials', async () => {
    const post = vi.fn().mockResolvedValue({
      status: 202,
      data: {
        data: {
          id: 'env1',
          attributes: {
            status: 'CREATING',
            sandboxSlug: 'acme--dev',
            adminUsername: 'acme--dev-admin',
            adminInitialPassword: 'S3cretOneTime',
            signInUrl: 'https://console.example.test/acme--dev/login',
            apiBaseUrl: 'https://gw.example.test/acme--dev',
          },
        },
      },
    });
    const ctx = {
      profile: { name: 'test' },
      global: { raw: false, quiet: false, yes: true },
      log: vi.fn(),
      client: { getAxiosInstance: () => client({ post }) },
    } as unknown as CommandContext;
    const def = environmentCommands.find((c) => c.group === 'sandbox' && c.name === 'create')!;

    const result = await def.handler(ctx, def.input.parse({ name: 'dev' }) as never);

    const human = (result as { human: string }).human;
    expect(human).toContain('Sandbox slug:   acme--dev');
    expect(human).toContain('Admin username: acme--dev-admin');
    expect(human).toContain('Admin password: S3cretOneTime');
    expect(human).toContain('Sign-in URL:    https://console.example.test/acme--dev/login');
    expect(human).toContain('API base URL:   https://gw.example.test/acme--dev');
  });

  it('throws on failure statuses', async () => {
    const post = vi.fn().mockResolvedValue({ status: 403, data: {} });
    await expect(runSandboxCreate(client({ post }), { name: 'dev' })).rejects.toThrow(
      /Request failed/
    );
  });

  const conflictDetail =
    "Environment name 'dev' is already used by environment 'dev' (id env-old, status ARCHIVED) " +
    'in this tenant. Choose a different name.';
  const conflictBody = {
    errors: [
      {
        status: '409',
        code: 'ENVIRONMENT_NAME_CONFLICT',
        title: 'Conflict',
        detail: conflictDetail,
        source: { pointer: '/data/attributes/name' },
        meta: { conflictingEnvironment: { id: 'env-old', name: 'dev', status: 'ARCHIVED' } },
      },
    ],
  };

  function rejectedWith(status: number, body: unknown): AxiosError {
    const config = { headers: new AxiosHeaders() };
    return new AxiosError(
      `Request failed with status code ${String(status)}`,
      'ERR_BAD_REQUEST',
      config as never,
      {},
      { status, statusText: '', headers: {}, config: config as never, data: body }
    );
  }

  async function sandboxCreateFailure(post: ReturnType<typeof vi.fn>): Promise<CliError> {
    const ctx = {
      profile: { name: 'test' },
      global: { raw: false, quiet: false, yes: true },
      log: vi.fn(),
      client: { getAxiosInstance: () => client({ post }) },
    } as unknown as CommandContext;
    const def = environmentCommands.find((c) => c.group === 'sandbox' && c.name === 'create')!;
    try {
      await def.handler(ctx, def.input.parse({ name: 'dev' }) as never);
    } catch (error) {
      return mapError(error);
    }
    throw new Error('sandbox create did not fail');
  }

  it('sandbox create prints the server 409 detail naming the archived sandbox unchanged', async () => {
    const post = vi.fn().mockRejectedValue(rejectedWith(409, conflictBody));

    const mapped = await sandboxCreateFailure(post);

    expect(mapped.message).toBe(conflictDetail);
    expect(mapped.code).toBe('ENVIRONMENT_NAME_CONFLICT');
    expect(mapped.exitCode).toBe(EXIT.CONFLICT);
    const payload = JSON.parse(toErrorPayload(mapped)) as { error: Record<string, unknown> };
    expect(payload.error.detail).toBe(conflictDetail);
    expect(payload.error.meta).toEqual({
      conflictingEnvironment: { id: 'env-old', name: 'dev', status: 'ARCHIVED' },
    });
  });

  it('sandbox create keeps the 409 detail when the client resolves non-2xx responses', async () => {
    const post = vi.fn().mockResolvedValue({ status: 409, data: conflictBody });

    const mapped = await sandboxCreateFailure(post);

    expect(mapped.message).toBe(conflictDetail);
    expect(mapped.exitCode).toBe(EXIT.CONFLICT);
  });

  it('sandbox create prints the server 400 reason and pointer, not the generic status text', async () => {
    const post = vi.fn().mockRejectedValue(
      rejectedWith(400, {
        errors: [
          {
            status: '400',
            code: 'VALIDATION_FAILED',
            detail: 'Sandbox name must contain letters or digits',
            source: { pointer: '/data/attributes/name' },
          },
        ],
      })
    );

    const mapped = await sandboxCreateFailure(post);

    expect(mapped.message).toBe('Sandbox name must contain letters or digits');
    expect(mapped.message).not.toMatch(/Request failed with status/);
    expect(mapped.source).toEqual({ pointer: '/data/attributes/name' });
  });

  it('sandbox create prints a legacy { error } body instead of the generic status text', async () => {
    const post = vi
      .fn()
      .mockRejectedValue(rejectedWith(400, { error: 'Environment name is required' }));

    const mapped = await sandboxCreateFailure(post);

    expect(mapped.message).toBe('Environment name is required');
  });

  it('lists environments defensively', async () => {
    const get = vi.fn().mockResolvedValue({ status: 200, data: { data: [{ id: 'e1' }] } });
    expect(await runSandboxList(client({ get }))).toEqual([{ id: 'e1' }]);
  });

  it('deletes a FAILED sandbox', async () => {
    const del = vi.fn().mockResolvedValue({ status: 200, data: { status: 'archived' } });
    await runSandboxDelete(client({ delete: del }), 'env-failed');
    expect(del).toHaveBeenCalledWith('/api/environments/env-failed');
  });

  it('deletes an ACTIVE sandbox', async () => {
    const del = vi.fn().mockResolvedValue({ status: 200, data: { status: 'archived' } });
    await runSandboxDelete(client({ delete: del }), 'env-active');
    expect(del).toHaveBeenCalledWith('/api/environments/env-active');
  });

  it('throws on failure statuses', async () => {
    const del = vi.fn().mockResolvedValue({ status: 403, data: {} });
    await expect(runSandboxDelete(client({ delete: del }), 'env1')).rejects.toThrow(
      /Request failed/
    );
  });
});

describe('promotion ops', () => {
  it('creates a promotion with items', async () => {
    const post = vi.fn().mockResolvedValue({ status: 201, data: { data: { id: 'p1' } } });
    await runPromoteCreate(client({ post }), {
      sourceEnvId: 's',
      targetEnvId: 't',
      promotionType: 'SELECTIVE',
      conflictMode: 'SKIP',
      items: [{ itemType: 'collection', itemName: 'orders' }],
    });
    expect(post).toHaveBeenCalledWith('/api/promotions', {
      data: {
        attributes: {
          sourceEnvId: 's',
          targetEnvId: 't',
          promotionType: 'SELECTIVE',
          conflictMode: 'SKIP',
          items: [{ itemType: 'collection', itemName: 'orders' }],
        },
      },
    });
  });

  it('surfaces the self-approval 409 clearly', async () => {
    const post = vi.fn().mockResolvedValue({ status: 409, data: {} });
    await expect(runPromoteApprove(client({ post }), 'p1')).rejects.toThrow(/cannot be approved/);
  });

  it('execute without wait returns null after acceptance', async () => {
    const post = vi.fn().mockResolvedValue({ status: 202, data: {} });
    expect(await runPromoteExecute(client({ post }), 'p1')).toBeNull();
  });

  it('execute with wait polls to a terminal status', async () => {
    const post = vi.fn().mockResolvedValue({ status: 202, data: {} });
    const get = vi
      .fn()
      .mockResolvedValueOnce({
        status: 200,
        data: { data: { id: 'p1', attributes: { status: 'RUNNING' } } },
      })
      .mockResolvedValueOnce({
        status: 200,
        data: { data: { id: 'p1', attributes: { status: 'COMPLETED' } } },
      });
    const result = await runPromoteExecute(client({ post, get }), 'p1', {
      wait: true,
      intervalMs: 1,
    });
    expect(result?.attributes?.status).toBe('COMPLETED');
    expect(get).toHaveBeenCalledTimes(2);
  });
});
