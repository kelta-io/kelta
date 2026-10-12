/**
 * AdminDataTable
 *
 * Shared grid for admin/builder pages (Page Layouts, Collections, List Views,
 * Flows, ...). Provides:
 *  - Column-config driven rendering with custom cell renderers
 *  - Click-to-sort headers (ascending / descending / unset cycle)
 *  - Column show/hide via ColumnPicker, persisted to localStorage by tableId
 *  - Optional in-grid FilterBuilder row
 *  - Row actions slot
 *
 * Distinct from the end-user `ObjectDataTable`, which is field-definition
 * driven and tied to CollectionRecord. Admin pages know their schema
 * statically, so we work with a plain `ColumnDef<T>[]` instead.
 */

import React, { useCallback, useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { ColumnPicker, loadHiddenColumns } from '@/components/ColumnPicker'
import type { ColumnPickerOption } from '@/components/ColumnPicker'
import {
  FilterBuilder,
  type FieldOption,
  type FilterClause,
  type FilterExpression,
} from '@/components/FilterBuilder'
import { evaluateFilter } from './evaluateFilter'

export interface AdminColumn<T> {
  id: string
  header: string
  /** How to read the column value for sorting / filtering. Defaults to row[id]. */
  accessor?: (row: T) => unknown
  /** Custom cell renderer. Falls back to the accessor's string repr. */
  cell?: (row: T) => React.ReactNode
  sortable?: boolean
  /** Allow this column to be hidden via the column picker. Defaults to true. */
  hideable?: boolean
  /** Class names applied to the header cell. */
  headerClassName?: string
  /** Class names applied to body cells. */
  cellClassName?: string
}

export interface AdminSortState {
  columnId: string
  direction: 'asc' | 'desc'
}

export interface AdminDataTableProps<T> {
  /** Stable id used to namespace column-visibility localStorage. */
  tableId: string
  columns: AdminColumn<T>[]
  rows: T[]
  rowKey: (row: T) => string
  /**
   * Render-prop for the per-row actions cell (Design / Edit / etc.). `index` is
   * the row's position in the displayed (filtered + sorted) order.
   */
  renderActions?: (row: T, index: number) => React.ReactNode
  emptyMessage?: React.ReactNode
  loading?: boolean
  /** Optional row click handler (e.g. open detail). */
  onRowClick?: (row: T) => void
  /** Class name applied to the wrapper. */
  className?: string
  /** Test id on the wrapper. Defaults to `admin-data-table`. */
  testId?: string
  /** Accessible name for the `<table>` element. */
  ariaLabel?: string
  /**
   * Per-row test id. Defaults to `admin-data-table-row-<rowKey>`; pages that
   * predate this table pass their own (e.g. `profile-row-<index>`) so existing
   * e2e selectors keep working.
   */
  rowTestId?: (row: T, index: number) => string
  /** Header label for the actions column. Defaults to `Actions`. */
  actionsHeader?: string
  /**
   * Show the in-grid Filters toggle. Defaults to true; turn it off when the
   * page already filters server-side (the grid only sees the current page).
   */
  filterable?: boolean
}

export function AdminDataTable<T>({
  tableId,
  columns,
  rows,
  rowKey,
  renderActions,
  emptyMessage,
  loading,
  onRowClick,
  className,
  testId = 'admin-data-table',
  ariaLabel,
  rowTestId,
  actionsHeader = 'Actions',
  filterable = true,
}: AdminDataTableProps<T>): React.ReactElement {
  const [hidden, setHidden] = useState<Set<string>>(() => loadHiddenColumns(tableId))
  const [sort, setSort] = useState<AdminSortState | null>(null)
  const [filter, setFilter] = useState<FilterExpression | null>(null)
  const [showFilter, setShowFilter] = useState(false)

  const visibleColumns = useMemo(() => columns.filter((c) => !hidden.has(c.id)), [columns, hidden])

  const pickerOptions: ColumnPickerOption[] = useMemo(
    () => columns.filter((c) => c.hideable !== false).map((c) => ({ id: c.id, label: c.header })),
    [columns]
  )

  const filterFields: FieldOption[] = useMemo(
    () => columns.map((c) => ({ name: c.id, displayName: c.header })),
    [columns]
  )

  const columnsById = useMemo(() => {
    const map = new Map<string, AdminColumn<T>>()
    for (const c of columns) map.set(c.id, c)
    return map
  }, [columns])

  const accessorFor = useCallback((col: AdminColumn<T>, row: T): unknown => {
    if (col.accessor) return col.accessor(row)
    return (row as Record<string, unknown>)[col.id]
  }, [])

  const filteredRows = useMemo(() => {
    if (!filter || filter.filters.length === 0) return rows
    return rows.filter((row) => {
      const recordView: Record<string, unknown> = {}
      for (const clause of filter.filters) {
        const col = columnsById.get(clause.field)
        recordView[clause.field] = col ? accessorFor(col, row) : undefined
      }
      return evaluateFilter(filter, recordView)
    })
  }, [filter, rows, columnsById, accessorFor])

  const sortedRows = useMemo(() => {
    if (!sort) return filteredRows
    const col = columnsById.get(sort.columnId)
    if (!col) return filteredRows
    const dir = sort.direction === 'asc' ? 1 : -1
    return [...filteredRows].sort((a, b) => {
      const av = accessorFor(col, a)
      const bv = accessorFor(col, b)
      return compareValues(av, bv) * dir
    })
  }, [sort, filteredRows, columnsById, accessorFor])

  const toggleSort = useCallback((columnId: string) => {
    setSort((prev) => {
      if (!prev || prev.columnId !== columnId) return { columnId, direction: 'asc' }
      if (prev.direction === 'asc') return { columnId, direction: 'desc' }
      return null
    })
  }, [])

  const hasAnyFilter = filter && filter.filters.length > 0

  return (
    <div className={cn('flex flex-col gap-2', className)} data-testid={testId}>
      <div className="flex flex-wrap items-center gap-2">
        {filterable && (
          <button
            type="button"
            className={cn(
              'inline-flex items-center rounded-md border border-border bg-background px-3 py-1.5 text-sm font-medium hover:bg-muted',
              showFilter && 'bg-muted'
            )}
            onClick={() => setShowFilter((v) => !v)}
            data-testid="admin-data-table-filter-toggle"
          >
            {hasAnyFilter ? 'Filters (active)' : 'Filters'}
          </button>
        )}
        <div className="ml-auto">
          <ColumnPicker
            tableId={tableId}
            columns={pickerOptions}
            hidden={hidden}
            onChange={setHidden}
          />
        </div>
      </div>

      {filterable && showFilter && (
        <div
          className="rounded-md border border-border bg-muted/30 p-3"
          data-testid="admin-data-table-filter"
        >
          <FilterBuilder
            value={filter}
            onChange={setFilter}
            fields={filterFields}
            idPrefix={`${tableId}-filter`}
          />
        </div>
      )}

      <Table aria-label={ariaLabel}>
        <TableHeader>
          <TableRow>
            {visibleColumns.map((col) => {
              const isSortable = col.sortable !== false
              const isSorted = sort?.columnId === col.id
              return (
                <TableHead
                  key={col.id}
                  className={col.headerClassName}
                  aria-sort={
                    isSorted
                      ? sort?.direction === 'asc'
                        ? 'ascending'
                        : 'descending'
                      : isSortable
                        ? 'none'
                        : undefined
                  }
                >
                  {isSortable ? (
                    <button
                      type="button"
                      className="inline-flex items-center gap-1 text-foreground hover:text-foreground/80"
                      onClick={() => toggleSort(col.id)}
                      data-testid={`admin-data-table-sort-${col.id}`}
                    >
                      {col.header}
                      <SortIndicator sort={sort} columnId={col.id} />
                    </button>
                  ) : (
                    col.header
                  )}
                </TableHead>
              )
            })}
            {renderActions && <TableHead className="w-1 text-right">{actionsHeader}</TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {loading ? (
            <TableRow>
              <TableCell
                colSpan={visibleColumns.length + (renderActions ? 1 : 0)}
                className="py-6 text-center text-sm text-muted-foreground"
              >
                Loading…
              </TableCell>
            </TableRow>
          ) : sortedRows.length === 0 ? (
            <TableRow>
              <TableCell
                colSpan={visibleColumns.length + (renderActions ? 1 : 0)}
                className="py-6 text-center text-sm text-muted-foreground"
              >
                {emptyMessage ?? 'No rows.'}
              </TableCell>
            </TableRow>
          ) : (
            sortedRows.map((row, index) => (
              <TableRow
                key={rowKey(row)}
                className={onRowClick ? 'cursor-pointer' : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                data-testid={
                  rowTestId ? rowTestId(row, index) : `admin-data-table-row-${rowKey(row)}`
                }
              >
                {visibleColumns.map((col) => (
                  <TableCell key={col.id} className={col.cellClassName}>
                    {col.cell ? col.cell(row) : renderDefaultCell(accessorFor(col, row))}
                  </TableCell>
                ))}
                {renderActions && (
                  <TableCell className="w-1 text-right" onClick={(e) => e.stopPropagation()}>
                    {renderActions(row, index)}
                  </TableCell>
                )}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </div>
  )
}

function SortIndicator({
  sort,
  columnId,
}: {
  sort: AdminSortState | null
  columnId: string
}): React.ReactElement {
  if (!sort || sort.columnId !== columnId) {
    return <ArrowUpDown className="h-3.5 w-3.5 text-muted-foreground/50" />
  }
  return sort.direction === 'asc' ? (
    <ArrowUp className="h-3.5 w-3.5" />
  ) : (
    <ArrowDown className="h-3.5 w-3.5" />
  )
}

function renderDefaultCell(value: unknown): React.ReactNode {
  if (value === null || value === undefined) return null
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (value instanceof Date) return value.toISOString()
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function compareValues(a: unknown, b: unknown): number {
  if (a === b) return 0
  if (a === null || a === undefined) return -1
  if (b === null || b === undefined) return 1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  if (typeof a === 'boolean' && typeof b === 'boolean') return a === b ? 0 : a ? 1 : -1
  return String(a).localeCompare(String(b))
}

// Re-export FilterClause so callers don't need to import from two places.
export type { FilterClause }
