/**
 * RelatedList Component
 *
 * Displays a compact table of related (child) records for a given
 * parent record. Used on the ObjectDetailPage/ResourceDetailPage (via the
 * shared DetailTabBar) to show master-detail relationships.
 *
 * Features:
 * - Compact table of related records
 * - Field type-aware rendering via FieldRenderer
 * - "View All" link to navigate to the full list filtered by parent
 * - Loading skeleton and empty state
 * - Opt-in inline CRUD (unified record slice 4): when `editable` and the child
 *   collection's permissions allow, cells become click-to-edit via the
 *   FieldControl registry (`InlineFieldValue`), rows can be deleted, and a new
 *   child can be created inline with the parent FK pre-filled. Child mutations
 *   are owned here (`useRecordMutation`) and refresh both the API-fetch path
 *   (`refetch`) and the parent-included path (`onChanged`).
 * - Mass edit: when inline editing is allowed AND the current user holds the
 *   MANAGE_DATA system permission, rows gain selection checkboxes and an
 *   action bar; "Edit field…" applies one value to all selected rows via the
 *   bulk-jobs backend (POST /api/bulk-jobs, operation UPDATE), polling the job
 *   to a terminal status before refreshing.
 */

import React, { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Plus, ExternalLink, Trash2, Check, X } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import { FieldRenderer } from '@/components/FieldRenderer'
import { InlineFieldValue } from '@/components/record/InlineFieldValue'
import { getFieldControl } from '@/components/fieldControl'
import type { FieldControlContext } from '@/components/fieldControl'
import { MassEditDialog } from './MassEditDialog'
import { useRelatedRecords } from '@/hooks/useRelatedRecords'
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import { useObjectPermissions } from '@/hooks/useObjectPermissions'
import { useSystemPermissions } from '@/hooks/useSystemPermissions'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useApi } from '@/context/ApiContext'
import { useToast } from '@/components'
import { extractIncluded } from '@/utils/jsonapi'
import { buildLookupLabels } from '@/utils/lookupDisplayField'
import { runBulkUpdate } from '@/utils/bulkUpdate'
import { useCollectionStore } from '@/context/CollectionStoreContext'
import { useRecordRoutes } from '@/context/RecordStackContext'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

/** System fields to exclude from related list columns */
const SYSTEM_FIELDS = new Set([
  'createdAt',
  'updatedAt',
  'createdBy',
  'updatedBy',
  'created_at',
  'updated_at',
  'created_by',
  'updated_by',
])

/** Reference field types that indicate a foreign key to another collection */
const REFERENCE_FIELD_TYPES = new Set(['master_detail', 'lookup', 'reference'])

/** Maximum number of columns to display in the related list */
const MAX_COLUMNS = 4

/** Maximum number of records to show in the compact view */
const DEFAULT_LIMIT = 5

/** System permission required to submit bulk jobs (mass edit). */
const MANAGE_DATA_PERMISSION = 'MANAGE_DATA'

export interface RelatedListProps {
  /** Related collection name */
  collectionName: string
  /** Foreign key field on the related collection that points to the parent */
  foreignKeyField: string
  /** Parent record ID */
  parentRecordId: string
  /** Tenant slug for building URLs */
  tenantSlug: string
  /** Optional: override the display label */
  label?: string
  /** Optional: number of records to show (default: 5) */
  limit?: number
  /**
   * Optional: ordered list of field names to display as columns. When provided
   * and non-empty, these names are resolved against the schema and rendered in
   * the given order, exactly as configured (no system-field/FK filtering, no
   * MAX_COLUMNS slice). When omitted or empty, falls back to the legacy
   * "first N non-system fields" auto-discovery.
   */
  displayColumns?: string[]
  /** Optional: field name on the related collection to sort by. */
  sortField?: string
  /** Optional: sort direction. Defaults to ascending. */
  sortDirection?: 'asc' | 'desc' | 'ASC' | 'DESC'
  /**
   * Optional: raw JSON:API response from the parent record request.
   * When provided, related records are extracted from the `included` array
   * instead of making a separate API call. This eliminates per-related-list
   * network requests when the parent fetches with reverse includes.
   */
  includedData?: unknown
  /**
   * Optional: called when the total record count resolves or changes. Used by
   * containers (e.g. the detail-page tab bar) that need to surface the count
   * outside the list itself.
   */
  onTotalChange?: (total: number) => void
  /**
   * Opt-in inline CRUD (slice 4). When true AND the child collection's
   * permissions allow, cells become inline-editable, rows can be deleted, and
   * a new child can be created inline. Defaults to false (read-only).
   */
  editable?: boolean
  /**
   * Called after any inline child mutation (create/edit/delete). Containers
   * pass the parent record's refetch so the `includedData` path refreshes; the
   * API-fetch path refreshes itself.
   */
  onChanged?: () => void
}

/**
 * Loading skeleton for the related list table.
 */
function RelatedListSkeleton({ columns }: { columns: number }) {
  return (
    <>
      {Array.from({ length: 3 }).map((_, rowIdx) => (
        <TableRow key={rowIdx}>
          {Array.from({ length: columns }).map((_, colIdx) => (
            <TableCell key={colIdx}>
              <Skeleton className="h-4 w-[80%]" />
            </TableCell>
          ))}
        </TableRow>
      ))}
    </>
  )
}

/** Build a FieldControl context for a related-list field (matches InlineFieldValue). */
function fieldContext(
  field: FieldDefinition,
  tenantSlug: string,
  displayLabel?: string
): FieldControlContext {
  return {
    fieldName: field.name,
    displayName: field.displayName || field.name,
    tenantSlug,
    targetCollection: field.referenceTarget,
    displayLabel,
    enumValues: field.enumValues,
    referenceOptions: field.lookupOptions,
    required: field.required,
  }
}

/**
 * Inline "add row" — a controlled row of FieldControl Edit inputs that POSTs a
 * new child with the parent FK pre-filled. Options-dependent types (picklist /
 * reference) edit only when their options are present in the schema field.
 */
function RelatedCreateRow({
  fields,
  tenantSlug,
  columnCount,
  hasSelectionColumn,
  saving,
  onSubmit,
  onCancel,
}: {
  fields: FieldDefinition[]
  tenantSlug: string
  columnCount: number
  hasSelectionColumn: boolean
  saving: boolean
  onSubmit: (values: Record<string, unknown>) => void
  onCancel: () => void
}): React.ReactElement {
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = () => {
    const nextErrors: Record<string, string> = {}
    const payload: Record<string, unknown> = {}
    for (const field of fields) {
      const control = getFieldControl(field.type)
      const ctx = fieldContext(field, tenantSlug)
      const coerced = control.coerce(values[field.name])
      const err = control.validate(coerced, ctx)
      if (err) nextErrors[field.name] = err
      if (coerced !== undefined) payload[field.name] = coerced
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return
    onSubmit(payload)
  }

  return (
    <TableRow data-testid="related-create-row">
      {hasSelectionColumn && <TableCell className="w-[32px] py-2" />}
      {fields.map((field) => {
        const control = getFieldControl(field.type)
        const Edit = control.Edit
        const ctx = fieldContext(field, tenantSlug)
        return (
          <TableCell key={field.name} className="py-2 align-top">
            <Edit
              type={field.type}
              value={values[field.name]}
              ctx={ctx}
              onChange={(next) => setValues((prev) => ({ ...prev, [field.name]: next }))}
              error={errors[field.name]}
              id={`related-create-${field.name}`}
            />
            {errors[field.name] && (
              <p className="mt-1 text-xs text-destructive" role="alert">
                {errors[field.name]}
              </p>
            )}
          </TableCell>
        )
      })}
      <TableCell className="py-2 text-right align-top whitespace-nowrap" colSpan={columnCount}>
        <div className="flex items-center justify-end gap-1">
          <Button
            variant="ghost"
            size="xs"
            onClick={submit}
            disabled={saving}
            aria-label="Save new row"
            data-testid="related-create-save"
          >
            <Check className="h-4 w-4" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="xs"
            onClick={onCancel}
            disabled={saving}
            aria-label="Cancel new row"
            data-testid="related-create-cancel"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      </TableCell>
    </TableRow>
  )
}

export function RelatedList({
  collectionName,
  foreignKeyField,
  parentRecordId,
  tenantSlug,
  label,
  limit = DEFAULT_LIMIT,
  displayColumns,
  sortField,
  sortDirection,
  includedData,
  onTotalChange,
  editable = false,
  onChanged,
}: RelatedListProps): React.ReactElement {
  const navigate = useNavigate()
  // Record links stay in the stack this list renders in (admin `/resources` or end-user `/app/o`).
  const routes = useRecordRoutes(tenantSlug)
  const collectionStore = useCollectionStore()
  const { showToast } = useToast()
  const { apiClient } = useApi()

  // Fetch schema for the related collection
  const { schema, fields, isLoading: schemaLoading } = useCollectionSchema(collectionName)

  // Fetch permissions for the related collection
  const { permissions } = useObjectPermissions(collectionName)

  // Mass edit posts to /api/bulk-jobs, which requires the MANAGE_DATA system
  // permission (in-controller gate) — hide the affordance without it.
  const { hasPermission } = useSystemPermissions()

  const [creating, setCreating] = useState(false)
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<string | null>(null)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [massEditOpen, setMassEditOpen] = useState(false)

  // Determine visible columns. When `displayColumns` is provided, render exactly
  // those fields in that order (configured in the page layout). Otherwise fall
  // back to the legacy auto-discovery: first MAX_COLUMNS non-system, non-id,
  // non-FK fields.
  const visibleFields = useMemo<FieldDefinition[]>(() => {
    if (!fields.length) return []
    if (displayColumns && displayColumns.length > 0) {
      const byName = new Map(fields.map((f) => [f.name, f]))
      return displayColumns
        .map((name) => byName.get(name))
        .filter((f): f is FieldDefinition => Boolean(f))
    }
    return fields
      .filter((f) => !SYSTEM_FIELDS.has(f.name))
      .filter((f) => f.name !== 'id')
      .filter((f) => f.name !== foreignKeyField)
      .slice(0, MAX_COLUMNS)
  }, [fields, foreignKeyField, displayColumns])

  // Identify reference fields among visible columns for include resolution
  const referenceFields = useMemo(
    () => visibleFields.filter((f) => REFERENCE_FIELD_TYPES.has(f.type) && f.referenceTarget),
    [visibleFields]
  )

  // Build include param from the reference FIELD names. The worker resolves `?include=` by field
  // name (e.g. `title`), not by target collection name (e.g. `titles`) — see ObjectListPage.
  const includeParam = useMemo(() => {
    if (referenceFields.length === 0) return undefined
    return [...new Set(referenceFields.map((f) => f.name))].join(',')
  }, [referenceFields])

  // Extract pre-loaded records from the parent's included resources when available.
  // This eliminates a separate API call when the include data contains records of
  // this collection type. Returns null if no records of this type are present in the
  // included data — which triggers the fallback API call. This distinction matters
  // because the gateway only resolves forward (belongs-to) includes; reverse (has-many)
  // relationships like order_items won't appear in the included array, so we must
  // fall back to a separate query.
  const preloadedRecords = useMemo<CollectionRecord[] | null>(() => {
    if (!includedData || !collectionName || !foreignKeyField || !parentRecordId) return null
    const allOfType = extractIncluded<CollectionRecord>(includedData, collectionName)
    // If no resources of this type exist in the included array, the gateway didn't
    // resolve this include — return null to trigger the fallback API call.
    if (allOfType.length === 0) return null
    // Filter to records that reference this parent
    const filtered = allOfType.filter((r) => String(r[foreignKeyField]) === parentRecordId)
    // Apply layout-configured sort client-side (preloaded data isn't server-sorted).
    if (sortField) {
      const desc = sortDirection?.toLowerCase() === 'desc'
      const sorted = [...filtered].sort((a, b) => {
        const av = a[sortField]
        const bv = b[sortField]
        if (av == null && bv == null) return 0
        if (av == null) return 1
        if (bv == null) return -1
        if (av < bv) return -1
        if (av > bv) return 1
        return 0
      })
      return desc ? sorted.reverse() : sorted
    }
    return filtered
  }, [includedData, collectionName, foreignKeyField, parentRecordId, sortField, sortDirection])

  // Only fetch via API if no pre-loaded data is available
  const hasPreloadedData = preloadedRecords !== null
  const {
    data: fetchedRecords,
    total: fetchedTotal,
    isLoading: recordsLoading,
    rawResponse,
    refetch,
  } = useRelatedRecords({
    collectionName,
    foreignKeyField,
    parentRecordId,
    limit,
    include: includeParam,
    sortField,
    sortDirection,
    enabled: !schemaLoading && !hasPreloadedData,
  })

  // Refresh after any inline mutation: the API path refetches itself; the
  // preloaded path is refreshed by the parent via `onChanged`.
  const refreshAfterMutation = () => {
    if (!hasPreloadedData) void refetch()
    onChanged?.()
  }

  const mutations = useRecordMutation({
    collectionName,
    onSuccess: refreshAfterMutation,
    onError: (err) => showToast(err.message || 'Operation failed', 'error'),
  })

  // Use pre-loaded data if available, otherwise fall back to fetched data
  const records = hasPreloadedData ? preloadedRecords.slice(0, limit) : fetchedRecords
  const total = hasPreloadedData ? preloadedRecords.length : fetchedTotal
  // For lookup display maps, use the parent's response (which contains all included resources)
  const effectiveRawResponse = hasPreloadedData ? includedData : rawResponse

  // Build lookup display map from included resources using centralized collection store
  // (labelled by the shared rule — the same text the record list and detail page show).
  const lookupDisplayMap = useMemo(
    () =>
      buildLookupLabels(effectiveRawResponse, referenceFields, collectionStore.getCollectionByName),
    [effectiveRawResponse, referenceFields, collectionStore]
  )

  // Display name for the related collection
  const displayLabel =
    label ||
    schema?.displayName ||
    (collectionName ? collectionName.charAt(0).toUpperCase() + collectionName.slice(1) : 'Related')

  const isLoading = schemaLoading || (!hasPreloadedData && recordsLoading)

  useEffect(() => {
    if (!onTotalChange) return
    if (isLoading) return
    onTotalChange(total)
  }, [total, isLoading, onTotalChange])

  // Inline-CRUD gates (require opt-in + child-collection permission).
  const canInlineEdit = editable && permissions.canEdit
  const canDeleteRows = editable && permissions.canDelete
  const canInlineCreate = editable && permissions.canCreate
  // Mass edit additionally requires the MANAGE_DATA system permission — the
  // bulk-jobs endpoint rejects profiles without it.
  const canMassEdit = canInlineEdit && hasPermission(MANAGE_DATA_PERMISSION)
  // Extra trailing column for row actions (delete).
  const actionColumns = canDeleteRows ? 1 : 0
  const totalColumns = visibleFields.length + actionColumns

  // Effective selection: drop ids for rows that are no longer visible
  // (deleted/refetched) without mutating state during render/effects.
  const visibleSelectedIds = useMemo(() => {
    if (selectedIds.size === 0) return selectedIds
    const visible = new Set(records.map((r) => r.id))
    const next = new Set([...selectedIds].filter((id) => visible.has(id)))
    return next.size === selectedIds.size ? selectedIds : next
  }, [records, selectedIds])

  const allSelected = records.length > 0 && records.every((r) => visibleSelectedIds.has(r.id))
  const someSelected = !allSelected && records.some((r) => visibleSelectedIds.has(r.id))

  const toggleSelectRow = (recordId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(recordId)) next.delete(recordId)
      else next.add(recordId)
      return next
    })
  }

  const toggleSelectAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(records.map((r) => r.id)))
  }

  // Fields offered by the mass-edit picker: user-editable schema fields only
  // (excludes id, system audit fields, and server-computed types).
  const massEditableFields = useMemo(
    () =>
      fields.filter(
        (f) => f.name !== 'id' && !SYSTEM_FIELDS.has(f.name) && getFieldControl(f.type).editable
      ),
    [fields]
  )

  /**
   * Apply one field value to every selected row via the bulk-jobs backend:
   * POST /api/bulk-jobs (operation UPDATE, partial by record id), then poll the
   * job to a terminal status. Throws on failure so MassEditDialog stays open
   * with the error inline.
   */
  const submitMassEdit = async (fieldName: string, value: unknown): Promise<void> => {
    const collectionId = schema?.id
    if (!collectionId) throw new Error('Collection schema is not loaded yet')
    const ids = [...visibleSelectedIds]
    const {
      status: finalStatus,
      successRecords,
      errorRecords,
    } = await runBulkUpdate(
      apiClient,
      collectionId,
      ids.map((id) => ({ id, [fieldName]: value }))
    )

    if (finalStatus !== 'COMPLETED') {
      const message = finalStatus
        ? `Bulk update ${finalStatus.toLowerCase()} — check the Bulk Jobs page for details`
        : 'Bulk update is still running — check the Bulk Jobs page for its result'
      showToast(message, 'error')
      throw new Error(message)
    }

    if (errorRecords > 0) {
      showToast(
        `Updated ${successRecords} of ${ids.length} records — ${errorRecords} failed. Check the Bulk Jobs page for details.`,
        'error'
      )
    } else {
      showToast(`Updated ${successRecords} of ${ids.length} records`, 'success')
    }
    refreshAfterMutation()
    setSelectedIds(new Set())
  }

  // Handle row click → navigate to record detail
  const handleRowClick = (record: CollectionRecord) => {
    navigate(routes.detail(collectionName, record.id))
  }

  // Handle "+ New" → inline create row when editable, else navigate to the full
  // form with the parent pre-filled.
  const handleNew = () => {
    if (canInlineCreate) {
      setCreating(true)
      return
    }
    const params = new URLSearchParams()
    params.set(foreignKeyField, parentRecordId)
    navigate(routes.new(collectionName, params))
  }

  const commitEdit = async (recordId: string, fieldName: string, value: unknown): Promise<void> => {
    await mutations.patch.mutateAsync({ id: recordId, data: { [fieldName]: value } })
  }

  const commitCreate = async (values: Record<string, unknown>): Promise<void> => {
    await mutations.create.mutateAsync({ ...values, [foreignKeyField]: parentRecordId })
    setCreating(false)
  }

  const confirmDelete = async (recordId: string): Promise<void> => {
    await mutations.remove.mutateAsync(recordId)
    setConfirmingDeleteId(null)
  }

  const showCreateButton = (canInlineCreate || permissions.canCreate) && !creating

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between py-3">
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          {displayLabel}
          <Badge variant="outline" className="font-normal">
            {isLoading ? '…' : total}
          </Badge>
        </CardTitle>
        <div className="flex items-center gap-2">
          {total > limit && (
            <Button variant="ghost" size="sm" className="h-7 text-xs" asChild>
              <Link
                to={routes.list(
                  collectionName,
                  new URLSearchParams({
                    filter: JSON.stringify([
                      {
                        id: 'parent',
                        field: foreignKeyField,
                        operator: 'equals',
                        value: parentRecordId,
                      },
                    ]),
                  })
                )}
              >
                View All
                <ExternalLink className="ml-1 h-3 w-3" />
              </Link>
            </Button>
          )}
          {showCreateButton && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              onClick={handleNew}
              data-testid="related-list-new"
            >
              <Plus className="mr-1 h-3 w-3" />
              New
            </Button>
          )}
        </div>
      </CardHeader>

      <CardContent className="pt-0">
        {isLoading ? (
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  {Array.from({ length: MAX_COLUMNS }).map((_, i) => (
                    <TableHead key={i}>
                      <Skeleton className="h-4 w-20" />
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                <RelatedListSkeleton columns={MAX_COLUMNS} />
              </TableBody>
            </Table>
          </div>
        ) : records.length === 0 && !creating ? (
          <p className="py-4 text-center text-sm text-muted-foreground">
            No related {displayLabel.toLowerCase()} found.
          </p>
        ) : (
          <div className="rounded-md border">
            {canMassEdit && visibleSelectedIds.size > 0 && (
              <div
                className="flex items-center gap-2 border-b bg-muted/40 px-3 py-1.5 text-xs"
                data-testid="related-mass-edit-bar"
              >
                <span className="text-muted-foreground" data-testid="related-mass-edit-count">
                  {visibleSelectedIds.size} selected
                </span>
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() => setMassEditOpen(true)}
                  disabled={massEditableFields.length === 0}
                  data-testid="related-mass-edit-open"
                >
                  Edit field…
                </Button>
                <Button
                  variant="ghost"
                  size="xs"
                  onClick={() => setSelectedIds(new Set())}
                  data-testid="related-mass-edit-clear"
                >
                  Clear
                </Button>
              </div>
            )}
            <Table>
              <TableHeader>
                <TableRow>
                  {canMassEdit && (
                    <TableHead className="w-[32px]">
                      <Checkbox
                        checked={allSelected ? true : someSelected ? 'indeterminate' : false}
                        onCheckedChange={toggleSelectAll}
                        aria-label={allSelected ? 'Deselect all rows' : 'Select all rows'}
                        data-testid="related-select-all"
                      />
                    </TableHead>
                  )}
                  {visibleFields.map((field) => (
                    <TableHead key={field.name} className="whitespace-nowrap text-xs">
                      {field.displayName || field.name}
                    </TableHead>
                  ))}
                  {actionColumns > 0 && (
                    <TableHead className="w-px text-right text-xs">
                      <span className="sr-only">Actions</span>
                    </TableHead>
                  )}
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((record) => (
                  <TableRow
                    key={record.id}
                    className="cursor-pointer"
                    onClick={() => handleRowClick(record)}
                  >
                    {canMassEdit && (
                      <TableCell className="w-[32px] py-2" onClick={(e) => e.stopPropagation()}>
                        <Checkbox
                          checked={visibleSelectedIds.has(record.id)}
                          onCheckedChange={() => toggleSelectRow(record.id)}
                          aria-label={`Select row ${record.id}`}
                          data-testid={`related-select-${record.id}`}
                        />
                      </TableCell>
                    )}
                    {visibleFields.map((field) => {
                      const value = record[field.name]
                      const isRef = REFERENCE_FIELD_TYPES.has(field.type)
                      const refLabel =
                        isRef && lookupDisplayMap?.[field.name]
                          ? lookupDisplayMap[field.name][String(value)] || undefined
                          : undefined

                      return (
                        <TableCell key={field.name} className="max-w-[200px] py-2">
                          {canInlineEdit ? (
                            <InlineFieldValue
                              field={field}
                              value={value}
                              displayLabel={refLabel}
                              tenantSlug={tenantSlug}
                              editable
                              editOn="pencil"
                              masked={
                                Array.isArray(
                                  (record as { __maskedFields?: unknown }).__maskedFields
                                ) &&
                                (
                                  (record as { __maskedFields?: string[] })
                                    .__maskedFields as string[]
                                ).includes(field.name)
                              }
                              onCommit={(name, v) => commitEdit(record.id, name, v)}
                            />
                          ) : (
                            <FieldRenderer
                              type={field.type}
                              value={value}
                              fieldName={field.name}
                              displayName={field.displayName || field.name}
                              tenantSlug={tenantSlug}
                              targetCollection={field.referenceTarget}
                              displayLabel={refLabel}
                              truncate
                            />
                          )}
                        </TableCell>
                      )
                    })}
                    {actionColumns > 0 && (
                      <TableCell
                        className="w-px py-2 text-right whitespace-nowrap"
                        onClick={(e) => e.stopPropagation()}
                      >
                        {confirmingDeleteId === record.id ? (
                          <span className="inline-flex items-center gap-1">
                            <Button
                              variant="ghost"
                              size="xs"
                              className="text-destructive"
                              onClick={() => void confirmDelete(record.id)}
                              disabled={mutations.remove.isPending}
                              aria-label="Confirm delete"
                              data-testid={`related-delete-confirm-${record.id}`}
                            >
                              <Check className="h-4 w-4" aria-hidden="true" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="xs"
                              onClick={() => setConfirmingDeleteId(null)}
                              aria-label="Cancel delete"
                            >
                              <X className="h-4 w-4" aria-hidden="true" />
                            </Button>
                          </span>
                        ) : (
                          <Button
                            variant="ghost"
                            size="xs"
                            className="text-muted-foreground hover:text-destructive"
                            onClick={() => setConfirmingDeleteId(record.id)}
                            aria-label={`Delete ${displayLabel} row`}
                            data-testid={`related-delete-${record.id}`}
                          >
                            <Trash2 className="h-4 w-4" aria-hidden="true" />
                          </Button>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                ))}
                {creating && (
                  <RelatedCreateRow
                    fields={visibleFields}
                    tenantSlug={tenantSlug}
                    columnCount={Math.max(actionColumns, 1)}
                    hasSelectionColumn={canMassEdit}
                    saving={mutations.create.isPending}
                    onSubmit={(values) => void commitCreate(values)}
                    onCancel={() => setCreating(false)}
                  />
                )}
              </TableBody>
            </Table>
          </div>
        )}
        {creating && totalColumns === 0 && (
          <p className="mt-2 text-xs text-muted-foreground">No editable columns configured.</p>
        )}
        {massEditOpen && (
          <MassEditDialog
            fields={massEditableFields}
            tenantSlug={tenantSlug}
            selectedCount={visibleSelectedIds.size}
            onSubmit={submitMassEdit}
            onClose={() => setMassEditOpen(false)}
          />
        )}
      </CardContent>
    </Card>
  )
}
