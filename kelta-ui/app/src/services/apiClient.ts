/**
 * API Client
 *
 * Provides a thin wrapper around the SDK's Axios instance that preserves
 * the same get/post/put/patch/delete interface used throughout the UI.
 *
 * All HTTP calls are routed through the SDK's KeltaClient Axios instance,
 * which provides: auth token injection, retry with exponential backoff,
 * and consistent error handling.
 *
 * For `/api/` routes (served by the DynamicCollectionRouter), the server
 * returns JSON:API format. Two usage patterns are supported:
 *
 * 1. Existing hooks (useResources, useRecord, etc.) call `get()` and
 *    handle JSON:API unwrapping themselves via utils/jsonapi.ts.
 *
 * 2. Migrated pages use `getList()` / `getOne()` which auto-unwrap
 *    JSON:API responses into flat objects, and `postResource()` /
 *    `putResource()` / `patchResource()` which auto-wrap request bodies.
 *
 * Requirements:
 * - 2.7: Include access token in all API requests (via SDK TokenProvider)
 * - 2.8: Trigger token refresh on 401 responses (via SDK interceptor)
 */

import type { AxiosInstance } from 'axios'
import axios from 'axios'

/**
 * Paginated response compatible with JSON:API metadata format.
 * Maps JSON:API pagination metadata to a flat page structure.
 */
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

/**
 * Structured field-level error from the API
 */
export interface ApiFieldError {
  field: string
  message: string
  code?: string
}

/**
 * Custom API error that preserves the HTTP status code,
 * server message, and field-level validation errors.
 */
export class ApiError extends Error {
  public readonly status: number
  public readonly serverMessage: string
  public readonly fieldErrors: ApiFieldError[]

  constructor(status: number, serverMessage: string, fieldErrors: ApiFieldError[] = []) {
    // Build a user-friendly message
    const fieldMessages = fieldErrors.map((e) => e.message).filter(Boolean)
    const displayMessage =
      fieldMessages.length > 0 ? fieldMessages.join('; ') : serverMessage || 'Request failed'
    super(displayMessage)
    this.name = 'ApiError'
    this.status = status
    this.serverMessage = serverMessage
    this.fieldErrors = fieldErrors
  }
}

/** JSON:API error code the worker returns (as a 409) when a write's `If-Match` is stale. */
export const STALE_WRITE_CODE = 'STALE_WRITE'

/**
 * Whether an error is an optimistic-locking conflict: the record changed since its ETag was read
 * (worker `409 STALE_WRITE`, unified record slice 5). Other 409s that carry their own code —
 * `UNIQUE_VIOLATION`, `REFERENCED_RECORD`, a hook conflict — are not, so callers keep showing
 * those as ordinary save errors.
 */
export function isStaleWriteError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const { status, fieldErrors } = error as { status?: unknown; fieldErrors?: ApiFieldError[] }
  if (status !== 409) return false
  const codes = (fieldErrors ?? []).map((e) => e.code).filter(Boolean)
  return codes.length === 0 || codes.includes(STALE_WRITE_CODE)
}

/** A client-side `409 STALE_WRITE`, for a conflict detected before the write is sent. */
export function staleWriteError(message: string): ApiError {
  return new ApiError(409, message, [{ field: '', message, code: STALE_WRITE_CODE }])
}

/**
 * Parse an Axios error into an ApiError with structured field-level details.
 *
 * Supports two error response formats:
 * 1. JSON:API format: { errors: [{ status, code, title, detail, source, meta }] }
 * 2. Legacy format: { message, error, errors: [{ field, message, code }] }
 */
export function parseAxiosError(error: unknown): ApiError {
  // Already parsed (the ApiClient methods throw ApiError): keep it, field errors included.
  // Rebuilding it here dropped `fieldErrors`, so forms lost every per-field server message.
  if (error instanceof ApiError) {
    return error
  }
  if (axios.isAxiosError(error) && error.response) {
    const { status, data } = error.response
    let serverMessage = error.response.statusText || 'Request failed'
    let fieldErrors: ApiFieldError[] = []

    if (data && typeof data === 'object') {
      const body = data as Record<string, unknown>

      // Check for JSON:API error format: { errors: [{ detail, source, code }] }
      if (Array.isArray(body.errors) && body.errors.length > 0) {
        const firstError = body.errors[0] as Record<string, unknown> | undefined
        // Detect JSON:API format by checking for 'detail' field (JSON:API uses 'detail', legacy uses 'message')
        if (firstError && typeof firstError.detail === 'string') {
          // JSON:API format
          serverMessage = firstError.detail as string
          fieldErrors = (body.errors as unknown[])
            .filter(
              (e: unknown): e is Record<string, unknown> =>
                typeof e === 'object' &&
                e !== null &&
                typeof (e as Record<string, unknown>).detail === 'string'
            )
            .map((e) => {
              const source = e.source as Record<string, unknown> | undefined
              let field = ''
              if (source && typeof source.pointer === 'string') {
                // Strip /data/attributes/ prefix to get field name
                field = (source.pointer as string).replace(/^\/data\/attributes\//, '')
              }
              return {
                field,
                message: e.detail as string,
                code: typeof e.code === 'string' ? (e.code as string) : undefined,
              }
            })
        } else {
          // Legacy format: errors array with { field, message, code }
          fieldErrors = (body.errors as unknown[])
            .filter(
              (e: unknown): e is { field: string; message: string; code?: string } =>
                typeof e === 'object' &&
                e !== null &&
                typeof (e as ApiFieldError).message === 'string'
            )
            .map((e) => ({
              field: e.field,
              message: e.message,
              code: e.code,
            }))
        }
      }

      // Use top-level message/error as serverMessage fallback
      if (typeof body.message === 'string') {
        serverMessage = body.message
      } else if (typeof body.error === 'string') {
        serverMessage = body.error
      } else if (fieldErrors.length > 0 && !body.message && !body.error) {
        // For JSON:API errors without top-level message, use first error's detail
        serverMessage = fieldErrors[0].message
      }
    }

    return new ApiError(status, serverMessage, fieldErrors)
  }

  // Network or other errors
  const message = error instanceof Error ? error.message : 'Request failed'
  return new ApiError(0, message)
}

// ---------------------------------------------------------------------------
// JSON:API helpers (inline to avoid cross-package dependency)
// ---------------------------------------------------------------------------

interface JsonApiResourceObject {
  type: string
  id: string
  attributes?: Record<string, unknown>
  relationships?: Record<string, { data: { type: string; id: string } | null }>
}

/**
 * Unwrap a PostgreSQL JSONB column value.
 *
 * The backend may serialize JSONB columns as wrapper objects:
 *   { type: "jsonb", value: "<json-string>", null: false }
 * This helper extracts the inner `value` string so the rest of the
 * app sees a plain JSON string (or null) instead of the wrapper.
 */
function unwrapJsonbValue(val: unknown): unknown {
  if (val && typeof val === 'object' && !Array.isArray(val)) {
    const obj = val as Record<string, unknown>
    if (obj.type === 'jsonb' && 'value' in obj) {
      return obj.null === true ? null : obj.value
    }
  }
  return val
}

/**
 * Flatten a JSON:API resource object into a plain object.
 * Merges id + attributes + relationship IDs.
 * Unwraps JSONB wrapper objects in attribute values.
 */
function flattenResource(obj: unknown): Record<string, unknown> {
  const resource = obj as JsonApiResourceObject
  const attrs = resource.attributes || {}
  const result: Record<string, unknown> = { id: resource.id }
  for (const [key, val] of Object.entries(attrs)) {
    result[key] = unwrapJsonbValue(val)
  }
  if (resource.relationships) {
    for (const [key, rel] of Object.entries(resource.relationships)) {
      result[key] = rel?.data?.id ?? null
    }
  }
  return result
}

/**
 * Unwrap a JSON:API list response body into a flat array.
 *
 * Input:  { data: [{ type, id, attributes }], metadata }
 * Output: [{ id, name, ... }]
 */
function unwrapJsonApiList<T>(body: unknown): T[] {
  if (!body || typeof body !== 'object') return []
  const obj = body as Record<string, unknown>
  if (Array.isArray(obj.data)) {
    return obj.data.map(flattenResource) as T[]
  }
  // Already a flat array
  if (Array.isArray(body)) return body as T[]
  return []
}

/**
 * Unwrap a JSON:API single-resource response into a flat object.
 *
 * Input:  { data: { type, id, attributes } }
 * Output: { id, name, ... }
 */
function unwrapJsonApiSingle<T>(body: unknown): T {
  if (!body || typeof body !== 'object') return body as T
  const obj = body as Record<string, unknown>
  if (obj.data && typeof obj.data === 'object' && 'type' in (obj.data as object)) {
    return flattenResource(obj.data) as T
  }
  // Already flat
  return body as T
}

/**
 * Extract the collection type from a `/api/...` URL.
 *
 * Examples:
 *   /api/scripts          → 'scripts'
 *   /api/scripts/123      → 'scripts'
 *   /api/scripts/123/logs → 'logs'
 */
function extractResourceType(url: string): string {
  const path = url.split('?')[0] // strip query string
  const parts = path.replace(/^\/api\//, '').split('/')
  // For sub-resources /api/{parent}/{parentId}/{child}, the child is parts[2]
  if (parts.length >= 3) {
    return parts[2]
  }
  return parts[0]
}

/**
 * Extract the record ID from a URL like `/api/scripts/123`.
 * Returns undefined if the URL doesn't contain an ID segment.
 */
function extractIdFromUrl(url: string): string | undefined {
  const path = url.split('?')[0]
  const parts = path.replace(/^\/api\//, '').split('/')
  // /api/{type}/{id} → id is parts[1]
  if (parts.length >= 2) {
    return parts[1]
  }
  return undefined
}

/**
 * Wrap a plain object into a JSON:API request body.
 */
function wrapJsonApiBody(
  type: string,
  data: Record<string, unknown>,
  id?: string
): { data: { type: string; id?: string; attributes: Record<string, unknown> } } {
  const attributes = { ...data }
  // Remove system fields from attributes
  delete attributes.id
  delete attributes.createdAt
  delete attributes.updatedAt

  const envelope: { type: string; id?: string; attributes: Record<string, unknown> } = {
    type,
    attributes,
  }
  if (id) {
    envelope.id = id
  }
  return { data: envelope }
}

/**
 * Check if a request body is already wrapped in JSON:API format.
 * Returns true if the body has { data: { type: ..., attributes: ... } }.
 */
function isAlreadyWrapped(data: unknown): boolean {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return false
  const obj = data as Record<string, unknown>
  if (!obj.data || typeof obj.data !== 'object' || Array.isArray(obj.data)) return false
  const inner = obj.data as Record<string, unknown>
  return typeof inner.type === 'string'
}

/**
 * Read a Blob's contents as text. Prefers the standard `Blob.text()` and
 * falls back to FileReader for environments that lack it (e.g. jsdom).
 */
function readBlobText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text()
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

/**
 * Axios-backed API client that wraps the SDK's Axios instance.
 *
 * Preserves the same interface (get, post, put, patch, delete) used by
 * all existing UI components, while routing all requests through the
 * SDK's Axios pipeline (auth, retry, interceptors).
 *
 * The base methods (get, post, put, patch, delete) do NOT perform any
 * JSON:API wrapping/unwrapping — they return raw response.data. This
 * ensures compatibility with existing hooks that handle JSON:API themselves.
 *
 * The convenience methods (getList, getOne, getPage, postResource,
 * putResource, patchResource, deleteResource) DO perform JSON:API
 * wrapping/unwrapping for /api/ routes.
 */
export class ApiClient {
  private axios: AxiosInstance

  constructor(axiosInstance: AxiosInstance) {
    this.axios = axiosInstance
  }

  // ---------------------------------------------------------------------------
  // Low-level fetch — provides a window.fetch-like interface backed by Axios.
  // Callers that need `.ok`, `.status`, `.json()` should use this method.
  // ---------------------------------------------------------------------------

  /**
   * Fetch-like wrapper around Axios.
   * Returns a response object with `ok`, `status`, `statusText`, `json()`, and `text()`.
   */
  async fetch(
    url: string,
    init?: { method?: string; headers?: Record<string, string>; body?: string }
  ): Promise<{
    ok: boolean
    status: number
    statusText: string
    json: () => Promise<unknown>
    text: () => Promise<string>
  }> {
    try {
      const method = (init?.method ?? 'GET').toLowerCase()
      const data = init?.body ? JSON.parse(init.body) : undefined
      const headers = init?.headers

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const response = await (this.axios as any).request({
        url,
        method,
        data,
        headers,
        // Don't throw on non-2xx — we want to handle status codes in the caller
        validateStatus: () => true,
      })

      const status: number = response.status
      const statusText: string = response.statusText || ''
      const responseData = response.data

      return {
        ok: status >= 200 && status < 300,
        status,
        statusText,
        json: async () => responseData,
        text: async () =>
          typeof responseData === 'string' ? responseData : JSON.stringify(responseData),
      }
    } catch (error) {
      // Network errors or request setup errors — return a failed response
      const message = error instanceof Error ? error.message : 'Network error'
      return {
        ok: false,
        status: 0,
        statusText: message,
        json: async () => ({}),
        text: async () => message,
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Base methods — raw response.data, no JSON:API handling
  // ---------------------------------------------------------------------------

  /**
   * GET request. Returns raw response.data.
   */
  async get<T = unknown>(url: string): Promise<T> {
    try {
      const response = await this.axios.get(url)
      return response.data
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * GET request for binary content (e.g. file downloads).
   * Returns the raw response body as a Blob.
   *
   * With `responseType: 'blob'` error bodies also arrive as Blobs, so this
   * decodes JSON error responses back into objects before parsing — callers
   * still get a structured ApiError carrying the server's detail message.
   */
  async getBlob(url: string): Promise<Blob> {
    try {
      const response = await this.axios.get(url, { responseType: 'blob' })
      return response.data as Blob
    } catch (error) {
      if (axios.isAxiosError(error) && error.response && error.response.data instanceof Blob) {
        try {
          error.response.data = JSON.parse(await readBlobText(error.response.data))
        } catch {
          error.response.data = undefined
        }
      }
      throw parseAxiosError(error)
    }
  }

  /**
   * POST request. Returns raw response.data.
   */
  async post<T = unknown>(url: string, data?: unknown): Promise<T> {
    try {
      const response = await this.axios.post(url, data)
      return response.data
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * POST request with multipart form data (e.g. file uploads)
   */
  async postFormData<T = unknown>(url: string, formData: FormData): Promise<T> {
    try {
      const response = await this.axios.post<T>(url, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      return response.data
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * PUT request. Returns raw response.data.
   */
  async put<T = unknown>(url: string, data?: unknown, ifMatch?: string): Promise<T> {
    try {
      const response = await this.axios.put(
        url,
        data,
        ifMatch ? { headers: { 'If-Match': ifMatch } } : undefined
      )
      return response.data
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * PATCH request. Returns raw response.data. Pass `ifMatch` for optimistic locking.
   */
  async patch<T = unknown>(url: string, data?: unknown, ifMatch?: string): Promise<T> {
    try {
      const response = await this.axios.patch(
        url,
        data,
        ifMatch ? { headers: { 'If-Match': ifMatch } } : undefined
      )
      return response.data
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * DELETE request. Returns raw response.data. Pass `ifMatch` for optimistic locking.
   */
  async delete<T = unknown>(url: string, ifMatch?: string): Promise<T> {
    try {
      const response = await this.axios.delete<T>(
        url,
        ifMatch ? { headers: { 'If-Match': ifMatch } } : undefined
      )
      return response.data
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  // ---------------------------------------------------------------------------
  // JSON:API convenience methods — auto-wrap/unwrap for /api/ routes
  // ---------------------------------------------------------------------------

  /**
   * GET a list of resources from a JSON:API endpoint.
   * Unwraps `{ data: [{ type, id, attributes }] }` → `T[]`
   */
  async getList<T = unknown>(url: string): Promise<T[]> {
    try {
      const response = await this.axios.get(url)
      return unwrapJsonApiList<T>(response.data)
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * GET a single resource from a JSON:API endpoint.
   * Unwraps `{ data: { type, id, attributes } }` → `T`
   */
  async getOne<T = unknown>(url: string): Promise<T> {
    try {
      const response = await this.axios.get(url)
      return unwrapJsonApiSingle<T>(response.data)
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * GET returning the raw response body plus the `ETag` header, for optimistic
   * locking (unified record slice 5). The caller echoes `etag` back as `If-Match`
   * on the next write; the token is opaque.
   */
  async getWithMeta<T = unknown>(url: string): Promise<{ data: T; etag?: string }> {
    try {
      const response = await this.axios.get(url)
      const etag = (response.headers?.etag ?? response.headers?.ETag) as string | undefined
      return { data: response.data as T, etag }
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * GET a paginated list of resources from a JSON:API endpoint.
   * Unwraps JSON:API list format and maps metadata to PageResponse<T>.
   */
  async getPage<T = unknown>(url: string): Promise<PageResponse<T>> {
    try {
      const response = await this.axios.get(url)
      const items = unwrapJsonApiList<T>(response.data)
      const meta = (response.data as Record<string, unknown>)?.metadata as
        | Record<string, number>
        | undefined
      return {
        content: items,
        totalElements: meta?.totalCount ?? items.length,
        totalPages: meta?.totalPages ?? 1,
        size: meta?.pageSize ?? items.length,
        number: meta?.currentPage ?? 0,
      }
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * DELETE a resource from a JSON:API endpoint.
   * Returns void (most deletes return 204 No Content).
   */
  async deleteResource(url: string, ifMatch?: string): Promise<void> {
    try {
      await this.axios.delete(url, ifMatch ? { headers: { 'If-Match': ifMatch } } : undefined)
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * POST a new resource to a JSON:API endpoint.
   * Auto-wraps plain objects in JSON:API format and unwraps response.
   * Skips wrapping if body is already in JSON:API format.
   */
  async postResource<T = unknown>(url: string, data?: unknown): Promise<T> {
    try {
      let body = data
      if (data && typeof data === 'object' && !Array.isArray(data) && !isAlreadyWrapped(data)) {
        const type = extractResourceType(url)
        body = wrapJsonApiBody(type, data as Record<string, unknown>)
      }
      const response = await this.axios.post(url, body)
      return unwrapJsonApiSingle<T>(response.data)
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * PUT (full update) a resource at a JSON:API endpoint.
   * Auto-wraps plain objects in JSON:API format and unwraps response.
   * Skips wrapping if body is already in JSON:API format.
   */
  async putResource<T = unknown>(url: string, data?: unknown, ifMatch?: string): Promise<T> {
    try {
      let body = data
      if (data && typeof data === 'object' && !Array.isArray(data) && !isAlreadyWrapped(data)) {
        const type = extractResourceType(url)
        const id = extractIdFromUrl(url)
        body = wrapJsonApiBody(type, data as Record<string, unknown>, id)
      }
      const response = await this.axios.put(
        url,
        body,
        ifMatch ? { headers: { 'If-Match': ifMatch } } : undefined
      )
      return unwrapJsonApiSingle<T>(response.data)
    } catch (error) {
      throw parseAxiosError(error)
    }
  }

  /**
   * PATCH (partial update) a resource at a JSON:API endpoint.
   * Auto-wraps plain objects in JSON:API format and unwraps response.
   * Skips wrapping if body is already in JSON:API format.
   */
  async patchResource<T = unknown>(url: string, data?: unknown, ifMatch?: string): Promise<T> {
    try {
      let body = data
      if (data && typeof data === 'object' && !Array.isArray(data) && !isAlreadyWrapped(data)) {
        const type = extractResourceType(url)
        const id = extractIdFromUrl(url)
        body = wrapJsonApiBody(type, data as Record<string, unknown>, id)
      }
      const response = await this.axios.patch(
        url,
        body,
        ifMatch ? { headers: { 'If-Match': ifMatch } } : undefined
      )
      return unwrapJsonApiSingle<T>(response.data)
    } catch (error) {
      throw parseAxiosError(error)
    }
  }
}
