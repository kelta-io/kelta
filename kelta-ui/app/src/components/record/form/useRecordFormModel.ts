/**
 * useRecordFormModel — the data side of the record form core.
 *
 * Loads everything the form needs before it can mount: the collection schema,
 * the record (edit mode), object + field permissions, record types (with the
 * default auto-selected on create) and their picklist overrides, the resolved
 * page layout, picklist values and lookup options merged into the fields, and
 * the collection's client-enforced validation rules, and the initial form data
 * (record values, or schema defaults + `prefill` on create).
 *
 * Form STATE (values, errors, client layout rules, save) lives in
 * `useRecordFormState`, which runs inside a body component remounted by
 * `formKey` — so its `useState` initializer sees the right `initialData` once
 * the async data above has resolved.
 *
 * Moved from `ObjectFormPage` (record-stack convergence P2); P3 added the
 * client validation rules, schema defaults on create, and the edit-mode record
 * type taken from the loaded record. Both the end-user and the admin form
 * wrappers use it.
 *
 * P5 (optimistic concurrency): exposes the loaded record's `etag` (echoed as
 * `If-Match` on update) and `reload()`, which refetches the record and bumps
 * `formKey` so the body re-initialises from the latest version — the conflict
 * prompt's "Reload latest".
 *
 * P6 (admin convergence): `cloneFromId` — a create pre-filled from an existing
 * record (`computeCloneFormData`: the source's schema-field values minus system,
 * encrypted and masked fields; the source's record type) — and `recordError`, set
 * when the record being edited or cloned failed to load or does not exist, so the
 * form never falls back to an empty create over a record it could not read.
 */

import { useState, useMemo, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { CollectionValidationRule } from '@kelta/sdk'
import { useAuth } from '@/context/AuthContext'
import { useApi } from '@/context/ApiContext'
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import { useRecord } from '@/hooks/useRecord'
import { useCollectionPermissions } from '@/hooks/useCollectionPermissions'
import { useLookupDisplayMap } from '@/hooks/useLookupDisplayMap'
import { usePageLayout } from '@/hooks/usePageLayout'
import { resolvePicklistSource } from '@/hooks/usePicklistOptions'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { UseCollectionPermissionsReturn } from '@/hooks/useCollectionPermissions'
import type { PageLayoutDto } from '@/hooks/usePageLayout'
import type { LookupOption } from '@/components/LookupSelect'
import type { RecordType } from '@/types/collections'
import { computeCloneFormData, computeInitialFormData } from './formData'
import { REFERENCE_TYPES } from './formFieldTypes'

/** Picklist value returned from the API (field names match backend schema) */
interface PicklistValueDto {
  value: string
  label: string
  isDefault: boolean
  isActive: boolean
  sortOrder: number
  color?: string
}

/** Record type picklist override from the API */
interface RecordTypePicklistOverrideDto {
  id: string
  recordTypeId: string
  fieldId: string
  availableValues: string | string[]
  defaultValue?: string
}

export interface UseRecordFormModelOptions {
  /** Collection API name. */
  collectionName: string
  /** Record being edited; absent = create. */
  recordId?: string
  /**
   * Initial values for a NEW record (e.g. `?order_ref=<id>` from a related
   * list). Only keys naming a schema field are applied; ignored in edit mode.
   */
  prefill?: Record<string, unknown>
  /**
   * Create mode only: an existing record to copy. Its schema-field values (minus system,
   * encrypted and masked fields) seed the new record; `prefill` still wins. Ignored on edit.
   */
  cloneFromId?: string
}

export interface RecordFormModel {
  isNew: boolean
  /** A create seeded from `cloneFromId`. */
  isClone: boolean
  /**
   * The record being edited (or the clone source) failed to load or was not found. The form
   * shows an error instead of an empty form that would save over a record it never read.
   */
  recordError: Error | null
  /** True until schema, record (edit) or clone source, permissions and layout have loaded. */
  isLoading: boolean
  schemaError: Error | null
  /** Object-level CRUD permission flags. */
  permissions: UseCollectionPermissionsReturn['permissions']
  /** Field-level edit check (VISIBLE vs READ_ONLY). */
  isFieldEditable: (fieldName: string) => boolean
  /** Permission-visible fields enriched with picklist values + lookup options. */
  fields: FieldDefinition[]
  /** Schema display name, else the capitalized collection name. */
  collectionLabel: string
  /** Resolved page layout (null when none configured). */
  layout: PageLayoutDto | null
  recordTypes: RecordType[] | undefined
  /**
   * The user's pick, else (edit) the loaded record's type or (create) the default
   * record type — drives the layout and the type's picklist restrictions.
   */
  selectedRecordTypeId: string | undefined
  setSelectedRecordTypeId: (id: string | undefined) => void
  /**
   * Active collection validation rules with `enforceOnClient` — fed to the client
   * rule engine so they show inline and block an ERROR-severity save. UX only; the
   * worker enforces every rule.
   */
  validationRules: CollectionValidationRule[]
  /** Initial form values for the body's `useState`. */
  initialData: Record<string, unknown>
  /** Changes whenever the form's data source changes — key the body on it. */
  formKey: string
  /**
   * The loaded record's optimistic-locking version (GET `ETag`); undefined on create or when
   * the record came from the offline replica. The body pins it at mount and sends it as
   * `If-Match` on update.
   */
  etag: string | undefined
  /** Refetch the record, then re-initialise the form from it (discards unsaved edits). */
  reload: () => Promise<void>
}

const NO_VALIDATION_RULES: CollectionValidationRule[] = []

/** Stable serialization of the create-mode prefill, used in the remount key. */
function prefillKey(prefill: Record<string, unknown> | undefined): string {
  if (!prefill) return ''
  return new URLSearchParams(
    Object.entries(prefill).map(([key, value]) => [key, String(value)])
  ).toString()
}

export function useRecordFormModel({
  collectionName,
  recordId,
  prefill,
  cloneFromId,
}: UseRecordFormModelOptions): RecordFormModel {
  const isNew = !recordId
  const isClone = isNew && !!cloneFromId
  // The record the form reads: the one being edited, or (create) the clone source.
  const sourceId = isNew ? (isClone ? cloneFromId : undefined) : recordId
  const { apiClient } = useApi()

  // Fetch collection schema
  const {
    schema,
    fields,
    isLoading: schemaLoading,
    error: schemaError,
  } = useCollectionSchema(collectionName)

  // Fetch the existing record (edit mode) or the clone source (create from a copy)
  const {
    record,
    isLoading: recordLoading,
    error: recordFetchError,
    etag: recordEtag,
    refetch: refetchRecord,
  } = useRecord({
    collectionName,
    recordId: sourceId,
    enabled: !!sourceId,
  })
  // Only an edit carries the version forward; a clone is a new record.
  const etag = isNew ? undefined : recordEtag

  // Fetch permissions (combined object + field in one call)
  const {
    permissions,
    isFieldVisible,
    isFieldEditable,
    isLoading: permissionsLoading,
  } = useCollectionPermissions(collectionName)

  // ---------------------------------------------------------------
  // Record types: fetch available record types for the collection
  // ---------------------------------------------------------------
  const { data: recordTypes } = useQuery({
    queryKey: ['record-types-for-form', schema?.id],
    queryFn: async () => {
      const types = await apiClient.getList<RecordType>(
        `/api/record-types?filter[collectionId][eq]=${schema!.id}&filter[isActive][eq]=true`
      )
      return types.sort((a, b) => a.name.localeCompare(b.name))
    },
    enabled: !!schema?.id,
  })

  // The selected record type is derived until the user picks one: edit mode takes the
  // loaded record's type (the record is not loaded on the first render, so it can't be
  // captured once), a clone the source's type (else the default), a plain create the
  // collection's default type.
  const [recordTypeChoice, setRecordTypeChoice] = useState<{ id: string | undefined } | null>(null)
  const recordRecordTypeId =
    sourceId && record?.recordTypeId ? String(record.recordTypeId) : undefined
  const defaultRecordTypeId = isNew ? recordTypes?.find((rt) => rt.isDefault)?.id : undefined
  const selectedRecordTypeId = recordTypeChoice
    ? recordTypeChoice.id
    : isNew
      ? (recordRecordTypeId ?? defaultRecordTypeId)
      : recordRecordTypeId
  const setSelectedRecordTypeId = useCallback((id: string | undefined) => {
    setRecordTypeChoice({ id })
  }, [])

  // Fetch picklist overrides for the selected record type
  const { data: recordTypePicklistOverrides } = useQuery({
    queryKey: ['record-type-picklist-overrides', selectedRecordTypeId],
    queryFn: async () => {
      const overrides = await apiClient.getList<RecordTypePicklistOverrideDto>(
        `/api/record-type-picklists?filter[recordTypeId][eq]=${selectedRecordTypeId}`
      )
      // Build a map of fieldId → allowed values
      const map: Record<string, { values: string[]; defaultValue?: string }> = {}
      for (const o of overrides) {
        const values =
          typeof o.availableValues === 'string'
            ? (JSON.parse(o.availableValues) as string[])
            : o.availableValues
        map[o.fieldId] = { values, defaultValue: o.defaultValue }
      }
      return map
    },
    enabled: !!selectedRecordTypeId,
  })

  // Collection-wide validation rules with enforce_on_client = true (same query
  // key as the admin form, so the two share the cache). Failure → no client rules;
  // the worker still enforces them.
  const { data: validationRules = NO_VALIDATION_RULES } = useQuery({
    queryKey: ['client-validation-rules', schema?.id],
    queryFn: async () => {
      try {
        return await apiClient.getList<CollectionValidationRule>(
          `/api/validation-rules?filter[collectionId][eq]=${schema!.id}&filter[active][eq]=true&filter[enforceOnClient][eq]=true&page[size]=100`
        )
      } catch {
        return NO_VALIDATION_RULES
      }
    },
    enabled: !!schema?.id,
    staleTime: 5 * 60 * 1000,
  })

  // Resolve page layout for this collection (returns null if none configured)
  const { user } = useAuth()
  const { layout, isLoading: layoutLoading } = usePageLayout(
    schema?.id,
    user?.id,
    selectedRecordTypeId
  )

  // Filter fields by field-level permissions (hidden fields excluded, read-only shown as disabled)
  const permissionFilteredFields = useMemo(() => {
    return fields.filter((f) => isFieldVisible(f.name))
  }, [fields, isFieldVisible])

  // ---------------------------------------------------------------
  // Picklist values: fetch enum values for picklist/multi_picklist fields
  // ---------------------------------------------------------------
  const picklistFields = useMemo(() => {
    return permissionFilteredFields.filter(
      (f) => f.type === 'picklist' || f.type === 'multi_picklist'
    )
  }, [permissionFilteredFields])

  const { data: picklistValuesMap } = useQuery({
    queryKey: ['picklist-values-for-form', collectionName, picklistFields.map((f) => f.id)],
    queryFn: async () => {
      const map: Record<string, PicklistValueDto[]> = {}
      await Promise.all(
        picklistFields.map(async (field) => {
          try {
            // Resolve the picklist source (GLOBAL via fieldTypeConfig — including the
            // legacy pre-#1222 dialect — else field-level values). Shared helper.
            const { sourceId, sourceType } = resolvePicklistSource(field)
            const values = await apiClient.getList<PicklistValueDto>(
              `/api/picklist-values?filter[picklistSourceId][eq]=${encodeURIComponent(sourceId)}&filter[picklistSourceType][eq]=${sourceType}`
            )
            map[field.id] = values
              .filter((v) => v.isActive)
              .sort((a, b) => a.sortOrder - b.sortOrder)
          } catch {
            map[field.id] = []
          }
        })
      )
      return map
    },
    enabled: picklistFields.length > 0,
  })

  // ---------------------------------------------------------------
  // Lookup options: reuse shared useLookupDisplayMap cache to avoid
  // re-fetching the same target collection data as the detail page.
  // ---------------------------------------------------------------
  const lookupFields = useMemo(() => {
    return permissionFilteredFields.filter(
      (f) => REFERENCE_TYPES.has(f.type) && (f.referenceCollectionId || f.referenceTarget)
    )
  }, [permissionFilteredFields])

  const { lookupDisplayMap } = useLookupDisplayMap(permissionFilteredFields)

  // Transform the display map (fieldName → { recordId: label }) into
  // the options map (fieldId → LookupOption[]) needed by the form fields.
  const lookupOptionsMap = useMemo(() => {
    if (!lookupDisplayMap) return undefined
    const result: Record<string, LookupOption[]> = {}
    for (const field of lookupFields) {
      const idToLabel = lookupDisplayMap[field.name]
      if (idToLabel) {
        result[field.id] = Object.entries(idToLabel).map(([id, label]) => ({ id, label }))
      }
    }
    return result
  }, [lookupDisplayMap, lookupFields])

  // ---------------------------------------------------------------
  // Merge picklist values and lookup options into enriched fields
  // ---------------------------------------------------------------
  const enrichedFields = useMemo(() => {
    const hasPicklists = picklistValuesMap && picklistFields.length > 0
    const hasLookups = lookupOptionsMap && lookupFields.length > 0
    if (!hasPicklists && !hasLookups) return permissionFilteredFields
    return permissionFilteredFields.map((f) => {
      let updated = f
      if ((f.type === 'picklist' || f.type === 'multi_picklist') && picklistValuesMap?.[f.id]) {
        let entries = picklistValuesMap[f.id]
        // Apply record type picklist restrictions if available
        const rtOverride = recordTypePicklistOverrides?.[f.id]
        if (rtOverride) {
          const allowedSet = new Set(rtOverride.values)
          entries = entries.filter((v) => allowedSet.has(v.value))
        }
        updated = {
          ...updated,
          enumValues: entries.map((v) => v.value),
          enumOptions: entries.map((v) => ({ value: v.value, label: v.label, color: v.color })),
        }
      }
      if (REFERENCE_TYPES.has(f.type) && lookupOptionsMap?.[f.id]) {
        updated = { ...updated, lookupOptions: lookupOptionsMap[f.id] }
      }
      return updated
    })
  }, [
    permissionFilteredFields,
    picklistValuesMap,
    picklistFields,
    lookupOptionsMap,
    lookupFields,
    recordTypePicklistOverrides,
  ])

  const isLoading =
    schemaLoading || (!!sourceId && recordLoading) || permissionsLoading || layoutLoading

  // A record the form needed but could not read: a fetch error, or a load that came back empty
  // (e.g. the offline replica has no copy).
  const recordError = useMemo<Error | null>(() => {
    if (!sourceId || recordLoading) return null
    if (recordFetchError) return recordFetchError
    return record ? null : new Error(`Record ${sourceId} not found`)
  }, [sourceId, recordLoading, recordFetchError, record])

  // Collection label
  const collectionLabel =
    schema?.displayName ||
    (collectionName ? collectionName.charAt(0).toUpperCase() + collectionName.slice(1) : 'Object')

  // Prefill only applies to new records (e.g. ?order_ref=<id>)
  const queryDefaults = isNew ? prefill : undefined

  // Compute initial data and a key that changes when the data source changes.
  // The key forces the form body to remount, running useState with fresh initialData.
  const initialData = useMemo(
    () =>
      isClone && record
        ? computeCloneFormData(record, enrichedFields, queryDefaults)
        : computeInitialFormData(isNew, isNew ? undefined : record, enrichedFields, queryDefaults),
    [isClone, isNew, record, enrichedFields, queryDefaults]
  )
  // `reloadCount` re-keys the body after a conflict reload: a plain refetch keeps the same
  // record id, so without it the body would keep the stale values (and pinned ETag).
  const [reloadCount, setReloadCount] = useState(0)
  const reload = useCallback(async () => {
    await refetchRecord()
    setReloadCount((count) => count + 1)
  }, [refetchRecord])
  const formKey = isClone
    ? `clone:${cloneFromId}:${record?.id ?? 'loading'}:${enrichedFields.length}:${prefillKey(prefill)}`
    : isNew
      ? `new:${enrichedFields.length}:${prefillKey(prefill)}`
      : `edit:${recordId}:${record?.id ?? 'loading'}:${reloadCount}`

  return {
    isNew,
    isClone,
    recordError,
    isLoading,
    schemaError,
    permissions,
    isFieldEditable,
    fields: enrichedFields,
    collectionLabel,
    layout,
    recordTypes,
    selectedRecordTypeId,
    setSelectedRecordTypeId,
    validationRules,
    initialData,
    formKey,
    etag,
    reload,
  }
}
