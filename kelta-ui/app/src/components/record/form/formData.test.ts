import { describe, it, expect } from 'vitest'
import { buildSaveAttributes, computeCloneFormData, computeInitialFormData } from './formData'
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

describe('computeInitialFormData schema defaults (create)', () => {
  it("applies each field's schema defaultValue on create", () => {
    const fields = [
      field({ name: 'status', defaultValue: 'Open' }),
      field({ name: 'qty', type: 'number', defaultValue: 3 }),
      field({ name: 'active', type: 'boolean', defaultValue: true }),
      field({ name: 'notes' }),
    ]
    const data = computeInitialFormData(true, undefined, fields)
    expect(data).toEqual({ status: 'Open', qty: 3, active: true })
  })

  it('formats defaults for their controls (date, datetime, JSON, boolean text)', () => {
    const fields = [
      field({ name: 'day', type: 'date', defaultValue: '2026-01-15T00:00:00Z' }),
      field({ name: 'at', type: 'datetime', defaultValue: '2026-01-15T09:30:00Z' }),
      field({ name: 'cfg', type: 'json', defaultValue: { a: 1 } }),
      field({ name: 'flag', type: 'boolean', defaultValue: 'true' }),
    ]
    const data = computeInitialFormData(true, undefined, fields)
    expect(data.day).toBe('2026-01-15')
    expect(data.at).toBe('2026-01-15T09:30')
    expect(data.cfg).toBe(JSON.stringify({ a: 1 }, null, 2))
    expect(data.flag).toBe(true)
  })

  it('lets a query-param prefill win over the schema default', () => {
    const fields = [field({ name: 'status', defaultValue: 'Open' })]
    const data = computeInitialFormData(true, undefined, fields, { status: 'Closed' })
    expect(data.status).toBe('Closed')
  })

  it('ignores schema defaults in edit mode', () => {
    const fields = [field({ name: 'status', defaultValue: 'Open' })]
    const data = computeInitialFormData(false, { id: 'r1', status: null }, fields)
    expect(data.status).toBeNull()
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

describe('buildSaveAttributes clearing a field', () => {
  const fields = [
    field({ name: 'title' }),
    field({ name: 'amount', type: 'number' }),
    field({ name: 'notes' }),
    field({ name: 'crew', type: 'json' }),
  ]

  it('update: sends null for a field emptied from a non-empty initial value', () => {
    const initialData = { title: 'A', amount: 7, notes: '', crew: '{"a":1}' }
    const { attributes } = buildSaveAttributes(
      fields,
      { title: 'A', amount: '', notes: '', crew: '' },
      { initialData }
    )
    expect(attributes).toEqual({ title: 'A', amount: null, crew: null })
  })

  it('update: does not start sending fields that were already blank', () => {
    const initialData = { title: 'A', notes: undefined }
    const { attributes } = buildSaveAttributes(
      fields,
      { title: 'A', notes: '', amount: undefined },
      { initialData }
    )
    expect(attributes).toEqual({ title: 'A' })
    expect(attributes).not.toHaveProperty('notes')
    expect(attributes).not.toHaveProperty('amount')
  })

  it('create: keeps omitting blank fields (no initialData)', () => {
    const { attributes } = buildSaveAttributes(fields, { title: 'A', amount: '', notes: '' })
    expect(attributes).toEqual({ title: 'A' })
  })

  it('uses the caller-supplied invalid-JSON message', () => {
    const { errors } = buildSaveAttributes(
      [field({ name: 'crew', type: 'json' })],
      { crew: '{nope' },
      { invalidJsonMessage: 'Please enter valid JSON' }
    )
    expect(errors.crew).toBe('Please enter valid JSON')
  })
})

describe('encrypted fields are write-only', () => {
  const secret = field({ name: 'api_key', type: 'encrypted' })

  it('never loads the stored value into the form on edit', () => {
    const data = computeInitialFormData(false, { name: 'Ada', api_key: 'stored' }, [
      field(),
      secret,
    ])
    expect(data.name).toBe('Ada')
    expect('api_key' in data).toBe(false)
  })

  it('applies no schema default or prefill on create', () => {
    const data = computeInitialFormData(
      true,
      undefined,
      [{ ...secret, defaultValue: 'default-secret' }],
      { api_key: 'from-query' }
    )
    expect(data.api_key).toBeUndefined()
  })

  it('sends a newly typed value', () => {
    const { attributes } = buildSaveAttributes([secret], { api_key: 'new' }, { initialData: {} })
    expect(attributes).toEqual({ api_key: 'new' })
  })

  it('omits a blank value on update — never clears the stored secret', () => {
    for (const blank of [undefined, '']) {
      const { attributes } = buildSaveAttributes(
        [secret],
        { api_key: blank },
        // Even if a caller's initial data carried a value, blank means "keep it".
        { initialData: { api_key: 'stored' } }
      )
      expect(attributes).toEqual({})
    }
  })
})

describe('computeCloneFormData (create from an existing record)', () => {
  const fields = [
    field({ name: 'name' }),
    field({ name: 'day', type: 'date' }),
    field({ name: 'config', type: 'json' }),
    field({ name: 'secret', type: 'encrypted' }),
    field({ name: 'ssn' }),
    field({ name: 'owner', type: 'master_detail' }),
  ]
  const source = {
    id: 'src-1',
    name: 'Original',
    day: '2026-07-18T00:00:00Z',
    config: { a: 1 },
    secret: '********',
    ssn: '***-**-1234',
    owner: 'acct-1',
    _rel_owner: { type: 'accounts', id: 'acct-1' },
    __maskedFields: ['ssn'],
    recordTypeId: 'rt-1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedBy: 'user-1',
  }

  it("copies the source's schema-field values, formatted for the controls", () => {
    const data = computeCloneFormData(source, fields)
    expect(data).toEqual({
      name: 'Original',
      day: '2026-07-18',
      config: JSON.stringify({ a: 1 }, null, 2),
      owner: 'acct-1',
    })
  })

  it('drops the id, audit fields and relationship / masking metadata', () => {
    const data = computeCloneFormData(source, fields)
    for (const key of [
      'id',
      'createdAt',
      'updatedBy',
      '_rel_owner',
      '__maskedFields',
      'recordTypeId',
    ]) {
      expect(data).not.toHaveProperty(key)
    }
  })

  it('never copies an encrypted field or a field the server masked', () => {
    const data = computeCloneFormData(source, fields)
    expect(data).not.toHaveProperty('secret')
    expect(data).not.toHaveProperty('ssn')
  })

  it('lets a create-mode prefill win over the copied values', () => {
    const data = computeCloneFormData(source, fields, { name: 'Prefilled', secret: 'x', bogus: 1 })
    expect(data.name).toBe('Prefilled')
    expect(data).not.toHaveProperty('secret')
    expect(data).not.toHaveProperty('bogus')
  })

  it('skips a schema field the source has no value for', () => {
    const data = computeCloneFormData({ id: 'src-2', name: 'Only name' }, fields)
    expect(data).toEqual({ name: 'Only name' })
  })
})
