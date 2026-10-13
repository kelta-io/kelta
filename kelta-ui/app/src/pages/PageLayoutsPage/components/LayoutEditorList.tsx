/**
 * LayoutEditorList
 *
 * Editor for LIST-type page layouts. Renders an ordered, horizontal list of
 * columns (each backed by a layout-field row) with width and label overrides,
 * plus a sidebar form for the default filter / sort / row limit that ships
 * with the layout.
 *
 * Self-contained: manages its own local state for columns and defaults and
 * owns the save mutation. It does not use the section/dropzone-based
 * LayoutEditorContext used by the DETAIL editor.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ArrowLeft, ArrowRight, GripVertical, Plus, Save, Trash2 } from 'lucide-react'
import { uuid } from '@/utils/uuid'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useApi } from '@/context/ApiContext'
import { FilterBuilder, type FilterExpression, type FieldOption } from '@/components/FilterBuilder'

export interface ListColumn {
  /** Editor-local id. Server-issued id once saved. */
  id: string
  /** Field UUID this column renders. */
  fieldId: string
  fieldName?: string
  fieldDisplayName?: string
  fieldType?: string
  /** Backend stores this as columnSpan; we treat it as a width unit (1–6). */
  columnSpan: number
  labelOverride?: string
  /** Track which columns came from the server so we know UPDATE vs CREATE. */
  serverId?: string
}

export interface LayoutEditorListInitialData {
  layoutId: string
  collectionId: string
  layoutName: string
  /** The first server section, if any — we collapse LIST layouts to one section. */
  sectionId?: string
  initialColumns: ListColumn[]
  defaultFilter: FilterExpression | null
  defaultSortField?: string
  defaultSortDirection: 'ASC' | 'DESC'
  defaultRowLimit: number
}

export interface LayoutEditorListProps {
  initialData: LayoutEditorListInitialData
  availableFields: FieldOption[]
  onBack: () => void
}

const ROW_LIMIT_OPTIONS = [10, 25, 50, 100, 200]
const WIDTH_OPTIONS = [1, 2, 3, 4, 5, 6]

export function LayoutEditorList({
  initialData,
  availableFields,
  onBack,
}: LayoutEditorListProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const [columns, setColumns] = useState<ListColumn[]>(initialData.initialColumns)
  const [defaultFilter, setDefaultFilter] = useState<FilterExpression | null>(
    initialData.defaultFilter
  )
  const [defaultSortField, setDefaultSortField] = useState<string>(
    initialData.defaultSortField ?? ''
  )
  const [defaultSortDirection, setDefaultSortDirection] = useState<'ASC' | 'DESC'>(
    initialData.defaultSortDirection
  )
  const [defaultRowLimit, setDefaultRowLimit] = useState<number>(initialData.defaultRowLimit)
  const [isDirty, setIsDirty] = useState(false)

  // Reset local edit state whenever the parent supplies a fresh server snapshot.
  // setState-in-effect is the standard pattern for syncing server data into
  // local editable state; we accept the lint warning here.
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect */
    setColumns(initialData.initialColumns)
    setDefaultFilter(initialData.defaultFilter)
    setDefaultSortField(initialData.defaultSortField ?? '')
    setDefaultSortDirection(initialData.defaultSortDirection)
    setDefaultRowLimit(initialData.defaultRowLimit)
    setIsDirty(false)
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialData])

  const placedFieldIds = useMemo(() => new Set(columns.map((c) => c.fieldId)), [columns])

  const unplacedFields = useMemo(
    () => availableFields.filter((f) => !placedFieldIds.has(f.name)),
    [availableFields, placedFieldIds]
  )

  const fieldByName = useMemo(() => {
    const map = new Map<string, FieldOption>()
    for (const f of availableFields) map.set(f.name, f)
    return map
  }, [availableFields])

  const mark = useCallback(() => setIsDirty(true), [])

  const addColumn = useCallback(
    (fieldName: string) => {
      const field = fieldByName.get(fieldName)
      if (!field) return
      // availableFields tracks by `name`; we still need the underlying field id
      // for the API. The parent passes id via FieldOption.name === field.name,
      // but we need .id too — see PageLayoutsPage wiring.
      const fieldId = field.id ?? fieldName
      setColumns((prev) => [
        ...prev,
        {
          id: uuid(),
          fieldId,
          fieldName: field.name,
          fieldDisplayName: field.displayName,
          columnSpan: 1,
        },
      ])
      mark()
    },
    [fieldByName, mark]
  )

  const removeColumn = useCallback(
    (id: string) => {
      setColumns((prev) => prev.filter((c) => c.id !== id))
      mark()
    },
    [mark]
  )

  const updateColumn = useCallback(
    (id: string, patch: Partial<ListColumn>) => {
      setColumns((prev) => prev.map((c) => (c.id === id ? { ...c, ...patch } : c)))
      mark()
    },
    [mark]
  )

  const moveColumn = useCallback(
    (index: number, direction: -1 | 1) => {
      setColumns((prev) => {
        const next = [...prev]
        const target = index + direction
        if (target < 0 || target >= next.length) return prev
        ;[next[index], next[target]] = [next[target], next[index]]
        return next
      })
      mark()
    },
    [mark]
  )

  const saveMutation = useMutation({
    mutationFn: async () => {
      const { layoutId, sectionId: initialSectionId } = initialData

      // Step 1: persist parent layout defaults.
      await apiClient.putResource(`/api/page-layouts/${layoutId}`, {
        defaultFilter: defaultFilter ?? null,
        defaultSortField: defaultSortField || null,
        defaultSortDirection,
        defaultRowLimit,
      })

      // Step 2: ensure exactly one section exists ("Columns") and use it.
      let sectionId = initialSectionId
      if (!sectionId) {
        const created = await apiClient.postResource<{ id: string }>('/api/layout-sections', {
          layoutId,
          heading: 'Columns',
          columns: 1,
          sortOrder: 0,
          collapsed: false,
          style: 'DEFAULT',
          sectionType: 'FIELDS',
        })
        sectionId = created.id
      }

      // Step 3: diff field placements against server.
      const desiredServerIds = new Set(columns.filter((c) => c.serverId).map((c) => c.serverId!))
      const originalServerIds = initialData.initialColumns
        .map((c) => c.serverId)
        .filter((id): id is string => !!id)

      const idsToDelete = originalServerIds.filter((id) => !desiredServerIds.has(id))
      await Promise.all(
        idsToDelete.map((id) => apiClient.deleteResource(`/api/layout-fields/${id}`))
      )

      // Step 4: create/update each column in order.
      for (let i = 0; i < columns.length; i++) {
        const c = columns[i]
        const payload = {
          sectionId,
          fieldId: c.fieldId,
          columnNumber: 1,
          columnSpan: c.columnSpan > 1 ? c.columnSpan : null,
          sortOrder: i,
          isRequiredOnLayout: false,
          isReadOnlyOnLayout: false,
          labelOverride: c.labelOverride || null,
          helpTextOverride: null,
          visibilityRule: null,
        }
        if (c.serverId) {
          await apiClient.putResource(`/api/layout-fields/${c.serverId}`, payload)
        } else {
          await apiClient.postResource('/api/layout-fields', payload)
        }
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pageLayouts'] })
      queryClient.invalidateQueries({ queryKey: ['pageLayout', initialData.layoutId] })
      setIsDirty(false)
      toast.success('Layout saved successfully')
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to save layout')
    },
  })

  // Leaving with unsaved changes asks first.
  const [confirmLeave, setConfirmLeave] = useState(false)
  const handleBack = useCallback(() => {
    if (isDirty) {
      setConfirmLeave(true)
      return
    }
    onBack()
  }, [isDirty, onBack])

  return (
    <div
      className="flex h-screen flex-col overflow-hidden bg-muted/30"
      data-testid="layout-editor-list"
    >
      <header className="flex items-center justify-between border-b border-border bg-background px-6 py-3">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" onClick={handleBack}>
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back
          </Button>
          <h2 className="text-lg font-semibold">{initialData.layoutName}</h2>
          <span className="rounded-md bg-muted px-2 py-0.5 text-xs font-medium uppercase">
            List
          </span>
        </div>
        <Button
          onClick={() => saveMutation.mutate()}
          disabled={!isDirty || saveMutation.isPending}
          data-testid="save-button"
        >
          <Save className="mr-1 h-4 w-4" />
          {saveMutation.isPending ? 'Saving…' : 'Save'}
        </Button>
      </header>

      <div className="grid flex-1 min-h-0 grid-cols-[1fr_360px] gap-4 overflow-hidden p-6">
        <Card className="flex min-h-0 flex-col overflow-hidden">
          <CardHeader>
            <CardTitle>Columns</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col gap-3 overflow-y-auto">
            {columns.length === 0 && (
              <p className="text-sm text-muted-foreground">
                No columns yet. Add a field from the panel on the right to get started.
              </p>
            )}

            {columns.map((column, i) => (
              <div
                key={column.id}
                className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-background p-3"
                data-testid={`list-column-${i}`}
              >
                <div className="flex items-center text-muted-foreground">
                  <GripVertical className="h-4 w-4" />
                </div>
                <div className="flex min-w-32 flex-1 flex-col">
                  <span className="text-sm font-medium">
                    {column.fieldDisplayName ?? column.fieldName ?? column.fieldId}
                  </span>
                  {column.fieldType && (
                    <span className="text-xs text-muted-foreground">{column.fieldType}</span>
                  )}
                </div>

                <div className="flex items-center gap-1">
                  <Label className="text-xs text-muted-foreground" htmlFor={`width-${column.id}`}>
                    Width
                  </Label>
                  <Select
                    value={String(column.columnSpan)}
                    onValueChange={(v) => updateColumn(column.id, { columnSpan: Number(v) })}
                  >
                    <SelectTrigger className="h-8 w-16" id={`width-${column.id}`}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {WIDTH_OPTIONS.map((w) => (
                        <SelectItem key={w} value={String(w)}>
                          {w}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                <Input
                  className="h-8 w-48"
                  placeholder="Label override"
                  value={column.labelOverride ?? ''}
                  onChange={(e) =>
                    updateColumn(column.id, { labelOverride: e.target.value || undefined })
                  }
                  data-testid={`list-column-label-${i}`}
                />

                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Move left"
                  disabled={i === 0}
                  onClick={() => moveColumn(i, -1)}
                >
                  <ArrowLeft className="h-4 w-4" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Move right"
                  disabled={i === columns.length - 1}
                  onClick={() => moveColumn(i, 1)}
                >
                  <ArrowRight className="h-4 w-4" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Remove column"
                  onClick={() => removeColumn(column.id)}
                  data-testid={`list-column-remove-${i}`}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}

            {unplacedFields.length > 0 && (
              <div className="mt-4 border-t border-border pt-3">
                <Label className="text-xs uppercase text-muted-foreground">Add field</Label>
                <div className="mt-2 flex flex-wrap gap-2">
                  {unplacedFields.map((f) => (
                    <Button
                      key={f.name}
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => addColumn(f.name)}
                      data-testid={`add-field-${f.name}`}
                    >
                      <Plus className="mr-1 h-3.5 w-3.5" />
                      {f.displayName}
                    </Button>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        <Card className="flex min-h-0 flex-col overflow-hidden">
          <CardHeader>
            <CardTitle>Defaults</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col gap-4 overflow-y-auto">
            <div className="flex flex-col gap-1">
              <Label className="text-sm">Sort by</Label>
              <div className="flex gap-2">
                <Select
                  value={defaultSortField || '__none__'}
                  onValueChange={(v) => {
                    setDefaultSortField(v === '__none__' ? '' : v)
                    mark()
                  }}
                >
                  <SelectTrigger className="h-8 flex-1" data-testid="default-sort-field">
                    <SelectValue placeholder="No sort" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">No sort</SelectItem>
                    {availableFields.map((f) => (
                      <SelectItem key={f.name} value={f.name}>
                        {f.displayName}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Select
                  value={defaultSortDirection}
                  onValueChange={(v) => {
                    setDefaultSortDirection(v as 'ASC' | 'DESC')
                    mark()
                  }}
                >
                  <SelectTrigger className="h-8 w-24" data-testid="default-sort-direction">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ASC">Asc</SelectItem>
                    <SelectItem value="DESC">Desc</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="flex flex-col gap-1">
              <Label className="text-sm">Row limit</Label>
              <Select
                value={String(defaultRowLimit)}
                onValueChange={(v) => {
                  setDefaultRowLimit(Number(v))
                  mark()
                }}
              >
                <SelectTrigger className="h-8 w-32" data-testid="default-row-limit">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROW_LIMIT_OPTIONS.map((n) => (
                    <SelectItem key={n} value={String(n)}>
                      {n}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex flex-col gap-2">
              <Label className="text-sm">Default filter</Label>
              <FilterBuilder
                value={defaultFilter}
                onChange={(next) => {
                  setDefaultFilter(next)
                  mark()
                }}
                fields={availableFields}
                idPrefix="default-filter"
              />
            </div>
          </CardContent>
        </Card>
      </div>

      <ConfirmDialog
        open={confirmLeave}
        title="Unsaved changes"
        message="You have unsaved changes. Are you sure you want to leave?"
        confirmLabel="Leave"
        cancelLabel="Stay"
        variant="danger"
        onConfirm={onBack}
        onCancel={() => setConfirmLeave(false)}
      />
    </div>
  )
}
