/**
 * `FormField` picklist tests (K-8 picklist display). The record form's picklist `<select>`
 * must show the authored label while the value attribute — and thus the PATCH payload built by
 * `buildSaveAttributes` — stays the raw stored value. Renaming values isn't an option (flows/SQL/
 * filters compare against the stored value), so this is presentation-only.
 */
import { describe, it, expect, vi } from 'vitest'
import { render as rtlRender, screen, fireEvent } from '@testing-library/react'
import type React from 'react'
import { I18nProvider } from '@/context/I18nContext'
import { FormField } from './FormField'
import { buildSaveAttributes } from './formData'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

function render(ui: React.ReactElement) {
  return rtlRender(<I18nProvider>{ui}</I18nProvider>)
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

describe('FormField picklist select', () => {
  it('displays the authored label for each option', () => {
    render(<FormField field={statusField} value="" onChange={vi.fn()} />)
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.getByText('Done')).toBeInTheDocument()
    expect(screen.queryByText('in_progress')).toBeNull()
  })

  it('falls back to the raw value when a field has no enumOptions', () => {
    const field: FieldDefinition = { ...statusField, enumOptions: undefined }
    render(<FormField field={field} value="" onChange={vi.fn()} />)
    expect(screen.getByText('in_progress')).toBeInTheDocument()
  })

  it('fires onChange with the raw stored value when the labeled option is selected', () => {
    const onChange = vi.fn()
    render(<FormField field={statusField} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('field-status'), { target: { value: 'in_progress' } })
    expect(onChange).toHaveBeenCalledWith('status', 'in_progress')
  })

  it('the PATCH body (buildSaveAttributes) carries the raw value, not the label', () => {
    const formData = { status: 'in_progress' }
    const { attributes } = buildSaveAttributes([statusField], formData)
    expect(attributes.status).toBe('in_progress')
    expect(attributes.status).not.toBe('In progress')
  })
})

describe('FormField error a11y and blur', () => {
  const nameField: FieldDefinition = {
    id: 'f-name',
    name: 'name',
    displayName: 'Name',
    type: 'string',
    required: true,
  }

  it('links the control to its error alert with aria-invalid + aria-describedby', () => {
    render(<FormField field={nameField} value="" onChange={vi.fn()} error="Required" />)
    const input = screen.getByTestId('field-name')
    const alert = screen.getByTestId('field-error-name')
    expect(alert).toHaveAttribute('role', 'alert')
    expect(alert).toHaveTextContent('Required')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', alert.id)
    expect(alert.id).toBe('field-name-error')
  })

  it('marks the control valid and undescribed without an error', () => {
    render(<FormField field={nameField} value="Ada" onChange={vi.fn()} />)
    const input = screen.getByTestId('field-name')
    expect(input).toHaveAttribute('aria-invalid', 'false')
    expect(input).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByTestId('field-error-name')).toBeNull()
  })

  it('links a picklist select to its error too', () => {
    render(<FormField field={statusField} value="" onChange={vi.fn()} error="Required" />)
    expect(screen.getByTestId('field-status')).toHaveAttribute(
      'aria-describedby',
      'field-status-error'
    )
  })

  it('reports the field name on blur', () => {
    const onBlur = vi.fn()
    render(<FormField field={nameField} value="" onChange={vi.fn()} onBlur={onBlur} />)
    fireEvent.blur(screen.getByTestId('field-name'))
    expect(onBlur).toHaveBeenCalledWith('name')
  })
})
