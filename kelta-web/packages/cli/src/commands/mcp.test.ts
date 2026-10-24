// @vitest-environment node
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import axios from 'axios';
import { setConfigDirForTesting, setLegacyRcForTesting } from '../config/paths.js';
import { resolveProfile } from '../config/resolve.js';
import { saveConfig, setToken } from '../config/store.js';
import { deriveMcpUrl } from '../mcp/remote.js';
import { applyTenantSelector, mcpCommands, resolveRemotes } from './mcp.js';

vi.mock('axios', () => ({ default: { post: vi.fn() } }));

const PROFILE_TOKEN = 'klt_profile1234567890';
const SANDBOX_TOKEN = 'klt_sandbox0987654321';
const ENV_KEYS = ['KELTA_TENANT', 'KELTA_TOKEN', 'KELTA_URL', 'KELTA_PROFILE', 'SANDBOX_PAT'];

let dir: string;
let savedEnv: Record<string, string | undefined>;

beforeEach(() => {
  savedEnv = Object.fromEntries(ENV_KEYS.map((key) => [key, process.env[key]]));
  for (const key of ENV_KEYS) delete process.env[key];
  dir = mkdtempSync(join(tmpdir(), 'kelta-cli-mcp-'));
  setConfigDirForTesting(join(dir, '.kelta'));
  setLegacyRcForTesting(join(dir, '.keltarc'));
  saveConfig({
    version: 1,
    defaultProfile: 'prod',
    profiles: { prod: { apiUrl: 'https://api.kelta.io', tenantSlug: 'acme' } },
  });
  setToken('prod', PROFILE_TOKEN);
  vi.mocked(axios.post).mockReset();
  vi.mocked(axios.post).mockResolvedValue({ data: { result: { tools: [] } } });
});

afterEach(() => {
  for (const key of ENV_KEYS) {
    if (savedEnv[key] === undefined) delete process.env[key];
    else process.env[key] = savedEnv[key];
  }
  setConfigDirForTesting();
  setLegacyRcForTesting();
  rmSync(dir, { recursive: true, force: true });
});

/** URL and bearer the endpoint actually sends, read off the outgoing request. */
async function requestOf(endpoint: { listTools(): Promise<unknown> }) {
  await endpoint.listTools();
  const [url, , config] = vi.mocked(axios.post).mock.calls.at(-1) as [
    string,
    unknown,
    { headers: Record<string, string> },
  ];
  return { url, authorization: config.headers.Authorization };
}

describe('kelta mcp serve tenant selector', () => {
  it('without a selector bridges the profile tenant with the profile token', async () => {
    const [admin] = resolveRemotes('prod', 'admin', undefined, 'auto');
    expect(await requestOf(admin)).toEqual({
      url: 'https://api.kelta.io/acme/mcp/admin',
      authorization: `Bearer ${PROFILE_TOKEN}`,
    });
  });

  it('--tenant + --token-env bridges the sandbox admin endpoint with the sandbox token', async () => {
    process.env.SANDBOX_PAT = SANDBOX_TOKEN;
    applyTenantSelector('prod', { tenant: 'acme-sbx-1', tokenEnv: 'SANDBOX_PAT' });

    const remotes = resolveRemotes('prod', 'admin', undefined, 'auto');
    expect(remotes).toHaveLength(1);
    expect(await requestOf(remotes[0])).toEqual({
      url: 'https://api.kelta.io/acme-sbx-1/mcp/admin',
      authorization: `Bearer ${SANDBOX_TOKEN}`,
    });
  });

  it('honours --mcp-url and covers both toolsets for --toolset all', async () => {
    process.env.SANDBOX_PAT = SANDBOX_TOKEN;
    applyTenantSelector('prod', { tenant: 'acme-sbx-1', tokenEnv: 'SANDBOX_PAT' });

    const remotes = resolveRemotes('prod', 'all', 'https://mcp.example.test/', 'auto');
    const urls = [];
    for (const remote of remotes) urls.push((await requestOf(remote)).url);
    expect(urls).toEqual([
      'https://mcp.example.test/acme-sbx-1/mcp/user',
      'https://mcp.example.test/acme-sbx-1/mcp/admin',
    ]);
  });

  it('points the cli_ local tools (e.g. cli_metadata_export) at the same tenant and token', () => {
    process.env.SANDBOX_PAT = SANDBOX_TOKEN;
    applyTenantSelector('prod', { tenant: 'acme-sbx-1', tokenEnv: 'SANDBOX_PAT' });

    // local tools build their context through resolveProfile — see runLocalCommand
    const profile = resolveProfile('prod');
    expect(profile.tenantSlug).toBe('acme-sbx-1');
    expect(profile.token).toBe(SANDBOX_TOKEN);
  });

  it('accepts the existing KELTA_TENANT/KELTA_TOKEN env precedence with no flags', async () => {
    process.env.KELTA_TENANT = 'acme-sbx-2';
    process.env.KELTA_TOKEN = SANDBOX_TOKEN;
    applyTenantSelector('prod', {});

    const [admin] = resolveRemotes('prod', 'admin', undefined, 'auto');
    expect(await requestOf(admin)).toEqual({
      url: 'https://api.kelta.io/acme-sbx-2/mcp/admin',
      authorization: `Bearer ${SANDBOX_TOKEN}`,
    });
  });

  it('--tenant + KELTA_TOKEN works without --token-env', async () => {
    process.env.KELTA_TOKEN = SANDBOX_TOKEN;
    applyTenantSelector('prod', { tenant: 'acme-sbx-3' });

    const [admin] = resolveRemotes('prod', 'admin', undefined, 'auto');
    expect(await requestOf(admin)).toEqual({
      url: 'https://api.kelta.io/acme-sbx-3/mcp/admin',
      authorization: `Bearer ${SANDBOX_TOKEN}`,
    });
  });

  it('refuses --tenant with only the profile token (the gateway would reject it)', () => {
    expect(() => applyTenantSelector('prod', { tenant: 'acme-sbx-1' })).toThrow(
      /needs that tenant's own token/
    );
    expect(process.env.KELTA_TENANT).toBeUndefined();
  });

  it('allows --tenant naming the profile tenant without another token', () => {
    applyTenantSelector('prod', { tenant: 'acme' });
    expect(resolveProfile('prod').token).toBe(PROFILE_TOKEN);
  });

  it('fails when --token-env names an unset variable', () => {
    expect(() =>
      applyTenantSelector('prod', { tenant: 'acme-sbx-1', tokenEnv: 'SANDBOX_PAT' })
    ).toThrow(/SANDBOX_PAT: that environment variable is not set/);
  });

  it('source local resolves no hosted endpoint', () => {
    expect(resolveRemotes('prod', 'admin', undefined, 'local')).toEqual([]);
  });
});

describe('kelta mcp serve --mcp-url default', () => {
  const mcpUrlOption = mcpCommands
    .find((command) => command.name === 'serve')
    ?.options?.find((option) => option.flag === '--mcp-url <url>');

  it("documents the default as the profile's API origin", () => {
    expect(mcpUrlOption?.description).toBe(
      "Hosted MCP base URL (default: the profile's API origin)"
    );
    expect(mcpUrlOption?.description).not.toMatch(/mcp\./);
  });

  it('bridges to deriveMcpUrl(apiUrl), the API origin, when --mcp-url is omitted', async () => {
    const [user] = resolveRemotes('prod', 'user', undefined, 'auto');
    expect(deriveMcpUrl('https://api.kelta.io')).toBe('https://api.kelta.io');
    expect((await requestOf(user)).url).toBe(
      `${deriveMcpUrl('https://api.kelta.io')}/acme/mcp/user`
    );
  });
});
