import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ResourceClient } from './ResourceClient';
import { KeltaClient } from '../client/KeltaClient';
import { ValidationError } from '../errors';

// Create a factory for mock axios instances
const createMockAxiosInstance = () => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
  defaults: {
    baseURL: 'https://api.example.com',
    headers: {
      'Content-Type': 'application/json',
    },
  },
  interceptors: {
    request: { use: vi.fn() },
    response: { use: vi.fn() },
  },
});

// Mock axios
vi.mock('axios', async () => {
  const actual = await vi.importActual('axios');
  return {
    ...actual,
    default: {
      create: vi.fn(() => createMockAxiosInstance()),
      isAxiosError: (error: unknown) =>
        error && typeof error === 'object' && 'isAxiosError' in error,
    },
  };
});

describe('ResourceClient', () => {
  let client: KeltaClient;
  let resourceClient: ResourceClient<{ id: string; name: string }>;
  let mockAxiosGet: ReturnType<typeof vi.fn>;
  let mockAxiosPost: ReturnType<typeof vi.fn>;
  let mockAxiosPatch: ReturnType<typeof vi.fn>;
  let mockAxiosDelete: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    client = new KeltaClient({ baseUrl: 'https://api.example.com' });
    resourceClient = client.resource<{ id: string; name: string }>('users');

    const axiosInstance = client.getAxiosInstance();
    mockAxiosGet = axiosInstance.get as ReturnType<typeof vi.fn>;
    mockAxiosPost = axiosInstance.post as ReturnType<typeof vi.fn>;
    mockAxiosPatch = axiosInstance.patch as ReturnType<typeof vi.fn>;
    mockAxiosDelete = axiosInstance.delete as ReturnType<typeof vi.fn>;
  });

  describe('Resource name', () => {
    it('should return the resource name', () => {
      expect(resourceClient.getName()).toBe('users');
    });
  });

  describe('list() - Requirements 3.1, 3.6, 3.7, 10.1, 10.2, 10.3', () => {
    const validListResponse = {
      data: [
        { id: '1', name: 'Alice' },
        { id: '2', name: 'Bob' },
      ],
      pagination: {
        page: 1,
        size: 10,
        total: 2,
        totalPages: 1,
      },
    };

    it('should send GET request to /api/{resourceName} (Requirement 3.1)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list();

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', { params: {} });
    });

    it('should return typed data with pagination metadata (Requirement 3.7)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      const result = await resourceClient.list();

      expect(result.data).toHaveLength(2);
      expect(result.data[0].name).toBe('Alice');
      expect(result.pagination.page).toBe(1);
      expect(result.pagination.total).toBe(2);
    });

    it('should validate response against schema when validation is enabled (Requirement 10.1)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      const result = await resourceClient.list();

      expect(result).toEqual(validListResponse);
    });

    it('should throw ValidationError when response is missing pagination (Requirement 10.3)', async () => {
      const invalidResponse = {
        data: [{ id: '1', name: 'Alice' }],
        // Missing pagination
      };
      mockAxiosGet.mockResolvedValue({ data: invalidResponse });

      await expect(resourceClient.list()).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when response is missing data array (Requirement 10.3)', async () => {
      const invalidResponse = {
        pagination: {
          page: 1,
          size: 10,
          total: 0,
          totalPages: 0,
        },
        // Missing data
      };
      mockAxiosGet.mockResolvedValue({ data: invalidResponse });

      await expect(resourceClient.list()).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError with details about the mismatch (Requirement 10.3)', async () => {
      const invalidResponse = {
        data: [{ id: '1', name: 'Alice' }],
        pagination: {
          page: 1,
          // Missing size, total, totalPages
        },
      };
      mockAxiosGet.mockResolvedValue({ data: invalidResponse });

      try {
        await resourceClient.list();
        expect.fail('Should have thrown ValidationError');
      } catch (error) {
        expect(error).toBeInstanceOf(ValidationError);
        expect((error as ValidationError).message).toContain('Invalid list response');
        expect((error as ValidationError).message).toContain('users');
      }
    });

    it('should skip validation when validation is disabled (Requirement 10.4)', async () => {
      const clientNoValidation = new KeltaClient({
        baseUrl: 'https://api.example.com',
        validation: false,
      });
      const resourceNoValidation = clientNoValidation.resource('users');
      const mockGet = clientNoValidation.getAxiosInstance().get as ReturnType<typeof vi.fn>;

      // Invalid response - missing pagination
      const invalidResponse = {
        data: [{ id: '1', name: 'Alice' }],
      };
      mockGet.mockResolvedValue({ data: invalidResponse });

      // Should not throw even with invalid response
      const result = await resourceNoValidation.list();
      expect(result.data).toHaveLength(1);
    });

    it('should include JSON:API pagination query parameters (Requirement 3.2)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list({ page: 2, size: 20 });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', {
        params: { 'page[number]': '2', 'page[size]': '20' },
      });
    });

    it('should include JSON:API sort query parameters (Requirement 3.3)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list({
        sort: [
          { field: 'name', direction: 'asc' },
          { field: 'createdAt', direction: 'desc' },
        ],
      });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', {
        params: { sort: 'name,-createdAt' },
      });
    });

    it('should include JSON:API filter query parameters (Requirement 3.4)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list({
        filters: [
          { field: 'status', operator: 'eq', value: 'active' },
          { field: 'age', operator: 'gte', value: 18 },
        ],
      });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', {
        params: {
          'filter[status][eq]': 'active',
          'filter[age][gte]': '18',
        },
      });
    });

    it('should send correct operator strings matching backend FilterOperator enum', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list({
        filters: [
          { field: 'name', operator: 'neq', value: 'admin' },
          { field: 'bio', operator: 'starts', value: 'Hello' },
          { field: 'email', operator: 'ends', value: '.com' },
          { field: 'note', operator: 'icontains', value: 'urgent' },
          { field: 'tag', operator: 'istarts', value: 'vip' },
          { field: 'code', operator: 'iends', value: 'xyz' },
          { field: 'type', operator: 'ieq', value: 'admin' },
          { field: 'deletedAt', operator: 'isnull', value: 'true' },
        ],
      });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', {
        params: {
          'filter[name][neq]': 'admin',
          'filter[bio][starts]': 'Hello',
          'filter[email][ends]': '.com',
          'filter[note][icontains]': 'urgent',
          'filter[tag][istarts]': 'vip',
          'filter[code][iends]': 'xyz',
          'filter[type][ieq]': 'admin',
          'filter[deletedAt][isnull]': 'true',
        },
      });
    });

    it('should include JSON:API sparse fieldsets with type bracket notation (Requirement 3.5)', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list({ fields: ['id', 'name', 'email'] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', {
        params: { 'fields[users]': 'id,name,email' },
      });
    });

    it('should include JSON:API include parameter', async () => {
      mockAxiosGet.mockResolvedValue({ data: validListResponse });

      await resourceClient.list({ include: ['accounts', 'contacts'] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users', {
        params: { include: 'accounts,contacts' },
      });
    });
  });

  describe('get() - Requirements 4.1, 10.1, 10.2, 10.3', () => {
    it('should send GET request to /api/{resourceName}/{id} (Requirement 4.1)', async () => {
      mockAxiosGet.mockResolvedValue({ data: { id: '123', name: 'Alice' } });

      await resourceClient.get('123');

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users/123', undefined);
    });

    it('should return the resource data (Requirement 10.2)', async () => {
      const userData = { id: '123', name: 'Alice' };
      mockAxiosGet.mockResolvedValue({ data: userData });

      const result = await resourceClient.get('123');

      expect(result).toEqual(userData);
    });

    it('should send GET request with include parameter when options provided', async () => {
      mockAxiosGet.mockResolvedValue({ data: { id: '123', name: 'Alice' } });

      await resourceClient.get('123', { include: ['accounts', 'contacts'] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users/123', {
        params: { include: 'accounts,contacts' },
      });
    });

    it('should send GET request without params when include is empty', async () => {
      mockAxiosGet.mockResolvedValue({ data: { id: '123', name: 'Alice' } });

      await resourceClient.get('123', { include: [] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users/123', undefined);
    });

    it('should send GET request with a sparse fieldset as fields[resourceName]', async () => {
      mockAxiosGet.mockResolvedValue({ data: { id: '123', name: 'Alice' } });

      await resourceClient.get('123', { fields: ['name', 'email'] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users/123', {
        params: { 'fields[users]': 'name,email' },
      });
    });

    it('should send include and fields together', async () => {
      mockAxiosGet.mockResolvedValue({ data: { id: '123', name: 'Alice' } });

      await resourceClient.get('123', { include: ['accounts'], fields: ['name'] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users/123', {
        params: { include: 'accounts', 'fields[users]': 'name' },
      });
    });

    it('should send GET request without params when fields is empty', async () => {
      mockAxiosGet.mockResolvedValue({ data: { id: '123', name: 'Alice' } });

      await resourceClient.get('123', { fields: [] });

      expect(mockAxiosGet).toHaveBeenCalledWith('/api/users/123', undefined);
    });

    it('should throw ValidationError when response is null (Requirement 10.3)', async () => {
      mockAxiosGet.mockResolvedValue({ data: null });

      await expect(resourceClient.get('123')).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when response is undefined (Requirement 10.3)', async () => {
      mockAxiosGet.mockResolvedValue({ data: undefined });

      await expect(resourceClient.get('123')).rejects.toThrow(ValidationError);
    });

    it('should skip validation when validation is disabled (Requirement 10.4)', async () => {
      const clientNoValidation = new KeltaClient({
        baseUrl: 'https://api.example.com',
        validation: false,
      });
      const resourceNoValidation = clientNoValidation.resource('users');
      const mockGet = clientNoValidation.getAxiosInstance().get as ReturnType<typeof vi.fn>;

      mockGet.mockResolvedValue({ data: null });

      // Should not throw even with null response
      const result = await resourceNoValidation.get('123');
      expect(result).toBeNull();
    });
  });

  describe('create() - Requirements 4.2, 10.1, 10.2, 10.3', () => {
    it('should send POST request to /api/{resourceName} with JSON:API wrapped data (Requirement 4.2)', async () => {
      const newUser = { name: 'Charlie' };
      mockAxiosPost.mockResolvedValue({ data: { id: '456', name: 'Charlie' } });

      await resourceClient.create(newUser);

      expect(mockAxiosPost).toHaveBeenCalledWith('/api/users', {
        data: {
          type: 'users',
          attributes: { name: 'Charlie' },
        },
      });
    });

    it('should strip system fields from JSON:API attributes', async () => {
      const newUser = {
        id: '456',
        name: 'Charlie',
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      };
      mockAxiosPost.mockResolvedValue({ data: { id: '456', name: 'Charlie' } });

      await resourceClient.create(newUser);

      expect(mockAxiosPost).toHaveBeenCalledWith('/api/users', {
        data: {
          type: 'users',
          attributes: { name: 'Charlie' },
        },
      });
    });

    it('should return the created resource (Requirement 10.2)', async () => {
      const newUser = { name: 'Charlie' };
      const createdUser = { id: '456', name: 'Charlie' };
      mockAxiosPost.mockResolvedValue({ data: createdUser });

      const result = await resourceClient.create(newUser);

      expect(result).toEqual(createdUser);
    });

    it('should throw ValidationError when response is null (Requirement 10.3)', async () => {
      mockAxiosPost.mockResolvedValue({ data: null });

      await expect(resourceClient.create({ name: 'Test' })).rejects.toThrow(ValidationError);
    });

    it('should skip validation when validation is disabled (Requirement 10.4)', async () => {
      const clientNoValidation = new KeltaClient({
        baseUrl: 'https://api.example.com',
        validation: false,
      });
      const resourceNoValidation = clientNoValidation.resource('users');
      const mockPost = clientNoValidation.getAxiosInstance().post as ReturnType<typeof vi.fn>;

      mockPost.mockResolvedValue({ data: null });

      const result = await resourceNoValidation.create({ name: 'Test' });
      expect(result).toBeNull();
    });
  });

  describe('update() - Requirements 4.3, 10.1, 10.2, 10.3', () => {
    it('should send PATCH request to /api/{resourceName}/{id} with JSON:API wrapped data (Requirement 4.3)', async () => {
      const updatedUser = { id: '123', name: 'Alice Updated' };
      mockAxiosPatch.mockResolvedValue({ data: updatedUser });

      await resourceClient.update('123', updatedUser);

      expect(mockAxiosPatch).toHaveBeenCalledWith('/api/users/123', {
        data: {
          type: 'users',
          id: '123',
          attributes: { name: 'Alice Updated' },
        },
      });
    });

    it('should return the updated resource (Requirement 10.2)', async () => {
      const updatedUser = { id: '123', name: 'Alice Updated' };
      mockAxiosPatch.mockResolvedValue({ data: updatedUser });

      const result = await resourceClient.update('123', updatedUser);

      expect(result).toEqual(updatedUser);
    });

    it('should throw ValidationError when response is null (Requirement 10.3)', async () => {
      mockAxiosPatch.mockResolvedValue({ data: null });

      await expect(resourceClient.update('123', { id: '123', name: 'Test' })).rejects.toThrow(
        ValidationError
      );
    });

    it('should skip validation when validation is disabled (Requirement 10.4)', async () => {
      const clientNoValidation = new KeltaClient({
        baseUrl: 'https://api.example.com',
        validation: false,
      });
      const resourceNoValidation = clientNoValidation.resource('users');
      const mockPatchFn = clientNoValidation.getAxiosInstance().patch as ReturnType<typeof vi.fn>;

      mockPatchFn.mockResolvedValue({ data: null });

      const result = await resourceNoValidation.update('123', { id: '123', name: 'Test' });
      expect(result).toBeNull();
    });
  });

  describe('patch() - Requirements 4.4, 10.1, 10.2, 10.3', () => {
    it('should send PATCH request to /api/{resourceName}/{id} with JSON:API wrapped partial data (Requirement 4.4)', async () => {
      const partialUpdate = { name: 'Alice Patched' };
      mockAxiosPatch.mockResolvedValue({ data: { id: '123', name: 'Alice Patched' } });

      await resourceClient.patch('123', partialUpdate);

      expect(mockAxiosPatch).toHaveBeenCalledWith('/api/users/123', {
        data: {
          type: 'users',
          id: '123',
          attributes: { name: 'Alice Patched' },
        },
      });
    });

    it('should return the patched resource (Requirement 10.2)', async () => {
      const patchedUser = { id: '123', name: 'Alice Patched' };
      mockAxiosPatch.mockResolvedValue({ data: patchedUser });

      const result = await resourceClient.patch('123', { name: 'Alice Patched' });

      expect(result).toEqual(patchedUser);
    });

    it('should throw ValidationError when response is null (Requirement 10.3)', async () => {
      mockAxiosPatch.mockResolvedValue({ data: null });

      await expect(resourceClient.patch('123', { name: 'Test' })).rejects.toThrow(ValidationError);
    });

    it('should skip validation when validation is disabled (Requirement 10.4)', async () => {
      const clientNoValidation = new KeltaClient({
        baseUrl: 'https://api.example.com',
        validation: false,
      });
      const resourceNoValidation = clientNoValidation.resource('users');
      const mockPatchFn = clientNoValidation.getAxiosInstance().patch as ReturnType<typeof vi.fn>;

      mockPatchFn.mockResolvedValue({ data: null });

      const result = await resourceNoValidation.patch('123', { name: 'Test' });
      expect(result).toBeNull();
    });
  });

  describe('delete() - Requirement 4.5', () => {
    it('should send DELETE request to /api/{resourceName}/{id} (Requirement 4.5)', async () => {
      mockAxiosDelete.mockResolvedValue({ data: undefined });

      await resourceClient.delete('123');

      expect(mockAxiosDelete).toHaveBeenCalledWith('/api/users/123');
    });

    it('should not return any data', async () => {
      mockAxiosDelete.mockResolvedValue({ data: undefined });

      const result = await resourceClient.delete('123');

      expect(result).toBeUndefined();
    });
  });

  describe('query()', () => {
    it('should return a QueryBuilder instance', () => {
      const queryBuilder = resourceClient.query();

      expect(queryBuilder).toBeDefined();
      expect(typeof queryBuilder.paginate).toBe('function');
      expect(typeof queryBuilder.sort).toBe('function');
      expect(typeof queryBuilder.filter).toBe('function');
      expect(typeof queryBuilder.fields).toBe('function');
      expect(typeof queryBuilder.include).toBe('function');
      expect(typeof queryBuilder.execute).toBe('function');
    });
  });

  describe('buildQueryParams() - JSON:API format', () => {
    it('should return empty object when no options provided', () => {
      const params = resourceClient.buildQueryParams();
      expect(params).toEqual({});
    });

    it('should return empty object when empty options provided', () => {
      const params = resourceClient.buildQueryParams({});
      expect(params).toEqual({});
    });

    it('should build all JSON:API query parameters correctly', () => {
      const params = resourceClient.buildQueryParams({
        page: 2,
        size: 25,
        sort: [{ field: 'name', direction: 'asc' }],
        filters: [{ field: 'status', operator: 'eq', value: 'active' }],
        fields: ['id', 'name'],
        include: ['accounts'],
      });

      expect(params).toEqual({
        'page[number]': '2',
        'page[size]': '25',
        sort: 'name',
        'filter[status][eq]': 'active',
        'fields[users]': 'id,name',
        include: 'accounts',
      });
    });

    it('should build sort with - prefix for descending', () => {
      const params = resourceClient.buildQueryParams({
        sort: [
          { field: 'name', direction: 'asc' },
          { field: 'createdAt', direction: 'desc' },
        ],
      });

      expect(params).toEqual({
        sort: 'name,-createdAt',
      });
    });
  });

  describe('buildListUrl() - JSON:API format', () => {
    it('should build URL without query params when no options', () => {
      const url = resourceClient.buildListUrl();
      expect(url).toBe('/api/users');
    });

    it('should build URL with JSON:API query params', () => {
      const url = resourceClient.buildListUrl({ page: 1, size: 10 });
      expect(url).toContain('/api/users?');
      expect(url).toContain('page%5Bnumber%5D=1');
      expect(url).toContain('page%5Bsize%5D=10');
    });
  });
});
