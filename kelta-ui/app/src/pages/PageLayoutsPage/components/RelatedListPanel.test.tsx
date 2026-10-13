/**
 * RelatedListPanel — the add/edit related-list dialog (a CrudFormDialog):
 * required-field validation, relationship options loaded for the picked
 * collection, the display-column check + Alt+Arrow reorder control, the saved
 * payload, editing an existing list and resetting dependents on collection
 * change.
 */

import { useEffect } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '../../../context/I18nContext'
import {
  LayoutEditorProvider,
  useLayoutEditor,
  type EditorRelatedList,
} from './LayoutEditorContext'
import { RelatedListPanel } from './RelatedListPanel'

const COLLECTIONS = [
  { id: 'parent', name: 'order', displayName: 'Order' },
  { id: 'child', name: 'line', displayName: 'Line' },
  { id: 'other', name: 'note', displayName: 'Note' },
]

const FIELDS: Record<string, Array<Record<string, unknown>>> = {
  parent: [],
  child: [
    {
      id: 'f-ref',
      name: 'order_id',
      displayName: 'Order',
      type: 'master_detail',
      referenceCollectionId: 'parent',
    },
    { id: 'f-title', name: 'title', displayName: 'Title', type: 'string' },
    { id: 'f-status', name: 'status', displayName: 'Status', type: 'string' },
    { id: 'f-priority', name: 'priority', displayName: 'Priority', type: 'string' },
  ],
  other: [{ id: 'f-body', name: 'body', displayName: 'Body', type: 'string' }],
}

const apiClient = {
  getList: vi.fn(),
  get: vi.fn(),
}

vi.mock('../../../context/ApiContext', () => ({
  useApi: () => ({ apiClient }),
}))

function Seed({ relatedLists }: { relatedLists: EditorRelatedList[] }) {
  const { setLayout } = useLayoutEditor()
  useEffect(() => {
    setLayout('parent', [], relatedLists)
  }, [setLayout, relatedLists])
  return null
}

function Probe() {
  const { state } = useLayoutEditor()
  return <pre data-testid="related-lists-state">{JSON.stringify(state.relatedLists)}</pre>
}

function renderPanel(relatedLists: EditorRelatedList[] = []) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <LayoutEditorProvider>
          <Seed relatedLists={relatedLists} />
          <RelatedListPanel />
          <Probe />
        </LayoutEditorProvider>
      </I18nProvider>
    </QueryClientProvider>
  )
}

function savedRelatedLists(): EditorRelatedList[] {
  return JSON.parse(screen.getByTestId('related-lists-state').textContent ?? '[]')
}

function columnOrder(): string[] {
  return within(screen.getByTestId('rl-form-display-columns'))
    .getAllByRole('listitem')
    .map((el) => (el.getAttribute('data-testid') ?? '').replace(/^rl-form-column-/, ''))
}

describe('RelatedListPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiClient.getList.mockResolvedValue(COLLECTIONS)
    apiClient.get.mockImplementation((url: string) => {
      const id = url.match(/\/api\/collections\/([^?]+)/)?.[1] ?? ''
      const col = COLLECTIONS.find((c) => c.id === id)
      return Promise.resolve({
        data: {
          type: 'collections',
          id,
          attributes: { name: col?.name, displayName: col?.displayName },
        },
        included: (FIELDS[id] ?? []).map(({ id: fieldId, ...attributes }) => ({
          type: 'fields',
          id: fieldId,
          attributes,
        })),
      })
    })
  })

  it('requires a collection and a relationship field', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('add-related-list-button'))

    const dialog = await screen.findByRole('dialog', { name: 'Add Related List' })
    await user.click(within(dialog).getByTestId('rl-form-submit'))

    expect(await within(dialog).findByText('Related collection is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Relationship field is required')).toBeInTheDocument()
    expect(savedRelatedLists()).toEqual([])
  })

  it('adds a related list with the checked columns in the arranged order', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('add-related-list-button'))
    const dialog = await screen.findByRole('dialog', { name: 'Add Related List' })

    await waitFor(() =>
      expect(within(dialog).getByRole('option', { name: 'Line' })).toBeInTheDocument()
    )
    // The layout's own collection is never offered.
    expect(within(dialog).queryByRole('option', { name: 'Order' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByTestId('rl-form-collection'), 'child')

    const relationship = screen.getByTestId('rl-form-relationship')
    await waitFor(() =>
      expect(
        within(relationship).getByRole('option', { name: 'Order (on Line)' })
      ).toBeInTheDocument()
    )
    await user.selectOptions(relationship, 'f-ref')

    await waitFor(() => expect(columnOrder()).toEqual(['title', 'status', 'priority']))
    await user.click(screen.getByTestId('rl-form-column-checkbox-title'))
    await user.click(screen.getByTestId('rl-form-column-checkbox-priority'))

    const priority = screen.getByTestId('rl-form-column-priority')
    fireEvent.keyDown(priority, { key: 'ArrowUp', altKey: true })
    fireEvent.keyDown(screen.getByTestId('rl-form-column-priority'), {
      key: 'ArrowUp',
      altKey: true,
    })
    expect(columnOrder()).toEqual(['priority', 'title', 'status'])

    // An emptied row limit snaps back to 10 (unchanged rule), so replace the value in one go.
    fireEvent.change(screen.getByTestId('rl-form-row-limit'), { target: { value: '25' } })
    await user.click(screen.getByTestId('rl-form-submit'))

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Add Related List' })).not.toBeInTheDocument()
    )
    const [saved] = savedRelatedLists()
    expect(saved).toMatchObject({
      relatedCollectionId: 'child',
      relationshipFieldId: 'f-ref',
      displayColumns: '["priority","title"]',
      sortDirection: 'ASC',
      rowLimit: 25,
      sortOrder: 0,
    })
  })

  it('edits an existing list and clears its dependents when the collection changes', async () => {
    const user = userEvent.setup()
    renderPanel([
      {
        id: 'rl-1',
        relatedCollectionId: 'child',
        relationshipFieldId: 'f-ref',
        displayColumns: '["status","title"]',
        sortField: 'title',
        sortDirection: 'DESC',
        rowLimit: 5,
        sortOrder: 0,
      },
    ])
    await user.click(await screen.findByTestId('related-list-edit-rl-1'))
    const dialog = await screen.findByRole('dialog', { name: 'Edit Related List' })

    await waitFor(() => expect(columnOrder()).toEqual(['status', 'title', 'priority']))
    expect(screen.getByTestId('rl-form-column-checkbox-status')).toBeChecked()
    expect(screen.getByTestId('rl-form-column-checkbox-title')).toBeChecked()
    expect(screen.getByTestId('rl-form-column-checkbox-priority')).not.toBeChecked()
    await waitFor(() => expect(screen.getByTestId('rl-form-relationship')).toHaveValue('f-ref'))
    expect(screen.getByTestId('rl-form-sort-field')).toHaveValue('title')
    expect(screen.getByTestId('rl-form-sort-direction')).toHaveValue('DESC')
    expect(within(dialog).getByTestId('rl-form-submit')).toHaveTextContent('Save')

    await user.selectOptions(screen.getByTestId('rl-form-collection'), 'other')
    expect(screen.getByTestId('rl-form-relationship')).toHaveValue('')
    expect(screen.getByTestId('rl-form-sort-field')).toHaveValue('')
    await waitFor(() => expect(columnOrder()).toEqual(['body']))
    expect(screen.getByTestId('rl-form-column-checkbox-body')).not.toBeChecked()

    await user.click(within(dialog).getByTestId('rl-form-cancel'))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Edit Related List' })).not.toBeInTheDocument()
    )
    expect(savedRelatedLists()[0]).toMatchObject({ relatedCollectionId: 'child', rowLimit: 5 })
  })
})
