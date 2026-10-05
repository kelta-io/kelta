import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

vi.mock('../auth/loginFlow.js', async (importOriginal) => {
  const original = await importOriginal<typeof import('../auth/loginFlow.js')>();
  return { ...original, browserLogin: vi.fn() };
});

import { browserLogin } from '../auth/loginFlow.js';
import { getToken, loadConfig, saveConfig, setToken } from '../config/store.js';
import { setConfigDirForTesting, setLegacyRcForTesting } from '../config/paths.js';
import type { CommandContext, RegisteredCommand } from '../registry/types.js';
import { authCommands } from './auth.js';
import { tokenCommands } from './token.js';

const browserLoginMock = vi.mocked(browserLogin);

let dir: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'kelta-cli-login-'));
  setConfigDirForTesting(join(dir, '.kelta'));
  setLegacyRcForTesting(join(dir, '.keltarc'));
  for (const key of ['KELTA_PROFILE', 'KELTA_URL', 'KELTA_TENANT', 'KELTA_TOKEN']) {
    delete process.env[key];
  }
  browserLoginMock.mockReset();
});

afterEach(() => {
  setConfigDirForTesting();
  setLegacyRcForTesting();
  rmSync(dir, { recursive: true, force: true });
});

function command(defs: RegisteredCommand[], name: string): RegisteredCommand {
  const def = defs.find((c) => c.name === name);
  if (!def) throw new Error(`no command ${name}`);
  return def;
}

function ctx(profileFlag?: string, client?: unknown): CommandContext {
  return {
    profile: { name: profileFlag ?? 'default' },
    global: { profile: profileFlag, raw: false, quiet: false, yes: true },
    log: vi.fn(),
    client,
  } as unknown as CommandContext;
}

async function run(
  defs: RegisteredCommand[],
  name: string,
  input: Record<string, unknown>,
  profile?: string,
  client?: unknown
) {
  const def = command(defs, name);
  return def.handler(ctx(profile, client), def.input.parse(input) as never);
}

describe('auth login — browser flow', () => {
  it('mints a PAT and stores profile + credential + expiry', async () => {
    browserLoginMock.mockResolvedValue({
      token: 'klt_minted1234567890',
      tokenPrefix: 'klt_mint',
      expiresAt: '2026-11-04T00:00:00Z',
      name: 'kelta-cli host 2026-08-06',
    });

    const result = await run(
      authCommands,
      'login',
      { url: 'https://api.kelta.io', tenant: 'acme', expiresIn: '30', browser: true },
      'prod'
    );

    expect(browserLoginMock).toHaveBeenCalledWith(
      expect.objectContaining({
        apiUrl: 'https://api.kelta.io',
        tenantSlug: 'acme',
        authUrl: 'https://auth.kelta.io', // derived api. → auth.
        expiresInDays: 30,
        noBrowser: false,
      })
    );
    const config = loadConfig();
    expect(config.profiles.prod).toMatchObject({
      apiUrl: 'https://api.kelta.io',
      tenantSlug: 'acme',
      authUrl: 'https://auth.kelta.io',
      tokenPrefix: 'klt_mint',
      tokenExpiresAt: '2026-11-04T00:00:00Z',
    });
    expect(getToken('prod')).toBe('klt_minted1234567890');
    expect((result.data as { method: string }).method).toBe('browser');
    // the full token never appears in output — only the prefix
    expect(JSON.stringify(result)).not.toContain('klt_minted1234567890');
  });

  it('strips a path from --url with a warning — the CLI prepends the tenant slug itself', async () => {
    browserLoginMock.mockResolvedValue({
      token: 'klt_minted1234567890',
      tokenPrefix: 'klt_mint',
      expiresAt: '2026-11-04T00:00:00Z',
      name: 'n',
    });

    const context = ctx('prod');
    const def = command(authCommands, 'login');
    await def.handler(
      context,
      def.input.parse({
        url: 'https://api.kelta.io/acme',
        tenant: 'acme',
        expiresIn: '30',
        browser: true,
      }) as never
    );

    expect(browserLoginMock).toHaveBeenCalledWith(
      expect.objectContaining({ apiUrl: 'https://api.kelta.io' })
    );
    expect(loadConfig().profiles.prod?.apiUrl).toBe('https://api.kelta.io');
    expect(vi.mocked(context.log).mock.calls.flat().join('\n')).toContain('Ignoring path "/acme"');
  });

  it('re-authenticates an existing profile with no flags', async () => {
    saveConfig({
      version: 1,
      defaultProfile: 'prod',
      profiles: {
        prod: {
          apiUrl: 'https://api.kelta.io',
          tenantSlug: 'acme',
          authUrl: 'https://sso.example.com',
        },
      },
    });
    browserLoginMock.mockResolvedValue({
      token: 'klt_fresh1234567890',
      tokenPrefix: 'klt_fres',
      expiresAt: '2026-12-01T00:00:00Z',
      name: 'n',
    });

    await run(authCommands, 'login', { expiresIn: '90', browser: true }, 'prod');

    // saved authUrl wins over derivation
    expect(browserLoginMock).toHaveBeenCalledWith(
      expect.objectContaining({ authUrl: 'https://sso.example.com' })
    );
    expect(getToken('prod')).toBe('klt_fresh1234567890');
  });

  it('fails cleanly when no connection is known and no flags given', async () => {
    await expect(
      run(authCommands, 'login', { expiresIn: '90', browser: true }, 'new')
    ).rejects.toMatchObject({ code: 'MISSING_CONNECTION', exitCode: 2 });
    expect(browserLoginMock).not.toHaveBeenCalled();
  });

  it('demands --auth-url when derivation fails', async () => {
    await expect(
      run(
        authCommands,
        'login',
        { url: 'https://gateway.internal', tenant: 'acme', expiresIn: '90', browser: true },
        'prod'
      )
    ).rejects.toMatchObject({ code: 'MISSING_AUTH_URL', exitCode: 2 });
  });

  it('--token path stays headless and never invokes the browser flow', async () => {
    await run(
      authCommands,
      'login',
      {
        url: 'https://api.kelta.io',
        tenant: 'acme',
        token: 'klt_manual1234567890',
        expiresIn: '90',
        browser: true,
      },
      'ci'
    );
    expect(browserLoginMock).not.toHaveBeenCalled();
    expect(getToken('ci')).toBe('klt_manual1234567890');
  });
});

describe('auth login — tenant binding of the active profile', () => {
  function bindDefaultTo(tenant: string) {
    saveConfig({
      version: 1,
      defaultProfile: 'default',
      profiles: { default: { apiUrl: 'https://api.kelta.io', tenantSlug: tenant } },
    });
    setToken('default', 'klt_tenantA_original');
  }

  function storedFiles() {
    return ['config.json', 'credentials.json'].map((f) => readFileSync(join(dir, '.kelta', f)));
  }

  function tokenLogin(tenant: string, token: string, profile?: string) {
    return run(authCommands, 'login', { tenant, token, expiresIn: '90', browser: true }, profile);
  }

  it('refuses a login for another tenant without --profile and leaves the profile untouched', async () => {
    bindDefaultTo('tenant-a');
    const before = storedFiles();

    const failure = tokenLogin('tenant-b', 'klt_tenantB_new').then(
      () => undefined,
      (error: unknown) => error
    );
    await expect(failure).resolves.toMatchObject({
      code: 'PROFILE_TENANT_MISMATCH',
      exitCode: 2,
    });
    expect(((await failure) as Error).message).toContain('--profile tenant-b');
    expect(storedFiles()).toEqual(before);
    expect(getToken('default')).toBe('klt_tenantA_original');
  });

  it('refuses before the browser flow starts, so nothing is minted', async () => {
    bindDefaultTo('tenant-a');
    await expect(
      run(authCommands, 'login', { tenant: 'tenant-b', expiresIn: '90', browser: true })
    ).rejects.toMatchObject({ code: 'PROFILE_TENANT_MISMATCH' });
    expect(browserLoginMock).not.toHaveBeenCalled();
  });

  it('re-login to the same tenant updates the active profile credential', async () => {
    bindDefaultTo('tenant-a');
    const result = await tokenLogin('tenant-a', 'klt_tenantA_refreshed');
    expect(getToken('default')).toBe('klt_tenantA_refreshed');
    expect(result.data).toMatchObject({ profile: 'default', tenant: 'tenant-a' });
  });

  it('an explicit --profile always wins, even over a profile bound to another tenant', async () => {
    bindDefaultTo('tenant-a');
    const result = await tokenLogin('tenant-b', 'klt_tenantB_new', 'default');
    expect(getToken('default')).toBe('klt_tenantB_new');
    expect(loadConfig().profiles.default?.tenantSlug).toBe('tenant-b');
    expect(result.data).toMatchObject({ profile: 'default', tenant: 'tenant-b' });
  });

  it('KELTA_PROFILE counts as an explicit profile', async () => {
    bindDefaultTo('tenant-a');
    process.env.KELTA_PROFILE = 'default';
    await tokenLogin('tenant-b', 'klt_tenantB_new');
    expect(getToken('default')).toBe('klt_tenantB_new');
    expect(loadConfig().profiles.default?.tenantSlug).toBe('tenant-b');
  });

  it('writes a fresh profile with no saved tenant without --profile', async () => {
    const result = await run(authCommands, 'login', {
      url: 'https://api.kelta.io',
      tenant: 'tenant-b',
      token: 'klt_tenantB_new',
      expiresIn: '90',
      browser: true,
    });
    expect(getToken('default')).toBe('klt_tenantB_new');
    expect(loadConfig().profiles.default?.tenantSlug).toBe('tenant-b');
    expect(result.data).toMatchObject({ profile: 'default' });
    expect(result.message).toContain('profile "default"');
  });

  it('browser login output names the written profile', async () => {
    bindDefaultTo('tenant-a');
    browserLoginMock.mockResolvedValue({
      token: 'klt_minted1234567890',
      tokenPrefix: 'klt_mint',
      expiresAt: '2026-11-04T00:00:00Z',
      name: 'n',
    });
    const result = await run(authCommands, 'login', { expiresIn: '90', browser: true });
    expect(result.data).toMatchObject({ profile: 'default', tenant: 'tenant-a' });
    expect(result.message).toContain('profile "default"');
  });
});

describe('auth logout --revoke', () => {
  function clientWith(tokens: { id: string; tokenPrefix: string }[]) {
    const revoke = vi.fn().mockResolvedValue(undefined);
    const list = vi.fn().mockResolvedValue(tokens);
    return { client: { admin: { personalTokens: { list, revoke } } }, revoke, list };
  }

  beforeEach(async () => {
    browserLoginMock.mockResolvedValue({
      token: 'klt_live1234567890ab',
      tokenPrefix: 'klt_live',
      expiresAt: '2026-11-04T00:00:00Z',
      name: 'n',
    });
    await run(
      authCommands,
      'login',
      { url: 'https://api.kelta.io', tenant: 'acme', expiresIn: '90', browser: true },
      'prod'
    );
  });

  it('revokes the matching server-side token, then removes locally', async () => {
    const { client, revoke } = clientWith([
      { id: 'tok-1', tokenPrefix: 'klt_othe' },
      { id: 'tok-2', tokenPrefix: 'klt_live' },
    ]);
    const result = await run(authCommands, 'logout', { revoke: true }, 'prod', client);
    expect(revoke).toHaveBeenCalledWith('tok-2');
    expect((result.data as { revoked: boolean }).revoked).toBe(true);
    expect(getToken('prod')).toBeUndefined();
  });

  it('removes locally with a warning when no server token matches', async () => {
    const { client, revoke } = clientWith([{ id: 'tok-1', tokenPrefix: 'klt_othe' }]);
    const result = await run(authCommands, 'logout', { revoke: true }, 'prod', client);
    expect(revoke).not.toHaveBeenCalled();
    expect((result.data as { revoked: boolean }).revoked).toBe(false);
    expect(getToken('prod')).toBeUndefined();
  });

  it('refuses --revoke without a stored PAT', async () => {
    setToken('prod', 'eyJhbGciOi.jwt.token');
    await expect(run(authCommands, 'logout', { revoke: true }, 'prod')).rejects.toMatchObject({
      code: 'NO_PAT_TO_REVOKE',
    });
  });
});

describe('token commands', () => {
  const TOKENS = [
    { id: 't1', name: 'ci', tokenPrefix: 'klt_aaaa', expiresAt: '2026-09-01', lastUsedAt: null },
    {
      id: 't2',
      name: 'dev',
      tokenPrefix: 'klt_bbbb',
      expiresAt: '2026-10-01',
      lastUsedAt: '2026-08-01',
    },
  ];

  it('token list returns rows and ids', async () => {
    const client = { admin: { personalTokens: { list: vi.fn().mockResolvedValue(TOKENS) } } };
    const result = await run(tokenCommands, 'list', {}, 'prod', client);
    expect(result.ids).toEqual(['t1', 't2']);
    expect(result.columns?.map((c) => c.key)).toContain('tokenPrefix');
  });

  it('token create passes name + lifetime and surfaces the one-time token', async () => {
    const create = vi.fn().mockResolvedValue({
      token: 'klt_once1234567890',
      tokenPrefix: 'klt_once',
      expiresAt: '2026-09-01',
    });
    const client = { admin: { personalTokens: { create } } };
    const result = await run(
      tokenCommands,
      'create',
      { name: 'automation', expiresIn: '30' },
      'prod',
      client
    );
    expect(create).toHaveBeenCalledWith({ name: 'automation', expiresInDays: 30 });
    expect(result.message).toContain('klt_once1234567890');
    expect(result.message).toContain('NOT be shown again');
  });

  it('token revoke is dangerous and calls the API', async () => {
    const revoke = vi.fn().mockResolvedValue(undefined);
    const client = { admin: { personalTokens: { revoke } } };
    const def = command(tokenCommands, 'revoke');
    expect(def.dangerous).toBe(true);
    await run(tokenCommands, 'revoke', { tokenId: 't1' }, 'prod', client);
    expect(revoke).toHaveBeenCalledWith('t1');
  });
});
