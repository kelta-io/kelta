import { describe, it, expect } from 'vitest'
import { buildSaveAttributes, computeInitialFormData } from './formData'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

function field(over: Partial<FieldDefinition> = {}): FieldDefinition {
  return { id: 'f1', name: 'name', displayName: 'Name', type: 'string', required: false, ...over }
}

describe('computeInitialFormData', () => {
  it('pretty-prints JSON object values for the textarea editor', () => {
    const fields = [field({ name: 'crew', type: 'json' })]
    const crew = [{ job: 'Novel', name: 'Megan Maxwell', department: 'Writing' }]
    const data = computeInitialFormData(false, { crew }, fields)
    expect(data.crew).toBe(JSON.stringify(crew, null, 2))
  })

  it('leaves JSON string values untouched', () => {
    const fields = [field({ name: 'config', type: 'json' })]
    const data = computeInitialFormData(false, { config: '{"a":1}' }, fields)
    expect(data.config).toBe('{"a":1}')
  })

  it('formats date and datetime strings for HTML inputs', () => {
    const fields = [field({ name: 'day', type: 'date' }), field({ name: 'at', type: 'datetime' })]
    const data = computeInitialFormData(
      false,
      { day: '2026-07-18T00:00:00Z', at: '2026-07-18T09:30:00Z' },
      fields
    )
    expect(data.day).toBe('2026-07-18')
    expect(data.at).toBe('2026-07-18T09:30')
  })

  it('applies boolean and query-param defaults for new records', () => {
    const fields = [field({ name: 'active', type: 'boolean' }), field({ name: 'order_ref' })]
    const data = computeInitialFormData(true, undefined, fields, { order_ref: 'abc' })
    expect(data.active).toBe(false)
    expect(data.order_ref).toBe('abc')
  })
})

describe('buildSaveAttributes', () => {
  it('parses JSON field text back to structured JSON', () => {
    const fields = [field({ name: 'crew', type: 'json' })]
    const crew = [{ job: 'Director', name: 'Lucía Alemany', department: 'Directing' }]
    const { attributes, errors } = buildSaveAttributes(fields, {
      crew: JSON.stringify(crew, null, 2),
    })
    expect(attributes.crew).toEqual(crew)
    expect(errors).toEqual({})
  })

  it('reports invalid JSON as a field error instead of sending the raw string', () => {
    const fields = [field({ name: 'crew', type: 'json' })]
    const { attributes, errors } = buildSaveAttributes(fields, { crew: '{not json' })
    expect(attributes).not.toHaveProperty('crew')
    expect(errors.crew).toBe('Invalid JSON')
  })

  it('passes non-string JSON values through unchanged', () => {
    const fields = [field({ name: 'crew', type: 'json' })]
    const crew = [{ job: 'Screenplay' }]
    const { attributes, errors } = buildSaveAttributes(fields, { crew })
    expect(attributes.crew).toBe(crew)
    expect(errors).toEqual({})
  })

  it('skips undefined and empty-string values', () => {
    const fields = [field({ name: 'title' }), field({ name: 'crew', type: 'json' })]
    const { attributes } = buildSaveAttributes(fields, { title: '', crew: undefined })
    expect(attributes).toEqual({})
  })
})
