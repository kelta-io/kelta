/**
 * ObjectDataTable Component
 *
 * A data table for displaying collection records with:
 * - Sortable column headers
 * - Row selection with select-all
 * - Field type-aware rendering via FieldRenderer
 * - Row click navigation to record detail
 * - Row action menu (Edit, Delete)
 * - Keyboard navigation (Arrow keys, Enter, Space, Home, End, Escape)
 * - Virtual scrolling for large datasets (>100 rows)
 */

import React, { useCallback, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useVirtualizer } from '@tanstack/react-virtual'
import {
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  ChevronDown,
  ChevronRight,
  MoreHorizontal,
  Eye,
  Pencil,
  Trash2,
} from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Checkbox } from '@/components/ui/checkbox'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Skeleton } from '@/components/ui/skeleton'
import { FieldRenderer } from '@/components/FieldRenderer'
import { InlineFieldValue } from '@/components/record/InlineFieldValue'
import { useTableKeyboardNav } from '@/hooks/useTableKeyboardNav'
import { cn } from '@/lib/utils'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord, SortState } from '@/hooks/useCollectionRecords'

/** Enable virtual scrolling when row count exceeds this threshold */
const VIRTUAL_THRESHOLD = 100

/** Estimated row height in pixels for the virtualizer */
const ROW_HEIGHT_ESTIMATE = 40

/** Max visible height for the virtual scrollable area */
const VIRTUAL_MAX_HEIGHT = 600

/** Field types summed in group headers (matches FieldRenderer's numeric cases). */
const NUMERIC_FIELD_TYPES = new Set(['number', 'currency', 'percent'])

/** One page-local group bucket (app-data-entry slice 3). */
interface RecordGroup {
  /** Stable bucket key (String(value), '' for null/empty). */
  key: string
  /** Raw group-field value of the bucket (label rendering). */
  value: unknown
  records: CollectionRecord[]
}

/**
 * Bucket records by a field, preserving arrival order (the caller prepends the
 * group field to the server sort, so buckets arrive contiguous — Map insertion
 * order keeps them that way even if they don't).
 */
function groupRecords(records: CollectionRecord[], groupBy: string): RecordGroup[] {
  const buckets = new Map<string, RecordGroup>()
  for (const record of records) {
    const value = record[groupBy]
    const key = value === null || value === undefined || value === '' ? '' : String(value)
    const bucket = buckets.get(key)
    if (bucket) {
      bucket.records.push(record)
    } else {
      buckets.set(key, { key, value, records: [record] })
    }
  }
  return [...buckets.values()]
}

/** Per-group sums for the visible numeric columns; non-parsable values are skipped. */
function groupSums(
  group: RecordGroup,
  fields: FieldDefinition[]
): Array<[FieldDefinition, number]> {
  const sums: Array<[FieldDefinition, number]> = []
  for (const field of fields) {
    if (!NUMERIC_FIELD_TYPES.has(field.type)) continue
    let sum = 0
    for (const record of group.records) {
      const value = record[field.name]
      if (value === null || value === undefined || value === '') continue
      const n = Number(value)
      if (Number.isFinite(n)) sum += n
    }
    sums.push([field, sum])
  }
  return sums
}

export interface ObjectDataTableProps {
  /** Records to display */
  records: CollectionRecord[]
  /** Field definitions for visible columns */
  fields: FieldDefinition[]
  /** Current sort state */
  sort?: SortState
  /** Callback when a column header is clicked for sorting */
  /** Additive=true on shift-click appends/toggles a secondary sort level (opt-in multi-sort). */
  onSortChange: (field: string, additive?: boolean) => void
  /** Set of selected record IDs */
  selectedIds: Set<string>
  /** Callback when selection changes */
  onSelectionChange: (ids: Set<string>) => void
  /** Whether the table is in a loading state */
  isLoading?: boolean
  /** Collection name for building URLs */
  collectionName: string
  /**
   * Row activation (row click, "View" action, keyboard Enter). When omitted the
   * table navigates to the end-user record route (`/{tenant}/app/o/{collection}/{id}`).
   * The admin resource browser passes this to route to `/{tenant}/resources/...` instead.
   */
  onRowClick?: (record: CollectionRecord) => void
  /** Callback when edit is clicked on a row */
  onEdit?: (record: CollectionRecord) => void
  /** Callback when delete is clicked on a row */
  onDelete?: (record: CollectionRecord) => void
  /** Lookup display map: { fieldName: { recordId: displayLabel } } */
  lookupDisplayMap?: Record<string, Record<string, string>>
  /**
   * Field name → raw value→`{label, color}` (see `usePicklistDisplayMap`) for picklist/
   * multi_picklist columns. Badges show the authored label; the stored value is unaffected.
   */
  picklistDisplayMaps?: Record<string, Map<string, { label: string; color?: string }>>
  /**
   * Opt-in in-place cell editing (unified record experience, slice 3). When true AND
   * `onCellCommit` is provided, editable-type cells become click-to-edit via `InlineFieldValue`.
   * When false/omitted the grid is read-only exactly as before.
   */
  editable?: boolean
  /** Persist a single edited cell (partial PATCH). Rejects surface inline in the cell. */
  onCellCommit?: (recordId: string, fieldName: string, value: unknown) => Promise<void>
  /** Ordered multi-sort state; when present it supersedes `sort` for indicators. */
  sorts?: SortState[]
  /** Row density (opt-in; default 'normal' preserves current sizing). */
  density?: 'compact' | 'normal' | 'comfortable'
  /** Freeze the first data column (plus selection checkbox) on horizontal scroll. */
  stickyFirstColumn?: boolean
  /**
   * Group rows by this field over the fetched page (opt-in; app-data-entry slice 3).
   * Renders collapsible group-header rows with count + numeric sums and disables
   * virtualization (page-sized data only). Absent = today's flat rendering.
   */
  groupBy?: string
}

/**
 * Fields the server masked for this viewer on a given row (`meta.maskedFields`,
 * surfaced onto the flat record by flattenResource as `__maskedFields`).
 */
function maskedFieldsOf(record: CollectionRecord): Set<string> {
  const raw = (record as { __maskedFields?: unknown }).__maskedFields
  return new Set(Array.isArray(raw) ? (raw as string[]) : [])
}

/**
 * Renders a sort indicator icon based on the current sort state.
 */
function SortIcon({
  field,
  sort,
  sorts,
}: {
  field: string
  sort?: SortState
  sorts?: SortState[]
}) {
  const levels = sorts && sorts.length > 0 ? sorts : sort ? [sort] : []
  const index = levels.findIndex((s) => s.field === field)
  if (index === -1) {
    return <ArrowUpDown className="ml-1 h-3.5 w-3.5 text-muted-foreground/50" />
  }
  const icon =
    levels[index].direction === 'asc' ? (
      <ArrowUp className="ml-1 h-3.5 w-3.5" />
    ) : (
      <ArrowDown className="ml-1 h-3.5 w-3.5" />
    )
  return (
    <span className="inline-flex items-center">
      {icon}
      {levels.length > 1 && (
        <span
          className="ml-0.5 text-[10px] font-semibold text-muted-foreground"
          data-testid={`sort-level-${field}`}
        >
          {index + 1}
        </span>
      )}
    </span>
  )
}

/**
 * Loading skeleton for the data table.
 */
function TableSkeleton({ columnCount }: { columnCount: number }) {
  return (
    <>
      {Array.from({ length: 5 }).map((_, rowIdx) => (
        <TableRow key={rowIdx}>
          <TableCell className="w-[40px]">
            <Skeleton className="h-4 w-4" />
          </TableCell>
          {Array.from({ length: columnCount }).map((_, colIdx) => (
            <TableCell key={colIdx}>
              <Skeleton className="h-4 w-[80%]" />
            </TableCell>
          ))}
          <TableCell className="w-[50px]">
            <Skeleton className="h-8 w-8" />
          </TableCell>
        </TableRow>
      ))}
    </>
  )
}

/**
 * Renders a single data row. Extracted to reduce duplication between
 * normal and virtualized rendering paths.
 */
function DataRow({
  record,
  fields,
  isSelected,
  rowProps,
  tenantSlug,
  lookupDisplayMap,
  picklistDisplayMaps,
  onRowClick,
  onSelectRow,
  onEdit,
  onDelete,
  editable,
  onCellCommit,
  density,
  stickyFirstColumn,
}: {
  record: CollectionRecord
  fields: FieldDefinition[]
  isSelected: boolean
  rowProps: {
    tabIndex: number
    'aria-selected': boolean
    'data-focused': boolean
    onFocus: () => void
  }
  tenantSlug: string | undefined
  lookupDisplayMap?: Record<string, Record<string, string>>
  picklistDisplayMaps?: Record<string, Map<string, { label: string; color?: string }>>
  onRowClick: (record: CollectionRecord) => void
  onSelectRow: (id: string) => void
  onEdit?: (record: CollectionRecord) => void
  onDelete?: (record: CollectionRecord) => void
  editable?: boolean
  onCellCommit?: (recordId: string, fieldName: string, value: unknown) => Promise<void>
  density?: 'compact' | 'normal' | 'comfortable'
  stickyFirstColumn?: boolean
}) {
  const inlineEditing = !!editable && !!onCellCommit
  const densityClass = density === 'compact' ? 'py-1' : density === 'comfortable' ? 'py-4' : ''
  return (
    <TableRow
      key={record.id}
      className={cn(
        'cursor-pointer even:bg-muted/40 hover:bg-primary/10',
        rowProps['data-focused'] && 'ring-2 ring-inset ring-ring'
      )}
      data-state={isSelected ? 'selected' : undefined}
      tabIndex={rowProps.tabIndex}
      aria-selected={rowProps['aria-selected']}
      onFocus={rowProps.onFocus}
      onClick={() => onRowClick(record)}
    >
      {/* Checkbox */}
      <TableCell
        className={cn(
          'w-[40px]',
          densityClass,
          stickyFirstColumn && 'sticky left-0 z-10 bg-inherit'
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <Checkbox
          checked={isSelected}
          onCheckedChange={() => onSelectRow(record.id)}
          aria-label={`Select row ${record.id}`}
        />
      </TableCell>

      {/* Data cells */}
      {fields.map((field, cellIndex) => {
        const fieldValue = record[field.name]
        const isLookup =
          field.type === 'master_detail' || field.type === 'lookup' || field.type === 'reference'
        const displayLabel =
          isLookup && lookupDisplayMap?.[field.name]
            ? lookupDisplayMap[field.name][String(fieldValue)] || undefined
            : undefined

        if (inlineEditing) {
          // No cell-level stopPropagation: a value click still bubbles to the row's navigation
          // handler; editing is triggered only by the hover pencil (editOn="pencil").
          return (
            <TableCell
              key={field.name}
              className={cn(
                'max-w-[300px]',
                densityClass,
                stickyFirstColumn && cellIndex === 0 && 'sticky left-[40px] z-10 bg-inherit'
              )}
            >
              <InlineFieldValue
                field={field}
                value={fieldValue}
                displayLabel={displayLabel}
                tenantSlug={tenantSlug}
                picklistDisplayMap={picklistDisplayMaps?.[field.name]}
                editable
                editOn="pencil"
                masked={maskedFieldsOf(record).has(field.name)}
                onCommit={(fieldName, value) => onCellCommit!(record.id, fieldName, value)}
              />
            </TableCell>
          )
        }

        return (
          <TableCell
            key={field.name}
            className={cn(
              'max-w-[300px]',
              densityClass,
              stickyFirstColumn && cellIndex === 0 && 'sticky left-[40px] z-10 bg-inherit'
            )}
          >
            <FieldRenderer
              type={field.type}
              value={fieldValue}
              fieldName={field.name}
              displayName={field.displayName || field.name}
              tenantSlug={tenantSlug}
              targetCollection={field.referenceTarget}
              displayLabel={displayLabel}
              picklistDisplayMap={picklistDisplayMaps?.[field.name]}
              truncate
            />
          </TableCell>
        )
      })}

      {/* Row actions */}
      <TableCell className="w-[50px]" onClick={(e) => e.stopPropagation()}>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="h-8 w-8" aria-label="Row actions">
              <MoreHorizontal className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem onClick={() => onRowClick(record)}>
              <Eye className="mr-2 h-4 w-4" />
              View
            </DropdownMenuItem>
            {onEdit && (
              <DropdownMenuItem onClick={() => onEdit(record)}>
                <Pencil className="mr-2 h-4 w-4" />
                Edit
              </DropdownMenuItem>
            )}
            {onDelete && (
              <DropdownMenuItem
                className="text-destructive focus:text-destructive"
                onClick={() => onDelete(record)}
              >
                <Trash2 className="mr-2 h-4 w-4" />
                Delete
              </DropdownMenuItem>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </TableCell>
    </TableRow>
  )
}

export function ObjectDataTable({
  records,
  fields,
  sort,
  onSortChange,
  sorts,
  density = 'normal',
  stickyFirstColumn = false,
  groupBy,
  selectedIds,
  onSelectionChange,
  isLoading = false,
  collectionName,
  onRowClick,
  onEdit,
  onDelete,
  lookupDisplayMap,
  picklistDisplayMaps,
  editable,
  onCellCommit,
}: ObjectDataTableProps): React.ReactElement {
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const navigate = useNavigate()
  const basePath = `/${tenantSlug}/app`

  // Ref for virtual scrolling container
  const scrollContainerRef = useRef<HTMLDivElement>(null)

  // Grouping (slice 3). Collapse keys carry the group field so switching fields
  // never leaks collapsed buckets across fields (no reset effect needed).
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => new Set())
  const groups = useMemo(
    () => (groupBy ? groupRecords(records, groupBy) : null),
    [records, groupBy]
  )
  const collapseKey = useCallback((key: string) => `${groupBy}:${key}`, [groupBy])
  const toggleGroup = useCallback(
    (key: string) => {
      const full = collapseKey(key)
      setCollapsedGroups((prev) => {
        const next = new Set(prev)
        if (next.has(full)) next.delete(full)
        else next.add(full)
        return next
      })
    },
    [collapseKey]
  )

  // Rows actually shown, in display order — keyboard nav indexes into this list
  // (collapsed groups are skipped). Identical to `records` when not grouping.
  const visibleRecords = useMemo(() => {
    if (!groups) return records
    return groups.flatMap((g) => (collapsedGroups.has(collapseKey(g.key)) ? [] : g.records))
  }, [groups, records, collapsedGroups, collapseKey])

  // Whether to use virtual scrolling. Grouping opts out: header rows break the
  // fixed-height row estimate and pages are clamped at 200 rows anyway.
  const useVirtual = !groupBy && records.length > VIRTUAL_THRESHOLD

  // Keyboard navigation
  const { handleKeyDown, tableRef, getRowProps } = useTableKeyboardNav({
    rowCount: visibleRecords.length,
    onRowActivate: (index) => {
      if (visibleRecords[index]) {
        if (onRowClick) {
          onRowClick(visibleRecords[index])
        } else {
          navigate(`${basePath}/o/${collectionName}/${visibleRecords[index].id}`)
        }
      }
    },
    onRowToggle: (index) => {
      if (visibleRecords[index]) {
        handleSelectRow(visibleRecords[index].id)
      }
    },
    enabled: !isLoading && visibleRecords.length > 0,
  })

  // Virtual row virtualizer (only active when useVirtual is true)
  const virtualizer = useVirtualizer({
    count: useVirtual ? records.length : 0,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => ROW_HEIGHT_ESTIMATE,
    overscan: 10,
  })

  // Check if all visible records are selected
  const allSelected = useMemo(() => {
    if (records.length === 0) return false
    return records.every((r) => selectedIds.has(r.id))
  }, [records, selectedIds])

  // Some (but not all) selected
  const someSelected = useMemo(() => {
    if (records.length === 0) return false
    const hasAny = records.some((r) => selectedIds.has(r.id))
    return hasAny && !allSelected
  }, [records, selectedIds, allSelected])

  // Handle select-all toggle
  const handleSelectAll = useCallback(() => {
    if (allSelected) {
      const next = new Set(selectedIds)
      for (const record of records) {
        next.delete(record.id)
      }
      onSelectionChange(next)
    } else {
      const next = new Set(selectedIds)
      for (const record of records) {
        next.add(record.id)
      }
      onSelectionChange(next)
    }
  }, [allSelected, records, selectedIds, onSelectionChange])

  // Handle individual row selection
  const handleSelectRow = useCallback(
    (recordId: string) => {
      const next = new Set(selectedIds)
      if (next.has(recordId)) {
        next.delete(recordId)
      } else {
        next.add(recordId)
      }
      onSelectionChange(next)
    },
    [selectedIds, onSelectionChange]
  )

  // Navigate to record detail (or defer to the caller's route)
  const handleRowClick = useCallback(
    (record: CollectionRecord) => {
      if (onRowClick) {
        onRowClick(record)
      } else {
        navigate(`${basePath}/o/${collectionName}/${record.id}`)
      }
    },
    [onRowClick, navigate, basePath, collectionName]
  )

  // Get aria-sort value for a column
  const getAriaSort = useCallback(
    (field: string): 'ascending' | 'descending' | 'none' => {
      if (!sort || sort.field !== field) return 'none'
      return sort.direction === 'asc' ? 'ascending' : 'descending'
    },
    [sort]
  )

  // Shared table header — kelta-table-header applies the DESIGN.md §5 head styling
  // (uppercase 11px, distinct background, tracking-0.09em).
  const tableHeader = (
    <TableHeader>
      <TableRow className="kelta-table-header hover:bg-transparent">
        {/* Checkbox column */}
        <TableHead
          className={`w-[40px] ${stickyFirstColumn ? 'sticky left-0 z-20 bg-inherit' : ''}`}
        >
          <Checkbox
            checked={allSelected ? true : someSelected ? 'indeterminate' : false}
            onCheckedChange={handleSelectAll}
            aria-label={allSelected ? 'Deselect all rows' : 'Select all rows'}
          />
        </TableHead>

        {/* Data columns */}
        {fields.map((field, index) => (
          <TableHead
            key={field.name}
            className={`cursor-pointer select-none whitespace-nowrap ${
              stickyFirstColumn && index === 0 ? 'sticky left-[40px] z-20 bg-inherit' : ''
            }`}
            onClick={(e) => onSortChange(field.name, e.shiftKey)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onSortChange(field.name, e.shiftKey)
              }
            }}
            tabIndex={0}
            aria-sort={getAriaSort(field.name)}
            role="columnheader"
          >
            <div className="flex items-center">
              {field.displayName || field.name}
              <SortIcon field={field.name} sort={sort} sorts={sorts} />
            </div>
          </TableHead>
        ))}

        {/* Actions column */}
        <TableHead className="w-[50px]">
          <span className="sr-only">Actions</span>
        </TableHead>
      </TableRow>
    </TableHeader>
  )

  // Render table body content
  const renderBody = () => {
    if (isLoading) {
      return <TableSkeleton columnCount={fields.length} />
    }

    if (records.length === 0) {
      return (
        <TableRow className="hover:bg-transparent">
          <TableCell colSpan={fields.length + 2} className="h-24 text-center text-muted-foreground">
            No records found.
          </TableCell>
        </TableRow>
      )
    }

    // Grouped path (slice 3): collapsible header rows with count + numeric sums.
    if (groups) {
      let flatIndex = 0
      return groups.map((group) => {
        const collapsed = collapsedGroups.has(collapseKey(group.key))
        const isEmpty = group.value === null || group.value === undefined || group.value === ''
        const label = isEmpty
          ? '—'
          : (lookupDisplayMap?.[groupBy!]?.[String(group.value)] ?? String(group.value))
        const sums = groupSums(group, fields)
        return (
          <React.Fragment key={`g:${group.key}`}>
            <TableRow
              className="bg-muted/60 hover:bg-muted/60"
              data-testid={`group-header-${group.key}`}
            >
              <TableCell colSpan={fields.length + 2} className="py-1.5">
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
                  <button
                    type="button"
                    className="flex items-center gap-1.5 font-medium"
                    onClick={() => toggleGroup(group.key)}
                    aria-expanded={!collapsed}
                    data-testid={`group-toggle-${group.key}`}
                  >
                    {collapsed ? (
                      <ChevronRight className="h-4 w-4 text-muted-foreground" aria-hidden />
                    ) : (
                      <ChevronDown className="h-4 w-4 text-muted-foreground" aria-hidden />
                    )}
                    <span>{label}</span>
                    <span className="font-normal tabular-nums text-muted-foreground">
                      ({group.records.length})
                    </span>
                  </button>
                  {sums.map(([field, sum]) => (
                    <span
                      key={field.name}
                      className="text-xs tabular-nums text-muted-foreground"
                      data-testid={`group-sum-${group.key}-${field.name}`}
                    >
                      Σ {field.displayName || field.name}:{' '}
                      {sum.toLocaleString(undefined, { maximumFractionDigits: 2 })}
                    </span>
                  ))}
                </div>
              </TableCell>
            </TableRow>
            {!collapsed &&
              group.records.map((record) => {
                const index = flatIndex
                flatIndex += 1
                return (
                  <DataRow
                    key={record.id}
                    record={record}
                    fields={fields}
                    isSelected={selectedIds.has(record.id)}
                    rowProps={getRowProps(index)}
                    tenantSlug={tenantSlug}
                    lookupDisplayMap={lookupDisplayMap}
                    picklistDisplayMaps={picklistDisplayMaps}
                    onRowClick={handleRowClick}
                    onSelectRow={handleSelectRow}
                    onEdit={onEdit}
                    onDelete={onDelete}
                    density={density}
                    stickyFirstColumn={stickyFirstColumn}
                    editable={editable}
                    onCellCommit={onCellCommit}
                  />
                )
              })}
          </React.Fragment>
        )
      })
    }

    // Virtual scrolling path: render only visible rows with spacers
    if (useVirtual) {
      const virtualRows = virtualizer.getVirtualItems()
      const totalSize = virtualizer.getTotalSize()

      return (
        <>
          {/* Top spacer */}
          {virtualRows.length > 0 && virtualRows[0].start > 0 && (
            <tr>
              <td style={{ height: virtualRows[0].start, padding: 0 }} />
            </tr>
          )}

          {/* Visible rows */}
          {virtualRows.map((virtualRow) => {
            const record = records[virtualRow.index]
            const isSelected = selectedIds.has(record.id)
            const rowPropsForIndex = getRowProps(virtualRow.index)
            return (
              <DataRow
                key={record.id}
                record={record}
                fields={fields}
                isSelected={isSelected}
                rowProps={rowPropsForIndex}
                tenantSlug={tenantSlug}
                lookupDisplayMap={lookupDisplayMap}
                picklistDisplayMaps={picklistDisplayMaps}
                onRowClick={handleRowClick}
                onSelectRow={handleSelectRow}
                onEdit={onEdit}
                onDelete={onDelete}
                density={density}
                stickyFirstColumn={stickyFirstColumn}
              />
            )
          })}

          {/* Bottom spacer */}
          {virtualRows.length > 0 && virtualRows[virtualRows.length - 1].end < totalSize && (
            <tr>
              <td
                style={{
                  height: totalSize - virtualRows[virtualRows.length - 1].end,
                  padding: 0,
                }}
              />
            </tr>
          )}
        </>
      )
    }

    // Normal rendering path (< VIRTUAL_THRESHOLD rows)
    return records.map((record, index) => {
      const isSelected = selectedIds.has(record.id)
      const rowPropsForIndex = getRowProps(index)
      return (
        <DataRow
          key={record.id}
          record={record}
          fields={fields}
          isSelected={isSelected}
          rowProps={rowPropsForIndex}
          tenantSlug={tenantSlug}
          lookupDisplayMap={lookupDisplayMap}
          picklistDisplayMaps={picklistDisplayMaps}
          onRowClick={handleRowClick}
          onSelectRow={handleSelectRow}
          onEdit={onEdit}
          onDelete={onDelete}
          density={density}
          stickyFirstColumn={stickyFirstColumn}
          editable={editable}
          onCellCommit={onCellCommit}
        />
      )
    })
  }

  // For virtual scrolling, wrap in a scrollable container with fixed height
  if (useVirtual) {
    return (
      <div
        className="overflow-hidden rounded-[10px] border border-border bg-card"
        ref={tableRef}
        onKeyDown={handleKeyDown}
        role="grid"
        tabIndex={0}
        aria-label={`${collectionName} records`}
        data-testid="data-table"
      >
        <Table>{tableHeader}</Table>
        <div
          ref={scrollContainerRef}
          className="overflow-auto"
          style={{ maxHeight: VIRTUAL_MAX_HEIGHT }}
        >
          <Table>
            <TableBody>{renderBody()}</TableBody>
          </Table>
        </div>
      </div>
    )
  }

  // Standard rendering (no virtual scrolling)
  return (
    <div
      className="rounded-md border"
      ref={tableRef}
      onKeyDown={handleKeyDown}
      role="grid"
      tabIndex={0}
      aria-label={`${collectionName} records`}
      data-testid="data-table"
    >
      <Table>
        {tableHeader}
        <TableBody>{renderBody()}</TableBody>
      </Table>
    </div>
  )
}
