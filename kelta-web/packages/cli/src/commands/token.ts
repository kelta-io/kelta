import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

/**
 * Structural view of the SDK's PersonalAccessToken. The SDK dist d.ts uses
 * extensionless relative re-exports, which Node16 resolution can't follow —
 * the imported type would silently be `any` (same issue as errors.ts).
 */
export interface PatSummary {
  id: string;
  name: string;
  tokenPrefix: string;
  expiresAt: string;
  lastUsedAt: string | null;
}

const list = defineCommand({
  group: 'token',
  name: 'list',
  summary: 'List your active personal access tokens',
  examples: [{ cmd: 'kelta token list' }],
  input: z.object({}),
  handler: async (ctx) => {
    const tokens = (await ctx.client.admin.personalTokens.list()) as PatSummary[];
    return {
      data: tokens,
      ids: tokens.map((token: PatSummary) => token.id),
      columns: [
        { key: 'id', header: 'ID' },
        { key: 'name', header: 'NAME' },
        { key: 'tokenPrefix', header: 'PREFIX' },
        { key: 'expiresAt', header: 'EXPIRES' },
        { key: 'lastUsedAt', header: 'LAST USED' },
      ],
    };
  },
});

const create = defineCommand({
  group: 'token',
  name: 'create',
  summary: 'Create a personal access token (the full token is shown exactly once)',
  options: [
    { flag: '--name <name>', description: 'Token name' },
    { flag: '--expires-in <days>', description: 'Lifetime in days (1-365)', default: '90' },
  ],
  examples: [
    {
      cmd: 'kelta token create --name ci-deploy --expires-in 30',
      description: 'Create a 30-day token for CI',
    },
  ],
  input: z.object({
    name: z.string().min(1).max(200),
    expiresIn: z.coerce.number().int().min(1).max(365).default(90),
  }),
  handler: async (ctx, input) => {
    const created = await ctx.client.admin.personalTokens.create({
      name: input.name,
      expiresInDays: input.expiresIn,
    });
    return {
      data: created,
      message:
        `Created ${created.token} (expires ${created.expiresAt})\n` +
        'Store it now — it will NOT be shown again.',
    };
  },
});

const revoke = defineCommand({
  group: 'token',
  name: 'revoke',
  summary: 'Revoke a personal access token by id',
  dangerous: true,
  positionals: [
    { name: 'tokenId', description: 'Token id (see: kelta token list)', required: true },
  ],
  examples: [{ cmd: 'kelta token revoke 3f6c1a52-8d2e-4b7a-9c1e-5a0d2f7b9e14 --yes' }],
  input: z.object({ tokenId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.admin.personalTokens.revoke(input.tokenId);
    return {
      data: { revoked: true, id: input.tokenId },
      message: `Token ${input.tokenId} revoked`,
      ids: [input.tokenId],
    };
  },
});

export const tokenCommands: RegisteredCommand[] = [list, create, revoke];
