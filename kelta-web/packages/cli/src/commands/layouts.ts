import { readFileSync } from 'node:fs';
import { z } from 'zod';
import { collectionIdByName } from '../admin/lookups.js';
import { deepEqual, readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { parseFilterSpec, parseList } from '../query.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

/** Reads and parses a layout tree JSON file for `layouts apply --file`. */
function readTreeFile(path: string): Record<string, unknown> {
  let raw: string;
  try {
    raw = readFileSync(path, 'utf-8');
  } catch {
    throw new CliError(`Cannot read file "${path}"`, {
      code: 'FILE_NOT_FOUND',
      exitCode: EXIT.USAGE,
    });
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new CliError(`Invalid JSON in "${path}"`, { code: 'INVALID_JSON', exitCode: EXIT.USAGE });
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new CliError(`"${path}" must contain a JSON object (a layout tree)`, {
      code: 'INVALID_JSON',
      exitCode: EXIT.USAGE,
    });
  }
  return parsed as Record<string, unknown>;
}

/** Renderers a shared list view can publish (V196). */
const VIEW_TYPES = ['TABLE', 'KANBAN', 'CALENDAR', 'GALLERY'] as const;

function normalizeViewType(value: string): string {
  const upper = value.trim().toUpperCase();
  if (!(VIEW_TYPES as readonly string[]).includes(upper)) {
    throw new CliError(`Invalid --view-type "${value}" (expected ${VIEW_TYPES.join(' | ')})`, {
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
  }
  return upper;
}

/**
 * Builds the `typeConfig` attribute from the kanban flags. Returns undefined when
 * neither is given so an update leaves an existing config (calendar/gallery
 * included) alone rather than clearing it.
 */
function kanbanTypeConfig(
  laneField: string | undefined,
  cardFields: string | undefined
): Record<string, unknown> | undefined {
  if (!laneField && !cardFields) return undefined;
  if (!laneField) {
    throw new CliError('--card-fields requires --lane-field (card fields are stored with it)', {
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
  }
  const kanban: Record<string, unknown> = { laneField };
  if (cardFields) kanban.cardFields = parseList(cardFields);
  return { kanban };
}

const layoutList = defineCommand({
  group: 'layouts',
  name: 'list',
  summary: 'List page layouts of a collection',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  input: z.object({ collection: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const response = await axios.get<unknown>(
      `/api/page-layouts?filter[collectionId][eq]=${collectionId}`
    );
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'layoutType', header: 'TYPE' },
        { key: 'isDefault', header: 'DEFAULT' },
      ],
    };
  },
});

const layoutCreate = defineCommand({
  group: 'layouts',
  name: 'create',
  summary: 'Create a page layout (sections/fields via the UI or kelta api)',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Layout name (required)' },
    { flag: '--default', description: 'Mark as the default layout' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    collection: z.string().min(1),
    name: z.string().min(1),
    default: z.boolean().default(false),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const attributes: Record<string, unknown> = {
      collectionId,
      name: input.name,
      layoutType: 'DETAIL',
      isDefault: input.default,
    };
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await axios.post<{ data?: { id?: string } }>('/api/page-layouts', {
      data: { type: 'page-layouts', attributes },
    });
    return {
      data: response.data,
      message: `Layout "${input.name}" created for ${input.collection}`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const layoutGet = defineCommand({
  group: 'layouts',
  name: 'get',
  summary: 'Get a page layout by id',
  positionals: [{ name: 'layoutId', description: 'Layout id', required: true }],
  options: [
    {
      flag: '--tree',
      description:
        'Fetch the whole layout as a tree (sections, field placements, related lists) — ' +
        'the document `layouts apply --file` accepts.',
    },
  ],
  input: z.object({ layoutId: z.string().min(1), tree: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const path = input.tree
      ? `/api/page-layouts/${input.layoutId}/tree`
      : `/api/page-layouts/${input.layoutId}`;
    const response = await axios.get<unknown>(path);
    return { data: response.data };
  },
});

const layoutApply = defineCommand({
  group: 'layouts',
  name: 'apply',
  summary: 'Apply a layout tree file (sections, fields, related lists) to a collection',
  options: [
    {
      flag: '--file <path>',
      description: 'Layout tree JSON file — see `layouts get <id> --tree` for the shape',
    },
    {
      flag: '--name <name>',
      description:
        'Layout name (overrides the file\'s own "name"; addresses the layout ' +
        'within the collection, creating it if it does not exist yet)',
    },
  ],
  positionals: [{ name: 'collection', description: 'Collection name (not id)', required: true }],
  input: z.object({
    collection: z.string().min(1),
    file: z.string().min(1),
    name: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const tree = readTreeFile(input.file);
    const name = input.name ?? (typeof tree.name === 'string' ? tree.name : undefined);
    if (!name) {
      throw new CliError('Layout name required — pass --name or set "name" in the tree file', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const body = { ...tree, name };
    const path = `/api/collections/${encodeURIComponent(input.collection)}/layouts/${encodeURIComponent(name)}/tree`;
    const response = await ctx.client.getAxiosInstance().put<{
      layoutId?: string;
      created?: number;
      updated?: number;
      deleted?: number;
      unchanged?: number;
    }>(path, body);
    const counts = response.data;
    return {
      data: counts,
      message:
        `Layout tree applied to "${input.collection}/${name}" ` +
        `(created=${counts.created ?? 0}, updated=${counts.updated ?? 0}, ` +
        `deleted=${counts.deleted ?? 0}, unchanged=${counts.unchanged ?? 0})`,
      ids: counts.layoutId ? [counts.layoutId] : [],
    };
  },
});

const layoutUpdate = defineCommand({
  group: 'layouts',
  name: 'update',
  summary: 'Update a page layout by id',
  positionals: [{ name: 'layoutId', description: 'Layout id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Layout name' },
    { flag: '--default <bool>', description: 'true|false' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    layoutId: z.string().min(1),
    name: z.string().optional(),
    default: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.name) attributes.name = input.name;
    if (input.default !== undefined) attributes.isDefault = input.default === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    // NOTE: the update body type is "pageLayouts" (camelCase) — matches the
    // worker's PATCH contract, which differs from create's "page-layouts".
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/page-layouts/${input.layoutId}`, {
        data: { type: 'pageLayouts', id: input.layoutId, attributes },
      });
    return { data: response.data, message: `Layout ${input.layoutId} updated` };
  },
});

const layoutDelete = defineCommand({
  group: 'layouts',
  name: 'delete',
  summary: 'Delete a page layout',
  dangerous: true,
  positionals: [{ name: 'layoutId', description: 'Layout id', required: true }],
  input: z.object({ layoutId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/page-layouts/${input.layoutId}`);
    return {
      data: { deleted: true, id: input.layoutId },
      message: `Layout ${input.layoutId} deleted`,
      ids: [input.layoutId],
    };
  },
});

const listViewList = defineCommand({
  group: 'list-views',
  name: 'list',
  summary: 'List saved list views of a collection',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  input: z.object({ collection: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const response = await axios.get<unknown>(
      `/api/list-views?filter[collectionId][eq]=${collectionId}`
    );
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'isDefault', header: 'DEFAULT' },
        { key: 'viewType', header: 'VIEW TYPE' },
        { key: 'sortField', header: 'SORT' },
      ],
    };
  },
});

const listViewCreate = defineCommand({
  group: 'list-views',
  name: 'create',
  summary: 'Create a saved list view',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    { flag: '--name <name>', description: 'List view name (required)' },
    { flag: '--columns <list>', description: 'Displayed field names, comma-separated (required)' },
    {
      flag: '--filter <spec>',
      description: 'Filter as field[.op]=value (repeatable)',
      repeatable: true,
    },
    { flag: '--sort <field>', description: 'Sort field, -prefix for descending' },
    { flag: '--default', description: 'Mark as the default list view' },
    { flag: '--visibility <vis>', description: 'PRIVATE (default), PUBLIC or GROUP' },
    {
      flag: '--view-type <type>',
      description: 'Renderer: TABLE (default), KANBAN, CALENDAR or GALLERY',
    },
    { flag: '--lane-field <field>', description: 'Kanban lane field (a picklist field)' },
    { flag: '--card-fields <list>', description: 'Kanban card fields, comma-separated' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    collection: z.string().min(1),
    name: z.string().min(1),
    columns: z.string().min(1),
    filter: z.array(z.string()).default([]),
    sort: z.string().optional(),
    default: z.boolean().default(false),
    visibility: z.string().optional(),
    viewType: z.string().optional(),
    laneField: z.string().optional(),
    cardFields: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const attributes: Record<string, unknown> = {
      collectionId,
      name: input.name,
      columns: parseList(input.columns),
      isDefault: input.default,
      // always sent, matching the MCP tool — the worker expects the key
      filters: input.filter.map((spec) => {
        const parsed = parseFilterSpec(spec);
        return {
          field: parsed.field,
          operator: parsed.operator.toUpperCase(),
          value: parsed.value,
        };
      }),
    };
    if (input.sort) {
      attributes.sortField = input.sort.replace(/^-/, '');
      attributes.sortDirection = input.sort.startsWith('-') ? 'DESC' : 'ASC';
    }
    if (input.visibility) attributes.visibility = input.visibility.trim().toUpperCase();
    if (input.viewType) attributes.viewType = normalizeViewType(input.viewType);
    const typeConfig = kanbanTypeConfig(input.laneField, input.cardFields);
    if (typeConfig) attributes.typeConfig = typeConfig;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await axios.post<{ data?: { id?: string } }>('/api/list-views', {
      data: { type: 'list-views', attributes },
    });
    return {
      data: response.data,
      message: `List view "${input.name}" created for ${input.collection}`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const listViewApply = defineCommand({
  group: 'list-views',
  name: 'apply',
  summary: 'Create-or-update a saved list view, keyed on (collection, name)',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    { flag: '--name <name>', description: 'List view name (required)' },
    { flag: '--columns <list>', description: 'Displayed field names, comma-separated (required)' },
    {
      flag: '--filter <spec>',
      description: 'Filter as field[.op]=value (repeatable); always written as given',
      repeatable: true,
    },
    { flag: '--sort <field>', description: 'Sort field, -prefix for descending' },
    { flag: '--row-limit <n>', description: 'Maximum rows rendered' },
    { flag: '--default <bool>', description: 'true|false — omit to leave the current value alone' },
    { flag: '--visibility <vis>', description: 'PRIVATE, PUBLIC or GROUP' },
    { flag: '--view-type <type>', description: 'Renderer: TABLE, KANBAN, CALENDAR or GALLERY' },
    { flag: '--lane-field <field>', description: 'Kanban lane field (a picklist field)' },
    { flag: '--card-fields <list>', description: 'Kanban card fields, comma-separated' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    collection: z.string().min(1),
    name: z.string().min(1),
    columns: z.string().min(1),
    filter: z.array(z.string()).default([]),
    sort: z.string().optional(),
    rowLimit: z.coerce.number().int().optional(),
    default: z.enum(['true', 'false']).optional(),
    visibility: z.string().optional(),
    viewType: z.string().optional(),
    laneField: z.string().optional(),
    cardFields: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);

    const attributes: Record<string, unknown> = {
      columns: parseList(input.columns),
      filters: input.filter.map((spec) => {
        const parsed = parseFilterSpec(spec);
        return {
          field: parsed.field,
          operator: parsed.operator.toUpperCase(),
          value: parsed.value,
        };
      }),
    };
    if (input.sort) {
      attributes.sortField = input.sort.replace(/^-/, '');
      attributes.sortDirection = input.sort.startsWith('-') ? 'DESC' : 'ASC';
    }
    if (input.rowLimit !== undefined) attributes.rowLimit = input.rowLimit;
    if (input.default !== undefined) attributes.isDefault = input.default === 'true';
    if (input.visibility) attributes.visibility = input.visibility.trim().toUpperCase();
    if (input.viewType) attributes.viewType = normalizeViewType(input.viewType);
    const typeConfig = kanbanTypeConfig(input.laneField, input.cardFields);
    if (typeConfig) attributes.typeConfig = typeConfig;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));

    const found = await axios.get<{
      data?: { id: string; attributes?: Record<string, unknown> }[];
    }>(
      `/api/list-views?filter[collectionId][eq]=${collectionId}` +
        `&filter[name][eq]=${encodeURIComponent(input.name)}&page[size]=1`
    );
    const existing = found.data.data?.[0];

    if (!existing) {
      const created = await axios.post<{ data?: { id?: string } }>('/api/list-views', {
        data: { type: 'list-views', attributes: { collectionId, name: input.name, ...attributes } },
      });
      return {
        data: { action: 'created', id: created.data.data?.id },
        message: `List view "${input.name}" created for ${input.collection}`,
        ids: created.data.data?.id ? [created.data.data.id] : [],
      };
    }

    const changed = Object.keys(attributes).filter(
      (key) => !deepEqual(existing.attributes?.[key], attributes[key])
    );
    if (changed.length === 0) {
      return {
        data: { action: 'unchanged', id: existing.id },
        message: `List view "${input.name}" unchanged`,
        ids: [existing.id],
      };
    }
    const patchAttrs: Record<string, unknown> = {};
    for (const key of changed) patchAttrs[key] = attributes[key];
    await axios.patch(`/api/list-views/${existing.id}`, {
      data: { type: 'list-views', id: existing.id, attributes: patchAttrs },
    });
    return {
      data: { action: 'updated', id: existing.id, changed },
      message: `List view "${input.name}" updated (${changed.join(', ')})`,
      ids: [existing.id],
    };
  },
});

const listViewUpdate = defineCommand({
  group: 'list-views',
  name: 'update',
  summary: 'Update a saved list view by id',
  positionals: [{ name: 'listViewId', description: 'List view id', required: true }],
  options: [
    { flag: '--name <name>', description: 'List view name' },
    { flag: '--columns <list>', description: 'Displayed field names, comma-separated' },
    {
      flag: '--filter <spec>',
      description: 'Filter as field[.op]=value (repeatable); replaces all filters',
      repeatable: true,
    },
    { flag: '--sort <field>', description: 'Sort field, -prefix for descending' },
    { flag: '--default <bool>', description: 'true|false' },
    { flag: '--visibility <vis>', description: 'PRIVATE, PUBLIC or GROUP' },
    {
      flag: '--view-type <type>',
      description: 'Renderer: TABLE, KANBAN, CALENDAR or GALLERY',
    },
    { flag: '--lane-field <field>', description: 'Kanban lane field (a picklist field)' },
    { flag: '--card-fields <list>', description: 'Kanban card fields, comma-separated' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    listViewId: z.string().min(1),
    name: z.string().optional(),
    columns: z.string().optional(),
    filter: z.array(z.string()).default([]),
    sort: z.string().optional(),
    default: z.enum(['true', 'false']).optional(),
    visibility: z.string().optional(),
    viewType: z.string().optional(),
    laneField: z.string().optional(),
    cardFields: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.name) attributes.name = input.name;
    if (input.columns) attributes.columns = parseList(input.columns);
    if (input.filter.length > 0) {
      attributes.filters = input.filter.map((spec) => {
        const parsed = parseFilterSpec(spec);
        return {
          field: parsed.field,
          operator: parsed.operator.toUpperCase(),
          value: parsed.value,
        };
      });
    }
    if (input.sort) {
      attributes.sortField = input.sort.replace(/^-/, '');
      attributes.sortDirection = input.sort.startsWith('-') ? 'DESC' : 'ASC';
    }
    if (input.default !== undefined) attributes.isDefault = input.default === 'true';
    if (input.visibility) attributes.visibility = input.visibility.trim().toUpperCase();
    if (input.viewType) attributes.viewType = normalizeViewType(input.viewType);
    const typeConfig = kanbanTypeConfig(input.laneField, input.cardFields);
    if (typeConfig) attributes.typeConfig = typeConfig;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    if (Object.keys(attributes).length === 0) {
      throw new CliError('Nothing to update — pass at least one field flag', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/list-views/${input.listViewId}`, {
        data: { type: 'list-views', id: input.listViewId, attributes },
      });
    return {
      data: response.data,
      message: `List view ${input.listViewId} updated`,
      ids: [input.listViewId],
    };
  },
});

const listViewGet = defineCommand({
  group: 'list-views',
  name: 'get',
  summary: 'Get a saved list view by id',
  positionals: [{ name: 'listViewId', description: 'List view id', required: true }],
  input: z.object({ listViewId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/list-views/${input.listViewId}`);
    return { data: response.data };
  },
});

const listViewDelete = defineCommand({
  group: 'list-views',
  name: 'delete',
  summary: 'Delete a saved list view',
  dangerous: true,
  positionals: [{ name: 'listViewId', description: 'List view id', required: true }],
  input: z.object({ listViewId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/list-views/${input.listViewId}`);
    return {
      data: { deleted: true, id: input.listViewId },
      message: `List view ${input.listViewId} deleted`,
      ids: [input.listViewId],
    };
  },
});

export const layoutCommands: RegisteredCommand[] = [
  layoutList,
  layoutCreate,
  layoutGet,
  layoutApply,
  layoutUpdate,
  layoutDelete,
  listViewList,
  listViewCreate,
  listViewApply,
  listViewUpdate,
  listViewGet,
  listViewDelete,
];
