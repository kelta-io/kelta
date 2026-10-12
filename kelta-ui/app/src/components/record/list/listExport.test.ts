import { describe, it, expect } from 'vitest'
import { escapeCSVValue, exportFileName, recordsToCsv } from './listExport'

describe('escapeCSVValue', () => {
  it('writes empty for null/undefined and plain text as is', () => {
    expect(escapeCSVValue(null)).toBe('')
    expect(escapeCSVValue(undefined)).toBe('')
    expect(escapeCSVValue('plain')).toBe('plain')
    expect(escapeCSVValue(42)).toBe('42')
  })

  it('quotes values holding a comma, quote or newline, doubling inner quotes', () => {
    expect(escapeCSVValue('a,b')).toBe('"a,b"')
    expect(escapeCSVValue('say "hi"')).toBe('"say ""hi"""')
    expect(escapeCSVValue('line\nbreak')).toBe('"line\nbreak"')
    expect(escapeCSVValue('cr\rhere')).toBe('"cr\rhere"')
  })

  it('serializes objects as JSON (quoted, since JSON has quotes)', () => {
    expect(escapeCSVValue({ a: 1 })).toBe('"{""a"":1}"')
    expect(escapeCSVValue([1, 2])).toBe('"[1,2]"')
  })

  it('writes booleans as text and quotes a value with several special characters once', () => {
    expect(escapeCSVValue(false)).toBe('false')
    expect(escapeCSVValue('a, "b"\nc')).toBe('"a, ""b""\nc"')
  })
})

describe('recordsToCsv', () => {
  it('writes a label header and one row per record in field order', () => {
    const csv = recordsToCsv(
      [
        { id: '1', title: 'First', amount: 3 },
        { id: '2', title: 'Second, too', amount: null },
      ],
      [{ name: 'title', displayName: 'Title' }, { name: 'amount' }]
    )
    expect(csv).toBe('Title,amount\nFirst,3\n"Second, too",')
  })

  it('writes only the header for no records, and the record id when the id column is visible', () => {
    expect(recordsToCsv([], [{ name: 'title', displayName: 'Title' }])).toBe('Title')
    expect(
      recordsToCsv(
        [{ id: 'r-1', title: 'First' }],
        [
          { name: 'id', displayName: 'ID' },
          { name: 'title', displayName: 'Title' },
        ]
      )
    ).toBe('ID,Title\nr-1,First')
  })
})

describe('exportFileName', () => {
  it('stamps the collection name with the UTC day', () => {
    expect(exportFileName('projects', 'csv', new Date('2026-10-12T23:00:00Z'))).toBe(
      'projects-2026-10-12.csv'
    )
  })
})
