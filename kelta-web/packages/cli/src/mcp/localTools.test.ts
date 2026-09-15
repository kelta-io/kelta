import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setConfigDirForTesting, setLegacyRcForTesting } from '../config/paths.js';
import { saveConfig, setToken } from '../config/store.js';
import { allCommands } from '../registry/registry.js';
import { deriveMcpUrl } from './remote.js';
import { localToolName, runLocalCommand, selectLocalCommands, toMcpTool } from './localTools.js';

describe('selectLocalCommands', () => {
  it('exposes CLI-only groups plus picks, never the hosted-covered admin surface', () => {
    const selected = selectLocalCommands(allCommands);
    const keys = selected.map((command) => `${command.group}:${command.name}`);
    expect(keys).toEqual(
      expect.arrayContaining([
        'metadata:export',
        'metadata:diff',
        'metadata:apply',
        'sandbox:create',
        'promote:execute',
        'sdk:types',
        'profile:list',
        'token:list',
        'token:revoke',
        'pages:list',
        'menus:get',
        'dashboards:get',
        'reports:list',
      ])
    );
    // hosted toolset territory stays remote
    expect(keys.some((key) => key.startsWith('fields:'))).toBe(false);
    expect(keys.some((key) => key.startsWith('records:'))).toBe(false);
    expect(keys.some((key) => key.startsWith('flows:'))).toBe(false);
    // layouts/list-views already have hosted admin tools (apply_layout,
    // apply_listview et al.) — stay remote so they aren't double-sourced
    expect(keys.some((key) => key.startsWith('layouts:'))).toBe(false);
    expect(keys.some((key) => key.startsWith('list-views:'))).toBe(false);
    // raw escape hatch is opt-in
    expect(keys).not.toContain(':api');
    const withApi = selectLocalCommands(allCommands, { enableApiTool: true });
    expect(withApi.map((command) => `${command.group}:${command.name}`)).toContain(':api');
  });

  it('names are cli_-prefixed with dashes normalized (collision-proof vs hosted)', () => {
    const selected = selectLocalCommands(allCommands, { enableApiTool: true });
    for (const command of selected) {
      const name = localToolName(command);
      expect(name).toMatch(/^cli_[a-z0-9_]+$/);
    }
    const metadataExport = selected.find(
      (command) => command.group === 'metadata' && command.name === 'export'
    );
    expect(localToolName(metadataExport!)).toBe('cli_metadata_export');
  });

  it('toMcpTool carries schema and destructive annotations', () => {
    const promoteRollback = allCommands.find(
      (command) => command.group === 'promote' && command.name === 'rollback'
    );
    const tool = toMcpTool(promoteRollback!);
    expect(tool.name).toBe('cli_promote_rollback');
    expect(tool.annotations.destructiveHint).toBe(true);
    expect((tool.inputSchema as { properties?: object }).properties).toHaveProperty('id');
  });
});

describe('runLocalCommand', () => {
  let dir: string;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'kelta-cli-mcp-'));
    setConfigDirForTesting(join(dir, '.kelta'));
    setLegacyRcForTesting(join(dir, '.keltarc'));
    saveConfig({
      version: 1,
      defaultProfile: 'prod',
      profiles: { prod: { apiUrl: 'https://api.kelta.io', tenantSlug: 'acme' } },
    });
    setToken('prod', 'klt_mcp1234567890abc');
  });

  afterEach(() => {
    setConfigDirForTesting();
    setLegacyRcForTesting();
    rmSync(dir, { recursive: true, force: true });
  });

  it('executes the handler with json semantics and returns flattened JSON text', async () => {
    const profileList = allCommands.find(
      (command) => command.group === 'profile' && command.name === 'list'
    );
    const text = await runLocalCommand(profileList!, {}, undefined);
    const rows = JSON.parse(text) as { name: string; token: string }[];
    expect(rows).toHaveLength(1);
    expect(rows[0].name).toBe('prod');
    expect(text).not.toContain('klt_mcp1234567890abc'); // prefix only, never the full token
  });

  it('validates arguments through the command zod schema', async () => {
    const profileShow = allCommands.find(
      (command) => command.group === 'profile' && command.name === 'show'
    );
    await expect(runLocalCommand(profileShow!, { name: 123 }, undefined)).rejects.toThrow();
  });
});

describe('deriveMcpUrl', () => {
  it('uses the API origin — the gateway routes /{slug}/mcp/{toolset}', () => {
    // Regression: an earlier version swapped api.->mcp., a host that does not
    // exist. Every bridge request 404'd and the server silently degraded to
    // local-only tools.
    expect(deriveMcpUrl('https://api.kelta.io')).toBe('https://api.kelta.io');
    expect(deriveMcpUrl('https://api.kelta.io/some/path')).toBe('https://api.kelta.io');
    expect(deriveMcpUrl('http://localhost:8080')).toBe('http://localhost:8080');
    // any reachable API origin works — no host-name convention is assumed
    expect(deriveMcpUrl('https://gateway.internal')).toBe('https://gateway.internal');
    expect(deriveMcpUrl('not a url')).toBeUndefined();
  });
});
