import { z } from 'zod';
import { buildFieldBody, resolveFieldType } from '../admin/fieldBody.js';
import { collectionIdByName } from '../admin/lookups.js';
import { readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const INTEGER_TYPES = new Set(['INTEGER', 'LONG']);
const DECIMAL_TYPES = new Set(['DOUBLE', 'CURRENCY', 'PERCENT']);

/** Turn `--default` into the JSON value the field type stores (BOOLEAN → boolean, numeric → number). */
export function typedDefault(type: string, value: string): string | number | boolean {
  const nativeType = resolveFieldType(type);
  if (nativeType === 'BOOLEAN') {
    if (value === 'true') return true;
    if (value === 'false') return false;
  } else if (INTEGER_TYPES.has(nativeType) || DECIMAL_TYPES.has(nativeType)) {
    const n = Number(value);
    const valid = INTEGER_TYPES.has(nativeType) ? Number.isSafeInteger(n) : Number.isFinite(n);
    if (value.trim() !== '' && valid) return n;
  } else {
    return value;
  }
  throw new CliError(`--default "${value}" is not a valid ${nativeType} value`, {
    code: 'INVALID_ARGUMENTS',
    exitCode: EXIT.USAGE,
  });
}

const list = defineCommand({
  group: 'fields',
  name: 'list',
  summary: 'List the fields of a collection',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  input: z.object({ collection: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const response = await axios.get<unknown>(
      `/api/fields?filter[collectionId][EQ]=${collectionId}&page[size]=200`
    );
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'type', header: 'TYPE' },
        { key: 'displayName', header: 'DISPLAY' },
        { key: 'required', header: 'REQUIRED' },
        { key: 'uniqueConstraint', header: 'UNIQUE' },
      ],
    };
  },
});

const add = defineCommand({
  group: 'fields',
  name: 'add',
  summary:
    'Add a field to a collection (friendly type aliases: text, number, picklist, reference, …)',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Field API name (required)' },
    { flag: '--type <type>', description: 'Field type alias or native enum (required)' },
    { flag: '--display-name <label>', description: 'Display name' },
    { flag: '--required', description: 'Field is required' },
    { flag: '--unique', description: 'Unique constraint' },
    { flag: '--indexed', description: 'Create an index' },
    { flag: '--searchable', description: 'Full-text searchable' },
    { flag: '--description <text>', description: 'Description' },
    {
      flag: '--default <value>',
      description: 'Default value (true|false for BOOLEAN, a number for numeric types)',
    },
    { flag: '--picklist <name|id>', description: 'Global picklist source (picklist types)' },
    { flag: '--reference <collection>', description: 'Target collection (reference types)' },
    { flag: '--relationship-name <name>', description: 'Relationship name (reference types)' },
    { flag: '--dimension <n>', description: 'Vector dimension (vector type)' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    collection: z.string().min(1),
    name: z.string().min(1),
    type: z.string().min(1),
    displayName: z.string().optional(),
    required: z.boolean().optional(),
    unique: z.boolean().optional(),
    indexed: z.boolean().optional(),
    searchable: z.boolean().optional(),
    description: z.string().optional(),
    default: z.string().optional(),
    picklist: z.string().optional(),
    reference: z.string().optional(),
    relationshipName: z.string().optional(),
    dimension: z.coerce.number().int().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const body = await buildFieldBody(axios, {
      collection: input.collection,
      name: input.name,
      type: input.type,
      displayName: input.displayName,
      required: input.required,
      unique: input.unique,
      indexed: input.indexed,
      searchable: input.searchable,
      description: input.description,
      defaultValue:
        input.default === undefined ? undefined : typedDefault(input.type, input.default),
      picklist: input.picklist,
      reference: input.reference,
      relationshipName: input.relationshipName,
      dimension: input.dimension,
      extra: input.data ? readDataArgument(input.data) : undefined,
    });
    const response = await axios.post<{ data?: { id?: string } }>('/api/fields', body);
    return {
      data: response.data,
      message: `Field "${input.name}" (${String(body.data.attributes.type)}) added to ${input.collection}`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const update = defineCommand({
  group: 'fields',
  name: 'update',
  summary: 'Update a field by id',
  positionals: [{ name: 'fieldId', description: 'Field id', required: true }],
  options: [
    { flag: '--display-name <label>', description: 'Display name' },
    { flag: '--required <bool>', description: 'true|false' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    fieldId: z.string().min(1),
    displayName: z.string().optional(),
    required: z.enum(['true', 'false']).optional(),
    description: z.string().optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.displayName) attributes.displayName = input.displayName;
    if (input.required !== undefined) attributes.required = input.required === 'true';
    if (input.description) attributes.description = input.description;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/fields/${input.fieldId}`, {
        data: { type: 'fields', id: input.fieldId, attributes },
      });
    return { data: response.data, message: `Field ${input.fieldId} updated` };
  },
});

const remove = defineCommand({
  group: 'fields',
  name: 'remove',
  summary: 'Remove a field and its data',
  dangerous: true,
  positionals: [{ name: 'fieldId', description: 'Field id', required: true }],
  input: z.object({ fieldId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/fields/${input.fieldId}`);
    return {
      data: { deleted: true, id: input.fieldId },
      message: `Field ${input.fieldId} removed`,
      ids: [input.fieldId],
    };
  },
});

export const fieldCommands: RegisteredCommand[] = [list, add, update, remove];
