import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ListViewToolbar } from './ListViewToolbar'

describe('ListViewToolbar', () => {
  const defaultProps = {
    collectionLabel: 'Accounts',
    selectedCount: 0,
    totalCount: 142,
    onNew: vi.fn(),
    onBulkDelete: vi.fn(),
    onExportCsv: vi.fn(),
    onExportJson: vi.fn(),
    onClearSelection: vi.fn(),
  }

  it('renders collection label with count', () => {
    render(<ListViewToolbar {...defaultProps} />)
    expect(screen.getByText('Accounts')).toBeDefined()
    expect(screen.getByText('(142)')).toBeDefined()
  })

  it('renders New button', () => {
    render(<ListViewToolbar {...defaultProps} />)
    expect(screen.getByLabelText('New Accounts')).toBeDefined()
  })

  it('calls onNew when New button is clicked', () => {
    const onNew = vi.fn()
    render(<ListViewToolbar {...defaultProps} onNew={onNew} />)
    fireEvent.click(screen.getByLabelText('New Accounts'))
    expect(onNew).toHaveBeenCalledOnce()
  })

  it('does not show bulk selection bar when no rows selected', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={0} />)
    expect(screen.queryByText(/records? selected/)).toBeNull()
  })

  it('shows bulk selection bar when rows are selected', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={5} />)
    expect(screen.getByText('5 records selected')).toBeDefined()
  })

  it('shows singular "record" when 1 selected', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={1} />)
    expect(screen.getByText('1 record selected')).toBeDefined()
  })

  it('shows Clear selection button in bulk bar', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={3} />)
    const clearBtn = screen.getByText('Clear selection')
    expect(clearBtn).toBeDefined()
    fireEvent.click(clearBtn)
    expect(defaultProps.onClearSelection).toHaveBeenCalledOnce()
  })

  it('does not show count when total is 0', () => {
    render(<ListViewToolbar {...defaultProps} totalCount={0} />)
    expect(screen.queryByText('(0)')).toBeNull()
  })

  it('shows the mass edit button in the bulk bar only when onMassEdit is provided', () => {
    const onMassEdit = vi.fn()
    render(<ListViewToolbar {...defaultProps} selectedCount={3} onMassEdit={onMassEdit} />)
    const btn = screen.getByTestId('mass-edit-button')
    fireEvent.click(btn)
    expect(onMassEdit).toHaveBeenCalledOnce()
  })

  it('hides the mass edit button without onMassEdit', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={3} />)
    expect(screen.queryByTestId('mass-edit-button')).toBeNull()
  })

  it('hides the mass edit button when nothing is selected (no bulk bar)', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={0} onMassEdit={vi.fn()} />)
    expect(screen.queryByTestId('mass-edit-button')).toBeNull()
  })

  it('leaves the title and New to the page header with showHeading={false}, keeping the actions', () => {
    render(<ListViewToolbar {...defaultProps} selectedCount={2} showHeading={false} />)

    expect(screen.queryByRole('heading')).toBeNull()
    expect(screen.queryByText('(142)')).toBeNull()
    expect(screen.queryByTestId('new-record-button')).toBeNull()
    expect(screen.getByRole('button', { name: /Actions/ })).toBeInTheDocument()
    expect(screen.getByText('2 records selected')).toBeInTheDocument()
  })
})
