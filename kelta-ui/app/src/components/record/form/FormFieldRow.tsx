/**
 * FormFieldRow — one field of the record form: label (+ required marker), the control, optional
 * help text, and the field's error.
 *
 * The control comes from the `components/fieldControl` registry (`getFieldControl(type).Edit`),
 * or from a plugin field renderer when one is registered for the type (isolated by
 * `PluginErrorBoundary`). Record-stack convergence P4 replaced the page-local `FormField` switch
 * with this row, so a field type renders and edits the same way here as in inline / bulk edit.
 *
 * DOM contract (unit + e2e suites rely on it): the control is `id="field-<name>"` and
 * `data-testid="field-<name>"` (composite controls — lookup, multi-picklist, rich text — carry the
 * test id on their root and the id on their focusable element), and carries `aria-invalid` +
 * `aria-describedby` → the error alert `id="field-<name>-error"`,
 * `data-testid="field-error-<name>"`, `role="alert"`.
 *
 * Type hint (opt-in, `showTypeHint`): the field type's translated name (`fields.types.<type>`)
 * under the label, `data-testid="field-type-<name>"` — the admin form shows it.
 *
 * Plugin renderers receive `metadata` with `fieldId` (the control id), `type` / `fieldType`,
 * `required`, `displayName`, `referenceTarget` and, when there is an error, `errorId`.
 *
 * Blur: focus leaving the whole row's control (not moving between a composite control's inner
 * elements) reports `onBlur(name)` — the client `onBlur` rules.
 *
 * Validation runs ONCE, in the form's save path (`validation.ts`: required, type format, the
 * field's constraints, with translated messages). The registry control's own `validate` is for
 * the inline/bulk surfaces and is deliberately not called here, so a field is never validated
 * twice with two different messages.
 */

import React from 'react'
import { useI18n } from '@/context/I18nContext'
import { FieldLabel } from '@/components/kelta'
import { PluginErrorBoundary } from '@/components/PluginErrorBoundary'
import { getFieldControl } from '@/components/fieldControl'
import type { FieldControlContext } from '@/components/fieldControl'
import type { FieldDefinition, FieldType } from '@/hooks/useCollectionSchema'
import type { FieldRendererProps } from '@/types/plugin'
import { READ_ONLY_TYPES, REFERENCE_TYPES } from './formFieldTypes'

export interface FormFieldRowProps {
  field: FieldDefinition
  value: unknown
  onChange: (name: string, value: unknown) => void
  /** Called with the field name when focus leaves the control */
  onBlur?: (name: string) => void
  /** Whether the field is read-only (field permission or layout) */
  readOnly?: boolean
  /** Validation error message for this field (client or server) */
  error?: string
  /** Help / description text shown under the control */
  helpText?: string
  /** Optional plugin-provided custom renderer for this field type */
  pluginRenderer?: React.ComponentType<FieldRendererProps>
  /** Show the field type's name under the label (admin form chrome). */
  showTypeHint?: boolean
}

/** Types whose control shows a "Select…" empty option / prompt. */
const SELECT_TYPES: ReadonlySet<FieldType> = new Set([
  'picklist',
  'multi_picklist',
  ...REFERENCE_TYPES,
])

/** The picklist value → authored label/color map the registry's picklist control reads. */
function toPicklistDisplayMap(
  field: FieldDefinition
): Map<string, { label: string; color?: string }> | undefined {
  if (!field.enumOptions || field.enumOptions.length === 0) return undefined
  return new Map(field.enumOptions.map((o) => [o.value, { label: o.label, color: o.color }]))
}

export function FormFieldRow({
  field,
  value,
  onChange,
  onBlur,
  readOnly = false,
  error,
  helpText,
  pluginRenderer: PluginRenderer,
  showTypeHint = false,
}: FormFieldRowProps): React.ReactElement {
  const { t } = useI18n()
  const isReadOnly = READ_ONLY_TYPES.has(field.type) || readOnly
  const fieldId = `field-${field.name}`
  const errorId = `${fieldId}-error`
  const label = field.displayName || field.name

  // Report a blur only when focus leaves the whole control — composite controls (lookup,
  // multi-picklist, rich text, geolocation, plugin renderers) move focus between inner elements.
  const handleBlur = onBlur
    ? (e: React.FocusEvent<HTMLDivElement>) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) onBlur(field.name)
      }
    : undefined

  const handleChange = (next: unknown): void => onChange(field.name, next)

  const labelEl = (
    <FieldLabel htmlFor={fieldId} className={field.type === 'boolean' ? 'mb-0' : undefined}>
      {label}
      {field.required && <span className="ml-1 text-destructive">*</span>}
    </FieldLabel>
  )

  let control: React.ReactNode
  if (PluginRenderer) {
    control = (
      <PluginErrorBoundary compact componentType="field renderer">
        <PluginRenderer
          name={field.name}
          value={value}
          onChange={handleChange}
          readOnly={isReadOnly}
          disabled={isReadOnly}
          error={error}
          metadata={{
            fieldId,
            type: field.type,
            fieldType: field.type,
            required: field.required,
            displayName: field.displayName,
            referenceTarget: field.referenceTarget,
            errorId: error ? errorId : undefined,
          }}
        />
      </PluginErrorBoundary>
    )
  } else {
    const { Edit } = getFieldControl(field.type)
    const ctx: FieldControlContext = {
      fieldName: field.name,
      displayName: label,
      targetCollection: field.referenceTarget,
      // Already narrowed to the selected record type's picklist override by the model.
      enumValues: field.enumValues,
      picklistDisplayMap: toPicklistDisplayMap(field),
      referenceOptions: field.lookupOptions,
      readOnly: isReadOnly,
      required: field.required,
    }
    const placeholder = SELECT_TYPES.has(field.type)
      ? t('recordForm.selectPlaceholder')
      : field.type === 'encrypted'
        ? t('recordForm.encryptedPlaceholder')
        : undefined
    control = (
      <Edit
        type={field.type}
        value={value}
        ctx={ctx}
        onChange={handleChange}
        id={fieldId}
        testId={fieldId}
        placeholder={placeholder}
        error={error}
        aria-invalid={!!error}
        aria-describedby={error ? errorId : undefined}
      />
    )
  }

  const errorEl = error ? (
    <p
      id={errorId}
      className="text-sm text-destructive"
      role="alert"
      data-testid={`field-error-${field.name}`}
    >
      {error}
    </p>
  ) : null

  const typeHintEl = showTypeHint ? (
    <p className="text-xs text-muted-foreground" data-testid={`field-type-${field.name}`}>
      {t(`fields.types.${field.type.toLowerCase()}`)}
    </p>
  ) : null

  const helpEl = helpText ? (
    <p className="text-xs text-muted-foreground" data-testid={`field-help-${field.name}`}>
      {helpText}
    </p>
  ) : null

  // Checkbox reads naturally with its label beside it.
  if (field.type === 'boolean' && !PluginRenderer) {
    return (
      <div className="space-y-2">
        <div className="flex items-center gap-2" onBlur={handleBlur}>
          {control}
          {labelEl}
        </div>
        {typeHintEl}
        {helpEl}
        {errorEl}
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {labelEl}
      {typeHintEl}
      <div onBlur={handleBlur}>{control}</div>
      {helpEl}
      {errorEl}
    </div>
  )
}
