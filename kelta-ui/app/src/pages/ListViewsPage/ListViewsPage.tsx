import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useCollectionSummaries, type CollectionSummary } from '../../hooks/useCollectionSummaries'
import { useCollectionSchema } from '../../hooks/useCollectionSchema'
import { useToast, ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import type { CreateListViewRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { AdminDataTable, type AdminColumn } from '@/components/AdminDataTable'
import {
  ColumnsEditor,
  FilterEditor,
  SortEditor,
  type EditorField,
  type FilterRow,
  type SortRow,
} from './ListViewEditors'
import type { SavedView } from '@/hooks/useSavedViews'
import { parseTypeConfig } from '@/pages/app/ObjectListPage/listViewMapping'

/** Renderers a shared view can publish (V196); the end-user list honours all four. */
const VIEW_TYPES = ['TABLE', 'KANBAN', 'CALENDAR', 'GALLERY'] as const
type ListViewType = (typeof VIEW_TYPES)[number]

type TypeConfig = NonNullable<SavedView['typeConfig']>

function asListViewType(value: unknown): ListViewType {
  const upper = typeof value === 'string' ? value.trim().toUpperCase() : ''
  return (VIEW_TYPES as readonly string[]).includes(upper) ? (upper as ListViewType) : 'TABLE'
}

interface ListView {
  id: string
  name: string
  collectionId: string
  visibility: string
  sortField: string
  sortDirection: string
  createdBy: string
  createdAt: string
  updatedAt: string
  columns: string[]
  filters: Record<string, unknown>[]
  /** Multi-field sort (V147); first entry mirrors sortField/sortDirection for back-compat. */
  sort?: SortRow[]
  /** Published renderer + its settings (V196). */
  viewType?: string
  typeConfig?: unknown
}

/** Structured form state — no raw JSON. Columns/filters/sort are edited via schema-driven pickers. */
interface ListViewFormData {
  name: string
  collectionId: string
  visibility: string
  columns: string[]
  filters: FilterRow[]
  sort: SortRow[]
  viewType: ListViewType
  /**
   * Held whole rather than as flat lane/card fields: the calendar and gallery
   * sections are authorable through the API, CLI and MCP, and editing a view
   * here must not silently drop settings this form has no editor for.
   */
  typeConfig: TypeConfig
}

interface FormErrors {
  name?: string
  collectionId?: string
}

export interface ListViewsPageProps {
  testId?: string
}

function validateForm(data: ListViewFormData): FormErrors {
  const errors: FormErrors = {}
  if (!data.name.trim()) {
    errors.name = 'Name is required'
  } else if (data.name.length > 100) {
    errors.name = 'Name must be 100 characters or less'
  }
  if (!data.collectionId) {
    errors.collectionId = 'Collection is required'
  }
  return errors
}

/** Map a stored list view's filters (loose objects) to the editor's {field, op, value} rows. */
function toFilterRows(filters: Record<string, unknown>[] | undefined): FilterRow[] {
  if (!Array.isArray(filters)) return []
  return filters.map((f) => ({
    field: typeof f.field === 'string' ? f.field : '',
    op: typeof f.op === 'string' ? f.op : 'eq',
    value: f.value == null ? '' : String(f.value),
  }))
}

/** Seed the sort rows: prefer the multi-sort array, else the legacy single sortField/sortDirection. */
function toSortRows(listView: ListView | undefined): SortRow[] {
  if (listView?.sort && Array.isArray(listView.sort) && listView.sort.length > 0) {
    return listView.sort.map((s) => ({
      field: s.field,
      direction: s.direction === 'DESC' ? 'DESC' : 'ASC',
    }))
  }
  if (listView?.sortField) {
    return [
      { field: listView.sortField, direction: listView.sortDirection === 'DESC' ? 'DESC' : 'ASC' },
    ]
  }
  return []
}

/**
 * Serialize structured form data into the list-view API body: columns/filters/sort as JSON strings
 * (matching the existing JSON-column contract), plus the legacy single `sortField`/`sortDirection`
 * derived from the first sort row for back-compat.
 */
function serializeListViewBody(data: ListViewFormData) {
  const first = data.sort[0]
  return {
    columns: JSON.stringify(data.columns),
    filters: JSON.stringify(data.filters),
    sort: JSON.stringify(data.sort),
    sortField: first?.field ?? '',
    sortDirection: first?.direction ?? 'ASC',
    viewType: data.viewType,
    // Sent as an object, not a JSON string: the end-user list reads this back as
    // the renderer config, and a JSON string would have to be re-parsed by every
    // consumer (CLI and MCP write an object here too).
    typeConfig: Object.keys(data.typeConfig).length > 0 ? data.typeConfig : null,
  }
}

interface ListViewFormProps {
  listView?: ListView
  collections: CollectionSummary[]
  onSubmit: (data: ListViewFormData) => void
  onCancel: () => void
  isSubmitting: boolean
}

function ListViewForm({
  listView,
  collections,
  onSubmit,
  onCancel,
  isSubmitting,
}: ListViewFormProps): React.ReactElement {
  const isEditing = !!listView
  const [formData, setFormData] = useState<ListViewFormData>({
    name: listView?.name ?? '',
    collectionId: listView?.collectionId ?? '',
    visibility: listView?.visibility ?? 'PRIVATE',
    columns: listView?.columns ?? [],
    filters: toFilterRows(listView?.filters),
    sort: toSortRows(listView),
    viewType: asListViewType(listView?.viewType),
    typeConfig: parseTypeConfig(listView?.typeConfig) ?? {},
  })
  const [errors, setErrors] = useState<FormErrors>({})
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const nameInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    nameInputRef.current?.focus()
  }, [])

  // Load the selected collection's fields so columns/filters/sort are pick-lists (no typing names).
  const collectionName = useMemo(
    () => collections.find((c) => c.id === formData.collectionId)?.name,
    [collections, formData.collectionId]
  )
  const { fields: schemaFields } = useCollectionSchema(collectionName)
  const editorFields: EditorField[] = useMemo(
    () =>
      schemaFields
        .filter((f) => !['createdAt', 'updatedAt', 'createdBy', 'updatedBy'].includes(f.name))
        .map((f) => ({ name: f.name, label: f.displayName || f.name })),
    [schemaFields]
  )
  // Kanban lanes come from a picklist's values, so only picklist fields qualify.
  const laneFields: EditorField[] = useMemo(
    () =>
      schemaFields
        .filter((f) => f.type === 'picklist')
        .map((f) => ({ name: f.name, label: f.displayName || f.name })),
    [schemaFields]
  )

  const handleChange = useCallback(
    (field: 'name' | 'collectionId' | 'visibility', value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }))
      if (errors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: undefined }))
      }
    },
    [errors]
  )

  // Setters for the structured (non-string) editors.
  const setColumns = useCallback((v: string[]) => setFormData((p) => ({ ...p, columns: v })), [])
  const setFilters = useCallback((v: FilterRow[]) => setFormData((p) => ({ ...p, filters: v })), [])
  const setSort = useCallback((v: SortRow[]) => setFormData((p) => ({ ...p, sort: v })), [])
  const setViewType = useCallback(
    (v: ListViewType) => setFormData((p) => ({ ...p, viewType: v })),
    []
  )
  const setLaneField = useCallback(
    (v: string) =>
      setFormData((p) => {
        const typeConfig = { ...p.typeConfig }
        if (v) {
          typeConfig.kanban = { ...typeConfig.kanban, laneField: v }
        } else {
          delete typeConfig.kanban
        }
        return { ...p, typeConfig }
      }),
    []
  )
  const setCardFields = useCallback(
    (v: string[]) =>
      setFormData((p) => {
        // Card fields hang off the lane field; without one there is no kanban section.
        if (!p.typeConfig.kanban?.laneField) return p
        return {
          ...p,
          typeConfig: {
            ...p.typeConfig,
            kanban: { ...p.typeConfig.kanban, cardFields: v.length > 0 ? v : undefined },
          },
        }
      }),
    []
  )

  const handleBlur = useCallback(
    (field: keyof FormErrors) => {
      setTouched((prev) => ({ ...prev, [field]: true }))
      const validationErrors = validateForm(formData)
      if (validationErrors[field]) {
        setErrors((prev) => ({ ...prev, [field]: validationErrors[field] }))
      }
    },
    [formData]
  )

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      const validationErrors = validateForm(formData)
      setErrors(validationErrors)
      setTouched({ name: true, collectionId: true })
      if (Object.keys(validationErrors).length === 0) {
        onSubmit(formData)
      }
    },
    [formData, onSubmit]
  )

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
      }
    },
    [onCancel]
  )

  const title = isEditing ? 'Edit List View' : 'Create List View'

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={handleKeyDown}
      data-testid="listview-form-overlay"
      role="presentation"
    >
      <div
        className="w-full max-w-[700px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby="listview-form-title"
        data-testid="listview-form-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="listview-form-title" className="m-0 text-xl font-semibold text-foreground">
            {title}
          </h2>
          <button
            type="button"
            className="rounded p-2 text-2xl leading-none text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onCancel}
            aria-label="Close"
            data-testid="listview-form-close"
          >
            &times;
          </button>
        </div>
        <div className="p-6">
          <form className="space-y-4" onSubmit={handleSubmit} noValidate>
            <div>
              <label
                htmlFor="listview-name"
                className="mb-1 block text-sm font-medium text-foreground"
              >
                Name
                <span className="ml-0.5 text-destructive" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                ref={nameInputRef}
                id="listview-name"
                type="text"
                className={cn(
                  'w-full rounded-md border px-3 py-2 text-sm text-foreground bg-background focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary',
                  touched.name && errors.name ? 'border-destructive' : 'border-border'
                )}
                value={formData.name}
                onChange={(e) => handleChange('name', e.target.value)}
                onBlur={() => handleBlur('name')}
                placeholder="Enter list view name"
                aria-required="true"
                aria-invalid={touched.name && !!errors.name}
                disabled={isSubmitting}
                data-testid="listview-name-input"
              />
              {touched.name && errors.name && (
                <span className="mt-1 block text-xs text-destructive" role="alert">
                  {errors.name}
                </span>
              )}
            </div>

            <div>
              <label
                htmlFor="listview-collectionId"
                className="mb-1 block text-sm font-medium text-foreground"
              >
                Collection
                <span className="ml-0.5 text-destructive" aria-hidden="true">
                  *
                </span>
              </label>
              <select
                id="listview-collectionId"
                className={cn(
                  'w-full rounded-md border px-3 py-2 text-sm text-foreground bg-background focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary',
                  touched.collectionId && errors.collectionId
                    ? 'border-destructive'
                    : 'border-border'
                )}
                value={formData.collectionId}
                onChange={(e) => handleChange('collectionId', e.target.value)}
                onBlur={() => handleBlur('collectionId')}
                aria-required="true"
                aria-invalid={touched.collectionId && !!errors.collectionId}
                disabled={isSubmitting || isEditing}
                data-testid="listview-collectionId-input"
              >
                <option value="">Select a collection</option>
                {collections.map((col) => (
                  <option key={col.id} value={col.id}>
                    {col.displayName || col.name}
                  </option>
                ))}
              </select>
              {touched.collectionId && errors.collectionId && (
                <span className="mt-1 block text-xs text-destructive" role="alert">
                  {errors.collectionId}
                </span>
              )}
            </div>

            <div>
              <label
                htmlFor="listview-visibility"
                className="mb-1 block text-sm font-medium text-foreground"
              >
                Visibility
              </label>
              <select
                id="listview-visibility"
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                value={formData.visibility}
                onChange={(e) => handleChange('visibility', e.target.value)}
                disabled={isSubmitting}
                data-testid="listview-visibility-input"
              >
                <option value="PRIVATE">PRIVATE</option>
                <option value="PUBLIC">PUBLIC</option>
                <option value="GROUP">GROUP</option>
              </select>
            </div>

            <div>
              <label
                htmlFor="listview-viewType"
                className="mb-1 block text-sm font-medium text-foreground"
              >
                View type
              </label>
              <select
                id="listview-viewType"
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                value={formData.viewType}
                onChange={(e) => setViewType(e.target.value as ListViewType)}
                disabled={isSubmitting}
                data-testid="listview-viewType-input"
              >
                {VIEW_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
              <span className="mt-1 block text-xs text-muted-foreground">
                How everyone opening this view sees it. Each user can still switch renderer for
                themselves.
              </span>
            </div>

            {formData.viewType === 'KANBAN' && (
              <>
                <div>
                  <label
                    htmlFor="listview-laneField"
                    className="mb-1 block text-sm font-medium text-foreground"
                  >
                    Lane field
                  </label>
                  <select
                    id="listview-laneField"
                    className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                    value={formData.typeConfig.kanban?.laneField ?? ''}
                    onChange={(e) => setLaneField(e.target.value)}
                    disabled={isSubmitting || laneFields.length === 0}
                    data-testid="listview-laneField-input"
                  >
                    <option value="">
                      {laneFields.length === 0
                        ? 'No picklist fields on this collection'
                        : 'First picklist field'}
                    </option>
                    {laneFields.map((f) => (
                      <option key={f.name} value={f.name}>
                        {f.label}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <span className="mb-1 block text-sm font-medium text-foreground">
                    Card fields
                  </span>
                  {formData.typeConfig.kanban?.laneField ? (
                    <ColumnsEditor
                      fields={editorFields}
                      value={formData.typeConfig.kanban.cardFields ?? []}
                      onChange={setCardFields}
                      emptyHint="No card fields selected — the first columns are shown on each card."
                      idPrefix="listview-card"
                    />
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      Pick a lane field first — card fields are stored with it.
                    </p>
                  )}
                </div>
              </>
            )}

            <div>
              <span className="mb-1 block text-sm font-medium text-foreground">Columns</span>
              <ColumnsEditor fields={editorFields} value={formData.columns} onChange={setColumns} />
            </div>

            <div>
              <span className="mb-1 block text-sm font-medium text-foreground">Filters</span>
              <FilterEditor fields={editorFields} value={formData.filters} onChange={setFilters} />
            </div>

            <div>
              <span className="mb-1 block text-sm font-medium text-foreground">Sort</span>
              <SortEditor fields={editorFields} value={formData.sort} onChange={setSort} />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                className="rounded-md border border-border bg-secondary px-4 py-2 text-sm text-foreground hover:bg-muted disabled:opacity-50"
                onClick={onCancel}
                disabled={isSubmitting}
                data-testid="listview-form-cancel"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                disabled={isSubmitting}
                data-testid="listview-form-submit"
              >
                {isSubmitting ? 'Saving...' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}

export function ListViewsPage({
  testId = 'listviews-page',
}: ListViewsPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()
  const { showToast } = useToast()

  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editingListView, setEditingListView] = useState<ListView | undefined>(undefined)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [listViewToDelete, setListViewToDelete] = useState<ListView | null>(null)

  const {
    data: listViews,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['listviews'],
    queryFn: () => keltaClient.admin.listViews.list('', ''),
  })

  const { summaries: collections } = useCollectionSummaries()

  const collectionMap = useMemo(() => {
    const map = new Map<string, CollectionSummary>()
    for (const col of collections) {
      map.set(col.id, col)
    }
    return map
  }, [collections])

  const listViewList: ListView[] = (listViews ?? []) as unknown as ListView[]

  const createMutation = useMutation({
    mutationFn: (data: ListViewFormData) => {
      const payload = {
        collectionId: data.collectionId,
        name: data.name,
        visibility: data.visibility,
        ...serializeListViewBody(data),
      }
      return keltaClient.admin.listViews.create('', '', payload as CreateListViewRequest)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['listviews'] })
      showToast('List view created successfully', 'success')
      handleCloseForm()
    },
    onError: (err: Error) => {
      showToast(err.message || 'An error occurred', 'error')
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: ListViewFormData }) => {
      const payload = {
        name: data.name,
        visibility: data.visibility,
        ...serializeListViewBody(data),
      }
      return keltaClient.admin.listViews.update(id, payload as Partial<CreateListViewRequest>)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['listviews'] })
      showToast('List view updated successfully', 'success')
      handleCloseForm()
    },
    onError: (err: Error) => {
      showToast(err.message || 'An error occurred', 'error')
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.listViews.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['listviews'] })
      showToast('List view deleted successfully', 'success')
      setDeleteDialogOpen(false)
      setListViewToDelete(null)
    },
    onError: (err: Error) => {
      showToast(err.message || 'An error occurred', 'error')
    },
  })

  const handleCreate = useCallback(() => {
    setEditingListView(undefined)
    setIsFormOpen(true)
  }, [])

  const handleEdit = useCallback((lv: ListView) => {
    setEditingListView(lv)
    setIsFormOpen(true)
  }, [])

  const handleCloseForm = useCallback(() => {
    setIsFormOpen(false)
    setEditingListView(undefined)
  }, [])

  const handleFormSubmit = useCallback(
    (data: ListViewFormData) => {
      if (editingListView) {
        updateMutation.mutate({ id: editingListView.id, data })
      } else {
        createMutation.mutate(data)
      }
    },
    [editingListView, createMutation, updateMutation]
  )

  const handleDeleteClick = useCallback((lv: ListView) => {
    setListViewToDelete(lv)
    setDeleteDialogOpen(true)
  }, [])

  const handleDeleteConfirm = useCallback(() => {
    if (listViewToDelete) {
      deleteMutation.mutate(listViewToDelete.id)
    }
  }, [listViewToDelete, deleteMutation])

  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
    setListViewToDelete(null)
  }, [])

  const getCollectionName = useCallback(
    (collectionId: string): string => {
      const col = collectionMap.get(collectionId)
      return col ? col.displayName || col.name : collectionId
    },
    [collectionMap]
  )

  const getVisibilityBadgeClass = (visibility: string): string => {
    switch (visibility) {
      case 'PUBLIC':
        return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
      case 'GROUP':
        return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
      default:
        return 'bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-300'
    }
  }

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading..." />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error('An error occurred')}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      <header className="flex items-center justify-between">
        <h1 className="m-0 text-2xl font-semibold text-foreground">List Views</h1>
        <button
          type="button"
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={handleCreate}
          aria-label="Create List View"
          data-testid="add-listview-button"
        >
          Create List View
        </button>
      </header>

      {listViewList.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card p-16 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          <p>No list views found.</p>
        </div>
      ) : (
        <div
          className="overflow-x-auto rounded-lg border border-border bg-card"
          role="grid"
          aria-label="List Views"
          data-testid="listviews-table"
        >
          <AdminDataTable
            tableId="list-views"
            rows={listViewList}
            rowKey={(lv) => lv.id}
            columns={
              [
                { id: 'name', header: 'Name', accessor: (r) => r.name },
                {
                  id: 'collection',
                  header: 'Collection',
                  accessor: (r) => getCollectionName(r.collectionId),
                },
                {
                  id: 'visibility',
                  header: 'Visibility',
                  accessor: (r) => r.visibility,
                  cell: (r) => (
                    <span
                      className={cn(
                        'inline-block rounded px-2 py-0.5 text-xs font-medium',
                        getVisibilityBadgeClass(r.visibility)
                      )}
                    >
                      {r.visibility}
                    </span>
                  ),
                },
                { id: 'createdBy', header: 'Created By', accessor: (r) => r.createdBy },
                {
                  id: 'createdAt',
                  header: 'Created',
                  accessor: (r) => r.createdAt,
                  cell: (r) =>
                    formatDate(new Date(r.createdAt), {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    }),
                },
              ] as AdminColumn<ListView>[]
            }
            renderActions={(lv) => {
              const index = listViewList.indexOf(lv)
              return (
                <div className="flex justify-end gap-2">
                  <button
                    type="button"
                    className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-primary hover:border-primary hover:bg-muted"
                    onClick={() => handleEdit(lv)}
                    aria-label={`Edit ${lv.name}`}
                    data-testid={`edit-button-${index}`}
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-destructive hover:border-destructive hover:bg-destructive/10"
                    onClick={() => handleDeleteClick(lv)}
                    aria-label={`Delete ${lv.name}`}
                    data-testid={`delete-button-${index}`}
                  >
                    Delete
                  </button>
                </div>
              )
            }}
          />
        </div>
      )}

      {isFormOpen && (
        <ListViewForm
          listView={editingListView}
          collections={collections}
          onSubmit={handleFormSubmit}
          onCancel={handleCloseForm}
          isSubmitting={isSubmitting}
        />
      )}

      <ConfirmDialog
        open={deleteDialogOpen}
        title="Delete List View"
        message="Are you sure you want to delete this list view? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
        variant="danger"
      />
    </div>
  )
}

export default ListViewsPage
