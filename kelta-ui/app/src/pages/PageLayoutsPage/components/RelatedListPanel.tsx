/**
 * RelatedListPanel Component
 *
 * Panel for managing related lists in the layout editor.
 * Shows configured related lists with edit/delete, plus a form dialog
 * (the shared AdminCrud `CrudFormDialog`) to add or edit related lists with
 * dropdown selectors for collections, relationship fields, display columns,
 * and sort fields. The display-column list (check + drag/Alt+Arrow reorder)
 * is a page-specific control that joins the form through `useFormContext`.
 */

import React, { useState, useCallback, useMemo } from 'react'
import { Pencil, X } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { useFormContext, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { useApi } from '../../../context/ApiContext'
import {
  CRUD_FIELD_LABEL_CLASS,
  CrudFormDialog,
  CrudNumberField,
  CrudSelectField,
  type CrudSelectOption,
} from '../../../components/AdminCrud'
import { useCollectionSummaries } from '../../../hooks/useCollectionSummaries'
import { useLayoutEditor, type EditorRelatedList } from './LayoutEditorContext'
import {
  computeDisplayOrder,
  reorderColumns,
  selectedInDisplayOrder,
  swapAdjacent,
} from './relatedListColumnOrder'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface CollectionFieldDetail {
  id: string
  name: string
  displayName: string
  type: string
  required: boolean
  referenceTarget?: string
  referenceCollectionId?: string
}

interface CollectionDetailResponse {
  id: string
  name: string
  displayName: string
  fields: CollectionFieldDetail[]
}

const relatedListSchema = z.object({
  relatedCollectionId: z.string().refine((v) => v !== '', 'Related collection is required'),
  relationshipFieldId: z.string().refine((v) => v !== '', 'Relationship field is required'),
  selectedDisplayColumns: z.array(z.string()),
  sortField: z.string(),
  sortDirection: z.string(),
  rowLimit: z.number(),
})

type RelatedListFormData = z.infer<typeof relatedListSchema>

const INITIAL_FORM_DATA: RelatedListFormData = {
  relatedCollectionId: '',
  relationshipFieldId: '',
  selectedDisplayColumns: [],
  sortField: '',
  sortDirection: 'ASC',
  rowLimit: 10,
}

const SORT_DIRECTION_OPTIONS: CrudSelectOption[] = [
  { value: 'ASC', label: 'Ascending' },
  { value: 'DESC', label: 'Descending' },
]

/** An empty row limit (or one that is not a number) falls back to 10. */
const parseRowLimit = (raw: string): number => parseInt(raw, 10) || 10

/**
 * Saved display columns may be a parsed JSON array (JSONB), a JSON string
 * `'["col1","col2"]'`, or CSV `'col1,col2'`.
 */
function parseDisplayColumns(displayColumns: unknown): string[] {
  if (!displayColumns) return []
  if (Array.isArray(displayColumns)) {
    return displayColumns.map(String).filter(Boolean)
  }
  if (typeof displayColumns !== 'string') return []
  const trimmed = displayColumns.trim()
  const fromCsv = () =>
    trimmed
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  if (!trimmed.startsWith('[')) return fromCsv()
  try {
    const parsed = JSON.parse(trimmed) as unknown
    return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : []
  } catch {
    return fromCsv()
  }
}

const REFERENCE_FIELD_TYPES = new Set(['reference', 'master_detail', 'lookup'])

function isReferenceField(type: string): boolean {
  return REFERENCE_FIELD_TYPES.has(type.toLowerCase())
}

// ---------------------------------------------------------------------------
// Form controls (rendered inside the CrudFormDialog's form context)
// ---------------------------------------------------------------------------

/** Picking another related collection clears the fields that depend on it. */
function RelatedCollectionSelect({
  options,
  onCollectionChange,
}: {
  options: CrudSelectOption[]
  onCollectionChange: (collectionId: string) => void
}): React.ReactElement {
  const { setValue } = useFormContext<RelatedListFormData>()
  return (
    <CrudSelectField<RelatedListFormData>
      name="relatedCollectionId"
      id="rl-collection"
      label="Related Collection"
      required
      placeholder="Select a collection..."
      options={options}
      testId="rl-form-collection"
      onValueChange={(value) => {
        setValue('relationshipFieldId', '')
        setValue('selectedDisplayColumns', [])
        setValue('sortField', '')
        onCollectionChange(value)
      }}
    />
  )
}

interface DisplayColumnsFieldProps {
  hasCollection: boolean
  /** Every displayable field name, in render order (user-arranged first). */
  displayOrder: string[]
  fieldByName: Map<string, CollectionFieldDetail>
  /** Receives the new full order after a drag or Alt+Arrow move. */
  onReorder: (order: string[]) => void
}

/** Check the columns to show and drag (or Alt+Up/Down) them into order. */
function DisplayColumnsField({
  hasCollection,
  displayOrder,
  fieldByName,
  onReorder,
}: DisplayColumnsFieldProps): React.ReactElement {
  const { control, setValue } = useFormContext<RelatedListFormData>()
  const selected = useWatch({ control, name: 'selectedDisplayColumns' })
  const [draggedColumn, setDraggedColumn] = useState<string | null>(null)
  const [dragOverColumn, setDragOverColumn] = useState<string | null>(null)

  const toggle = (fieldName: string) => {
    setValue(
      'selectedDisplayColumns',
      selected.includes(fieldName)
        ? selected.filter((c) => c !== fieldName)
        : [...selected, fieldName],
      { shouldDirty: true }
    )
  }

  const handleDragStart = (e: React.DragEvent<HTMLDivElement>, fieldName: string) => {
    setDraggedColumn(fieldName)
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', fieldName)
  }

  const handleDragEnd = () => {
    setDraggedColumn(null)
    setDragOverColumn(null)
  }

  const handleDragOver = (e: React.DragEvent<HTMLDivElement>, fieldName: string) => {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    if (draggedColumn && draggedColumn !== fieldName) {
      setDragOverColumn(fieldName)
    }
  }

  const handleDrop = (e: React.DragEvent<HTMLDivElement>, targetName: string) => {
    e.preventDefault()
    setDragOverColumn(null)
    if (!draggedColumn || draggedColumn === targetName) return
    onReorder(reorderColumns(displayOrder, draggedColumn, targetName))
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>, index: number) => {
    if (!e.altKey) return
    if (e.key === 'ArrowUp' && index > 0) {
      e.preventDefault()
      onReorder(swapAdjacent(displayOrder, index, -1))
    } else if (e.key === 'ArrowDown' && index < displayOrder.length - 1) {
      e.preventDefault()
      onReorder(swapAdjacent(displayOrder, index, 1))
    }
  }

  return (
    <fieldset className="m-0 flex flex-col gap-1.5 border-none p-0">
      <legend className={CRUD_FIELD_LABEL_CLASS}>Display Columns</legend>
      {!hasCollection ? (
        <div className="text-[12px] text-muted-foreground">Select a collection first</div>
      ) : displayOrder.length === 0 ? (
        <div className="text-[12px] text-muted-foreground">No fields available</div>
      ) : (
        <>
          <div
            className="flex max-h-[200px] flex-col gap-1 overflow-y-auto rounded-md border border-input bg-background p-2"
            data-testid="rl-form-display-columns"
            role="list"
            aria-label="Display columns, drag to reorder"
          >
            {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
            {displayOrder.map((fieldName, index) => {
              const f = fieldByName.get(fieldName)
              if (!f) return null
              const checked = selected.includes(f.name)
              const isDragging = draggedColumn === f.name
              const isDragOver = dragOverColumn === f.name
              return (
                <div
                  key={f.id}
                  className={`flex items-center gap-2 rounded px-1.5 py-1 text-[13px] text-foreground cursor-grab transition-colors duration-150 hover:bg-muted focus:outline-2 focus:outline-primary focus:-outline-offset-2 ${
                    isDragging ? 'opacity-50 bg-muted' : ''
                  } ${isDragOver ? 'bg-primary/10 shadow-[inset_0_-2px_0_hsl(var(--primary))]' : ''}`}
                  role="listitem"
                  draggable
                  tabIndex={0}
                  onDragStart={(e) => handleDragStart(e, f.name)}
                  onDragEnd={handleDragEnd}
                  onDragOver={(e) => handleDragOver(e, f.name)}
                  onDragLeave={() => setDragOverColumn(null)}
                  onDrop={(e) => handleDrop(e, f.name)}
                  onKeyDown={(e) => handleKeyDown(e, index)}
                  aria-label={`${f.displayName || f.name}${checked ? ', selected' : ''}`}
                  data-testid={`rl-form-column-${f.name}`}
                >
                  <span
                    className="flex h-4 w-4 shrink-0 items-center justify-center text-muted-foreground active:cursor-grabbing"
                    aria-hidden="true"
                    data-testid={`rl-form-column-drag-handle-${f.name}`}
                  >
                    <span className="text-[12px] leading-none tracking-[-2px]">
                      &#x22EE;&#x22EE;
                    </span>
                  </span>
                  <input
                    type="checkbox"
                    className="h-3.5 w-3.5 accent-primary cursor-pointer"
                    checked={checked}
                    onChange={() => toggle(f.name)}
                    onClick={(e) => e.stopPropagation()}
                    aria-label={`Include ${f.displayName || f.name}`}
                    data-testid={`rl-form-column-checkbox-${f.name}`}
                  />
                  <span className="overflow-hidden text-ellipsis whitespace-nowrap">
                    {f.displayName || f.name}
                  </span>
                </div>
              )
            })}
            {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
          </div>
          <div className="text-[11px] text-muted-foreground">
            Drag rows or press Alt+Up/Down to reorder columns.
          </div>
        </>
      )}
    </fieldset>
  )
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function RelatedListPanel(): React.ReactElement {
  const { state, addRelatedList, updateRelatedList, removeRelatedList } = useLayoutEditor()
  const { relatedLists, collectionId } = state
  const { apiClient } = useApi()

  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editing, setEditing] = useState<EditorRelatedList | null>(null)
  // Mirrors the form's related collection: drives the field fetch the
  // relationship / column / sort options are built from.
  const [selectedCollectionId, setSelectedCollectionId] = useState('')
  const [orderedFieldNames, setOrderedFieldNames] = useState<string[]>([])

  // Fetch all collections for the dropdown
  const { summaries: collections } = useCollectionSummaries()

  // Build a map of collection id -> display name for display
  const collectionNameMap = useMemo(() => {
    const map = new Map<string, string>()
    for (const col of collections) {
      map.set(col.id, col.displayName || col.name)
    }
    return map
  }, [collections])

  // Look up collection names by ID for reference-target matching
  const collectionNameById = useMemo(() => {
    const map = new Map<string, string>()
    for (const col of collections) {
      map.set(col.id, col.name)
    }
    return map
  }, [collections])

  const currentCollectionName = collectionId ? (collectionNameById.get(collectionId) ?? null) : null

  // Fetch the current layout collection's fields (to find master-side relationships).
  // Fields are separate records fetched via ?include=fields.
  const { data: currentCollectionDetail } = useQuery({
    queryKey: ['collection-detail-for-rl', collectionId],
    queryFn: async () => {
      const raw = await apiClient.get<{
        data: { type: string; id: string; attributes: Record<string, unknown> }
        included?: { type: string; id: string; attributes: Record<string, unknown> }[]
      }>(`/api/collections/${collectionId}?include=fields`)

      const result: CollectionDetailResponse = {
        id: raw.data.id,
        name: raw.data.attributes.name as string,
        displayName: raw.data.attributes.displayName as string,
        fields: [],
      }

      if (raw.included && raw.included.length > 0) {
        result.fields = raw.included
          .filter((r) => r.type === 'fields')
          .map((r) => ({ id: r.id, ...r.attributes }) as unknown as CollectionFieldDetail)
      }

      return result
    },
    enabled: !!collectionId,
  })

  const currentCollectionFields = useMemo<CollectionFieldDetail[]>(
    () => currentCollectionDetail?.fields ?? [],
    [currentCollectionDetail]
  )

  // Fetch fields for the selected related collection
  const { data: relatedCollectionDetail } = useQuery({
    queryKey: ['collection-detail-for-rl', selectedCollectionId],
    queryFn: async () => {
      const raw = await apiClient.get<{
        data: { type: string; id: string; attributes: Record<string, unknown> }
        included?: { type: string; id: string; attributes: Record<string, unknown> }[]
      }>(`/api/collections/${selectedCollectionId}?include=fields`)

      const result: CollectionDetailResponse = {
        id: raw.data.id,
        name: raw.data.attributes.name as string,
        displayName: raw.data.attributes.displayName as string,
        fields: [],
      }

      if (raw.included && raw.included.length > 0) {
        result.fields = raw.included
          .filter((r) => r.type === 'fields')
          .map((r) => ({ id: r.id, ...r.attributes }) as unknown as CollectionFieldDetail)
      }

      return result
    },
    enabled: !!selectedCollectionId,
  })

  const relatedCollectionFields = useMemo<CollectionFieldDetail[]>(
    () => relatedCollectionDetail?.fields ?? [],
    [relatedCollectionDetail]
  )

  // Look up selected related collection name for reference-target matching
  const selectedCollectionName = selectedCollectionId
    ? (collectionNameById.get(selectedCollectionId) ?? null)
    : null

  // Relationship fields from BOTH sides:
  // 1. Fields on the related collection that point to the current collection (detail → master)
  // 2. Fields on the current collection that point to the related collection (master → detail)
  interface RelationshipFieldOption {
    id: string
    label: string
    source: 'related' | 'current'
  }

  const relationshipFieldOptions = useMemo<RelationshipFieldOption[]>(() => {
    if (!selectedCollectionId) return []
    const options: RelationshipFieldOption[] = []

    // Fields on the related collection pointing to the current collection
    for (const f of relatedCollectionFields) {
      if (
        isReferenceField(f.type) &&
        (f.referenceTarget === currentCollectionName || f.referenceCollectionId === collectionId)
      ) {
        const relatedDisplayName =
          collectionNameMap.get(selectedCollectionId) || selectedCollectionName || 'Related'
        options.push({
          id: f.id,
          label: `${f.displayName || f.name} (on ${relatedDisplayName})`,
          source: 'related',
        })
      }
    }

    // Fields on the current collection pointing to the related collection
    for (const f of currentCollectionFields) {
      if (
        isReferenceField(f.type) &&
        (f.referenceTarget === selectedCollectionName ||
          f.referenceCollectionId === selectedCollectionId)
      ) {
        const currentDisplayName =
          collectionNameMap.get(collectionId!) || currentCollectionName || 'Current'
        options.push({
          id: f.id,
          label: `${f.displayName || f.name} (on ${currentDisplayName})`,
          source: 'current',
        })
      }
    }

    return options
  }, [
    selectedCollectionId,
    relatedCollectionFields,
    currentCollectionFields,
    currentCollectionName,
    selectedCollectionName,
    collectionId,
    collectionNameMap,
  ])

  // Non-reference fields for display columns and sort
  const displayableFields = useMemo(
    () => relatedCollectionFields.filter((f) => !isReferenceField(f.type)),
    [relatedCollectionFields]
  )

  // Build a map of field id -> display name (from both collections)
  const fieldNameMap = useMemo(() => {
    const map = new Map<string, string>()
    for (const f of relatedCollectionFields) {
      map.set(f.id, f.displayName || f.name)
    }
    for (const f of currentCollectionFields) {
      if (!map.has(f.id)) {
        map.set(f.id, f.displayName || f.name)
      }
    }
    return map
  }, [relatedCollectionFields, currentCollectionFields])

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  const handleOpenForm = useCallback(() => {
    setEditing(null)
    setSelectedCollectionId('')
    setOrderedFieldNames([])
    setIsFormOpen(true)
  }, [])

  const handleEdit = useCallback((rl: EditorRelatedList) => {
    setEditing(rl)
    setSelectedCollectionId(rl.relatedCollectionId)
    // Seed user-arranged order from saved columns so reopening preserves it
    setOrderedFieldNames(parseDisplayColumns(rl.displayColumns))
    setIsFormOpen(true)
  }, [])

  const handleCloseForm = useCallback(() => {
    setIsFormOpen(false)
    setEditing(null)
  }, [])

  const handleCollectionChange = useCallback((collectionId: string) => {
    setSelectedCollectionId(collectionId)
    setOrderedFieldNames([])
  }, [])

  // Render order = user-arranged prefix (orderedFieldNames) followed by any
  // remaining displayable fields in their natural order. Computed each render
  // so we don't need an effect to reconcile when fields load or change.
  const displayOrder = useMemo(
    () =>
      computeDisplayOrder(
        displayableFields.map((f) => f.name),
        orderedFieldNames
      ),
    [displayableFields, orderedFieldNames]
  )

  const fieldByName = useMemo(() => {
    const map = new Map<string, CollectionFieldDetail>()
    for (const f of displayableFields) map.set(f.name, f)
    return map
  }, [displayableFields])

  const handleSubmit = useCallback(
    (values: RelatedListFormData) => {
      // Persist in the order the user arranged in the dialog: filter the
      // ordered list to checked items, falling back to the toggle order if
      // the ordered list hasn't been seeded yet.
      const orderedSelection =
        displayOrder.length > 0
          ? selectedInDisplayOrder(displayOrder, values.selectedDisplayColumns)
          : values.selectedDisplayColumns
      // Store as JSON array string since the backend column is JSONB
      const displayColumns = JSON.stringify(orderedSelection)
      const relatedList = {
        relatedCollectionId: values.relatedCollectionId,
        relationshipFieldId: values.relationshipFieldId,
        displayColumns,
        sortField: values.sortField || undefined,
        sortDirection: values.sortDirection,
        rowLimit: values.rowLimit,
      }

      if (editing) {
        updateRelatedList(editing.id, relatedList)
      } else {
        const maxSortOrder = relatedLists.reduce((max, r) => Math.max(max, r.sortOrder), -1)
        addRelatedList({ ...relatedList, sortOrder: maxSortOrder + 1 })
      }
      handleCloseForm()
    },
    [displayOrder, editing, relatedLists, addRelatedList, updateRelatedList, handleCloseForm]
  )

  const collectionOptions = useMemo<CrudSelectOption[]>(
    () =>
      collections
        .filter((c) => c.id !== collectionId)
        .map((c) => ({ value: c.id, label: c.displayName || c.name })),
    [collections, collectionId]
  )

  const sortFieldOptions = useMemo<CrudSelectOption[]>(
    () => displayableFields.map((f) => ({ value: f.name, label: f.displayName || f.name })),
    [displayableFields]
  )

  const handleDelete = useCallback(
    (relatedListId: string) => {
      removeRelatedList(relatedListId)
    },
    [removeRelatedList]
  )

  const sortedRelatedLists = useMemo(
    () => [...relatedLists].sort((a, b) => a.sortOrder - b.sortOrder),
    [relatedLists]
  )

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div className="flex flex-col gap-3 px-4 py-3" data-testid="related-list-panel">
      <h3 className="m-0 text-sm font-semibold text-foreground">Related Lists</h3>

      {sortedRelatedLists.length === 0 ? (
        <div className="p-4 text-center text-[13px] text-muted-foreground">
          No related lists configured
        </div>
      ) : (
        sortedRelatedLists.map((rl) => (
          <div
            key={rl.id}
            className="flex items-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-[13px]"
            data-testid={`related-list-${rl.id}`}
          >
            <div className="min-w-0 flex-1 overflow-hidden">
              <div className="overflow-hidden text-ellipsis whitespace-nowrap font-medium text-foreground">
                {collectionNameMap.get(rl.relatedCollectionId) || rl.relatedCollectionId}
              </div>
              <div className="text-[11px] text-muted-foreground">
                {fieldNameMap.get(rl.relationshipFieldId) || rl.relationshipFieldId} | Limit:{' '}
                {rl.rowLimit} | {rl.sortDirection}
              </div>
            </div>
            <button
              type="button"
              className="flex h-6 w-6 items-center justify-center rounded border-none bg-transparent p-0 text-muted-foreground cursor-pointer transition-colors duration-150 hover:bg-primary/10 hover:text-primary focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-2 motion-reduce:transition-none"
              onClick={() => handleEdit(rl)}
              aria-label="Edit related list"
              title="Edit related list"
              data-testid={`related-list-edit-${rl.id}`}
            >
              <Pencil size={14} />
            </button>
            <button
              type="button"
              className="flex h-6 w-6 items-center justify-center rounded border-none bg-transparent p-0 text-muted-foreground cursor-pointer transition-colors duration-150 hover:bg-destructive/10 hover:text-destructive focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-2 motion-reduce:transition-none"
              onClick={() => handleDelete(rl.id)}
              aria-label="Delete related list"
              title="Delete related list"
              data-testid={`related-list-delete-${rl.id}`}
            >
              <X size={14} />
            </button>
          </div>
        ))
      )}

      <button
        type="button"
        className="w-full rounded-md border border-dashed border-input bg-background p-2 text-[13px] text-muted-foreground cursor-pointer transition-colors duration-150 hover:border-primary hover:text-primary focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-2 motion-reduce:transition-none"
        onClick={handleOpenForm}
        data-testid="add-related-list-button"
      >
        + Add Related List
      </button>

      <CrudFormDialog<RelatedListFormData>
        open={isFormOpen}
        onClose={handleCloseForm}
        title={editing ? 'Edit Related List' : 'Add Related List'}
        schema={relatedListSchema}
        defaultValues={
          editing
            ? {
                relatedCollectionId: editing.relatedCollectionId,
                relationshipFieldId: editing.relationshipFieldId,
                selectedDisplayColumns: parseDisplayColumns(editing.displayColumns),
                sortField: editing.sortField ?? '',
                sortDirection: editing.sortDirection,
                rowLimit: editing.rowLimit,
              }
            : INITIAL_FORM_DATA
        }
        onSubmit={handleSubmit}
        testIdPrefix="rl"
        submitLabel={editing ? 'Save' : 'Add'}
        className="sm:max-w-[520px]"
      >
        <RelatedCollectionSelect
          options={collectionOptions}
          onCollectionChange={handleCollectionChange}
        />
        <CrudSelectField<RelatedListFormData>
          name="relationshipFieldId"
          id="rl-relationship"
          label="Relationship Field"
          required
          placeholder={
            !selectedCollectionId
              ? 'Select a collection first'
              : relationshipFieldOptions.length === 0
                ? 'No relationship fields found'
                : 'Select a relationship field...'
          }
          options={relationshipFieldOptions.map((opt) => ({ value: opt.id, label: opt.label }))}
          disabled={!selectedCollectionId}
          testId="rl-form-relationship"
        />
        <DisplayColumnsField
          hasCollection={!!selectedCollectionId}
          displayOrder={displayOrder}
          fieldByName={fieldByName}
          onReorder={setOrderedFieldNames}
        />
        <CrudSelectField<RelatedListFormData>
          name="sortField"
          id="rl-sort-field"
          label="Sort Field"
          placeholder="None (default order)"
          options={sortFieldOptions}
          disabled={!selectedCollectionId}
          testId="rl-form-sort-field"
        />
        <CrudSelectField<RelatedListFormData>
          name="sortDirection"
          id="rl-sort-direction"
          label="Sort Direction"
          options={SORT_DIRECTION_OPTIONS}
          testId="rl-form-sort-direction"
        />
        <CrudNumberField<RelatedListFormData>
          name="rowLimit"
          id="rl-row-limit"
          label="Row Limit"
          min={1}
          max={100}
          parse={parseRowLimit}
          testId="rl-form-row-limit"
        />
      </CrudFormDialog>
    </div>
  )
}
