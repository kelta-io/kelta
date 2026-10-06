/**
 * CollectionDetailPage Tests
 *
 * Unit tests for the CollectionDetailPage component covering:
 * - Rendering collection metadata
 * - Rendering fields list
 * - Rendering authorization configuration
 * - Rendering version history
 * - Loading state
 * - Error state
 * - Edit/delete actions
 * - Navigation
 *
 * Requirements:
 * - 3.7: Navigate to collection detail page
 * - 3.8: Display collection metadata and list of fields
 * - 3.12: Display collection version history
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { CollectionDetailPage } from './CollectionDetailPage'
import { I18nProvider } from '../../context/I18nContext'
import { ToastProvider } from '../../components/Toast'
import { AuthProvider } from '../../context/AuthContext'
import { ApiProvider } from '../../context/ApiContext'
import { PluginProvider } from '../../context/PluginContext'
import { mockAxios, resetMockAxios, createAxiosError } from '../../test/testUtils'
import type { Collection, CollectionVersion } from '../../types/collections'

// Mock navigate function
const mockNavigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

// Mock collection data
const mockCollection: Collection = {
  id: 'col-123',
  name: 'users',
  displayName: 'Users',
  description: 'User accounts collection',
  active: true,
  currentVersion: 3,
  fields: [
    {
      id: 'field-1',
      name: 'email',
      displayName: 'Email Address',
      type: 'string',
      required: true,
      unique: true,
      indexed: true,
      order: 1,
    },
    {
      id: 'field-2',
      name: 'name',
      displayName: 'Full Name',
      type: 'string',
      required: true,
      unique: false,
      indexed: false,
      order: 2,
    },
    {
      id: 'field-3',
      name: 'age',
      displayName: 'Age',
      type: 'number',
      required: false,
      unique: false,
      indexed: false,
      order: 3,
    },
  ],
  createdAt: '2024-01-15T10:30:00Z',
  updatedAt: '2024-01-20T14:45:00Z',
}

// Mock version history data
const mockVersions: CollectionVersion[] = [
  { id: 'ver-3', version: 3, schema: '{}', createdAt: '2024-01-20T14:45:00Z' },
  { id: 'ver-2', version: 2, schema: '{}', createdAt: '2024-01-18T09:00:00Z' },
  { id: 'ver-1', version: 1, schema: '{}', createdAt: '2024-01-15T10:30:00Z' },
]

beforeAll(() => {
  // Mock sessionStorage for auth
  const mockSessionStorage: Record<string, string> = {
    kelta_auth_tokens: JSON.stringify({
      accessToken: 'mock-access-token',
      idToken:
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0LXVzZXIiLCJlbWFpbCI6InRlc3RAdGVzdC5jb20iLCJuYW1lIjoiVGVzdCBVc2VyIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c',
      refreshToken: 'mock-refresh-token',
      expiresAt: Date.now() + 3600000,
    }),
  }

  Object.defineProperty(window, 'sessionStorage', {
    value: {
      getItem: (key: string) => mockSessionStorage[key] || null,
      setItem: (key: string, value: string) => {
        mockSessionStorage[key] = value
      },
      removeItem: (key: string) => {
        delete mockSessionStorage[key]
      },
      clear: () => {
        Object.keys(mockSessionStorage).forEach((key) => delete mockSessionStorage[key])
      },
      get length() {
        return Object.keys(mockSessionStorage).length
      },
      key: (index: number) => Object.keys(mockSessionStorage)[index] || null,
    },
    writable: true,
  })
})

// Create a fresh QueryClient for each test
function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })
}

// Test wrapper component
function TestWrapper({
  children,
  initialEntries = ['/collections/users'],
}: {
  children: React.ReactNode
  initialEntries?: string[]
}) {
  const queryClient = createTestQueryClient()
  return (
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <AuthProvider>
          <ApiProvider>
            <PluginProvider>
              <ToastProvider>
                <MemoryRouter initialEntries={initialEntries}>
                  <Routes>
                    <Route path="/collections/:id" element={children} />
                    <Route path="/collections" element={<div>Collections List</div>} />
                  </Routes>
                </MemoryRouter>
              </ToastProvider>
            </PluginProvider>
          </ApiProvider>
        </AuthProvider>
      </I18nProvider>
    </QueryClientProvider>
  )
}

/**
 * Converts a flat Collection object into a JSON:API response with included fields.
 * The CollectionDetailPage now uses apiClient.get() with ?include=fields,
 * so the mock must return proper JSON:API format with the "included" array.
 */
function toJsonApiCollectionResponse(collection: Collection) {
  const { id, fields, ...attributes } = collection
  const response: Record<string, unknown> = {
    data: { type: 'collections', id, attributes },
  }
  if (fields && fields.length > 0) {
    response.included = fields.map((f) => {
      const { id: fieldId, ...fieldAttrs } = f
      return { type: 'fields', id: fieldId, attributes: fieldAttrs }
    })
  }
  return response
}

// Default GET handler for all endpoints used by CollectionDetailPage
function createGetHandler(
  collection: Collection = mockCollection,
  versions: CollectionVersion[] = []
) {
  return (url: string) => {
    // Collection versions: /api/collection-versions?filter[collectionId][eq]=...
    if (url.match(/\/api\/collection-versions/)) {
      return Promise.resolve({ data: versions })
    }
    // Validation rules: /api/validation-rules?filter[collectionId][eq]=...
    if (url.match(/\/api\/validation-rules/)) {
      return Promise.resolve({ data: [] })
    }
    // Record types: /api/record-types?filter[collectionId][eq]=...
    if (url.match(/\/api\/record-types/)) {
      return Promise.resolve({ data: [] })
    }
    // Single collection with ?include=fields: /api/collections/{id}?include=fields
    // Uses apiClient.get() so returns raw JSON:API format with included fields
    if (url.match(/\/api\/collections\/[^?]+(\?include=|$)/) && !url.includes('filter[')) {
      return Promise.resolve({ data: toJsonApiCollectionResponse(collection) })
    }
    // Collection list (getList via useCollectionSummaries): /api/collections?filter[...]
    // getList calls unwrapJsonApiList on response.data, so return JSON:API format
    if (url.match(/\/api\/collections/)) {
      return Promise.resolve({
        data: {
          data: [],
          metadata: { totalCount: 0, currentPage: 0, pageSize: 500, totalPages: 0 },
        },
      })
    }
    // Global picklists (getList): /api/global-picklists
    if (url.match(/\/api\/global-picklists/)) {
      return Promise.resolve({
        data: {
          data: [],
          metadata: { totalCount: 0, currentPage: 0, pageSize: 20, totalPages: 0 },
        },
      })
    }
    return Promise.resolve({ data: [] })
  }
}

// Helper to setup Axios mock handlers for collection data
function setupCollectionHandlers(collection: Collection = mockCollection) {
  mockAxios.get.mockImplementation(createGetHandler(collection))
}

// Helper to setup Axios mock handlers for collection + versions
function setupCollectionAndVersionsHandlers(
  collection: Collection = mockCollection,
  versions: CollectionVersion[] = mockVersions
) {
  mockAxios.get.mockImplementation(createGetHandler(collection, versions))
}

describe('CollectionDetailPage', () => {
  beforeEach(() => {
    resetMockAxios()
    mockNavigate.mockReset()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  describe('Loading State', () => {
    it('should display loading spinner while fetching collection', async () => {
      // Setup a delayed response
      mockAxios.get.mockImplementation(
        () =>
          new Promise((resolve) =>
            setTimeout(() => resolve({ data: toJsonApiCollectionResponse(mockCollection) }), 100)
          )
      )

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      expect(screen.getByTestId('loading-spinner')).toBeInTheDocument()
      expect(screen.getByRole('status')).toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should display error message when fetch fails', async () => {
      mockAxios.get.mockRejectedValue(createAxiosError(500))

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should display not found error for 404 response', async () => {
      mockAxios.get.mockRejectedValue(createAxiosError(404))

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should allow retry on error', async () => {
      let callCount = 0
      mockAxios.get.mockImplementation((url: string) => {
        if (url.match(/\/api\/collections\/[^?]+(\?include=|$)/) && !url.includes('filter[')) {
          callCount++
          if (callCount === 1) {
            return Promise.reject(createAxiosError(500))
          }
          return Promise.resolve({ data: toJsonApiCollectionResponse(mockCollection) })
        }
        // Collection list (getList via useCollectionSummaries) - JSON:API format
        if (url.match(/\/api\/collections/)) {
          return Promise.resolve({
            data: {
              data: [],
              metadata: { totalCount: 0, currentPage: 0, pageSize: 500, totalPages: 0 },
            },
          })
        }
        return Promise.resolve({ data: {} })
      })

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })

      const retryButton = screen.getByTestId('error-message-retry')
      fireEvent.click(retryButton)

      await waitFor(() => {
        expect(screen.getByTestId('collection-title')).toBeInTheDocument()
      })
    })
  })

  describe('Collection Metadata Display', () => {
    beforeEach(() => {
      setupCollectionHandlers()
    })

    it('should display collection title', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-title')).toHaveTextContent('Users')
      })
    })

    it('should display collection name', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-name')).toHaveTextContent('users')
      })
    })

    it('should display collection display name', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-display-name')).toHaveTextContent('Users')
      })
    })

    it('should display collection status as active', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-status')).toHaveTextContent('Active')
      })
    })

    it('should display collection status as inactive', async () => {
      const inactiveCollection = { ...mockCollection, active: false }
      setupCollectionHandlers(inactiveCollection)

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-status')).toHaveTextContent('Inactive')
      })
    })

    it('should display collection version', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-version')).toHaveTextContent('3')
      })
    })

    it('should display collection description when present', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-description')).toHaveTextContent(
          'User accounts collection'
        )
      })
    })

    it('should display created and updated dates', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('collection-created')).toBeInTheDocument()
        expect(screen.getByTestId('collection-updated')).toBeInTheDocument()
      })
    })
  })

  describe('Fields List Display', () => {
    beforeEach(() => {
      setupCollectionHandlers()
    })

    it('should display fields tab by default', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('fields-panel')).toBeInTheDocument()
      })
    })

    it('should display fields table with all fields', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('fields-table')).toBeInTheDocument()
        expect(screen.getByTestId('field-row-0')).toBeInTheDocument()
        expect(screen.getByTestId('field-row-1')).toBeInTheDocument()
        expect(screen.getByTestId('field-row-2')).toBeInTheDocument()
      })
    })

    it('should display field names', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        const table = screen.getByTestId('fields-table')
        expect(within(table).getByText('email')).toBeInTheDocument()
        expect(within(table).getByText('name')).toBeInTheDocument()
        expect(within(table).getByText('age')).toBeInTheDocument()
      })
    })

    it('should display field types', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        const table = screen.getByTestId('fields-table')
        expect(within(table).getAllByText('String').length).toBeGreaterThan(0)
        expect(within(table).getByText('Number')).toBeInTheDocument()
      })
    })

    it('should display field count in tab', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('fields-tab')).toHaveTextContent('Fields (3)')
      })
    })

    it('should display add field button', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('add-field-button')).toBeInTheDocument()
      })
    })

    it('should display edit button for each field', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('edit-field-button-0')).toBeInTheDocument()
        expect(screen.getByTestId('edit-field-button-1')).toBeInTheDocument()
        expect(screen.getByTestId('edit-field-button-2')).toBeInTheDocument()
      })
    })

    it('should display empty state when no fields', async () => {
      const collectionNoFields = { ...mockCollection, fields: [] }
      setupCollectionHandlers(collectionNoFields)

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('fields-empty-state')).toBeInTheDocument()
      })
    })
  })

  describe('Version History Display', () => {
    it('should switch to versions tab when clicked', async () => {
      setupCollectionAndVersionsHandlers()

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('change-history-tab')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('change-history-tab'))

      await waitFor(() => {
        expect(screen.getByTestId('change-history-panel')).toBeInTheDocument()
      })
    })

    it('should display versions table with all versions', async () => {
      setupCollectionAndVersionsHandlers()

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('change-history-tab')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('change-history-tab'))

      await waitFor(() => {
        expect(screen.getByTestId('versions-table')).toBeInTheDocument()
        expect(screen.getByTestId('version-row-0')).toBeInTheDocument()
        expect(screen.getByTestId('version-row-1')).toBeInTheDocument()
        expect(screen.getByTestId('version-row-2')).toBeInTheDocument()
      })
    })

    it('should display view button for each version', async () => {
      setupCollectionAndVersionsHandlers()

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('change-history-tab')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('change-history-tab'))

      await waitFor(() => {
        expect(screen.getByTestId('view-version-button-0')).toBeInTheDocument()
        expect(screen.getByTestId('view-version-button-1')).toBeInTheDocument()
        expect(screen.getByTestId('view-version-button-2')).toBeInTheDocument()
      })
    })

    it('should mark current version', async () => {
      setupCollectionAndVersionsHandlers()

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('change-history-tab')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('change-history-tab'))

      await waitFor(() => {
        const currentVersionRow = screen.getByTestId('version-row-0')
        expect(within(currentVersionRow).getByText('(Current)')).toBeInTheDocument()
      })
    })
  })

  describe('Edit and Delete Actions', () => {
    beforeEach(() => {
      setupCollectionHandlers()
    })

    it('should display edit button', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('edit-button')).toBeInTheDocument()
      })
    })

    it('should navigate to edit page when edit button is clicked', async () => {
      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('edit-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button'))

      expect(mockNavigate).toHaveBeenCalledWith('/default/collections/users/edit')
    })

    it('should display delete button', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })
    })

    it('should open confirmation dialog when delete button is clicked', async () => {
      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))

      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      expect(screen.getByTestId('confirm-dialog-title')).toHaveTextContent('Delete Collection')
    })

    it('should close confirmation dialog when cancel is clicked', async () => {
      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))
      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()

      await user.click(screen.getByTestId('confirm-dialog-cancel'))

      await waitFor(() => {
        expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument()
      })
    })

    it('should call delete API and navigate when confirmed', async () => {
      // Setup handlers for collection load and delete
      setupCollectionHandlers()
      mockAxios.delete.mockResolvedValueOnce({ data: {} })

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))

      // Wait for dialog to appear
      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('confirm-dialog-confirm'))

      // Wait for navigation to happen (indicates delete was successful)
      await waitFor(
        () => {
          expect(mockNavigate).toHaveBeenCalledWith('/default/collections')
        },
        { timeout: 3000 }
      )
    })
  })

  describe('Navigation', () => {
    beforeEach(() => {
      setupCollectionHandlers()
    })

    it('should display back button', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('back-button')).toBeInTheDocument()
      })
    })

    it('should navigate to collections list when back button is clicked', async () => {
      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('back-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('back-button'))

      expect(mockNavigate).toHaveBeenCalledWith('/default/collections')
    })

    it('should open field editor modal when add field button is clicked', async () => {
      // Override get handler to also handle collections list (needed by field editor)
      mockAxios.get.mockImplementation(createGetHandler())

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('add-field-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-field-button'))

      // Field editor modal should open
      await waitFor(() => {
        expect(screen.getByTestId('field-editor')).toBeInTheDocument()
      })
    })

    it('should open field editor modal when edit field button is clicked', async () => {
      // Override get handler to also handle collections list (needed by field editor)
      mockAxios.get.mockImplementation(createGetHandler())

      const user = userEvent.setup()

      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('edit-field-button-0')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-field-button-0'))

      // Field editor modal should open with field data
      await waitFor(() => {
        expect(screen.getByTestId('field-editor')).toBeInTheDocument()
      })
    })
  })

  describe('Accessibility', () => {
    beforeEach(() => {
      setupCollectionHandlers()
    })

    it('should have proper ARIA roles for tabs', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByRole('tablist')).toBeInTheDocument()
        expect(screen.getAllByRole('tab')).toHaveLength(6)
        expect(screen.getByRole('tabpanel')).toBeInTheDocument()
      })
    })

    it('should have proper ARIA attributes for selected tab', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        const fieldsTab = screen.getByTestId('fields-tab')
        expect(fieldsTab).toHaveAttribute('aria-selected', 'true')
      })
    })

    it('should have proper ARIA labels for action buttons', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByTestId('edit-button')).toHaveAttribute('aria-label')
        expect(screen.getByTestId('delete-button')).toHaveAttribute('aria-label')
        expect(screen.getByTestId('back-button')).toHaveAttribute('aria-label')
      })
    })

    it('should have proper table roles', async () => {
      render(
        <TestWrapper>
          <CollectionDetailPage />
        </TestWrapper>
      )

      await waitFor(() => {
        const table = screen.getByTestId('fields-table')
        expect(table.tagName).toBe('TABLE')
        expect(table).toHaveAttribute('aria-label')
      })
    })
  })
})
