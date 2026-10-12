/**
 * PageLayoutsPage Tests
 *
 * Unit tests for the PageLayoutsPage component (LIST view mode).
 * Tests cover:
 * - Loading state rendering
 * - Error state rendering with retry
 * - Empty state when no layouts exist
 * - Layout table rendering with data
 * - Create layout modal flow
 * - Edit layout modal flow
 * - Delete confirmation dialog flow
 * - Design button rendering for each row
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  createTestWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../../test/testUtils'
import { PageLayoutsPage } from '../PageLayoutsPage'

// ---------------------------------------------------------------------------
// Mock data
// ---------------------------------------------------------------------------

// Collection summaries returned by /api/collections (flat array)
const mockCollectionSummaries = [
  { id: 'col-1', name: 'accounts', displayName: 'Accounts' },
  { id: 'col-2', name: 'contacts', displayName: 'Contacts' },
  { id: 'col-3', name: 'opportunities', displayName: 'Opportunities' },
]

const mockLayouts = [
  {
    id: 'layout-1',
    name: 'Account Detail Layout',
    description: 'Standard detail layout for accounts',
    layoutType: 'DETAIL',
    collectionId: 'col-1',
    isDefault: true,
    createdAt: '2024-06-10T08:00:00Z',
    updatedAt: '2024-06-15T10:30:00Z',
  },
  {
    id: 'layout-2',
    name: 'Contact Edit Layout',
    description: 'Edit layout for contacts',
    layoutType: 'EDIT',
    collectionId: 'col-2',
    isDefault: false,
    createdAt: '2024-06-12T09:00:00Z',
    updatedAt: '2024-06-14T11:00:00Z',
  },
  {
    id: 'layout-3',
    name: 'Opportunity Mini Layout',
    description: null,
    layoutType: 'MINI',
    collectionId: 'col-3',
    isDefault: false,
    createdAt: '2024-06-14T14:00:00Z',
    updatedAt: '2024-06-14T14:00:00Z',
  },
]

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Set up standard Axios mocks for layouts + collections endpoints.
 * Override specific responses with the `overrides` parameter.
 */
function setupAxiosMocks(overrides: { layouts?: unknown; collections?: unknown } = {}) {
  mockAxios.get.mockImplementation((url: string) => {
    if (url.includes('/api/page-layouts')) {
      return Promise.resolve({ data: overrides.layouts ?? mockLayouts })
    }
    if (url.includes('/api/collections')) {
      return Promise.resolve({ data: overrides.collections ?? mockCollectionSummaries })
    }
    if (url.includes('/api/collections')) {
      return Promise.resolve({ data: overrides.collections ?? mockCollectionSummaries })
    }
    return Promise.resolve({ data: {} })
  })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('PageLayoutsPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.clearAllMocks()
  })

  // -------------------------------------------------------------------------
  // 1. Loading state
  // -------------------------------------------------------------------------

  describe('Loading State', () => {
    it('should display loading spinner while fetching layouts', () => {
      // Mock a delayed response so the loading state persists
      mockAxios.get.mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve({ data: mockLayouts }), 5000))
      )

      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      expect(screen.getByRole('status')).toBeInTheDocument()
    })
  })

  // -------------------------------------------------------------------------
  // 2. Error state
  // -------------------------------------------------------------------------

  describe('Error State', () => {
    it('should display error message when fetch fails', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/page-layouts')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should display retry button on error', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/page-layouts')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
      })
    })
  })

  // -------------------------------------------------------------------------
  // 3. Empty state
  // -------------------------------------------------------------------------

  describe('Empty State', () => {
    it('should display empty state when no layouts exist', async () => {
      setupAxiosMocks({ layouts: [] })

      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('empty-state')).toBeInTheDocument()
      })

      expect(screen.getByText(/no page layouts found/i)).toBeInTheDocument()
    })

    it('should still show Create Layout button in empty state', async () => {
      setupAxiosMocks({ layouts: [] })

      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })
    })
  })

  // -------------------------------------------------------------------------
  // 4. Layout table rendering
  // -------------------------------------------------------------------------

  describe('Layout Table Display', () => {
    beforeEach(() => {
      setupAxiosMocks()
    })

    it('should display the page title', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: /page layouts/i })).toBeInTheDocument()
      })
    })

    it('should render the layouts table as a labelled table', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('page-layouts-table')).toBeInTheDocument()
      })

      expect(screen.getByRole('table', { name: /page layouts/i })).toBeInTheDocument()
    })

    it('should display all layout names in the table', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      expect(screen.getByText('Contact Edit Layout')).toBeInTheDocument()
      expect(screen.getByText('Opportunity Mini Layout')).toBeInTheDocument()
    })

    it('should display collection names resolved from collection IDs', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      // Collections should be resolved to display names
      expect(screen.getByText('Accounts')).toBeInTheDocument()
      expect(screen.getByText('Contacts')).toBeInTheDocument()
      expect(screen.getByText('Opportunities')).toBeInTheDocument()
    })

    it('should display layout type badges', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('DETAIL')).toBeInTheDocument()
      })

      expect(screen.getByText('EDIT')).toBeInTheDocument()
      expect(screen.getByText('MINI')).toBeInTheDocument()
    })

    it('should display default status for each layout', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      // First layout is default, others are not
      const rows = screen.getAllByTestId(/admin-data-table-row-/)
      expect(rows).toHaveLength(3)

      expect(within(rows[0]).getByText('Yes')).toBeInTheDocument()
      expect(within(rows[1]).getByText('No')).toBeInTheDocument()
      expect(within(rows[2]).getByText('No')).toBeInTheDocument()
    })

    it('should display three rows matching the mock data', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        const rows = screen.getAllByTestId(/admin-data-table-row-/)
        expect(rows).toHaveLength(3)
      })
    })

    it('should render table column headers', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      expect(screen.getByRole('columnheader', { name: /name/i })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: /collection/i })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: /type/i })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: /default/i })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: /created/i })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: /actions/i })).toBeInTheDocument()
    })

    it('should render with a custom testId', async () => {
      setupAxiosMocks()

      render(<PageLayoutsPage testId="my-layouts" />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('my-layouts')).toBeInTheDocument()
      })
    })
  })

  // -------------------------------------------------------------------------
  // 5. Create Layout modal
  // -------------------------------------------------------------------------

  describe('Create Layout', () => {
    beforeEach(() => {
      setupAxiosMocks()
    })

    it('should open create modal when Create Layout button is clicked', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-layout-button'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      // Verify the modal title says "Create Layout"
      expect(screen.getByText('Create Layout', { selector: 'h2' })).toBeInTheDocument()
    })

    it('should close the create modal when Cancel is clicked', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-layout-button'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('layout-form-cancel'))

      await waitFor(() => {
        expect(screen.queryByTestId('layout-form-modal')).not.toBeInTheDocument()
      })
    })

    it('should create a layout via the form and call the API', async () => {
      const user = userEvent.setup()

      const newLayout = {
        id: 'layout-new',
        name: 'New Layout',
        description: 'A new layout',
        layoutType: 'DETAIL',
        collectionId: 'col-1',
        isDefault: false,
        createdAt: '2024-06-20T10:00:00Z',
        updatedAt: '2024-06-20T10:00:00Z',
      }

      mockAxios.post.mockResolvedValueOnce({ data: newLayout })

      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })

      // Open the create modal
      await user.click(screen.getByTestId('add-layout-button'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      // Fill in the form
      const nameInput = screen.getByTestId('layout-name-input')
      await user.clear(nameInput)
      await user.type(nameInput, 'New Layout')

      const descInput = screen.getByTestId('layout-description-input')
      await user.type(descInput, 'A new layout')

      // Select a collection
      const collectionSelect = screen.getByTestId('layout-collection-id-input')
      await user.selectOptions(collectionSelect, 'col-1')

      // Submit the form
      await user.click(screen.getByTestId('layout-form-submit'))

      // Verify the API was called
      await waitFor(() => {
        expect(mockAxios.post).toHaveBeenCalled()
      })

      // The POST URL should include collectionId
      const postCall = mockAxios.post.mock.calls[0]
      expect(postCall[0]).toContain('/api/page-layouts')
      expect(postCall[0]).toContain('collectionId=col-1')
    })

    it('should show validation error when name is empty', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-layout-button'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      // Select a collection so only name is the blocker
      const collectionSelect = screen.getByTestId('layout-collection-id-input')
      await user.selectOptions(collectionSelect, 'col-1')

      // Submit without entering a name
      await user.click(screen.getByTestId('layout-form-submit'))

      await waitFor(() => {
        expect(screen.getByText('Name is required')).toBeInTheDocument()
      })

      // Verify no API call was made
      expect(mockAxios.post).not.toHaveBeenCalled()
    })

    it('should show validation error when collection is not selected', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-layout-button'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      // Fill in name but leave collection empty
      const nameInput = screen.getByTestId('layout-name-input')
      await user.type(nameInput, 'My Layout')

      // Submit without selecting a collection
      await user.click(screen.getByTestId('layout-form-submit'))

      await waitFor(() => {
        expect(screen.getByText('Collection is required')).toBeInTheDocument()
      })

      expect(mockAxios.post).not.toHaveBeenCalled()
    })
  })

  // -------------------------------------------------------------------------
  // 6. Edit layout modal
  // -------------------------------------------------------------------------

  describe('Edit Layout', () => {
    beforeEach(() => {
      setupAxiosMocks()
    })

    it('should open edit modal when Edit button is clicked', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      // Verify the modal title says "Edit Layout"
      expect(screen.getByText('Edit Layout', { selector: 'h2' })).toBeInTheDocument()
    })

    it('should populate form with existing layout data', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      // Verify form is pre-populated
      const nameInput = screen.getByTestId('layout-name-input') as HTMLInputElement
      expect(nameInput.value).toBe('Account Detail Layout')

      const descInput = screen.getByTestId('layout-description-input') as HTMLTextAreaElement
      expect(descInput.value).toBe('Standard detail layout for accounts')

      const typeSelect = screen.getByTestId('layout-type-select') as HTMLSelectElement
      expect(typeSelect.value).toBe('DETAIL')

      const defaultCheckbox = screen.getByTestId('layout-is-default-input') as HTMLInputElement
      expect(defaultCheckbox.checked).toBe(true)
    })

    it('should disable collection selector in edit mode', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('layout-form-modal')).toBeInTheDocument()
      })

      const collectionSelect = screen.getByTestId('layout-collection-id-input') as HTMLSelectElement
      expect(collectionSelect).toBeDisabled()
    })

    it('should send the unchanged collection with the edited metadata', async () => {
      const user = userEvent.setup()
      mockAxios.put.mockResolvedValueOnce({ data: mockLayouts[0] })
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await user.click(await screen.findByTestId('edit-button-0'))
      await screen.findByTestId('layout-form-modal')
      await user.type(screen.getByTestId('layout-name-input'), ' v2')
      await user.click(screen.getByTestId('layout-form-submit'))

      await waitFor(() => expect(mockAxios.put).toHaveBeenCalled())
      const [url, body] = mockAxios.put.mock.calls[0] as [string, unknown]
      expect(url).toContain('/api/page-layouts/layout-1')
      expect(JSON.stringify(body)).toContain('"collectionId":"col-1"')
      expect(JSON.stringify(body)).toContain('"name":"Account Detail Layout v2"')
    })
  })

  // -------------------------------------------------------------------------
  // 7. Delete confirmation dialog
  // -------------------------------------------------------------------------

  describe('Delete Layout', () => {
    beforeEach(() => {
      setupAxiosMocks()
    })

    it('should open delete confirmation dialog when Delete button is clicked', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      })

      expect(screen.getByText(/are you sure you want to delete this layout/i)).toBeInTheDocument()
    })

    it('should close delete dialog when Cancel is clicked', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('confirm-dialog-cancel'))

      await waitFor(() => {
        expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument()
      })
    })

    it('should call delete API when confirming deletion', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      // Setup delete mock before clicking
      mockAxios.delete.mockResolvedValueOnce({ data: null })

      await user.click(screen.getByTestId('delete-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('confirm-dialog-confirm'))

      await waitFor(() => {
        expect(mockAxios.delete).toHaveBeenCalled()
      })

      // Verify the delete was called with the correct layout ID
      const deleteUrl = mockAxios.delete.mock.calls[0][0]
      expect(deleteUrl).toContain('/api/page-layouts/layout-1')
    })

    it('should show delete confirmation title as Delete Layout', async () => {
      const user = userEvent.setup()
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog-title')).toHaveTextContent('Delete Layout')
      })
    })
  })

  // -------------------------------------------------------------------------
  // 8. Design button
  // -------------------------------------------------------------------------

  describe('Design Button', () => {
    beforeEach(() => {
      setupAxiosMocks()
    })

    it('should render a Design button for each layout row', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      expect(screen.getByTestId('design-button-0')).toBeInTheDocument()
      expect(screen.getByTestId('design-button-1')).toBeInTheDocument()
      expect(screen.getByTestId('design-button-2')).toBeInTheDocument()
    })

    it('should have accessible aria-label on Design buttons', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      expect(screen.getByTestId('design-button-0')).toHaveAttribute(
        'aria-label',
        'Design Account Detail Layout'
      )
      expect(screen.getByTestId('design-button-1')).toHaveAttribute(
        'aria-label',
        'Design Contact Edit Layout'
      )
      expect(screen.getByTestId('design-button-2')).toHaveAttribute(
        'aria-label',
        'Design Opportunity Mini Layout'
      )
    })

    it('should render Edit and Delete buttons alongside Design for each row', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      // Each row should have Design, Edit, and Delete buttons
      for (let i = 0; i < 3; i++) {
        expect(screen.getByTestId(`design-button-${i}`)).toBeInTheDocument()
        expect(screen.getByTestId(`edit-button-${i}`)).toBeInTheDocument()
        expect(screen.getByTestId(`delete-button-${i}`)).toBeInTheDocument()
      }
    })
  })

  // -------------------------------------------------------------------------
  // 9. Accessibility
  // -------------------------------------------------------------------------

  describe('Accessibility', () => {
    beforeEach(() => {
      setupAxiosMocks()
    })

    it('should have accessible table structure with a labelled table', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByRole('table')).toBeInTheDocument()
      })

      expect(screen.getByRole('table')).toHaveAttribute('aria-label', 'Page Layouts')
    })

    it('should have accessible action buttons with aria-labels', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Account Detail Layout')).toBeInTheDocument()
      })

      const editButton = screen.getByTestId('edit-button-0')
      expect(editButton).toHaveAttribute('aria-label', 'Edit Account Detail Layout')

      const deleteButton = screen.getByTestId('delete-button-0')
      expect(deleteButton).toHaveAttribute('aria-label', 'Delete Account Detail Layout')
    })

    it('should have the Create Layout button with aria-label', async () => {
      render(<PageLayoutsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-layout-button')).toBeInTheDocument()
      })

      expect(screen.getByTestId('add-layout-button')).toHaveAttribute('aria-label', 'Create Layout')
    })
  })
})
