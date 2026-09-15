import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { InlineFieldValue } from './InlineFieldValue'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

function field(over: Partial<FieldDefinition> = {}): FieldDefinition {
  return { id: 'f1', name: 'name', displayName: 'Name', type: 'string', required: false, ...over }
}

function renderInline(props: React.ComponentProps<typeof InlineFieldValue>) {
  return render(
    <MemoryRouter>
      <InlineFieldValue {...props} />
    </MemoryRouter>
  )
}

describe('InlineFieldValue', () => {
  it('renders read-only view when not editable', () => {
    renderInline({ field: field(), value: 'Acme', editable: false })
    expect(screen.getByText('Acme')).toBeDefined()
    expect(screen.queryByTestId('inline-field-name')).toBeNull()
  })

  it('renders read-only view when no onCommit is supplied', () => {
    renderInline({ field: field(), value: 'Acme', editable: true })
    expect(screen.queryByTestId('inline-field-name')).toBeNull()
  })

  it('becomes click-to-edit when editable + onCommit', () => {
    renderInline({ field: field(), value: 'Acme', editable: true, onCommit: vi.fn() })
    expect(screen.getByTestId('inline-field-name')).toBeDefined()
  })

  it('commits a coerced value on Enter and exits edit mode', async () => {
    const onCommit = vi.fn().mockResolvedValue(undefined)
    renderInline({
      field: field({ name: 'amount', type: 'number' }),
      value: 1,
      editable: true,
      onCommit,
    })
    fireEvent.click(screen.getByTestId('inline-field-amount'))
    const input = screen.getByRole('spinbutton')
    fireEvent.change(input, { target: { value: '42' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(onCommit).toHaveBeenCalledWith('amount', 42))
    await waitFor(() => expect(screen.queryByRole('spinbutton')).toBeNull())
  })

  it('blocks commit and shows a validation error for a required-empty value', async () => {
    const onCommit = vi.fn().mockResolvedValue(undefined)
    renderInline({
      field: field({ name: 'name', required: true }),
      value: 'x',
      editable: true,
      required: true,
      onCommit,
    })
    fireEvent.click(screen.getByTestId('inline-field-name'))
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(screen.getByTestId('inline-field-error-name')).toBeDefined())
    expect(onCommit).not.toHaveBeenCalled()
  })

  it('cancels on Escape without committing', () => {
    const onCommit = vi.fn()
    renderInline({ field: field(), value: 'Acme', editable: true, onCommit })
    fireEvent.click(screen.getByTestId('inline-field-name'))
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' })
    expect(onCommit).not.toHaveBeenCalled()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('surfaces a rejected commit as an inline error and stays in edit mode', async () => {
    const onCommit = vi.fn().mockRejectedValue(new Error('Conflict'))
    renderInline({ field: field({ name: 'name' }), value: 'a', editable: true, onCommit })
    fireEvent.click(screen.getByTestId('inline-field-name'))
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'b' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() =>
      expect(screen.getByTestId('inline-field-error-name')).toHaveTextContent('Conflict')
    )
    expect(screen.getByRole('textbox')).toBeDefined()
  })

  it('stays view-only for a placement read-only override', () => {
    renderInline({
      field: field(),
      value: 'Acme',
      editable: true,
      readOnly: true,
      onCommit: vi.fn(),
    })
    expect(screen.queryByTestId('inline-field-name')).toBeNull()
  })

  it('stays view-only for server-computed types', () => {
    renderInline({
      field: field({ name: 'seq', type: 'auto_number' }),
      value: 'A-1',
      editable: true,
      onCommit: vi.fn(),
    })
    expect(screen.queryByTestId('inline-field-seq')).toBeNull()
  })

  it('stays view-only for a picklist with no loaded options', () => {
    renderInline({
      field: field({ name: 'stage', type: 'picklist' }),
      value: 'New',
      editable: true,
      onCommit: vi.fn(),
    })
    expect(screen.queryByTestId('inline-field-stage')).toBeNull()
  })

  it('editOn=pencil: value is not a button, but the pencil enters edit mode', () => {
    const onCommit = vi.fn().mockResolvedValue(undefined)
    renderInline({ field: field(), value: 'Acme', editable: true, editOn: 'pencil', onCommit })
    // The value text is present but not wrapped in the edit button.
    expect(screen.getByText('Acme')).toBeDefined()
    // The pencil (carrying the testid) triggers edit.
    fireEvent.click(screen.getByTestId('inline-field-name'))
    expect(screen.getByRole('textbox')).toBeDefined()
  })

  it('is editable for a picklist once options are loaded', () => {
    renderInline({
      field: field({ name: 'stage', type: 'picklist', enumValues: ['New', 'Won'] }),
      value: 'New',
      editable: true,
      onCommit: vi.fn(),
    })
    expect(screen.getByTestId('inline-field-stage')).toBeDefined()
  })

  it('renders the authored label from picklistDisplayMap in the read view', () => {
    renderInline({
      field: field({ name: 'stage', type: 'picklist' }),
      value: 'in_progress',
      editable: false,
      picklistDisplayMap: new Map([['in_progress', { label: 'In progress', color: '#F59E0B' }]]),
    })
    expect(screen.getByText('In progress')).toBeDefined()
    expect(screen.queryByText('in_progress')).toBeNull()
  })

  it('renders a locked read-only view when masked, even with edit permission', () => {
    renderInline({
      field: field({ name: 'ssn' }),
      value: '***-**-6789',
      editable: true,
      masked: true,
      onCommit: vi.fn(),
    })
    // No edit affordance; a masked-field marker is shown instead.
    expect(screen.queryByTestId('inline-field-ssn')).toBeNull()
    expect(screen.getByTestId('masked-field-ssn')).toBeDefined()
    expect(screen.getByText('***-**-6789')).toBeDefined()
  })
})
