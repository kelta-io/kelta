/**
 * FormField — the record form's per-field control renderer.
 *
 * Moved verbatim from `ObjectFormPage` (record-stack convergence P2). A later
 * slice replaces it with the `components/fieldControl` registry; until then
 * keep it equivalent so the end-user form does not change.
 */

import React from 'react'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { FieldLabel } from '@/components/kelta'
import { Checkbox } from '@/components/ui/checkbox'
import { LookupSelect } from '@/components/LookupSelect'
import { MultiPicklistSelect, normalizeMultiPicklistValue } from '@/components/MultiPicklistSelect'
import { PluginErrorBoundary } from '@/components/PluginErrorBoundary'
import type { FieldDefinition, FieldType } from '@/hooks/useCollectionSchema'
import { READ_ONLY_TYPES, REFERENCE_TYPES } from './formFieldTypes'

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
