import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const reportList = defineCommand({
  group: 'reports',
  name: 'list',
  summary: 'List saved reports',
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/reports');
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'reportType', header: 'TYPE' },
        { key: 'scope', header: 'SCOPE' },
        { key: 'accessLevel', header: 'ACCESS' },
      ],
    };
  },
});

const reportGet = defineCommand({
  group: 'reports',
  name: 'get',
  summary: 'Get a report definition by id',
  positionals: [{ name: 'reportId', description: 'Report id', required: true }],
  input: z.object({ reportId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/reports/${input.reportId}`);
    return { data: response.data };
  },
});

export const reportCommands: RegisteredCommand[] = [reportList, reportGet];
