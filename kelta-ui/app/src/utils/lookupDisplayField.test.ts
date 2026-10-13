import { describe, it, expect } from 'vitest'
import { buildLookupLabels, resolveLookupDisplayField } from './lookupDisplayField'

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

describe('buildLookupLabels', () => {
  const response = {
    data: [],
    included: [
      { type: 'people', id: 'p-1', attributes: { age: 36, fullName: 'Ada Lovelace' } },
      { type: 'accounts', id: 'a-1', attributes: { name: 'Acme', code: 'ACME' } },
    ],
  }
  const schemas: Record<
    string,
    { displayFieldName?: string; fields?: Array<{ name: string; type: string }> }
  > = {
    people: { fields: [field('age', 'number'), field('fullName')] },
    accounts: { displayFieldName: 'code', fields: [field('name')] },
  }

  it("labels each lookup field's included targets by the target's label field", () => {
    expect(
      buildLookupLabels(
        response,
        [
          { name: 'owner', referenceTarget: 'people' },
          { name: 'account', referenceTarget: 'accounts' },
        ],
        (name) => schemas[name]
      )
    ).toEqual({ owner: { 'p-1': 'Ada Lovelace' }, account: { 'a-1': 'ACME' } })
  })

  it('leaves out fields without included targets and is undefined when none resolve', () => {
    expect(
      buildLookupLabels(
        response,
        [{ name: 'manager', referenceTarget: 'managers' }],
        () => undefined
      )
    ).toBeUndefined()
    expect(
      buildLookupLabels(undefined, [{ name: 'owner', referenceTarget: 'people' }], () => undefined)
    ).toBeUndefined()
  })
})
