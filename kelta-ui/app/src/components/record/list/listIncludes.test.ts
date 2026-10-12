import { describe, it, expect } from 'vitest'
import { referenceIncludeParam, REFERENCE_FIELD_TYPES } from './listIncludes'

describe('referenceIncludeParam', () => {
  it('returns undefined when there are no reference fields', () => {
    expect(referenceIncludeParam([])).toBeUndefined()
  })

  it('uses the reference FIELD names, not target collection names', () => {
    // Regression: the worker resolves include by field name (title/provider), not target type
    // (titles/providers). Passing target names returns no included resources → raw ids.
    expect(referenceIncludeParam([{ name: 'title' }, { name: 'provider' }])).toBe('title,provider')
  })

  it('dedupes repeated field names', () => {
    expect(referenceIncludeParam([{ name: 'title' }, { name: 'title' }])).toBe('title')
  })

  it('recognises the reference field types', () => {
    expect(REFERENCE_FIELD_TYPES.has('lookup')).toBe(true)
    expect(REFERENCE_FIELD_TYPES.has('master_detail')).toBe(true)
    expect(REFERENCE_FIELD_TYPES.has('reference')).toBe(true)
    expect(REFERENCE_FIELD_TYPES.has('string')).toBe(false)
  })
})
