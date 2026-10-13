import type { KeltaClient } from '../client/KeltaClient';
import type { GetOptions, ListOptions, ListResponse } from './types';
import { QueryBuilder } from '../query/QueryBuilder';
import { ListResponseSchema } from '../validation/schemas';
import { ValidationError } from '../errors';
import { z } from 'zod';

/**
 * Client for performing CRUD operations on a specific resource
 */
export class ResourceClient<T = unknown> {
  constructor(
    private readonly client: KeltaClient,
    private readonly resourceName: string
  ) {}

  /**
   * Get the resource name
   */
  getName(): string {
    return this.resourceName;
  }

  /**
   * List resources with optional pagination, sorting, and filtering
   */
  async list(options?: ListOptions): Promise<ListResponse<T>> {
    const params = this.buildQueryParams(options);
    const response = await this.client
      .getAxiosInstance()
      .get<ListResponse<T>>(`/api/${this.resourceName}`, { params });

    // Validate response if validation is enabled
    if (this.client.isValidationEnabled()) {
      const listSchema = ListResponseSchema(z.unknown());
      const parseResult = listSchema.safeParse(response.data);
      if (!parseResult.success) {
        const errorMessages = parseResult.error.errors.map(
          (e) => `${e.path.join('.')}: ${e.message}`
        );
        throw new ValidationError(
          `Invalid list response for ${this.resourceName}: ${errorMessages.join(', ')}`,
          { schema: errorMessages }
        );
      }
    }

    return response.data;
  }

  /**
   * Get a single resource by ID, with optional include for related resources
   */
  async get(id: string, options?: GetOptions): Promise<T> {
    const params: Record<string, string> = {};
    if (options?.include && options.include.length > 0) {
      params.include = options.include.join(',');
    }
    if (options?.fields && options.fields.length > 0) {
      params[`fields[${this.resourceName}]`] = options.fields.join(',');
    }
    const response = await this.client
      .getAxiosInstance()
      .get<T>(
        `/api/${this.resourceName}/${id}`,
        Object.keys(params).length > 0 ? { params } : undefined
      );

    // Validate response if validation is enabled
    if (this.client.isValidationEnabled()) {
      // For single resource responses, we validate that it's an object (not null/undefined)
      if (response.data === null || response.data === undefined) {
        throw new ValidationError(
          `Invalid get response for ${this.resourceName}/${id}: expected object, got ${String(response.data)}`,
          { schema: ['Response must be an object'] }
        );
      }
    }

    return response.data;
  }

  /**
   * Create a new resource (JSON:API format)
   */
  async create(data: Partial<T>): Promise<T> {
    const body = this.wrapJsonApi(undefined, data as Record<string, unknown>);
    const response = await this.client
      .getAxiosInstance()
      .post<T>(`/api/${this.resourceName}`, body);

    // Validate response if validation is enabled
    if (this.client.isValidationEnabled()) {
      if (response.data === null || response.data === undefined) {
        throw new ValidationError(
          `Invalid create response for ${this.resourceName}: expected object, got ${String(response.data)}`,
          { schema: ['Response must be an object'] }
        );
      }
    }

    return response.data;
  }

  /**
   * Update a resource (full replacement via PATCH with JSON:API format)
   */
  async update(id: string, data: T): Promise<T> {
    const body = this.wrapJsonApi(id, data as Record<string, unknown>);
    const response = await this.client
      .getAxiosInstance()
      .patch<T>(`/api/${this.resourceName}/${id}`, body);

    // Validate response if validation is enabled
    if (this.client.isValidationEnabled()) {
      if (response.data === null || response.data === undefined) {
        throw new ValidationError(
          `Invalid update response for ${this.resourceName}/${id}: expected object, got ${String(response.data)}`,
          { schema: ['Response must be an object'] }
        );
      }
    }

    return response.data;
  }

  /**
   * Patch a resource (partial update with JSON:API format)
   */
  async patch(id: string, data: Partial<T>): Promise<T> {
    const body = this.wrapJsonApi(id, data as Record<string, unknown>);
    const response = await this.client
      .getAxiosInstance()
      .patch<T>(`/api/${this.resourceName}/${id}`, body);

    // Validate response if validation is enabled
    if (this.client.isValidationEnabled()) {
      if (response.data === null || response.data === undefined) {
        throw new ValidationError(
          `Invalid patch response for ${this.resourceName}/${id}: expected object, got ${String(response.data)}`,
          { schema: ['Response must be an object'] }
        );
      }
    }

    return response.data;
  }

  /**
   * Delete a resource
   */
  async delete(id: string): Promise<void> {
    await this.client.getAxiosInstance().delete(`/api/${this.resourceName}/${id}`);
  }

  /**
   * List child resources under a parent record (sub-resource query).
   *
   * Calls: GET /api/{parent}/{parentId}/{child}
   *
   * @param parentId the parent record ID
   * @param childResource the child collection name
   * @param options optional pagination, sorting, and filtering options
   * @returns paginated list of child resources
   */
  async listChildren<C = unknown>(
    parentId: string,
    childResource: string,
    options?: ListOptions
  ): Promise<ListResponse<C>> {
    const params = this.buildQueryParams(options);
    const response = await this.client
      .getAxiosInstance()
      .get<ListResponse<C>>(`/api/${this.resourceName}/${parentId}/${childResource}`, { params });
    return response.data;
  }

  /**
   * Get a specific child resource under a parent record.
   *
   * Calls: GET /api/{parent}/{parentId}/{child}/{childId}
   *
   * @param parentId the parent record ID
   * @param childResource the child collection name
   * @param childId the child record ID
   * @returns the child resource
   */
  async getChild<C = unknown>(
    parentId: string,
    childResource: string,
    childId: string
  ): Promise<C> {
    const response = await this.client
      .getAxiosInstance()
      .get<C>(`/api/${this.resourceName}/${parentId}/${childResource}/${childId}`);
    return response.data;
  }

  /**
   * Create a query builder for fluent query construction
   */
  query(): QueryBuilder<T> {
    return new QueryBuilder<T>(this);
  }

  /**
   * Wrap data in JSON:API request format.
   *
   * Produces: { data: { type, id?, attributes: { ... } } }
   */
  private wrapJsonApi(
    id: string | undefined,
    data: Record<string, unknown>
  ): { data: { type: string; id?: string; attributes: Record<string, unknown> } } {
    const attributes = { ...data };

    // Remove system fields from attributes
    delete attributes.id;
    delete attributes.createdAt;
    delete attributes.updatedAt;

    const result: { type: string; id?: string; attributes: Record<string, unknown> } = {
      type: this.resourceName,
      attributes,
    };

    if (id) {
      result.id = id;
    }

    return { data: result };
  }

  /**
   * Build JSON:API compliant query parameters from list options.
   *
   * Pagination: page[number]=N, page[size]=N
   * Sorting:    sort=-field1,field2  (- prefix = descending)
   * Filters:    filter[field][op]=value
   * Fields:     fields=field1,field2
   */
  buildQueryParams(options?: ListOptions): Record<string, string | string[]> {
    const params: Record<string, string | string[]> = {};

    if (!options) {
      return params;
    }

    if (options.page !== undefined) {
      params['page[number]'] = String(options.page);
    }

    if (options.size !== undefined) {
      params['page[size]'] = String(options.size);
    }

    if (options.sort && options.sort.length > 0) {
      const sortValue = options.sort
        .map((s) => (s.direction === 'desc' ? `-${s.field}` : s.field))
        .join(',');
      params.sort = sortValue;
    }

    if (options.filters && options.filters.length > 0) {
      options.filters.forEach((filter) => {
        params[`filter[${filter.field}][${filter.operator}]`] = String(filter.value);
      });
    }

    if (options.fields && options.fields.length > 0) {
      params[`fields[${this.resourceName}]`] = options.fields.join(',');
    }

    if (options.include && options.include.length > 0) {
      params.include = options.include.join(',');
    }

    return params;
  }

  /**
   * Build the list URL with query parameters (for testing)
   */
  buildListUrl(options?: ListOptions): string {
    const params = this.buildQueryParams(options);
    const queryString = Object.entries(params)
      .map(([key, value]) => {
        if (Array.isArray(value)) {
          return value.map((v) => `${encodeURIComponent(key)}=${encodeURIComponent(v)}`).join('&');
        }
        return `${encodeURIComponent(key)}=${encodeURIComponent(value)}`;
      })
      .join('&');

    const baseUrl = `/api/${this.resourceName}`;
    return queryString ? `${baseUrl}?${queryString}` : baseUrl;
  }
}
