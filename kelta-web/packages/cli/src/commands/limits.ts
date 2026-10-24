import { z } from 'zod';
import { readDataArgument } from '../data.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const get = defineCommand({
  group: 'limits',
  name: 'get',
  summary: 'Show tenant governor-limit status',
  examples: [{ cmd: 'kelta limits get' }],
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/governor-limits');
    return { data: response.data };
  },
});

const setTier = defineCommand({
  group: 'limits',
  name: 'set-tier',
  summary: 'Set the tenant governor tier',
  dangerous: true,
  positionals: [
    { name: 'tier', description: 'FREE|PROFESSIONAL|ENTERPRISE|UNLIMITED', required: true },
  ],
  examples: [{ cmd: 'kelta limits set-tier PROFESSIONAL --yes' }],
  input: z.object({
    tier: z.enum(['FREE', 'PROFESSIONAL', 'ENTERPRISE', 'UNLIMITED']),
  }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .put<unknown>('/api/governor-limits/tier', { tier: input.tier });
    return { data: response.data, message: `Governor tier set to ${input.tier}` };
  },
});

const set = defineCommand({
  group: 'limits',
  name: 'set',
  summary: 'Override individual governor limits (raw limits map, persisted per tenant)',
  dangerous: true,
  options: [{ flag: '--data <json>', description: 'Limits map as JSON, @file, or - (required)' }],
  examples: [
    {
      cmd: 'kelta limits set --data \'{"apiCallsPerDay": 50000}\' --yes',
      description: 'Override the daily API call limit',
    },
  ],
  input: z.object({ data: z.string().min(1) }),
  handler: async (ctx, input) => {
    const body = readDataArgument(input.data);
    const response = await ctx.client.getAxiosInstance().put<unknown>('/api/governor-limits', body);
    return { data: response.data, message: 'Governor limit overrides applied' };
  },
});

export const limitCommands: RegisteredCommand[] = [get, setTier, set];
