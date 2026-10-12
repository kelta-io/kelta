/**
 * useRecordListModel — the data and state side of the record list core (`RecordList`).
 *
 * Loads the collection schema and the viewer's object + field permissions, keeps the list
 * state (page / pageSize / sort / filters / view, through `useListParams` — the URL or a local
 * copy of it) and the saved-view state (`useListViewState`), resolves the columns and the
 * alternate views' bound fields, fetches the page of records (with the JSON:API `include` for
 * the visible lookup columns, whose labels it resolves), and exposes every list action: sort,
 * paging, filters, selection, single + bulk delete, inline cell edit (If-Match), kanban move,
 * mass edit, export, and navigation through the caller's `routes`.
 *
 * Dialog open state lives here too, so `RecordList` only renders. Optional features
 * (`RecordListFeatures`) are switched by the caller; a disabled feature's actions are absent
 * (`undefined`) rather than checked at render time.
 *
 * Lookup labels: the visible reference columns are requested with `?include=<field names>` and
 * labelled from the included resources by `buildLookupLabels` — `resolveLookupDisplayField`: the
 * target's display field → `name` → first string field → id. See `.claude/docs/architecture.md` → Record list.
 *
 * Moved out of `ObjectListPage` (record-stack convergence P9).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApi } from '@/context/ApiContext'
import { useCollectionStore } from '@/context/CollectionStoreContext'
import { useI18n } from '@/context/I18nContext'
import { useAnnounce } from '@/components/LiveRegion'
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import type { CollectionSchema, FieldDefinition } from '@/hooks/useCollectionSchema'
import { useCollectionRecords } from '@/hooks/useCollectionRecords'
import type { CollectionRecord, FilterCondition } from '@/hooks/useCollectionRecords'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useCollectionPermissions } from '@/hooks/useCollectionPermissions'
import type { ObjectPermissions } from '@/hooks/useObjectPermissions'
import { useSystemPermissions } from '@/hooks/useSystemPermissions'
import { usePicklistDisplayMaps, type PicklistDisplayMaps } from '@/hooks/usePicklistOptions'
import { isStaleWriteError } from '@/services/apiClient'
import { runBulkUpdate } from '@/utils/bulkUpdate'
import { buildLookupLabels } from '@/utils/lookupDisplayField'
import { resolveRowIfMatch } from '@/utils/rowVersion'
import type { RecordRoutes } from '@/utils/recordRoutes'
import type { QuickActionExecutionContext } from '@/types/quickActions'
import { REFERENCE_FIELD_TYPES, referenceIncludeParam } from './listIncludes'
import { downloadFile, exportFileName, recordsToCsv } from './listExport'
import {
  CALENDAR_FETCH_LIMIT,
  DEFAULT_PAGE_SIZE,
  RECORD_ID_COLUMN,
  accessibleListFields,
  massEditableFields as pickMassEditableFields,
  nextSortParam,
  resolveCalendarDateField,
  resolveGalleryImageField,
  resolveKanbanCardFields,
  resolveKanbanLaneField,
  resolveTitleField,
  resolveVisibleFields,
  withCalendarRange,
  withGroupSort,
} from './listViewState'
import { useListParams, type ListUrlStateMode, type ParsedListParams } from './useListParams'
import { useListViewState, type UseListViewStateReturn } from './useListViewState'

/** Optional list features. Each is off unless the caller turns it on. */
export interface RecordListFeatures {
  /** Personal saved views + the view selector (and the default-view auto-apply). */
  savedViews: boolean
  /** Admin-published shared `list-views` alongside personal views (needs `savedViews`). */
  sharedViews: boolean
  /** The column chooser. */
  columnChooser: boolean
  /** The row-density toggle. */
  density: boolean
  /** Group the page's rows by a field. */
  groupBy: boolean
  /** Kanban / calendar / gallery renderers and the view-type switcher. */
  altViews: boolean
  /** Set one field across the selection through a bulk job (also needs edit + MANAGE_DATA). */
  massEdit: boolean
  /** CSV import (also needs create). */
  import: boolean
  /** CSV / JSON export of the loaded rows (the selection when there is one). */
  export: boolean
  /** The list-scoped quick actions menu. */
  quickActions: boolean
  /** In-place cell edit, always on for a viewer who can edit. */
  inlineEdit: boolean
  /** The filter builder panel (AND-only, see `listFilterMapping.ts`) and its toggle. */
  filterBuilder: boolean
  /** The record id as a choosable column, shown by default (`RECORD_ID_COLUMN`). */
  idColumn: boolean
  /** Count / filter summary / numeric sums of the loaded page above the table. */
  summaryStats: boolean
}

export const NO_LIST_FEATURES: RecordListFeatures = {
  savedViews: false,
  sharedViews: false,
  columnChooser: false,
  density: false,
  groupBy: false,
  altViews: false,
  massEdit: false,
  import: false,
  export: false,
  quickActions: false,
  inlineEdit: false,
  filterBuilder: false,
  idColumn: false,
  summaryStats: false,
}

export interface UseRecordListModelOptions {
  collectionName: string
  /** Tenant slug — bulk-jobs link and quick-action context. */
  tenantSlug: string
  /** Record URLs of the stack this list renders in (`recordRoutes(variant, tenantSlug)`). */
  routes: RecordRoutes
  /** Optional features (missing keys are off). */
  features?: Partial<RecordListFeatures>
  /** Where the list state lives: the URL (`'sync'`) or a local copy seeded from it (`'seed'`). */
  urlState?: ListUrlStateMode
}

type LookupDisplayMap = Record<string, Record<string, string>>

export interface RecordListModel {
  collectionName: string
  features: RecordListFeatures
  schema: CollectionSchema | undefined
  /** `schema.displayName`, else the capitalised collection name. */
  collectionLabel: string
  /** All schema fields. */
  fields: FieldDefinition[]
  /** Non-audit, FLS-visible fields (filter builder, group-by picker, alt-view pickers). */
  accessibleFields: FieldDefinition[]
  /** What the column chooser offers: `accessibleFields`, led by the id column when it is on. */
  columnFields: FieldDefinition[]
  /** The table's columns, in order. */
  visibleFields: FieldDefinition[]
  /** The visible columns that are record fields (no id column) — alt-view card bodies. */
  visibleRecordFields: FieldDefinition[]
  permissions: ObjectPermissions
  /** Schema or permissions still loading — the shell shows its spinner. */
  isLoading: boolean
  schemaError: Error | null

  /** The list state from the params store. */
  params: ParsedListParams
  view: UseListViewStateReturn

  records: CollectionRecord[]
  total: number
  recordsLoading: boolean
  recordsError: Error | null
  refetch: () => void
  lookupDisplayMap: LookupDisplayMap | undefined
  picklistDisplayMaps: PicklistDisplayMaps

  /** Alternate views' bound fields. */
  kanbanLaneField: FieldDefinition | null
  kanbanCardFields: FieldDefinition[]
  calendarDateField: FieldDefinition | null
  galleryImageField: FieldDefinition | undefined
  titleField: string

  selectedIds: Set<string>
  setSelectedIds: (ids: Set<string>) => void
  clearSelection: () => void

  /** Mass-edit targets, and whether mass edit is offered at all. */
  massEditFields: FieldDefinition[]
  canMassEdit: boolean
  /** Inline cell edit is on for this viewer. */
  inlineEditable: boolean

  changeSort: (field: string, additive?: boolean) => void
  changePage: (page: number) => void
  changePageSize: (pageSize: number) => void
  /** Replace the filter conditions (back to page 1). */
  setFilters: (filters: FilterCondition[]) => void
  removeFilter: (filterId: string) => void
  clearFilters: () => void
  /** The table's visible columns, as saved into a new view. */
  saveView: (name: string) => void

  goToNew: () => void
  goToEdit: (record: CollectionRecord) => void
  goToDetail: (record: CollectionRecord) => void

  commitCell: (recordId: string, fieldName: string, value: unknown) => Promise<void>
  moveCard: (recordId: string, lane: string | null) => Promise<void>
  submitMassEdit: (fieldName: string, value: unknown) => Promise<void>
  exportCsv: () => void
  exportJson: () => void

  quickActionContext: QuickActionExecutionContext

  /** Filter builder panel state. */
  filterBuilderOpen: boolean
  setFilterBuilderOpen: (open: boolean) => void

  /** Dialog state. */
  deleteTarget: CollectionRecord | null
  requestDelete: (record: CollectionRecord) => void
  cancelDelete: () => void
  confirmDelete: () => void
  deletePending: boolean
  bulkDeleteOpen: boolean
  requestBulkDelete: () => void
  setBulkDeleteOpen: (open: boolean) => void
  confirmBulkDelete: () => void
  bulkDeletePending: boolean
  importOpen: boolean
  setImportOpen: (open: boolean) => void
  massEditOpen: boolean
  setMassEditOpen: (open: boolean) => void
}

export function useRecordListModel({
  collectionName,
  tenantSlug,
  routes,
  features: featureOverrides,
  urlState = 'sync',
}: UseRecordListModelOptions): RecordListModel {
  const navigate = useNavigate()
  const { t } = useI18n()
  const { apiClient } = useApi()
  const collectionStore = useCollectionStore()
  const { announce } = useAnnounce()
  const features = useMemo<RecordListFeatures>(
    () => ({ ...NO_LIST_FEATURES, ...featureOverrides }),
    [featureOverrides]
  )

  const listParams = useListParams(urlState)
  const { params, update } = listParams
  const { page, pageSize, sorts, filters } = params

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [deleteTarget, setDeleteTarget] = useState<CollectionRecord | null>(null)
  const [bulkDeleteOpen, setBulkDeleteOpen] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [massEditOpen, setMassEditOpen] = useState(false)
  const [filterBuilderOpen, setFilterBuilderOpen] = useState(false)

  const {
    schema,
    fields,
    isLoading: schemaLoading,
    error: schemaError,
  } = useCollectionSchema(collectionName)
  const {
    permissions,
    isFieldVisible,
    isLoading: permissionsLoading,
  } = useCollectionPermissions(collectionName)
  // Mass edit posts to /api/bulk-jobs, which requires the MANAGE_DATA system
  // permission (in-controller gate) — hide the affordance without it.
  const { hasPermission } = useSystemPermissions()

  const view = useListViewState({
    collectionName,
    collectionId: schema?.id,
    listParams,
    savedViews: features.savedViews,
    sharedViews: features.sharedViews,
    groupBy: features.groupBy,
    altViews: features.altViews,
  })
  const { activeView, columnOverride, groupBy, viewType, typeConfig, calendarMonth } = view

  const accessibleFields = useMemo(
    () => accessibleListFields(fields, isFieldVisible),
    [fields, isFieldVisible]
  )
  const columnFields = useMemo(
    () => (features.idColumn ? [RECORD_ID_COLUMN, ...accessibleFields] : accessibleFields),
    [features.idColumn, accessibleFields]
  )
  const visibleFields = useMemo(
    () => resolveVisibleFields(columnFields, columnOverride, activeView?.visibleColumns),
    [columnFields, columnOverride, activeView]
  )
  const visibleRecordFields = useMemo(
    () => visibleFields.filter((f) => f !== RECORD_ID_COLUMN),
    [visibleFields]
  )

  // Alt-view field resolution: explicit typeConfig wins, else the first accessible field of the
  // right type; none ⇒ the renderer shows an inline empty state instead of crashing.
  const kanbanLaneField = useMemo(
    () => resolveKanbanLaneField(accessibleFields, typeConfig),
    [accessibleFields, typeConfig]
  )
  const calendarDateField = useMemo(
    () => resolveCalendarDateField(accessibleFields, typeConfig),
    [accessibleFields, typeConfig]
  )
  const galleryImageField = useMemo(
    () => resolveGalleryImageField(accessibleFields, typeConfig),
    [accessibleFields, typeConfig]
  )
  const titleField = resolveTitleField(typeConfig, schema?.displayFieldName)
  const kanbanCardFields = useMemo(
    () => resolveKanbanCardFields(accessibleFields, visibleRecordFields, titleField, typeConfig),
    [accessibleFields, visibleRecordFields, titleField, typeConfig]
  )

  // Table columns + the filter bar render authored labels for every picklist field.
  const { displayMaps: picklistDisplayMaps } = usePicklistDisplayMaps(accessibleFields)

  const massEditFields = useMemo(() => pickMassEditableFields(fields), [fields])
  const canMassEdit =
    features.massEdit &&
    permissions.canEdit &&
    hasPermission('MANAGE_DATA') &&
    massEditFields.length > 0
  const inlineEditable = features.inlineEdit && permissions.canEdit

  // Visible reference columns are fetched with `include` (by FIELD name — see listIncludes).
  const referenceFields = useMemo(
    () => visibleFields.filter((f) => REFERENCE_FIELD_TYPES.has(f.type) && f.referenceTarget),
    [visibleFields]
  )
  const includeParam = useMemo(() => referenceIncludeParam(referenceFields), [referenceFields])

  const effectiveSorts = useMemo(() => withGroupSort(sorts, groupBy), [groupBy, sorts])
  const effectiveFilters = useMemo(
    () =>
      viewType === 'calendar'
        ? withCalendarRange(filters, calendarDateField?.name ?? null, calendarMonth)
        : filters,
    [filters, viewType, calendarDateField, calendarMonth]
  )

  // The calendar fetches up to the HTTP clamp so a month isn't truncated at the page size.
  const isCalendar = viewType === 'calendar'
  const {
    data: records,
    total,
    isLoading: recordsLoading,
    error: recordsError,
    refetch,
    rawResponse,
  } = useCollectionRecords({
    collectionName,
    page: isCalendar ? 1 : page,
    pageSize: isCalendar ? CALENDAR_FETCH_LIMIT : pageSize,
    sort: effectiveSorts.length > 0 ? effectiveSorts : undefined,
    filters: effectiveFilters.length > 0 ? effectiveFilters : undefined,
    enabled: !!schema,
    include: includeParam,
  })

  // Lookup labels from the included resources, labelled by the target's display field.
  const lookupDisplayMap = useMemo(
    () => buildLookupLabels(rawResponse, referenceFields, collectionStore.getCollectionByName),
    [rawResponse, referenceFields, collectionStore]
  )

  // Screen-reader announcement when a load finishes.
  const prevRecordsLoadingRef = useRef(recordsLoading)
  useEffect(() => {
    if (prevRecordsLoadingRef.current && !recordsLoading) {
      if (recordsError) {
        announce('Error loading records', 'assertive')
      } else {
        announce(`Loaded ${records.length} of ${total} records`)
      }
    }
    prevRecordsLoadingRef.current = recordsLoading
  }, [recordsLoading, records.length, total, recordsError, announce])

  // No shared onSuccess: the hook fires it for EVERY mutation (inline cell patch included),
  // which would falsely announce "Record deleted" and clear the selection on each cell edit.
  const mutations = useRecordMutation({ collectionName })

  const clearSelection = useCallback(() => setSelectedIds(new Set()), [])

  const afterDeleteSuccess = useCallback(
    (count: number) => {
      setDeleteTarget(null)
      setBulkDeleteOpen(false)
      setSelectedIds(new Set())
      const message =
        count === 1
          ? t('listDelete.deleted', 'Record deleted')
          : t('listDelete.bulkDeleted', { count })
      toast.success(message)
      announce(message)
    },
    [announce, t]
  )

  // A failed delete keeps its dialog open and says why.
  const afterDeleteFailure = useCallback(
    (error: unknown) => {
      const message = t('listDelete.failed', {
        message: error instanceof Error && error.message ? error.message : t('errors.generic'),
      })
      toast.error(message)
      announce(message, 'assertive')
    },
    [announce, t]
  )

  // In-place cell edit. Optimistic concurrency (P5): the row's version is checked against the
  // stored record and the write carries its ETag as If-Match. A conflict reverts the cell — the
  // commit resolves so the editor closes on the refetched value — and says why; any other error
  // stays inline.
  const commitCell = useCallback(
    async (recordId: string, fieldName: string, value: unknown): Promise<void> => {
      const staleMessage = t('recordConflict.cellReverted')
      try {
        const ifMatch = await resolveRowIfMatch(
          apiClient,
          collectionName,
          recordId,
          records.find((r) => r.id === recordId),
          staleMessage
        )
        await mutations.patch.mutateAsync({
          id: recordId,
          data: { [fieldName]: value },
          ...(ifMatch ? { ifMatch } : {}),
        })
      } catch (e) {
        if (!isStaleWriteError(e)) throw e
        toast.error(staleMessage)
        announce(staleMessage, 'assertive')
        await refetch()
        return
      }
      await refetch()
    },
    [apiClient, collectionName, records, mutations.patch, refetch, t, announce]
  )

  // Mass edit: one field value across the selection via the shared bulk-jobs flow. Throws on
  // failure so MassEditDialog stays open with the error inline; toasts link to Bulk Jobs.
  const submitMassEdit = useCallback(
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

  // Kanban drop: fetch a fresh ETag, PATCH the lane field with If-Match. Any rejection toasts and
  // refetches; the board reverts its optimistic move on the rejection.
  const moveCard = useCallback(
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

  const changeSort = useCallback(
    (field: string, additive?: boolean) => {
      update({ sort: nextSortParam(sorts, field, !!additive), page: undefined })
    },
    [sorts, update]
  )

  const changePage = useCallback(
    (newPage: number) => {
      update({ page: newPage > 1 ? String(newPage) : undefined })
      setSelectedIds(new Set())
    },
    [update]
  )

  const changePageSize = useCallback(
    (newPageSize: number) => {
      update({
        pageSize: newPageSize !== DEFAULT_PAGE_SIZE ? String(newPageSize) : undefined,
        page: undefined,
      })
      setSelectedIds(new Set())
    },
    [update]
  )

  const setFilters = useCallback(
    (next: FilterCondition[]) => {
      update({ filter: next.length > 0 ? JSON.stringify(next) : undefined, page: undefined })
    },
    [update]
  )
  const removeFilter = useCallback(
    (filterId: string) => setFilters(filters.filter((f) => f.id !== filterId)),
    [filters, setFilters]
  )
  const clearFilters = useCallback(() => setFilters([]), [setFilters])

  const { saveView: saveViewWithColumns } = view
  const saveView = useCallback(
    (name: string) =>
      saveViewWithColumns(
        name,
        visibleFields.map((f) => f.name)
      ),
    [saveViewWithColumns, visibleFields]
  )

  const goToNew = useCallback(
    () => navigate(routes.new(collectionName)),
    [navigate, routes, collectionName]
  )
  const goToEdit = useCallback(
    (record: CollectionRecord) => navigate(routes.edit(collectionName, record.id)),
    [navigate, routes, collectionName]
  )
  const goToDetail = useCallback(
    (record: CollectionRecord) => navigate(routes.detail(collectionName, record.id)),
    [navigate, routes, collectionName]
  )

  const requestDelete = useCallback((record: CollectionRecord) => setDeleteTarget(record), [])
  const cancelDelete = useCallback(() => setDeleteTarget(null), [])
  const confirmDelete = useCallback(() => {
    if (!deleteTarget) return
    mutations.remove
      .mutateAsync(deleteTarget.id)
      .then(() => afterDeleteSuccess(1), afterDeleteFailure)
  }, [deleteTarget, mutations.remove, afterDeleteSuccess, afterDeleteFailure])

  const requestBulkDelete = useCallback(() => {
    if (selectedIds.size > 0) setBulkDeleteOpen(true)
  }, [selectedIds])
  const confirmBulkDelete = useCallback(() => {
    const ids = Array.from(selectedIds)
    mutations.bulkDelete
      .mutateAsync(ids)
      .then(() => afterDeleteSuccess(ids.length), afterDeleteFailure)
  }, [mutations.bulkDelete, selectedIds, afterDeleteSuccess, afterDeleteFailure])

  // Export the loaded rows — the selection when there is one.
  const exportRows = useCallback(
    () => (selectedIds.size > 0 ? records.filter((r) => selectedIds.has(r.id)) : records),
    [records, selectedIds]
  )
  const exportCsv = useCallback(() => {
    if (!records.length || !visibleFields.length) return
    downloadFile(
      recordsToCsv(exportRows(), visibleFields),
      exportFileName(collectionName, 'csv'),
      'text/csv;charset=utf-8'
    )
  }, [records, visibleFields, exportRows, collectionName])
  const exportJson = useCallback(() => {
    if (!records.length) return
    downloadFile(
      JSON.stringify(exportRows(), null, 2),
      exportFileName(collectionName, 'json'),
      'application/json'
    )
  }, [records, exportRows, collectionName])

  const quickActionContext = useMemo<QuickActionExecutionContext>(
    () => ({ collectionName, selectedIds: Array.from(selectedIds), tenantSlug }),
    [collectionName, selectedIds, tenantSlug]
  )

  const collectionLabel =
    schema?.displayName ||
    (collectionName ? collectionName.charAt(0).toUpperCase() + collectionName.slice(1) : 'Objects')

  return {
    collectionName,
    features,
    schema,
    collectionLabel,
    fields,
    accessibleFields,
    columnFields,
    visibleFields,
    visibleRecordFields,
    permissions,
    isLoading: schemaLoading || permissionsLoading,
    schemaError: schemaError ?? null,
    params,
    view,
    records,
    total,
    recordsLoading,
    recordsError,
    refetch,
    lookupDisplayMap,
    picklistDisplayMaps,
    kanbanLaneField,
    kanbanCardFields,
    calendarDateField,
    galleryImageField,
    titleField,
    selectedIds,
    setSelectedIds,
    clearSelection,
    massEditFields,
    canMassEdit,
    inlineEditable,
    changeSort,
    changePage,
    changePageSize,
    setFilters,
    removeFilter,
    clearFilters,
    saveView,
    goToNew,
    goToEdit,
    goToDetail,
    commitCell,
    moveCard,
    submitMassEdit,
    exportCsv,
    exportJson,
    quickActionContext,
    filterBuilderOpen,
    setFilterBuilderOpen,
    deleteTarget,
    requestDelete,
    cancelDelete,
    confirmDelete,
    deletePending: mutations.remove.isPending,
    bulkDeleteOpen,
    requestBulkDelete,
    setBulkDeleteOpen,
    confirmBulkDelete,
    bulkDeletePending: mutations.bulkDelete.isPending,
    importOpen,
    setImportOpen,
    massEditOpen,
    setMassEditOpen,
  }
}
