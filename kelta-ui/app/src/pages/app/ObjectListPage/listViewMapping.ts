/**
 * Saved-view helpers for the end-user list (app-surfacing slice 5).
 *
 * Two view sources feed one ViewSelector:
 * - personal views — the existing localStorage `useSavedViews` mechanism;
 * - shared views — admin-authored `list-views` system-collection rows
 *   (ListViewsPage, MANAGE_LISTVIEWS), surfaced read-only with a `shared:` id prefix.
 *
 * A shared row also publishes its renderer (`viewType`/`typeConfig`, V196), so an
 * admin can hand out a kanban board rather than a column set every user has to
 * re-configure. Anything unrecognized maps to the table renderer.
 */
import type { SavedView, SavedViewType, FilterCondition } from '@/hooks/useSavedViews'

export const SHARED_VIEW_PREFIX = 'shared:'

export function isSharedViewId(viewId: string): boolean {
  return viewId.startsWith(SHARED_VIEW_PREFIX)
}

/** A raw `list-views` JSON:API row (attributes flattened by apiClient.getList). */
export interface ListViewRow {
  id: string
  name?: string
  columns?: unknown
  filters?: unknown
  sortField?: string | null
  sortDirection?: string | null
  rowLimit?: number | null
  isDefault?: boolean
  /** Renderer published with the view (V196): TABLE | KANBAN | CALENDAR | GALLERY. */
  viewType?: unknown
  /** Per-renderer settings, e.g. {kanban: {laneField, cardFields}} (V196). */
  typeConfig?: unknown
}

const VIEW_TYPES: readonly SavedViewType[] = ['table', 'kanban', 'calendar', 'gallery']

/**
 * Stored `viewType` (uppercase on the row) → the FE's lowercase SavedViewType.
 * Anything unrecognized returns undefined so the caller renders a table — rows
 * written before V196, or by a future/rolled-back platform version, must not
 * break the list.
 */
function asViewType(value: unknown): SavedViewType | undefined {
  if (typeof value !== 'string') return undefined
  const lower = value.trim().toLowerCase() as SavedViewType
  return VIEW_TYPES.includes(lower) ? lower : undefined
}

function asOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function asOptionalStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined
  const names = value.filter((v): v is string => typeof v === 'string')
  return names.length > 0 ? names : undefined
}

/**
 * Keeps only the per-renderer settings that match the SavedView shape. A section
 * missing its required field (kanban lane, calendar date) is dropped rather than
 * passed through half-formed — the page then resolves that field itself from the
 * schema, which is the same path a user's first toolbar switch takes.
 */
export function parseTypeConfig(value: unknown): SavedView['typeConfig'] | undefined {
  const parsed = typeof value === 'string' ? safeParse(value) : value
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined
  const source = parsed as Record<string, Record<string, unknown> | undefined>
  const config: NonNullable<SavedView['typeConfig']> = {}

  const laneField = asOptionalString(source.kanban?.laneField)
  if (laneField) {
    config.kanban = { laneField, cardFields: asOptionalStringArray(source.kanban?.cardFields) }
  }
  const dateField = asOptionalString(source.calendar?.dateField)
  if (dateField) {
    config.calendar = {
      dateField,
      endDateField: asOptionalString(source.calendar?.endDateField),
    }
  }
  const gallery = {
    imageField: asOptionalString(source.gallery?.imageField),
    titleField: asOptionalString(source.gallery?.titleField),
    cardFields: asOptionalStringArray(source.gallery?.cardFields),
  }
  if (gallery.imageField || gallery.titleField || gallery.cardFields) {
    config.gallery = gallery
  }
  return Object.keys(config).length > 0 ? config : undefined
}

function asStringArray(value: unknown): string[] {
  const parsed = typeof value === 'string' ? safeParse(value) : value
  if (!Array.isArray(parsed)) return []
  return parsed.filter((v): v is string => typeof v === 'string')
}

/** Accepts only filter entries already in the FE FilterCondition shape; drops the rest. */
function asFilterConditions(value: unknown): FilterCondition[] {
  const parsed = typeof value === 'string' ? safeParse(value) : value
  if (!Array.isArray(parsed)) return []
  return parsed.filter(
    (f): f is FilterCondition =>
      !!f &&
      typeof f === 'object' &&
      typeof (f as FilterCondition).field === 'string' &&
      typeof (f as FilterCondition).operator === 'string' &&
      typeof (f as FilterCondition).value === 'string'
  )
}

function safeParse(value: string): unknown {
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

/**
 * Maps an admin-authored `list-views` row into the SavedView shape the ViewSelector
 * renders. Filters not matching the FE condition shape are dropped (the admin builder's
 * server-side operator grammar is a superset); columns/sort/rowLimit always apply.
 */
export function mapSharedListView(row: ListViewRow, collectionName: string): SavedView {
  const pageSize = [10, 25, 50, 100].includes(row.rowLimit ?? -1) ? row.rowLimit! : 25
  return {
    id: `${SHARED_VIEW_PREFIX}${row.id}`,
    name: row.name || 'Shared view',
    collectionName,
    filters: asFilterConditions(row.filters).map((f, i) => ({ ...f, id: f.id ?? `s${i + 1}` })),
    sortField: row.sortField ?? null,
    sortDirection: row.sortDirection?.toUpperCase() === 'DESC' ? 'desc' : 'asc',
    sorts: row.sortField
      ? [
          {
            field: row.sortField,
            direction:
              row.sortDirection?.toUpperCase() === 'DESC' ? ('desc' as const) : ('asc' as const),
          },
        ]
      : undefined,
    visibleColumns: asStringArray(row.columns),
    pageSize,
    isDefault: row.isDefault === true,
    viewType: asViewType(row.viewType),
    typeConfig: parseTypeConfig(row.typeConfig),
    createdAt: '',
  }
}

/**
 * Orders the schema fields by a view's visibleColumns (view order wins); returns null when
 * the view declares no columns so the caller falls back to the default first-6 rule.
 */
export function orderFieldsByView<T extends { name: string }>(
  fields: T[],
  visibleColumns: string[]
): T[] | null {
  if (visibleColumns.length === 0) return null
  const byName = new Map(fields.map((f) => [f.name, f]))
  const ordered = visibleColumns.map((name) => byName.get(name)).filter((f): f is T => !!f)
  return ordered.length > 0 ? ordered : null
}
