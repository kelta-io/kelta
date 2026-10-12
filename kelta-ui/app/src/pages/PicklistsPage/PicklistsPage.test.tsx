/**
 * PicklistsPage Tests
 *
 * Covers the list, create (with checkbox defaults), edit (name locked, PATCH
 * still carries it), delete confirmation, empty and error states — and the
 * data-testids the Playwright page object (`e2e-tests/pages/picklists.page.ts`)
 * relies on.
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { PicklistsPage } from './PicklistsPage'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'

const mockPicklists = [
  {
    id: 'pl-1',
    tenantId: 't',
    name: 'Priority',
    description: 'Ticket priority',
    sorted: true,
    restricted: true,
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
  },
  {
    id: 'pl-2',
    tenantId: 't',
    name: 'Region',
    description: null,
    sorted: false,
    restricted: false,
    createdAt: '2024-02-01T08:00:00Z',
    updatedAt: '2024-02-01T08:00:00Z',
  },
]

async function renderLoaded() {
  mockAxios.get.mockResolvedValueOnce({ data: mockPicklists })
  render(<PicklistsPage />, { wrapper: createTestWrapper() })
  await waitFor(() => {
    expect(screen.getByText('Priority')).toBeInTheDocument()
  })
}

describe('PicklistsPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('renders picklists with row and action test ids', async () => {
    await renderLoaded()
    expect(screen.getByTestId('picklists-page')).toBeInTheDocument()
    expect(screen.getByTestId('picklists-table')).toBeInTheDocument()
    expect(screen.getByTestId('picklist-row-0')).toHaveTextContent('Priority')
    expect(screen.getByTestId('picklist-row-1')).toHaveTextContent('Region')
    expect(within(screen.getByTestId('picklist-row-1')).getByText('-')).toBeInTheDocument()
    for (const index of [0, 1]) {
      expect(screen.getByTestId(`values-button-${index}`)).toBeInTheDocument()
      expect(screen.getByTestId(`edit-button-${index}`)).toBeInTheDocument()
      expect(screen.getByTestId(`delete-button-${index}`)).toBeInTheDocument()
    }
    expect(screen.getByRole('table', { name: 'Picklists' })).toBeInTheDocument()
  })

  it('renders the empty state', async () => {
    mockAxios.get.mockResolvedValueOnce({ data: [] })
    render(<PicklistsPage />, { wrapper: createTestWrapper() })
    expect(await screen.findByTestId('empty-state')).toHaveTextContent('No results found')
    expect(screen.getByTestId('add-picklist-button')).toBeInTheDocument()
  })

  it('renders the error state with retry', async () => {
    mockAxios.get.mockRejectedValueOnce(new Error('Network error'))
    render(<PicklistsPage />, { wrapper: createTestWrapper() })
    expect(await screen.findByTestId('error-message')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })

  it('validates the required name and creates a picklist', async () => {
    const user = userEvent.setup()
    await renderLoaded()
    mockAxios.post.mockResolvedValueOnce({
      data: { data: { type: 'global-picklists', id: 'pl-3', attributes: { name: 'Status' } } },
    })
    mockAxios.get.mockResolvedValueOnce({ data: mockPicklists })

    await user.click(screen.getByTestId('add-picklist-button'))
    const modal = screen.getByTestId('picklist-form-modal')
    expect(within(modal).getByText('Add Picklist', { selector: 'h2' })).toBeInTheDocument()
    expect(screen.getByTestId('picklist-sorted-input')).not.toBeChecked()
    expect(screen.getByTestId('picklist-restricted-input')).toBeChecked()

    await user.click(screen.getByTestId('picklist-form-submit'))
    expect(await screen.findByText('Picklist name is required')).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()

    await user.type(screen.getByTestId('picklist-name-input'), 'Status')
    await user.type(screen.getByTestId('picklist-description-input'), 'Lifecycle')
    await user.click(screen.getByTestId('picklist-sorted-input'))
    await user.click(screen.getByTestId('picklist-restricted-input'))
    await user.click(screen.getByTestId('picklist-form-submit'))

    await waitFor(() => {
      expect(mockAxios.post).toHaveBeenCalledWith('/api/global-picklists', {
        data: {
          type: 'global-picklists',
          attributes: {
            name: 'Status',
            description: 'Lifecycle',
            sorted: true,
            restricted: false,
          },
        },
      })
    })
    expect(await screen.findByText('Picklists created successfully.')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByTestId('picklist-form-modal')).not.toBeInTheDocument()
    })
  })

  it('rejects a description over 500 characters', async () => {
    const user = userEvent.setup()
    await renderLoaded()
    await user.click(screen.getByTestId('add-picklist-button'))
    await user.type(screen.getByTestId('picklist-name-input'), 'Status')
    await user.click(screen.getByTestId('picklist-description-input'))
    await user.paste('x'.repeat(501))
    await user.click(screen.getByTestId('picklist-form-submit'))
    expect(
      await screen.findByText('Description must be 500 characters or less')
    ).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('edits a picklist with the name locked', async () => {
    const user = userEvent.setup()
    await renderLoaded()
    mockAxios.patch.mockResolvedValueOnce({
      data: { data: { type: 'global-picklists', id: 'pl-1', attributes: { name: 'Priority' } } },
    })
    mockAxios.get.mockResolvedValueOnce({ data: mockPicklists })

    await user.click(screen.getByTestId('edit-button-0'))
    const nameInput = screen.getByTestId('picklist-name-input')
    expect(nameInput).toHaveValue('Priority')
    expect(nameInput).toBeDisabled()
    expect(screen.getByTestId('picklist-sorted-input')).toBeChecked()

    const description = screen.getByTestId('picklist-description-input')
    await user.clear(description)
    await user.type(description, 'Updated')
    await user.click(screen.getByTestId('picklist-form-submit'))

    await waitFor(() => {
      expect(mockAxios.patch).toHaveBeenCalledWith('/api/global-picklists/pl-1', {
        data: {
          type: 'global-picklists',
          id: 'pl-1',
          attributes: {
            name: 'Priority',
            description: 'Updated',
            sorted: true,
            restricted: true,
          },
        },
      })
    })
    expect(await screen.findByText('Picklists updated successfully.')).toBeInTheDocument()
  })

  it('closes the form on cancel and via the close button', async () => {
    const user = userEvent.setup()
    await renderLoaded()
    await user.click(screen.getByTestId('add-picklist-button'))
    await user.click(screen.getByTestId('picklist-form-cancel'))
    await waitFor(() => {
      expect(screen.queryByTestId('picklist-form-modal')).not.toBeInTheDocument()
    })
    await user.click(screen.getByTestId('add-picklist-button'))
    await user.click(screen.getByTestId('picklist-form-close'))
    await waitFor(() => {
      expect(screen.queryByTestId('picklist-form-modal')).not.toBeInTheDocument()
    })
  })

  it('deletes a picklist after confirmation', async () => {
    const user = userEvent.setup()
    await renderLoaded()
    mockAxios.delete.mockResolvedValueOnce({ data: {} })
    mockAxios.get.mockResolvedValueOnce({ data: [mockPicklists[0]] })

    await user.click(screen.getByTestId('delete-button-1'))
    const dialog = await screen.findByTestId('confirm-dialog')
    expect(
      within(dialog).getByText(
        'Are you sure you want to delete this picklist? This action cannot be undone.'
      )
    ).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => {
      expect(mockAxios.delete).toHaveBeenCalledWith('/api/global-picklists/pl-2')
    })
    expect(await screen.findByText('Picklists deleted successfully.')).toBeInTheDocument()
  })
})
