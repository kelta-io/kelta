import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { KanbanBoard } from './KanbanBoard'
import { resolveLanes } from './lanes'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

const laneField: FieldDefinition = {
  id: 'f-status',
  name: 'status',
  displayName: 'Status',
  type: 'picklist',
  required: false,
}
const cardFields: FieldDefinition[] = [
  { id: 'f-amount', name: 'amount', displayName: 'Amount', type: 'currency', required: false },
]

const records: CollectionRecord[] = [
  { id: 'r1', name: 'Acme', status: 'open', amount: 10 },
  { id: 'r2', name: 'Beta', status: 'won', amount: 20 },
  { id: 'r3', name: 'Gamma', status: 'legacy', amount: 5 },
  { id: 'r4', name: 'Delta', status: null, amount: null },
]

describe('resolveLanes', () => {
  it('orders lanes by picklist options, appends data-only values, then unassigned', () => {
    const lanes = resolveLanes(records, 'status', ['open', 'won', 'lost'])
    expect(lanes.map((l) => l.label)).toEqual(['open', 'won', 'lost', 'legacy', '—'])
    expect(lanes.find((l) => l.label === 'lost')?.records).toHaveLength(0)
    expect(lanes.find((l) => l.label === '—')?.value).toBeNull()
    expect(lanes.find((l) => l.label === '—')?.records.map((r) => r.id)).toEqual(['r4'])
  })

  it('omits the unassigned lane when every record has a value', () => {
    const lanes = resolveLanes(records.slice(0, 3), 'status', ['open'])
    expect(lanes.some((l) => l.value === null)).toBe(false)
  })

  it('uses the authored label/color for the header but keeps grouping on the stored value', () => {
    const displayMap = new Map([['open', { label: 'Open', color: '#3B82F6' }]])
    const lanes = resolveLanes(records, 'status', ['open', 'won', 'lost'], displayMap)
    const openLane = lanes.find((l) => l.id === 'open')
    expect(openLane?.label).toBe('Open')
    expect(openLane?.color).toBe('#3B82F6')
    expect(openLane?.value).toBe('open')
    expect(openLane?.records.map((r) => r.id)).toEqual(['r1'])
    // 'won' has no display-map entry — falls back to the raw value with no color.
    const wonLane = lanes.find((l) => l.id === 'won')
    expect(wonLane?.label).toBe('won')
    expect(wonLane?.color).toBeUndefined()
  })
})

function renderBoard(props: Partial<React.ComponentProps<typeof KanbanBoard>> = {}) {
  return render(
    <MemoryRouter>
      <KanbanBoard
        records={records}
        laneField={laneField}
        laneOptions={['open', 'won', 'lost']}
        titleField="name"
        cardFields={cardFields}
        canEdit
        onCardClick={vi.fn()}
        onMoveCard={vi.fn().mockResolvedValue(undefined)}
        {...props}
      />
    </MemoryRouter>
  )
}

describe('KanbanBoard', () => {
  it('renders one column per lane with counts, empty picklist lanes included', () => {
    renderBoard()
    expect(screen.getByTestId('kanban-lane-count-open').textContent).toBe('(1)')
    expect(screen.getByTestId('kanban-lane-count-won').textContent).toBe('(1)')
    expect(screen.getByTestId('kanban-lane-count-lost').textContent).toBe('(0)')
    expect(screen.getByTestId('kanban-lane-count-legacy').textContent).toBe('(1)')
    expect(screen.getByTestId('kanban-lane-count-__unassigned__').textContent).toBe('(1)')
  })

  it('places cards in their lane with title and card fields', () => {
    renderBoard()
    const openLane = screen.getByTestId('kanban-lane-open')
    expect(within(openLane).getByText('Acme')).toBeInTheDocument()
    expect(within(openLane).getByTestId('kanban-card-r1')).toBeInTheDocument()
    // Unassigned lane holds the null-status record
    const unassigned = screen.getByTestId('kanban-lane-__unassigned__')
    expect(within(unassigned).getByText('Delta')).toBeInTheDocument()
  })

  it('fires onCardClick when a card is clicked', () => {
    const onCardClick = vi.fn()
    renderBoard({ onCardClick })
    fireEvent.click(screen.getByTestId('kanban-card-r2'))
    expect(onCardClick).toHaveBeenCalledWith(records[1])
  })

  it('falls back to the record id when the title field is empty', () => {
    renderBoard({ records: [{ id: 'r9', name: '', status: 'open' }] })
    expect(within(screen.getByTestId('kanban-card-r9')).getByText('r9')).toBeInTheDocument()
  })

  it('shows the authored label and a color dot in the lane header when a display map is given', () => {
    renderBoard({
      laneDisplayMap: new Map([['open', { label: 'In progress', color: '#F59E0B' }]]),
    })
    const openLane = screen.getByTestId('kanban-lane-open')
    expect(within(openLane).getByText('In progress')).toBeInTheDocument()
    expect(within(openLane).queryByText('open')).toBeNull()
    const dot = openLane.querySelector('span[style*="background-color"]')
    expect(dot).not.toBeNull()
  })

  it('renders the raw value with no dot when the lane has no display map entry', () => {
    renderBoard()
    const wonLane = screen.getByTestId('kanban-lane-won')
    expect(within(wonLane).getByText('won')).toBeInTheDocument()
    expect(wonLane.querySelector('span[style*="background-color"]')).toBeNull()
  })
})
