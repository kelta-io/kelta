/**
 * Pure form-state helpers for the record form (`RecordForm`): initial form data from a record
 * (or schema defaults for a new one, or a clone source's values) and the reverse mapping back
 * to a JSON:API attributes payload on save.
 */
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import { SYSTEM_FIELDS } from './formFieldTypes'
import { isEmptyFormValue } from './validation'

/**
 * Convert a stored/API value to what the field's control edits: date/datetime strings
 * trimmed for HTML inputs, JSON pretty-printed as text.
 */
function toFormValue(field: FieldDefinition, value: unknown): unknown {
  if (typeof value === 'string') {
    if (field.type === 'date') {
      // HTML date input expects YYYY-MM-DD
      return value.split('T')[0]
    }
    if (field.type === 'datetime') {
      // HTML datetime-local input expects YYYY-MM-DDTHH:MM
      return value.slice(0, 16)
    }
    if (field.type === 'boolean') {
      return value === 'true'
    }
    return value
  }
  if (field.type === 'json' && typeof value === 'object' && value !== null) {
    // JSON arrives parsed — edit it as text
    return JSON.stringify(value, null, 2)
  }
  return value
}

/**
 * Compute initial form data from record (edit) or field defaults (create).
 *
 * Create: booleans start `false`; a field's schema `defaultValue` applies next; then
 * `queryDefaults` (e.g. `?order_ref=<id>` from a related list) win over both.
 * Formats date/datetime values for HTML input compatibility and JSON values
 * as pretty-printed text for the textarea editor.
 *
 * `encrypted` fields are WRITE-ONLY: the stored (or masked) value is never loaded into the
 * form, and no default/prefill is applied — the field starts empty and is only sent when the
 * user types a new value (see `buildSaveAttributes`).
 */
export function computeInitialFormData(
  isNew: boolean,
  record: Record<string, unknown> | undefined,
  fields: FieldDefinition[],
  queryDefaults?: Record<string, unknown>
): Record<string, unknown> {
  if (!isNew && record) {
    const data: Record<string, unknown> = { ...record }
    for (const field of fields) {
      if (field.type === 'encrypted') {
        delete data[field.name]
        continue
      }
      const value = data[field.name]
      if (value == null) continue
      if (field.type === 'boolean') continue
      data[field.name] = toFormValue(field, value)
    }
    return data
  }
  const defaults: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.type === 'encrypted') continue
    if (field.type === 'boolean') {
      defaults[field.name] = false
    }
    if (field.defaultValue !== undefined && field.defaultValue !== null) {
      defaults[field.name] = toFormValue(field, field.defaultValue)
    }
  }
  // Apply query parameter defaults (e.g. ?order_ref=<id> from related list)
  if (queryDefaults) {
    const fieldNames = new Set(fields.filter((f) => f.type !== 'encrypted').map((f) => f.name))
    for (const [key, value] of Object.entries(queryDefaults)) {
      if (fieldNames.has(key)) {
        defaults[key] = value
      }
    }
  }
  return defaults
}

/**
 * Initial form data for a NEW record cloned from `source` (create mode with a clone source).
 *
 * Copies the source's values for the collection's schema fields only — formatted for the
 * controls as on edit — and nothing else: no `id`, no system/audit fields, no relationship
 * metadata. `encrypted` fields are never copied (write-only), and neither is a field the
 * server MASKED on the source (`__maskedFields`), whose value is a placeholder, not data.
 * `queryDefaults` (create-mode prefill) apply last and win, as on a plain create.
 */
export function computeCloneFormData(
  source: Record<string, unknown>,
  fields: FieldDefinition[],
  queryDefaults?: Record<string, unknown>
): Record<string, unknown> {
  const masked = new Set(
    Array.isArray(source.__maskedFields) ? (source.__maskedFields as unknown[]).map(String) : []
  )
  const loaded = computeInitialFormData(false, source, fields)
  const data: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.type === 'encrypted' || masked.has(field.name) || SYSTEM_FIELDS.has(field.name)) {
      continue
    }
    if (field.name in loaded) {
      data[field.name] = loaded[field.name]
    }
  }
  if (queryDefaults) {
    for (const field of fields) {
      if (field.type !== 'encrypted' && field.name in queryDefaults) {
        data[field.name] = queryDefaults[field.name]
      }
    }
  }
  return data
}

export interface BuildSaveAttributesOptions {
  /**
   * The form's initial values when editing an existing record. A field the user emptied
   * whose initial value was non-empty is sent as `null` so the clear persists; without
   * it (create), empty fields are omitted.
   */
  initialData?: Record<string, unknown>
  /** Field error for unparseable JSON text (translated by the caller). */
  invalidJsonMessage?: string
}

/**
 * Build the JSON:API attributes payload from form state. JSON fields are edited as
 * text — parse them back to structured JSON here; a parse failure becomes a field
 * error so the raw string never overwrites structured data.
 *
 * Empty values (`undefined` / `''`) are omitted, except on update for a field that was
 * non-empty when the form loaded: that is a deliberate clear, sent as `null`. A blank
 * `encrypted` field is always omitted (write-only: blank means "keep the stored secret").
 */
export function buildSaveAttributes(
  editableFields: FieldDefinition[],
  formData: Record<string, unknown>,
  options: BuildSaveAttributesOptions = {}
): { attributes: Record<string, unknown>; errors: Record<string, string> } {
  const { initialData, invalidJsonMessage = 'Invalid JSON' } = options
  const attributes: Record<string, unknown> = {}
  const errors: Record<string, string> = {}
  for (const field of editableFields) {
    const value = formData[field.name]
    if (value === undefined || value === '' || (field.type === 'json' && isBlankText(value))) {
      if (field.type !== 'encrypted' && initialData && !isEmptyFormValue(initialData[field.name])) {
        attributes[field.name] = null
      }
      continue
    }
    if (field.type === 'json' && typeof value === 'string') {
      try {
        attributes[field.name] = JSON.parse(value)
      } catch {
        errors[field.name] = invalidJsonMessage
      }
      continue
    }
    attributes[field.name] = value
  }
  return { attributes, errors }
}

function isBlankText(value: unknown): boolean {
  return typeof value === 'string' && value.trim() === ''
}
