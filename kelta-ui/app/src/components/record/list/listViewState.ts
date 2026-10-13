/**
 * Pure helpers behind the record list core (`RecordList` / `useRecordListModel`): which
 * columns a list shows, which field an alternate view binds to, and how the view state
 * reshapes the server query (grouping sort, calendar month range) and the sort URL param.
 *
 * Moved out of `ObjectListPage` (record-stack convergence P9) so both list stacks resolve
 * them the same way, and so they are unit-testable without rendering a page.
 */
import type { FilterCondition, SortState } from '@/hooks/useCollectionRecords'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { SavedView, SavedViewDensity } from '@/hooks/useSavedViews'
import { getFieldControl } from '@/components/fieldControl'
import { monthRange } from '@/components/CalendarMonthView'
import { buildSortParam } from './listUrlState'
import { orderFieldsByView } from './listViewMapping'

/** Audit fields every collection carries; never offered as list columns or mass-edit targets. */
const SYSTEM_AUDIT_FIELDS: readonly string[] = ['createdAt', 'updatedAt', 'createdBy', 'updatedBy']

/**
 * The record id as a choosable list column (`RecordListFeatures.idColumn`). Not a schema field:
 * it is offered first in the column chooser (so the default columns start with it) and typed as
 * a server-computed value, so it renders monospace and is never inline-edited.
 */
export const RECORD_ID_COLUMN: FieldDefinition = {
  id: '__record-id',
  name: 'id',
  displayName: 'ID',
  type: 'auto_number',
  required: false,
}

/** Columns shown when neither the column chooser nor the active view names any. */
const DEFAULT_VISIBLE_COLUMN_COUNT = 6

/** The page size the list URL omits (`listUrlState` reads a missing `pageSize` as this). */
export const DEFAULT_PAGE_SIZE = 25

/** Rows a calendar fetches for its month — the HTTP page-size clamp. */
export const CALENDAR_FETCH_LIMIT = 200

/** Minimal field shape the helpers need. */
interface ListField {
  name: string
  type: string
}

type TypeConfig = SavedView['typeConfig'] | null | undefined

/** Non-audit fields the viewer may read (FLS) — the column chooser and group-by picker offer these. */
export function accessibleListFields<F extends ListField>(
  fields: F[],
  isFieldVisible: (fieldName: string) => boolean
): F[] {
  return fields.filter((f) => !SYSTEM_AUDIT_FIELDS.includes(f.name) && isFieldVisible(f.name))
}

/**
 * Table columns. Precedence: the column chooser's override > the active view's column list >
 * the first {@link DEFAULT_VISIBLE_COLUMN_COUNT} accessible fields. Names that no longer exist
 * (or that FLS hides) are dropped; a list that resolves to nothing falls through to the next rule.
 */
export function resolveVisibleFields<F extends ListField>(
  accessible: F[],
  columnOverride: string[] | null,
  viewColumns: string[] | null | undefined
): F[] {
  if (accessible.length === 0) return []
  const fromOverride = columnOverride ? orderFieldsByView(accessible, columnOverride) : null
  const fromView = !fromOverride && viewColumns ? orderFieldsByView(accessible, viewColumns) : null
  return fromOverride ?? fromView ?? accessible.slice(0, DEFAULT_VISIBLE_COLUMN_COUNT)
}

/** Kanban lanes: the configured picklist field, else the first accessible picklist; null if none. */
export function resolveKanbanLaneField<F extends ListField>(
  accessible: F[],
  typeConfig: TypeConfig
): F | null {
  const configured = typeConfig?.kanban?.laneField
  const picklists = accessible.filter((f) => f.type === 'picklist')
  return (configured && picklists.find((f) => f.name === configured)) || picklists[0] || null
}

/** Calendar date: the configured date/datetime field, else the first one; null if none. */
export function resolveCalendarDateField<F extends ListField>(
  accessible: F[],
  typeConfig: TypeConfig
): F | null {
  const configured = typeConfig?.calendar?.dateField
  const dateFields = accessible.filter((f) => f.type === 'date' || f.type === 'datetime')
  return (configured && dateFields.find((f) => f.name === configured)) || dateFields[0] || null
}

/** Gallery image: only an explicitly configured `url` field — no image otherwise. */
export function resolveGalleryImageField<F extends ListField>(
  accessible: F[],
  typeConfig: TypeConfig
): F | undefined {
  const configured = typeConfig?.gallery?.imageField
  if (!configured) return undefined
  return accessible.find((f) => f.name === configured && f.type === 'url')
}

/** Card / chip title: the gallery's configured title > the schema display field > `name`. */
export function resolveTitleField(typeConfig: TypeConfig, schemaDisplayField?: string): string {
  return typeConfig?.gallery?.titleField || schemaDisplayField || 'name'
}

/** Kanban card body: the configured names, else the first 3 visible non-title columns. */
export function resolveKanbanCardFields<F extends ListField>(
  accessible: F[],
  visible: F[],
  titleField: string,
  typeConfig: TypeConfig
): F[] {
  const configured = typeConfig?.kanban?.cardFields
  const source = configured
    ? configured
        .map((name) => accessible.find((f) => f.name === name))
        .filter((f): f is F => Boolean(f))
    : visible.filter((f) => f.name !== titleField)
  return source.slice(0, 3)
}

/**
 * Fields a mass edit may set (same rule as RelatedList): user-editable schema fields only —
 * no `id`, no audit fields, no server-computed types.
 */
export function massEditableFields<F extends ListField>(fields: F[]): F[] {
  return fields.filter(
    (f) =>
      f.name !== 'id' && !SYSTEM_AUDIT_FIELDS.includes(f.name) && getFieldControl(f.type).editable
  )
}

/**
 * Grouping prepends the group field to the server sort so buckets arrive contiguous; the
 * user's own sort levels order rows within groups. The URL `sort=` param is not touched.
 */
export function withGroupSort(sorts: SortState[], groupBy: string | null): SortState[] {
  if (!groupBy) return sorts
  return [{ field: groupBy, direction: 'asc' }, ...sorts.filter((s) => s.field !== groupBy)]
}

/**
 * The calendar merges its visible month (`YYYY-MM`) as gte/lte conditions on the date field —
 * view state, not user filters (the filter chips keep showing only the user's own).
 */
export function withCalendarRange(
  filters: FilterCondition[],
  dateField: string | null,
  month: string
): FilterCondition[] {
  if (!dateField) return filters
  const range = monthRange(month)
  return [
    ...filters,
    { id: '__calendar_gte', field: dateField, operator: 'greater_than_or_equal', value: range.gte },
    { id: '__calendar_lte', field: dateField, operator: 'less_than_or_equal', value: range.lte },
  ]
}

/**
 * The `sort=` param after a header click. Plain click: single-level cycle asc → desc → none.
 * Shift-click (`additive`): appends the field as a new level, or cycles / removes its existing
 * level while keeping the others (the server accepts the comma grammar natively).
 */
export function nextSortParam(
  sorts: SortState[],
  field: string,
  additive: boolean
): string | undefined {
  if (!additive) {
    if (sorts.length === 1 && sorts[0].field === field) {
      return sorts[0].direction === 'asc' ? `-${field}` : undefined
    }
    return field
  }
  const index = sorts.findIndex((s) => s.field === field)
  let next = [...sorts]
  if (index === -1) {
    next.push({ field, direction: 'asc' })
  } else if (next[index].direction === 'asc') {
    next[index] = { field, direction: 'desc' }
  } else {
    next = next.filter((s) => s.field !== field)
  }
  return buildSortParam(next)
}

/** The density toggle's cycle: normal → compact → comfortable → normal. */
export function nextDensity(density: SavedViewDensity): SavedViewDensity {
  if (density === 'normal') return 'compact'
  if (density === 'compact') return 'comfortable'
  return 'normal'
}
