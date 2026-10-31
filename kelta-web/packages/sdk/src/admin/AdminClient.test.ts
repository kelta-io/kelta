import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Mock } from 'vitest';
import type { AxiosInstance } from 'axios';
import { AdminClient } from './AdminClient';
import type { CreateBulkJobRequest } from './types';

const createMockAxios = () =>
  ({
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  }) as unknown as AxiosInstance;

describe('AdminClient bulkJobs', () => {
  let axios: AxiosInstance;
  let client: AdminClient;
  let mockPost: Mock;
  let mockGet: Mock;
  let mockPatch: Mock;

  const jobResponse = {
    data: {
      type: 'bulk-jobs',
      id: 'job-1',
      attributes: {
        status: 'QUEUED',
        operation: 'INSERT',
        collectionId: 'col-1',
        totalRecords: 2,
        processedRecords: 0,
        successRecords: 0,
        errorRecords: 0,
        batchSize: 200,
      },
    },
  };

  beforeEach(() => {
    axios = createMockAxios();
    client = new AdminClient(axios);
    mockPost = axios.post as Mock;
    mockGet = axios.get as Mock;
    mockPatch = axios.patch as Mock;
  });

  describe('create()', () => {
    it('sends the plain request body (not a JSON:API envelope) to /api/bulk-jobs', async () => {
      mockPost.mockResolvedValue({ data: jobResponse });
      const request: CreateBulkJobRequest = {
        collectionId: 'col-1',
        operation: 'INSERT',
        records: [{ name: 'a' }, { name: 'b' }],
      };

      await client.bulkJobs.create('tenant-1', 'user-1', request);

      expect(mockPost).toHaveBeenCalledWith('/api/bulk-jobs', request);
      const body = mockPost.mock.calls[0][1];
      expect(body.collectionId).toBe('col-1');
      expect(body.operation).toBe('INSERT');
      expect(body.records).toEqual([{ name: 'a' }, { name: 'b' }]);
      expect(body.data).toBeUndefined();
    });

    it('passes optional externalIdField and batchSize at the top level', async () => {
      mockPost.mockResolvedValue({ data: jobResponse });
      const request: CreateBulkJobRequest = {
        collectionId: 'col-1',
        operation: 'UPSERT',
        externalIdField: 'externalId',
        batchSize: 50,
        records: [{ externalId: 'x1' }],
      };

      await client.bulkJobs.create('tenant-1', 'user-1', request);

      const body = mockPost.mock.calls[0][1];
      expect(body.externalIdField).toBe('externalId');
      expect(body.batchSize).toBe(50);
    });

    it('unwraps the JSON:API response to a flat BulkJob', async () => {
      mockPost.mockResolvedValue({ data: jobResponse });

      const job = await client.bulkJobs.create('tenant-1', 'user-1', {
        collectionId: 'col-1',
        operation: 'INSERT',
        records: [{ name: 'a' }],
      });

      expect(job.id).toBe('job-1');
      expect(job.status).toBe('QUEUED');
      expect(job.collectionId).toBe('col-1');
      expect(job.totalRecords).toBe(2);
    });
  });

  describe('list()', () => {
    it('unwraps the JSON:API list from /api/bulk-jobs', async () => {
      mockGet.mockResolvedValue({ data: { data: [jobResponse.data] } });

      const jobs = await client.bulkJobs.list();

      expect(mockGet).toHaveBeenCalledWith('/api/bulk-jobs');
      expect(jobs).toHaveLength(1);
      expect(jobs[0].id).toBe('job-1');
    });
  });

  describe('get()', () => {
    it('fetches a single job by id', async () => {
      mockGet.mockResolvedValue({ data: jobResponse });

      const job = await client.bulkJobs.get('job-1');

      expect(mockGet).toHaveBeenCalledWith('/api/bulk-jobs/job-1');
      expect(job.id).toBe('job-1');
    });
  });

  describe('abort()', () => {
    it('patches the job with a JSON:API ABORTED body', async () => {
      mockPatch.mockResolvedValue({ data: jobResponse });

      await client.bulkJobs.abort('job-1');

      expect(mockPatch).toHaveBeenCalledWith('/api/bulk-jobs/job-1', {
        data: {
          type: 'bulk-jobs',
          id: 'job-1',
          attributes: { status: 'ABORTED' },
        },
      });
    });
  });

  describe('getResults() / getErrors()', () => {
    it('fetches results and error-filtered results', async () => {
      mockGet.mockResolvedValue({ data: { data: [] } });

      await client.bulkJobs.getResults('job-1');
      await client.bulkJobs.getErrors('job-1');

      expect(mockGet).toHaveBeenNthCalledWith(1, '/api/bulk-jobs/job-1/bulk-job-results');
      expect(mockGet).toHaveBeenNthCalledWith(
        2,
        '/api/bulk-jobs/job-1/bulk-job-results?filter[status][eq]=ERROR'
      );
    });
  });
});

describe('AdminClient tenants.inviteAdmin', () => {
  it('POSTs the email as a plain body to the tenant admin-invite endpoint', async () => {
    const axios = createMockAxios();
    const client = new AdminClient(axios);
    const mockPost = axios.post as Mock;
    const invite = { status: 'INVITED', tenantId: 't1', userId: 'u1', email: 'owner@example.com' };
    mockPost.mockResolvedValue({ data: invite });

    const result = await client.tenants.inviteAdmin('t1', 'owner@example.com');

    expect(mockPost).toHaveBeenCalledWith('/api/tenants/t1/admin-invite', {
      email: 'owner@example.com',
    });
    expect(result).toEqual(invite);
  });
});

describe('AdminClient tenants.bootstrapToken', () => {
  it('POSTs expiresIn and userId to the tenant bootstrap-token endpoint', async () => {
    const axios = createMockAxios();
    const client = new AdminClient(axios);
    const mockPost = axios.post as Mock;
    const minted = {
      token: 'klt_abc',
      tokenPrefix: 'klt_abc',
      name: 'bootstrap-ops@example.com-2026-10-05T00:00:00Z',
      tenantId: 't1',
      userId: 'u1',
      expiresAt: '2026-10-05T01:00:00Z',
    };
    mockPost.mockResolvedValue({ data: minted });

    const result = await client.tenants.bootstrapToken('t1', { expiresIn: '2h', userId: 'u1' });

    expect(mockPost).toHaveBeenCalledWith('/api/tenants/t1/bootstrap-token', {
      expiresIn: '2h',
      userId: 'u1',
    });
    expect(result).toEqual(minted);
  });

  it('sends an empty body when no options are given, so the server defaults apply', async () => {
    const axios = createMockAxios();
    const client = new AdminClient(axios);
    const mockPost = axios.post as Mock;
    mockPost.mockResolvedValue({ data: {} });

    await client.tenants.bootstrapToken('t1');

    expect(mockPost).toHaveBeenCalledWith('/api/tenants/t1/bootstrap-token', {});
  });
});

describe('AdminClient users.list', () => {
  it('leaves SERVICE users (module service principals) out of the people list', async () => {
    const axios = createMockAxios();
    const client = new AdminClient(axios);
    const mockGet = axios.get as Mock;
    mockGet.mockResolvedValue({ data: { data: [], meta: {} } });

    await client.users.list('ada', 'ACTIVE', 0, 20);

    const url = decodeURIComponent(mockGet.mock.calls[0][0] as string);
    expect(url).toContain('filter[userType][neq]=SERVICE');
    expect(url).toContain('filter[search][contains]=ada');
    expect(url).toContain('filter[status][eq]=ACTIVE');
  });
});

describe('AdminClient users.invitePortal', () => {
  let axios: AxiosInstance;
  let client: AdminClient;
  let mockPost: Mock;

  beforeEach(() => {
    axios = createMockAxios();
    client = new AdminClient(axios);
    mockPost = axios.post as Mock;
  });

  it('POSTs the invite as a plain (non-JSON:API) body and returns the result', async () => {
    mockPost.mockResolvedValue({ data: { userId: 'u1', status: 'INVITED' } });

    const result = await client.users.invitePortal({
      email: 'pat@example.com',
      firstName: 'Pat',
    });

    expect(mockPost).toHaveBeenCalledWith('/api/admin/users/portal-invite', {
      email: 'pat@example.com',
      firstName: 'Pat',
    });
    expect(result).toEqual({ userId: 'u1', status: 'INVITED' });
  });

  it('surfaces a re-invite response unchanged', async () => {
    mockPost.mockResolvedValue({ data: { userId: 'u1', status: 'REINVITED' } });

    const result = await client.users.invitePortal({ email: 'pat@example.com' });

    expect(result.status).toBe('REINVITED');
  });
});

describe('AdminClient users.tokens.create', () => {
  let axios: AxiosInstance;
  let client: AdminClient;
  let mockPost: Mock;

  beforeEach(() => {
    axios = createMockAxios();
    client = new AdminClient(axios);
    mockPost = axios.post as Mock;
  });

  it('POSTs to the admin-on-behalf-of mint endpoint for the target user', async () => {
    mockPost.mockResolvedValue({
      data: {
        token: 'klt_abc123',
        name: 'Integration token',
        tokenPrefix: 'klt_abc1',
        scopes: ['api'],
        expiresAt: '2027-01-01T00:00:00Z',
      },
    });

    const result = await client.users.tokens.create('user-1', {
      name: 'Integration token',
      expiresInDays: 90,
    });

    expect(mockPost).toHaveBeenCalledWith('/api/admin/users/user-1/tokens', {
      name: 'Integration token',
      expiresInDays: 90,
    });
    expect(result.token).toBe('klt_abc123');
  });
});

describe('AdminClient users.tokens.list / revoke', () => {
  let axios: AxiosInstance;
  let client: AdminClient;

  beforeEach(() => {
    axios = createMockAxios();
    client = new AdminClient(axios);
  });

  it("GETs the target user's live tokens and unwraps data", async () => {
    const token = {
      id: 'tok-1',
      name: 'CI bot',
      tokenPrefix: 'klt_abc1',
      scopes: ['api'],
      createdAt: '2026-10-01T00:00:00Z',
      expiresAt: '2026-12-30T00:00:00Z',
    };
    const mockGet = axios.get as Mock;
    mockGet.mockResolvedValue({ data: { data: [token] } });

    const result = await client.users.tokens.list('user-1');

    expect(mockGet).toHaveBeenCalledWith('/api/admin/users/user-1/tokens');
    expect(result).toEqual([token]);
  });

  it("DELETEs the target user's token", async () => {
    const mockDelete = axios.delete as Mock;
    mockDelete.mockResolvedValue({ data: { status: 'revoked', tokenId: 'tok-1' } });

    await client.users.tokens.revoke('user-1', 'tok-1');

    expect(mockDelete).toHaveBeenCalledWith('/api/admin/users/user-1/tokens/tok-1');
  });
});

describe('AdminClient recordTypes', () => {
  let axios: AxiosInstance;
  let client: AdminClient;
  let mockGet: Mock;
  let mockPost: Mock;
  let mockPatch: Mock;

  const recordTypeResponse = (attributes: Record<string, unknown>) => ({
    data: {
      data: {
        type: 'record-types',
        id: 'rt-1',
        attributes: { collectionId: 'col-1', name: 'Standard', isDefault: false, ...attributes },
      },
    },
  });

  const overrideRow = (id: string, availableValues: unknown) => ({
    type: 'record-type-picklists',
    id,
    attributes: { recordTypeId: 'rt-1', fieldId: `field-${id}`, availableValues },
  });

  beforeEach(() => {
    axios = createMockAxios();
    client = new AdminClient(axios);
    mockGet = axios.get as Mock;
    mockPost = axios.post as Mock;
    mockPatch = axios.patch as Mock;
  });

  it('reads isActive and mirrors it onto the deprecated active alias', async () => {
    mockGet.mockResolvedValue({
      data: {
        data: [
          { type: 'record-types', id: 'rt-1', attributes: { name: 'A', isActive: true } },
          { type: 'record-types', id: 'rt-2', attributes: { name: 'B', isActive: false } },
        ],
      },
    });

    const types = await client.recordTypes.list('col-1');

    expect(types.map((t) => [t.isActive, t.active])).toEqual([
      [true, true],
      [false, false],
    ]);
  });

  it('sends a deprecated active flag on update as isActive', async () => {
    mockPatch.mockResolvedValue(recordTypeResponse({ isActive: false }));

    const updated = await client.recordTypes.update('col-1', 'rt-1', { active: false });

    const body = mockPatch.mock.calls[0][1];
    expect(body.data.attributes).toEqual({ isActive: false });
    expect(updated.isActive).toBe(false);
    expect(updated.active).toBe(false);
  });

  it('prefers an explicit isActive over the deprecated active flag on update', async () => {
    mockPatch.mockResolvedValue(recordTypeResponse({ isActive: true }));

    await client.recordTypes.update('col-1', 'rt-1', { isActive: true, active: false });

    expect(mockPatch.mock.calls[0][1].data.attributes).toEqual({ isActive: true });
  });

  it('requests every picklist override in one page of 200', async () => {
    mockGet.mockResolvedValue({ data: { data: [] } });

    await client.recordTypes.getPicklistOverrides('col-1', 'rt 1');

    expect(mockGet).toHaveBeenCalledWith(
      '/api/record-type-picklists?filter[recordTypeId][eq]=rt%201&page[size]=200'
    );
  });

  it('reads availableValues as a string array, parsing legacy JSON-string rows', async () => {
    mockGet.mockResolvedValue({
      data: {
        data: [
          overrideRow('a', ['Open', 'Closed']),
          overrideRow('b', '["High","Low"]'),
          overrideRow('c', 'not json'),
          overrideRow('d', { not: 'an array' }),
          overrideRow('e', [1, 2]),
        ],
      },
    });

    const overrides = await client.recordTypes.getPicklistOverrides('col-1', 'rt-1');

    expect(overrides.map((o) => o.availableValues)).toEqual([
      ['Open', 'Closed'],
      ['High', 'Low'],
      [],
      [],
      [],
    ]);
    expect(overrides[0].fieldId).toBe('field-a');
  });

  const fieldsResponse = {
    data: {
      data: [
        { type: 'fields', id: 'field-a', attributes: { name: 'status' } },
        { type: 'fields', id: 'field-b', attributes: { name: 'priority' } },
      ],
    },
  };

  /** Routes GETs: the collection's fields, otherwise the given override rows. */
  const routeGets = (overrideRows: unknown[]) =>
    mockGet.mockImplementation(async (url: string) =>
      url.startsWith('/api/fields') ? fieldsResponse : { data: { data: overrideRows } }
    );

  it('resolves fieldName from the collection fields, falling back to the field id', async () => {
    routeGets([overrideRow('a', ['Open']), overrideRow('z', ['High'])]);

    const overrides = await client.recordTypes.getPicklistOverrides('col-1', 'rt-1');

    expect(mockGet).toHaveBeenCalledWith(
      '/api/fields?filter[collectionId][eq]=col-1&page[size]=200'
    );
    expect(overrides.map((o) => o.fieldName)).toEqual(['status', 'field-z']);
  });

  it('still returns overrides when the field lookup fails', async () => {
    mockGet.mockImplementation(async (url: string) => {
      if (url.startsWith('/api/fields')) throw new Error('forbidden');
      return { data: { data: [overrideRow('a', ['Open'])] } };
    });

    const overrides = await client.recordTypes.getPicklistOverrides('col-1', 'rt-1');

    expect(overrides[0].fieldName).toBe('field-a');
  });

  it('creates the override when none exists for the record type and field', async () => {
    routeGets([]);
    mockPost.mockResolvedValue({ data: { data: overrideRow('a', '["Open"]') } });

    const created = await client.recordTypes.setPicklistOverride('col-1', 'rt-1', 'field-a', {
      availableValues: ['Open'],
      defaultValue: 'Open',
    });

    expect(mockGet).toHaveBeenCalledWith(
      '/api/record-type-picklists?filter[recordTypeId][eq]=rt-1&filter[fieldId][eq]=field-a&page[size]=1'
    );
    expect(mockPatch).not.toHaveBeenCalled();
    const body = mockPost.mock.calls[0][1];
    expect(mockPost.mock.calls[0][0]).toBe('/api/record-type-picklists');
    expect(body.data.attributes).toEqual({
      availableValues: ['Open'],
      defaultValue: 'Open',
      recordTypeId: 'rt-1',
      fieldId: 'field-a',
    });
    expect(created.availableValues).toEqual(['Open']);
    expect(created.fieldName).toBe('status');
  });

  it('updates the existing override in place instead of creating a duplicate', async () => {
    routeGets([overrideRow('a', ['Open', 'Closed'])]);
    mockPatch.mockResolvedValue({ data: { data: overrideRow('a', ['Closed']) } });

    const updated = await client.recordTypes.setPicklistOverride('col-1', 'rt-1', 'field-a', {
      availableValues: ['Closed'],
    });

    expect(mockPost).not.toHaveBeenCalled();
    expect(mockPatch.mock.calls[0][0]).toBe('/api/record-type-picklists/a');
    expect(mockPatch.mock.calls[0][1].data).toMatchObject({
      type: 'record-type-picklists',
      id: 'a',
      attributes: { availableValues: ['Closed'] },
    });
    expect(updated.availableValues).toEqual(['Closed']);
    expect(updated.fieldName).toBe('status');
  });
});
