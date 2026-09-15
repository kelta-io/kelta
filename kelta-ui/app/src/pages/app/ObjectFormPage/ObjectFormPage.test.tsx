/**
 * `FormField` picklist tests (K-8 picklist display). The record form's picklist `<select>`
 * must show the authored label while the value attribute — and thus the PATCH payload built by
 * `buildSaveAttributes` — stays the raw stored value. Renaming values isn't an option (flows/SQL/
 * filters compare against the stored value), so this is presentation-only.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { FormField } from './ObjectFormPage'
import { buildSaveAttributes } from './formData'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

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
