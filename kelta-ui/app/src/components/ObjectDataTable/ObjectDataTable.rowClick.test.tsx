import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { ObjectDataTable } from './ObjectDataTable'
import { RecordStackProvider } from '@/context/RecordStackContext'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

const fields: FieldDefinition[] = [
  { id: 'f-name', name: 'name', displayName: 'Name', type: 'string', required: false },
]
const records: CollectionRecord[] = [{ id: 'r1', name: 'Acme' }]

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

function renderTable(
  props: Partial<React.ComponentProps<typeof ObjectDataTable>> = {},
  wrap: (table: React.ReactElement) => React.ReactElement = (table) => table
) {
  return render(
    <MemoryRouter initialEntries={['/acme/resources/accounts']}>
      <Routes>
        <Route
          path="/:tenantSlug/resources/accounts"
          element={
            <>
              {wrap(
                <ObjectDataTable
                  records={records}
                  fields={fields}
                  onSortChange={vi.fn()}
                  selectedIds={new Set()}
                  onSelectionChange={vi.fn()}
                  collectionName="accounts"
                  {...props}
                />
              )}
              <LocationProbe />
            </>
          }
        />
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>
  )
}

describe('ObjectDataTable row activation', () => {
  it('calls onRowClick with the record instead of navigating when provided', () => {
    const onRowClick = vi.fn()
    renderTable({ onRowClick })
    fireEvent.click(screen.getByText('Acme'))
    expect(onRowClick).toHaveBeenCalledWith(records[0])
    // No navigation happened — the caller owns the route.
    expect(screen.getByTestId('location').textContent).toBe('/acme/resources/accounts')
  })

  it('falls back to the end-user record route when onRowClick is omitted', () => {
    renderTable()
    fireEvent.click(screen.getByText('Acme'))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts/r1')
  })

  it('routes to the admin record when rendered in the admin record stack', () => {
    renderTable({}, (table) => <RecordStackProvider variant="admin">{table}</RecordStackProvider>)
    fireEvent.click(screen.getByText('Acme'))
    expect(screen.getByTestId('location').textContent).toBe('/acme/resources/accounts/r1')
  })
})
