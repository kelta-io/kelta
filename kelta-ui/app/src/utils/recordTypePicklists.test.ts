import { describe, it, expect } from 'vitest'
import {
  RECORD_TYPE_PICKLISTS_PATH,
  parseAvailableValues,
  recordTypePicklistsUrl,
} from './recordTypePicklists'

describe('recordTypePicklistsUrl', () => {
  it('lists one record type’s rows from the record-type-picklists collection, a full page', () => {
    expect(RECORD_TYPE_PICKLISTS_PATH).toBe('/api/record-type-picklists')
    expect(recordTypePicklistsUrl('rt-1')).toBe(
      '/api/record-type-picklists?filter[recordTypeId][eq]=rt-1&page[size]=200'
    )
  })

  it('encodes the record type id', () => {
    expect(recordTypePicklistsUrl('a&b')).toContain('filter[recordTypeId][eq]=a%26b&')
  })
})

describe('parseAvailableValues', () => {
  it.each<[string, unknown, string[] | undefined]>([
    ['a JSON array', ['open', 'closed'], ['open', 'closed']],
    ['an empty array', [], []],
    ['its JSON string encoding', '["open"]', ['open']],
    ['a non-JSON string', 'open, closed', undefined],
    ['a JSON string that is not an array', '"open"', undefined],
    ['an array with non-strings', ['open', 1], undefined],
    ['null', null, undefined],
    ['an object', { open: true }, undefined],
  ])('%s', (_label, raw, expected) => {
    expect(parseAvailableValues(raw)).toEqual(expected)
  })
})
