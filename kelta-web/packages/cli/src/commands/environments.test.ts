import { describe, it, expect, vi } from 'vitest';
import type { AxiosInstance } from 'axios';
import {
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

  it('throws on failure statuses', async () => {
    const post = vi.fn().mockResolvedValue({ status: 403, data: {} });
    await expect(runSandboxCreate(client({ post }), { name: 'dev' })).rejects.toThrow(
      /Request failed/
    );
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
