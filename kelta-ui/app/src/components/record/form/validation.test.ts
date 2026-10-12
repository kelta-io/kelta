import { describe, it, expect } from 'vitest'
import en from '@/i18n/translations/en.json'
import {
  isEmptyFormValue,
  parseFieldConstraints,
  validateFieldValue,
  validateFormValues,
  type TranslateFn,
} from './validation'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

/** Resolve keys against the real English table, interpolating {{params}}. */
const t: TranslateFn = (key, params) => {
  let node: unknown = en
  for (const part of key.split('.')) {
    node = (node as Record<string, unknown> | undefined)?.[part]
  }
  if (typeof node !== 'string') return key
  if (!params || typeof params === 'string') return node
  return node.replace(/\{\{(\w+)\}\}/g, (_, name: string) => params[name] ?? '')
}

function field(over: Partial<FieldDefinition> = {}): FieldDefinition {
  return { id: 'f1', name: 'name', displayName: 'Name', type: 'string', required: false, ...over }
}

describe('validateFieldValue — required', () => {
  it('flags an empty required field', () => {
    for (const empty of [undefined, null, '', []]) {
      expect(validateFieldValue(field({ required: true }), empty, t)).toBe('Required')
    }
  })

  it('accepts an empty optional field', () => {
    expect(validateFieldValue(field(), '', t)).toBeNull()
  })

  it('never flags a required boolean (false is a value)', () => {
    expect(validateFieldValue(field({ type: 'boolean', required: true }), false, t)).toBeNull()
  })
})

describe('validateFieldValue — encrypted (write-only)', () => {
  const secret = field({ name: 'api_key', type: 'encrypted', required: true })

  it('requires a value on create', () => {
    expect(validateFieldValue(secret, '', t)).toBe('Required')
  })

  it('accepts a blank value on update (keeps the stored secret)', () => {
    expect(validateFieldValue(secret, '', t, { isUpdate: true })).toBeNull()
    expect(validateFormValues([secret], {}, t, { isUpdate: true })).toEqual({})
  })

  it('still flags other blank required fields on update', () => {
    expect(validateFieldValue(field({ required: true }), '', t, { isUpdate: true })).toBe(
      'Required'
    )
  })
})

describe('validateFieldValue — formats', () => {
  it.each([
    ['email', 'not-an-email', 'Please enter a valid email address'],
    ['url', 'nope', 'Please enter a valid URL'],
    ['phone', 'call me', 'Invalid phone number format'],
    ['json', '{bad', 'Please enter valid JSON'],
    ['date', 'not a date', 'Please enter a valid date'],
  ] as const)('%s rejects %j', (type, value, message) => {
    expect(validateFieldValue(field({ type }), value, t)).toBe(message)
  })

  it.each([
    ['email', 'ada@example.com'],
    ['url', 'https://kelta.io'],
    ['phone', '+1 (555) 010-0000'],
    ['json', '{"a":1}'],
    ['date', '2026-01-15'],
    ['number', 42],
  ] as const)('%s accepts %j', (type, value) => {
    expect(validateFieldValue(field({ type }), value, t)).toBeNull()
  })

  it('range-checks geolocation', () => {
    const geo = field({ type: 'geolocation' })
    expect(validateFieldValue(geo, { latitude: 91, longitude: 0 }, t)).toBe(
      'Latitude must be between -90 and 90'
    )
    expect(validateFieldValue(geo, { latitude: 0, longitude: 181 }, t)).toBe(
      'Longitude must be between -180 and 180'
    )
  })
})

describe('validateFieldValue — constraints', () => {
  it('applies min/max length to text (field-editor dialect, JSON string)', () => {
    const f = field({ constraints: JSON.stringify({ min: 2, max: 4 }) })
    expect(validateFieldValue(f, 'a', t)).toBe('Must be at least 2 characters')
    expect(validateFieldValue(f, 'abcde', t)).toBe('Must be at most 4 characters')
    expect(validateFieldValue(f, 'abc', t)).toBeNull()
  })

  it('applies min/max value to numbers (server dialect, object)', () => {
    const f = field({ type: 'number', constraints: { minValue: 1, maxValue: 10 } })
    expect(validateFieldValue(f, 0, t)).toBe('Value must be at least 1')
    expect(validateFieldValue(f, 11, t)).toBe('Value must be at most 10')
    expect(validateFieldValue(f, 5, t)).toBeNull()
  })

  it('maps the field-editor min/max to value bounds on a number', () => {
    expect(parseFieldConstraints(field({ type: 'currency', constraints: { min: 1 } }))).toEqual(
      expect.objectContaining({ minValue: 1, minLength: undefined })
    )
  })

  it('full-matches a pattern, as the worker does', () => {
    const f = field({ constraints: { pattern: '[A-Z]{3}' } })
    expect(validateFieldValue(f, 'ABC', t)).toBeNull()
    expect(validateFieldValue(f, 'ABCD', t)).toBe('Value does not match the required pattern')
  })

  it('skips an invalid pattern instead of blocking', () => {
    expect(validateFieldValue(field({ constraints: { pattern: '(' } }), 'x', t)).toBeNull()
  })

  it('applies email / url constraints on a plain text field', () => {
    expect(validateFieldValue(field({ constraints: { email: true } }), 'x', t)).toBe(
      'Please enter a valid email address'
    )
    expect(validateFieldValue(field({ constraints: { url: true } }), 'x', t)).toBe(
      'Please enter a valid URL'
    )
  })

  it('ignores unparseable constraints', () => {
    expect(parseFieldConstraints(field({ constraints: '{oops' }))).toEqual({})
    expect(validateFieldValue(field({ constraints: '{oops' }), 'x', t)).toBeNull()
  })
})

describe('validateFormValues', () => {
  it('returns only the failing fields', () => {
    const fields = [
      field({ name: 'title', required: true }),
      field({ name: 'email', type: 'email' }),
      field({ name: 'notes' }),
    ]
    expect(validateFormValues(fields, { title: '', email: 'bad', notes: 'ok' }, t)).toEqual({
      title: 'Required',
      email: 'Please enter a valid email address',
    })
  })
})

describe('isEmptyFormValue', () => {
  it('treats undefined, null, empty string and empty array as empty', () => {
    expect([undefined, null, '', []].every(isEmptyFormValue)).toBe(true)
    expect([0, false, ' ', ['a']].some(isEmptyFormValue)).toBe(false)
  })
})
