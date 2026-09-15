/**
 * ObjectFormPage
 *
 * Create or edit a record using a schema-driven form.
 * Renders input fields for each field in the collection schema,
 * with type-appropriate form controls.
 *
 * Features:
 * - Schema-driven form field generation
 * - Create and edit modes
 * - JSON:API request wrapping via useRecordMutation
 * - Picklist dropdowns with fetched values
 * - Reference field (master_detail/lookup) searchable dropdowns
 * - Proper date/datetime value binding
 * - Cancel/Save actions
 * - Loading states
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { useNavigate, useParams, useSearchParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Save, X, Loader2, AlertCircle } from 'lucide-react'
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { FieldLabel } from '@/components/kelta'
import { Checkbox } from '@/components/ui/checkbox'
import { Separator } from '@/components/ui/separator'
import { LookupSelect } from '@/components/LookupSelect'
import { MultiPicklistSelect, normalizeMultiPicklistValue } from '@/components/MultiPicklistSelect'
import { useAuth } from '@/context/AuthContext'
import { useApi } from '@/context/ApiContext'
import { LayoutFormSections } from '@/components/LayoutFormSections'
import type { LayoutFormFieldDefinition } from '@/components/LayoutFormSections/LayoutFormSections'
import { useLayoutRules } from '@kelta/components'
import { dtosToLayoutRules } from '@/utils/layoutRules'
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import { useRecord } from '@/hooks/useRecord'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useCollectionPermissions } from '@/hooks/useCollectionPermissions'
import { useLookupDisplayMap } from '@/hooks/useLookupDisplayMap'
import { usePageLayout } from '@/hooks/usePageLayout'
import { resolvePicklistSource } from '@/hooks/usePicklistOptions'
import { useToast } from '@/components/Toast'
import { PluginErrorBoundary } from '@/components/PluginErrorBoundary'
import { usePlugins } from '@/context/PluginContext'
import { InsufficientPrivileges } from '@/components/InsufficientPrivileges'
import { ApiError } from '@/services/apiClient'
import type { FieldDefinition, FieldType } from '@/hooks/useCollectionSchema'
import type { PageLayoutDto } from '@/hooks/usePageLayout'
import type { LookupOption } from '@/components/LookupSelect'
import type { RecordType } from '@/types/collections'
import { buildSaveAttributes, computeInitialFormData } from './formData'

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

/** System fields excluded from forms */
const SYSTEM_FIELDS = new Set([
  'id',
  'createdAt',
  'updatedAt',
  'createdBy',
  'updatedBy',
  'created_at',
  'updated_at',
  'created_by',
  'updated_by',
])

/** Read-only field types that should not be editable */
const READ_ONLY_TYPES: Set<FieldType> = new Set(['auto_number', 'formula', 'rollup_summary'])

/** Reference field types that need lookup dropdowns */
const REFERENCE_TYPES: Set<FieldType> = new Set(['master_detail', 'lookup', 'reference'])

/**
 * Get the HTML input type for a field type.
 */
function getInputType(fieldType: FieldType): string {
  switch (fieldType) {
    case 'number':
    case 'currency':
    case 'percent':
      return 'number'
    case 'date':
      return 'date'
    case 'datetime':
      return 'datetime-local'
    case 'email':
      return 'email'
    case 'phone':
      return 'tel'
    case 'url':
      return 'url'
    default:
      return 'text'
  }
}

/**
 * Check if a field should use a textarea instead of an input.
 */
function isTextareaField(fieldType: FieldType): boolean {
  return fieldType === 'rich_text' || fieldType === 'json'
}

interface FormFieldProps {
  field: FieldDefinition
  value: unknown
  onChange: (name: string, value: unknown) => void
  /** Whether the field is read-only due to field-level permissions */
  readOnly?: boolean
  /** Server-side validation error message for this field */
  error?: string
  /** Optional plugin-provided custom renderer for this field type */
  pluginRenderer?: React.ComponentType<import('../../../types/plugin').FieldRendererProps>
}

/**
 * Renders the appropriate form control for a field type.
 */
export function FormField({
  field,
  value,
  onChange,
  readOnly = false,
  error,
  pluginRenderer: PluginRenderer,
}: FormFieldProps): React.ReactElement {
  const isReadOnly = READ_ONLY_TYPES.has(field.type) || readOnly
  const fieldId = `field-${field.name}`

  const labelEl = (
    <FieldLabel htmlFor={fieldId}>
      {field.displayName || field.name}
      {field.required && <span className="ml-1 text-destructive">*</span>}
    </FieldLabel>
  )

  const errorEl = error ? (
    <p className="text-sm text-destructive" role="alert" data-testid={`field-error-${field.name}`}>
      {error}
    </p>
  ) : null

  // Use plugin-provided custom renderer if available
  if (PluginRenderer) {
    return (
      <div className="space-y-2">
        {labelEl}
        <PluginErrorBoundary compact componentType="field renderer">
          <PluginRenderer
            name={field.name}
            value={value}
            onChange={(v) => onChange(field.name, v)}
            readOnly={isReadOnly}
            disabled={isReadOnly}
            error={error}
            metadata={{ fieldType: field.type, required: field.required }}
          />
        </PluginErrorBoundary>
        {errorEl}
      </div>
    )
  }

  // Boolean fields use a checkbox
  if (field.type === 'boolean') {
    return (
      <div className="space-y-2">
        <div className="flex items-center gap-2">
          <Checkbox
            id={fieldId}
            checked={Boolean(value)}
            onCheckedChange={(checked) => onChange(field.name, checked)}
            disabled={isReadOnly}
          />
          <FieldLabel htmlFor={fieldId} className="mb-0">
            {field.displayName || field.name}
            {field.required && <span className="ml-1 text-destructive">*</span>}
          </FieldLabel>
        </div>
        {errorEl}
      </div>
    )
  }

  // Multi-picklist fields use a checkbox-based multi-select
  if (field.type === 'multi_picklist') {
    const arrayValue = normalizeMultiPicklistValue(value)
    return (
      <div className="space-y-2">
        {labelEl}
        <MultiPicklistSelect
          id={fieldId}
          name={field.name}
          value={arrayValue}
          options={field.enumValues || []}
          onChange={(values) => onChange(field.name, values)}
          disabled={isReadOnly}
          required={field.required}
        />
        {errorEl}
      </div>
    )
  }

  // Single picklist fields use a dropdown select
  if (field.type === 'picklist') {
    return (
      <div className="space-y-2">
        {labelEl}
        <select
          id={fieldId}
          data-testid={fieldId}
          value={value != null ? String(value) : ''}
          onChange={(e) => onChange(field.name, e.target.value)}
          disabled={isReadOnly}
          className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
        >
          <option value="">Select...</option>
          {(field.enumValues || []).map((val: string) => (
            <option key={val} value={val}>
              {field.enumOptions?.find((o) => o.value === val)?.label || val}
            </option>
          ))}
        </select>
        {errorEl}
      </div>
    )
  }

  // Reference fields (master_detail, lookup, reference) use a searchable LookupSelect
  if (REFERENCE_TYPES.has(field.type)) {
    const options = field.lookupOptions || []
    return (
      <div className="space-y-2">
        {labelEl}
        <LookupSelect
          id={fieldId}
          name={field.name}
          value={value != null ? String(value) : ''}
          options={options}
          onChange={(v) => onChange(field.name, v)}
          placeholder="Select..."
          required={field.required}
          disabled={isReadOnly}
        />
        {errorEl}
      </div>
    )
  }

  // Textarea fields (rich_text, json)
  if (isTextareaField(field.type)) {
    // JSON values can arrive as parsed objects/arrays — String() would render "[object Object],…"
    const textValue =
      value == null
        ? ''
        : typeof value === 'string'
          ? value
          : field.type === 'json'
            ? JSON.stringify(value, null, 2)
            : String(value)
    return (
      <div className="space-y-2">
        {labelEl}
        <Textarea
          id={fieldId}
          data-testid={fieldId}
          value={textValue}
          onChange={(e) => onChange(field.name, e.target.value)}
          disabled={isReadOnly}
          rows={field.type === 'json' ? 6 : 4}
          className={field.type === 'json' ? 'font-mono text-sm' : ''}
          placeholder={field.type === 'json' ? '{}' : ''}
        />
        {errorEl}
      </div>
    )
  }

  // Encrypted fields show masked value
  if (field.type === 'encrypted') {
    return (
      <div className="space-y-2">
        {labelEl}
        <Input
          id={fieldId}
          data-testid={fieldId}
          type="password"
          value={value != null ? String(value) : ''}
          onChange={(e) => onChange(field.name, e.target.value)}
        />
        {errorEl}
      </div>
    )
  }

  // Default: text input with appropriate type
  const inputType = getInputType(field.type)
  const step = field.type === 'currency' ? '0.01' : field.type === 'percent' ? '0.01' : undefined

  return (
    <div className="space-y-2">
      {labelEl}
      <Input
        id={fieldId}
        data-testid={fieldId}
        type={inputType}
        value={value != null ? String(value) : ''}
        onChange={(e) => {
          const newValue =
            inputType === 'number'
              ? e.target.value === ''
                ? ''
                : Number(e.target.value)
              : e.target.value
          onChange(field.name, newValue)
        }}
        disabled={isReadOnly}
        step={step}
        required={field.required}
      />
      {errorEl}
    </div>
  )
}

interface ObjectFormBodyProps {
  isNew: boolean
  initialData: Record<string, unknown>
  fields: FieldDefinition[]
  collectionName: string
  collectionLabel: string
  recordId?: string
  basePath: string
  /** Check if a field is editable (VISIBLE vs READ_ONLY from field permissions) */
  isFieldEditable?: (fieldName: string) => boolean
  /** Resolved page layout (null when none configured — falls back to flat grid) */
  layout?: PageLayoutDto | null
  /** Available record types for this collection */
  recordTypes?: RecordType[]
  /** Currently selected record type ID */
  selectedRecordTypeId?: string
  /** Callback when record type selection changes */
  onRecordTypeChange?: (id: string | undefined) => void
}

/**
 * Inner form body — receives initialData so useState initializer runs
 * with the correct value on mount. Parent uses `key` to force remount
 * when the data source changes.
 */
function ObjectFormBody({
  isNew,
  initialData,
  fields,
  collectionName,
  collectionLabel,
  recordId,
  basePath,
  isFieldEditable,
  layout,
  recordTypes,
  selectedRecordTypeId,
  onRecordTypeChange,
}: ObjectFormBodyProps): React.ReactElement {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { getFieldRenderer } = usePlugins()
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
        navigate(`${basePath}/o/${collectionName}`)
      } else {
        navigate(`${basePath}/o/${collectionName}/${recordId}`)
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

  const pageTitle = isNew ? `New ${collectionLabel}` : `Edit ${collectionLabel}`

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

  // Handle cancel
  const handleCancel = useCallback(() => {
    if (recordId) {
      navigate(`${basePath}/o/${collectionName}/${recordId}`)
    } else {
      navigate(`${basePath}/o/${collectionName}`)
    }
  }, [navigate, basePath, collectionName, recordId])

  const isSaving = mutations.create.isPending || mutations.update.isPending

  return (
    <div className="space-y-4 p-6">
      {/* Breadcrumb */}
      <Breadcrumb>
        <BreadcrumbList>
          <BreadcrumbItem>
            <BreadcrumbLink asChild>
              <Link to={`${basePath}/home`}>Home</Link>
            </BreadcrumbLink>
          </BreadcrumbItem>
          <BreadcrumbSeparator />
          <BreadcrumbItem>
            <BreadcrumbLink asChild>
              <Link to={`${basePath}/o/${collectionName}`}>{collectionLabel}</Link>
            </BreadcrumbLink>
          </BreadcrumbItem>
          <BreadcrumbSeparator />
          <BreadcrumbItem>
            <BreadcrumbPage>{isNew ? 'New' : 'Edit'}</BreadcrumbPage>
          </BreadcrumbItem>
        </BreadcrumbList>
      </Breadcrumb>

      {/* Form header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight text-foreground">{pageTitle}</h1>
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={handleCancel}
            disabled={isSaving}
            data-testid="cancel-button"
          >
            <X className="mr-1.5 h-3.5 w-3.5" />
            Cancel
          </Button>
          <Button size="sm" onClick={handleSave} disabled={isSaving} data-testid="save-button">
            {isSaving ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Save className="mr-1.5 h-3.5 w-3.5" />
            )}
            {isSaving ? 'Saving...' : 'Save'}
          </Button>
        </div>
      </div>

      <Separator />

      {/* Record Type Selector — shown when collection has record types */}
      {recordTypes && recordTypes.length > 0 && (
        <Card data-testid="record-type-selector">
          <CardContent className="py-3">
            <div className="flex items-center gap-4">
              <FieldLabel htmlFor="recordType" className="mb-0 whitespace-nowrap">
                Record type
              </FieldLabel>
              <select
                id="recordType"
                data-testid="record-type-select"
                value={selectedRecordTypeId || ''}
                onChange={(e) => onRecordTypeChange?.(e.target.value || undefined)}
                className="flex h-9 w-full max-w-xs rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <option value="">Select record type...</option>
                {recordTypes.map((rt) => (
                  <option key={rt.id} value={rt.id}>
                    {rt.name}
                    {rt.isDefault ? ' (Default)' : ''}
                  </option>
                ))}
              </select>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Form Fields — use page layout sections when available, otherwise flat grid */}
      {layout && layout.sections.length > 0 ? (
        <div data-testid="form-fields">
          <LayoutFormSections
            sections={layout.sections}
            schemaFields={displayFields as unknown as LayoutFormFieldDefinition[]}
            record={formData}
            isComputed={ruleEngine.isComputed}
            renderField={(field: LayoutFormFieldDefinition) => {
              const fieldIsEditable = !isFieldEditable || isFieldEditable(field.name)
              const layoutReadOnly = !!field.readOnly
              return (
                <FormField
                  key={field.name}
                  field={field as FieldDefinition}
                  value={formData[field.name]}
                  onChange={fieldIsEditable && !layoutReadOnly ? handleFieldChange : () => {}}
                  readOnly={!fieldIsEditable || layoutReadOnly}
                  error={formErrors[field.name]}
                  pluginRenderer={getFieldRenderer(field.type)}
                />
              )
            }}
          />
        </div>
      ) : (
        <Card data-testid="form-fields">
          <CardHeader className="py-3">
            <CardTitle className="text-sm font-medium">{collectionLabel} Information</CardTitle>
          </CardHeader>
          <CardContent>
            {displayFields.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No editable fields in this collection.
              </p>
            ) : (
              <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                {displayFields.map((field) => {
                  const fieldIsEditable = !isFieldEditable || isFieldEditable(field.name)
                  return (
                    <FormField
                      key={field.name}
                      field={field}
                      value={formData[field.name]}
                      onChange={fieldIsEditable ? handleFieldChange : () => {}}
                      readOnly={!fieldIsEditable}
                      error={formErrors[field.name]}
                      pluginRenderer={getFieldRenderer(field.type)}
                    />
                  )
                })}
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

export function ObjectFormPage(): React.ReactElement {
  const {
    tenantSlug,
    collection: collectionName,
    id: recordId,
  } = useParams<{
    tenantSlug: string
    collection: string
    id?: string
  }>()
  const basePath = `/${tenantSlug}/app`
  const isNew = !recordId
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { apiClient } = useApi()

  // Fetch collection schema
  const {
    schema,
    fields,
    isLoading: schemaLoading,
    error: schemaError,
  } = useCollectionSchema(collectionName)

  // Fetch existing record for edit mode
  const { record, isLoading: recordLoading } = useRecord({
    collectionName,
    recordId,
    enabled: !isNew,
  })

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

  const [selectedRecordTypeId, setSelectedRecordTypeId] = useState<string | undefined>(() => {
    // For edit mode, use the record's existing recordTypeId
    if (!isNew && record?.recordTypeId) return String(record.recordTypeId)
    return undefined
  })

  // Auto-select default record type on create when record types exist
  React.useEffect(() => {
    if (isNew && recordTypes && recordTypes.length > 0 && !selectedRecordTypeId) {
      const defaultType = recordTypes.find((rt) => rt.isDefault)
      if (defaultType) {
        setSelectedRecordTypeId(defaultType.id)
      }
    }
  }, [isNew, recordTypes, selectedRecordTypeId])

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
    schemaLoading || (!isNew && recordLoading) || permissionsLoading || layoutLoading

  // Collection label
  const collectionLabel =
    schema?.displayName ||
    (collectionName ? collectionName.charAt(0).toUpperCase() + collectionName.slice(1) : 'Object')

  // Extract query parameter defaults for new records (e.g. ?order_ref=<id>)
  const queryDefaults = useMemo(() => {
    if (!isNew) return undefined
    const params: Record<string, string> = {}
    searchParams.forEach((value, key) => {
      params[key] = value
    })
    return Object.keys(params).length > 0 ? params : undefined
  }, [isNew, searchParams])

  // Compute initial data and a key that changes when the data source changes.
  // The key forces ObjectFormBody to remount, running useState with fresh initialData.
  const initialData = useMemo(
    () => computeInitialFormData(isNew, record, enrichedFields, queryDefaults),
    [isNew, record, enrichedFields, queryDefaults]
  )
  const formKey = isNew
    ? `new:${enrichedFields.length}:${searchParams.toString()}`
    : `edit:${recordId}:${record?.id ?? 'loading'}`

  // Handle cancel (needed for error state)
  const handleCancel = useCallback(() => {
    if (recordId) {
      navigate(`${basePath}/o/${collectionName}/${recordId}`)
    } else {
      navigate(`${basePath}/o/${collectionName}`)
    }
  }, [navigate, basePath, collectionName, recordId])

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-12">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  // Permission check: canCreate for new, canEdit for edit
  const requiredPermission = isNew ? permissions.canCreate : permissions.canEdit
  if (!requiredPermission) {
    return (
      <InsufficientPrivileges
        action={isNew ? 'create' : 'edit'}
        resource={collectionLabel}
        backPath={
          recordId
            ? `${basePath}/o/${collectionName}/${recordId}`
            : `${basePath}/o/${collectionName}`
        }
      />
    )
  }

  // Error state
  if (schemaError) {
    return (
      <div className="space-y-4 p-6">
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Error</AlertTitle>
          <AlertDescription>
            {schemaError.message || 'Failed to load collection schema.'}
          </AlertDescription>
        </Alert>
        <Button variant="outline" onClick={handleCancel}>
          Go back
        </Button>
      </div>
    )
  }

  return (
    <ObjectFormBody
      key={formKey}
      isNew={isNew}
      initialData={initialData}
      fields={enrichedFields}
      collectionName={collectionName || ''}
      collectionLabel={collectionLabel}
      recordId={recordId}
      basePath={basePath}
      isFieldEditable={isFieldEditable}
      layout={layout}
      recordTypes={recordTypes}
      selectedRecordTypeId={selectedRecordTypeId}
      onRecordTypeChange={setSelectedRecordTypeId}
    />
  )
}
