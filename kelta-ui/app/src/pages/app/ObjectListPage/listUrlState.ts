/**
 * Canonical URL state for the end-user list view (`/app/o/<collection>`), extracted from
 * ObjectListPage so other surfaces (dashboard drill-through, saved views) can build and
 * parse the same deep-link format.
 *
 * URL format:
 * - page=2&pageSize=50&sort=-createdAt
 * - filter=[{"id":"f1","field":"status","operator":"equals","value":"active"}]
 */
import type { SortState, FilterCondition, FilterOperator } from '@/hooks/useCollectionRecords'
import { recordRoutes } from '@/utils/recordRoutes'

/**
 * Parse filter conditions from a URL search param.
 * Filters are stored as a JSON-encoded array of FilterCondition objects.
 * Returns an empty array if the param is missing or invalid.
 */
export function parseFilters(filterParam: string | null): FilterCondition[] {
  if (!filterParam) return []
  try {
    const parsed = JSON.parse(filterParam)
    if (Array.isArray(parsed)) {
      return parsed.filter(
        (f: unknown) =>
          f &&
          typeof f === 'object' &&
          'field' in (f as Record<string, unknown>) &&
          'operator' in (f as Record<string, unknown>) &&
          'value' in (f as Record<string, unknown>)
      ) as FilterCondition[]
    }
    return []
  } catch {
    return []
  }
}

/**
 * Parse list view state from URL search params.
 * Returns page, pageSize, sort, and filters with sensible defaults.
 */
/** Parses the comma sort grammar (`a,-b`) into ordered SortState entries. */
export function parseSortParam(sortParam: string | null): SortState[] {
  if (!sortParam) return []
  return sortParam
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) =>
      part.startsWith('-')
        ? { field: part.slice(1), direction: 'desc' as const }
        : { field: part, direction: 'asc' as const }
    )
    .filter((s) => s.field.length > 0)
}

/** Serializes ordered SortState entries back to the comma grammar; undefined when empty. */
export function buildSortParam(sorts: SortState[]): string | undefined {
  if (sorts.length === 0) return undefined
  return sorts.map((s) => (s.direction === 'desc' ? `-${s.field}` : s.field)).join(',')
}

export function parseListViewParams(searchParams: URLSearchParams): {
  page: number
  pageSize: number
  /** First sort level — kept for single-sort consumers. */
  sort: SortState | undefined
  /** Full ordered sort list (server grammar `sort=a,-b` — multi-sort verified server-side). */
  sorts: SortState[]
  filters: FilterCondition[]
  /** Saved-view deep link (`view=<id>`). */
  viewId: string | null
} {
  const pageParam = parseInt(searchParams.get('page') || '1', 10)
  const pageSizeParam = parseInt(searchParams.get('pageSize') || '25', 10)
  const sorts = parseSortParam(searchParams.get('sort'))
  const filterParam = searchParams.get('filter')

  return {
    page: isNaN(pageParam) || pageParam < 1 ? 1 : pageParam,
    pageSize: [10, 25, 50, 100].includes(pageSizeParam) ? pageSizeParam : 25,
    sort: sorts[0],
    sorts,
    filters: parseFilters(filterParam),
    viewId: searchParams.get('view'),
  }
}

/**
 * Simplified filter input for deep links — `id` is generated when omitted.
 */
export interface ListLinkFilter {
  field: string
  operator: FilterOperator
  value: string
  id?: string
}

/**
 * Build a deep link into the end-user list view carrying filter (and optional sort) state
 * in the exact format `parseListViewParams` reads back. The canonical drill-through helper
 * (dashboard chart segments, report rows, saved views).
 */
export function buildListUrl(
  tenantSlug: string,
  collectionName: string,
  filters: ListLinkFilter[],
  sort?: SortState
): string {
  const params = new URLSearchParams()
  if (filters.length > 0) {
    const conditions: FilterCondition[] = filters.map((f, i) => ({
      id: f.id ?? `f${i + 1}`,
      field: f.field,
      operator: f.operator,
      value: f.value,
    }))
    params.set('filter', JSON.stringify(conditions))
  }
  if (sort) {
    params.set('sort', sort.direction === 'desc' ? `-${sort.field}` : sort.field)
  }
  return recordRoutes('enduser', tenantSlug).list(collectionName, params)
}
