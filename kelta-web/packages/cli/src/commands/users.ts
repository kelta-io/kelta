import { z } from 'zod';
import { readDataArgument } from '../data.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const list = defineCommand({
  group: 'users',
  name: 'list',
  summary: 'List platform users',
  options: [
    { flag: '--search <text>', description: 'Search filter (name/email contains)' },
    { flag: '--status <status>', description: 'ACTIVE|INACTIVE' },
    { flag: '--page <n>', description: 'Page number', default: '1' },
    { flag: '--size <n>', description: 'Page size', default: '20' },
  ],
  input: z.object({
    search: z.string().optional(),
    status: z.string().optional(),
    page: z.coerce.number().int().positive().default(1),
    size: z.coerce.number().int().positive().max(200).default(20),
  }),
  handler: async (ctx, input) => {
    const params = new URLSearchParams();
    params.set('page[number]', String(input.page));
    params.set('page[size]', String(input.size));
    if (input.search) params.set('filter[search][contains]', input.search);
    if (input.status) params.set('filter[status][eq]', input.status);
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/users?${params.toString()}`);
    return {
      data: response.data,
      columns: [
        { key: 'email', header: 'EMAIL' },
        { key: 'firstName', header: 'FIRST' },
        { key: 'lastName', header: 'LAST' },
        { key: 'status', header: 'STATUS' },
        { key: 'userType', header: 'TYPE' },
      ],
    };
  },
});

const get = defineCommand({
  group: 'users',
  name: 'get',
  summary: 'Show one user',
  positionals: [{ name: 'userId', description: 'User id', required: true }],
  input: z.object({ userId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>(`/api/users/${input.userId}`);
    return { data: response.data };
  },
});

const invite = defineCommand({
  group: 'users',
  name: 'invite',
  summary: 'Send (or resend) the invite email to an existing user',
  positionals: [{ name: 'userId', description: 'User id', required: true }],
  input: z.object({ userId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(`/api/admin/users/${input.userId}/invite`);
    return { data: response.data, message: `Invite sent to user ${input.userId}` };
  },
});

const portalInvite = defineCommand({
  group: 'users',
  name: 'portal-invite',
  summary: 'Invite a portal (external) user',
  options: [
    { flag: '--email <email>', description: 'Email address' },
    {
      flag: '--data <json>',
      description: 'Full request body as JSON, @file, or - (wins on collision)',
    },
  ],
  input: z
    .object({ email: z.string().email().optional(), data: z.string().optional() })
    .refine((value) => value.email || value.data, {
      message: 'pass --email or --data',
    }),
  handler: async (ctx, input) => {
    const body: Record<string, unknown> = {};
    if (input.email) body.email = input.email;
    if (input.data) Object.assign(body, readDataArgument(input.data));
    // raw body, not JSON:API — PortalUserAdminController contract
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>('/api/admin/users/portal-invite', body);
    return { data: response.data, message: `Portal invite sent` };
  },
});

const resetPassword = defineCommand({
  group: 'users',
  name: 'reset-password',
  summary: 'Trigger a password reset for a user',
  dangerous: true,
  positionals: [{ name: 'userId', description: 'User id', required: true }],
  input: z.object({ userId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(`/api/admin/users/${input.userId}/reset-password`);
    return { data: response.data, message: `Password reset triggered for ${input.userId}` };
  },
});

const tokenCreate = defineCommand({
  group: 'users',
  name: 'token-create',
  summary: "Mint a personal access token on a user's behalf (requires MANAGE_USERS)",
  dangerous: true,
  positionals: [{ name: 'userId', description: 'Target user id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Token name' },
    { flag: '--expires-in <days>', description: 'Lifetime in days (1-365)', default: '90' },
  ],
  input: z.object({
    userId: z.string().min(1),
    name: z.string().min(1).max(200),
    expiresIn: z.coerce.number().int().min(1).max(365).default(90),
  }),
  handler: async (ctx, input) => {
    const created = await ctx.client.admin.users.tokens.create(input.userId, {
      name: input.name,
      expiresInDays: input.expiresIn,
    });
    return {
      data: created,
      message:
        `Created ${created.token} for user ${input.userId} (expires ${created.expiresAt})\n` +
        'Store it now — it will NOT be shown again.',
    };
  },
});

const logins = defineCommand({
  group: 'users',
  name: 'logins',
  summary: "Show a user's login history",
  positionals: [{ name: 'userId', description: 'User id', required: true }],
  options: [{ flag: '--size <n>', description: 'Page size', default: '20' }],
  input: z.object({
    userId: z.string().min(1),
    size: z.coerce.number().int().positive().max(200).default(20),
  }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(
        `/api/login-history?filter[userId][eq]=${input.userId}&page[size]=${String(input.size)}`
      );
    return { data: response.data };
  },
});

export const userCommands: RegisteredCommand[] = [
  list,
  get,
  invite,
  portalInvite,
  resetPassword,
  tokenCreate,
  logins,
];
