import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

interface NotificationsPage {
  meta?: { unreadCount?: number };
}

const list = defineCommand({
  group: 'notifications',
  name: 'list',
  summary: 'List your own in-app notifications, newest first',
  options: [
    { flag: '--unread', description: 'Only unread notifications' },
    { flag: '--page <n>', description: 'Page number', default: '1' },
    { flag: '--size <n>', description: 'Page size (max 200)', default: '20' },
  ],
  examples: [
    { cmd: 'kelta notifications list' },
    {
      cmd: 'kelta notifications list --unread --size 50',
      description: 'Only what is still unread',
    },
  ],
  input: z.object({
    unread: z.boolean().default(false),
    page: z.coerce.number().int().positive().default(1),
    size: z.coerce.number().int().positive().max(200).default(20),
  }),
  handler: async (ctx, input) => {
    const params = new URLSearchParams();
    if (input.unread) params.set('unread', 'true');
    params.set('page[number]', String(input.page));
    params.set('page[size]', String(input.size));
    const response = await ctx.client
      .getAxiosInstance()
      .get<NotificationsPage>(`/api/me/notifications?${params.toString()}`);
    const unreadCount = response.data?.meta?.unreadCount;
    return {
      data: response.data,
      columns: [
        { key: 'createdAt', header: 'CREATED' },
        { key: 'level', header: 'LEVEL' },
        { key: 'title', header: 'TITLE' },
        { key: 'body', header: 'BODY' },
        { key: 'link', header: 'LINK' },
        { key: 'readAt', header: 'READ' },
      ],
      ...(unreadCount !== undefined ? { message: `${unreadCount} unread` } : {}),
    };
  },
});

export const notificationCommands: RegisteredCommand[] = [list];
