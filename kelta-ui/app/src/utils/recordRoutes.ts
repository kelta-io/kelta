/**
 * Record URLs for both record stacks (record-stack convergence P7).
 *
 * The record pages exist twice — the admin Resource Browser under `/:tenant/resources/...`
 * and the end-user app under `/:tenant/app/o/...` — and render the same shared record
 * components (`RecordShell`, `RecordForm`, `ListShell`, `RelatedList`, `FieldRenderer`, …).
 * A link those components build must stay inside the stack they are rendered in, so record
 * URLs come from here and are never hand-built:
 *
 * - `recordRoutes(variant, tenantSlug)` builds them for a known stack;
 * - `useRecordRoutes(tenantSlug)` (`context/RecordStackContext`) builds them for the stack
 *   the calling component is rendered in;
 * - `parseRecordPath(pathname)` reads the stack + record back out of a URL (global keyboard
 *   shortcuts, which live outside the record pages).
 *
 * Collection names and record ids are path segments: each is `encodeURIComponent`-encoded
 * (a no-op for valid collection names and UUIDs, so the URLs are unchanged from the
 * hand-built ones they replace). A query is appended verbatim after `?`.
 */

/** Which record stack a URL belongs to. */
export type RecordStackVariant = 'admin' | 'enduser'

/**
 * A query for a list / new URL: a pre-built query string (with or without the leading `?`),
 * `URLSearchParams`, or a plain key → value map. Empty queries add nothing.
 */
export type RecordRouteQuery = string | URLSearchParams | Record<string, string>

// Members are function-typed properties, not method signatures: `new(...)` in an interface
// would declare a construct signature.
export interface RecordRoutes {
  /** The stack these routes point into. */
  readonly variant: RecordStackVariant
  /** A collection's record list, optionally with list state (`filter`, `sort`, `view`, …). */
  list: (collection: string, query?: RecordRouteQuery) => string
  /** The create form; `query` carries create-mode prefill (e.g. a parent FK from a related list). */
  new: (collection: string, query?: RecordRouteQuery) => string
  /** A record's detail page. */
  detail: (collection: string, id: string) => string
  /** A record's edit form. */
  edit: (collection: string, id: string) => string
  /** The create form seeded from an existing record (`?clone=<id>`; create mode only). */
  clone: (collection: string, id: string) => string
}

/** Tenant-relative prefix of each stack's collection pages. */
const STACK_PREFIX: Record<RecordStackVariant, string> = {
  admin: 'resources',
  enduser: 'app/o',
}

function toQueryString(query: RecordRouteQuery | undefined): string {
  if (query === undefined) return ''
  if (typeof query === 'string') return query.startsWith('?') ? query.slice(1) : query
  if (query instanceof URLSearchParams) return query.toString()
  return new URLSearchParams(query).toString()
}

function withQuery(path: string, query: RecordRouteQuery | undefined): string {
  const qs = toQueryString(query)
  return qs ? `${path}?${qs}` : path
}

/**
 * Build the record URLs of one stack for one tenant. An empty `tenantSlug` builds root-relative
 * URLs (`/resources/…`, `/app/o/…`) rather than a `//` path.
 */
export function recordRoutes(variant: RecordStackVariant, tenantSlug: string): RecordRoutes {
  // No slug (a custom domain mounts the app at the root) → no slug segment, never `//…`.
  const tenantPrefix = tenantSlug ? `/${tenantSlug}` : ''
  const collectionPath = (collection: string) =>
    `${tenantPrefix}/${STACK_PREFIX[variant]}/${encodeURIComponent(collection)}`
  const recordPath = (collection: string, id: string) =>
    `${collectionPath(collection)}/${encodeURIComponent(id)}`

  return {
    variant,
    list: (collection, query) => withQuery(collectionPath(collection), query),
    new: (collection, query) => withQuery(`${collectionPath(collection)}/new`, query),
    detail: (collection, id) => recordPath(collection, id),
    edit: (collection, id) => `${recordPath(collection, id)}/edit`,
    clone: (collection, id) => withQuery(`${collectionPath(collection)}/new`, { clone: id }),
  }
}

/** Which record page a URL is. */
export type RecordPageKind = 'list' | 'new' | 'detail' | 'edit'

/** A record page URL read back into its parts (see {@link parseRecordPath}). */
export interface ParsedRecordPath {
  variant: RecordStackVariant
  tenantSlug: string
  kind: RecordPageKind
  collection: string
  /** The record id on a detail / edit page. */
  id?: string
}

const ADMIN_PATH = /^\/([^/]+)\/resources\/([^/]+)(?:\/([^/]+)(?:\/(edit))?)?\/?$/
const ENDUSER_PATH = /^\/([^/]+)\/app\/o\/([^/]+)(?:\/([^/]+)(?:\/(edit))?)?\/?$/

/**
 * Read a record page out of a pathname (`location.pathname`, no query string):
 * `/:tenant/resources/:collection[/new | /:id[/edit]]` or the same under `/:tenant/app/o/`.
 * Returns null for any other path. Segments are decoded.
 */
export function parseRecordPath(pathname: string): ParsedRecordPath | null {
  const admin = ADMIN_PATH.exec(pathname)
  const match = admin ?? ENDUSER_PATH.exec(pathname)
  if (!match) return null
  const [, tenantSlug, rawCollection, rawId, edit] = match
  const variant: RecordStackVariant = admin ? 'admin' : 'enduser'
  const collection = safeDecode(rawCollection)
  if (rawId === undefined) return { variant, tenantSlug, kind: 'list', collection }
  if (rawId === 'new') {
    return edit ? null : { variant, tenantSlug, kind: 'new', collection }
  }
  return { variant, tenantSlug, kind: edit ? 'edit' : 'detail', collection, id: safeDecode(rawId) }
}

function safeDecode(segment: string): string {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}
