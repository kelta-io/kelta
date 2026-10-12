/**
 * ListViewsPage
 *
 * Setup → List Views: shared list views per collection, with create / edit /
 * delete built on the shared AdminCrud building block. Columns, filters, sort
 * and the published renderer (kanban lanes + card fields) are edited through
 * schema-driven pickers in {@link ListViewDesignFields}, which read the form
 * from the dialog's react-hook-form context.
 */

import React, { useCallback, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useFormContext, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useCollectionSummaries, type CollectionSummary } from '../../hooks/useCollectionSummaries'
import { useCollectionSchema } from '../../hooks/useCollectionSchema'
import { ConfirmDialog } from '../../components'
import type { CreateListViewRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { AdminDataTable, type AdminColumn } from '@/components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '@/components/AdminCrud'
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

const VISIBILITY_OPTIONS = ['PRIVATE', 'PUBLIC', 'GROUP'].map((v) => ({ value: v, label: v }))
const VIEW_TYPE_OPTIONS = VIEW_TYPES.map((v) => ({ value: v, label: v }))

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

/**
 * Structured form state — no raw JSON. Columns/filters/sort are edited via
 * schema-driven pickers. `typeConfig` is held whole rather than as flat
 * lane/card fields: the calendar and gallery sections are authorable through
 * the API, CLI and MCP, and editing a view here must not silently drop
 * settings this form has no editor for. The structured values are passed
 * through untouched (`z.custom`), never re-shaped by the schema.
 */
const listViewSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or less'),
  collectionId: z.string().refine((v) => v !== '', 'Collection is required'),
  visibility: z.string(),
  columns: z.array(z.string()),
  filters: z.custom<FilterRow[]>(),
  sort: z.custom<SortRow[]>(),
  viewType: z.enum(VIEW_TYPES),
  typeConfig: z.custom<TypeConfig>(),
})

type ListViewFormData = z.infer<typeof listViewSchema>

export interface ListViewsPageProps {
  testId?: string
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

function listViewFormDefaults(listView: ListView | undefined): ListViewFormData {
  return {
    name: listView?.name ?? '',
    collectionId: listView?.collectionId ?? '',
    visibility: listView?.visibility ?? 'PRIVATE',
    columns: listView?.columns ?? [],
    filters: toFilterRows(listView?.filters),
    sort: toSortRows(listView),
    viewType: asListViewType(listView?.viewType),
    typeConfig: parseTypeConfig(listView?.typeConfig) ?? {},
  }
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

const SECTION_LABEL_CLASS =
  'text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground'

const LANE_SELECT_CLASS =
  'border-input h-9 w-full min-w-0 rounded-md border bg-transparent px-3 py-1 text-base shadow-xs outline-none disabled:cursor-not-allowed disabled:opacity-50 md:text-sm dark:bg-input/30 focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]'

/**
 * The schema-driven part of the list-view form: kanban lane + card fields (for
 * KANBAN views) and the columns / filters / sort pickers, all scoped to the
 * selected collection's fields. Reads and writes the dialog's form via context.
 */
function ListViewDesignFields({
  collections,
  isSubmitting,
}: {
  collections: CollectionSummary[]
  isSubmitting: boolean
}): React.ReactElement {
  const { control, getValues, setValue } = useFormContext<ListViewFormData>()
  const [collectionId, viewType, typeConfig, columns, filters, sort] = useWatch({
    control,
    name: ['collectionId', 'viewType', 'typeConfig', 'columns', 'filters', 'sort'],
  })

  // Load the selected collection's fields so columns/filters/sort are pick-lists (no typing names).
  const collectionName = useMemo(
    () => collections.find((c) => c.id === collectionId)?.name,
    [collections, collectionId]
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

  const setColumns = useCallback((v: string[]) => setValue('columns', v), [setValue])
  const setFilters = useCallback((v: FilterRow[]) => setValue('filters', v), [setValue])
  const setSort = useCallback((v: SortRow[]) => setValue('sort', v), [setValue])
  const setLaneField = useCallback(
    (v: string) => {
      const next = { ...getValues('typeConfig') }
      if (v) {
        next.kanban = { ...next.kanban, laneField: v }
      } else {
        delete next.kanban
      }
      setValue('typeConfig', next)
    },
    [getValues, setValue]
  )
  const setCardFields = useCallback(
    (v: string[]) => {
      const current = getValues('typeConfig')
      // Card fields hang off the lane field; without one there is no kanban section.
      if (!current.kanban?.laneField) return
      setValue('typeConfig', {
        ...current,
        kanban: { ...current.kanban, cardFields: v.length > 0 ? v : undefined },
      })
    },
    [getValues, setValue]
  )

  return (
    <>
      {viewType === 'KANBAN' && (
        <>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="listview-laneField" className={SECTION_LABEL_CLASS}>
              Lane field
            </label>
            <select
              id="listview-laneField"
              className={LANE_SELECT_CLASS}
              value={typeConfig.kanban?.laneField ?? ''}
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

          <div className="flex flex-col gap-1.5">
            <span className={SECTION_LABEL_CLASS}>Card fields</span>
            {typeConfig.kanban?.laneField ? (
              <ColumnsEditor
                fields={editorFields}
                value={typeConfig.kanban.cardFields ?? []}
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

      <div className="flex flex-col gap-1.5">
        <span className={SECTION_LABEL_CLASS}>Columns</span>
        <ColumnsEditor fields={editorFields} value={columns} onChange={setColumns} />
      </div>

      <div className="flex flex-col gap-1.5">
        <span className={SECTION_LABEL_CLASS}>Filters</span>
        <FilterEditor fields={editorFields} value={filters} onChange={setFilters} />
      </div>

      <div className="flex flex-col gap-1.5">
        <span className={SECTION_LABEL_CLASS}>Sort</span>
        <SortEditor fields={editorFields} value={sort} onChange={setSort} />
      </div>
    </>
  )
}

function getVisibilityBadgeClass(visibility: string): string {
  switch (visibility) {
    case 'PUBLIC':
      return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
    case 'GROUP':
      return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
    default:
      return 'bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-300'
  }
}

export function ListViewsPage({
  testId = 'listviews-page',
}: ListViewsPageProps): React.ReactElement {
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()

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

  const collectionOptions = useMemo(
    () => collections.map((col) => ({ value: col.id, label: col.displayName || col.name })),
    [collections]
  )

  const listViewList: ListView[] = (listViews ?? []) as unknown as ListView[]

  const crud = useAdminCrud<ListView, ListViewFormData>({
    queryKey: ['listviews'],
    create: (data) => {
      const payload = {
        collectionId: data.collectionId,
        name: data.name,
        visibility: data.visibility,
        ...serializeListViewBody(data),
      }
      return keltaClient.admin.listViews.create('', '', payload as CreateListViewRequest)
    },
    update: (listView, data) => {
      const payload = {
        name: data.name,
        visibility: data.visibility,
        ...serializeListViewBody(data),
      }
      return keltaClient.admin.listViews.update(
        listView.id,
        payload as Partial<CreateListViewRequest>
      )
    },
    remove: (listView) => keltaClient.admin.listViews.delete(listView.id),
    messages: {
      created: 'List view created successfully',
      updated: 'List view updated successfully',
      deleted: 'List view deleted successfully',
      error: 'An error occurred',
    },
  })

  const getCollectionName = useCallback(
    (collectionId: string): string => {
      const col = collectionMap.get(collectionId)
      return col ? col.displayName || col.name : collectionId
    },
    [collectionMap]
  )

  const columns = useMemo<AdminColumn<ListView>[]>(
    () => [
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
    ],
    [formatDate, getCollectionName]
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title="List Views"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="Create List View"
          data-testid="add-listview-button"
        >
          Create List View
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={listViewList.length === 0}
      emptyMessage="No list views found."
      list={
        <AdminDataTable
          tableId="list-views"
          testId="listviews-table"
          ariaLabel="List Views"
          columns={columns}
          rows={listViewList}
          rowKey={(lv) => lv.id}
          renderActions={(lv, index) => (
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(lv)}
                aria-label={`Edit ${lv.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(lv)}
                aria-label={`Delete ${lv.name}`}
                data-testid={`delete-button-${index}`}
              >
                Delete
              </Button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<ListViewFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit List View' : 'Create List View'}
        schema={listViewSchema}
        defaultValues={listViewFormDefaults(editing)}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="listview"
        className="sm:max-w-[700px]"
      >
        <CrudTextField<ListViewFormData>
          name="name"
          id="listview-name"
          label="Name"
          required
          placeholder="Enter list view name"
          testId="listview-name-input"
        />
        <CrudSelectField<ListViewFormData>
          name="collectionId"
          id="listview-collectionId"
          label="Collection"
          required
          placeholder="Select a collection"
          options={collectionOptions}
          disabled={!!editing}
          testId="listview-collectionId-input"
        />
        <CrudSelectField<ListViewFormData>
          name="visibility"
          id="listview-visibility"
          label="Visibility"
          options={VISIBILITY_OPTIONS}
          testId="listview-visibility-input"
        />
        <CrudSelectField<ListViewFormData>
          name="viewType"
          id="listview-viewType"
          label="View type"
          options={VIEW_TYPE_OPTIONS}
          hint="How everyone opening this view sees it. Each user can still switch renderer for themselves."
          testId="listview-viewType-input"
        />
        <ListViewDesignFields collections={collections} isSubmitting={crud.isSaving} />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete List View"
        message="Are you sure you want to delete this list view? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}

export default ListViewsPage
