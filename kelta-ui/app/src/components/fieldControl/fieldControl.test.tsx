import React from 'react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { FieldType } from '@/hooks/useCollectionSchema'
import { getFieldControl, registerFieldControl, resetFieldControls, CONTROLS } from './index'
import type { FieldControlContext } from './types'

const ALL_TYPES: FieldType[] = [
  'string',
  'number',
  'boolean',
  'date',
  'datetime',
  'json',
  'reference',
  'picklist',
  'multi_picklist',
  'currency',
  'percent',
  'auto_number',
  'phone',
  'email',
  'url',
  'rich_text',
  'encrypted',
  'external_id',
  'geolocation',
  'lookup',
  'master_detail',
  'formula',
  'rollup_summary',
]

const COMPUTED: FieldType[] = ['auto_number', 'formula', 'rollup_summary', 'encrypted']

const ctx = (over: Partial<FieldControlContext> = {}): FieldControlContext => ({
  fieldName: 'f',
  displayName: 'Field',
  ...over,
})

afterEach(() => resetFieldControls())

describe('registry completeness', () => {
  it('registers a control for every field type', () => {
    for (const type of ALL_TYPES) {
      expect(getFieldControl(type)).toBeDefined()
      expect(CONTROLS[type]).toBeDefined()
    }
  })

  it('falls back to the string control for unknown types', () => {
    expect(getFieldControl('totally_unknown')).toBe(CONTROLS.string)
  })

  it('marks server-computed types non-editable, others editable', () => {
    for (const type of ALL_TYPES) {
      const expected = !COMPUTED.includes(type)
      expect(getFieldControl(type).editable).toBe(expected)
    }
  })
})

describe('coerce', () => {
  it('number: string → number, empty → null, NaN → raw', () => {
    const c = getFieldControl('number').coerce
    expect(c('10')).toBe(10)
    expect(c('')).toBeNull()
    expect(c('abc')).toBe('abc')
  })

  it('boolean: truthy → true, falsy → false', () => {
    const c = getFieldControl('boolean').coerce
    expect(c(true)).toBe(true)
    expect(c('')).toBe(false)
  })

  it('multi_picklist: normalizes to an array', () => {
    const c = getFieldControl('multi_picklist').coerce
    expect(Array.isArray(c(['a', 'b']))).toBe(true)
  })

  it('json: parses a valid string, keeps invalid text', () => {
    const c = getFieldControl('json').coerce
    expect(c('{"a":1}')).toEqual({ a: 1 })
    expect(c('{bad')).toBe('{bad')
    expect(c('')).toBeNull()
  })

  it('computed types coerce to undefined (omit from payload)', () => {
    for (const type of COMPUTED) {
      expect(getFieldControl(type).coerce('anything')).toBeUndefined()
    }
  })

  it('string: empty → null', () => {
    expect(getFieldControl('string').coerce('')).toBeNull()
    expect(getFieldControl('string').coerce('x')).toBe('x')
  })
})

describe('validate', () => {
  it('required blocks empty values', () => {
    expect(getFieldControl('string').validate('', ctx({ required: true }))).toMatch(/required/)
    expect(getFieldControl('string').validate('x', ctx({ required: true }))).toBeNull()
    expect(getFieldControl('multi_picklist').validate([], ctx({ required: true }))).toMatch(
      /required/
    )
  })

  it('number rejects non-numeric', () => {
    expect(getFieldControl('number').validate('abc', ctx())).toMatch(/number/)
    expect(getFieldControl('number').validate(5, ctx())).toBeNull()
  })

  it('picklist enforces enum membership', () => {
    const v = getFieldControl('picklist').validate
    expect(v('Blue', ctx({ enumValues: ['Red', 'Green'] }))).toMatch(/allowed/)
    expect(v('Red', ctx({ enumValues: ['Red', 'Green'] }))).toBeNull()
  })

  it('multi_picklist enforces enum membership per value', () => {
    const v = getFieldControl('multi_picklist').validate
    expect(v(['Red', 'Nope'], ctx({ enumValues: ['Red', 'Green'] }))).toMatch(/allowed/)
    expect(v(['Red', 'Green'], ctx({ enumValues: ['Red', 'Green'] }))).toBeNull()
  })

  it('json rejects invalid JSON strings', () => {
    expect(getFieldControl('json').validate('{bad', ctx())).toMatch(/Invalid JSON/)
    expect(getFieldControl('json').validate('{"a":1}', ctx())).toBeNull()
  })

  it('geolocation enforces lat/lng ranges', () => {
    const v = getFieldControl('geolocation').validate
    expect(v({ latitude: 200, longitude: 0 }, ctx())).toMatch(/Latitude/)
    expect(v({ latitude: 0, longitude: 999 }, ctx())).toMatch(/Longitude/)
    expect(v({ latitude: 40, longitude: -70 }, ctx())).toBeNull()
  })
})

function renderView(type: FieldType, value: unknown, c = ctx()) {
  return render(
    <MemoryRouter>
      {React.createElement(getFieldControl(type).View, { type, value, ctx: c })}
    </MemoryRouter>
  )
}

describe('View (parity via FieldRenderer)', () => {
  it('renders string value', () => {
    renderView('string', 'Hello')
    expect(screen.getByText('Hello')).toBeDefined()
  })

  it('renders empty for null', () => {
    renderView('string', null)
    expect(screen.getByText('—')).toBeDefined()
  })

  it('renders a reference link with tenant + target', () => {
    renderView(
      'reference',
      'rec-1',
      ctx({ tenantSlug: 't', targetCollection: 'accounts', displayLabel: 'Acme' })
    )
    const link = screen.getByText('Acme').closest('a')
    expect(link?.getAttribute('href')).toBe('/t/app/o/accounts/rec-1')
  })

  it('passes picklistDisplayMap through to FieldRenderer for the picklist label/color', () => {
    renderView(
      'picklist',
      'in_progress',
      ctx({
        picklistDisplayMap: new Map([['in_progress', { label: 'In progress', color: '#F59E0B' }]]),
      })
    )
    expect(screen.getByText('In progress')).toBeDefined()
    expect(screen.queryByText('in_progress')).toBeNull()
  })
})

describe('Edit', () => {
  it('TextEdit fires onChange with the raw string', () => {
    const onChange = vi.fn()
    render(
      React.createElement(getFieldControl('string').Edit, {
        type: 'string',
        value: '',
        ctx: ctx(),
        onChange,
      })
    )
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'hi' } })
    expect(onChange).toHaveBeenCalledWith('hi')
  })

  it('BooleanEdit fires onChange with a boolean', () => {
    const onChange = vi.fn()
    render(
      React.createElement(getFieldControl('boolean').Edit, {
        type: 'boolean',
        value: false,
        ctx: ctx(),
        onChange,
      })
    )
    fireEvent.click(screen.getByRole('checkbox'))
    expect(onChange).toHaveBeenCalledWith(true)
  })

  it('PicklistEdit renders enum options and fires the chosen value', () => {
    const onChange = vi.fn()
    render(
      React.createElement(getFieldControl('picklist').Edit, {
        type: 'picklist',
        value: '',
        ctx: ctx({ enumValues: ['Red', 'Green'] }),
        onChange,
      })
    )
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'Green' } })
    expect(onChange).toHaveBeenCalledWith('Green')
  })

  it('PicklistEdit shows the authored label but still fires the raw stored value', () => {
    const onChange = vi.fn()
    render(
      React.createElement(getFieldControl('picklist').Edit, {
        type: 'picklist',
        value: '',
        ctx: ctx({
          enumValues: ['in_progress', 'done'],
          picklistDisplayMap: new Map([['in_progress', { label: 'In progress' }]]),
        }),
        onChange,
      })
    )
    const select = screen.getByRole('combobox') as HTMLSelectElement
    expect(screen.getByText('In progress')).toBeDefined()
    // No display-map entry for 'done' — falls back to the raw value.
    expect(screen.getByText('done')).toBeDefined()
    fireEvent.change(select, { target: { value: 'in_progress' } })
    expect(onChange).toHaveBeenCalledWith('in_progress')
  })

  it('respects readOnly by disabling the input', () => {
    render(
      React.createElement(getFieldControl('string').Edit, {
        type: 'string',
        value: 'x',
        ctx: ctx({ readOnly: true }),
        onChange: vi.fn(),
      })
    )
    expect((screen.getByRole('textbox') as HTMLInputElement).disabled).toBe(true)
  })
})

describe('Edit — record-form contract (FieldEditProps id/testId/aria/placeholder)', () => {
  const edit = (type: FieldType, props: Record<string, unknown> = {}) =>
    render(
      React.createElement(getFieldControl(type).Edit, {
        type,
        value: '',
        ctx: ctx(),
        onChange: vi.fn(),
        ...props,
      })
    )

  it('puts id, data-testid and the error a11y attributes on a simple input', () => {
    edit('string', {
      id: 'field-f',
      testId: 'field-f',
      placeholder: 'Type here',
      'aria-invalid': true,
      'aria-describedby': 'field-f-error',
      ctx: ctx({ required: true }),
    })
    const input = screen.getByTestId('field-f')
    expect(input).toHaveAttribute('id', 'field-f')
    expect(input).toHaveAttribute('placeholder', 'Type here')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'field-f-error')
    expect(input).toHaveAttribute('aria-required', 'true')
  })

  it('omits the a11y attributes when not supplied (inline consumers unchanged)', () => {
    edit('string')
    const input = screen.getByRole('textbox')
    expect(input).not.toHaveAttribute('aria-describedby')
    expect(input).not.toHaveAttribute('aria-required')
    expect(input).not.toHaveAttribute('data-testid')
  })

  it('NumberEdit emits a number, "" when cleared, and steps currency in hundredths', () => {
    const onChange = vi.fn()
    // Controlled with a fixed value: each change event is measured against `5`.
    edit('currency', { onChange, testId: 'n', value: 5 })
    const input = screen.getByTestId('n')
    expect(input).toHaveAttribute('step', '0.01')
    fireEvent.change(input, { target: { value: '9.99' } })
    expect(onChange).toHaveBeenLastCalledWith(9.99)
    fireEvent.change(input, { target: { value: '' } })
    expect(onChange).toHaveBeenLastCalledWith('')
  })

  it('PicklistEdit labels its empty option with the placeholder', () => {
    edit('picklist', { placeholder: 'Select...', ctx: ctx({ enumValues: ['a'] }) })
    expect(screen.getByRole('option', { name: 'Select...' })).toHaveValue('')
  })

  it('MultiPicklistEdit carries testId on its root and the error a11y on its trigger', () => {
    edit('multi_picklist', {
      id: 'field-f',
      testId: 'field-f',
      value: [],
      'aria-invalid': true,
      'aria-describedby': 'field-f-error',
      ctx: ctx({ enumValues: ['a'] }),
    })
    expect(screen.getByTestId('field-f')).toBeInTheDocument()
    const trigger = document.getElementById('field-f')!
    expect(trigger).toHaveAttribute('aria-describedby', 'field-f-error')
  })

  it('ReferenceEdit keeps its default field-<name>-lookup test id unless testId is given', () => {
    const { unmount } = edit('lookup', { ctx: ctx({ referenceOptions: [] }) })
    expect(screen.getByTestId('field-f-lookup')).toBeInTheDocument()
    unmount()
    edit('lookup', { testId: 'field-f', ctx: ctx({ referenceOptions: [] }) })
    expect(screen.getByTestId('field-f')).toBeInTheDocument()
    expect(screen.queryByTestId('field-f-lookup')).toBeNull()
  })

  it('EncryptedEdit is write-only: never shows the passed value, emits what is typed', () => {
    const onChange = vi.fn()
    edit('encrypted', { value: 'stored-secret', onChange, testId: 'secret' })
    const input = screen.getByTestId('secret') as HTMLInputElement
    expect(input.type).toBe('password')
    expect(input.value).toBe('')
    fireEvent.change(input, { target: { value: 'typed' } })
    expect(onChange).toHaveBeenCalledWith('typed')
  })

  it('EncryptedEdit honours readOnly', () => {
    edit('encrypted', { testId: 'secret', ctx: ctx({ readOnly: true }) })
    expect(screen.getByTestId('secret')).toBeDisabled()
  })

  it('JsonEdit defaults its placeholder to {}', () => {
    edit('json', { testId: 'j' })
    expect(screen.getByTestId('j')).toHaveAttribute('placeholder', '{}')
  })
})

describe('InlineEdit', () => {
  it('commits the coerced value on Enter', () => {
    const onCommit = vi.fn()
    render(
      React.createElement(getFieldControl('number').InlineEdit, {
        type: 'number',
        value: 1,
        ctx: ctx(),
        onChange: vi.fn(),
        onCommit,
        onCancel: vi.fn(),
      })
    )
    const input = screen.getByRole('spinbutton')
    fireEvent.change(input, { target: { value: '42' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onCommit).toHaveBeenCalledWith(42)
  })

  it('cancels on Escape without committing', () => {
    const onCommit = vi.fn()
    const onCancel = vi.fn()
    render(
      React.createElement(getFieldControl('string').InlineEdit, {
        type: 'string',
        value: 'a',
        ctx: ctx(),
        onChange: vi.fn(),
        onCommit,
        onCancel,
      })
    )
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' })
    expect(onCancel).toHaveBeenCalled()
    expect(onCommit).not.toHaveBeenCalled()
  })
})

describe('registerFieldControl override', () => {
  it('overrides a member and resets cleanly', () => {
    const custom = () => null
    registerFieldControl('string', { validate: () => 'nope' })
    expect(getFieldControl('string').validate('x', ctx())).toBe('nope')
    registerFieldControl('brand_new', { editable: false, validate: custom })
    expect(getFieldControl('brand_new').editable).toBe(false)
    resetFieldControls()
    expect(getFieldControl('string').validate('x', ctx())).toBeNull()
  })
})
