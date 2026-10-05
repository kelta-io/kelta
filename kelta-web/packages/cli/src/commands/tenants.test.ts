import { describe, it, expect, vi } from 'vitest';
import type { CommandContext } from '../registry/types.js';
import { renderResult } from '../render/render.js';
import { allCommands } from '../registry/registry.js';
import { tenantCommands } from './tenants.js';

const TENANT_ID = '11111111-1111-1111-1111-111111111111';
const MINTED = {
  token: 'klt_secretsecretsecret',
  tokenPrefix: 'klt_secr',
  name: 'bootstrap-ops@example.com-2026-10-05T00:00:00Z',
  tenantId: TENANT_ID,
  userId: 'u1',
  expiresAt: '2026-10-05T01:00:00Z',
};

function command() {
  const def = tenantCommands.find((c) => c.name === 'bootstrap-token');
  if (!def) throw new Error('no bootstrap-token command');
  return def;
}

function fakeCtx(slugMatches: Array<{ id: string }>) {
  const get = vi.fn().mockResolvedValue({ data: { data: slugMatches } });
  const bootstrapToken = vi.fn().mockResolvedValue(MINTED);
  const log = vi.fn();
  const ctx = {
    profile: { name: 'test' },
    global: { raw: false, quiet: false, yes: false },
    log,
    client: {
      getAxiosInstance: () => ({ get }),
      admin: { tenants: { bootstrapToken } },
    },
  } as unknown as CommandContext;
  return { ctx, get, bootstrapToken, log };
}

describe('tenants bootstrap-token', () => {
  it('is registered as `kelta tenants bootstrap-token`', () => {
    expect(allCommands.some((c) => c.group === 'tenants' && c.name === 'bootstrap-token')).toBe(
      true
    );
  });

  it('resolves the slug to an id, mints with --expires-in, and prints only the token to stdout', async () => {
    const { ctx, get, bootstrapToken, log } = fakeCtx([{ id: TENANT_ID }]);
    const def = command();
    const input = def.input.parse({ slug: 'acme', expiresIn: '1h' });

    const result = await def.handler(ctx, input as never);

    expect(get).toHaveBeenCalledWith('/api/tenants?filter[slug][eq]=acme&page[size]=1');
    expect(bootstrapToken).toHaveBeenCalledWith(TENANT_ID, { expiresIn: '1h' });
    for (const format of ['table', 'json'] as const) {
      expect(renderResult(result, { format, raw: false, quiet: false })).toBe(`${MINTED.token}\n`);
    }
    expect(renderResult(result, { format: 'json', raw: false, quiet: true })).toBe(
      `${MINTED.token}\n`
    );
    // the stderr notice carries the expiry and warning, never the token
    expect(log).toHaveBeenCalledTimes(1);
    expect(log.mock.calls[0][0]).toContain(MINTED.expiresAt);
    expect(log.mock.calls[0][0]).not.toContain(MINTED.token);
  });

  it('defaults --expires-in to 1h and passes --user-id through', async () => {
    const { ctx, bootstrapToken } = fakeCtx([{ id: TENANT_ID }]);
    const def = command();

    await def.handler(ctx, def.input.parse({ slug: 'acme', userId: 'u9' }) as never);

    expect(bootstrapToken).toHaveBeenCalledWith(TENANT_ID, { expiresIn: '1h', userId: 'u9' });
  });

  it('accepts a tenant id without a lookup', async () => {
    const { ctx, get, bootstrapToken } = fakeCtx([]);
    const def = command();

    await def.handler(ctx, def.input.parse({ slug: TENANT_ID }) as never);

    expect(get).not.toHaveBeenCalled();
    expect(bootstrapToken).toHaveBeenCalledWith(TENANT_ID, { expiresIn: '1h' });
  });

  it('fails with NOT_FOUND for an unknown slug and mints nothing', async () => {
    const { ctx, bootstrapToken } = fakeCtx([]);
    const def = command();

    await expect(
      def.handler(ctx, def.input.parse({ slug: 'nope' }) as never)
    ).rejects.toMatchObject({ code: 'NOT_FOUND' });
    expect(bootstrapToken).not.toHaveBeenCalled();
  });
});
