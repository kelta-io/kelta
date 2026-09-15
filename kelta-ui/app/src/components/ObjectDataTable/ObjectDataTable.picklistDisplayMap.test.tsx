import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ObjectDataTable } from './ObjectDataTable'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

const fields: FieldDefinition[] = [
  { id: 'f-stage', name: 'stage', displayName: 'Stage', type: 'picklist', required: false },
]
const records: CollectionRecord[] = [{ id: 'r1', stage: 'in_progress' }]
const picklistDisplayMaps = {
  stage: new Map([['in_progress', { label: 'In progress', color: '#F59E0B' }]]),
}

function renderTable(props: Partial<React.ComponentProps<typeof ObjectDataTable>> = {}) {
  return render(
    <MemoryRouter>
      <ObjectDataTable
        records={records}
        fields={fields}
        onSortChange={vi.fn()}
        selectedIds={new Set()}
        onSelectionChange={vi.fn()}
        collectionName="deals"
        {...props}
      />
    </MemoryRouter>
  )
}

describe('ObjectDataTable picklist display map', () => {
  it('renders the raw value when no display map is supplied', () => {
    renderTable()
    expect(screen.getByText('in_progress')).toBeInTheDocument()
  })

  it('renders the authored label in read-only list cells', () => {
    renderTable({ picklistDisplayMaps })
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.queryByText('in_progress')).not.toBeInTheDocument()
  })

  it('renders the authored label in the inline-editable cell', () => {
    renderTable({
      picklistDisplayMaps,
      editable: true,
      onCellCommit: vi.fn().mockResolvedValue(undefined),
    })
    expect(screen.getByText('In progress')).toBeInTheDocument()
  })
})
