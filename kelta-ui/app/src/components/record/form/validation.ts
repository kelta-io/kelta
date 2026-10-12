/**
 * Client-side per-field validation for the record form core (`RecordForm`).
 *
 * Ported from `ResourceFormPage`'s `validateField`: required, type-format checks
 * (number / JSON / date / phone / email / URL / geolocation), and the field's
 * configured constraints (min / max length or value, pattern, email, URL).
 *
 * Constraints are read from the schema field's `constraints` JSON — both the dialect
 * the Field editor writes (`{ min, max, pattern, email, url }`, where min/max bound a
 * number's value or a text value's length) and the server's
 * (`{ minLength, maxLength, minValue, maxValue, pattern }`). A pattern is a full match,
 * as the worker's `DefaultValidationEngine` applies it.
 *
 * Advisory only: the worker validates every write and is the source of truth.
 */
import type { FieldDefinition, FieldType } from '@/hooks/useCollectionSchema'

/** The `t()` shape the validators need (params object or inline English fallback). */
export type TranslateFn = (
  key: string,
  paramsOrFallback?: Record<string, string> | string
) => string

const NUMERIC_TYPES: ReadonlySet<FieldType> = new Set(['number', 'currency', 'percent'])

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PHONE_RE = /^[+]?[\d\s\-().]*$/

/** Normalized field constraints (every key optional). */
export interface FieldConstraints {
  minLength?: number
  maxLength?: number
  minValue?: number
  maxValue?: number
  pattern?: string
  email?: boolean
  url?: boolean
}

/** True for the values the form treats as "no value". */
export function isEmptyFormValue(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  )
}

function toNumber(value: unknown): number | undefined {
  if (value === undefined || value === null || value === '') return undefined
  const n = Number(value)
  return Number.isFinite(n) ? n : undefined
}

/**
 * Parse a field's `constraints` (JSON string or object, either dialect) into one shape.
 * Unparseable or absent constraints yield `{}`.
 */
export function parseFieldConstraints(field: FieldDefinition): FieldConstraints {
  let raw: unknown = field.constraints
  if (typeof raw === 'string') {
    if (!raw.trim()) return {}
    try {
      raw = JSON.parse(raw)
    } catch {
      return {}
    }
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const c = raw as Record<string, unknown>
  const numeric = NUMERIC_TYPES.has(field.type)
  const out: FieldConstraints = {
    minLength: toNumber(c.minLength),
    maxLength: toNumber(c.maxLength),
    minValue: toNumber(c.minValue),
    maxValue: toNumber(c.maxValue),
    pattern: typeof c.pattern === 'string' && c.pattern ? c.pattern : undefined,
    email: c.email === true || undefined,
    url: c.url === true || undefined,
  }
  // Field-editor dialect: `min` / `max` bound the value of a number, the length of text.
  const min = toNumber(c.min)
  const max = toNumber(c.max)
  if (numeric) {
    out.minValue ??= min
    out.maxValue ??= max
  } else {
    out.minLength ??= min
    out.maxLength ??= max
  }
  return out
}

function isValidUrl(value: string): boolean {
  try {
    new URL(value)
    return true
  } catch {
    return false
  }
}

/**
 * Validate one field's form value. Returns the (translated) error message, or `null`.
 */
export function validateFieldValue(
  field: FieldDefinition,
  value: unknown,
  t: TranslateFn
): string | null {
  if (isEmptyFormValue(value)) {
    return field.required && field.type !== 'boolean' ? t('common.required') : null
  }

  const text = typeof value === 'string' ? value : undefined

  // Type-format checks
  switch (field.type) {
    case 'number':
    case 'currency':
    case 'percent':
      if (Number.isNaN(Number(value))) return t('resourceForm.validation.invalidNumber')
      break
    case 'json':
      if (text !== undefined && text.trim()) {
        try {
          JSON.parse(text)
        } catch {
          return t('resourceForm.validation.invalidJson')
        }
      }
      break
    case 'date':
    case 'datetime':
      if (text !== undefined && text.trim() && Number.isNaN(new Date(text).getTime())) {
        return t('resourceForm.validation.invalidDate')
      }
      break
    case 'phone':
      if (text !== undefined && text.trim() && !PHONE_RE.test(text)) {
        return t('resourceForm.validation.invalidPhone')
      }
      break
    case 'email':
      if (text !== undefined && text.trim() && !EMAIL_RE.test(text)) {
        return t('resourceForm.validation.email')
      }
      break
    case 'url':
      if (text !== undefined && text.trim() && !isValidUrl(text)) {
        return t('resourceForm.validation.url')
      }
      break
    case 'geolocation':
      if (typeof value === 'object') {
        const geo = value as Record<string, unknown>
        if (geo.latitude !== null && geo.latitude !== undefined) {
          const lat = Number(geo.latitude)
          if (Number.isNaN(lat) || lat < -90 || lat > 90) {
            return t('resourceForm.validation.invalidLatitude')
          }
        }
        if (geo.longitude !== null && geo.longitude !== undefined) {
          const lng = Number(geo.longitude)
          if (Number.isNaN(lng) || lng < -180 || lng > 180) {
            return t('resourceForm.validation.invalidLongitude')
          }
        }
      }
      break
  }

  // Configured constraints
  const c = parseFieldConstraints(field)
  if (NUMERIC_TYPES.has(field.type)) {
    const n = Number(value)
    if (c.minValue !== undefined && n < c.minValue) {
      return t('resourceForm.validation.minValue', { min: String(c.minValue) })
    }
    if (c.maxValue !== undefined && n > c.maxValue) {
      return t('resourceForm.validation.maxValue', { max: String(c.maxValue) })
    }
  } else if (text !== undefined) {
    if (c.minLength !== undefined && text.length < c.minLength) {
      return t('resourceForm.validation.minLength', { min: String(c.minLength) })
    }
    if (c.maxLength !== undefined && text.length > c.maxLength) {
      return t('resourceForm.validation.maxLength', { max: String(c.maxLength) })
    }
    if (c.pattern) {
      let regex: RegExp | undefined
      try {
        regex = new RegExp(`^(?:${c.pattern})$`)
      } catch {
        // An invalid pattern in the definition is skipped, as the worker does.
      }
      if (regex && !regex.test(text)) return t('resourceForm.validation.pattern')
    }
    if (c.email && !EMAIL_RE.test(text)) return t('resourceForm.validation.email')
    if (c.url && !isValidUrl(text)) return t('resourceForm.validation.url')
  }

  return null
}

/** Validate every field; returns a field-name → message map of the failures only. */
export function validateFormValues(
  fields: FieldDefinition[],
  values: Record<string, unknown>,
  t: TranslateFn
): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const field of fields) {
    const message = validateFieldValue(field, values[field.name], t)
    if (message) errors[field.name] = message
  }
  return errors
}
