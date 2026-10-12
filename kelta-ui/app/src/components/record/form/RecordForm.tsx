/**
 * RecordForm — the one create/edit form for a record.
 *
 * Schema-driven: renders a control per field (`FormField`), grouped by the
 * resolved page layout's sections (`LayoutFormSections`) or, without a layout,
 * a flat two-column grid. Handles create and edit, record-type selection,
 * picklist/lookup options, client layout rules, field permissions, server
 * field errors, and the loading / permission / schema-error states.
 *
 * Data comes from `useRecordFormModel`; form state from `useRecordFormState`
 * inside a body keyed on the model's `formKey`. Variant-specific chrome (the
 * breadcrumb, extra header actions) and every navigation target are supplied
 * by the caller, so the end-user (`/app/o`) `ObjectFormPage` — and, in a later
 * slice, the admin `ResourceFormPage` — are thin `variant` wrappers over it.
 *
 * Record-stack convergence P2: moved from `ObjectFormPage`; P3 added the admin
 * form's correctness features (client validation rules, blur rules, schema
 * defaults, clear-to-null, per-field validation with linked a11y errors, create
 * → new record's detail page, i18n). See `.claude/docs/conventions.md` →
 * Component reuse.
 */

import React, { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Save, X, Loader2, AlertCircle } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { FieldLabel } from '@/components/kelta'
import { Separator } from '@/components/ui/separator'
import { LayoutFormSections } from '@/components/LayoutFormSections'
import type { LayoutFormFieldDefinition } from '@/components/LayoutFormSections/LayoutFormSections'
import { usePlugins } from '@/context/PluginContext'
import { useI18n } from '@/context/I18nContext'
import { InsufficientPrivileges } from '@/components/InsufficientPrivileges'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { PageLayoutDto } from '@/hooks/usePageLayout'
import type { RecordType } from '@/types/collections'
import type { CollectionValidationRule } from '@kelta/sdk'
import { FormField } from './FormField'
import { useRecordFormModel } from './useRecordFormModel'
import { useRecordFormState } from './useRecordFormState'

export type RecordFormVariant = 'enduser' | 'admin'

/** Where the form navigates: after save, on cancel, and from the permission gate. */
export interface RecordFormRoutes {
  /** The collection's list (create → cancel). */
  list: string
  /** A record's detail page (create → the new record after save; edit → save/cancel). */
  detail: (id: string) => string
}

/** Values the caller's chrome may need that only the form knows once loaded. */
export interface RecordFormChromeContext {
  collectionLabel: string
  isNew: boolean
}

export interface RecordFormProps {
  /** Which stack renders this form. Exposed as `data-variant`; the slots drive the chrome. */
  variant?: RecordFormVariant
  /** Collection API name. */
  collectionName: string
  /** Record being edited; absent = create. */
  recordId?: string
  /** Navigation targets — the form never builds URLs itself. */
  routes: RecordFormRoutes
  /** Initial values for a NEW record (only keys naming a schema field apply). */
  prefill?: Record<string, unknown>
  /**
   * Breadcrumb row above the header. A function receives the loaded collection
   * label + mode, since the caller cannot know the label before the schema loads.
   */
  breadcrumb?: React.ReactNode | ((ctx: RecordFormChromeContext) => React.ReactNode)
  /** Extra header actions, rendered before Cancel/Save. */
  headerExtras?: React.ReactNode
  /** `data-testid` for the form root. */
  testId?: string
}

interface RecordFormBodyProps {
  variant: RecordFormVariant
  isNew: boolean
  initialData: Record<string, unknown>
  fields: FieldDefinition[]
  collectionName: string
  collectionLabel: string
  recordId?: string
  routes: RecordFormRoutes
  onCancel: () => void
  breadcrumb?: RecordFormProps['breadcrumb']
  headerExtras?: React.ReactNode
  testId?: string
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
  /** Collection validation rules enforced on the client */
  validationRules?: CollectionValidationRule[]
}

/**
 * Inner form body — receives initialData so useState initializer runs
 * with the correct value on mount. Parent uses `key` to force remount
 * when the data source changes.
 */
function RecordFormBody({
  variant,
  isNew,
  initialData,
  fields,
  collectionName,
  collectionLabel,
  recordId,
  routes,
  onCancel,
  breadcrumb,
  headerExtras,
  testId,
  isFieldEditable,
  layout,
  recordTypes,
  selectedRecordTypeId,
  onRecordTypeChange,
  validationRules,
}: RecordFormBodyProps): React.ReactElement {
  const { getFieldRenderer } = usePlugins()
  const { t } = useI18n()
  const {
    formData,
    formErrors,
    ruleEngine,
    displayFields,
    handleFieldChange,
    handleFieldBlur,
    handleSave,
    isSaving,
  } = useRecordFormState({
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
  })

  const pageTitle = isNew
    ? t('recordForm.newTitle', { collection: collectionLabel })
    : t('recordForm.editTitle', { collection: collectionLabel })

  return (
    <div className="space-y-4 p-6" data-testid={testId} data-variant={variant}>
      {/* Breadcrumb */}
      {typeof breadcrumb === 'function' ? breadcrumb({ collectionLabel, isNew }) : breadcrumb}

      {/* Form header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight text-foreground">{pageTitle}</h1>
        <div className="flex items-center gap-2">
          {headerExtras}
          <Button
            size="sm"
            variant="outline"
            onClick={onCancel}
            disabled={isSaving}
            data-testid="cancel-button"
          >
            <X className="mr-1.5 h-3.5 w-3.5" />
            {t('common.cancel')}
          </Button>
          <Button size="sm" onClick={handleSave} disabled={isSaving} data-testid="save-button">
            {isSaving ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Save className="mr-1.5 h-3.5 w-3.5" />
            )}
            {isSaving ? t('common.saving') : t('common.save')}
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
                {t('recordForm.recordType')}
              </FieldLabel>
              <select
                id="recordType"
                data-testid="record-type-select"
                value={selectedRecordTypeId || ''}
                onChange={(e) => onRecordTypeChange?.(e.target.value || undefined)}
                className="flex h-9 w-full max-w-xs rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <option value="">{t('recordForm.selectRecordType')}</option>
                {recordTypes.map((rt) => (
                  <option key={rt.id} value={rt.id}>
                    {rt.isDefault ? t('recordForm.defaultRecordType', { name: rt.name }) : rt.name}
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
                  onBlur={handleFieldBlur}
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
            <CardTitle className="text-sm font-medium">
              {t('recordForm.sectionTitle', { collection: collectionLabel })}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {displayFields.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('recordForm.noEditableFields')}</p>
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
                      onBlur={handleFieldBlur}
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

export function RecordForm({
  variant = 'enduser',
  collectionName,
  recordId,
  routes,
  prefill,
  breadcrumb,
  headerExtras,
  testId,
}: RecordFormProps): React.ReactElement {
  const navigate = useNavigate()
  const { t } = useI18n()
  const {
    isNew,
    isLoading,
    schemaError,
    permissions,
    isFieldEditable,
    fields,
    collectionLabel,
    layout,
    recordTypes,
    selectedRecordTypeId,
    setSelectedRecordTypeId,
    validationRules,
    initialData,
    formKey,
  } = useRecordFormModel({ collectionName, recordId, prefill })

  // Handle cancel (also used by the error state)
  const handleCancel = useCallback(() => {
    if (recordId) {
      navigate(routes.detail(recordId))
    } else {
      navigate(routes.list)
    }
  }, [navigate, routes, recordId])

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
        backPath={recordId ? routes.detail(recordId) : routes.list}
      />
    )
  }

  // Error state
  if (schemaError) {
    return (
      <div className="space-y-4 p-6">
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>{t('common.error')}</AlertTitle>
          <AlertDescription>
            {schemaError.message || t('recordForm.schemaLoadError')}
          </AlertDescription>
        </Alert>
        <Button variant="outline" onClick={handleCancel}>
          {t('recordForm.goBack')}
        </Button>
      </div>
    )
  }

  return (
    <RecordFormBody
      key={formKey}
      variant={variant}
      isNew={isNew}
      initialData={initialData}
      fields={fields}
      collectionName={collectionName}
      collectionLabel={collectionLabel}
      recordId={recordId}
      routes={routes}
      onCancel={handleCancel}
      breadcrumb={breadcrumb}
      headerExtras={headerExtras}
      testId={testId}
      isFieldEditable={isFieldEditable}
      layout={layout}
      recordTypes={recordTypes}
      selectedRecordTypeId={selectedRecordTypeId}
      onRecordTypeChange={setSelectedRecordTypeId}
      validationRules={validationRules}
    />
  )
}

export default RecordForm
