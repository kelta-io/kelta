/**
 * ResourceFormPage Tests
 *
 * Tests for the ResourceFormPage component including:
 * - Form generation from collection schema
 * - Create and edit modes
 * - Field type rendering
 * - Form validation
 * - Form submission
 * - Custom field renderer integration
 *
 * Requirements:
 * - 11.6: Resource browser allows creating new resources
 * - 11.9: Resource browser allows editing existing resources
 * - 12.4: Use custom field renderers when registered, fall back to defaults
 */

import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  AuthWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { ResourceFormPage } from './ResourceFormPage'
import type { CollectionSchema, Resource } from './ResourceFormPage'
import { PluginProvider } from '../../context/PluginContext'
import type { Plugin, FieldRendererProps } from '../../types/plugin'

// Mock usePageLayout to return no layout (fallback to flat form)
vi.mock('../../hooks/usePageLayout', () => ({
  usePageLayout: () => ({ layout: null, isLoading: false, error: null }),
}))

vi.mock('../../components/LayoutFormSections/LayoutFormSections', () => ({
  LayoutFormSections: () => <div data-testid="layout-form-sections">Layout Form</div>,
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

// Test data
const mockSchema: CollectionSchema = {
  id: 'col-1',
  name: 'users',
  displayName: 'Users',
  fields: [
    {
      id: 'field-1',
      name: 'name',
      displayName: 'Full Name',
      type: 'string',
      required: true,
      order: 1,
    },
    {
      id: 'field-2',
      name: 'email',
      displayName: 'Email Address',
      type: 'string',
      required: true,
      order: 2,
      validation: [{ type: 'email' }],
    },
    {
      id: 'field-3',
      name: 'age',
      displayName: 'Age',
      type: 'number',
      required: false,
      order: 3,
      validation: [
        { type: 'min', value: 0 },
        { type: 'max', value: 150 },
      ],
    },
    {
      id: 'field-4',
      name: 'active',
      displayName: 'Is Active',
      type: 'boolean',
      required: false,
      order: 4,
    },
    {
      id: 'field-5',
      name: 'birthDate',
      displayName: 'Birth Date',
      type: 'date',
      required: false,
      order: 5,
    },
    {
      id: 'field-6',
      name: 'lastLogin',
      displayName: 'Last Login',
      type: 'datetime',
      required: false,
      order: 6,
    },
    {
      id: 'field-7',
      name: 'metadata',
      displayName: 'Metadata',
      type: 'json',
      required: false,
      order: 7,
    },
    {
      id: 'field-8',
      name: 'organizationId',
      displayName: 'Organization',
      type: 'master_detail',
      required: false,
      referenceCollectionId: 'org-collection-id',
      order: 8,
    },
  ],
}

/**
 * Convert a CollectionSchema into a JSON:API response with included field records.
 * This matches the format returned by /api/collections/{name}?include=fields
 */
function toJsonApiWithFields(schema: CollectionSchema) {
  return {
    data: {
      type: 'collections',
      id: schema.id,
      attributes: {
        name: schema.name,
        displayName: schema.displayName,
        description: schema.description,
      },
    },
    included: ((schema.fields || []) as unknown as Record<string, unknown>[]).map(
      (f: Record<string, unknown>, i: number) => {
        // Spread ALL field properties into attributes to preserve validation, order, etc.
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { id: _id, referenceCollectionId: _ref, ...rest } = f as Record<string, unknown>
        return {
          type: 'fields',
          id: f.id,
          attributes: {
            ...rest,
            type: (f.type as string).toUpperCase(),
            required: f.required ?? false,
            fieldOrder: (f as Record<string, unknown>).order ?? i + 1,
            active: true,
          },
          relationships: {
            collectionId: { data: { type: 'collections', id: schema.id } },
            ...(f.referenceCollectionId
              ? {
                  referenceCollectionId: {
                    data: { type: 'collections', id: f.referenceCollectionId },
                  },
                }
              : {}),
          },
        }
      }
    ),
  }
}

const mockResource: Resource = {
  id: 'res-123',
  name: 'John Doe',
  email: 'john@example.com',
  age: 30,
  active: true,
  birthDate: '1993-05-15',
  lastLogin: '2024-01-15T10:30:00',
  metadata: { role: 'admin' },
  organizationId: 'org-456',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-15T10:30:00Z',
}

// Helper to create query client
function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })
}

// Helper to render with providers
function renderWithProviders(
  ui: React.ReactElement,
  {
    route = '/resources/users/new',
    plugins = [] as Plugin[],
    queryClient = createQueryClient(),
  }: { route?: string; plugins?: Plugin[]; queryClient?: QueryClient } = {}
) {
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <AuthWrapper>
          <PluginProvider plugins={plugins}>
            <MemoryRouter initialEntries={[route]}>
              <Routes>
                <Route path="/resources/:collection/new" element={ui} />
                <Route path="/resources/:collection/:id/edit" element={ui} />
              </Routes>
            </MemoryRouter>
          </PluginProvider>
        </AuthWrapper>
      </QueryClientProvider>
    ),
    queryClient,
  }
}

describe('ResourceFormPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    mockNavigate.mockReset()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.clearAllMocks()
  })

  describe('Loading State', () => {
    it('should display loading spinner while fetching schema', () => {
      mockAxios.get.mockImplementation(() => new Promise(() => {})) // Never resolves

      renderWithProviders(<ResourceFormPage />)

      expect(screen.getByTestId('loading-spinner')).toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should display error message when schema fetch fails', async () => {
      mockAxios.get.mockRejectedValue(createAxiosError(500))

      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should display error message when resource fetch fails in edit mode', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        // Resource fetch fails
        return Promise.reject(createAxiosError(404))
      })

      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })
  })

  describe('Create Mode', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
    })

    it('should render form with all fields from schema', async () => {
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Check all fields are rendered
      expect(screen.getByTestId('field-name')).toBeInTheDocument()
      expect(screen.getByTestId('field-email')).toBeInTheDocument()
      expect(screen.getByTestId('field-age')).toBeInTheDocument()
      expect(screen.getByTestId('field-active')).toBeInTheDocument()
      expect(screen.getByTestId('field-birthDate')).toBeInTheDocument()
      expect(screen.getByTestId('field-lastLogin')).toBeInTheDocument()
      expect(screen.getByTestId('field-metadata')).toBeInTheDocument()
      expect(screen.getByTestId('field-organizationId')).toBeInTheDocument()
    })

    it('should display page title for create mode', async () => {
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('page-title')).toHaveTextContent(/create record/i)
      })
    })

    it('should render correct input types for each field type', async () => {
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // String field - text input
      const nameInput = screen.getByTestId('field-name')
      expect(nameInput).toHaveAttribute('type', 'text')

      // Number field - number input
      const ageInput = screen.getByTestId('field-age')
      expect(ageInput).toHaveAttribute('type', 'number')

      // Boolean field - checkbox
      const activeInput = screen.getByTestId('field-active')
      expect(activeInput).toHaveAttribute('type', 'checkbox')

      // Date field - date input
      const birthDateInput = screen.getByTestId('field-birthDate')
      expect(birthDateInput).toHaveAttribute('type', 'date')

      // Datetime field - datetime-local input
      const lastLoginInput = screen.getByTestId('field-lastLogin')
      expect(lastLoginInput).toHaveAttribute('type', 'datetime-local')

      // JSON field - textarea
      const metadataInput = screen.getByTestId('field-metadata')
      expect(metadataInput.tagName.toLowerCase()).toBe('textarea')

      // Reference field - text input
      const orgInput = screen.getByTestId('field-organizationId')
      expect(orgInput).toHaveAttribute('type', 'text')
    })

    it('should mark required fields with asterisk', async () => {
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Find labels for required fields
      const nameLabel = screen.getByText('Full Name')
      const emailLabel = screen.getByText('Email Address')

      // Check for required indicator
      expect(nameLabel.parentElement).toHaveTextContent('*')
      expect(emailLabel.parentElement).toHaveTextContent('*')
    })

    it('should show validation error for required fields when submitting empty form', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Submit empty form
      const submitButton = screen.getByTestId('submit-button')
      await user.click(submitButton)

      // Check for validation errors
      await waitFor(() => {
        expect(screen.getByTestId('error-name')).toBeInTheDocument()
        expect(screen.getByTestId('error-email')).toBeInTheDocument()
      })
    })

    it('should validate email format', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Fill in name
      const nameInput = screen.getByTestId('field-name')
      await user.type(nameInput, 'John Doe')

      // Fill in invalid email
      const emailInput = screen.getByTestId('field-email')
      await user.type(emailInput, 'invalid-email')

      // Submit form
      const submitButton = screen.getByTestId('submit-button')
      await user.click(submitButton)

      // Check for email validation error
      await waitFor(() => {
        expect(screen.getByTestId('error-email')).toBeInTheDocument()
      })
    })

    it('should validate JSON format', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Fill required fields
      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')

      // Fill in invalid JSON
      const metadataInput = screen.getByTestId('field-metadata')
      await user.type(metadataInput, 'invalid json')

      // Submit form
      const submitButton = screen.getByTestId('submit-button')
      await user.click(submitButton)

      // Check for JSON validation error
      await waitFor(() => {
        expect(screen.getByTestId('error-metadata')).toBeInTheDocument()
      })
    })

    it('should submit form with valid data', async () => {
      const user = userEvent.setup()

      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
      mockAxios.post.mockResolvedValueOnce({
        data: { id: 'new-res-123', name: 'John Doe', email: 'john@example.com' },
      })

      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Fill in required fields
      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')

      // Submit form
      const submitButton = screen.getByTestId('submit-button')
      await user.click(submitButton)

      // Verify API was called
      await waitFor(() => {
        const postCalls = mockAxios.post.mock.calls.filter((call: unknown[]) =>
          (call[0] as string).includes('/api/users')
        )
        expect(postCalls.length).toBeGreaterThan(0)
      })
    })

    it('should navigate to resource detail after successful create', async () => {
      const user = userEvent.setup()

      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
      mockAxios.post.mockResolvedValueOnce({
        data: { id: 'new-res-123', name: 'John Doe', email: 'john@example.com' },
      })

      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Fill in required fields
      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')

      // Submit form
      await user.click(screen.getByTestId('submit-button'))

      // Verify navigation
      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/new-res-123')
      })
    })

    it('should navigate back to list on cancel', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Click cancel
      const cancelButton = screen.getByTestId('cancel-button')
      await user.click(cancelButton)

      // Verify navigation
      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users')
    })
  })

  describe('Edit Mode', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (url.includes('/users/res-123') && !url.includes('/api/collections')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.reject(createAxiosError(404))
      })
    })

    it('should display page title for edit mode', async () => {
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('page-title')).toHaveTextContent(/edit record/i)
      })
    })

    it('should display resource ID in edit mode', async () => {
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-id')).toHaveTextContent('res-123')
      })
    })

    it('should pre-populate form with existing resource data', async () => {
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Check pre-populated values
      expect(screen.getByTestId('field-name')).toHaveValue('John Doe')
      expect(screen.getByTestId('field-email')).toHaveValue('john@example.com')
      expect(screen.getByTestId('field-age')).toHaveValue(30)
      expect(screen.getByTestId('field-active')).toBeChecked()
      expect(screen.getByTestId('field-birthDate')).toHaveValue('1993-05-15')
      expect(screen.getByTestId('field-organizationId')).toHaveValue('org-456')
    })

    it('pre-populates when the detail page already cached the record', async () => {
      // ResourceDetailPage caches { resource, etag } under the same key; opening Edit from it
      // must read that entry as the record, not render a blank form.
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 60_000, staleTime: 60_000 } },
      })
      queryClient.setQueryData(['resource', 'users', 'res-123'], {
        resource: mockResource,
        etag: '"v1"',
      })
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
        queryClient,
      })

      await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('John Doe'))
      expect(screen.getByTestId('field-email')).toHaveValue('john@example.com')
    })

    it('should submit form with PATCH method in edit mode', async () => {
      const user = userEvent.setup()

      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (url.includes('/users/res-123') && !url.includes('/api/collections')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.reject(createAxiosError(404))
      })
      mockAxios.patch.mockResolvedValueOnce({
        data: { ...mockResource, name: 'Jane Doe' },
      })

      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Modify a field
      const nameInput = screen.getByTestId('field-name')
      await user.clear(nameInput)
      await user.type(nameInput, 'Jane Doe')

      // Submit form
      await user.click(screen.getByTestId('submit-button'))

      // Verify API was called with PATCH
      await waitFor(() => {
        const patchCalls = mockAxios.patch.mock.calls.filter((call: unknown[]) =>
          (call[0] as string).includes('/api/users/res-123')
        )
        expect(patchCalls.length).toBeGreaterThan(0)
      })
    })

    it('should navigate to resource detail after successful update', async () => {
      const user = userEvent.setup()

      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        if (url.includes('/users/res-123') && !url.includes('/api/collections')) {
          return Promise.resolve({ data: mockResource })
        }
        return Promise.reject(createAxiosError(404))
      })
      mockAxios.patch.mockResolvedValueOnce({
        data: mockResource,
      })

      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Submit form
      await user.click(screen.getByTestId('submit-button'))

      // Verify navigation
      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/res-123')
      })
    })

    it('should navigate back to detail on cancel in edit mode', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit',
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Click cancel
      await user.click(screen.getByTestId('cancel-button'))

      // Verify navigation to detail page
      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/res-123')
    })
  })

  describe('Field Type Handling', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
    })

    it('should handle boolean field toggle', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      const activeCheckbox = screen.getByTestId('field-active')
      expect(activeCheckbox).not.toBeChecked()

      await user.click(activeCheckbox)
      expect(activeCheckbox).toBeChecked()

      await user.click(activeCheckbox)
      expect(activeCheckbox).not.toBeChecked()
    })

    it('should handle number field input', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      const ageInput = screen.getByTestId('field-age')
      await user.type(ageInput, '25')
      expect(ageInput).toHaveValue(25)
    })

    it('should handle date field input', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      const dateInput = screen.getByTestId('field-birthDate')
      await user.type(dateInput, '2000-01-15')
      expect(dateInput).toHaveValue('2000-01-15')
    })
  })

  describe('Validation Rules', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
    })

    it('should validate min value for number fields', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Fill required fields
      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')

      // Enter negative age (min is 0)
      const ageInput = screen.getByTestId('field-age')
      await user.type(ageInput, '-5')

      // Submit form
      await user.click(screen.getByTestId('submit-button'))

      // Check for validation error
      await waitFor(() => {
        expect(screen.getByTestId('error-age')).toBeInTheDocument()
      })
    })

    it('should validate max value for number fields', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Fill required fields
      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')

      // Enter age over max (max is 150)
      const ageInput = screen.getByTestId('field-age')
      await user.type(ageInput, '200')

      // Submit form
      await user.click(screen.getByTestId('submit-button'))

      // Check for validation error
      await waitFor(() => {
        expect(screen.getByTestId('error-age')).toBeInTheDocument()
      })
    })

    it('should clear validation error when field is corrected', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Submit empty form to trigger validation
      await user.click(screen.getByTestId('submit-button'))

      // Check error is shown
      await waitFor(() => {
        expect(screen.getByTestId('error-name')).toBeInTheDocument()
      })

      // Fill in the field
      await user.type(screen.getByTestId('field-name'), 'John Doe')

      // Error should be cleared
      expect(screen.queryByTestId('error-name')).not.toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
    })

    it('should have proper labels for all form fields', async () => {
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Check that inputs have associated labels
      expect(screen.getByLabelText(/full name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/email address/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/age/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/is active/i)).toBeInTheDocument()
    })

    it('should have aria-invalid on fields with errors', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Submit empty form
      await user.click(screen.getByTestId('submit-button'))

      // Check aria-invalid
      await waitFor(() => {
        expect(screen.getByTestId('field-name')).toHaveAttribute('aria-invalid', 'true')
        expect(screen.getByTestId('field-email')).toHaveAttribute('aria-invalid', 'true')
      })
    })

    it('should have aria-describedby linking to error messages', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Submit empty form
      await user.click(screen.getByTestId('submit-button'))

      // Check aria-describedby
      await waitFor(() => {
        const nameInput = screen.getByTestId('field-name')
        expect(nameInput).toHaveAttribute('aria-describedby', 'field-name-error')
      })
    })

    it('should have breadcrumb navigation', async () => {
      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      const breadcrumb = screen.getByRole('navigation', { name: /breadcrumb/i })
      expect(breadcrumb).toBeInTheDocument()
    })
  })

  describe('Empty Schema', () => {
    it('should display empty state when schema has no fields', async () => {
      const emptySchema = { ...mockSchema, fields: [] }
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(emptySchema) })
        }
        return Promise.reject(createAxiosError(404))
      })

      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('no-fields')).toBeInTheDocument()
      })
    })

    it('should disable submit button when schema has no fields', async () => {
      const emptySchema = { ...mockSchema, fields: [] }
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(emptySchema) })
        }
        return Promise.reject(createAxiosError(404))
      })

      renderWithProviders(<ResourceFormPage />)

      await waitFor(() => {
        expect(screen.getByTestId('submit-button')).toBeDisabled()
      })
    })
  })

  describe('Custom Field Renderer Integration', () => {
    /**
     * Requirement 12.4: Use custom field renderers when registered, fall back to defaults
     */

    // Custom renderer component for testing
    const CustomStringRenderer: React.FC<FieldRendererProps> = ({
      name,
      value,
      onChange,
      error,
      metadata,
    }) => (
      <div data-testid={`custom-string-renderer-${name}`}>
        <input
          type="text"
          data-testid={`custom-input-${name}`}
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          className={error ? 'error' : ''}
          aria-label={`Custom ${name} input`}
        />
        {Boolean(metadata?.required) && <span data-testid="required-indicator">*</span>}
      </div>
    )

    // Custom renderer for a custom field type
    const CustomRichTextRenderer: React.FC<FieldRendererProps> = ({ name, value, onChange }) => (
      <div data-testid={`rich-text-renderer-${name}`}>
        <textarea
          data-testid={`rich-text-input-${name}`}
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          placeholder="Rich text editor"
        />
        <div data-testid="rich-text-toolbar">Bold | Italic | Underline</div>
      </div>
    )

    beforeEach(() => {
      mockAxios.get.mockImplementation((url: string) => {
        // Collection schema with included fields (JSON:API format)
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
    })

    it('should use custom renderer when registered for a field type', async () => {
      const customPlugin: Plugin = {
        id: 'test-plugin',
        name: 'Test Plugin',
        version: '1.0.0',
        fieldRenderers: {
          string: CustomStringRenderer,
        },
      }

      renderWithProviders(<ResourceFormPage />, {
        plugins: [customPlugin],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Custom renderer should be used for string fields
      expect(screen.getByTestId('custom-renderer-name')).toBeInTheDocument()
      expect(screen.getByTestId('custom-string-renderer-name')).toBeInTheDocument()
      expect(screen.getByTestId('custom-input-name')).toBeInTheDocument()
    })

    it('should fall back to default renderer for unregistered types', async () => {
      const customPlugin: Plugin = {
        id: 'test-plugin',
        name: 'Test Plugin',
        version: '1.0.0',
        fieldRenderers: {
          string: CustomStringRenderer,
        },
      }

      renderWithProviders(<ResourceFormPage />, {
        plugins: [customPlugin],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Custom renderer for string fields
      expect(screen.getByTestId('custom-renderer-name')).toBeInTheDocument()

      // Default renderer for number fields (not registered)
      const ageInput = screen.getByTestId('field-age')
      expect(ageInput).toHaveAttribute('type', 'number')
      expect(screen.queryByTestId('custom-renderer-age')).not.toBeInTheDocument()
    })

    it('should use default renderers when no plugins are registered', async () => {
      renderWithProviders(<ResourceFormPage />, {
        plugins: [],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // All fields should use default renderers
      expect(screen.getByTestId('field-name')).toHaveAttribute('type', 'text')
      expect(screen.getByTestId('field-age')).toHaveAttribute('type', 'number')
      expect(screen.getByTestId('field-active')).toHaveAttribute('type', 'checkbox')

      // No custom renderer wrappers
      expect(screen.queryByTestId('custom-renderer-name')).not.toBeInTheDocument()
    })

    it('should pass correct props to custom renderer', async () => {
      const capturedPropsMap: Map<string, FieldRendererProps> = new Map()

      const PropsCapturingRenderer: React.FC<FieldRendererProps> = (props) => {
        // Capture props for each field
        capturedPropsMap.set(props.name, { ...props })
        return (
          <input
            data-testid={`capturing-input-${props.name}`}
            value={String(props.value ?? '')}
            onChange={(e) => props.onChange(e.target.value)}
          />
        )
      }

      const customPlugin: Plugin = {
        id: 'test-plugin',
        name: 'Test Plugin',
        version: '1.0.0',
        fieldRenderers: {
          string: PropsCapturingRenderer,
        },
      }

      renderWithProviders(<ResourceFormPage />, {
        plugins: [customPlugin],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Verify props passed to custom renderer for 'name' field
      const nameProps = capturedPropsMap.get('name')
      expect(nameProps).toBeDefined()
      expect(nameProps?.name).toBe('name')
      expect(nameProps?.value).toBe('')
      expect(typeof nameProps?.onChange).toBe('function')
      expect(nameProps?.metadata).toBeDefined()
      expect(nameProps?.metadata?.type).toBe('string')
      expect(nameProps?.metadata?.required).toBe(true)

      // Verify props passed to custom renderer for 'email' field
      const emailProps = capturedPropsMap.get('email')
      expect(emailProps).toBeDefined()
      expect(emailProps?.name).toBe('email')
      expect(emailProps?.metadata?.required).toBe(true)
    })

    it('should handle value changes from custom renderer', async () => {
      const user = userEvent.setup()

      const customPlugin: Plugin = {
        id: 'test-plugin',
        name: 'Test Plugin',
        version: '1.0.0',
        fieldRenderers: {
          string: CustomStringRenderer,
        },
      }

      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(mockSchema) })
        }
        return Promise.reject(createAxiosError(404))
      })
      mockAxios.post.mockResolvedValueOnce({
        data: { id: 'new-res-123', name: 'Test Name', email: 'test@example.com' },
      })

      renderWithProviders(<ResourceFormPage />, {
        plugins: [customPlugin],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Type in custom renderer input
      const customNameInput = screen.getByTestId('custom-input-name')
      await user.type(customNameInput, 'Test Name')

      // Type in custom email input
      const customEmailInput = screen.getByTestId('custom-input-email')
      await user.type(customEmailInput, 'test@example.com')

      // Submit form
      await user.click(screen.getByTestId('submit-button'))

      // Verify API was called with the values from custom renderer
      await waitFor(() => {
        expect(mockAxios.post).toHaveBeenCalledWith('/api/users', expect.anything())
      })

      // Verify the body contains the values in JSON:API format
      const postCall = mockAxios.post.mock.calls.find((call: unknown[]) =>
        (call[0] as string).includes('/api/users')
      ) as [string, unknown] | undefined
      expect(postCall).toBeDefined()
      if (postCall) {
        const body = postCall[1] as { data: { type: string; attributes: Record<string, unknown> } }
        expect(body.data.type).toBe('users')
        expect(body.data.attributes.name).toBe('Test Name')
        expect(body.data.attributes.email).toBe('test@example.com')
      }
    })

    it('should display validation errors with custom renderer', async () => {
      const user = userEvent.setup()

      const customPlugin: Plugin = {
        id: 'test-plugin',
        name: 'Test Plugin',
        version: '1.0.0',
        fieldRenderers: {
          string: CustomStringRenderer,
        },
      }

      renderWithProviders(<ResourceFormPage />, {
        plugins: [customPlugin],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Submit empty form to trigger validation
      await user.click(screen.getByTestId('submit-button'))

      // Validation errors should still be displayed
      await waitFor(() => {
        expect(screen.getByTestId('error-name')).toBeInTheDocument()
        expect(screen.getByTestId('error-email')).toBeInTheDocument()
      })
    })

    it('should support multiple custom renderers for different field types', async () => {
      // Schema with a custom field type
      const schemaWithCustomType: CollectionSchema = {
        id: 'col-1',
        name: 'articles',
        displayName: 'Articles',
        fields: [
          {
            id: 'field-1',
            name: 'title',
            displayName: 'Title',
            type: 'string',
            required: true,
            order: 1,
          },
          {
            id: 'field-2',
            name: 'content',
            displayName: 'Content',
            type: 'json', // Using json type for rich text
            required: false,
            order: 2,
          },
        ],
      }

      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections/') && url.includes('include=fields')) {
          return Promise.resolve({ data: toJsonApiWithFields(schemaWithCustomType) })
        }
        return Promise.reject(createAxiosError(404))
      })

      const customPlugin: Plugin = {
        id: 'test-plugin',
        name: 'Test Plugin',
        version: '1.0.0',
        fieldRenderers: {
          string: CustomStringRenderer,
          json: CustomRichTextRenderer,
        },
      }

      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/articles/new',
        plugins: [customPlugin],
      })

      await waitFor(() => {
        expect(screen.getByTestId('resource-form')).toBeInTheDocument()
      })

      // Custom string renderer for title
      expect(screen.getByTestId('custom-string-renderer-title')).toBeInTheDocument()

      // Custom rich text renderer for content
      expect(screen.getByTestId('rich-text-renderer-content')).toBeInTheDocument()
      expect(screen.getByTestId('rich-text-toolbar')).toBeInTheDocument()
    })
  })
})
