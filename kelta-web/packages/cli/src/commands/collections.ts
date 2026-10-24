import { z } from 'zod';
import { collectionIdByName } from '../admin/lookups.js';
import { readDataArgument } from '../data.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

interface CollectionAttrs {
  name?: string;
  displayName?: string;
  fields?: { name: string; type: string; nullable: boolean }[];
  readOnly?: boolean;
}

interface CollectionResource {
  id: string;
  attributes?: CollectionAttrs;
}

const list = defineCommand({
  group: 'collections',
  name: 'list',
  summary: 'List all collections',
  examples: [{ cmd: 'kelta collections list' }],
  input: z.object({}),
  handler: async (ctx) => {
    const body = (await ctx.client.resource('collections').list({ size: 200 })) as {
      data?: CollectionResource[];
    };
    const rows = (body.data ?? []).map((col) => ({
      id: col.id,
      name: col.attributes?.name ?? col.id,
      displayName: col.attributes?.displayName ?? '',
      fields: col.attributes?.fields?.length ?? 0,
      readOnly: col.attributes?.readOnly ?? false,
    }));
    return {
      data: body,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'displayName', header: 'DISPLAY NAME' },
        { key: 'fields', header: 'FIELDS' },
        { key: 'readOnly', header: 'READ-ONLY' },
      ],
      human: rowsAsTable(rows),
    };
  },
});

function rowsAsTable(
  rows: { name: string; displayName: string; fields: number; readOnly: boolean }[]
): string {
  if (rows.length === 0) return 'No collections found.\n';
  const out = [
    `${'NAME'.padEnd(30)} ${'DISPLAY NAME'.padEnd(30)} ${'FIELDS'.padEnd(8)} READ-ONLY`,
    '-'.repeat(80),
  ];
  for (const row of rows) {
    out.push(
      `${row.name.padEnd(30)} ${row.displayName.padEnd(30)} ${String(row.fields).padEnd(8)} ${row.readOnly ? 'Yes' : 'No'}`
    );
  }
  return out.join('\n') + '\n';
}

const describe = defineCommand({
  group: 'collections',
  name: 'describe',
  summary: 'Show collection details and fields',
  positionals: [{ name: 'name', description: 'Collection name', required: true }],
  examples: [{ cmd: 'kelta collections describe customers' }],
  input: z.object({ name: z.string().min(1) }),
  handler: async (ctx, input) => {
    const body = (await ctx.client.resource('collections').get(input.name)) as {
      data?: CollectionResource;
    };
    const attrs = body.data?.attributes ?? {};
    const lines = [
      `Collection: ${attrs.name ?? input.name}`,
      `Display:    ${attrs.displayName ?? ''}`,
      `Read-Only:  ${attrs.readOnly ? 'Yes' : 'No'}`,
      '',
      'Fields:',
      `  ${'Name'.padEnd(25)} ${'Type'.padEnd(15)} Nullable`,
      `  ${'-'.repeat(50)}`,
      ...(attrs.fields ?? []).map(
        (field) =>
          `  ${field.name.padEnd(25)} ${field.type.padEnd(15)} ${field.nullable ? 'Yes' : 'No'}`
      ),
    ];
    return { data: body, human: lines.join('\n') + '\n' };
  },
});

const create = defineCommand({
  group: 'collections',
  name: 'create',
  summary: 'Create a collection',
  options: [
    { flag: '--name <name>', description: 'API name (required)' },
    { flag: '--display-name <label>', description: 'Display name' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  examples: [
    {
      cmd: "kelta collections create --name invoices --display-name Invoices --description 'Customer invoices'",
    },
    {
      cmd: 'kelta collections create --name orders --data @orders-collection.json',
      description: 'Create from a JSON attributes file',
    },
  ],
  input: z.object({
    name: z.string().min(1),
    displayName: z.string().optional(),
    description: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = { name: input.name };
    if (input.displayName) attributes.displayName = input.displayName;
    if (input.description) attributes.description = input.description;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .post<{ data?: CollectionResource }>('/api/collections', {
        data: { type: 'collections', attributes },
      });
    return {
      data: response.data,
      message: `Collection "${input.name}" created (id ${response.data.data?.id ?? '?'})`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const update = defineCommand({
  group: 'collections',
  name: 'update',
  summary: 'Update a collection (name or id)',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    { flag: '--display-name <label>', description: 'Display name' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  examples: [{ cmd: "kelta collections update invoices --display-name 'Sales Invoices'" }],
  input: z.object({
    collection: z.string().min(1),
    displayName: z.string().optional(),
    description: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const id = await collectionIdByName(axios, input.collection);
    const attributes: Record<string, unknown> = {};
    if (input.displayName) attributes.displayName = input.displayName;
    if (input.description) attributes.description = input.description;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await axios.patch<{ data?: CollectionResource }>(`/api/collections/${id}`, {
      data: { type: 'collections', id, attributes },
    });
    return { data: response.data, message: `Collection "${input.collection}" updated` };
  },
});

const remove = defineCommand({
  group: 'collections',
  name: 'delete',
  summary: 'Delete a collection and its data',
  dangerous: true,
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    {
      flag: '--force',
      description:
        'Confirm deletion of a collection that still has dependent data (attachments, ' +
        'layouts, reports, validation rules, field history, record versions, …); the ' +
        'server blocks the delete without this.',
    },
  ],
  examples: [
    { cmd: 'kelta collections delete invoices --yes' },
    {
      cmd: 'kelta collections delete invoices --force --yes',
      description: 'Delete even though layouts and other dependent data exist',
    },
  ],
  input: z.object({ collection: z.string().min(1), force: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const id = await collectionIdByName(axios, input.collection);
    await axios.delete(`/api/collections/${id}`, {
      params: input.force ? { force: true } : undefined,
    });
    return {
      data: { deleted: true, collection: input.collection, id },
      message: `Collection "${input.collection}" deleted`,
      ids: [id],
    };
  },
});

export const collectionCommands: RegisteredCommand[] = [list, describe, create, update, remove];
