/**
 * useRecordFormState — the state side of the record form core.
 *
 * Owns the form values and field errors, hosts the client layout-rule engine
 * (`useLayoutRules`, fed the layout's rules AND the collection's client-enforced
 * validation rules), and implements field change, field blur (`onBlur` rules)
 * and save (per-field client validation → layout-rule `onBeforeSave` gate →
 * JSON parse → `useRecordMutation` create/update, an emptied field sent as
 * `null` on update → server field errors mapped back onto fields). On success
 * it navigates via the caller's `routes` — create to the NEW record's detail
 * page, edit back to the record's detail page.
 *
 * Must run in a component keyed on `useRecordFormModel().formKey`: the
 * `useState` initializer reads `initialData` once, on mount.
 *
 * Moved from `ObjectFormPage`'s `ObjectFormBody` (record-stack convergence P2);
 * P3 ported the admin form's correctness features listed above.
 *
 * P5 (optimistic concurrency): an update sends the record's ETag — pinned when
 * the body mounts, so a background refetch cannot silently move it — as
 * `If-Match`. A `409 STALE_WRITE` keeps the user's edits and raises `conflict`
 * (the caller shows `RecordConflictDialog`) instead of a toast.
 */

import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useLayoutRules } from '@kelta/components'
import type { UseLayoutRulesResult } from '@kelta/components'
import type { CollectionValidationRule } from '@kelta/sdk'
import { useI18n } from '@/context/I18nContext'
import { dtosToLayoutRules } from '@/utils/layoutRules'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useToast } from '@/components/Toast'
import { ApiError, isStaleWriteError } from '@/services/apiClient'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { PageLayoutDto } from '@/hooks/usePageLayout'
import { buildSaveAttributes } from './formData'
import { READ_ONLY_TYPES, SYSTEM_FIELDS } from './formFieldTypes'
import { validateFormValues } from './validation'
import type { RecordFormRoutes } from './RecordForm'

export interface UseRecordFormStateOptions {
  isNew: boolean
  initialData: Record<string, unknown>
  fields: FieldDefinition[]
  collectionName: string
  recordId?: string
  routes: RecordFormRoutes
  /** Check if a field is editable (VISIBLE vs READ_ONLY from field permissions) */
  isFieldEditable?: (fieldName: string) => boolean
  /** Resolved page layout (null when none configured) */
  layout?: PageLayoutDto | null
  /** Currently selected record type ID — sent as `recordTypeId` on save */
  selectedRecordTypeId?: string
  /** Collection validation rules with `enforceOnClient` (from `useRecordFormModel`). */
  validationRules?: CollectionValidationRule[]
  /** The record's ETag at load (edit mode); read once on mount and sent as `If-Match`. */
  etag?: string
}

export interface RecordFormState {
  formData: Record<string, unknown>
  formErrors: Record<string, string>
  /** Client layout-rule engine (compute targets render via `isComputed`). */
  ruleEngine: UseLayoutRulesResult
  /** Non-system, non-read-only-type fields shown on the form. */
  displayFields: FieldDefinition[]
  handleFieldChange: (name: string, value: unknown) => void
  /** Runs the client rules' `onBlur` event for a field (e.g. TRANSFORM rules). */
  handleFieldBlur: (name: string) => void
  handleSave: () => void
  isSaving: boolean
  /** True after an update was rejected as stale (`409 STALE_WRITE`); the edits are kept. */
  conflict: boolean
  /** Close the conflict prompt, keeping the edits in the form. */
  dismissConflict: () => void
}

export function useRecordFormState({
  isNew,
  initialData,
  fields,
  collectionName,
  recordId,
  routes,
  isFieldEditable,
  layout,
  selectedRecordTypeId,
  validationRules,
  etag,
}: UseRecordFormStateOptions): RecordFormState {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { t } = useI18n()
  const [formData, setFormData] = useState<Record<string, unknown>>(initialData)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  // The version this form's values were loaded from. Pinned at mount (like `formData`): if a
  // background refetch moved it while the user still edits the old values, the update would
  // overwrite the newer version without a conflict.
  const [ifMatch] = useState(etag)
  const [conflict, setConflict] = useState(false)
  const dismissConflict = useCallback(() => setConflict(false), [])

  // Layout client-side rules engine: compute/validate/default/transform rules
  // attached to the resolved layout, plus the collection's validation rules marked
  // enforceOnClient. The engine is no-op when there are neither.
  const layoutRules = useMemo(() => dtosToLayoutRules(layout?.rules, ''), [layout?.rules])
  const setFieldValueForEngine = useCallback((name: string, value: unknown) => {
    setFormData((prev) => ({ ...prev, [name]: value }))
  }, [])
  const setFieldErrorForEngine = useCallback((name: string, message: string) => {
    setFormErrors((prev) => ({ ...prev, [name]: message }))
  }, [])
  const clearFieldErrorForEngine = useCallback((name: string) => {
    setFormErrors((prev) => {
      if (!(name in prev)) return prev
      const next = { ...prev }
      delete next[name]
      return next
    })
  }, [])
  const ruleEngine = useLayoutRules({
    rules: layoutRules,
    validationRules,
    values: formData,
    setFieldValue: setFieldValueForEngine,
    setFieldError: setFieldErrorForEngine,
    clearFieldError: clearFieldErrorForEngine,
  })

  // Editable fields (exclude system, read-only types, and permission-read-only fields)
  const editableFields = useMemo(() => {
    return fields.filter(
      (f) =>
        !SYSTEM_FIELDS.has(f.name) &&
        !READ_ONLY_TYPES.has(f.type) &&
        (!isFieldEditable || isFieldEditable(f.name))
    )
  }, [fields, isFieldEditable])

  // All visible non-system fields (including read-only for display)
  const displayFields = useMemo(() => {
    return fields.filter((f) => !SYSTEM_FIELDS.has(f.name) && !READ_ONLY_TYPES.has(f.type))
  }, [fields])

  // Mutations
  const mutations = useRecordMutation({
    collectionName,
    onSuccess: (saved) => {
      if (isNew) {
        // Land on the new record; fall back to the list if the API returned no id.
        navigate(saved?.id ? routes.detail(String(saved.id)) : routes.list)
      } else {
        navigate(routes.detail(recordId!))
      }
    },
    onError: (error: Error) => {
      // Concurrent edit: keep the user's values and let them choose (reload vs keep editing).
      if (!isNew && isStaleWriteError(error)) {
        setConflict(true)
        return
      }
      showToast(error.message || t('recordForm.saveFailed'), 'error')
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        const serverErrors: Record<string, string> = {}
        for (const fe of error.fieldErrors) {
          if (fe.field) {
            serverErrors[fe.field] = fe.message
          }
        }
        setFormErrors((prev) => ({ ...prev, ...serverErrors }))
      }
    },
  })

  // Handle field change. Use a functional ref of the latest values so we can
  // pass the post-mutation map directly to the engine — without that, the
  // engine reads stale values because setFormData is async and useLayoutRules'
  // valuesRef only refreshes on the next render. Sync the ref via effect to
  // satisfy react-hooks/refs (no direct .current = during render).
  const formDataRef = useRef(formData)
  useEffect(() => {
    formDataRef.current = formData
  }, [formData])
  const handleFieldChange = useCallback(
    (name: string, value: unknown) => {
      const next = { ...formDataRef.current, [name]: value }
      formDataRef.current = next
      setFormData(next)
      // Clear server-side error for this field when user modifies it
      setFormErrors((prev) => {
        if (prev[name]) {
          const errNext = { ...prev }
          delete errNext[name]
          return errNext
        }
        return prev
      })
      // Drive the engine with the freshly computed values map so cascaded
      // computes/validations see the new value immediately.
      ruleEngine.onFieldChange(name, next)
    },
    [ruleEngine]
  )

  const handleFieldBlur = useCallback(
    (name: string) => {
      ruleEngine.onFieldBlur(name, formDataRef.current)
    },
    [ruleEngine]
  )

  // Handle save
  const handleSave = useCallback(() => {
    // Per-field client validation (required, format, configured constraints) on
    // the fields the user can edit. Advisory — the worker re-validates.
    const fieldErrors = validateFormValues(editableFields, formData, t, { isUpdate: !isNew })
    if (Object.keys(fieldErrors).length > 0) {
      setFormErrors((prev) => ({ ...prev, ...fieldErrors }))
      showToast(t('errors.validation'), 'error')
      return
    }

    // Layout-rule beforeSave gate: collect violations from the engine and
    // block submit for ERROR severity. Warnings allow submit to proceed.
    const ruleResult = ruleEngine.runBeforeSave()
    if (ruleResult.blocked) {
      showToast(ruleResult.violations[0]?.message ?? t('recordForm.validationFailed'), 'error')
      return
    }

    const { attributes, errors } = buildSaveAttributes(editableFields, formData, {
      initialData: isNew ? undefined : initialData,
      invalidJsonMessage: t('resourceForm.validation.invalidJson'),
    })
    if (Object.keys(errors).length > 0) {
      setFormErrors((prev) => ({ ...prev, ...errors }))
      showToast(t('recordForm.fixInvalidJson'), 'error')
      return
    }

    // Include record type ID if selected
    if (selectedRecordTypeId) {
      attributes.recordTypeId = selectedRecordTypeId
    }

    if (isNew) {
      mutations.create.mutate(attributes)
    } else {
      mutations.update.mutate({ id: recordId!, data: attributes, ...(ifMatch ? { ifMatch } : {}) })
    }
  }, [
    editableFields,
    formData,
    initialData,
    isNew,
    mutations,
    recordId,
    ifMatch,
    selectedRecordTypeId,
    ruleEngine,
    showToast,
    t,
  ])

  const isSaving = mutations.create.isPending || mutations.update.isPending

  return {
    formData,
    formErrors,
    ruleEngine,
    displayFields,
    handleFieldChange,
    handleFieldBlur,
    handleSave,
    isSaving,
    conflict,
    dismissConflict,
  }
}
