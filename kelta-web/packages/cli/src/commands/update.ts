import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';
import { updateBaseUrl } from '../update/manifest.js';
import { assertSelfUpdatable, checkForUpdate, performUpdate } from '../update/selfUpdate.js';
import { BUILD_TARGET, GIT_SHA, VERSION } from '../version.js';

const version = defineCommand({
  group: '',
  name: 'version',
  summary: 'Show detailed build information',
  requiresAuth: false,
  examples: [{ cmd: 'kelta version --output json' }],
  input: z.object({}),
  handler: () => {
    const data = { version: VERSION, gitSha: GIT_SHA, target: BUILD_TARGET };
    return Promise.resolve({
      data,
      human: `kelta ${VERSION} (${BUILD_TARGET}, ${GIT_SHA})\n`,
    });
  },
});

const update = defineCommand({
  group: '',
  name: 'update',
  summary: 'Self-update from the cluster downloads service (--check only reports)',
  requiresAuth: false,
  options: [{ flag: '--check', description: 'Report whether an update exists without installing' }],
  examples: [
    { cmd: 'kelta update --check', description: 'Report whether a newer version exists' },
    { cmd: 'kelta update' },
  ],
  input: z.object({ check: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const baseUrl = updateBaseUrl();
    if (input.check) {
      const check = await checkForUpdate(baseUrl);
      return {
        data: {
          currentVersion: check.currentVersion,
          latestVersion: check.latestVersion,
          updateAvailable: check.updateAvailable,
          target: check.target,
        },
        message: check.updateAvailable
          ? `Update available: ${check.currentVersion} → ${check.latestVersion} (run: kelta update)`
          : `kelta ${check.currentVersion} is up to date`,
      };
    }

    assertSelfUpdatable();
    const result = await performUpdate(baseUrl, ctx.log);
    return {
      data: {
        currentVersion: result.currentVersion,
        latestVersion: result.latestVersion,
        updated: result.updateAvailable,
        target: result.target,
      },
      message: result.updateAvailable
        ? `Updated ${result.currentVersion} → ${result.latestVersion} (sha256 verified)`
        : `kelta ${result.currentVersion} is already up to date`,
    };
  },
});

export const updateCommands: RegisteredCommand[] = [version, update];
