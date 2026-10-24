/**
 * RecordTypePicklistEditor — per-record-type picklist overrides (allowed values + default) for a
 * collection's picklist / multi_picklist fields.
 *
 * Reads and writes the `record-type-picklists` system collection (`utils/recordTypePicklists.ts`),
 * the same rows the record form core restricts its picklists by and `RecordTypeEnforcementHook`
 * validates writes against. The values offered per field are the field's active picklist values,
 * resolved exactly as the form resolves them (`usePicklistDisplayMaps`: global picklist via
 * `fieldTypeConfig`, else the field's own values).
 *
 * Save writes only the fields the admin changed: a field left with every value allowed and no
 * default has its row deleted, otherwise its row is created (POST) or updated (PATCH by id).
 */

import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '@/context/I18nContext'
import { useApi } from '@/context/ApiContext'
import { usePicklistDisplayMaps } from '@/hooks/usePicklistOptions'
import {
  RECORD_TYPE_PICKLISTS_PATH,
  parseAvailableValues,
  recordTypePicklistsUrl,
} from '@/utils/recordTypePicklists'
import { LoadingSpinner } from '../LoadingSpinner'
import { ErrorMessage } from '../ErrorMessage'
import type {
  RecordType,
  FieldDefinition,
  RecordTypePicklistOverride,
} from '../../types/collections'

export interface RecordTypePicklistEditorProps {
  recordType: RecordType
  fields: FieldDefinition[]
  onClose: () => void
  onSaved: () => void
}

/** One active picklist value offered for a field, in display order. */
interface PicklistOption {
  value: string
  label: string
  color?: string
}

/** A field's override as shown/edited: the allowed values and the default ('' = none). */
interface FieldSelection {
  checkedValues: Set<string>
  defaultValue: string
}

interface FieldOverrideState extends FieldSelection {
  fieldId: string
  fieldName: string
  allValues: PicklistOption[]
  existingOverride?: RecordTypePicklistOverride
}

/** Query key of this editor's raw override rows (the record form caches its own parsed map). */
function recordTypePicklistOverridesKey(recordTypeId: string): readonly unknown[] {
  return ['record-type-picklist-overrides', recordTypeId]
}

const NO_OVERRIDES: RecordTypePicklistOverride[] = []

const selectClasses = cn(
  'px-2 py-2 text-[0.8125rem] text-foreground bg-background border border-input rounded-md',
  'transition-colors duration-150 motion-reduce:transition-none',
  'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring/10',
  'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed',
  'flex-1'
)

function sameSelection(a: FieldSelection, b: FieldSelection): boolean {
  if (a.defaultValue !== b.defaultValue || a.checkedValues.size !== b.checkedValues.size) {
    return false
  }
  for (const v of a.checkedValues) if (!b.checkedValues.has(v)) return false
  return true
}

export function RecordTypePicklistEditor({
  recordType,
  fields,
  onClose,
  onSaved,
}: RecordTypePicklistEditorProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const [isSaving, setIsSaving] = useState(false)
  // The admin's edits, by field id; a field without an entry shows its stored override.
  const [drafts, setDrafts] = useState<Record<string, FieldSelection>>({})
  const [collapsedFields, setCollapsedFields] = useState<Set<string>>(new Set())

  // Filter to only picklist/multi_picklist fields
  const picklistFields = useMemo(
    () => fields.filter((f) => f.type === 'picklist' || f.type === 'multi_picklist'),
    [fields]
  )

  // Existing overrides for this record type
  const {
    data: existingOverrides = NO_OVERRIDES,
    isLoading: loadingOverrides,
    error: overridesError,
    refetch: refetchOverrides,
  } = useQuery({
    queryKey: recordTypePicklistOverridesKey(recordType.id),
    queryFn: () =>
      apiClient.getList<RecordTypePicklistOverride>(recordTypePicklistsUrl(recordType.id)),
  })

  // Active values for each picklist field, resolved the way the record form resolves them
  const { displayMaps, isLoading: loadingValues } = usePicklistDisplayMaps(picklistFields)

  const overrideByField = useMemo(() => {
    const map = new Map<string, RecordTypePicklistOverride>()
    for (const override of existingOverrides) map.set(override.fieldId, override)
    return map
  }, [existingOverrides])

  // What each field shows: the stored override (no row, or an unparseable one = every value
  // allowed — what the form does), with the admin's draft on top.
  const fieldStates = useMemo(() => {
    const states: Record<string, FieldOverrideState> = {}
    for (const field of picklistFields) {
      const allValues: PicklistOption[] = Array.from(
        displayMaps[field.name] ?? new Map<string, { label: string; color?: string }>(),
        ([value, entry]) => ({ value, label: entry.label, color: entry.color })
      )
      const existingOverride = overrideByField.get(field.id)
      const stored = existingOverride
        ? parseAvailableValues(existingOverride.availableValues)
        : undefined
      const baseline: FieldSelection = {
        checkedValues: new Set(stored ?? allValues.map((v) => v.value)),
        defaultValue: existingOverride?.defaultValue ?? '',
      }
      states[field.id] = {
        fieldId: field.id,
        fieldName: field.displayName || field.name,
        allValues,
        existingOverride,
        ...(drafts[field.id] ?? baseline),
      }
    }
    return states
  }, [picklistFields, displayMaps, overrideByField, drafts])

  const updateSelection = useCallback(
    (fieldId: string, change: (current: FieldOverrideState) => FieldSelection) => {
      const state = fieldStates[fieldId]
      if (!state) return
      setDrafts((prev) => ({ ...prev, [fieldId]: change(state) }))
    },
    [fieldStates]
  )

  const handleToggleValue = useCallback(
    (fieldId: string, value: string) => {
      updateSelection(fieldId, (state) => {
        const checkedValues = new Set(state.checkedValues)
        if (checkedValues.has(value)) {
          checkedValues.delete(value)
          // An unchecked value can no longer be the default
          const defaultValue = state.defaultValue === value ? '' : state.defaultValue
          return { checkedValues, defaultValue }
        }
        checkedValues.add(value)
        return { checkedValues, defaultValue: state.defaultValue }
      })
    },
    [updateSelection]
  )

  const handleDefaultChange = useCallback(
    (fieldId: string, value: string) => {
      updateSelection(fieldId, (state) => ({
        checkedValues: state.checkedValues,
        defaultValue: value,
      }))
    },
    [updateSelection]
  )

  const handleToggleCollapse = useCallback((fieldId: string) => {
    setCollapsedFields((prev) => {
      const next = new Set(prev)
      if (next.has(fieldId)) {
        next.delete(fieldId)
      } else {
        next.add(fieldId)
      }
      return next
    })
  }, [])

  const handleSelectAll = useCallback(
    (fieldId: string) => {
      updateSelection(fieldId, (state) => ({
        checkedValues: new Set(state.allValues.map((v) => v.value)),
        defaultValue: state.defaultValue,
      }))
    },
    [updateSelection]
  )

  const handleDeselectAll = useCallback(
    (fieldId: string) => {
      updateSelection(fieldId, () => ({ checkedValues: new Set<string>(), defaultValue: '' }))
    },
    [updateSelection]
  )

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    const writes: Promise<unknown>[] = []
    for (const [fieldId, draft] of Object.entries(drafts)) {
      const state = fieldStates[fieldId]
      if (!state) continue
      const stored = state.existingOverride
        ? parseAvailableValues(state.existingOverride.availableValues)
        : undefined
      const baseline: FieldSelection = {
        checkedValues: new Set(stored ?? state.allValues.map((v) => v.value)),
        defaultValue: state.existingOverride?.defaultValue ?? '',
      }
      if (sameSelection(draft, baseline)) continue

      // Only values the field still offers, in its display order
      const availableValues = state.allValues
        .map((v) => v.value)
        .filter((v) => draft.checkedValues.has(v))
      const restricts = availableValues.length < state.allValues.length || draft.defaultValue !== ''
      const existingId = state.existingOverride?.id

      if (!restricts) {
        // Every value allowed, no default: the override is redundant
        if (existingId) {
          writes.push(apiClient.deleteResource(`${RECORD_TYPE_PICKLISTS_PATH}/${existingId}`))
        }
        continue
      }
      const attributes = {
        availableValues,
        defaultValue: draft.defaultValue || null,
      }
      writes.push(
        existingId
          ? apiClient.patchResource(`${RECORD_TYPE_PICKLISTS_PATH}/${existingId}`, attributes)
          : apiClient.postResource(RECORD_TYPE_PICKLISTS_PATH, {
              recordTypeId: recordType.id,
              fieldId,
              ...attributes,
            })
      )
    }

    const results = await Promise.allSettled(writes)
    if (writes.length > 0) {
      // Refetch whatever landed — this editor's rows and every record form's parsed overrides
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: recordTypePicklistOverridesKey(recordType.id) }),
        queryClient.invalidateQueries({ queryKey: ['record-form-picklist-overrides'] }),
      ])
    }
    setIsSaving(false)

    const failure = results.find((r): r is PromiseRejectedResult => r.status === 'rejected')
    if (failure) {
      // Keep the dialog open: written fields now match their refetched rows, the failed ones
      // keep their draft so the admin can retry.
      const reason: unknown = failure.reason
      toast.error(reason instanceof Error ? reason.message : t('errors.generic'))
      return
    }
    toast.success(t('recordTypes.overrideSaved'))
    onSaved()
  }, [drafts, fieldStates, recordType.id, apiClient, queryClient, t, onSaved])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    },
    [onClose]
  )

  const isLoading = loadingOverrides || loadingValues
  // Without the stored rows a save would POST over rows that already exist: block editing.
  const loadFailed = !loadingOverrides && !!overridesError

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
      onKeyDown={handleKeyDown}
      role="presentation"
      data-testid="picklist-override-overlay"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="bg-background dark:bg-card rounded-lg shadow-xl max-w-[700px] w-full max-h-[90vh] flex flex-col"
        role="dialog"
        aria-modal="true"
        aria-labelledby="picklist-override-title"
        onMouseDown={(e) => e.stopPropagation()}
        data-testid="picklist-override-modal"
      >
        <div className="flex justify-between items-center p-6 border-b border-border shrink-0">
          <h2 id="picklist-override-title" className="text-xl font-semibold m-0 text-foreground">
            {t('recordTypes.picklistOverrides')} &mdash; {recordType.name}
          </h2>
          <button
            type="button"
            className="p-2 text-2xl leading-none text-muted-foreground bg-transparent border-none cursor-pointer rounded transition-colors hover:text-foreground hover:bg-muted"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="picklist-override-close"
          >
            &times;
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1">
          {isLoading ? (
            <div className="flex justify-center items-center py-12">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : loadFailed ? (
            <ErrorMessage
              error={overridesError instanceof Error ? overridesError : t('errors.generic')}
              onRetry={() => void refetchOverrides()}
              data-testid="picklist-override-load-error"
            />
          ) : picklistFields.length === 0 ? (
            <div
              className="py-8 text-center text-muted-foreground text-sm"
              data-testid="no-picklist-fields"
            >
              <p>{t('recordTypes.noPicklistFields')}</p>
            </div>
          ) : (
            <div className="flex flex-col gap-3">
              {picklistFields.map((field) => {
                const state = fieldStates[field.id]
                if (!state) return null
                const isCollapsed = collapsedFields.has(field.id)
                const checkedCount = state.checkedValues.size
                const totalCount = state.allValues.length
                const allSelected = checkedCount === totalCount

                return (
                  <div
                    key={field.id}
                    className="border border-border rounded-md overflow-hidden"
                    data-testid={`field-section-${field.id}`}
                  >
                    <button
                      type="button"
                      className="flex items-center gap-3 w-full px-4 py-3 bg-muted border-none cursor-pointer text-left text-sm transition-colors duration-150 motion-reduce:transition-none hover:bg-accent"
                      onClick={() => handleToggleCollapse(field.id)}
                      aria-expanded={!isCollapsed}
                      data-testid={`field-header-${field.id}`}
                    >
                      <span className="text-[0.625rem] text-muted-foreground w-3 shrink-0">
                        {isCollapsed ? '\u25B6' : '\u25BC'}
                      </span>
                      <span className="font-semibold text-foreground flex-1">
                        {state.fieldName}
                      </span>
                      <span className="text-xs text-muted-foreground font-normal">
                        {allSelected
                          ? t('recordTypes.allValuesAvailable')
                          : t('recordTypes.restrictedValues', {
                              count: checkedCount,
                              total: totalCount,
                            })}
                      </span>
                    </button>

                    {!isCollapsed && (
                      <div className="p-4 border-t border-border">
                        {state.allValues.length === 0 ? (
                          <p className="text-sm text-muted-foreground text-center p-4">
                            {t('picklistDependencies.noValuesForField')}
                          </p>
                        ) : (
                          <>
                            <div className="flex gap-2 mb-3">
                              <button
                                type="button"
                                className="px-2.5 py-1 text-xs font-medium text-primary bg-transparent border border-primary rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-primary hover:text-primary-foreground disabled:opacity-40 disabled:cursor-not-allowed"
                                onClick={() => handleSelectAll(field.id)}
                                disabled={isSaving || allSelected}
                                data-testid={`select-all-${field.id}`}
                              >
                                {t('common.selectAll')}
                              </button>
                              <button
                                type="button"
                                className="px-2.5 py-1 text-xs font-medium text-primary bg-transparent border border-primary rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-primary hover:text-primary-foreground disabled:opacity-40 disabled:cursor-not-allowed"
                                onClick={() => handleDeselectAll(field.id)}
                                disabled={isSaving || checkedCount === 0}
                                data-testid={`deselect-all-${field.id}`}
                              >
                                {t('common.deselectAll')}
                              </button>
                            </div>
                            <div className="flex flex-col gap-1.5 mb-4 max-h-60 overflow-y-auto py-1">
                              {state.allValues.map((val) => (
                                <label
                                  key={val.value}
                                  className="flex items-center gap-2 px-2 py-1.5 rounded cursor-pointer text-sm transition-colors duration-100 motion-reduce:transition-none hover:bg-muted"
                                  data-testid={`value-item-${field.id}-${val.value}`}
                                >
                                  <input
                                    type="checkbox"
                                    className="w-4 h-4 accent-primary cursor-pointer shrink-0"
                                    checked={state.checkedValues.has(val.value)}
                                    onChange={() => handleToggleValue(field.id, val.value)}
                                    disabled={isSaving}
                                    data-testid={`value-checkbox-${field.id}-${val.value}`}
                                  />
                                  <span className="text-foreground flex-1">
                                    {val.label || val.value}
                                  </span>
                                  {val.color && (
                                    <span
                                      className="w-4 h-4 rounded-full border border-border shrink-0"
                                      style={{ backgroundColor: val.color }}
                                      aria-label={val.color}
                                    />
                                  )}
                                </label>
                              ))}
                            </div>

                            <div className="flex items-center gap-3 pt-3 border-t border-border max-md:flex-col max-md:items-start">
                              <label
                                htmlFor={`default-value-${field.id}`}
                                className="text-[0.8125rem] font-medium text-foreground whitespace-nowrap"
                              >
                                {t('recordTypes.defaultValue')}
                              </label>
                              <select
                                id={`default-value-${field.id}`}
                                className={cn(selectClasses, 'max-md:w-full')}
                                value={state.defaultValue}
                                onChange={(e) => handleDefaultChange(field.id, e.target.value)}
                                disabled={isSaving}
                                data-testid={`default-select-${field.id}`}
                              >
                                <option value="">{t('recordTypes.noDefault')}</option>
                                {state.allValues
                                  .filter((v) => state.checkedValues.has(v.value))
                                  .map((v) => (
                                    <option key={v.value} value={v.value}>
                                      {v.label || v.value}
                                    </option>
                                  ))}
                              </select>
                            </div>
                          </>
                        )}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <div className="flex gap-3 justify-end px-6 py-4 border-t border-border shrink-0">
          <button
            type="button"
            className="px-5 py-2.5 text-sm font-medium text-foreground bg-background border border-input rounded-md cursor-pointer transition-colors hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={onClose}
            disabled={isSaving}
            data-testid="picklist-override-cancel"
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="inline-flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={handleSave}
            disabled={isSaving || isLoading || loadFailed || picklistFields.length === 0}
            data-testid="picklist-override-save"
          >
            {isSaving ? (
              <>
                <LoadingSpinner size="small" />
                <span>{t('common.saving')}</span>
              </>
            ) : (
              t('common.save')
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
