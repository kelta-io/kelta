import { z } from 'zod';
import { CliError, EXIT } from '../errors.js';
import { getToken, loadConfig, removeToken, renameToken, saveConfig } from '../config/store.js';
import { resolveProfile } from '../config/resolve.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

function assertProfileExists(name: string): void {
  if (!(name in loadConfig().profiles)) {
    throw new CliError(`Profile "${name}" does not exist`, {
      code: 'PROFILE_NOT_FOUND',
      exitCode: EXIT.NOT_FOUND,
    });
  }
}

const list = defineCommand({
  group: 'profile',
  name: 'list',
  summary: 'List configured profiles',
  requiresAuth: false,
  examples: [{ cmd: 'kelta profile list' }],
  input: z.object({}),
  handler: () => {
    const config = loadConfig();
    const rows = Object.entries(config.profiles).map(([name, entry]) => ({
      name,
      active: name === config.defaultProfile ? '*' : '',
      url: entry.apiUrl,
      tenant: entry.tenantSlug,
      token: getToken(name) ? (entry.tokenPrefix ?? 'set') + '…' : '',
      expires: entry.tokenExpiresAt ?? '',
    }));
    return Promise.resolve({
      data: rows,
      ids: rows.map((row) => row.name),
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'active', header: 'ACTIVE' },
        { key: 'url', header: 'URL' },
        { key: 'tenant', header: 'TENANT' },
        { key: 'token', header: 'TOKEN' },
        { key: 'expires', header: 'EXPIRES' },
      ],
    });
  },
});

const use = defineCommand({
  group: 'profile',
  name: 'use',
  summary: 'Set the default profile',
  requiresAuth: false,
  positionals: [{ name: 'name', description: 'Profile name', required: true }],
  examples: [{ cmd: 'kelta profile use staging' }],
  input: z.object({ name: z.string().min(1) }),
  handler: (_ctx, input) => {
    assertProfileExists(input.name);
    const config = loadConfig();
    config.defaultProfile = input.name;
    saveConfig(config);
    return Promise.resolve({ message: `Default profile set to "${input.name}"` });
  },
});

const show = defineCommand({
  group: 'profile',
  name: 'show',
  summary: 'Show the resolved settings of a profile (default: active profile)',
  requiresAuth: false,
  positionals: [{ name: 'name', description: 'Profile name', required: false }],
  examples: [{ cmd: 'kelta profile show staging --output json' }],
  input: z.object({ name: z.string().min(1).optional() }),
  handler: (ctx, input) => {
    const profile = resolveProfile(input.name ?? ctx.global.profile);
    return Promise.resolve({
      data: {
        profile: profile.name,
        url: profile.apiUrl ?? null,
        tenant: profile.tenantSlug ?? null,
        authUrl: profile.authUrl ?? null,
        token: profile.token ? profile.token.slice(0, 8) + '…' : null,
        tokenExpiresAt: profile.tokenExpiresAt ?? null,
      },
    });
  },
});

const remove = defineCommand({
  group: 'profile',
  name: 'remove',
  summary: 'Remove a profile and its stored credential',
  requiresAuth: false,
  positionals: [{ name: 'name', description: 'Profile name', required: true }],
  examples: [{ cmd: 'kelta profile remove staging' }],
  input: z.object({ name: z.string().min(1) }),
  handler: (_ctx, input) => {
    assertProfileExists(input.name);
    const config = loadConfig();
    delete config.profiles[input.name];
    if (config.defaultProfile === input.name) delete config.defaultProfile;
    saveConfig(config);
    removeToken(input.name);
    return Promise.resolve({ message: `Profile "${input.name}" removed` });
  },
});

const rename = defineCommand({
  group: 'profile',
  name: 'rename',
  summary: 'Rename a profile (credential moves with it)',
  requiresAuth: false,
  positionals: [
    { name: 'oldName', description: 'Current profile name', required: true },
    { name: 'newName', description: 'New profile name', required: true },
  ],
  examples: [{ cmd: 'kelta profile rename default production' }],
  input: z.object({ oldName: z.string().min(1), newName: z.string().min(1) }),
  handler: (_ctx, input) => {
    assertProfileExists(input.oldName);
    const config = loadConfig();
    if (input.newName in config.profiles) {
      throw new CliError(`Profile "${input.newName}" already exists`, {
        code: 'PROFILE_EXISTS',
        exitCode: EXIT.CONFLICT,
      });
    }
    config.profiles[input.newName] = config.profiles[input.oldName];
    delete config.profiles[input.oldName];
    if (config.defaultProfile === input.oldName) config.defaultProfile = input.newName;
    saveConfig(config);
    renameToken(input.oldName, input.newName);
    return Promise.resolve({ message: `Profile "${input.oldName}" renamed to "${input.newName}"` });
  },
});

export const profileCommands: RegisteredCommand[] = [list, use, show, remove, rename];
