import { describe, it, expect, vi } from 'vitest';
import type { CommandContext } from '../registry/types.js';
import { renderResult } from '../render/render.js';
import { allCommands } from '../registry/registry.js';
import { notificationCommands } from './notifications.js';

const PAGE = {
  data: [
    {
      type: 'notifications',
      id: 'n-2',
      attributes: {
        title: 'Order approved',
        body: 'Order 123 was approved',
        link: '/app/orders/123',
        level: 'INFO',
        readAt: null,
        createdAt: '2026-10-24T10:00:00Z',
      },
    },
    {
      type: 'notifications',
      id: 'n-1',
      attributes: {
        title: 'Welcome',
        body: 'Hello',
        link: null,
        level: 'INFO',
        readAt: '2026-10-23T09:00:00Z',
        createdAt: '2026-10-23T08:00:00Z',
      },
    },
  ],
  meta: { totalCount: 2, unreadCount: 1 },
};

function command() {
  const def = notificationCommands.find((c) => c.name === 'list');
  if (!def) throw new Error('no notifications list command');
  return def;
}

function fakeCtx() {
  const get = vi.fn().mockResolvedValue({ data: PAGE });
  const ctx = {
    profile: { name: 'test' },
    global: { raw: false, quiet: false, yes: false },
    log: vi.fn(),
    client: { getAxiosInstance: () => ({ get }) },
  } as unknown as CommandContext;
  return { ctx, get };
}

describe('notifications list', () => {
  it('is registered as `kelta notifications list`', () => {
    expect(allCommands.some((c) => c.group === 'notifications' && c.name === 'list')).toBe(true);
  });

  it('lists all of the caller’s notifications by default', async () => {
    const { ctx, get } = fakeCtx();
    const def = command();

    const result = await def.handler(ctx, def.input.parse({}) as never);

    expect(get).toHaveBeenCalledWith('/api/me/notifications?page%5Bnumber%5D=1&page%5Bsize%5D=20');
    const table = renderResult(result, { format: 'table', raw: false, quiet: false });
    expect(table).toContain('Order approved');
    expect(table).toContain('Welcome');
    expect(table).toContain('1 unread');
  });

  it('adds unread=true with --unread', async () => {
    const { ctx, get } = fakeCtx();
    const def = command();

    await def.handler(ctx, def.input.parse({ unread: true, size: '50' }) as never);

    expect(get).toHaveBeenCalledWith(
      '/api/me/notifications?unread=true&page%5Bnumber%5D=1&page%5Bsize%5D=50'
    );
  });

  it('prints the notifications as JSON for pipes', async () => {
    const { ctx } = fakeCtx();
    const def = command();

    const result = await def.handler(ctx, def.input.parse({}) as never);
    const json = JSON.parse(renderResult(result, { format: 'json', raw: false, quiet: false }));

    expect(json).toEqual(
      expect.arrayContaining([expect.objectContaining({ id: 'n-2', title: 'Order approved' })])
    );
  });
});
