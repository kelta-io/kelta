import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setConfigDirForTesting, setLegacyRcForTesting } from '../config/paths.js';
import { saveConfig, setToken } from '../config/store.js';
import type { CommandContext, RegisteredCommand } from '../registry/types.js';
import { mcpCommands } from './mcp.js';
import { BUILD_TARGET } from '../version.js';

const TOKEN = 'klt_install1234567890';

let dir: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'kelta-cli-install-'));
  setConfigDirForTesting(join(dir, '.kelta'));
  setLegacyRcForTesting(join(dir, '.keltarc'));
  saveConfig({
    version: 1,
    defaultProfile: 'prod',
    profiles: { prod: { apiUrl: 'https://api.kelta.io', tenantSlug: 'acme' } },
  });
  setToken('prod', TOKEN);
});

afterEach(() => {
  setConfigDirForTesting();
  setLegacyRcForTesting();
  rmSync(dir, { recursive: true, force: true });
});

const install = mcpCommands.find((command) => command.name === 'install') as RegisteredCommand;

async function run(input: Record<string, unknown>) {
  const ctx = {
    profile: { name: 'prod' },
    global: { profile: 'prod', raw: false, quiet: false, yes: false },
    log: vi.fn(),
    client: undefined,
  } as unknown as CommandContext;
  return install.handler(ctx, install.input.parse(input) as never);
}

describe('kelta mcp install', () => {
  it('claude-code default recommends the stdio bridge', async () => {
    const result = await run({ client: 'claude-code' });
    expect(result.text).toContain('claude mcp add kelta-prod --');
    expect(result.text).toContain('mcp serve --profile prod');
  });

  it('emits a launcher GUI clients can actually spawn (absolute path when released)', async () => {
    // Regression: desktop clients are started by the OS launcher, not a login
    // shell, so ~/.local/bin is not on PATH and a bare `kelta` fails to spawn.
    const desktop = await run({ client: 'claude-desktop' });
    const parsed = JSON.parse(desktop.text?.slice(desktop.text.indexOf('{')) ?? '{}') as {
      mcpServers: Record<string, { command: string }>;
    };
    const command = parsed.mcpServers['kelta-prod'].command;
    if (BUILD_TARGET === 'dev') {
      expect(command).toBe('kelta');
    } else {
      expect(command).toBe(process.execPath);
      expect(command.startsWith('/')).toBe(true);
    }
  });

  it('claude-desktop and cursor print mergeable stdio JSON', async () => {
    const desktop = await run({ client: 'claude-desktop', toolset: 'admin' });
    const parsedStart = desktop.text?.indexOf('{') ?? 0;
    const parsed = JSON.parse(desktop.text?.slice(parsedStart) ?? '{}') as {
      mcpServers: Record<string, { command: string; args: string[] }>;
    };
    expect(parsed.mcpServers['kelta-prod-admin'].command).toBe('kelta');
    expect(parsed.mcpServers['kelta-prod-admin'].args).toEqual([
      'mcp',
      'serve',
      '--profile',
      'prod',
      '--toolset',
      'admin',
    ]);
    const cursor = await run({ client: 'cursor' });
    expect(cursor.text).toContain('.cursor/mcp.json');
  });

  it('--direct emits the hosted HTTP endpoint with a PAT placeholder, never the token', async () => {
    const result = await run({ client: 'claude-code', direct: true, toolset: 'user' });
    // gateway-routed, not a separate mcp.* host
    expect(result.text).toContain('https://api.kelta.io/acme/mcp/user');
    expect(result.text).toContain('<YOUR_PAT>');
    expect(result.text).not.toContain(TOKEN);
  });

  it('--direct with toolset all falls back to the user endpoint (hosted has no all)', async () => {
    const result = await run({ client: 'generic', direct: true });
    expect(result.text).toContain('/mcp/user');
  });

  it('--tenant + --token-env are passed through to mcp serve, never the token', async () => {
    const desktop = await run({
      client: 'claude-desktop',
      toolset: 'admin',
      tenant: 'acme-sbx-1',
      tokenEnv: 'SANDBOX_PAT',
    });
    const parsed = JSON.parse(desktop.text?.slice(desktop.text.indexOf('{')) ?? '{}') as {
      mcpServers: Record<string, { command: string; args: string[] }>;
    };
    expect(parsed.mcpServers['kelta-prod-acme-sbx-1-admin'].args).toEqual([
      'mcp',
      'serve',
      '--profile',
      'prod',
      '--toolset',
      'admin',
      '--tenant',
      'acme-sbx-1',
      '--token-env',
      'SANDBOX_PAT',
    ]);
    expect(desktop.text).not.toContain(TOKEN);

    const code = await run({
      client: 'claude-code',
      tenant: 'acme-sbx-1',
      tokenEnv: 'SANDBOX_PAT',
    });
    expect(code.text).toContain('claude mcp add kelta-prod-acme-sbx-1 --');
    expect(code.text).toContain(
      'mcp serve --profile prod --tenant acme-sbx-1 --token-env SANDBOX_PAT'
    );
  });

  it('--tenant without --token-env is refused for the stdio bridge', async () => {
    await expect(run({ client: 'claude-code', tenant: 'acme-sbx-1' })).rejects.toThrow(
      /--tenant needs --token-env/
    );
  });

  it('--direct --tenant points the hosted URL at that tenant', async () => {
    const result = await run({
      client: 'claude-code',
      direct: true,
      toolset: 'admin',
      tenant: 'acme-sbx-1',
    });
    expect(result.text).toContain('https://api.kelta.io/acme-sbx-1/mcp/admin');
    expect(result.text).toContain('<YOUR_PAT>');
  });
});
