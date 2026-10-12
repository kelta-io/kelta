/**
 * RecordForm — the one create/edit form for a record.
 *
 * Schema-driven: renders a row per field (`FormFieldRow` — label, the
 * `components/fieldControl` registry's edit control or a plugin renderer, help
 * text, error), grouped by the
 * resolved page layout's sections (`LayoutFormSections`) or, without a layout,
 * a flat two-column grid. Handles create and edit, record-type selection,
 * picklist/lookup options, client layout rules, field permissions, server
 * field errors, and the loading / permission / schema-error states.
 *
 * Data comes from `useRecordFormModel`; form state from `useRecordFormState`
 * inside a body keyed on the model's `formKey`. Variant-specific chrome (the
 * breadcrumb, a banner under the title, extra header actions, field type hints)
 * and every navigation target are supplied by the caller, so the end-user
 * (`/app/o`) `ObjectFormPage` and the admin (`/resources`) `ResourceFormPage`
 * are thin `variant` wrappers over it.
 *
 * Record-stack convergence P2: moved from `ObjectFormPage`; P3 added the admin
 * form's correctness features (client validation rules, blur rules, schema
 * defaults, clear-to-null, per-field validation with linked a11y errors, create
 * → new record's detail page, i18n); P4 rendered fields through the
 * fieldControl registry; P5 sent the record's ETag as `If-Match` on update and
 * shows `RecordConflictDialog` on a concurrent edit (edits kept until the user
 * reloads). P6 converged the admin form onto it: clone (`cloneFromId` — a create
 * seeded from an existing record), the record-load error state, the success
 * toast, the empty-schema disabled Save, and the `banner` / `showFieldTypeHints`
 * chrome. P7 made it embeddable (`presentation="embedded"`, the page-builder `form`
 * widget): no page padding or `h1`, no Cancel, never navigates — `onSaved` gets the
 * saved record, then a create resets to a blank form and an edit re-reads the record
 * (fresh values + ETag) — plus `readOnly` and a `fieldNames` subset (both also usable
 * on a page). See `.claude/docs/conventions.md` → Component reuse.
 */

import React, { useCallback, useMemo, useState } from 'react'
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
import { RecordConflictDialog } from '@/components/record/RecordConflictDialog'
import { RecordStackProvider } from '@/context/RecordStackContext'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { PageLayoutDto } from '@/hooks/usePageLayout'
import type { RecordType } from '@/types/collections'
import type { CollectionValidationRule } from '@kelta/sdk'
import { FormFieldRow } from './FormFieldRow'
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
  /** A create seeded from `cloneFromId`. */
  isClone: boolean
}

/** Chrome rendered from the loaded form context (breadcrumb, banner). */
type ChromeSlot = React.ReactNode | ((ctx: RecordFormChromeContext) => React.ReactNode)

function renderSlot(slot: ChromeSlot | undefined, ctx: RecordFormChromeContext): React.ReactNode {
  return typeof slot === 'function' ? slot(ctx) : slot
}

/** Called after an embedded form saves, with the saved record as the API returned it. */
export type RecordFormSavedHandler = (
  record: CollectionRecord | undefined,
  context: { isNew: boolean }
) => void

interface RecordFormCommonProps {
  /** Which stack renders this form. Exposed as `data-variant` and provided as the record
   *  stack (`RecordStackProvider`) to the form body; the slots drive the chrome. */
  variant?: RecordFormVariant
  /** Collection API name. */
  collectionName: string
  /** Record being edited; absent = create. */
  recordId?: string
  /**
   * Show the record without letting it be edited: every field read-only, no Save. Gated on
   * read permission instead of create/edit.
   */
  readOnly?: boolean
  /**
   * Render only these fields, in this order (unknown names are skipped; empty = every field).
   * The page layout is not used when a subset is given — its sections would not match it.
   */
  fieldNames?: string[]
  /** Initial values for a NEW record (only keys naming a schema field apply). */
  prefill?: Record<string, unknown>
  /**
   * Create mode only: seed the new record from this existing record's values (minus system,
   * encrypted and masked fields; `prefill` still wins). The title becomes "Clone …".
   */
  cloneFromId?: string
  /**
   * Breadcrumb row above the header. A function receives the loaded collection
   * label + mode, since the caller cannot know the label before the schema loads.
   */
  breadcrumb?: ChromeSlot
  /** Content under the title row (e.g. the record id, a "cloning from" notice). */
  banner?: ChromeSlot
  /** Extra header actions, rendered before Cancel/Save. */
  headerExtras?: React.ReactNode
  /** Show each field's type name under its label. */
  showFieldTypeHints?: boolean
  /** `data-testid` for the form root. */
  testId?: string
}

/** A full-page form: navigates via `routes` after save, on cancel and from the error states. */
interface RecordFormPageProps extends RecordFormCommonProps {
  presentation?: 'page'
  /** Navigation targets — the form never builds URLs itself. */
  routes: RecordFormRoutes
  onSaved?: never
}

/**
 * A form embedded in another surface (the page-builder `form` widget): no page padding, an `h2`
 * title, no Cancel, and it never navigates. After a save it calls `onSaved`, then a create resets
 * to a blank form and an edit re-reads the record (its saved values and new ETag).
 */
interface RecordFormEmbeddedProps extends RecordFormCommonProps {
  presentation: 'embedded'
  routes?: never
  onSaved?: RecordFormSavedHandler
}

export type RecordFormProps = RecordFormPageProps | RecordFormEmbeddedProps

/** No field is editable (`readOnly`). */
const NEVER_EDITABLE = (): boolean => false

/** The `fieldNames` subset of `fields`, in `fieldNames` order; every field when none are named. */
function selectFormFields(fields: FieldDefinition[], fieldNames?: string[]): FieldDefinition[] {
  if (!fieldNames || fieldNames.length === 0) return fields
  const byName = new Map(fields.map((field) => [field.name, field]))
  return fieldNames.flatMap((name) => {
    const field = byName.get(name)
    return field ? [field] : []
  })
}

interface RecordFormBodyProps {
  variant: RecordFormVariant
  embedded: boolean
  readOnly: boolean
  isNew: boolean
  isClone: boolean
  initialData: Record<string, unknown>
  fields: FieldDefinition[]
  collectionName: string
  collectionLabel: string
  recordId?: string
  routes?: RecordFormRoutes
  /** Embedded forms: replaces navigation after a save. */
  onSaved?: (saved: CollectionRecord | undefined) => void
  /** Absent = no Cancel button (embedded forms). */
  onCancel?: () => void
  breadcrumb?: ChromeSlot
  banner?: ChromeSlot
  headerExtras?: React.ReactNode
  showFieldTypeHints: boolean
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
  /** The loaded record's ETag (edit mode) — sent as `If-Match` on update. */
  etag?: string
  /** Refetch the record and re-initialise the form (the conflict prompt's Reload). */
  onReload: () => Promise<void>
}

/**
 * Inner form body — receives initialData so useState initializer runs
 * with the correct value on mount. Parent uses `key` to force remount
 * when the data source changes.
 */
function RecordFormBody({
  variant,
  embedded,
  readOnly,
  isNew,
  isClone,
  initialData,
  fields,
  collectionName,
  collectionLabel,
  recordId,
  routes,
  onSaved,
  onCancel,
  breadcrumb,
  banner,
  headerExtras,
  showFieldTypeHints,
  testId,
  isFieldEditable,
  layout,
  recordTypes,
  selectedRecordTypeId,
  onRecordTypeChange,
  validationRules,
  etag,
  onReload,
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
    conflict,
    dismissConflict,
  } = useRecordFormState({
    isNew,
    initialData,
    fields,
    collectionName,
    recordId,
    routes,
    onSaved,
    isFieldEditable,
    layout,
    selectedRecordTypeId,
    validationRules,
    etag,
  })

  // Reload re-keys this body (fresh values, ETag and conflict state) once the refetch lands.
  const [reloading, setReloading] = useState(false)
  const handleReload = useCallback(async () => {
    setReloading(true)
    try {
      await onReload()
    } finally {
      setReloading(false)
    }
  }, [onReload])

  const pageTitle = readOnly
    ? collectionLabel
    : isClone
      ? t('recordForm.cloneTitle', { collection: collectionLabel })
      : isNew
        ? t('recordForm.newTitle', { collection: collectionLabel })
        : t('recordForm.editTitle', { collection: collectionLabel })
  // Embedded in a page that has its own h1: the form's title is a section heading.
  const TitleTag = embedded ? 'h2' : 'h1'
  const chromeContext: RecordFormChromeContext = { collectionLabel, isNew, isClone }
  // Nothing to save on a collection without a single form field.
  const nothingToSave = displayFields.length === 0

  return (
    <div
      className={embedded ? 'space-y-4' : 'space-y-4 p-6'}
      data-testid={testId}
      data-variant={variant}
      data-presentation={embedded ? 'embedded' : 'page'}
    >
      {/* Breadcrumb */}
      {renderSlot(breadcrumb, chromeContext)}

      {/* Form header */}
      <div className="flex items-center justify-between">
        <TitleTag
          className={
            embedded
              ? 'text-base font-semibold text-foreground'
              : 'text-xl font-semibold tracking-tight text-foreground'
          }
          data-testid={embedded ? 'record-form-title' : 'page-title'}
        >
          {pageTitle}
        </TitleTag>
        <div className="flex items-center gap-2">
          {headerExtras}
          {onCancel && (
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
          )}
          {!readOnly && (
            <Button
              size="sm"
              onClick={handleSave}
              disabled={isSaving || nothingToSave}
              data-testid="save-button"
            >
              {isSaving ? (
                <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
              ) : (
                <Save className="mr-1.5 h-3.5 w-3.5" />
              )}
              {isSaving ? t('common.saving') : t('common.save')}
            </Button>
          )}
        </div>
      </div>

      {renderSlot(banner, chromeContext)}

      <Separator />

      <RecordConflictDialog
        open={conflict}
        reloading={reloading}
        onReload={() => void handleReload()}
        onKeepEditing={dismissConflict}
      />

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
              // Help text is rendered by LayoutFormSections (layout override → description).
              return (
                <FormFieldRow
                  key={field.name}
                  field={field as FieldDefinition}
                  value={formData[field.name]}
                  onChange={fieldIsEditable && !layoutReadOnly ? handleFieldChange : () => {}}
                  readOnly={!fieldIsEditable || layoutReadOnly}
                  onBlur={handleFieldBlur}
                  error={formErrors[field.name]}
                  pluginRenderer={getFieldRenderer(field.type)}
                  showTypeHint={showFieldTypeHints}
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
              <p className="text-sm text-muted-foreground" data-testid="no-fields">
                {t('recordForm.noEditableFields')}
              </p>
            ) : (
              <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                {displayFields.map((field) => {
                  const fieldIsEditable = !isFieldEditable || isFieldEditable(field.name)
                  return (
                    <FormFieldRow
                      key={field.name}
                      field={field}
                      value={formData[field.name]}
                      onChange={fieldIsEditable ? handleFieldChange : () => {}}
                      readOnly={!fieldIsEditable}
                      helpText={field.description}
                      onBlur={handleFieldBlur}
                      error={formErrors[field.name]}
                      pluginRenderer={getFieldRenderer(field.type)}
                      showTypeHint={showFieldTypeHints}
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

export function RecordForm(props: RecordFormProps): React.ReactElement {
  const {
    variant = 'enduser',
    collectionName,
    recordId,
    routes,
    onSaved,
    readOnly = false,
    fieldNames,
    prefill,
    cloneFromId,
    breadcrumb,
    banner,
    headerExtras,
    showFieldTypeHints = false,
    testId,
  } = props
  const embedded = props.presentation === 'embedded'
  const navigate = useNavigate()
  const { t } = useI18n()
  const {
    isNew,
    isClone,
    recordError,
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
    etag,
    reload,
  } = useRecordFormModel({ collectionName, recordId, prefill, cloneFromId })

  const formFields = useMemo(() => selectFormFields(fields, fieldNames), [fields, fieldNames])

  // Handle cancel (also used by the error state). Embedded forms have no routes: no Cancel.
  const handleCancel = useCallback(() => {
    if (!routes) return
    if (recordId) {
      navigate(routes.detail(recordId))
    } else {
      navigate(routes.list)
    }
  }, [navigate, routes, recordId])

  // Embedded: stay on the surface after a save. `resetCount` re-keys the body so a create
  // starts the next entry blank; an edit re-reads the record so the next save carries the
  // new ETag instead of tripping the stale-write check.
  const [resetCount, setResetCount] = useState(0)
  const handleEmbeddedSaved = useCallback(
    (saved: CollectionRecord | undefined) => {
      onSaved?.(saved, { isNew })
      if (isNew) {
        setResetCount((count) => count + 1)
      } else {
        void reload()
      }
    },
    [onSaved, isNew, reload]
  )

  // Loading state
  if (isLoading) {
    return (
      <div
        className="flex items-center justify-center p-12"
        data-testid="record-form-loading"
        role="status"
        aria-label={t('common.loading')}
      >
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  // Permission check: canRead when read-only, else canCreate for new, canEdit for edit
  const requiredPermission = readOnly
    ? permissions.canRead
    : isNew
      ? permissions.canCreate
      : permissions.canEdit
  if (!requiredPermission) {
    if (embedded) {
      // No full-page gate (its Go back would navigate away from the host page).
      return (
        <Alert variant="destructive" data-testid="record-form-forbidden">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>{t('errors.forbidden')}</AlertDescription>
        </Alert>
      )
    }
    return (
      <InsufficientPrivileges
        action={readOnly ? 'view' : isNew ? 'create' : 'edit'}
        resource={collectionLabel}
        backPath={routes && (recordId ? routes.detail(recordId) : routes.list)}
      />
    )
  }

  // Error state: the schema, or the record being edited / cloned, could not be loaded
  if (schemaError || recordError) {
    return (
      <div className={embedded ? 'space-y-4' : 'space-y-4 p-6'} data-testid="record-form-error">
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>{t('common.error')}</AlertTitle>
          <AlertDescription>
            {schemaError
              ? schemaError.message || t('recordForm.schemaLoadError')
              : t('recordForm.recordLoadError')}
          </AlertDescription>
        </Alert>
        {routes && (
          <Button variant="outline" onClick={handleCancel}>
            {t('recordForm.goBack')}
          </Button>
        )}
      </div>
    )
  }

  return (
    <RecordStackProvider variant={variant}>
      <RecordFormBody
        key={`${formKey}:${resetCount}`}
        variant={variant}
        embedded={embedded}
        readOnly={readOnly}
        isNew={isNew}
        isClone={isClone}
        initialData={initialData}
        fields={formFields}
        collectionName={collectionName}
        collectionLabel={collectionLabel}
        recordId={recordId}
        routes={routes}
        onSaved={embedded ? handleEmbeddedSaved : undefined}
        onCancel={routes ? handleCancel : undefined}
        breadcrumb={breadcrumb}
        banner={banner}
        headerExtras={headerExtras}
        showFieldTypeHints={showFieldTypeHints}
        testId={testId}
        isFieldEditable={readOnly ? NEVER_EDITABLE : isFieldEditable}
        layout={fieldNames && fieldNames.length > 0 ? null : layout}
        recordTypes={recordTypes}
        selectedRecordTypeId={selectedRecordTypeId}
        onRecordTypeChange={setSelectedRecordTypeId}
        validationRules={validationRules}
        etag={etag}
        onReload={reload}
      />
    </RecordStackProvider>
  )
}

export default RecordForm
