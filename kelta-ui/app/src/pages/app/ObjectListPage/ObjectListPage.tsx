/**
 * ObjectListPage
 *
 * Displays a paginated, sortable, selectable list of records for a given collection.
 * Driven by the collection schema with field type-aware rendering.
 *
 * Features:
 * - Sortable column headers
 * - Row selection with bulk actions
 * - Field type-aware rendering (FieldRenderer)
 * - Pagination with page size control
 * - Row click navigation to record detail
 * - Row action menu (View, Edit, Delete)
 * - CSV/JSON export
 * - Quick actions menu for list-scoped operations
 * - Breadcrumb navigation
 */

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react'
import { useNavigate, useParams, useSearchParams, Link } from 'react-router-dom'
import { useCollectionStore } from '@/context/CollectionStoreContext'
import {
  Loader2,
  AlertCircle,
  Rows3,
  Layers,
  ChevronDown,
  Table2,
  SquareKanban,
  CalendarDays,
  LayoutGrid,
} from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import { useCollectionRecords } from '@/hooks/useCollectionRecords'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useCollectionPermissions } from '@/hooks/useCollectionPermissions'
import { useSystemPermissions } from '@/hooks/useSystemPermissions'
import { useApi } from '@/context/ApiContext'
import { getFieldControl } from '@/components/fieldControl'
import { runBulkUpdate } from '@/utils/bulkUpdate'
import { MassEditDialog } from '@/components/RelatedList/MassEditDialog'
import { buildIncludedDisplayMap } from '@/utils/jsonapi'
import { REFERENCE_FIELD_TYPES, referenceIncludeParam } from './listIncludes'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'
import { buildSortParam, parseListViewParams } from './listUrlState'
import { ColumnChooser } from '@/components/ColumnChooser/ColumnChooser'
import { KanbanBoard } from '@/components/KanbanBoard'
import { CalendarMonthView, currentMonthKey, monthRange } from '@/components/CalendarMonthView'
import { GalleryGrid } from '@/components/GalleryGrid'
import { usePicklistOptions, usePicklistDisplayMap } from '@/hooks/usePicklistOptions'
import { viewSorts, type SavedViewDensity, type SavedViewType } from '@/hooks/useSavedViews'
import { ListShell } from '@/components/record/ListShell'
import { ObjectDataTable } from '@/components/ObjectDataTable/ObjectDataTable'
import { DataTablePagination } from '@/components/ObjectDataTable/DataTablePagination'
import { ListViewToolbar } from '@/components/ListViewToolbar'
import { CsvImportDialog } from '@/components/CsvImportDialog/CsvImportDialog'
import { FilterBar } from '@/components/FilterBar'
import { ViewSelector } from '@/components/ViewSelector/ViewSelector'
import { useSavedViews, type SavedView } from '@/hooks/useSavedViews'
import { useSharedListViews } from '@/hooks/useSharedListViews'
import { isSharedViewId, orderFieldsByView } from './listViewMapping'
import { toast } from 'sonner'
import { useI18n } from '@/context/I18nContext'
import { InsufficientPrivileges } from '@/components/InsufficientPrivileges'
import { QuickActionsMenu } from '@/components/QuickActions'
import { useAnnounce } from '@/components/LiveRegion'
import type { QuickActionExecutionContext } from '@/types/quickActions'

/**
 * Escape a value for CSV format.
 */
function escapeCSVValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  const str = typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
    return `"${str.replace(/"/g, '""')}"`
  }
  return str
}

/**
 * Download a string as a file.
 */
function downloadFile(content: string, filename: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

// URL state helpers (parseFilters/parseListViewParams) live in listUrlState.ts,
// shared with dashboard drill-through and saved views.

/** Renderer switcher entries (slices 5-7); labels come from `altViews.<type>` keys. */
const VIEW_TYPE_META: Array<{
  type: SavedViewType
  icon: React.ComponentType<{ className?: string }>
}> = [
  { type: 'table', icon: Table2 },
  { type: 'kanban', icon: SquareKanban },
  { type: 'calendar', icon: CalendarDays },
  { type: 'gallery', icon: LayoutGrid },
]

export function ObjectListPage(): React.ReactElement {
  const { tenantSlug, collection: collectionName } = useParams<{
    tenantSlug: string
    collection: string
  }>()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const collectionStore = useCollectionStore()
  const { t } = useI18n()
  const basePath = `/${tenantSlug}/app`

  // Parse list state from URL params (deep linking support)
  const { page, pageSize, sort, sorts, filters, viewId } = useMemo(
    () => parseListViewParams(searchParams),
    [searchParams]
  )

  // Selection state (local only — not persisted in URL)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())

  // Delete confirmation state
  const [deleteTarget, setDeleteTarget] = useState<CollectionRecord | null>(null)
  const [showBulkDeleteDialog, setShowBulkDeleteDialog] = useState(false)

  // Import dialog state
  const [showImportDialog, setShowImportDialog] = useState(false)

  // Fetch collection schema
  const {
    schema,
    fields,
    isLoading: schemaLoading,
    error: schemaError,
  } = useCollectionSchema(collectionName)

  // Fetch permissions (combined object + field in one call)
  const {
    permissions,
    isFieldVisible,
    isLoading: permissionsLoading,
  } = useCollectionPermissions(collectionName)

  // Mass edit posts to /api/bulk-jobs, which requires the MANAGE_DATA system
  // permission (in-controller gate) — hide the affordance without it.
  const { hasPermission } = useSystemPermissions()
  const { apiClient } = useApi()

  // Determine visible fields (first 6 fields by default, excluding system and hidden fields)
  // Saved views: personal (localStorage, the admin ResourceListPage mechanism) + shared
  // (admin-authored PUBLIC `list-views` rows, read-only with a `shared:` id prefix).
  const savedViews = useSavedViews(collectionName || '')
  const { sharedViews } = useSharedListViews(collectionName, schema?.id)
  const allViews = useMemo(
    () => [...sharedViews, ...savedViews.views],
    [sharedViews, savedViews.views]
  )
  const [activeViewId, setActiveViewId] = useState<string | null>(null)
  const activeView = useMemo(
    () => allViews.find((v) => v.id === activeViewId) ?? null,
    [allViews, activeViewId]
  )
  // Ad-hoc column override from the chooser (wins over the active view until saved).
  const [columnOverride, setColumnOverride] = useState<string[] | null>(null)
  const [density, setDensity] = useState<SavedViewDensity>('normal')
  // This-page grouping field (slice 3) — from the active view or the toolbar picker.
  const [groupBy, setGroupBy] = useState<string | null>(null)
  // Mass edit (slice 4)
  const [massEditOpen, setMassEditOpen] = useState(false)
  // Renderer + per-type config (slices 5-7) — from the active view or the toolbar pickers.
  const [viewType, setViewType] = useState<SavedViewType>('table')
  const [typeConfig, setTypeConfig] = useState<SavedView['typeConfig'] | null>(null)
  // Visible calendar month ('YYYY-MM'); drives the calendar range filter.
  const [calendarMonth, setCalendarMonth] = useState<string>(() => currentMonthKey())

  // Accessible (non-system, FLS-visible) fields — feeds the column chooser and
  // the group-by picker.
  const accessibleFields = useMemo(
    () =>
      fields
        .filter((f) => !['createdAt', 'updatedAt', 'createdBy', 'updatedBy'].includes(f.name))
        .filter((f) => isFieldVisible(f.name)),
    [fields, isFieldVisible]
  )

  const visibleFields = useMemo(() => {
    if (!accessibleFields.length) return []
    // Precedence: chooser override > active view's column list > default first-6 rule.
    const fromOverride = columnOverride ? orderFieldsByView(accessibleFields, columnOverride) : null
    const fromView =
      !fromOverride && activeView
        ? orderFieldsByView(accessibleFields, activeView.visibleColumns)
        : null
    return fromOverride ?? fromView ?? accessibleFields.slice(0, 6)
  }, [accessibleFields, activeView, columnOverride])

  // Alt-view field resolution (slices 5-7): explicit typeConfig wins, else the
  // first accessible field of the right type; none ⇒ the renderer shows an
  // inline empty-state instead of crashing.
  const kanbanLaneField = useMemo(() => {
    const configured = typeConfig?.kanban?.laneField
    const picklists = accessibleFields.filter((f) => f.type === 'picklist')
    return (configured && picklists.find((f) => f.name === configured)) || picklists[0] || null
  }, [typeConfig, accessibleFields])
  const calendarDateField = useMemo(() => {
    const configured = typeConfig?.calendar?.dateField
    const dateFields = accessibleFields.filter((f) => f.type === 'date' || f.type === 'datetime')
    return (configured && dateFields.find((f) => f.name === configured)) || dateFields[0] || null
  }, [typeConfig, accessibleFields])
  const galleryImageField = useMemo(() => {
    const configured = typeConfig?.gallery?.imageField
    if (!configured) return undefined
    return accessibleFields.find((f) => f.name === configured && f.type === 'url')
  }, [typeConfig, accessibleFields])
  // Card/chip title: gallery override > the schema display field > 'name'.
  const titleField = typeConfig?.gallery?.titleField || schema?.displayFieldName || 'name'
  // Kanban card body: configured names, else the first 3 visible non-title columns.
  const kanbanCardFields = useMemo(() => {
    const configured = typeConfig?.kanban?.cardFields
    const source = configured
      ? configured
          .map((name) => accessibleFields.find((f) => f.name === name))
          .filter((f): f is (typeof accessibleFields)[number] => Boolean(f))
      : visibleFields.filter((f) => f.name !== titleField)
    return source.slice(0, 3)
  }, [typeConfig, accessibleFields, visibleFields, titleField])
  // Lanes come from the lane field's picklist values (fetched only in kanban view).
  const { options: laneOptions } = usePicklistOptions(
    kanbanLaneField ?? undefined,
    viewType === 'kanban'
  )
  const { displayMap: laneDisplayMap } = usePicklistDisplayMap(
    kanbanLaneField ?? undefined,
    viewType === 'kanban'
  )

  // Fields offered by the mass-edit picker (same rule as RelatedList): user-editable
  // schema fields only (excludes id, system audit fields, server-computed types).
  const massEditableFields = useMemo(
    () =>
      fields.filter(
        (f) =>
          f.name !== 'id' &&
          !['createdAt', 'updatedAt', 'createdBy', 'updatedBy'].includes(f.name) &&
          getFieldControl(f.type).editable
      ),
    [fields]
  )
  const canMassEdit =
    permissions.canEdit && hasPermission('MANAGE_DATA') && massEditableFields.length > 0

  // Identify reference fields in visible columns that need included resources
  const referenceFields = useMemo(
    () => visibleFields.filter((f) => REFERENCE_FIELD_TYPES.has(f.type) && f.referenceTarget),
    [visibleFields]
  )

  // Build the JSON:API `include` param from the reference FIELD names (see referenceIncludeParam).
  const includeParam = useMemo(() => referenceIncludeParam(referenceFields), [referenceFields])

  // Grouping prepends the group field to the server sort so buckets arrive
  // contiguous; the user's own sort levels order rows within groups. The URL
  // `sort=` param is left untouched.
  const effectiveSorts = useMemo(() => {
    if (!groupBy) return sorts
    return [
      { field: groupBy, direction: 'asc' as const },
      ...sorts.filter((s) => s.field !== groupBy),
    ]
  }, [groupBy, sorts])

  // Calendar merges the visible month as gte/lte conditions on the date field —
  // view state, not user chips (FilterBar keeps showing only `filters`).
  const effectiveFilters = useMemo(() => {
    if (viewType !== 'calendar' || !calendarDateField) return filters
    const range = monthRange(calendarMonth)
    return [
      ...filters,
      {
        id: '__calendar_gte',
        field: calendarDateField.name,
        operator: 'greater_than_or_equal' as const,
        value: range.gte,
      },
      {
        id: '__calendar_lte',
        field: calendarDateField.name,
        operator: 'less_than_or_equal' as const,
        value: range.lte,
      },
    ]
  }, [filters, viewType, calendarDateField, calendarMonth])

  // Fetch records with includes for reference fields. Calendar fetches up to the
  // HTTP clamp so a month isn't truncated at the table's page size.
  const {
    data: records,
    total,
    isLoading: recordsLoading,
    error: recordsError,
    refetch,
    rawResponse,
  } = useCollectionRecords({
    collectionName,
    page: viewType === 'calendar' ? 1 : page,
    pageSize: viewType === 'calendar' ? 200 : pageSize,
    sort: effectiveSorts.length > 0 ? effectiveSorts : undefined,
    filters: effectiveFilters.length > 0 ? effectiveFilters : undefined,
    enabled: !!schema,
    include: includeParam,
  })

  // Build lookup display map from included resources using centralized collection store
  const lookupDisplayMap = useMemo(() => {
    if (!rawResponse || referenceFields.length === 0) return undefined

    const map: Record<string, Record<string, string>> = {}

    referenceFields.forEach((field) => {
      const targetType = field.referenceTarget!
      const refSchema = collectionStore.getCollectionByName(targetType)
      const displayField = refSchema?.displayFieldName || 'name'

      const fieldMap = buildIncludedDisplayMap(rawResponse, targetType, displayField)
      if (Object.keys(fieldMap).length > 0) {
        map[field.name] = fieldMap
      }
    })

    return Object.keys(map).length > 0 ? map : undefined
  }, [rawResponse, referenceFields, collectionStore])

  // Screen reader announcements for dynamic state changes
  const { announce } = useAnnounce()
  const prevRecordsLoadingRef = useRef(recordsLoading)

  useEffect(() => {
    // Announce when records finish loading
    if (prevRecordsLoadingRef.current && !recordsLoading) {
      if (recordsError) {
        announce('Error loading records', 'assertive')
      } else {
        announce(`Loaded ${records.length} of ${total} records`)
      }
    }
    prevRecordsLoadingRef.current = recordsLoading
  }, [recordsLoading, records.length, total, recordsError, announce])

  // Mutations. No shared onSuccess: the hook fires it for EVERY mutation (inline cell
  // patch included), which would falsely announce "Record deleted" and clear the row
  // selection on each cell edit. Delete side effects live in the delete confirm handlers.
  const mutations = useRecordMutation({
    collectionName: collectionName || '',
  })

  // Shared post-delete cleanup for single + bulk delete.
  const afterDeleteSuccess = useCallback(() => {
    setDeleteTarget(null)
    setShowBulkDeleteDialog(false)
    setSelectedIds(new Set())
    announce('Record deleted successfully')
  }, [announce])

  // In-place cell edit in the list grid (unified record experience, slice 3).
  const handleCellCommit = useCallback(
    async (recordId: string, fieldName: string, value: unknown): Promise<void> => {
      await mutations.patch.mutateAsync({ id: recordId, data: { [fieldName]: value } })
      await refetch()
    },
    [mutations.patch, refetch]
  )

  /**
   * Mass edit (slice 4): apply one field value to every selected row via the
   * shared bulk-jobs flow. Throws on failure so MassEditDialog stays open with
   * the error inline; toasts link to the Bulk Jobs page (same MANAGE_DATA gate).
   */
  const handleMassEditSubmit = useCallback(
    async (fieldName: string, value: unknown): Promise<void> => {
      const collectionId = schema?.id
      if (!collectionId) throw new Error('Collection schema is not loaded yet')
      const ids = Array.from(selectedIds)
      const { status, successRecords, errorRecords } = await runBulkUpdate(
        apiClient,
        collectionId,
        ids.map((id) => ({ id, [fieldName]: value }))
      )
      const viewJobs = {
        label: t('massEdit.viewJobs', 'View jobs'),
        onClick: () => navigate(`/${tenantSlug}/bulk-jobs`),
      }
      if (status !== 'COMPLETED') {
        const message = status
          ? t('massEdit.terminal', { status: status.toLowerCase() })
          : t('massEdit.stillRunning', 'Bulk update is still running — check the Bulk Jobs page')
        toast.error(message, { action: viewJobs })
        throw new Error(message)
      }
      if (errorRecords > 0) {
        toast.error(
          t('massEdit.partial', {
            success: successRecords,
            total: ids.length,
            failed: errorRecords,
          }),
          { action: viewJobs }
        )
      } else {
        toast.success(t('massEdit.success', { success: successRecords, total: ids.length }))
      }
      setSelectedIds(new Set())
      await refetch()
    },
    [schema, selectedIds, apiClient, t, navigate, tenantSlug, refetch]
  )

  // Alt views navigate on card/chip click (same route as a table row).
  const handleRecordNavigate = useCallback(
    (record: CollectionRecord) => {
      navigate(`${basePath}/o/${collectionName}/${record.id}`)
    },
    [navigate, basePath, collectionName]
  )

  /**
   * Kanban drop (slice 5): fetch a fresh ETag, PATCH the lane field with
   * If-Match. Any rejection (409 stale write, validation) toasts and refetches;
   * the board reverts its optimistic move on the rejection.
   */
  const handleMoveCard = useCallback(
    async (recordId: string, lane: string | null): Promise<void> => {
      const laneName = kanbanLaneField?.name
      if (!laneName) return
      try {
        const { etag } = await apiClient.getWithMeta(`/api/${collectionName}/${recordId}`)
        await mutations.patch.mutateAsync({
          id: recordId,
          data: { [laneName]: lane },
          ifMatch: etag,
        })
      } catch (e) {
        toast.error(
          t('altViews.moveFailed', 'Move failed — the record changed or the value was rejected')
        )
        await refetch()
        throw e instanceof Error ? e : new Error('Move failed')
      }
      await refetch()
    },
    [kanbanLaneField, apiClient, collectionName, mutations.patch, refetch, t]
  )

  // Collection label
  const collectionLabel =
    schema?.displayName ||
    (collectionName ? collectionName.charAt(0).toUpperCase() + collectionName.slice(1) : 'Objects')

  /**
   * Update URL search params, preserving existing params and removing defaults.
   */
  const updateParams = useCallback(
    (updates: Record<string, string | undefined>) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          for (const [key, value] of Object.entries(updates)) {
            if (value === undefined) {
              next.delete(key)
            } else {
              next.set(key, value)
            }
          }
          // Remove defaults to keep URLs clean
          if (next.get('page') === '1') next.delete('page')
          if (next.get('pageSize') === '25') next.delete('pageSize')
          return next
        },
        { replace: true }
      )
    },
    [setSearchParams]
  )

  /** Applies a saved view onto the URL state (deep links keep working); null clears it. */
  const applyView = useCallback(
    (view: SavedView | null) => {
      setActiveViewId(view?.id ?? null)
      setColumnOverride(null)
      setDensity(view?.density ?? 'normal')
      setGroupBy(view?.groupBy ?? null)
      setViewType(view?.viewType ?? 'table')
      setTypeConfig(view?.typeConfig ?? null)
      if (view) {
        updateParams({
          filter: view.filters.length > 0 ? JSON.stringify(view.filters) : undefined,
          sort: buildSortParam(viewSorts(view)),
          pageSize: String(view.pageSize),
          page: undefined,
          view: view.id,
        })
      } else {
        updateParams({
          filter: undefined,
          sort: undefined,
          pageSize: undefined,
          page: undefined,
          view: undefined,
        })
      }
    },
    [updateParams]
  )

  const handleSelectView = useCallback(
    (viewId: string | null) => {
      applyView(viewId ? (allViews.find((v) => v.id === viewId) ?? null) : null)
    },
    [applyView, allViews]
  )

  const handleSaveView = useCallback(
    (name: string) => {
      savedViews.saveView(name, {
        filters,
        sortField: sort?.field ?? null,
        sortDirection: sort?.direction ?? 'asc',
        sorts,
        density,
        groupBy,
        viewType,
        typeConfig: typeConfig ?? undefined,
        visibleColumns: visibleFields.map((f) => f.name),
        pageSize,
        isDefault: false,
      })
      setColumnOverride(null)
    },
    [
      savedViews,
      filters,
      sort,
      sorts,
      density,
      groupBy,
      viewType,
      typeConfig,
      visibleFields,
      pageSize,
    ]
  )

  const rejectSharedViewEdit = useCallback(() => {
    toast.error(t('savedViews.sharedReadOnly', 'Shared views are managed in Setup → List Views'))
  }, [t])

  const handleDeleteView = useCallback(
    (viewId: string) => {
      if (isSharedViewId(viewId)) return rejectSharedViewEdit()
      if (activeViewId === viewId) setActiveViewId(null)
      savedViews.deleteView(viewId)
    },
    [savedViews, activeViewId, rejectSharedViewEdit]
  )

  const handleRenameView = useCallback(
    (viewId: string, newName: string) => {
      if (isSharedViewId(viewId)) return rejectSharedViewEdit()
      savedViews.renameView(viewId, newName)
    },
    [savedViews, rejectSharedViewEdit]
  )

  const handleSetDefaultView = useCallback(
    (viewId: string) => {
      if (isSharedViewId(viewId)) return rejectSharedViewEdit()
      savedViews.setDefaultView(viewId)
    },
    [savedViews, rejectSharedViewEdit]
  )

  // Auto-apply the default view on a clean first visit — explicit URL state always wins.
  // A `view=<id>` deep link selects that view (its state params are already in the URL
  // when shared from an applied view; selecting keeps columns/density in sync).
  const appliedDefaultRef = useRef(false)
  useEffect(() => {
    if (appliedDefaultRef.current) return
    if (viewId) {
      const linked = allViews.find((v) => v.id === viewId)
      if (linked) {
        appliedDefaultRef.current = true
        // Deferred: no synchronous setState inside the effect (cascading-render rule).
        const timer = setTimeout(() => {
          setActiveViewId(linked.id)
          setDensity(linked.density ?? 'normal')
          setGroupBy(linked.groupBy ?? null)
          setViewType(linked.viewType ?? 'table')
          setTypeConfig(linked.typeConfig ?? null)
        }, 0)
        return () => clearTimeout(timer)
      }
      return
    }
    const urlHasState = ['page', 'pageSize', 'sort', 'filter'].some((k) => searchParams.has(k))
    if (urlHasState) {
      appliedDefaultRef.current = true
      return
    }
    const personalDefault = savedViews.views.find((v) => v.isDefault)
    const sharedDefault = sharedViews.find((v) => v.isDefault)
    const def = personalDefault ?? sharedDefault
    if (def) {
      appliedDefaultRef.current = true
      // Deferred so the apply (setState + URL write) never runs synchronously inside
      // the effect — avoids a cascading render on first mount.
      const timer = setTimeout(() => applyView(def), 0)
      return () => clearTimeout(timer)
    }
  }, [savedViews.views, sharedViews, searchParams, applyView, viewId, allViews])

  // Sort handler. Plain click: single-level cycle asc → desc → none. Shift-click:
  // additive — appends the field as a new level, or cycles/removes its existing level
  // while keeping the others (server accepts the comma grammar natively).
  const handleSortChange = useCallback(
    (field: string, additive?: boolean) => {
      if (!additive) {
        let newSort: string | undefined
        if (sorts.length === 1 && sorts[0].field === field) {
          newSort = sorts[0].direction === 'asc' ? `-${field}` : undefined
        } else {
          newSort = field
        }
        updateParams({ sort: newSort, page: undefined })
        return
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
      updateParams({ sort: buildSortParam(next), page: undefined })
    },
    [sorts, updateParams]
  )

  // Page change handler
  const handlePageChange = useCallback(
    (newPage: number) => {
      updateParams({ page: newPage > 1 ? String(newPage) : undefined })
      setSelectedIds(new Set())
    },
    [updateParams]
  )

  // Page size change handler
  const handlePageSizeChange = useCallback(
    (newPageSize: number) => {
      updateParams({
        pageSize: newPageSize !== 25 ? String(newPageSize) : undefined,
        page: undefined,
      })
      setSelectedIds(new Set())
    },
    [updateParams]
  )

  // Navigation handlers
  const handleNew = useCallback(() => {
    navigate(`${basePath}/o/${collectionName}/new`)
  }, [navigate, basePath, collectionName])

  const handleEdit = useCallback(
    (record: CollectionRecord) => {
      navigate(`${basePath}/o/${collectionName}/${record.id}/edit`)
    },
    [navigate, basePath, collectionName]
  )

  // Delete handlers
  const handleDeleteClick = useCallback((record: CollectionRecord) => {
    setDeleteTarget(record)
  }, [])

  const handleDeleteConfirm = useCallback(() => {
    if (deleteTarget) {
      mutations.remove
        .mutateAsync(deleteTarget.id)
        .then(afterDeleteSuccess)
        .catch(() => {
          // Error state is surfaced by the mutation; keep the dialog open.
        })
    }
  }, [deleteTarget, mutations.remove, afterDeleteSuccess])

  const handleBulkDeleteClick = useCallback(() => {
    if (selectedIds.size > 0) {
      setShowBulkDeleteDialog(true)
    }
  }, [selectedIds])

  const handleBulkDeleteConfirm = useCallback(() => {
    mutations.bulkDelete
      .mutateAsync(Array.from(selectedIds))
      .then(afterDeleteSuccess)
      .catch(() => {
        // Error state is surfaced by the mutation; keep the dialog open.
      })
  }, [mutations.bulkDelete, selectedIds, afterDeleteSuccess])

  // Export handlers
  const handleExportCsv = useCallback(() => {
    if (!records.length || !visibleFields.length) return
    const exportRecords =
      selectedIds.size > 0 ? records.filter((r) => selectedIds.has(r.id)) : records
    const headers = visibleFields.map((f) => f.displayName || f.name)
    const rows = [headers.map(escapeCSVValue).join(',')]
    for (const record of exportRecords) {
      const values = visibleFields.map((f) => escapeCSVValue(record[f.name]))
      rows.push(values.join(','))
    }
    const timestamp = new Date().toISOString().slice(0, 10)
    downloadFile(rows.join('\n'), `${collectionName}-${timestamp}.csv`, 'text/csv;charset=utf-8')
  }, [records, visibleFields, selectedIds, collectionName])

  const handleExportJson = useCallback(() => {
    if (!records.length) return
    const exportRecords =
      selectedIds.size > 0 ? records.filter((r) => selectedIds.has(r.id)) : records
    const timestamp = new Date().toISOString().slice(0, 10)
    downloadFile(
      JSON.stringify(exportRecords, null, 2),
      `${collectionName}-${timestamp}.json`,
      'application/json'
    )
  }, [records, selectedIds, collectionName])

  const handleClearSelection = useCallback(() => {
    setSelectedIds(new Set())
  }, [])

  // Filter handlers
  const handleRemoveFilter = useCallback(
    (filterId: string) => {
      const remaining = filters.filter((f) => f.id !== filterId)
      updateParams({
        filter: remaining.length > 0 ? JSON.stringify(remaining) : undefined,
        page: undefined,
      })
    },
    [filters, updateParams]
  )

  const handleClearAllFilters = useCallback(() => {
    updateParams({ filter: undefined, page: undefined })
  }, [updateParams])

  // Quick action execution context for list-scoped actions
  const quickActionContext = useMemo<QuickActionExecutionContext>(
    () => ({
      collectionName: collectionName || '',
      selectedIds: Array.from(selectedIds),
      tenantSlug: tenantSlug || '',
    }),
    [collectionName, selectedIds, tenantSlug]
  )

  // Status branch (permission gate / schema error), rendered by the shell in
  // place of the list frame. Order matches the legacy early-returns.
  const statusSlot: React.ReactNode = !permissions.canRead ? (
    <InsufficientPrivileges
      action="view"
      resource={collectionLabel}
      backPath={`${basePath}/home`}
    />
  ) : schemaError ? (
    <div className="space-y-4 p-6">
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>Error loading collection</AlertTitle>
        <AlertDescription>
          {schemaError.message || 'Failed to load collection schema.'}
        </AlertDescription>
      </Alert>
      <Button variant="outline" onClick={() => refetch()}>
        Retry
      </Button>
    </div>
  ) : null

  return (
    <ListShell
      variant="enduser"
      isLoading={schemaLoading || permissionsLoading}
      statusSlot={statusSlot}
      breadcrumb={
        <Breadcrumb>
          <BreadcrumbList>
            <BreadcrumbItem>
              <BreadcrumbLink asChild>
                <Link to={`${basePath}/home`}>Home</Link>
              </BreadcrumbLink>
            </BreadcrumbItem>
            <BreadcrumbSeparator />
            <BreadcrumbItem>
              <BreadcrumbPage>{collectionLabel}</BreadcrumbPage>
            </BreadcrumbItem>
          </BreadcrumbList>
        </Breadcrumb>
      }
      toolbar={
        <div className="flex flex-col gap-2">
          <ListViewToolbar
            collectionLabel={collectionLabel}
            selectedCount={selectedIds.size}
            totalCount={total}
            onNew={handleNew}
            onBulkDelete={handleBulkDeleteClick}
            onExportCsv={handleExportCsv}
            onExportJson={handleExportJson}
            onImportCsv={permissions.canCreate ? () => setShowImportDialog(true) : undefined}
            onClearSelection={handleClearSelection}
            onMassEdit={canMassEdit ? () => setMassEditOpen(true) : undefined}
            canCreate={permissions.canCreate}
            canDelete={permissions.canDelete}
            quickActionsSlot={
              <QuickActionsMenu
                collectionName={collectionName || ''}
                context="list"
                executionContext={quickActionContext}
              />
            }
          />
          <div className="flex flex-wrap items-center gap-2">
            <ViewSelector
              views={allViews}
              activeView={activeView}
              onSelectView={handleSelectView}
              onSaveView={handleSaveView}
              onDeleteView={handleDeleteView}
              onRenameView={handleRenameView}
              onSetDefault={handleSetDefaultView}
            />
            <ColumnChooser
              fields={accessibleFields.map((f) => ({ name: f.name, displayName: f.displayName }))}
              visibleColumns={visibleFields.map((f) => f.name)}
              onChange={setColumnOverride}
            />
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                setDensity((d) =>
                  d === 'normal' ? 'compact' : d === 'compact' ? 'comfortable' : 'normal'
                )
              }
              data-testid="density-toggle"
              aria-label={t('listPower.density', 'Row density')}
            >
              <Rows3 className="mr-1.5 h-4 w-4" aria-hidden />
              {density === 'compact'
                ? t('listPower.compact', 'Compact')
                : density === 'comfortable'
                  ? t('listPower.comfortable', 'Comfortable')
                  : t('listPower.normal', 'Normal')}
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  data-testid="group-by-trigger"
                  aria-label={t('listPower.groupBy', 'Group by')}
                >
                  <Layers className="mr-1.5 h-4 w-4" aria-hidden />
                  {groupBy
                    ? (fields.find((f) => f.name === groupBy)?.displayName ?? groupBy)
                    : t('listPower.groupBy', 'Group by')}
                  <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuItem onClick={() => setGroupBy(null)} data-testid="group-by-none">
                  {t('listPower.noGrouping', 'No grouping')}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                {accessibleFields.map((f) => (
                  <DropdownMenuItem
                    key={f.name}
                    onClick={() => setGroupBy(f.name)}
                    data-testid={`group-by-${f.name}`}
                  >
                    {f.displayName || f.name}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
            {/* Renderer switcher (slices 5-7) */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  data-testid="view-type-trigger"
                  aria-label={t('altViews.viewType', 'View type')}
                >
                  {(() => {
                    const meta =
                      VIEW_TYPE_META.find((m) => m.type === viewType) ?? VIEW_TYPE_META[0]
                    const Icon = meta.icon
                    return (
                      <>
                        <Icon className="mr-1.5 h-4 w-4" aria-hidden />
                        {t(`altViews.${meta.type}`, meta.type)}
                        <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                      </>
                    )
                  })()}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {VIEW_TYPE_META.map(({ type, icon: Icon }) => (
                  <DropdownMenuItem
                    key={type}
                    onClick={() => setViewType(type)}
                    data-testid={`view-type-${type}`}
                  >
                    <Icon className="mr-2 h-4 w-4" aria-hidden />
                    {t(`altViews.${type}`, type)}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
            {/* Per-type config pickers */}
            {viewType === 'kanban' && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" size="sm" data-testid="lane-field-trigger">
                    {t('altViews.laneField', 'Lane')}:{' '}
                    {kanbanLaneField ? kanbanLaneField.displayName || kanbanLaneField.name : '—'}
                    <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {accessibleFields
                    .filter((f) => f.type === 'picklist')
                    .map((f) => (
                      <DropdownMenuItem
                        key={f.name}
                        onClick={() =>
                          setTypeConfig((prev) => ({ ...prev, kanban: { laneField: f.name } }))
                        }
                        data-testid={`lane-field-${f.name}`}
                      >
                        {f.displayName || f.name}
                      </DropdownMenuItem>
                    ))}
                </DropdownMenuContent>
              </DropdownMenu>
            )}
            {viewType === 'calendar' && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" size="sm" data-testid="date-field-trigger">
                    {t('altViews.dateField', 'Date')}:{' '}
                    {calendarDateField
                      ? calendarDateField.displayName || calendarDateField.name
                      : '—'}
                    <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {accessibleFields
                    .filter((f) => f.type === 'date' || f.type === 'datetime')
                    .map((f) => (
                      <DropdownMenuItem
                        key={f.name}
                        onClick={() =>
                          setTypeConfig((prev) => ({ ...prev, calendar: { dateField: f.name } }))
                        }
                        data-testid={`date-field-${f.name}`}
                      >
                        {f.displayName || f.name}
                      </DropdownMenuItem>
                    ))}
                </DropdownMenuContent>
              </DropdownMenu>
            )}
            {viewType === 'gallery' && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" size="sm" data-testid="image-field-trigger">
                    {t('altViews.imageField', 'Image')}:{' '}
                    {galleryImageField
                      ? galleryImageField.displayName || galleryImageField.name
                      : t('altViews.none', 'None')}
                    <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  <DropdownMenuItem
                    onClick={() =>
                      setTypeConfig((prev) => {
                        const next = { ...prev }
                        delete next.gallery
                        return next
                      })
                    }
                    data-testid="image-field-none"
                  >
                    {t('altViews.none', 'None')}
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  {accessibleFields
                    .filter((f) => f.type === 'url')
                    .map((f) => (
                      <DropdownMenuItem
                        key={f.name}
                        onClick={() =>
                          setTypeConfig((prev) => ({ ...prev, gallery: { imageField: f.name } }))
                        }
                        data-testid={`image-field-${f.name}`}
                      >
                        {f.displayName || f.name}
                      </DropdownMenuItem>
                    ))}
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </div>
        </div>
      }
      filters={
        <>
          {/* Active filters bar */}
          <FilterBar
            filters={filters}
            onRemoveFilter={handleRemoveFilter}
            onClearAll={handleClearAllFilters}
          />

          {/* Error state for records */}
          {recordsError && (
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle>Error loading records</AlertTitle>
              <AlertDescription>
                {recordsError.message || 'Failed to load records.'}
              </AlertDescription>
            </Alert>
          )}
        </>
      }
      table={
        viewType === 'kanban' ? (
          kanbanLaneField ? (
            <KanbanBoard
              records={records}
              laneField={kanbanLaneField}
              laneOptions={laneOptions}
              titleField={titleField}
              cardFields={kanbanCardFields}
              canEdit={permissions.canEdit}
              onCardClick={handleRecordNavigate}
              onMoveCard={handleMoveCard}
              tenantSlug={tenantSlug}
              lookupDisplayMap={lookupDisplayMap}
              laneDisplayMap={laneDisplayMap}
            />
          ) : (
            <div
              className="rounded-[10px] border border-border bg-card p-8 text-center text-sm text-muted-foreground"
              data-testid="kanban-empty-state"
            >
              {t('altViews.noLaneField', 'Kanban needs a picklist field to define lanes.')}
            </div>
          )
        ) : viewType === 'calendar' ? (
          calendarDateField ? (
            <>
              <CalendarMonthView
                records={records}
                dateField={calendarDateField}
                titleField={titleField}
                month={calendarMonth}
                onMonthChange={setCalendarMonth}
                onRecordClick={handleRecordNavigate}
              />
              {total > 200 && (
                <p className="px-1 pt-1 text-xs text-muted-foreground" data-testid="calendar-clamp">
                  {t('altViews.showingFirst', { count: 200, total })}
                </p>
              )}
            </>
          ) : (
            <div
              className="rounded-[10px] border border-border bg-card p-8 text-center text-sm text-muted-foreground"
              data-testid="calendar-empty-state"
            >
              {t('altViews.noDateField', 'Calendar needs a date or datetime field.')}
            </div>
          )
        ) : viewType === 'gallery' ? (
          <GalleryGrid
            records={records}
            imageField={galleryImageField}
            titleField={titleField}
            cardFields={visibleFields}
            onCardClick={handleRecordNavigate}
            tenantSlug={tenantSlug}
            lookupDisplayMap={lookupDisplayMap}
          />
        ) : (
          <>
            <ObjectDataTable
              records={records}
              fields={visibleFields}
              sort={sort}
              onSortChange={handleSortChange}
              sorts={sorts}
              density={density}
              stickyFirstColumn
              groupBy={groupBy ?? undefined}
              selectedIds={selectedIds}
              onSelectionChange={setSelectedIds}
              isLoading={recordsLoading}
              collectionName={collectionName || ''}
              onEdit={permissions.canEdit ? handleEdit : undefined}
              onDelete={permissions.canDelete ? handleDeleteClick : undefined}
              lookupDisplayMap={lookupDisplayMap}
              editable={permissions.canEdit}
              onCellCommit={handleCellCommit}
            />
            {groupBy && (
              <p className="px-1 pt-1 text-xs text-muted-foreground" data-testid="group-caption">
                {t('listPower.groupsPageOnly', 'Groups reflect this page only')}
              </p>
            )}
          </>
        )
      }
      pagination={
        viewType !== 'calendar' && !recordsLoading && records.length > 0 ? (
          <DataTablePagination
            page={page}
            pageSize={pageSize}
            total={total}
            selectedCount={selectedIds.size}
            onPageChange={handlePageChange}
            onPageSizeChange={handlePageSizeChange}
          />
        ) : undefined
      }
      dialogs={
        <>
          {/* Mass edit (slice 4): one field across the selection via bulk jobs */}
          {massEditOpen && (
            <MassEditDialog
              fields={massEditableFields}
              tenantSlug={tenantSlug || ''}
              selectedCount={selectedIds.size}
              onSubmit={handleMassEditSubmit}
              onClose={() => setMassEditOpen(false)}
            />
          )}

          {/* CSV import dialog */}
          <CsvImportDialog
            open={showImportDialog}
            onOpenChange={setShowImportDialog}
            collectionName={collectionName || ''}
            onImported={refetch}
          />

          {/* Single delete confirmation */}
          <AlertDialog
            open={!!deleteTarget}
            onOpenChange={(open) => {
              if (!open) setDeleteTarget(null)
            }}
          >
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete record?</AlertDialogTitle>
                <AlertDialogDescription>
                  This will permanently delete this record. This action cannot be undone.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={handleDeleteConfirm}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  {mutations.remove.isPending ? (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  ) : null}
                  Delete
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>

          {/* Bulk delete confirmation */}
          <AlertDialog open={showBulkDeleteDialog} onOpenChange={setShowBulkDeleteDialog}>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete {selectedIds.size} records?</AlertDialogTitle>
                <AlertDialogDescription>
                  This will permanently delete {selectedIds.size} record
                  {selectedIds.size !== 1 ? 's' : ''}. This action cannot be undone.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={handleBulkDeleteConfirm}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  {mutations.bulkDelete.isPending ? (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  ) : null}
                  Delete {selectedIds.size} records
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      }
    />
  )
}
