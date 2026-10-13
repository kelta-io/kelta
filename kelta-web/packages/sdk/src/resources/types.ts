/**
 * Resource operation types
 */

/**
 * Sort criteria for list operations
 */
export interface SortCriteria {
  field: string;
  direction: 'asc' | 'desc';
}

/**
 * Filter expression for list operations
 */
export interface FilterExpression {
  field: string;
  operator:
    | 'eq'
    | 'neq'
    | 'gt'
    | 'gte'
    | 'lt'
    | 'lte'
    | 'isnull'
    | 'contains'
    | 'starts'
    | 'ends'
    | 'icontains'
    | 'istarts'
    | 'iends'
    | 'ieq'
    | 'in';
  value: unknown;
}

/**
 * Options for list operations
 */
export interface ListOptions {
  /**
   * Page number (1-indexed)
   */
  page?: number;

  /**
   * Number of items per page
   */
  size?: number;

  /**
   * Sort criteria
   */
  sort?: SortCriteria[];

  /**
   * Filter expressions
   */
  filters?: FilterExpression[];

  /**
   * Fields to include in the response (sparse fieldsets)
   */
  fields?: string[];

  /**
   * Related resources to include via JSON:API ?include= parameter
   */
  include?: string[];
}

/**
 * Options for get operations
 */
export interface GetOptions {
  /**
   * Related resources to include via JSON:API ?include= parameter
   */
  include?: string[];

  /**
   * Sparse fieldset: attribute/relationship names to return, sent as fields[<resourceName>]
   */
  fields?: string[];
}

/**
 * Pagination metadata
 */
export interface PaginationMeta {
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

/**
 * Response from list operations
 */
export interface ListResponse<T> {
  data: T[];
  pagination: PaginationMeta;
}
