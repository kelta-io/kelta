/**
 * `FormFieldRow` — the record form's per-field row, rendering the `fieldControl` registry's edit
 * control (or a plugin renderer). Covers the DOM contract the unit + e2e suites rely on
 * (`id`/`data-testid="field-<name>"`, the linked `field-error-<name>` alert), blur reporting,
 * read-only handling, the picklist label display (K-8), plugin override + error boundary, and the
 * encrypted write-only input. Ported from the removed page-local `FormField` tests (P4).
 */
import { describe, it, expect, vi } from 'vitest'
import { render as rtlRender, screen, fireEvent } from '@testing-library/react'
import type React from 'react'
import { I18nProvider } from '@/context/I18nContext'
import { FormFieldRow } from './FormFieldRow'
import { buildSaveAttributes } from './formData'
import type { FieldDefinition, FieldType } from '@/hooks/useCollectionSchema'
import type { FieldRendererProps } from '@/types/plugin'

function render(ui: React.ReactElement) {
  return rtlRender(<I18nProvider>{ui}</I18nProvider>)
}

function makeField(type: FieldType, over: Partial<FieldDefinition> = {}): FieldDefinition {
  return { id: `f-${type}`, name: 'f', displayName: 'Field', type, required: false, ...over }
}

const statusField: FieldDefinition = {
  id: 'f-status',
  name: 'status',
  displayName: 'Status',
  type: 'picklist',
  required: false,
  enumValues: ['in_progress', 'done'],
  enumOptions: [
    { value: 'in_progress', label: 'In progress', color: '#F59E0B' },
    { value: 'done', label: 'Done' },
  ],
}

const nameField: FieldDefinition = {
  id: 'f-name',
  name: 'name',
  displayName: 'Name',
  type: 'string',
  required: true,
}

describe('FormFieldRow picklist select', () => {
  it('displays the authored label for each option', () => {
    render(<FormFieldRow field={statusField} value="" onChange={vi.fn()} />)
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.getByText('Done')).toBeInTheDocument()
    expect(screen.queryByText('in_progress')).toBeNull()
  })

  it('falls back to the raw value when a field has no enumOptions', () => {
    const field: FieldDefinition = { ...statusField, enumOptions: undefined }
    render(<FormFieldRow field={field} value="" onChange={vi.fn()} />)
    expect(screen.getByText('in_progress')).toBeInTheDocument()
  })

  it('shows the translated "Select..." empty option', () => {
    render(<FormFieldRow field={statusField} value="" onChange={vi.fn()} />)
    expect(screen.getByRole('option', { name: 'Select...' })).toHaveValue('')
  })

  it('fires onChange with the raw stored value when the labeled option is selected', () => {
    const onChange = vi.fn()
    render(<FormFieldRow field={statusField} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('field-status'), { target: { value: 'in_progress' } })
    expect(onChange).toHaveBeenCalledWith('status', 'in_progress')
  })

  it('offers only the (record-type narrowed) enumValues the model supplies', () => {
    const field: FieldDefinition = {
      ...statusField,
      enumValues: ['done'],
      enumOptions: [{ value: 'done', label: 'Done' }],
    }
    render(<FormFieldRow field={field} value="" onChange={vi.fn()} />)
    expect(screen.queryByText('In progress')).toBeNull()
    expect(screen.getByText('Done')).toBeInTheDocument()
  })

  it('the PATCH body (buildSaveAttributes) carries the raw value, not the label', () => {
    const formData = { status: 'in_progress' }
    const { attributes } = buildSaveAttributes([statusField], formData)
    expect(attributes.status).toBe('in_progress')
    expect(attributes.status).not.toBe('In progress')
  })
})

describe('FormFieldRow DOM contract, error a11y and blur', () => {
  it('renders the label (with a required marker) pointing at the control', () => {
    render(<FormFieldRow field={nameField} value="" onChange={vi.fn()} />)
    const input = screen.getByTestId('field-name')
    expect(input).toHaveAttribute('id', 'field-name')
    expect(screen.getByLabelText(/Name/)).toBe(input)
    expect(screen.getByText('*')).toBeInTheDocument()
  })

  it('links the control to its error alert with aria-invalid + aria-describedby', () => {
    render(<FormFieldRow field={nameField} value="" onChange={vi.fn()} error="Required" />)
    const input = screen.getByTestId('field-name')
    const alert = screen.getByTestId('field-error-name')
    expect(alert).toHaveAttribute('role', 'alert')
    expect(alert).toHaveTextContent('Required')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', alert.id)
    expect(alert.id).toBe('field-name-error')
  })

  it('marks the control valid and undescribed without an error', () => {
    render(<FormFieldRow field={nameField} value="Ada" onChange={vi.fn()} />)
    const input = screen.getByTestId('field-name')
    expect(input).toHaveAttribute('aria-invalid', 'false')
    expect(input).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByTestId('field-error-name')).toBeNull()
  })

  it('links a picklist select to its error too', () => {
    render(<FormFieldRow field={statusField} value="" onChange={vi.fn()} error="Required" />)
    expect(screen.getByTestId('field-status')).toHaveAttribute(
      'aria-describedby',
      'field-status-error'
    )
  })

  it('reports the field name on blur', () => {
    const onBlur = vi.fn()
    render(<FormFieldRow field={nameField} value="" onChange={vi.fn()} onBlur={onBlur} />)
    fireEvent.blur(screen.getByTestId('field-name'))
    expect(onBlur).toHaveBeenCalledWith('name')
  })

  it('does not report a blur while focus moves inside a composite control', () => {
    const onBlur = vi.fn()
    const geo = makeField('geolocation', { name: 'loc' })
    render(<FormFieldRow field={geo} value={null} onChange={vi.fn()} onBlur={onBlur} />)
    const lat = screen.getByTestId('field-loc')
    const lng = screen.getByTestId('field-loc-longitude')
    fireEvent.blur(lat, { relatedTarget: lng })
    expect(onBlur).not.toHaveBeenCalled()
    fireEvent.blur(lng, { relatedTarget: null })
    expect(onBlur).toHaveBeenCalledWith('loc')
  })

  it('renders help text when supplied', () => {
    render(
      <FormFieldRow field={nameField} value="" onChange={vi.fn()} helpText="Legal name only" />
    )
    expect(screen.getByTestId('field-help-name')).toHaveTextContent('Legal name only')
  })

  it('reports the edited value with the field name', () => {
    const onChange = vi.fn()
    render(<FormFieldRow field={nameField} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Ada' } })
    expect(onChange).toHaveBeenCalledWith('name', 'Ada')
  })
})

describe('FormFieldRow per-type controls (fieldControl registry)', () => {
  it.each([
    ['email', 'email'],
    ['phone', 'tel'],
    ['url', 'url'],
    ['date', 'date'],
    ['datetime', 'datetime-local'],
    ['number', 'number'],
  ] as const)('%s renders an <input type="%s"> with the field id', (type, htmlType) => {
    render(<FormFieldRow field={makeField(type)} value="" onChange={vi.fn()} />)
    const input = screen.getByTestId('field-f')
    expect(input).toHaveAttribute('type', htmlType)
    expect(input).toHaveAttribute('id', 'field-f')
  })

  it.each(['number', 'currency', 'percent'] as const)(
    '%s emits a number on change, and "" when cleared',
    (type) => {
      const onChange = vi.fn()
      render(<FormFieldRow field={makeField(type)} value={1} onChange={onChange} />)
      const input = screen.getByTestId('field-f')
      fireEvent.change(input, { target: { value: '42.5' } })
      expect(onChange).toHaveBeenLastCalledWith('f', 42.5)
      fireEvent.change(input, { target: { value: '' } })
      expect(onChange).toHaveBeenLastCalledWith('f', '')
    }
  )

  it('currency and percent step in hundredths', () => {
    render(<FormFieldRow field={makeField('currency')} value="" onChange={vi.fn()} />)
    expect(screen.getByTestId('field-f')).toHaveAttribute('step', '0.01')
  })

  it('boolean renders a checkbox with the field id and emits a boolean', () => {
    const onChange = vi.fn()
    render(<FormFieldRow field={makeField('boolean')} value={false} onChange={onChange} />)
    const box = screen.getByRole('checkbox', { name: 'Field' })
    expect(box).toHaveAttribute('id', 'field-f')
    fireEvent.click(box)
    expect(onChange).toHaveBeenCalledWith('f', true)
  })

  it('json edits pretty-printed text in a textarea', () => {
    const onChange = vi.fn()
    render(<FormFieldRow field={makeField('json')} value={{ a: 1 }} onChange={onChange} />)
    const area = screen.getByTestId('field-f')
    expect(area.tagName).toBe('TEXTAREA')
    expect(area).toHaveValue('{\n  "a": 1\n}')
    fireEvent.change(area, { target: { value: '{"b":2}' } })
    expect(onChange).toHaveBeenCalledWith('f', '{"b":2}')
  })

  it('multi_picklist puts the field id on its trigger and emits an array', () => {
    const onChange = vi.fn()
    const field = makeField('multi_picklist', { enumValues: ['a', 'b'] })
    render(<FormFieldRow field={field} value={['a']} onChange={onChange} error="Bad" />)
    expect(screen.getByTestId('field-f')).toBeInTheDocument()
    const trigger = document.getElementById('field-f')!
    expect(trigger).toHaveAttribute('aria-describedby', 'field-f-error')
    fireEvent.click(trigger)
    fireEvent.click(screen.getByRole('option', { name: /b/ }))
    expect(onChange).toHaveBeenCalledWith('f', ['a', 'b'])
  })

  it.each(['lookup', 'master_detail', 'reference'] as const)(
    '%s renders a LookupSelect combobox trigger with the field id and lookup options',
    (type) => {
      const onChange = vi.fn()
      const field = makeField(type, {
        lookupOptions: [
          { id: 'r1', label: 'Acme' },
          { id: 'r2', label: 'Globex' },
        ],
      })
      render(<FormFieldRow field={field} value="r1" onChange={onChange} error="Pick one" />)
      const trigger = document.querySelector('[id="field-f"][role="combobox"]')!
      expect(trigger).toBeTruthy()
      expect(trigger).toHaveTextContent('Acme')
      expect(trigger).toHaveAttribute('aria-describedby', 'field-f-error')
      expect(trigger).toHaveAttribute('aria-invalid', 'true')
      fireEvent.click(trigger)
      fireEvent.click(screen.getByText('Globex'))
      expect(onChange).toHaveBeenCalledWith('f', 'r2')
    }
  )

  it('geolocation edits latitude/longitude and emits a structured value', () => {
    const onChange = vi.fn()
    render(
      <FormFieldRow
        field={makeField('geolocation')}
        value={{ latitude: 1, longitude: 2 }}
        onChange={onChange}
      />
    )
    const lat = screen.getByTestId('field-f')
    expect(lat).toHaveValue(1)
    expect(screen.getByTestId('field-f-longitude')).toHaveValue(2)
    fireEvent.change(lat, { target: { value: '45' } })
    expect(onChange).toHaveBeenCalledWith('f', { latitude: 45, longitude: 2 })
  })

  it('rich_text renders the registry RichTextEditor with the field id on its editable content', async () => {
    render(<FormFieldRow field={makeField('rich_text')} value="<p>Hello</p>" onChange={vi.fn()} />)
    expect(await screen.findByTestId('field-f')).toBeInTheDocument()
    const content = screen.getByTestId('field-f-content')
    expect(content).toHaveAttribute('id', 'field-f')
    expect(content).toHaveAttribute('contenteditable', 'true')
    expect(content).toHaveTextContent('Hello')
  })
})

describe('FormFieldRow read-only', () => {
  it('disables a read-only field', () => {
    render(<FormFieldRow field={nameField} value="Ada" onChange={vi.fn()} readOnly />)
    expect(screen.getByTestId('field-name')).toBeDisabled()
  })

  it('disables a read-only picklist and lookup', () => {
    const lookup = makeField('lookup', { name: 'acct', lookupOptions: [] })
    render(
      <>
        <FormFieldRow field={statusField} value="" onChange={vi.fn()} readOnly />
        <FormFieldRow field={lookup} value="" onChange={vi.fn()} readOnly />
      </>
    )
    expect(screen.getByTestId('field-status')).toBeDisabled()
    expect(document.getElementById('field-acct')).toBeDisabled()
  })

  it('renders read-only rich text as sanitized display, not an editor', () => {
    render(
      <FormFieldRow
        field={makeField('rich_text')}
        value="<b>Bold</b>"
        onChange={vi.fn()}
        readOnly
      />
    )
    expect(screen.queryByTestId('field-f-content')).toBeNull()
    expect(screen.getByText('Bold')).toBeInTheDocument()
  })
})

describe('FormFieldRow encrypted (write-only)', () => {
  const secret = makeField('encrypted', { name: 'api_key', displayName: 'API key' })

  it('never shows the stored value — the password input starts empty', () => {
    render(<FormFieldRow field={secret} value="stored-secret" onChange={vi.fn()} />)
    const input = screen.getByTestId('field-api_key')
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveAttribute('autocomplete', 'new-password')
    expect(input).toHaveValue('')
    expect(input).toHaveAttribute('placeholder', expect.stringMatching(/never shown/))
  })

  it('emits what the user types', () => {
    const onChange = vi.fn()
    render(<FormFieldRow field={secret} value={undefined} onChange={onChange} />)
    fireEvent.change(screen.getByTestId('field-api_key'), { target: { value: 'new-secret' } })
    expect(onChange).toHaveBeenCalledWith('api_key', 'new-secret')
  })

  it('honours readOnly (disabled)', () => {
    render(<FormFieldRow field={secret} value={undefined} onChange={vi.fn()} readOnly />)
    expect(screen.getByTestId('field-api_key')).toBeDisabled()
  })
})

describe('FormFieldRow plugin field renderer', () => {
  function Plugin({ name, onChange, readOnly, metadata }: FieldRendererProps) {
    return (
      <input
        data-testid={`plugin-${name}`}
        readOnly={readOnly}
        data-error-id={String(metadata?.errorId ?? '')}
        onChange={(e) => onChange(e.target.value)}
      />
    )
  }

  it('renders the plugin renderer instead of the registry control', () => {
    const onChange = vi.fn()
    render(
      <FormFieldRow
        field={nameField}
        value=""
        onChange={onChange}
        error="Bad"
        readOnly
        pluginRenderer={Plugin}
      />
    )
    const input = screen.getByTestId('plugin-name')
    expect(screen.queryByTestId('field-name')).toBeNull()
    expect(input).toHaveAttribute('readonly')
    expect(input).toHaveAttribute('data-error-id', 'field-name-error')
    expect(screen.getByTestId('field-error-name')).toHaveTextContent('Bad')
    fireEvent.change(input, { target: { value: 'x' } })
    expect(onChange).toHaveBeenCalledWith('name', 'x')
  })

  it('contains a crashing plugin renderer in the compact error boundary', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    function Broken(): React.ReactElement {
      throw new Error('boom')
    }
    render(<FormFieldRow field={nameField} value="" onChange={vi.fn()} pluginRenderer={Broken} />)
    expect(screen.getByTestId('plugin-error-compact')).toBeInTheDocument()
    // The row itself (label) still renders.
    expect(screen.getByText('Name')).toBeInTheDocument()
    consoleError.mockRestore()
  })
})
