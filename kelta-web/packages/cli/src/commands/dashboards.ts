import { z } from 'zod';
import { readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

interface DashboardComponentResource {
  id: string;
  attributes?: Record<string, unknown>;
}

interface DashboardTreeCounts {
  dashboardId?: string;
  name?: string;
  created?: number;
  updated?: number;
  deleted?: number;
  unchanged?: number;
}

const dashboardList = defineCommand({
  group: 'dashboards',
  name: 'list',
  summary: 'List dashboards',
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/dashboards');
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'accessLevel', header: 'ACCESS' },
        { key: 'columnCount', header: 'COLUMNS' },
      ],
    };
  },
});

const dashboardGet = defineCommand({
  group: 'dashboards',
  name: 'get',
  summary: 'Get a dashboard by id (--components includes widgets sorted by row/column)',
  positionals: [{ name: 'dashboardId', description: 'Dashboard id', required: true }],
  options: [
    {
      flag: '--components',
      description: 'Include dashboard components, sorted by rowPosition then columnPosition',
    },
  ],
  input: z.object({ dashboardId: z.string().min(1), components: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const response = await axios.get<{
      data?: { id: string; type?: string; attributes?: Record<string, unknown> };
    }>(`/api/dashboards/${input.dashboardId}`);
    if (!input.components) return { data: response.data };

    const componentsResponse = await axios.get<{ data?: DashboardComponentResource[] }>(
      `/api/dashboard-components?filter[dashboardId][eq]=${input.dashboardId}&page[size]=200`
    );
    const components = (componentsResponse.data.data ?? [])
      .map((resource: DashboardComponentResource) => ({ id: resource.id, ...resource.attributes }))
      .sort((a: Record<string, unknown>, b: Record<string, unknown>) => {
        const rowDiff = Number(a.rowPosition ?? 0) - Number(b.rowPosition ?? 0);
        if (rowDiff !== 0) return rowDiff;
        return Number(a.columnPosition ?? 0) - Number(b.columnPosition ?? 0);
      });
    const dashboard = response.data.data;
    return {
      data: {
        data: {
          type: 'dashboards',
          id: dashboard?.id,
          attributes: { ...dashboard?.attributes, components },
        },
      },
    };
  },
});

const dashboardCreate = defineCommand({
  group: 'dashboards',
  name: 'create',
  summary: 'Create a dashboard (add widgets via `kelta api` — dashboard-components)',
  options: [
    { flag: '--name <name>', description: 'Dashboard name (required)' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--access-level <level>', description: 'PRIVATE (default), PUBLIC or HIDDEN' },
    { flag: '--column-count <n>', description: 'Width of the dashboard grid in columns' },
    {
      flag: '--dynamic',
      description: 'Widgets execute as --running-user-id rather than the viewer',
    },
    { flag: '--running-user-id <id>', description: 'User a dynamic dashboard executes widgets as' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    name: z.string().min(1),
    description: z.string().optional(),
    accessLevel: z.string().optional(),
    columnCount: z.coerce.number().int().optional(),
    dynamic: z.boolean().default(false),
    runningUserId: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = { name: input.name, dynamic: input.dynamic };
    if (input.description) attributes.description = input.description;
    if (input.accessLevel) attributes.accessLevel = input.accessLevel.trim().toUpperCase();
    if (input.columnCount !== undefined) attributes.columnCount = input.columnCount;
    if (input.runningUserId) attributes.runningUserId = input.runningUserId;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .post<{ data?: { id?: string } }>('/api/dashboards', {
        data: { type: 'dashboards', attributes },
      });
    return {
      data: response.data,
      message: `Dashboard "${input.name}" created`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const dashboardApply = defineCommand({
  group: 'dashboards',
  name: 'apply',
  summary: 'Apply a dashboard tree file (widgets) in one call, creating the dashboard if needed',
  options: [
    {
      flag: '--file <path>',
      description: 'Dashboard tree JSON file — see `dashboards get <id> --components` for the shape',
    },
    {
      flag: '--name <name>',
      description:
        'Dashboard name (overrides the file\'s own "name"; creates the dashboard if it does not exist yet)',
    },
  ],
  input: z.object({ file: z.string().min(1), name: z.string().optional() }),
  handler: async (ctx, input) => {
    const tree = readDataArgument('@' + input.file);
    const name = input.name ?? (typeof tree.name === 'string' ? tree.name : undefined);
    if (!name) {
      throw new CliError('Dashboard name required — pass --name or set "name" in the tree file', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const body = { ...tree };
    delete body.name;
    const path = `/api/dashboards/${encodeURIComponent(name)}/tree`;
    const response = await ctx.client.getAxiosInstance().put<DashboardTreeCounts>(path, body);
    const counts = response.data;
    return {
      data: counts,
      message:
        `Dashboard tree applied to "${name}" ` +
        `(created=${counts.created ?? 0}, updated=${counts.updated ?? 0}, ` +
        `deleted=${counts.deleted ?? 0}, unchanged=${counts.unchanged ?? 0})`,
      ids: counts.dashboardId ? [counts.dashboardId] : [],
    };
  },
});

const dashboardUpdate = defineCommand({
  group: 'dashboards',
  name: 'update',
  summary: 'Update a dashboard by id',
  positionals: [{ name: 'dashboardId', description: 'Dashboard id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Dashboard name' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--access-level <level>', description: 'PRIVATE, PUBLIC or HIDDEN' },
    { flag: '--column-count <n>', description: 'Width of the dashboard grid in columns' },
    { flag: '--dynamic <bool>', description: 'true|false' },
    { flag: '--running-user-id <id>', description: 'User a dynamic dashboard executes widgets as' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    dashboardId: z.string().min(1),
    name: z.string().optional(),
    description: z.string().optional(),
    accessLevel: z.string().optional(),
    columnCount: z.coerce.number().int().optional(),
    dynamic: z.enum(['true', 'false']).optional(),
    runningUserId: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.name) attributes.name = input.name;
    if (input.description) attributes.description = input.description;
    if (input.accessLevel) attributes.accessLevel = input.accessLevel.trim().toUpperCase();
    if (input.columnCount !== undefined) attributes.columnCount = input.columnCount;
    if (input.dynamic !== undefined) attributes.dynamic = input.dynamic === 'true';
    if (input.runningUserId) attributes.runningUserId = input.runningUserId;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    if (Object.keys(attributes).length === 0) {
      throw new CliError('Nothing to update — pass at least one field flag', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/dashboards/${input.dashboardId}`, {
        data: { type: 'dashboards', id: input.dashboardId, attributes },
      });
    return { data: response.data, message: `Dashboard ${input.dashboardId} updated` };
  },
});

const dashboardDelete = defineCommand({
  group: 'dashboards',
  name: 'delete',
  summary: 'Delete a dashboard',
  dangerous: true,
  positionals: [{ name: 'dashboardId', description: 'Dashboard id', required: true }],
  input: z.object({ dashboardId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/dashboards/${input.dashboardId}`);
    return {
      data: { deleted: true, id: input.dashboardId },
      message: `Dashboard ${input.dashboardId} deleted`,
      ids: [input.dashboardId],
    };
  },
});

export const dashboardCommands: RegisteredCommand[] = [
  dashboardList,
  dashboardGet,
  dashboardCreate,
  dashboardApply,
  dashboardUpdate,
  dashboardDelete,
];
