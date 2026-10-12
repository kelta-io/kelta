import { describe, it, expect } from 'vitest'
import { normalizeFieldType } from './normalizeFieldType'

/**
 * Every constant of runtime-core `io.kelta.runtime.model.FieldType`, with the UI
 * type it normalizes to by default and with `collapseRelationships: false`
 * (CollectionDetailPage). TEXT and VECTOR have no UI type; they pass through
 * lowercased, as they always have.
 */
const TABLE: Array<[backend: string, collapsed: string, designer: string]> = [
  ['STRING', 'string', 'string'],
  ['INTEGER', 'number', 'number'],
  ['LONG', 'number', 'number'],
  ['DOUBLE', 'number', 'number'],
  ['BOOLEAN', 'boolean', 'boolean'],
  ['DATE', 'date', 'date'],
  ['DATETIME', 'datetime', 'datetime'],
  ['JSON', 'json', 'json'],
  ['REFERENCE', 'master_detail', 'reference'],
  ['ARRAY', 'json', 'json'],
  ['PICKLIST', 'picklist', 'picklist'],
  ['MULTI_PICKLIST', 'multi_picklist', 'multi_picklist'],
  ['CURRENCY', 'currency', 'currency'],
  ['PERCENT', 'percent', 'percent'],
  ['AUTO_NUMBER', 'auto_number', 'auto_number'],
  ['PHONE', 'phone', 'phone'],
  ['EMAIL', 'email', 'email'],
  ['URL', 'url', 'url'],
  ['TEXT', 'text', 'text'],
  ['RICH_TEXT', 'rich_text', 'rich_text'],
  ['VECTOR', 'vector', 'vector'],
  ['ENCRYPTED', 'encrypted', 'encrypted'],
  ['EXTERNAL_ID', 'external_id', 'external_id'],
  ['GEOLOCATION', 'geolocation', 'geolocation'],
  ['LOOKUP', 'master_detail', 'lookup'],
  ['MASTER_DETAIL', 'master_detail', 'master_detail'],
  ['FORMULA', 'formula', 'formula'],
  ['ROLLUP_SUMMARY', 'rollup_summary', 'rollup_summary'],
]

describe('normalizeFieldType', () => {
  it.each(TABLE)('%s → %s (default)', (backend, collapsed) => {
    expect(normalizeFieldType(backend)).toBe(collapsed)
  })

  it.each(TABLE)('%s → %s (collapseRelationships: false)', (backend, _collapsed, designer) => {
    expect(normalizeFieldType(backend, { collapseRelationships: false })).toBe(designer)
  })

  it.each(TABLE)('%s is case-insensitive', (backend, collapsed, designer) => {
    const lower = backend.toLowerCase()
    expect(normalizeFieldType(lower)).toBe(collapsed)
    expect(normalizeFieldType(lower, { collapseRelationships: false })).toBe(designer)
  })

  it('passes an unknown type through lowercased', () => {
    expect(normalizeFieldType('SomethingNew')).toBe('somethingnew')
  })
})
