import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const SINCE = z.string().optional();

function auditCommand(name: string, summary: string, collection: string): RegisteredCommand {
  return defineCommand({
    group: 'audit',
    name,
    summary,
    options: [
      { flag: '--since <iso>', description: 'Only entries at/after this ISO timestamp' },
      { flag: '--size <n>', description: 'Page size (max 200)', default: '50' },
    ],
    examples: [
      { cmd: `kelta audit ${name} --size 20` },
      {
        cmd: `kelta audit ${name} --since 2026-10-01T00:00:00Z --output json`,
        description: 'Entries since 1 October 2026 as JSON',
      },
    ],
    input: z.object({
      since: SINCE,
      size: z.coerce.number().int().positive().max(200).default(50),
    }),
    handler: async (ctx, input) => {
      const params = new URLSearchParams();
      params.set('page[size]', String(input.size));
      params.set('sort', '-createdAt');
      if (input.since) params.set('filter[createdAt][gte]', input.since);
      const response = await ctx.client
        .getAxiosInstance()
        .get<unknown>(`/api/${collection}?${params.toString()}`);
      return { data: response.data };
    },
  }) as RegisteredCommand;
}

export const auditCommands: RegisteredCommand[] = [
  auditCommand('setup', 'Recent setup (metadata) audit entries', 'setup-audit-entries'),
  auditCommand('security', 'Recent security audit log entries', 'security-audit-logs'),
  auditCommand('logins', 'Recent login history', 'login-history'),
];
