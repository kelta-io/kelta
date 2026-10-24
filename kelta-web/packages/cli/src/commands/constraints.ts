import { z } from 'zod';
import { parseList } from '../query.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

// NOT JSON:API — CompositeUniqueConstraintController takes/returns plain JSON.

const list = defineCommand({
  group: 'constraints',
  name: 'list',
  summary: 'List composite unique constraints of a collection',
  positionals: [{ name: 'collection', description: 'Collection name', required: true }],
  examples: [{ cmd: 'kelta constraints list orders' }],
  input: z.object({ collection: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/admin/collections/${input.collection}/unique-constraints`);
    return { data: response.data };
  },
});

const create = defineCommand({
  group: 'constraints',
  name: 'create',
  summary: 'Create a composite unique constraint (Postgres unique index; duplicates → 409)',
  positionals: [{ name: 'collection', description: 'Collection name', required: true }],
  options: [{ flag: '--fields <list>', description: 'Field names, comma-separated (required)' }],
  examples: [
    {
      cmd: 'kelta constraints create orders --fields customer,order_number',
      description: 'Make each order number unique per customer',
    },
  ],
  input: z.object({
    collection: z.string().min(1),
    fields: z.string().min(1),
  }),
  handler: async (ctx, input) => {
    const fieldNames = parseList(input.fields);
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(`/api/admin/collections/${input.collection}/unique-constraints`, {
        fieldNames,
      });
    return {
      data: response.data,
      message: `Unique constraint on (${fieldNames.join(', ')}) created for ${input.collection}`,
    };
  },
});

const remove = defineCommand({
  group: 'constraints',
  name: 'delete',
  summary: 'Drop a composite unique constraint by index name',
  dangerous: true,
  positionals: [
    { name: 'collection', description: 'Collection name', required: true },
    { name: 'indexName', description: 'Index name (see: kelta constraints list)', required: true },
  ],
  examples: [{ cmd: 'kelta constraints delete orders uq_orders_customer_order_number --yes' }],
  input: z.object({ collection: z.string().min(1), indexName: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client
      .getAxiosInstance()
      .delete(`/api/admin/collections/${input.collection}/unique-constraints/${input.indexName}`);
    return {
      data: { deleted: true, indexName: input.indexName },
      message: `Constraint ${input.indexName} dropped from ${input.collection}`,
    };
  },
});

export const constraintCommands: RegisteredCommand[] = [list, create, remove];
