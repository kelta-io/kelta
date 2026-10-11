import { z } from 'zod';
import { picklistIdByName } from '../admin/lookups.js';
import { parseList } from '../query.js';
import { flattenBody, flattenResource } from '../render/flatten.js';
import { renderTable } from '../render/table.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

interface JsonApiResource {
  id?: string;
  attributes?: Record<string, unknown>;
}

const list = defineCommand({
  group: 'picklists',
  name: 'list',
  summary: 'List global picklists',
  options: [{ flag: '--name <name>', description: 'Filter by exact name' }],
  input: z.object({ name: z.string().optional() }),
  handler: async (ctx, input) => {
    const filter = input.name ? `&filter[name][EQ]=${encodeURIComponent(input.name)}` : '';
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/global-picklists?page[size]=200${filter}`);
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'description', header: 'DESCRIPTION' },
      ],
    };
  },
});

const get = defineCommand({
  group: 'picklists',
  name: 'get',
  summary: 'Show a global picklist with its values',
  positionals: [{ name: 'picklist', description: 'Picklist name or id', required: true }],
  input: z.object({ picklist: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const id = await picklistIdByName(axios, input.picklist);
    const picklist = await axios.get<{ data?: JsonApiResource }>(`/api/global-picklists/${id}`);
    const valuesResponse = await axios.get<{ data?: JsonApiResource[] }>(
      `/api/picklist-values?filter[picklistSourceId][EQ]=${encodeURIComponent(id)}` +
        `&filter[picklistSourceType][EQ]=GLOBAL&page[size]=200`
    );
    const values = [...(valuesResponse.data.data ?? [])].sort(
      (a, b) => Number(a.attributes?.sortOrder ?? 0) - Number(b.attributes?.sortOrder ?? 0)
    );
    const flatValues = values.map(flattenResource);
    const name = String(picklist.data.data?.attributes?.name ?? input.picklist);
    return {
      data: ctx.global.raw
        ? { data: picklist.data.data, values }
        : { ...(flattenBody(picklist.data) as Record<string, unknown>), values: flatValues },
      verbatim: true,
      human:
        `Picklist ${name} (${id})\n` +
        renderTable(flatValues, [
          { key: 'value', header: 'VALUE' },
          { key: 'label', header: 'LABEL' },
          { key: 'sortOrder', header: 'SORT' },
          { key: 'isActive', header: 'ACTIVE' },
        ]),
      ids: [id],
    };
  },
});

const create = defineCommand({
  group: 'picklists',
  name: 'create',
  summary: 'Create a global picklist (optionally with initial values)',
  options: [
    { flag: '--name <name>', description: 'Picklist name (required)' },
    { flag: '--description <text>', description: 'Description' },
    {
      flag: '--values <list>',
      description: 'Initial values, comma-separated (value used as label)',
    },
  ],
  input: z.object({
    name: z.string().min(1),
    description: z.string().optional(),
    values: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const attributes: Record<string, unknown> = { name: input.name };
    if (input.description) attributes.description = input.description;
    const created = await axios.post<{ data?: { id?: string } }>('/api/global-picklists', {
      data: { type: 'global-picklists', attributes },
    });
    const picklistId = created.data.data?.id;
    const values = input.values ? parseList(input.values) : [];
    if (picklistId) {
      for (const [index, value] of values.entries()) {
        await axios.post('/api/picklist-values', {
          data: {
            type: 'picklist-values',
            attributes: {
              value,
              label: value,
              picklistSourceType: 'GLOBAL',
              picklistSourceId: picklistId,
              sortOrder: index,
              isActive: true,
              isDefault: false,
            },
          },
        });
      }
    }
    return {
      data: created.data,
      message: `Picklist "${input.name}" created${values.length ? ` with ${String(values.length)} value(s)` : ''}`,
      ids: picklistId ? [picklistId] : [],
    };
  },
});

const remove = defineCommand({
  group: 'picklists',
  name: 'delete',
  summary: 'Delete a global picklist',
  dangerous: true,
  positionals: [{ name: 'picklist', description: 'Picklist name or id', required: true }],
  input: z.object({ picklist: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const id = await picklistIdByName(axios, input.picklist);
    await axios.delete(`/api/global-picklists/${id}`);
    return {
      data: { deleted: true, id },
      message: `Picklist "${input.picklist}" deleted`,
      ids: [id],
    };
  },
});

const valueAdd = defineCommand({
  group: 'picklists',
  name: 'value-add',
  summary: 'Add a value to a global picklist',
  positionals: [{ name: 'picklist', description: 'Picklist name or id', required: true }],
  options: [
    { flag: '--value <value>', description: 'Stored value (required)' },
    { flag: '--label <label>', description: 'Display label (default: the value)' },
    { flag: '--sort <n>', description: 'Sort order', default: '0' },
    { flag: '--default', description: 'Mark as the default value' },
  ],
  input: z.object({
    picklist: z.string().min(1),
    value: z.string().min(1),
    label: z.string().optional(),
    sort: z.coerce.number().int().default(0),
    default: z.boolean().default(false),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const id = await picklistIdByName(axios, input.picklist);
    const response = await axios.post<{ data?: { id?: string } }>('/api/picklist-values', {
      data: {
        type: 'picklist-values',
        attributes: {
          value: input.value,
          label: input.label ?? input.value,
          picklistSourceType: 'GLOBAL',
          picklistSourceId: id,
          globalPicklistId: id,
          sortOrder: input.sort,
          isActive: true,
          isDefault: input.default,
        },
      },
    });
    return {
      data: response.data,
      message: `Value "${input.value}" added to "${input.picklist}"`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const valueUpdate = defineCommand({
  group: 'picklists',
  name: 'value-update',
  summary: 'Update a picklist value by id',
  positionals: [{ name: 'valueId', description: 'Picklist value id', required: true }],
  options: [
    { flag: '--value <value>', description: 'Stored value' },
    { flag: '--label <label>', description: 'Display label' },
    { flag: '--sort <n>', description: 'Sort order' },
    { flag: '--active <bool>', description: 'true|false' },
    { flag: '--default <bool>', description: 'true|false' },
  ],
  input: z.object({
    valueId: z.string().min(1),
    value: z.string().optional(),
    label: z.string().optional(),
    sort: z.coerce.number().int().optional(),
    active: z.enum(['true', 'false']).optional(),
    default: z.enum(['true', 'false']).optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.value !== undefined) attributes.value = input.value;
    if (input.label !== undefined) attributes.label = input.label;
    if (input.sort !== undefined) attributes.sortOrder = input.sort;
    if (input.active !== undefined) attributes.isActive = input.active === 'true';
    if (input.default !== undefined) attributes.isDefault = input.default === 'true';
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/picklist-values/${input.valueId}`, {
        data: { type: 'picklist-values', id: input.valueId, attributes },
      });
    return { data: response.data, message: `Picklist value ${input.valueId} updated` };
  },
});

const valueDeactivate = defineCommand({
  group: 'picklists',
  name: 'value-deactivate',
  summary: 'Deactivate a picklist value (records keep it; new picks cannot use it)',
  positionals: [{ name: 'valueId', description: 'Picklist value id', required: true }],
  input: z.object({ valueId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/picklist-values/${input.valueId}`, {
        data: { type: 'picklist-values', id: input.valueId, attributes: { isActive: false } },
      });
    return { data: response.data, message: `Picklist value ${input.valueId} deactivated` };
  },
});

export const picklistCommands: RegisteredCommand[] = [
  list,
  get,
  create,
  remove,
  valueAdd,
  valueUpdate,
  valueDeactivate,
];
