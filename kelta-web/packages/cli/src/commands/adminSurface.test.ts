import { describe, it, expect, vi } from 'vitest';
import type { CommandContext, RegisteredCommand } from '../registry/types.js';
import { auditCommands } from './audit.js';
import { collectionCommands } from './collections.js';
import { constraintCommands } from './constraints.js';
import { flowCommands } from './flows.js';
import { layoutCommands } from './layouts.js';
import { limitCommands } from './limits.js';
import { picklistCommands } from './picklists.js';
import { recordCommands } from './records.js';
import { userCommands } from './users.js';
import { validationCommands } from './validation.js';

const CID = '11111111-2222-3333-4444-555555555555';

interface FakeAxios {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
}

function fakeAxios(getRoutes: Record<string, unknown> = {}): FakeAxios {
  return {
    get: vi.fn().mockImplementation((url: string) => {
      for (const [prefix, body] of Object.entries(getRoutes)) {
        if (url.startsWith(prefix)) return Promise.resolve({ data: body });
      }
      return Promise.resolve({ data: { data: [] } });
    }),
    post: vi.fn().mockResolvedValue({ data: { data: { id: 'new-id' } } }),
    patch: vi.fn().mockResolvedValue({ data: { data: { id: 'patched' } } }),
    put: vi.fn().mockResolvedValue({ data: { status: 'ok' } }),
    delete: vi.fn().mockResolvedValue({ data: undefined }),
  };
}

function ctx(axios: FakeAxios): CommandContext {
  return {
    profile: { name: 'test' },
    global: { raw: false, quiet: false, yes: true },
    log: vi.fn(),
    client: { getAxiosInstance: () => axios },
  } as unknown as CommandContext;
}

function command(defs: RegisteredCommand[], name: string): RegisteredCommand {
  const def = defs.find((c) => c.name === name);
  if (!def) throw new Error(`no command ${name}`);
  return def;
}

async function run(
  defs: RegisteredCommand[],
  name: string,
  input: Record<string, unknown>,
  axios: FakeAxios
) {
  const def = command(defs, name);
  return def.handler(ctx(axios), def.input.parse(input) as never);
}

const COLLECTION_ROUTE = { '/api/collections/invoices': { data: { id: CID } } };

describe('collections create/update/delete', () => {
  it('create posts a JSON:API collections body', async () => {
    const axios = fakeAxios();
    await run(collectionCommands, 'create', { name: 'orders', displayName: 'Orders' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/collections', {
      data: { type: 'collections', attributes: { name: 'orders', displayName: 'Orders' } },
    });
  });

  it('update resolves the name and PATCHes by id', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    await run(collectionCommands, 'update', { collection: 'invoices', description: 'd' }, axios);
    expect(axios.patch).toHaveBeenCalledWith(`/api/collections/${CID}`, {
      data: { type: 'collections', id: CID, attributes: { description: 'd' } },
    });
  });

  it('delete is dangerous and deletes by resolved id (no force by default)', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    expect(command(collectionCommands, 'delete').dangerous).toBe(true);
    await run(collectionCommands, 'delete', { collection: 'invoices' }, axios);
    expect(axios.delete).toHaveBeenCalledWith(`/api/collections/${CID}`, { params: undefined });
  });

  it('delete --force sends ?force=true so the server guard lets a used collection through', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    await run(collectionCommands, 'delete', { collection: 'invoices', force: true }, axios);
    expect(axios.delete).toHaveBeenCalledWith(`/api/collections/${CID}`, {
      params: { force: true },
    });
  });
});

describe('validation-rules create', () => {
  it('sends the ERROR-condition contract with defaults', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    await run(
      validationCommands,
      'create',
      {
        collection: 'invoices',
        name: 'amount-positive',
        formula: 'amount <= 0',
        message: 'Amount must be positive',
      },
      axios
    );
    expect(axios.post).toHaveBeenCalledWith('/api/validation-rules', {
      data: {
        type: 'validation-rules',
        attributes: {
          collectionId: CID,
          name: 'amount-positive',
          errorConditionFormula: 'amount <= 0',
          errorMessage: 'Amount must be positive',
          active: true,
          evaluateOn: 'CREATE_AND_UPDATE',
          severity: 'ERROR',
        },
      },
    });
  });
});

describe('constraints', () => {
  it('create posts the PLAIN (non-JSON:API) fieldNames body', async () => {
    const axios = fakeAxios();
    await run(constraintCommands, 'create', { collection: 'invoices', fields: 'a, b' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/admin/collections/invoices/unique-constraints', {
      fieldNames: ['a', 'b'],
    });
  });

  it('delete targets the index name', async () => {
    const axios = fakeAxios();
    await run(constraintCommands, 'delete', { collection: 'invoices', indexName: 'ux_1' }, axios);
    expect(axios.delete).toHaveBeenCalledWith(
      '/api/admin/collections/invoices/unique-constraints/ux_1'
    );
  });
});

describe('picklist value-add', () => {
  it('sends BOTH picklistSourceId and globalPicklistId (MCP parity)', async () => {
    const axios = fakeAxios({
      '/api/global-picklists?filter[name][EQ]=statuses': { data: [{ id: CID }] },
    });
    await run(picklistCommands, 'value-add', { picklist: 'statuses', value: 'OPEN' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/picklist-values', {
      data: {
        type: 'picklist-values',
        attributes: {
          value: 'OPEN',
          label: 'OPEN',
          picklistSourceType: 'GLOBAL',
          picklistSourceId: CID,
          globalPicklistId: CID,
          sortOrder: 0,
          isActive: true,
          isDefault: false,
        },
      },
    });
  });
});

describe('list-views create', () => {
  it('transforms filters and splits sort into field+direction', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    await run(
      layoutCommands,
      'create',
      {
        collection: 'invoices',
        name: 'Open',
        columns: 'status,amount',
        filter: ['status=OPEN'],
        sort: '-createdAt',
        default: false,
      },
      axios
    );
    // layoutCommands contains both groups; the list-views create is the one hitting /api/list-views
    const call = axios.post.mock.calls.find(([url]) => url === '/api/list-views');
    expect(call).toBeUndefined(); // 'create' in layouts group creates a LAYOUT, not a list view
  });

  it('list-views create posts columns/filters/sort', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const listViewCreate = layoutCommands.filter(
      (c) => c.group === 'list-views' && c.name === 'create'
    )[0];
    await listViewCreate.handler(
      ctx(axios),
      listViewCreate.input.parse({
        collection: 'invoices',
        name: 'Open',
        columns: 'status,amount',
        filter: ['status=OPEN', 'amount.gte=10'],
        sort: '-createdAt',
        default: true,
      }) as never
    );
    expect(axios.post).toHaveBeenCalledWith('/api/list-views', {
      data: {
        type: 'list-views',
        attributes: {
          collectionId: CID,
          name: 'Open',
          columns: ['status', 'amount'],
          isDefault: true,
          filters: [
            { field: 'status', operator: 'EQ', value: 'OPEN' },
            { field: 'amount', operator: 'GTE', value: '10' },
          ],
          sortField: 'createdAt',
          sortDirection: 'DESC',
        },
      },
    });
  });
});

describe('list-views renderer flags', () => {
  function listViewCommand(name: string): RegisteredCommand {
    return layoutCommands.filter((c) => c.group === 'list-views' && c.name === name)[0];
  }

  it('create publishes a kanban board from --view-type/--lane-field/--card-fields', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const create = listViewCommand('create');
    await create.handler(
      ctx(axios),
      create.input.parse({
        collection: 'invoices',
        name: 'The board',
        columns: 'title,status',
        visibility: 'public',
        viewType: 'kanban',
        laneField: 'status',
        cardFields: 'title, tier',
      }) as never
    );
    const [, body] = axios.post.mock.calls.find(([url]) => url === '/api/list-views')!;
    const attributes = (body as { data: { attributes: Record<string, unknown> } }).data.attributes;
    // the column's CHECK only accepts the uppercase form
    expect(attributes.viewType).toBe('KANBAN');
    expect(attributes.visibility).toBe('PUBLIC');
    // same row the MCP create_listview call writes
    expect(attributes.typeConfig).toEqual({
      kanban: { laneField: 'status', cardFields: ['title', 'tier'] },
    });
  });

  it('create leaves the renderer attributes off when no flag is given', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const create = listViewCommand('create');
    await create.handler(
      ctx(axios),
      create.input.parse({ collection: 'invoices', name: 'Plain', columns: 'title' }) as never
    );
    const [, body] = axios.post.mock.calls.find(([url]) => url === '/api/list-views')!;
    const attributes = (body as { data: { attributes: Record<string, unknown> } }).data.attributes;
    expect(attributes).not.toHaveProperty('viewType');
    expect(attributes).not.toHaveProperty('typeConfig');
  });

  it('rejects an unknown --view-type before hitting the API', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const create = listViewCommand('create');
    await expect(
      create.handler(
        ctx(axios),
        create.input.parse({
          collection: 'invoices',
          name: 'x',
          columns: 'title',
          viewType: 'timeline',
        }) as never
      )
    ).rejects.toThrow(/view-type/);
    expect(axios.post).not.toHaveBeenCalled();
  });

  it('rejects --card-fields without --lane-field (card fields are stored with it)', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const create = listViewCommand('create');
    await expect(
      create.handler(
        ctx(axios),
        create.input.parse({
          collection: 'invoices',
          name: 'x',
          columns: 'title',
          viewType: 'KANBAN',
          cardFields: 'title',
        }) as never
      )
    ).rejects.toThrow(/lane-field/);
  });

  it('update patches only what was passed', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const update = listViewCommand('update');
    await update.handler(
      ctx(axios),
      update.input.parse({ listViewId: 'lv-1', viewType: 'GALLERY' }) as never
    );
    expect(axios.patch).toHaveBeenCalledWith('/api/list-views/lv-1', {
      data: { type: 'list-views', id: 'lv-1', attributes: { viewType: 'GALLERY' } },
    });
  });

  it('update refuses an empty change set rather than PATCHing nothing', async () => {
    const axios = fakeAxios(COLLECTION_ROUTE);
    const update = listViewCommand('update');
    await expect(
      update.handler(ctx(axios), update.input.parse({ listViewId: 'lv-1' }) as never)
    ).rejects.toThrow(/Nothing to update/);
    expect(axios.patch).not.toHaveBeenCalled();
  });
});

describe('layouts update', () => {
  it('uses the camelCase pageLayouts body type (worker PATCH contract)', async () => {
    const axios = fakeAxios();
    await run(
      layoutCommands.filter((c) => c.group === 'layouts'),
      'update',
      { layoutId: 'L1', name: 'New' },
      axios
    );
    expect(axios.patch).toHaveBeenCalledWith('/api/page-layouts/L1', {
      data: { type: 'pageLayouts', id: 'L1', attributes: { name: 'New' } },
    });
  });
});

describe('flows', () => {
  it('execute wraps input under "input" and returns the execution id', async () => {
    const axios = fakeAxios();
    axios.post.mockResolvedValueOnce({
      data: { data: { id: 'exec-1', attributes: { status: 'RUNNING' } } },
    });
    const result = await run(
      flowCommands,
      'execute',
      { flowId: 'flow-1', input: '{"dryRun":true}', test: false, wait: false },
      axios
    );
    expect(axios.post).toHaveBeenCalledWith('/api/flows/flow-1/execute', {
      input: { dryRun: true },
    });
    expect(result.ids).toEqual(['exec-1']);
  });

  it('execute --wait polls to a terminal status', async () => {
    const axios = fakeAxios();
    axios.post.mockResolvedValueOnce({
      data: { data: { id: 'exec-1', attributes: { status: 'RUNNING' } } },
    });
    axios.get
      .mockResolvedValueOnce({
        data: { data: { id: 'exec-1', attributes: { status: 'RUNNING' } } },
      })
      .mockResolvedValueOnce({
        data: { data: { id: 'exec-1', attributes: { status: 'COMPLETED' } } },
      });
    const def = command(flowCommands, 'execute');
    const original = globalThis.setTimeout;
    vi.stubGlobal('setTimeout', ((fn: () => void) => original(fn, 0)) as never);
    try {
      const result = await def.handler(
        ctx(axios),
        def.input.parse({ flowId: 'flow-1', wait: true }) as never
      );
      expect(result.message).toContain('COMPLETED');
      expect(axios.get).toHaveBeenCalledWith('/api/flows/executions/exec-1');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('publish posts the change summary; retry passes the mode', async () => {
    const axios = fakeAxios();
    await run(flowCommands, 'publish', { flowId: 'f1', summary: 'v2' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/flows/f1/publish', { changeSummary: 'v2' });
    await run(flowCommands, 'retry', { executionId: 'e1', mode: 'from-failure' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/flows/executions/e1/retry?mode=from-failure');
  });
});

describe('users', () => {
  it('list builds JSON:API filter params', async () => {
    const axios = fakeAxios();
    await run(
      userCommands,
      'list',
      { search: 'ada', status: 'ACTIVE', page: '2', size: '10' },
      axios
    );
    const url = decodeURIComponent((axios.get.mock.calls[0] as [string])[0]);
    expect(url).toContain('filter[search][contains]=ada');
    expect(url).toContain('filter[status][eq]=ACTIVE');
    expect(url).toContain('page[number]=2');
  });

  it('portal-invite posts a raw (non-JSON:API) body', async () => {
    const axios = fakeAxios();
    await run(userCommands, 'portal-invite', { email: 'x@y.io' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/admin/users/portal-invite', { email: 'x@y.io' });
  });

  it('reset-password is dangerous and hits the admin endpoint', async () => {
    const axios = fakeAxios();
    expect(command(userCommands, 'reset-password').dangerous).toBe(true);
    await run(userCommands, 'reset-password', { userId: 'u1' }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/admin/users/u1/reset-password');
  });

  it('token-create is dangerous and mints via the admin SDK client for the target user', async () => {
    const create = vi.fn().mockResolvedValue({
      token: 'klt_xyz123',
      name: 'CI bot',
      tokenPrefix: 'klt_xyz1',
      scopes: ['api'],
      expiresAt: '2027-01-01T00:00:00Z',
    });
    const def = command(userCommands, 'token-create');
    expect(def.dangerous).toBe(true);
    const context = {
      profile: { name: 'test' },
      global: { raw: false, quiet: false, yes: true },
      log: vi.fn(),
      client: { admin: { users: { tokens: { create } } } },
    } as unknown as CommandContext;

    const result = await def.handler(
      context,
      def.input.parse({ userId: 'u1', name: 'CI bot' }) as never
    );

    expect(create).toHaveBeenCalledWith('u1', { name: 'CI bot', expiresInDays: 90 });
    expect(result.message).toContain('klt_xyz123');
    expect(result.message).toContain('u1');
  });
});

describe('limits', () => {
  it('set-tier PUTs the tier; set PUTs the raw limits map', async () => {
    const axios = fakeAxios();
    await run(limitCommands, 'set-tier', { tier: 'PROFESSIONAL' }, axios);
    expect(axios.put).toHaveBeenCalledWith('/api/governor-limits/tier', { tier: 'PROFESSIONAL' });
    await run(limitCommands, 'set', { data: '{"apiCallsPerDay":5000}' }, axios);
    expect(axios.put).toHaveBeenCalledWith('/api/governor-limits', { apiCallsPerDay: 5000 });
  });
});

describe('audit', () => {
  it('applies the --since filter and newest-first sort', async () => {
    const axios = fakeAxios();
    await run(auditCommands, 'security', { since: '2026-08-01T00:00:00Z', size: '50' }, axios);
    const url = decodeURIComponent((axios.get.mock.calls[0] as [string])[0]);
    expect(url).toContain('/api/security-audit-logs?');
    expect(url).toContain('sort=-createdAt');
    expect(url).toContain('filter[createdAt][gte]=2026-08-01T00:00:00Z');
  });
});

describe('records bulk/search/semantic-search', () => {
  it('bulk validates the atomic:operations shape and posts to /api/operations', async () => {
    const axios = fakeAxios();
    await expect(run(recordCommands, 'bulk', { data: '{"nope":[]}' }, axios)).rejects.toMatchObject(
      { code: 'INVALID_ARGUMENTS' }
    );
    const body = '{"atomic:operations":[{"op":"add","data":{"type":"invoices","attributes":{}}}]}';
    await run(recordCommands, 'bulk', { data: body }, axios);
    expect(axios.post).toHaveBeenCalledWith('/api/operations', JSON.parse(body));
  });

  it('search hits /api/_search with q + limit; semantic-search posts the query body', async () => {
    const axios = fakeAxios();
    await run(recordCommands, 'search', { query: 'acme', limit: '5' }, axios);
    expect(axios.get).toHaveBeenCalledWith('/api/_search?q=acme&limit=5');
    await run(
      recordCommands,
      'semantic-search',
      { collection: 'notes', query: 'similar things', limit: '3' },
      axios
    );
    expect(axios.post).toHaveBeenCalledWith('/api/notes/semantic-search', {
      query: 'similar things',
      limit: 3,
    });
  });
});
