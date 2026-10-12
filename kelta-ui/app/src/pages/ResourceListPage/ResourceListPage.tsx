/**
 * ResourceListPage Component
 *
 * Displays records in a collection with pagination, filtering, sorting,
 * column selection, bulk selection, and export capabilities.
 *
 * Requirements:
 * - 11.2: Resource browser displays paginated list of resources
 * - 11.3: Resource browser supports filtering by field values
 * - 11.4: Resource browser supports multiple filter conditions
 * - 11.5: Resource browser supports filter operators (equals, contains, greater than, etc.)
 * - 11.11: Resource browser supports bulk selection
 * - 11.12: Resource browser supports exporting selected records
 */

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useApi } from '../../context/ApiContext'
import { ApiClient } from '../../services/apiClient'
import { unwrapCollection } from '../../utils/jsonapi'
// The shared fetcher: the `['collection-schema', name]` cache entry must hold the full schema
// shape (incl. displayFieldName, trackHistory) every other reader expects.
import { fetchCollectionSchema, type FieldDefinition } from '../../hooks/useCollectionSchema'
import { useToast, ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { useSavedViews } from '../../hooks/useSavedViews'
import { useLookupDisplayMap } from '../../hooks/useLookupDisplayMap'
import { useObjectPermissions } from '../../hooks/useObjectPermissions'
import { ListShell } from '../../components/record/ListShell'
import { ViewSelector } from '../../components/ViewSelector/ViewSelector'
import { ObjectDataTable } from '../../components/ObjectDataTable/ObjectDataTable'
import { SummaryStatsBar } from '../../components/SummaryStatsBar/SummaryStatsBar'
import { cn } from '@/lib/utils'

/**
 * Export format type
 * Requirement 11.12: Support CSV and JSON export formats
 */
export type ExportFormat = 'csv' | 'json'

/**
 * Escape a value for CSV format
 * Handles commas, quotes, and newlines
 */
// eslint-disable-next-line react-refresh/only-export-components
export function escapeCSVValue(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }

  const stringValue = typeof value === 'object' ? JSON.stringify(value) : String(value)

  // Check if the value needs to be quoted
  const needsQuoting =
    stringValue.includes(',') ||
    stringValue.includes('"') ||
    stringValue.includes('\n') ||
    stringValue.includes('\r')

  if (needsQuoting) {
    // Escape double quotes by doubling them
    const escaped = stringValue.replace(/"/g, '""')
    return `"${escaped}"`
  }

  return stringValue
}

/**
 * Convert records to CSV format
 * Requirement 11.12: Export selected records to CSV
 */
// eslint-disable-next-line react-refresh/only-export-components
export function recordsToCSV(
  records: Resource[],
  fields: FieldDefinition[],
  includeId: boolean = true
): string {
  // Build headers
  const headers: string[] = []
  if (includeId) {
    headers.push('id')
  }
  fields.forEach((field) => {
    headers.push(field.displayName || field.name)
  })

  // Build rows
  const rows: string[] = [headers.map(escapeCSVValue).join(',')]

  records.forEach((record) => {
    const values: string[] = []
    if (includeId) {
      values.push(escapeCSVValue(record.id))
    }
    fields.forEach((field) => {
      values.push(escapeCSVValue(record[field.name]))
    })
    rows.push(values.join(','))
  })

  return rows.join('\n')
}

/**
 * Convert records to JSON format
 * Requirement 11.12: Export selected records to JSON
 */
// eslint-disable-next-line react-refresh/only-export-components
export function recordsToJSON(records: Resource[]): string {
  return JSON.stringify(records, null, 2)
}

/**
 * Trigger a file download in the browser
 */
// eslint-disable-next-line react-refresh/only-export-components
export function downloadFile(content: string, filename: string, mimeType: string): void {
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

/** Field definition for collection schema — the shared runtime-schema shape. */
export type { FieldDefinition }

/**
 * Collection schema interface
 */
export interface CollectionSchema {
  id: string
  name: string
  displayName: string
  fields: FieldDefinition[]
}

/**
 * Resource record interface
 */
export interface Resource {
  id: string
  [key: string]: unknown
}

/**
 * Filter condition interface
 * Requirement 11.4, 11.5: Support multiple filter conditions with operators
 */
export interface FilterCondition {
  id: string
  field: string
  operator: FilterOperator
  value: string
}

/**
 * Filter operator type
 * Requirement 11.5: Support filter operators
 */
export type FilterOperator =
  | 'equals'
  | 'not_equals'
  | 'contains'
  | 'starts_with'
  | 'ends_with'
  | 'greater_than'
  | 'less_than'
  | 'greater_than_or_equal'
  | 'less_than_or_equal'

/**
 * Sort state interface
 */
interface SortState {
  field: string
  direction: 'asc' | 'desc'
}

/**
 * Paginated response interface
 */
interface PaginatedResponse {
  data: Resource[]
  total: number
  page: number
  pageSize: number
}

/**
 * Props for ResourceListPage component
 */
export interface ResourceListPageProps {
  /** Optional test ID for testing */
  testId?: string
}

// Filter operators with labels
const FILTER_OPERATORS: { value: FilterOperator; label: string }[] = [
  { value: 'equals', label: 'Equals' },
  { value: 'not_equals', label: 'Not Equals' },
  { value: 'contains', label: 'Contains' },
  { value: 'starts_with', label: 'Starts With' },
  { value: 'ends_with', label: 'Ends With' },
  { value: 'greater_than', label: 'Greater Than' },
  { value: 'less_than', label: 'Less Than' },
  { value: 'greater_than_or_equal', label: 'Greater Than or Equal' },
  { value: 'less_than_or_equal', label: 'Less Than or Equal' },
]

// Page size options
const PAGE_SIZE_OPTIONS = [10, 25, 50, 100]

// Generate unique ID for filter conditions
let filterIdCounter = 0
function generateFilterId(): string {
  return `filter-${++filterIdCounter}`
}

// API functions using apiClient

interface FetchResourcesParams {
  collectionName: string
  page: number
  pageSize: number
  sort?: SortState
  filters?: FilterCondition[]
}

/**
 * Map UI filter operators to JSON:API filter operators.
 * The backend's FilterCondition supports: eq, neq, gt, lt, gte, lte, contains, starts, ends
 */
const FILTER_OPERATOR_MAP: Record<FilterOperator, string> = {
  equals: 'eq',
  not_equals: 'neq',
  contains: 'contains',
  starts_with: 'starts',
  ends_with: 'ends',
  greater_than: 'gt',
  less_than: 'lt',
  greater_than_or_equal: 'gte',
  less_than_or_equal: 'lte',
}

async function fetchResources(
  apiClient: ApiClient,
  params: FetchResourcesParams
): Promise<PaginatedResponse> {
  const { collectionName, page, pageSize, sort, filters } = params

  // Build JSON:API compliant query parameters
  const queryParams = new URLSearchParams()
  queryParams.set('page[number]', String(page))
  queryParams.set('page[size]', String(pageSize))

  if (sort) {
    const sortValue = sort.direction === 'desc' ? `-${sort.field}` : sort.field
    queryParams.set('sort', sortValue)
  }

  if (filters && filters.length > 0) {
    for (const filter of filters) {
      const op = FILTER_OPERATOR_MAP[filter.operator] || filter.operator
      queryParams.set(`filter[${filter.field}][${op}]`, filter.value)
    }
  }

  const response = await apiClient.get(`/api/${collectionName}?${queryParams.toString()}`)
  return unwrapCollection<Resource>(response)
}

async function deleteResource(
  apiClient: ApiClient,
  collectionName: string,
  resourceId: string
): Promise<void> {
  return apiClient.delete(`/api/${collectionName}/${resourceId}`)
}

async function deleteResources(
  apiClient: ApiClient,
  collectionName: string,
  resourceIds: string[]
): Promise<void> {
  return apiClient.post(`/api/${collectionName}/bulk-delete`, { ids: resourceIds })
}

/**
 * ResourceListPage Component
 *
 * Main page for displaying and managing resources in a collection.
 * Provides listing, filtering, sorting, column selection, and bulk operations.
 */
export function ResourceListPage({
  testId = 'resource-list-page',
}: ResourceListPageProps): React.ReactElement {
  const { collection: collectionName } = useParams<{ collection: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { showToast } = useToast()
  const { permissions: objectPermissions } = useObjectPermissions(collectionName)

  // Pagination state
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(25)

  // Sort state
  const [sort, setSort] = useState<SortState | undefined>(undefined)

  // Filter state - Requirement 11.3, 11.4
  const [filters, setFilters] = useState<FilterCondition[]>([])
  const [pendingFilters, setPendingFilters] = useState<FilterCondition[]>([])
  const [showFilters, setShowFilters] = useState(false)

  // Column visibility state - Requirement 11.5 (column selection)
  const [visibleColumnOverrides, setVisibleColumnOverrides] = useState<Set<string> | null>(null)
  const [showColumnSelector, setShowColumnSelector] = useState(false)

  // Bulk selection state - Requirement 11.11
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())

  // Delete confirmation dialog state
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [resourceToDelete, setResourceToDelete] = useState<Resource | null>(null)
  const [bulkDeleteDialogOpen, setBulkDeleteDialogOpen] = useState(false)

  // Export dropdown state - Requirement 11.12
  const [showExportDropdown, setShowExportDropdown] = useState(false)
  const exportDropdownRef = useRef<HTMLDivElement>(null)

  // Inline editing toggle state (T11)
  const [inlineEditEnabled, setInlineEditEnabled] = useState(false)

  // Saved views hook (T10)
  const savedViews = useSavedViews(collectionName || '')

  // Fetch collection schema
  const {
    data: schema,
    isLoading: schemaLoading,
    error: schemaError,
  } = useQuery({
    queryKey: ['collection-schema', collectionName],
    queryFn: () => fetchCollectionSchema(apiClient, collectionName!),
    enabled: !!collectionName,
  })

  // Compute visible columns: use overrides if user has toggled, otherwise derive defaults from schema
  const visibleColumns = useMemo(() => {
    if (visibleColumnOverrides) return visibleColumnOverrides
    if (!schema) return new Set<string>()
    const schemaFields = Array.isArray(schema.fields) ? schema.fields : []
    const defaultColumns = new Set<string>(['id'])
    schemaFields.slice(0, 5).forEach((field) => {
      defaultColumns.add(field.name)
    })
    return defaultColumns
  }, [visibleColumnOverrides, schema])

  const setVisibleColumns = useCallback(
    (value: Set<string> | ((prev: Set<string>) => Set<string>)) => {
      if (typeof value === 'function') {
        setVisibleColumnOverrides((prev) => value(prev ?? new Set()))
      } else {
        setVisibleColumnOverrides(value)
      }
    },
    []
  )

  // Fetch resources - Requirement 11.2
  const {
    data: resourcesData,
    isLoading: resourcesLoading,
    error: resourcesError,
    refetch: refetchResources,
  } = useQuery({
    queryKey: ['resources', collectionName, page, pageSize, sort, filters],
    queryFn: () =>
      fetchResources(apiClient, {
        collectionName: collectionName!,
        page,
        pageSize,
        sort,
        filters,
      }),
    enabled: !!collectionName && !!schema,
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: ({ id }: { id: string }) => deleteResource(apiClient, collectionName!, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['resources', collectionName] })
      showToast(t('success.deleted', { item: t('resources.record') }), 'success')
      setDeleteDialogOpen(false)
      setResourceToDelete(null)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Bulk delete mutation - Requirement 11.11
  const bulkDeleteMutation = useMutation({
    mutationFn: (ids: string[]) => deleteResources(apiClient, collectionName!, ids),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['resources', collectionName] })
      showToast(t('success.deleted', { item: `${selectedIds.size} records` }), 'success')
      setBulkDeleteDialogOpen(false)
      setSelectedIds(new Set())
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Resolve display labels for reference/lookup/master_detail fields
  const { lookupDisplayMap } = useLookupDisplayMap(schema?.fields, 'lookup-display-list')

  // Get visible fields for table columns
  const visibleFields = useMemo(() => {
    if (!schema) return []
    const schemaFields = Array.isArray(schema.fields) ? schema.fields : []
    return schemaFields.filter((field) => visibleColumns.has(field.name))
  }, [schema, visibleColumns])

  // Calculate total pages
  const totalPages = useMemo(() => {
    if (!resourcesData) return 0
    return Math.ceil(resourcesData.total / pageSize)
  }, [resourcesData, pageSize])

  // Handle sort change - Requirement 11.4 (column sorting)
  const handleSortChange = useCallback((field: string) => {
    setSort((prev) => {
      if (prev?.field === field) {
        return prev.direction === 'asc' ? { field, direction: 'desc' } : undefined
      }
      return { field, direction: 'asc' }
    })
    setPage(1)
  }, [])

  // Handle page change
  const handlePageChange = useCallback((newPage: number) => {
    setPage(newPage)
    setSelectedIds(new Set()) // Clear selection on page change
  }, [])

  // Handle page size change
  const handlePageSizeChange = useCallback((event: React.ChangeEvent<HTMLSelectElement>) => {
    setPageSize(Number(event.target.value))
    setPage(1)
    setSelectedIds(new Set())
  }, [])

  // Filter handlers - Requirement 11.3, 11.4, 11.5
  const handleToggleFilters = useCallback(() => {
    setShowFilters((prev) => !prev)
    if (!showFilters) {
      setPendingFilters(filters)
    }
  }, [showFilters, filters])

  const handleAddFilter = useCallback(() => {
    const addFilterFields = Array.isArray(schema?.fields) ? schema.fields : []
    if (!schema || addFilterFields.length === 0) return
    const newFilter: FilterCondition = {
      id: generateFilterId(),
      field: addFilterFields[0].name,
      operator: 'equals',
      value: '',
    }
    setPendingFilters((prev) => [...prev, newFilter])
  }, [schema])

  const handleRemoveFilter = useCallback((filterId: string) => {
    setPendingFilters((prev) => prev.filter((f) => f.id !== filterId))
  }, [])

  const handleFilterFieldChange = useCallback((filterId: string, field: string) => {
    setPendingFilters((prev) => prev.map((f) => (f.id === filterId ? { ...f, field } : f)))
  }, [])

  const handleFilterOperatorChange = useCallback((filterId: string, operator: FilterOperator) => {
    setPendingFilters((prev) => prev.map((f) => (f.id === filterId ? { ...f, operator } : f)))
  }, [])

  const handleFilterValueChange = useCallback((filterId: string, value: string) => {
    setPendingFilters((prev) => prev.map((f) => (f.id === filterId ? { ...f, value } : f)))
  }, [])

  const handleApplyFilters = useCallback(() => {
    setFilters(pendingFilters.filter((f) => f.value.trim() !== ''))
    setPage(1)
    setSelectedIds(new Set())
  }, [pendingFilters])

  const handleClearFilters = useCallback(() => {
    setPendingFilters([])
    setFilters([])
    setPage(1)
    setSelectedIds(new Set())
  }, [])

  // Column visibility handlers
  const handleToggleColumnSelector = useCallback(() => {
    setShowColumnSelector((prev) => !prev)
  }, [])

  const handleToggleColumn = useCallback(
    (fieldName: string) => {
      setVisibleColumns((prev) => {
        const next = new Set(prev)
        if (next.has(fieldName)) {
          next.delete(fieldName)
        } else {
          next.add(fieldName)
        }
        return next
      })
    },
    [setVisibleColumns]
  )

  // Row selection is handled by ObjectDataTable via onSelectionChange={setSelectedIds}.

  // Navigation handlers
  const handleCreate = useCallback(() => {
    navigate(`/${getTenantSlug()}/resources/${collectionName}/new`)
  }, [navigate, collectionName])

  const handleView = useCallback(
    (resource: Resource) => {
      navigate(`/${getTenantSlug()}/resources/${collectionName}/${resource.id}`)
    },
    [navigate, collectionName]
  )

  const handleEdit = useCallback(
    (resource: Resource) => {
      navigate(`/${getTenantSlug()}/resources/${collectionName}/${resource.id}/edit`)
    },
    [navigate, collectionName]
  )

  // Delete handlers
  const handleDeleteClick = useCallback((resource: Resource) => {
    setResourceToDelete(resource)
    setDeleteDialogOpen(true)
  }, [])

  const handleDeleteConfirm = useCallback(() => {
    if (resourceToDelete) {
      deleteMutation.mutate({ id: resourceToDelete.id })
    }
  }, [resourceToDelete, deleteMutation])

  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
    setResourceToDelete(null)
  }, [])

  // Bulk delete handlers - Requirement 11.11
  const handleBulkDeleteClick = useCallback(() => {
    setBulkDeleteDialogOpen(true)
  }, [])

  const handleBulkDeleteConfirm = useCallback(() => {
    bulkDeleteMutation.mutate(Array.from(selectedIds))
  }, [bulkDeleteMutation, selectedIds])

  const handleBulkDeleteCancel = useCallback(() => {
    setBulkDeleteDialogOpen(false)
  }, [])

  // Export handlers - Requirement 11.12
  const handleToggleExportDropdown = useCallback(() => {
    setShowExportDropdown((prev) => !prev)
  }, [])

  const handleExportSelected = useCallback(
    (format: ExportFormat) => {
      if (!schema || !resourcesData) return

      // Get selected records
      const selectedRecords = resourcesData.data.filter((r) => selectedIds.has(r.id))
      if (selectedRecords.length === 0) return

      const timestamp = new Date().toISOString().slice(0, 10)
      const filename = `${collectionName}-export-${timestamp}`

      if (format === 'csv') {
        const csv = recordsToCSV(selectedRecords, visibleFields, visibleColumns.has('id'))
        downloadFile(csv, `${filename}.csv`, 'text/csv;charset=utf-8')
        showToast(
          t('resources.exportSuccess', { count: selectedRecords.length, format: 'CSV' }),
          'success'
        )
      } else {
        const json = recordsToJSON(selectedRecords)
        downloadFile(json, `${filename}.json`, 'application/json')
        showToast(
          t('resources.exportSuccess', { count: selectedRecords.length, format: 'JSON' }),
          'success'
        )
      }

      setShowExportDropdown(false)
    },
    [
      schema,
      resourcesData,
      selectedIds,
      collectionName,
      visibleFields,
      visibleColumns,
      showToast,
      t,
    ]
  )

  const handleExportAll = useCallback(
    (format: ExportFormat) => {
      if (!schema || !resourcesData) return

      const records = resourcesData.data
      if (records.length === 0) return

      const timestamp = new Date().toISOString().slice(0, 10)
      const filename = `${collectionName}-export-${timestamp}`

      if (format === 'csv') {
        const csv = recordsToCSV(records, visibleFields, visibleColumns.has('id'))
        downloadFile(csv, `${filename}.csv`, 'text/csv;charset=utf-8')
        showToast(t('resources.exportSuccess', { count: records.length, format: 'CSV' }), 'success')
      } else {
        const json = recordsToJSON(records)
        downloadFile(json, `${filename}.json`, 'application/json')
        showToast(
          t('resources.exportSuccess', { count: records.length, format: 'JSON' }),
          'success'
        )
      }

      setShowExportDropdown(false)
    },
    [schema, resourcesData, collectionName, visibleFields, visibleColumns, showToast, t]
  )

  // Close export dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (exportDropdownRef.current && !exportDropdownRef.current.contains(event.target as Node)) {
        setShowExportDropdown(false)
      }
    }

    if (showExportDropdown) {
      document.addEventListener('mousedown', handleClickOutside)
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [showExportDropdown])

  // Saved view selection handler (T10)
  const handleSelectView = useCallback(
    (viewId: string | null) => {
      savedViews.selectView(viewId)
      if (!viewId) {
        // "All Records" — reset to defaults
        setFilters([])
        setPendingFilters([])
        setSort(undefined)
        setVisibleColumnOverrides(null)
        setPageSize(25)
        setPage(1)
        return
      }
      const view = savedViews.views.find((v) => v.id === viewId)
      if (!view) return
      setFilters(view.filters as unknown as FilterCondition[])
      setPendingFilters(view.filters as unknown as FilterCondition[])
      setSort(view.sortField ? { field: view.sortField, direction: view.sortDirection } : undefined)
      setVisibleColumnOverrides(new Set(view.visibleColumns))
      setPageSize(view.pageSize)
      setPage(1)
    },
    [savedViews]
  )

  const handleSaveView = useCallback(
    (name: string) => {
      savedViews.saveView(name, {
        filters,
        sortField: sort?.field ?? null,
        sortDirection: sort?.direction ?? 'asc',
        visibleColumns: Array.from(visibleColumns),
        pageSize,
        isDefault: false,
      })
      showToast(t('listViews.savedSuccessfully'), 'success')
    },
    [savedViews, filters, sort, visibleColumns, pageSize, showToast, t]
  )

  const handleDeleteView = useCallback(
    (viewId: string) => {
      savedViews.deleteView(viewId)
      showToast(t('listViews.deletedSuccessfully'), 'success')
    },
    [savedViews, showToast, t]
  )

  // Inline edit toggle handler (T11)
  const handleToggleInlineEdit = useCallback(() => {
    setInlineEditEnabled((prev) => !prev)
  }, [])

  // Inline edit save callback (T11)
  // Persist a single inline-edited cell via the JSON:API partial-PATCH path, then
  // refetch the list (drives ObjectDataTable's opt-in inline edit via the shared
  // InlineFieldValue control).
  const handleCellCommit = useCallback(
    async (recordId: string, fieldName: string, value: unknown) => {
      await apiClient.patch(`/api/${collectionName}/${recordId}`, {
        data: {
          type: collectionName,
          id: recordId,
          attributes: { [fieldName]: value },
        },
      })
      queryClient.invalidateQueries({ queryKey: ['resources', collectionName] })
    },
    [apiClient, collectionName, queryClient]
  )

  // Cell rendering + sort indicators are owned by the shared ObjectDataTable
  // (FieldRenderer / InlineFieldValue), replacing the local formatCellValue and
  // sort-indicator helpers.

  const resources = resourcesData?.data ?? []
  const totalCount = resourcesData?.total ?? 0

  // Status branch (schema error / not-found), rendered by the shell in place
  // of the list frame. Order matches the legacy early-returns.
  const statusSlot: React.ReactNode = schemaError ? (
    <div
      className="flex flex-col gap-6 p-6 w-full max-md:p-2 max-md:gap-4 max-lg:p-4"
      data-testid={testId}
    >
      <ErrorMessage
        error={schemaError instanceof Error ? schemaError : new Error(t('errors.generic'))}
        onRetry={() =>
          queryClient.invalidateQueries({ queryKey: ['collection-schema', collectionName] })
        }
      />
    </div>
  ) : !schema ? (
    <div
      className="flex flex-col gap-6 p-6 w-full max-md:p-2 max-md:gap-4 max-lg:p-4"
      data-testid={testId}
    >
      <ErrorMessage error={new Error(t('errors.notFound'))} />
    </div>
  ) : null

  return (
    <ListShell
      variant="admin"
      data-testid={testId}
      isLoading={schemaLoading}
      statusSlot={statusSlot}
      header={
        schema ? (
          <header className="flex justify-between items-start flex-wrap gap-4 max-md:flex-col max-md:items-stretch">
            <div className="flex flex-col gap-1">
              <nav
                className="flex items-center gap-1 text-sm text-muted-foreground"
                aria-label="Breadcrumb"
              >
                <Link
                  to={`/${getTenantSlug()}/resources`}
                  className="text-primary no-underline cursor-pointer hover:underline"
                >
                  {t('resources.title')}
                </Link>
                <span className="text-muted-foreground/60" aria-hidden="true">
                  /
                </span>
                <span>{schema.displayName}</span>
              </nav>
              <h1 className="m-0 text-2xl font-semibold text-foreground max-md:text-xl">
                {schema.displayName}
              </h1>
            </div>
            <div className="flex gap-2 items-center max-md:flex-col">
              {objectPermissions.canCreate && (
                <button
                  type="button"
                  className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 active:bg-primary/80 max-md:w-full"
                  onClick={handleCreate}
                  aria-label={t('resources.createRecord')}
                  data-testid="create-record-button"
                >
                  {t('resources.createRecord')}
                </button>
              )}
            </div>
          </header>
        ) : undefined
      }
      toolbar={
        schema ? (
          <>
            {/* View Selector (T10) */}
            <ViewSelector
              views={savedViews.views}
              activeView={savedViews.activeView}
              onSelectView={handleSelectView}
              onSaveView={handleSaveView}
              onDeleteView={handleDeleteView}
              onRenameView={savedViews.renameView}
              onSetDefault={savedViews.setDefaultView}
            />

            {/* Toolbar */}
            <div className="flex justify-between items-center flex-wrap gap-4 p-4 bg-muted rounded-md max-lg:flex-col max-lg:items-stretch">
              <div className="flex items-center gap-4 flex-wrap max-lg:justify-start">
                {/* Bulk Actions - Requirement 11.11, 11.12 */}
                {selectedIds.size > 0 && (
                  <div
                    className="flex items-center gap-2 px-4 py-2 bg-blue-50 dark:bg-blue-950 rounded-md"
                    data-testid="bulk-actions"
                  >
                    <span className="text-sm font-medium text-blue-700 dark:text-blue-300">
                      {selectedIds.size} {t('common.selected')}
                    </span>

                    {/* Export Dropdown - Requirement 11.12 */}
                    <div className="relative" ref={exportDropdownRef}>
                      <button
                        type="button"
                        className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-all duration-150 hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={handleToggleExportDropdown}
                        aria-expanded={showExportDropdown}
                        aria-haspopup="menu"
                        aria-label={t('resources.exportSelected')}
                        data-testid="export-button"
                      >
                        {t('resources.exportSelected')}
                      </button>
                      {showExportDropdown && (
                        <div
                          className="absolute top-full left-0 z-20 min-w-[200px] mt-1 py-2 bg-background border border-border rounded-md shadow-lg"
                          role="menu"
                          aria-label={t('resources.exportOptions')}
                          data-testid="export-dropdown"
                        >
                          <div className="flex flex-col py-1">
                            <span className="px-4 py-1 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                              {t('resources.exportSelectedLabel', { count: selectedIds.size })}
                            </span>
                            <button
                              type="button"
                              className="block w-full px-4 py-2 text-sm text-foreground text-left bg-transparent border-none cursor-pointer transition-colors duration-150 hover:bg-muted focus:outline-none focus:bg-accent"
                              onClick={() => handleExportSelected('csv')}
                              role="menuitem"
                              data-testid="export-selected-csv"
                            >
                              {t('resources.exportToCSV')}
                            </button>
                            <button
                              type="button"
                              className="block w-full px-4 py-2 text-sm text-foreground text-left bg-transparent border-none cursor-pointer transition-colors duration-150 hover:bg-muted focus:outline-none focus:bg-accent"
                              onClick={() => handleExportSelected('json')}
                              role="menuitem"
                              data-testid="export-selected-json"
                            >
                              {t('resources.exportToJSON')}
                            </button>
                          </div>
                          <div className="h-px my-1 bg-border" />
                          <div className="flex flex-col py-1">
                            <span className="px-4 py-1 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                              {t('resources.exportAllVisible', {
                                count: resourcesData?.data.length ?? 0,
                              })}
                            </span>
                            <button
                              type="button"
                              className="block w-full px-4 py-2 text-sm text-foreground text-left bg-transparent border-none cursor-pointer transition-colors duration-150 hover:bg-muted focus:outline-none focus:bg-accent"
                              onClick={() => handleExportAll('csv')}
                              role="menuitem"
                              data-testid="export-all-csv"
                            >
                              {t('resources.exportToCSV')}
                            </button>
                            <button
                              type="button"
                              className="block w-full px-4 py-2 text-sm text-foreground text-left bg-transparent border-none cursor-pointer transition-colors duration-150 hover:bg-muted focus:outline-none focus:bg-accent"
                              onClick={() => handleExportAll('json')}
                              role="menuitem"
                              data-testid="export-all-json"
                            >
                              {t('resources.exportToJSON')}
                            </button>
                          </div>
                        </div>
                      )}
                    </div>

                    {objectPermissions.canDelete && (
                      <button
                        type="button"
                        className="px-2 py-1 text-xs font-medium text-destructive bg-background border border-destructive/30 rounded cursor-pointer transition-all duration-150 hover:bg-destructive/10 hover:border-destructive focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={handleBulkDeleteClick}
                        aria-label={t('resources.bulkDelete')}
                        data-testid="bulk-delete-button"
                      >
                        {t('resources.bulkDelete')}
                      </button>
                    )}
                  </div>
                )}

                {/* Filter Toggle - Requirement 11.3 */}
                <button
                  type="button"
                  className={cn(
                    'inline-flex items-center gap-1 px-2 py-1 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-all duration-150 hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2',
                    showFilters &&
                      'bg-blue-50 dark:bg-blue-950 border-blue-300 dark:border-blue-700 text-blue-700 dark:text-blue-300'
                  )}
                  onClick={handleToggleFilters}
                  aria-expanded={showFilters}
                  aria-controls="filter-builder"
                  data-testid="filter-toggle"
                >
                  {t('common.filter')}
                  {filters.length > 0 && (
                    <span
                      className="inline-flex items-center justify-center min-w-[18px] h-[18px] px-1 text-xs font-semibold text-primary-foreground bg-primary rounded-full"
                      data-testid="filter-count"
                    >
                      {filters.length}
                    </span>
                  )}
                </button>
              </div>

              <div className="flex items-center gap-2 max-lg:justify-start">
                {/* Column Selector */}
                <div className="relative">
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 px-2 py-1 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-all duration-150 hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                    onClick={handleToggleColumnSelector}
                    aria-expanded={showColumnSelector}
                    aria-haspopup="listbox"
                    data-testid="column-selector-button"
                  >
                    Columns ({visibleColumns.size})
                  </button>
                  {showColumnSelector && (
                    <div
                      className="absolute top-full right-0 z-10 min-w-[200px] max-h-[300px] overflow-y-auto mt-1 p-2 bg-background border border-border rounded-md shadow-lg"
                      role="listbox"
                      aria-label="Select columns"
                      data-testid="column-dropdown"
                    >
                      <label className="flex items-center gap-2 px-2 py-1 text-sm text-foreground cursor-pointer rounded hover:bg-muted">
                        <input
                          type="checkbox"
                          className="w-4 h-4 cursor-pointer"
                          checked={visibleColumns.has('id')}
                          onChange={() => handleToggleColumn('id')}
                        />
                        ID
                      </label>
                      {(Array.isArray(schema.fields) ? schema.fields : []).map((field) => (
                        <label
                          key={field.name}
                          className="flex items-center gap-2 px-2 py-1 text-sm text-foreground cursor-pointer rounded hover:bg-muted"
                        >
                          <input
                            type="checkbox"
                            className="w-4 h-4 cursor-pointer"
                            checked={visibleColumns.has(field.name)}
                            onChange={() => handleToggleColumn(field.name)}
                          />
                          {field.displayName || field.name}
                        </label>
                      ))}
                    </div>
                  )}
                </div>

                {/* Inline Edit Toggle (T11) */}
                <button
                  type="button"
                  className={cn(
                    'inline-flex items-center gap-1 px-2 py-1 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-all duration-150 hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2',
                    inlineEditEnabled &&
                      'bg-blue-50 dark:bg-blue-950 border-blue-300 dark:border-blue-700 text-blue-700 dark:text-blue-300'
                  )}
                  onClick={handleToggleInlineEdit}
                  aria-pressed={inlineEditEnabled}
                  data-testid="inline-edit-toggle"
                >
                  {inlineEditEnabled ? t('inlineEdit.clickToEdit') : t('inlineEdit.clickToEdit')}
                </button>
              </div>
            </div>
          </>
        ) : undefined
      }
      filters={
        schema ? (
          <>
            {/* Filter Builder - Requirements 11.3, 11.4, 11.5 */}
            {showFilters && (
              <div
                id="filter-builder"
                className="p-4 bg-background border border-border rounded-md"
                data-testid="filter-builder"
              >
                {pendingFilters.length === 0 ? (
                  <p>{t('common.noResults')}</p>
                ) : (
                  pendingFilters.map((filter) => (
                    <div
                      key={filter.id}
                      className="flex items-center gap-2 mb-2 last:mb-0 max-md:flex-wrap"
                      data-testid={`filter-row-${filter.id}`}
                    >
                      {/* Field Selector */}
                      <select
                        className="p-2 text-sm text-foreground bg-background border border-border rounded-md transition-all duration-200 focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 min-w-[150px] max-md:min-w-full"
                        value={filter.field}
                        onChange={(e) => handleFilterFieldChange(filter.id, e.target.value)}
                        aria-label="Filter field"
                        data-testid={`filter-field-${filter.id}`}
                      >
                        {(Array.isArray(schema.fields) ? schema.fields : []).map((field) => (
                          <option key={field.name} value={field.name}>
                            {field.displayName || field.name}
                          </option>
                        ))}
                      </select>

                      {/* Operator Selector - Requirement 11.5 */}
                      <select
                        className="p-2 text-sm text-foreground bg-background border border-border rounded-md transition-all duration-200 focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 min-w-[120px] max-md:min-w-full"
                        value={filter.operator}
                        onChange={(e) =>
                          handleFilterOperatorChange(filter.id, e.target.value as FilterOperator)
                        }
                        aria-label="Filter operator"
                        data-testid={`filter-operator-${filter.id}`}
                      >
                        {FILTER_OPERATORS.map((op) => (
                          <option key={op.value} value={op.value}>
                            {op.label}
                          </option>
                        ))}
                      </select>

                      {/* Value Input */}
                      <input
                        type="text"
                        className="flex-1 p-2 text-sm text-foreground bg-background border border-border rounded-md transition-all duration-200 focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 min-w-[150px] max-md:min-w-full"
                        value={filter.value}
                        onChange={(e) => handleFilterValueChange(filter.id, e.target.value)}
                        placeholder="Value"
                        aria-label="Filter value"
                        data-testid={`filter-value-${filter.id}`}
                      />

                      {/* Remove Filter Button */}
                      <button
                        type="button"
                        className="flex items-center justify-center w-8 h-8 p-0 text-lg text-muted-foreground bg-transparent border border-border rounded-md cursor-pointer transition-all duration-150 hover:bg-destructive/10 hover:text-destructive hover:border-destructive/30 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={() => handleRemoveFilter(filter.id)}
                        aria-label="Remove filter"
                        data-testid={`remove-filter-${filter.id}`}
                      >
                        &times;
                      </button>
                    </div>
                  ))
                )}

                <div className="flex gap-2 mt-4">
                  <button
                    type="button"
                    className="px-2 py-1 text-sm font-medium text-primary bg-transparent border border-dashed border-border rounded-md cursor-pointer transition-all duration-150 hover:bg-blue-50 dark:hover:bg-blue-950 hover:border-primary focus:outline-2 focus:outline-primary focus:outline-offset-2"
                    onClick={handleAddFilter}
                    data-testid="add-filter-button"
                  >
                    + Add Filter
                  </button>
                  <button
                    type="button"
                    className="px-2 py-1 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                    onClick={handleApplyFilters}
                    data-testid="apply-filters-button"
                  >
                    Apply Filters
                  </button>
                  {(pendingFilters.length > 0 || filters.length > 0) && (
                    <button
                      type="button"
                      className="px-2 py-1 text-sm font-medium text-muted-foreground bg-transparent border border-border rounded-md cursor-pointer transition-colors duration-150 hover:bg-muted focus:outline-2 focus:outline-primary focus:outline-offset-2"
                      onClick={handleClearFilters}
                      data-testid="clear-filters-button"
                    >
                      Clear All
                    </button>
                  )}
                </div>
              </div>
            )}

            {/* Summary Stats Bar (T12) — rendered here (above the table) to
                preserve the pre-shell DOM order. */}
            {!resourcesLoading && !resourcesError && resources.length > 0 && (
              <SummaryStatsBar
                totalCount={totalCount}
                filteredCount={resources.length}
                filters={filters}
                visibleFields={visibleFields}
                records={resources as Array<Record<string, unknown>>}
                onClearFilters={handleClearFilters}
              />
            )}
          </>
        ) : undefined
      }
      table={
        // Data Table - Requirement 11.2
        schema ? (
          resourcesLoading ? (
            <div className="flex justify-center items-center min-h-[400px]">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : resourcesError ? (
            <ErrorMessage
              error={
                resourcesError instanceof Error ? resourcesError : new Error(t('errors.generic'))
              }
              onRetry={() => refetchResources()}
            />
          ) : resources.length === 0 ? (
            <div
              className="flex flex-col items-center justify-center p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="empty-state"
            >
              <p className="m-0 text-base">{t('common.noResults')}</p>
            </div>
          ) : (
            // Shared record grid (unified-record convergence): the admin resource
            // browser now renders the same ObjectDataTable as the end-user list
            // instead of a hand-built table. Admin chrome (filters, column selector,
            // saved views, export, summary stats, pagination) stays in this page as
            // ListShell slots; only the table body is shared. `onRowClick` routes to
            // the admin `/resources/...` detail; inline edit is the opt-in shared
            // control (InlineFieldValue) gated on the existing inline-edit toggle.
            <div data-testid="resources-table">
              <ObjectDataTable
                records={resources}
                fields={visibleFields}
                sort={sort}
                onSortChange={handleSortChange}
                selectedIds={selectedIds}
                onSelectionChange={setSelectedIds}
                isLoading={false}
                collectionName={collectionName || ''}
                onRowClick={handleView}
                onEdit={objectPermissions.canEdit ? handleEdit : undefined}
                onDelete={objectPermissions.canDelete ? handleDeleteClick : undefined}
                lookupDisplayMap={lookupDisplayMap}
                editable={inlineEditEnabled && objectPermissions.canEdit}
                onCellCommit={handleCellCommit}
              />
            </div>
          )
        ) : undefined
      }
      pagination={
        // Pagination - Requirement 11.2
        !resourcesLoading && !resourcesError && resources.length > 0 ? (
          <nav
            className="flex justify-between items-center flex-wrap gap-4 py-4 max-md:flex-col max-md:items-stretch"
            role="navigation"
            aria-label="Table pagination"
            data-testid="pagination"
          >
            <div className="flex items-center gap-2">
              <label className="text-sm text-muted-foreground" htmlFor="page-size-select">
                Rows per page:
              </label>
              <select
                id="page-size-select"
                className="px-2 py-1 text-sm text-foreground bg-background border border-border rounded-md focus:outline-2 focus:outline-primary focus:outline-offset-2"
                value={pageSize}
                onChange={handlePageSizeChange}
                data-testid="page-size-select"
              >
                {PAGE_SIZE_OPTIONS.map((size) => (
                  <option key={size} value={size}>
                    {size}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex items-center gap-2 max-md:justify-center">
              <button
                type="button"
                className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-all duration-150 hover:enabled:bg-muted hover:enabled:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
                disabled={page <= 1}
                onClick={() => handlePageChange(page - 1)}
                aria-label={t('common.previous')}
                data-testid="prev-page-button"
              >
                {t('common.previous')}
              </button>
              <span className="text-sm text-muted-foreground" aria-live="polite">
                Page {page} of {totalPages || 1}
              </span>
              <button
                type="button"
                className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-all duration-150 hover:enabled:bg-muted hover:enabled:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
                disabled={page >= totalPages}
                onClick={() => handlePageChange(page + 1)}
                aria-label={t('common.next')}
                data-testid="next-page-button"
              >
                {t('common.next')}
              </button>
            </div>

            <div className="text-sm text-muted-foreground/60" data-testid="total-count">
              {totalCount} total records
            </div>
          </nav>
        ) : undefined
      }
      dialogs={
        <>
          {/* Delete Confirmation Dialog */}
          <ConfirmDialog
            open={deleteDialogOpen}
            title={t('resources.deleteRecord')}
            message={t('resources.confirmDelete', { count: 1 })}
            confirmLabel={t('common.delete')}
            cancelLabel={t('common.cancel')}
            onConfirm={handleDeleteConfirm}
            onCancel={handleDeleteCancel}
            variant="danger"
          />

          {/* Bulk Delete Confirmation Dialog - Requirement 11.11 */}
          <ConfirmDialog
            open={bulkDeleteDialogOpen}
            title={t('resources.bulkDelete')}
            message={t('resources.confirmBulkDelete', { count: selectedIds.size })}
            confirmLabel={t('common.delete')}
            cancelLabel={t('common.cancel')}
            onConfirm={handleBulkDeleteConfirm}
            onCancel={handleBulkDeleteCancel}
            variant="danger"
          />
        </>
      }
    />
  )
}

export default ResourceListPage
