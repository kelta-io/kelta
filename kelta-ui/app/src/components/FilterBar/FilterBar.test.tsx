import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { FilterBar } from './FilterBar'
import type { FilterCondition } from '@/hooks/useCollectionRecords'

describe('FilterBar', () => {
  const sampleFilters: FilterCondition[] = [
    { id: 'f1', field: 'status', operator: 'equals', value: 'active' },
    { id: 'f2', field: 'name', operator: 'contains', value: 'Acme' },
  ]

  it('renders nothing when filters are empty', () => {
    const { container } = render(
      <FilterBar filters={[]} onRemoveFilter={vi.fn()} onClearAll={vi.fn()} />
    )
    expect(container.innerHTML).toBe('')
  })

  it('renders filter badges', () => {
    render(<FilterBar filters={sampleFilters} onRemoveFilter={vi.fn()} onClearAll={vi.fn()} />)

    expect(screen.getByText('status')).toBeDefined()
    expect(screen.getByText('active')).toBeDefined()
    expect(screen.getByText('name')).toBeDefined()
    expect(screen.getByText('Acme')).toBeDefined()
  })

  it('displays operator symbols', () => {
    render(<FilterBar filters={sampleFilters} onRemoveFilter={vi.fn()} onClearAll={vi.fn()} />)

    expect(screen.getByText('=')).toBeDefined()
    expect(screen.getByText('contains')).toBeDefined()
  })

  it('calls onRemoveFilter when X button is clicked', () => {
    const onRemoveFilter = vi.fn()
    render(
      <FilterBar filters={sampleFilters} onRemoveFilter={onRemoveFilter} onClearAll={vi.fn()} />
    )

    // Click the first remove button
    const removeButtons = screen.getAllByRole('button', { name: /Remove filter/ })
    fireEvent.click(removeButtons[0])

    expect(onRemoveFilter).toHaveBeenCalledWith('f1')
  })

  it('shows "Clear all" button when multiple filters', () => {
    const onClearAll = vi.fn()
    render(<FilterBar filters={sampleFilters} onRemoveFilter={vi.fn()} onClearAll={onClearAll} />)

    const clearButton = screen.getByText('Clear all')
    expect(clearButton).toBeDefined()

    fireEvent.click(clearButton)
    expect(onClearAll).toHaveBeenCalled()
  })

  it('hides "Clear all" with single filter', () => {
    render(<FilterBar filters={[sampleFilters[0]]} onRemoveFilter={vi.fn()} onClearAll={vi.fn()} />)

    expect(screen.queryByText('Clear all')).toBeNull()
  })

  it('shows Filters label', () => {
    render(<FilterBar filters={sampleFilters} onRemoveFilter={vi.fn()} onClearAll={vi.fn()} />)

    expect(screen.getByText('Filters:')).toBeDefined()
  })

  it('shows the authored label for a mapped picklist filter value while keeping the raw value in the callback', () => {
    const onRemoveFilter = vi.fn()
    render(
      <FilterBar
        filters={sampleFilters}
        onRemoveFilter={onRemoveFilter}
        onClearAll={vi.fn()}
        picklistDisplayMaps={{
          status: new Map([['active', { label: 'Active', color: '#22C55E' }]]),
        }}
      />
    )

    expect(screen.getByText('Active')).toBeDefined()
    expect(screen.queryByText('active')).toBeNull()

    fireEvent.click(screen.getAllByRole('button', { name: /Remove filter/ })[0])
    expect(onRemoveFilter).toHaveBeenCalledWith('f1')
  })

  it('falls back to the raw value when no display map entry exists for the filtered field', () => {
    render(
      <FilterBar
        filters={sampleFilters}
        onRemoveFilter={vi.fn()}
        onClearAll={vi.fn()}
        picklistDisplayMaps={{ status: new Map() }}
      />
    )

    expect(screen.getByText('active')).toBeDefined()
  })
})
