/**
 * useRecordFormState — the state side of the record form core.
 *
 * Owns the form values and field errors, hosts the client layout-rule engine
 * (`useLayoutRules`), and implements field change and save (layout-rule
 * `onBeforeSave` gate → JSON parse → `useRecordMutation` create/update →
 * server field errors mapped back onto fields). On success it navigates via
 * the caller's `routes`.
 *
 * Must run in a component keyed on `useRecordFormModel().formKey`: the
 * `useState` initializer reads `initialData` once, on mount.
 *
 * Moved from `ObjectFormPage`'s `ObjectFormBody` (record-stack convergence P2);
 * behavior is unchanged.
 */

import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useLayoutRules } from '@kelta/components'
import type { UseLayoutRulesResult } from '@kelta/components'
import { dtosToLayoutRules } from '@/utils/layoutRules'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useToast } from '@/components/Toast'
import { ApiError } from '@/services/apiClient'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { PageLayoutDto } from '@/hooks/usePageLayout'
import { buildSaveAttributes } from './formData'
import { READ_ONLY_TYPES, SYSTEM_FIELDS } from './formFieldTypes'
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
}

export interface RecordFormState {
  formData: Record<string, unknown>
  formErrors: Record<string, string>
  /** Client layout-rule engine (compute targets render via `isComputed`). */
  ruleEngine: UseLayoutRulesResult
  /** Non-system, non-read-only-type fields shown on the form. */
  displayFields: FieldDefinition[]
  handleFieldChange: (name: string, value: unknown) => void
  handleSave: () => void
  isSaving: boolean
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
}: UseRecordFormStateOptions): RecordFormState {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [formData, setFormData] = useState<Record<string, unknown>>(initialData)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})

  // Layout client-side rules engine: compute/validate/default/transform rules
  // attached to the resolved layout. The engine is no-op when the layout has
  // no rules (engine.enabled === false).
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
    onSuccess: () => {
      if (isNew) {
        navigate(routes.list)
      } else {
        navigate(routes.detail(recordId!))
      }
    },
    onError: (error: Error) => {
      showToast(error.message || 'Failed to save record', 'error')
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

  // Handle save
  const handleSave = useCallback(() => {
    // Layout-rule beforeSave gate: collect violations from the engine and
    // block submit for ERROR severity. Warnings allow submit to proceed.
    const ruleResult = ruleEngine.runBeforeSave()
    if (ruleResult.blocked) {
      showToast(ruleResult.violations[0]?.message ?? 'Validation failed', 'error')
      return
    }

    const { attributes, errors } = buildSaveAttributes(editableFields, formData)
    if (Object.keys(errors).length > 0) {
      setFormErrors((prev) => ({ ...prev, ...errors }))
      showToast('Fix invalid JSON before saving', 'error')
      return
    }

    // Include record type ID if selected
    if (selectedRecordTypeId) {
      attributes.recordTypeId = selectedRecordTypeId
    }

    if (isNew) {
      mutations.create.mutate(attributes)
    } else {
      mutations.update.mutate({ id: recordId!, data: attributes })
    }
  }, [
    editableFields,
    formData,
    isNew,
    mutations,
    recordId,
    selectedRecordTypeId,
    ruleEngine,
    showToast,
  ])

  const isSaving = mutations.create.isPending || mutations.update.isPending

  return {
    formData,
    formErrors,
    ruleEngine,
    displayFields,
    handleFieldChange,
    handleSave,
    isSaving,
  }
}
