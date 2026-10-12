/**
 * ResourceDetailPage Tests
 *
 * Tests for the ResourceDetailPage component including:
 * - Loading states
 * - Error states
 * - Field value display with formatting
 * - Edit and delete actions
 * - Navigation
 *
 * Requirements:
 * - 11.7: Resource browser displays resource detail view
 * - 11.8: Resource browser allows viewing all field values
 * - 11.10: Resource browser allows deleting resources with confirmation
 */

import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter, Routes, Route, BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  AuthWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { ResourceDetailPage } from './ResourceDetailPage'
import type { Resource } from './ResourceDetailPage'
import type { CollectionSchema } from '../../hooks/useCollectionSchema'

// Mock the I18nContext
vi.mock('../../context/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, string>) => {
      const translations: Record<string, string> = {
        'common.loading': 'Loading...',
        'common.back': 'Back',
        'common.edit': 'Edit',
        'common.delete': 'Delete',
        'common.cancel': 'Cancel',
        'common.yes': 'Yes',
        'common.no': 'No',
        'common.noData': 'No data available',
        'resources.title': 'Resource Browser',
        'resources.viewRecord': 'View Record',
        'resources.editRecord': 'Edit Record',
        'resources.deleteRecord': 'Delete Record',
        'resources.confirmDelete': 'Are you sure you want to delete this record?',
        'resources.record': 'Record',
        'collections.fields': 'Fields',
        'collections.created': 'Created',
        'collections.updated': 'Updated',
        'fields.types.string': 'String',
        'fields.types.number': 'Number',
        'fields.types.boolean': 'Boolean',
        'fields.types.date': 'Date',
        'fields.types.datetime': 'Date & Time',
        'fields.types.json': 'JSON',
        'fields.types.reference': 'Reference',
        'errors.generic': 'An error occurred',
        'errors.notFound': 'Not found',
        'success.deleted': params?.item
          ? `${params.item} deleted successfully`
          : 'Deleted successfully',
      }
      return translations[key] || key
    },
    formatDate: (date: Date, options?: Intl.DateTimeFormatOptions) => {
      return date.toLocaleDateString('en-US', options)
    },
    formatNumber: (num: number) => num.toLocaleString('en-US'),
    direction: 'ltr' as const,
    locale: 'en',
    setLocale: vi.fn(),
  }),
  I18nProvider: ({ children }: { children: React.ReactNode }) => children,
}))

// Mock the Toast hook
const mockShowToast = vi.fn()
vi.mock('../../components', () => ({
  useToast: () => ({
    showToast: mockShowToast,
  }),
  ConfirmDialog: ({
    open,
    title,
    message,
    onConfirm,
    onCancel,
  }: {
    open: boolean
    title: string
    message: string
    confirmLabel?: string
    cancelLabel?: string
    onConfirm: () => void
    onCancel: () => void
    variant?: string
  }) =>
    open ? (
      <div data-testid="confirm-dialog" role="dialog">
        <h2>{title}</h2>
        <p>{message}</p>
        <button onClick={onCancel}>Cancel</button>
        <button onClick={onConfirm}>Confirm</button>
      </div>
    ) : null,
  LoadingSpinner: ({ label }: { label?: string; size?: string }) => (
    <div data-testid="loading-spinner">{label || 'Loading...'}</div>
  ),
  ErrorMessage: ({ error, onRetry }: { error: Error; onRetry?: () => void }) => (
    <div data-testid="error-message">
      <p>{error.message}</p>
      {onRetry && <button onClick={onRetry}>Retry</button>}
    </div>
  ),
}))

// Mock navigate
const mockNavigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

// Mock the new T6-T9 components to isolate ResourceDetailPage tests
vi.mock('../../components/RecordHeader/RecordHeader', () => ({
  RecordHeader: ({
    record,
    schema,
  }: {
    record: { id: string }
    schema: { displayName: string }
  }) => (
    <div data-testid="record-header">
      <h1 data-testid="record-header-name">{schema.displayName}</h1>
      <span data-testid="resource-id">{record.id}</span>
    </div>
  ),
}))

vi.mock('../../components/RecordActionsBar/RecordActionsBar', () => ({
  RecordActionsBar: ({
    onEdit,
    onDelete,
    onBack,
    onToggleFavorite,
    isFavorite,
  }: {
    collectionName: string
    recordId: string
    onEdit: () => void
    onDelete: () => void
    onBack: () => void
    onToggleFavorite: () => void
    isFavorite: boolean
    apiClient: unknown
  }) => (
    <div data-testid="record-actions-bar">
      <button data-testid="back-button" aria-label="Back" onClick={onBack}>
        Back
      </button>
      <button data-testid="edit-button" aria-label="Edit Record" onClick={onEdit}>
        Edit
      </button>
      <button data-testid="delete-button" aria-label="Delete Record" onClick={onDelete}>
        Delete
      </button>
      <button data-testid="favorite-button" onClick={onToggleFavorite}>
        {isFavorite ? 'Unfav' : 'Fav'}
      </button>
    </div>
  ),
}))

vi.mock('../../components/ActivityTimeline/ActivityTimeline', () => ({
  ActivityTimeline: () => <div data-testid="activity-timeline">Activity</div>,
}))

vi.mock('../../components/NotesSection/NotesSection', () => ({
  NotesSection: () => <div data-testid="notes-section">Notes</div>,
}))

vi.mock('../../components/AttachmentsSection/AttachmentsSection', () => ({
  AttachmentsSection: () => <div data-testid="attachments-section">Attachments</div>,
}))

// Mock usePageLayout to return no layout (fallback to flat grid)
vi.mock('../../hooks/usePageLayout', () => ({
  usePageLayout: () => ({ layout: null, isLoading: false, error: null }),
}))

vi.mock('../../components/LayoutFieldSections/LayoutFieldSections', () => ({
  LayoutFieldSections: () => <div data-testid="layout-field-sections">Layout Fields</div>,
}))

// Sample test data
const mockSchema: CollectionSchema = {
  id: 'col-1',
  name: 'users',
  displayName: 'Users',
  fields: [
    { id: 'f1', name: 'name', displayName: 'Full Name', type: 'string', required: true },
    { id: 'f2', name: 'email', displayName: 'Email Address', type: 'string', required: true },
    { id: 'f3', name: 'age', displayName: 'Age', type: 'number', required: false },
    { id: 'f4', name: 'active', displayName: 'Is Active', type: 'boolean', required: false },
    { id: 'f5', name: 'birthDate', displayName: 'Birth Date', type: 'date', required: false },
    { id: 'f6', name: 'lastLogin', displayName: 'Last Login', type: 'datetime', required: false },
    { id: 'f7', name: 'metadata', displayName: 'Metadata', type: 'json', required: false },
    {
      id: 'f8',
      name: 'organizationId',
      displayName: 'Organization',
      type: 'master_detail',
      required: false,
      referenceCollectionId: 'org-collection-id',
    },
  ],
}

const mockResource: Resource = {
  id: 'res-123',
  name: 'John Doe',
  email: 'john@example.com',
  age: 30,
  active: true,
  birthDate: '1993-05-15',
  lastLogin: '2024-01-15T10:30:00Z',
  metadata: { role: 'admin', department: 'Engineering' },
  organizationId: 'org-456',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-15T12:00:00Z',
}

// Helper to create a fresh QueryClient for each test
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

/**
 * Convert a CollectionSchema into a JSON:API response with included field records.
 */
function toJsonApiWithFields(schema: {
  id: string
  name: string
  displayName: string
  trackHistory?: boolean
  fields: ReadonlyArray<Record<string, unknown> | object>
}) {
  return {
    data: {
      type: 'collections',
      id: schema.id,
      attributes: {
        name: schema.name,
        displayName: schema.displayName,
        trackHistory: schema.trackHistory ?? false,
      },
    },
    included: (schema.fields || []).map((rawF, i) => {
      const f = rawF as Record<string, unknown>
      // Spread ALL field properties into attributes to preserve validation, order, etc.
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { id: _id, referenceCollectionId: _ref, ...rest } = f
      return {
        type: 'fields',
        id: f.id,
        attributes: {
          ...rest,
          type: (f.type as string).toUpperCase(),
          required: f.required ?? false,
          fieldOrder: f.order ?? i + 1,
          active: true,
        },
        relationships: {
          collectionId: { data: { type: 'collections', id: schema.id } },
          ...(f.referenceCollectionId
            ? {
                referenceCollectionId: {
                  data: { type: 'collections', id: f.referenceCollectionId as string },
                },
              }
            : {}),
        },
      }
    }),
  }
}

// Helper to render with providers
function renderWithProviders(
  ui: React.ReactElement,
  { route = '/resources/users/res-123', queryClient = createTestQueryClient() } = {}
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <AuthWrapper>
          <Routes>
            <Route path="/resources/:collection/:id" element={ui} />
            <Route path="/resources/:collection" element={<div>List Page</div>} />
            <Route path="/resources" element={<div>Browser Page</div>} />
          </Routes>
        </AuthWrapper>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('ResourceDetailPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.clearAllMocks()
  })

  describe('Loading State', () => {
    it('should display loading spinner while fetching data', async () => {
      // Mock axios to delay response (never resolves)
      mockAxios.get.mockImplementation(() => new Promise(() => {}))

      renderWithProviders(<ResourceDetailPage />)

      expect(screen.getByTestId('loading-spinner')).toBeInTheDocument()
    })
  })

  describe('Error States', () => {
    it('should display error message when schema fetch fails', async () => {
      mockAxios.get.mockRejectedValueOnce(createAxiosError(500))

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should display error message when resource fetch fails', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(500))
      })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should display not found error when resource returns 404', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })
  })

  describe('Successful Data Display', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        // Lookup: fetch target collection schema by ID for master_detail resolution
        if (url.includes('/api/collections/org-collection-id')) {
          return Promise.resolve({
            data: {
              id: 'org-collection-id',
              name: 'organizations',
              displayName: 'Organizations',
              fields: [{ name: 'name', type: 'STRING' }],
            },
          })
        }
        // Lookup: fetch target collection records for display name resolution
        if (url.includes('/api/organizations')) {
          return Promise.resolve({
            data: {
              data: [{ id: 'org-456', attributes: { name: 'Acme Corp' } }],
            },
          })
        }
        // getList for notes, attachments, record-shares — return empty flat arrays
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        // get('/api/users/res-123') — return flat resource for apiClient.get()
        if (url.includes('/api/')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.resolve({ data: [] })
      })
    })

    it('should display the record header', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('record-header')).toBeInTheDocument()
      })
    })

    it('should display the resource ID', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-id')).toHaveTextContent('res-123')
      })
    })

    it('should display breadcrumb navigation', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        const breadcrumb = screen.getByRole('navigation', { name: 'Breadcrumb' })
        expect(breadcrumb).toBeInTheDocument()
        expect(screen.getByText('Resource Browser')).toBeInTheDocument()
      })
    })

    it('should display all field values - Requirement 11.8', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('fields-grid')).toBeInTheDocument()
      })

      // Check string field
      expect(screen.getByTestId('field-value-name')).toHaveTextContent('John Doe')
      expect(screen.getByTestId('field-value-email')).toHaveTextContent('john@example.com')

      // Check number field
      expect(screen.getByTestId('field-value-age')).toHaveTextContent('30')

      // Check boolean field
      expect(screen.getByTestId('field-value-active')).toHaveTextContent('Yes')

      // Check reference field
      expect(screen.getByTestId('field-value-organizationId')).toHaveTextContent('org-456')
    })

    it('should display field type badges', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('fields-grid')).toBeInTheDocument()
      })

      // Check that field types are displayed
      expect(screen.getAllByText('String').length).toBeGreaterThan(0)
      expect(screen.getByText('Number')).toBeInTheDocument()
      expect(screen.getByText('Boolean')).toBeInTheDocument()
    })

    it('should display system information panel with timestamps', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('system-created-at')).toBeInTheDocument()
        expect(screen.getByTestId('system-updated-at')).toBeInTheDocument()
        expect(screen.getByTestId('system-internal-id')).toBeInTheDocument()
      })
    })

    it('should format JSON fields properly', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        const jsonField = screen.getByTestId('field-value-metadata')
        expect(jsonField).toBeInTheDocument()
        // JSON should be formatted
        expect(jsonField.textContent).toContain('role')
        expect(jsonField.textContent).toContain('admin')
      })
    })

    it('should render master_detail fields as links with display names', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        const referenceField = screen.getByTestId('field-value-organizationId')
        const link = referenceField.querySelector('a')
        expect(link).toBeInTheDocument()
        expect(link).toHaveAttribute('href', '/default/resources/organizations/org-456')
        // Should display the resolved name, not the raw UUID
        expect(link).toHaveTextContent('Acme Corp')
      })
    })
  })

  describe('Navigation', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.resolve({ data: [] })
      })
    })

    it('should navigate back to list when back button is clicked', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('back-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('back-button'))

      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users')
    })

    it('should navigate to edit page when edit button is clicked', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('edit-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button'))

      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/res-123/edit')
    })
  })

  describe('Delete Functionality - Requirement 11.10', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.resolve({ data: [] })
      })
    })

    it('should open confirmation dialog when delete button is clicked', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))

      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      expect(screen.getByText('Delete Record')).toBeInTheDocument()
    })

    it('should close dialog when cancel is clicked', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))
      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()

      await user.click(screen.getByText('Cancel'))
      expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument()
    })

    it('should delete resource and navigate when confirmed', async () => {
      const user = userEvent.setup()

      // Set up delete response mock
      mockAxios.delete.mockResolvedValueOnce({ data: {} })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))
      await user.click(screen.getByText('Confirm'))

      await waitFor(() => {
        expect(mockShowToast).toHaveBeenCalledWith('Record deleted successfully', 'success')
        expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users')
      })
    })

    it('invalidates the list cache the admin list reads, so it drops the deleted row', async () => {
      const user = userEvent.setup()
      mockAxios.delete.mockResolvedValueOnce({ data: {} })
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 60_000, staleTime: 60_000 } },
      })
      queryClient.setQueryData(['collection-records', 'users', 1, 25], { data: [mockResource] })

      renderWithProviders(<ResourceDetailPage />, { queryClient })
      await user.click(await screen.findByTestId('delete-button'))
      await user.click(screen.getByText('Confirm'))

      await waitFor(() =>
        expect(
          queryClient.getQueryState(['collection-records', 'users', 1, 25])?.isInvalidated
        ).toBe(true)
      )
    })

    it('should show error toast when delete fails', async () => {
      const user = userEvent.setup()

      // Set up failed delete response mock
      mockAxios.delete.mockRejectedValueOnce(createAxiosError(500))

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button'))
      await user.click(screen.getByText('Confirm'))

      await waitFor(() => {
        expect(mockShowToast).toHaveBeenCalledWith(expect.any(String), 'error')
      })
    })
  })

  describe('Empty/Null Field Values', () => {
    it('should display placeholder for null values', async () => {
      const resourceWithNulls: Resource = {
        id: 'res-456',
        name: 'Test User',
        email: null as unknown as string,
        age: undefined as unknown as number,
      }

      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: resourceWithNulls })
        }
        return Promise.resolve({ data: [] })
      })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('fields-grid')).toBeInTheDocument()
      })

      // Null/undefined values should show placeholder
      expect(screen.getByTestId('field-value-email')).toHaveTextContent('-')
      expect(screen.getByTestId('field-value-age')).toHaveTextContent('-')
    })
  })

  describe('Boolean Field Display', () => {
    it('should display "No" for false boolean values', async () => {
      const resourceWithFalse: Resource = {
        ...mockResource,
        active: false,
      }

      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: resourceWithFalse })
        }
        return Promise.resolve({ data: [] })
      })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('field-value-active')).toHaveTextContent('No')
      })
    })
  })

  describe('Props Override', () => {
    it('should use props over route params when provided', async () => {
      const productsSchema = { ...mockSchema, name: 'products', displayName: 'Products' }
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(productsSchema) })
        }
        if (url.includes('/api/collections/')) {
          return Promise.resolve({ data: productsSchema })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: { ...mockResource, id: 'prod-789' } })
        }
        return Promise.resolve({ data: [] })
      })

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <BrowserRouter>
            <AuthWrapper>
              <ResourceDetailPage collectionName="products" resourceId="prod-789" />
            </AuthWrapper>
          </BrowserRouter>
        </QueryClientProvider>
      )

      await waitFor(() => {
        expect(screen.getByTestId('record-header')).toBeInTheDocument()
        expect(screen.getByTestId('resource-id')).toHaveTextContent('prod-789')
      })
    })
  })

  // Regression guard: the page used to fork its own CollectionSchema/fetcher,
  // both of which dropped `trackHistory`, leaving the History tab permanently
  // dead. It now consumes the shared useCollectionSchema hook.
  describe('History Tab', () => {
    function mockApiWithSchema(schema: CollectionSchema) {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(schema) })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.resolve({ data: [] })
      })
    }

    it('should show the History tab when the collection has trackHistory enabled', async () => {
      mockApiWithSchema({ ...mockSchema, trackHistory: true })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('detail-tab-history')).toBeInTheDocument()
      })
    })

    it('should hide the History tab when trackHistory is disabled', async () => {
      mockApiWithSchema({ ...mockSchema, trackHistory: false })

      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('detail-tab-system')).toBeInTheDocument()
      })
      expect(screen.queryByTestId('detail-tab-history')).not.toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (
          url.includes('/api/notes') ||
          url.includes('/api/attachments') ||
          url.includes('/api/record-shares')
        ) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.resolve({ data: [] })
      })
    })

    it('should have proper aria labels on buttons', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByTestId('back-button')).toHaveAttribute('aria-label', 'Back')
        expect(screen.getByTestId('edit-button')).toHaveAttribute('aria-label', 'Edit Record')
        expect(screen.getByTestId('delete-button')).toHaveAttribute('aria-label', 'Delete Record')
      })
    })

    it('should have proper breadcrumb navigation', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        const breadcrumb = screen.getByRole('navigation', { name: 'Breadcrumb' })
        expect(breadcrumb).toBeInTheDocument()
      })
    })

    it('should have proper section headings', async () => {
      renderWithProviders(<ResourceDetailPage />)

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: 'Fields' })).toBeInTheDocument()
        expect(screen.getByTestId('detail-tab-system')).toBeInTheDocument()
      })
    })
  })
})
