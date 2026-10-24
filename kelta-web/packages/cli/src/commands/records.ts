import { z } from 'zod';
import { readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { parseFilterSpec, parseList, parseSort } from '../query.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

/** Hard client-side ceiling for `--all` auto-pagination. */
const ALL_PAGES_CAP = 10_000;
/** HTTP page-size clamp (server-enforced); used as the `--all` page size. */
const MAX_HTTP_PAGE_SIZE = 200;

const listInput = z.object({
  collection: z.string().min(1),
  filter: z.array(z.string()).default([]),
  sort: z.string().optional(),
  fields: z.string().optional(),
  include: z.string().optional(),
  page: z.coerce.number().int().positive().default(1),
  size: z.coerce.number().int().positive().max(MAX_HTTP_PAGE_SIZE).default(25),
  all: z.boolean().default(false),
});

interface ListEnvelope {
  data?: unknown[];
  [key: string]: unknown;
}

/**
 * Warns when a list response did not come from the generic collection route.
 *
 * Some collections are served by a hand-written controller mounted at the same
 * path — `/api/watches` is `WatchController`, which scopes rows to the calling
 * user. A tenant admin listing that collection gets an empty array even when it
 * is full, and the CLI renders that identically to a genuinely empty collection,
 * so `0 rows` reads as fact. Operators have acted on that false negative.
 *
 * The two cases are distinguishable on the wire: the generic route always
 * carries pagination metadata, even for an empty result, while these controllers
 * return a bare `{ data: [...] }`. Absence of that metadata is the signal.
 */
export function warnIfCallerScoped(
  body: ListEnvelope,
  collection: string,
  log: (message: string) => void
): void {
  if (!Array.isArray(body.data)) return;
  if (body.metadata !== undefined || body.meta !== undefined) return;
  log(
    `Note: "${collection}" is served by a caller-scoped endpoint, so these results are ` +
      `limited to what the signed-in user owns. An empty result does not mean the ` +
      `collection is empty.`
  );
}

const list = defineCommand({
  group: 'records',
  name: 'list',
  summary: 'List records in a collection',
  positionals: [{ name: 'collection', description: 'Collection name', required: true }],
  options: [
    {
      flag: '--filter <spec>',
      description: 'Filter as field[.op]=value (repeatable)',
      repeatable: true,
    },
    { flag: '--sort <fields>', description: 'Sort fields, comma-separated, -prefix for desc' },
    { flag: '--fields <fields>', description: 'Sparse fieldset, comma-separated' },
    { flag: '--include <rels>', description: 'Related resources to include, comma-separated' },
    { flag: '--page <number>', description: 'Page number', default: '1' },
    { flag: '--size <number>', description: 'Page size (max 200)', default: '25' },
    { flag: '--all', description: 'Auto-paginate through every page (client cap 10k records)' },
  ],
  examples: [
    {
      cmd: 'kelta records list customers --filter status=active --sort -createdAt --size 50',
      description: 'Newest 50 active customers',
    },
    {
      cmd: 'kelta records list orders --filter amount.gte=100 --fields id,amount,status --all',
      description: 'Every order of 100 or more, three fields each',
    },
  ],
  input: listInput,
  handler: async (ctx, input) => {
    const resource = ctx.client.resource(input.collection);
    const options = {
      filters: input.filter.map(parseFilterSpec),
      sort: input.sort ? parseSort(input.sort) : undefined,
      fields: input.fields ? parseList(input.fields) : undefined,
      include: input.include ? parseList(input.include) : undefined,
    };

    if (!input.all) {
      const body = (await resource.list({
        ...options,
        page: input.page,
        size: input.size,
      })) as ListEnvelope;
      warnIfCallerScoped(body, input.collection, ctx.log);
      return { data: body };
    }

    const rows: unknown[] = [];
    let page = 1;
    for (;;) {
      const body = (await resource.list({
        ...options,
        page,
        size: MAX_HTTP_PAGE_SIZE,
      })) as ListEnvelope;
      if (page === 1) warnIfCallerScoped(body, input.collection, ctx.log);
      const batch = Array.isArray(body.data) ? body.data : [];
      rows.push(...batch);
      if (batch.length < MAX_HTTP_PAGE_SIZE) break;
      if (rows.length >= ALL_PAGES_CAP) {
        ctx.log(`Warning: --all stopped at the ${String(ALL_PAGES_CAP)}-record client cap`);
        break;
      }
      page += 1;
    }
    return { data: { data: rows } };
  },
});

const get = defineCommand({
  group: 'records',
  name: 'get',
  summary: 'Get a single record',
  positionals: [
    { name: 'collection', description: 'Collection name', required: true },
    { name: 'id', description: 'Record id', required: true },
  ],
  options: [
    { flag: '--include <rels>', description: 'Related resources to include' },
    { flag: '--fields <fields>', description: 'Sparse fieldset, comma-separated' },
  ],
  examples: [{ cmd: 'kelta records get orders rec_123 --include customer' }],
  input: z.object({
    collection: z.string().min(1),
    id: z.string().min(1),
    include: z.string().optional(),
    fields: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const options = {
      ...(input.include ? { include: parseList(input.include) } : {}),
      ...(input.fields ? { fields: parseList(input.fields) } : {}),
    };
    const body = await ctx.client
      .resource(input.collection)
      .get(input.id, Object.keys(options).length > 0 ? options : undefined);
    return { data: body };
  },
});

const create = defineCommand({
  group: 'records',
  name: 'create',
  summary: 'Create a record',
  positionals: [{ name: 'collection', description: 'Collection name', required: true }],
  options: [{ flag: '--data <json>', description: 'Attributes as JSON, @file, or - for stdin' }],
  examples: [
    { cmd: 'kelta records create customers --data \'{"name": "Acme Ltd", "status": "active"}\'' },
    {
      cmd: 'kelta records create orders --data @order.json',
      description: 'Attributes from a file',
    },
  ],
  input: z.object({ collection: z.string().min(1), data: z.string().min(1) }),
  handler: async (ctx, input) => {
    const attributes = readDataArgument(input.data);
    const body = await ctx.client.resource(input.collection).create(attributes);
    return { data: body };
  },
});

const update = defineCommand({
  group: 'records',
  name: 'update',
  summary: 'Update a record (partial)',
  positionals: [
    { name: 'collection', description: 'Collection name', required: true },
    { name: 'id', description: 'Record id', required: true },
  ],
  options: [{ flag: '--data <json>', description: 'Attributes as JSON, @file, or - for stdin' }],
  examples: [{ cmd: 'kelta records update orders rec_123 --data \'{"status": "shipped"}\'' }],
  input: z.object({
    collection: z.string().min(1),
    id: z.string().min(1),
    data: z.string().min(1),
  }),
  handler: async (ctx, input) => {
    const attributes = readDataArgument(input.data);
    const body = await ctx.client.resource(input.collection).patch(input.id, attributes);
    return { data: body };
  },
});

const remove = defineCommand({
  group: 'records',
  name: 'delete',
  summary: 'Delete a record',
  dangerous: true,
  positionals: [
    { name: 'collection', description: 'Collection name', required: true },
    { name: 'id', description: 'Record id', required: true },
  ],
  examples: [{ cmd: 'kelta records delete customers rec_123 --yes' }],
  input: z.object({ collection: z.string().min(1), id: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.resource(input.collection).delete(input.id);
    return {
      data: { deleted: true, collection: input.collection, id: input.id },
      message: `Deleted ${input.collection}/${input.id}`,
      ids: [input.id],
    };
  },
});

const bulk = defineCommand({
  group: 'records',
  name: 'bulk',
  summary: 'Apply a batch of atomic operations (all-or-nothing, max 100 ops)',
  dangerous: true,
  options: [
    {
      flag: '--data <json>',
      description: 'Body as JSON, @file, or - : {"atomic:operations":[{op,data|ref},…]} (required)',
    },
  ],
  examples: [
    {
      cmd: 'kelta records bulk --data @ops.json --yes',
      description: 'Apply a JSON:API atomic:operations document, all-or-nothing',
    },
  ],
  input: z.object({ data: z.string().min(1) }),
  handler: async (ctx, input) => {
    const body = readDataArgument(input.data);
    if (!Array.isArray(body['atomic:operations'])) {
      throw new CliError('--data must contain an "atomic:operations" array', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const response = await ctx.client.getAxiosInstance().post<unknown>('/api/operations', body);
    return {
      data: response.data,
      message: `${String((body['atomic:operations'] as unknown[]).length)} operation(s) applied`,
    };
  },
});

const search = defineCommand({
  group: 'records',
  name: 'search',
  summary: 'Full-text search across collections (query min 3 chars)',
  positionals: [{ name: 'query', description: 'Search text', required: true }],
  options: [{ flag: '--limit <n>', description: 'Max results (max 100)', default: '20' }],
  examples: [{ cmd: 'kelta records search acme --limit 5' }],
  input: z.object({
    query: z.string().min(3),
    limit: z.coerce.number().int().positive().max(100).default(20),
  }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(
        `/api/_search?q=${encodeURIComponent(input.query)}&limit=${String(input.limit)}`
      );
    return {
      data: response.data,
      columns: [
        { key: 'collectionName', header: 'COLLECTION' },
        { key: 'displayValue', header: 'RECORD' },
        { key: 'id', header: 'ID' },
        { key: 'rank', header: 'RANK' },
      ],
    };
  },
});

const semanticSearch = defineCommand({
  group: 'records',
  name: 'semantic-search',
  summary: 'Vector similarity search within one collection (needs a VECTOR field)',
  positionals: [
    { name: 'collection', description: 'Collection name', required: true },
    { name: 'query', description: 'Query text', required: true },
  ],
  options: [{ flag: '--limit <n>', description: 'Max results', default: '10' }],
  examples: [{ cmd: "kelta records semantic-search orders 'late delivery complaints' --limit 3" }],
  input: z.object({
    collection: z.string().min(1),
    query: z.string().min(1),
    limit: z.coerce.number().int().positive().default(10),
  }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(`/api/${input.collection}/semantic-search`, {
        query: input.query,
        limit: input.limit,
      });
    return { data: response.data };
  },
});

export const recordCommands: RegisteredCommand[] = [
  list,
  get,
  create,
  update,
  remove,
  bulk,
  search,
  semanticSearch,
];
