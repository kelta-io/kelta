import { describe, it, expect } from 'vitest'
import { resolveLookupDisplayField } from './lookupDisplayField'

const field = (name: string, type = 'string') => ({ name, type })

describe('resolveLookupDisplayField', () => {
  it('uses the target collection display field when it has one', () => {
    expect(resolveLookupDisplayField({ displayFieldName: 'code', fields: [field('name')] })).toBe(
      'code'
    )
  })

  it('falls back to a field named name, in any case', () => {
    expect(resolveLookupDisplayField({ fields: [field('title'), field('Name')] })).toBe('Name')
  })

  it('then to the first string field', () => {
    expect(resolveLookupDisplayField({ fields: [field('amount', 'number'), field('title')] })).toBe(
      'title'
    )
  })

  it('labels by id when the target has no usable field', () => {
    expect(resolveLookupDisplayField({ fields: [field('amount', 'number')] })).toBe('id')
    expect(resolveLookupDisplayField({ fields: [] })).toBe('id')
  })

  it('assumes name when the target schema is unknown', () => {
    expect(resolveLookupDisplayField(undefined)).toBe('name')
  })
})
