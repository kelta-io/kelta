import { z } from 'zod';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type CommandContext, type RegisteredCommand } from '../registry/types.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Resolve a tenant slug (or pass a tenant id through) to the tenant id. */
export async function resolveTenantId(ctx: CommandContext, slugOrId: string): Promise<string> {
  if (UUID.test(slugOrId)) return slugOrId;
  const response = await ctx.client.getAxiosInstance().get<{
    data?: Array<{ id: string }>;
  }>(`/api/tenants?filter[slug][eq]=${encodeURIComponent(slugOrId)}&page[size]=1`);
  const id = response.data?.data?.[0]?.id;
  if (!id) {
    throw new CliError(`No tenant with slug "${slugOrId}"`, {
      code: 'NOT_FOUND',
      exitCode: EXIT.NOT_FOUND,
    });
  }
  return id;
}

const bootstrapToken = defineCommand({
  group: 'tenants',
  name: 'bootstrap-token',
  summary:
    "Mint a short-lived PAT as a tenant's admin (requires MANAGE_TENANTS; prints the token once)",
  positionals: [{ name: 'slug', description: 'Tenant slug (or id)', required: true }],
  options: [
    {
      flag: '--expires-in <duration>',
      description: 'Lifetime: 30m, 2h or ISO-8601 (PT2H); max 24h',
      default: '1h',
    },
    {
      flag: '--user-id <id>',
      description: "A user of that tenant (default: the tenant's seeded System Administrator)",
    },
  ],
  input: z.object({
    slug: z.string().min(1),
    expiresIn: z.string().min(1).default('1h'),
    userId: z.string().min(1).optional(),
  }),
  handler: async (ctx, input) => {
    const tenantId = await resolveTenantId(ctx, input.slug);
    const minted = await ctx.client.admin.tenants.bootstrapToken(tenantId, {
      expiresIn: input.expiresIn,
      ...(input.userId ? { userId: input.userId } : {}),
    });
    ctx.log(
      `Bootstrap token for user ${minted.userId} in tenant ${input.slug} (expires ${minted.expiresAt}). ` +
        'It works only on that tenant and will NOT be shown again.'
    );
    return { text: minted.token + '\n', ids: [minted.token] };
  },
});

export const tenantCommands: RegisteredCommand[] = [bootstrapToken];
