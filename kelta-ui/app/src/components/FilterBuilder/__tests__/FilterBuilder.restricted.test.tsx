/**
 * `FilterBuilder` for a consumer whose filter grammar is narrower than the builder's: an operator
 * subset (`operators`) and no ALL/ANY switch (`allowOr={false}`) — how the record list uses it.
 * The shadcn Select is rendered inline so its options can be read.
 */
import React from 'react'
import { render, screen, within } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { FilterBuilder, type FilterExpression } from '../FilterBuilder'

vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  SelectTrigger: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div {...props}>{children}</div>
  ),
  SelectValue: () => null,
  SelectContent: ({ children }: React.PropsWithChildren) => <div role="listbox">{children}</div>,
  SelectItem: ({ children, value }: React.PropsWithChildren<{ value: string }>) => (
    <div role="option" aria-selected={false} data-value={value}>
      {children}
    </div>
  ),
}))

const fields = [
  { name: 'status', displayName: 'Status' },
  { name: 'amount', displayName: 'Amount' },
]

const TWO_CLAUSES: FilterExpression = {
  logic: 'AND',
  filters: [
    { field: 'status', op: 'equals', value: 'open' },
    { field: 'amount', op: 'gt', value: '5' },
  ],
}

function operatorValues(clauseIndex: number): string[] {
  const opTrigger = screen.getByTestId(`filter-op-${clauseIndex}`)
  const menu = within(opTrigger.parentElement as HTMLElement).getAllByRole('listbox')[0]
  return within(menu)
    .getAllByRole('option')
    .map((o) => o.getAttribute('data-value') ?? '')
}

describe('FilterBuilder — restricted consumer', () => {
  it('offers every operator by default', () => {
    render(<FilterBuilder value={TWO_CLAUSES} onChange={vi.fn()} fields={fields} />)

    expect(operatorValues(0)).toContain('is_null')
    expect(operatorValues(0)).toHaveLength(11)
  })

  it('offers only the given operators', () => {
    render(
      <FilterBuilder
        value={TWO_CLAUSES}
        onChange={vi.fn()}
        fields={fields}
        operators={['equals', 'gt']}
      />
    )

    expect(operatorValues(0)).toEqual(['equals', 'gt'])
  })

  it('shows the ALL/ANY switch for several clauses unless OR is disallowed', () => {
    const { rerender } = render(
      <FilterBuilder value={TWO_CLAUSES} onChange={vi.fn()} fields={fields} />
    )
    expect(screen.getByTestId('filter-logic')).toBeInTheDocument()

    rerender(
      <FilterBuilder value={TWO_CLAUSES} onChange={vi.fn()} fields={fields} allowOr={false} />
    )
    expect(screen.queryByTestId('filter-logic')).toBeNull()
  })
})
