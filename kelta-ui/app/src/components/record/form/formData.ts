/**
 * Pure form-state helpers for the record form (`RecordForm`): initial form data from a record
 * (or defaults for a new one) and the reverse mapping back to a JSON:API
 * attributes payload on save.
 */
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

/**
 * Compute initial form data from record (edit) or field defaults (create).
 * Formats date/datetime values for HTML input compatibility and JSON values
 * as pretty-printed text for the textarea editor.
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
      const value = data[field.name]
      if (value == null) continue
      if (typeof value === 'string') {
        if (field.type === 'date') {
          // HTML date input expects YYYY-MM-DD
          data[field.name] = value.split('T')[0]
        } else if (field.type === 'datetime') {
          // HTML datetime-local input expects YYYY-MM-DDTHH:MM
          data[field.name] = value.slice(0, 16)
        }
      } else if (field.type === 'json' && typeof value === 'object') {
        // JSON arrives parsed — edit it as text
        data[field.name] = JSON.stringify(value, null, 2)
      }
    }
    return data
  }
  const defaults: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.type === 'boolean') {
      defaults[field.name] = false
    }
  }
  // Apply query parameter defaults (e.g. ?order_ref=<id> from related list)
  if (queryDefaults) {
    const fieldNames = new Set(fields.map((f) => f.name))
    for (const [key, value] of Object.entries(queryDefaults)) {
      if (fieldNames.has(key)) {
        defaults[key] = value
      }
    }
  }
  return defaults
}

/**
 * Build the JSON:API attributes payload from form state. JSON fields are edited as
 * text — parse them back to structured JSON here; a parse failure becomes a field
 * error so the raw string never overwrites structured data.
 */
export function buildSaveAttributes(
  editableFields: FieldDefinition[],
  formData: Record<string, unknown>
): { attributes: Record<string, unknown>; errors: Record<string, string> } {
  const attributes: Record<string, unknown> = {}
  const errors: Record<string, string> = {}
  for (const field of editableFields) {
    const value = formData[field.name]
    if (value === undefined || value === '') continue
    if (field.type === 'json' && typeof value === 'string') {
      try {
        attributes[field.name] = JSON.parse(value)
      } catch {
        errors[field.name] = 'Invalid JSON'
      }
      continue
    }
    attributes[field.name] = value
  }
  return { attributes, errors }
}
