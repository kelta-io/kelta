import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach } from 'vitest'
import { AdminDataTable, type AdminColumn } from '../AdminDataTable'
import { evaluateFilter } from '../evaluateFilter'

interface Row {
  id: string
  name: string
  count: number
}

const columns: AdminColumn<Row>[] = [
  { id: 'name', header: 'Name' },
  { id: 'count', header: 'Count' },
]

const rows: Row[] = [
  { id: '1', name: 'Bravo', count: 2 },
  { id: '2', name: 'Alpha', count: 10 },
  { id: '3', name: 'Charlie', count: 1 },
]

describe('AdminDataTable', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('renders rows in input order by default', () => {
    render(<AdminDataTable tableId="t1" columns={columns} rows={rows} rowKey={(r) => r.id} />)
    const bodyRows = screen.getAllByTestId(/^admin-data-table-row-/)
    expect(bodyRows.map((r) => r.dataset.testid)).toEqual([
      'admin-data-table-row-1',
      'admin-data-table-row-2',
      'admin-data-table-row-3',
    ])
  })

  it('sorts ascending then descending on header click', async () => {
    render(<AdminDataTable tableId="t2" columns={columns} rows={rows} rowKey={(r) => r.id} />)
    await userEvent.click(screen.getByTestId('admin-data-table-sort-name'))
    let bodyRows = screen.getAllByTestId(/^admin-data-table-row-/)
    expect(bodyRows[0].dataset.testid).toBe('admin-data-table-row-2') // Alpha
    await userEvent.click(screen.getByTestId('admin-data-table-sort-name'))
    bodyRows = screen.getAllByTestId(/^admin-data-table-row-/)
    expect(bodyRows[0].dataset.testid).toBe('admin-data-table-row-3') // Charlie
  })

  it('uses custom cell renderer', () => {
    const cols: AdminColumn<Row>[] = [
      { id: 'name', header: 'Name', cell: (r) => <strong>{r.name.toUpperCase()}</strong> },
    ]
    render(<AdminDataTable tableId="t3" columns={cols} rows={[rows[0]]} rowKey={(r) => r.id} />)
    expect(screen.getByText('BRAVO')).toBeInTheDocument()
  })

  it('passes the displayed index to cell renderers', async () => {
    const cols: AdminColumn<Row>[] = [
      {
        id: 'name',
        header: 'Name',
        cell: (r, index) => <span data-testid={`name-cell-${index}`}>{r.name}</span>,
      },
    ]
    render(<AdminDataTable tableId="t3b" columns={cols} rows={rows} rowKey={(r) => r.id} />)
    expect(screen.getByTestId('name-cell-0')).toHaveTextContent('Bravo')
    await userEvent.click(screen.getByTestId('admin-data-table-sort-name'))
    expect(screen.getByTestId('name-cell-0')).toHaveTextContent('Alpha')
    expect(screen.getByTestId('name-cell-2')).toHaveTextContent('Charlie')
  })

  it('renders actions slot', () => {
    render(
      <AdminDataTable
        tableId="t4"
        columns={columns}
        rows={[rows[0]]}
        rowKey={(r) => r.id}
        renderActions={(r) => <span data-testid={`actions-${r.id}`}>x</span>}
      />
    )
    expect(screen.getByTestId('actions-1')).toBeInTheDocument()
  })

  it('shows the filter panel when toggled', async () => {
    render(<AdminDataTable tableId="t5" columns={columns} rows={rows} rowKey={(r) => r.id} />)
    expect(screen.queryByTestId('admin-data-table-filter')).toBeNull()
    await userEvent.click(screen.getByTestId('admin-data-table-filter-toggle'))
    expect(screen.getByTestId('admin-data-table-filter')).toBeInTheDocument()
  })

  it('renders empty message when no rows', () => {
    render(
      <AdminDataTable
        tableId="t6"
        columns={columns}
        rows={[]}
        rowKey={(r) => r.id}
        emptyMessage="No data here."
      />
    )
    expect(screen.getByText('No data here.')).toBeInTheDocument()
  })

  it('supports custom test ids, aria label, actions header and displayed-order indexes', async () => {
    render(
      <AdminDataTable
        tableId="t7"
        testId="things-table"
        ariaLabel="Things"
        actionsHeader="Acciones"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        rowTestId={(_, index) => `thing-row-${index}`}
        renderActions={(r, index) => <button data-testid={`act-${index}`}>{r.name}</button>}
      />
    )
    expect(screen.getByTestId('things-table')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Things' })).toBeInTheDocument()
    expect(screen.getByText('Acciones')).toBeInTheDocument()
    expect(screen.getByTestId('thing-row-0')).toHaveTextContent('Bravo')
    expect(screen.getByTestId('act-0')).toHaveTextContent('Bravo')

    // Indexes follow the displayed (sorted) order, not the input order.
    await userEvent.click(screen.getByTestId('admin-data-table-sort-name'))
    expect(screen.getByTestId('thing-row-0')).toHaveTextContent('Alpha')
    expect(screen.getByTestId('act-0')).toHaveTextContent('Alpha')
  })
})

describe('evaluateFilter (admin)', () => {
  it('AND requires all clauses', () => {
    const f = {
      logic: 'AND' as const,
      filters: [
        { field: 'name', op: 'equals' as const, value: 'Alpha' },
        { field: 'count', op: 'gt' as const, value: 5 },
      ],
    }
    expect(evaluateFilter(f, { name: 'Alpha', count: 10 })).toBe(true)
    expect(evaluateFilter(f, { name: 'Alpha', count: 3 })).toBe(false)
  })

  it('OR matches if any clause matches', () => {
    const f = {
      logic: 'OR' as const,
      filters: [
        { field: 'name', op: 'equals' as const, value: 'Alpha' },
        { field: 'count', op: 'gt' as const, value: 5 },
      ],
    }
    expect(evaluateFilter(f, { name: 'Bravo', count: 10 })).toBe(true)
    expect(evaluateFilter(f, { name: 'Bravo', count: 1 })).toBe(false)
  })

  it('is_null treats empty string as blank', () => {
    expect(
      evaluateFilter({ logic: 'AND', filters: [{ field: 'name', op: 'is_null' }] }, { name: '' })
    ).toBe(true)
  })

  it('empty filter is always true', () => {
    expect(evaluateFilter(null, {})).toBe(true)
    expect(evaluateFilter({ logic: 'AND', filters: [] }, {})).toBe(true)
  })
})
