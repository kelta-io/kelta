import { z } from 'zod';
import { readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const pageList = defineCommand({
  group: 'pages',
  name: 'list',
  summary: 'List UI pages (screen builder)',
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/ui-pages');
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'path', header: 'PATH' },
        { key: 'published', header: 'PUBLISHED' },
        { key: 'active', header: 'ACTIVE' },
      ],
    };
  },
});

const pageGet = defineCommand({
  group: 'pages',
  name: 'get',
  summary: 'Get a UI page by id',
  positionals: [{ name: 'pageId', description: 'Page id', required: true }],
  input: z.object({ pageId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/ui-pages/${input.pageId}`);
    return { data: response.data };
  },
});

const pageCreate = defineCommand({
  group: 'pages',
  name: 'create',
  summary: 'Create a UI page (draft — unpublished until `pages publish`)',
  options: [
    { flag: '--name <name>', description: 'Page name (required)' },
    { flag: '--path <path>', description: 'Route the page is served at (required)' },
    { flag: '--title <title>', description: 'Browser and header title' },
    {
      flag: '--config <json>',
      description: 'Page config as JSON, @file, or - (layout, components, bindings)',
    },
    { flag: '--active <bool>', description: 'true|false (default true)' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    name: z.string().min(1),
    path: z.string().min(1),
    title: z.string().optional(),
    config: z.string().optional(),
    active: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = { name: input.name, path: input.path };
    if (input.title) attributes.title = input.title;
    if (input.config) attributes.config = readDataArgument(input.config);
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .post<{ data?: { id?: string } }>('/api/ui-pages', {
        data: { type: 'ui-pages', attributes },
      });
    return {
      data: response.data,
      message: `Page "${input.name}" created at ${input.path}`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const pageUpdate = defineCommand({
  group: 'pages',
  name: 'update',
  summary: 'Update a UI page by id',
  positionals: [{ name: 'pageId', description: 'Page id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Page name' },
    { flag: '--path <path>', description: 'Route the page is served at' },
    { flag: '--title <title>', description: 'Browser and header title' },
    { flag: '--config <json>', description: 'Page config as JSON, @file, or -' },
    { flag: '--active <bool>', description: 'true|false' },
    { flag: '--published <bool>', description: 'true|false' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    pageId: z.string().min(1),
    name: z.string().optional(),
    path: z.string().optional(),
    title: z.string().optional(),
    config: z.string().optional(),
    active: z.enum(['true', 'false']).optional(),
    published: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.name) attributes.name = input.name;
    if (input.path) attributes.path = input.path;
    if (input.title) attributes.title = input.title;
    if (input.config) attributes.config = readDataArgument(input.config);
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.published !== undefined) attributes.published = input.published === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    if (Object.keys(attributes).length === 0) {
      throw new CliError('Nothing to update — pass at least one field flag', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/ui-pages/${input.pageId}`, {
        data: { type: 'ui-pages', id: input.pageId, attributes },
      });
    return { data: response.data, message: `Page ${input.pageId} updated` };
  },
});

const pageDelete = defineCommand({
  group: 'pages',
  name: 'delete',
  summary: 'Delete a UI page',
  dangerous: true,
  positionals: [{ name: 'pageId', description: 'Page id', required: true }],
  input: z.object({ pageId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/ui-pages/${input.pageId}`);
    return {
      data: { deleted: true, id: input.pageId },
      message: `Page ${input.pageId} deleted`,
      ids: [input.pageId],
    };
  },
});

const pagePublish = defineCommand({
  group: 'pages',
  name: 'publish',
  summary: 'Publish the UI page served at a route path (sets published=true)',
  positionals: [{ name: 'path', description: 'Route the page is served at', required: true }],
  input: z.object({ path: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const response = await axios.get<{ data?: { id?: string }[] }>(
      `/api/ui-pages?filter[path][eq]=${encodeURIComponent(input.path)}`
    );
    const id = response.data.data?.[0]?.id;
    if (!id) {
      throw new CliError(`Page at path "${input.path}" not found`, {
        code: 'NOT_FOUND',
        exitCode: EXIT.NOT_FOUND,
      });
    }
    const updated = await axios.patch<unknown>(`/api/ui-pages/${id}`, {
      data: { type: 'ui-pages', id, attributes: { published: true } },
    });
    return { data: updated.data, message: `Page "${input.path}" published`, ids: [id] };
  },
});

export const pageCommands: RegisteredCommand[] = [
  pageList,
  pageGet,
  pageCreate,
  pageUpdate,
  pageDelete,
  pagePublish,
];
