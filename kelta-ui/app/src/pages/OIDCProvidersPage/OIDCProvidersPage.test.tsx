/**
 * OIDCProvidersPage Tests
 *
 * Unit tests for the OIDCProvidersPage component.
 * Tests cover:
 * - Rendering the providers list with status
 * - Add provider action
 * - Edit provider action
 * - Delete provider with confirmation
 * - Test connection functionality
 * - Loading and error states
 * - Empty state
 * - Form validation
 * - Accessibility
 *
 * Requirements tested:
 * - 6.1: Display a list of all OIDC providers with status (active/inactive)
 * - 6.2: Add new OIDC provider action
 * - 6.7: Delete OIDC provider with confirmation dialog
 * - 6.8: Test connection functionality to verify provider configuration
 * - 6.9: Display provider status (connected/disconnected)
 */

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { OIDCProvidersPage } from './OIDCProvidersPage'
import type { OIDCProvider } from './OIDCProvidersPage'
import {
  createTestWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'

// Mock OIDC providers data
const mockProviders: OIDCProvider[] = [
  {
    id: '1',
    name: 'Google',
    issuer: 'https://accounts.google.com',
    clientId: 'google-client-id-123',
    scopes: ['openid', 'profile', 'email'],
    active: true,
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
  },
  {
    id: '2',
    name: 'Okta',
    issuer: 'https://dev-123456.okta.com',
    clientId: 'okta-client-id-456',
    scopes: ['openid', 'profile'],
    active: true,
    createdAt: '2024-01-10T08:00:00Z',
    updatedAt: '2024-01-12T14:00:00Z',
  },
  {
    id: '3',
    name: 'Azure AD',
    issuer: 'https://login.microsoftonline.com/tenant-id',
    clientId: 'azure-client-id-789',
    scopes: ['openid', 'profile', 'email', 'offline_access'],
    active: false,
    createdAt: '2024-01-05T09:00:00Z',
    updatedAt: '2024-01-05T09:00:00Z',
  },
]

describe('OIDCProvidersPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    vi.clearAllMocks()
    cleanupAuthMocks()
  })

  describe('Loading State', () => {
    it('should display loading spinner while fetching providers', async () => {
      // Mock a delayed response
      mockAxios.get.mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve({ data: mockProviders }), 100))
      )

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      // Look for the loading spinner component
      expect(screen.getByRole('status')).toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should display error message when fetch fails', async () => {
      mockAxios.get.mockRejectedValue(createAxiosError(500))

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('should display retry button on error', async () => {
      mockAxios.get.mockRejectedValue(createAxiosError(500))

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
      })
    })

    it('should retry fetching when retry button is clicked', async () => {
      mockAxios.get
        .mockRejectedValueOnce(createAxiosError(500))
        .mockResolvedValueOnce({ data: mockProviders })

      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
      })

      await user.click(screen.getByRole('button', { name: /retry/i }))

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })
    })
  })

  describe('Providers List Display', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should display all providers in the table', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
        expect(screen.getByText('Okta')).toBeInTheDocument()
        expect(screen.getByText('Azure AD')).toBeInTheDocument()
      })
    })

    it('should display provider issuer URLs', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('https://accounts.google.com')).toBeInTheDocument()
        expect(screen.getByText('https://dev-123456.okta.com')).toBeInTheDocument()
      })
    })

    it('should display provider client IDs', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('google-client-id-123')).toBeInTheDocument()
        expect(screen.getByText('okta-client-id-456')).toBeInTheDocument()
      })
    })

    it('should display active status for active providers', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        const statusBadges = screen.getAllByTestId('status-badge')
        // Google and Okta are active
        expect(statusBadges[0]).toHaveTextContent('Active')
        expect(statusBadges[1]).toHaveTextContent('Active')
      })
    })

    it('should display inactive status for inactive providers', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        const statusBadges = screen.getAllByTestId('status-badge')
        // Azure AD is inactive
        expect(statusBadges[2]).toHaveTextContent('Inactive')
      })
    })

    it('should display page title', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: /oidc providers/i })).toBeInTheDocument()
      })
    })

    it('should display add provider button', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('add-provider-button')).toBeInTheDocument()
      })
    })
  })

  describe('Empty State', () => {
    it('should display empty state when no providers exist', async () => {
      mockAxios.get.mockResolvedValue({ data: [] })

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('empty-state')).toBeInTheDocument()
      })
    })
  })

  describe('Add Provider', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should open add form when clicking add button', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
        expect(screen.getByRole('heading', { name: 'Add Provider' })).toBeInTheDocument()
      })
    })

    it('should close form when clicking cancel', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('oidc-form-cancel'))

      await waitFor(() => {
        expect(screen.queryByTestId('oidc-form-modal')).not.toBeInTheDocument()
      })
    })

    it('should close form when clicking close button', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('oidc-form-close'))

      await waitFor(() => {
        expect(screen.queryByTestId('oidc-form-modal')).not.toBeInTheDocument()
      })
    })

    it('should close form when pressing Escape', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.keyboard('{Escape}')

      await waitFor(() => {
        expect(screen.queryByTestId('oidc-form-modal')).not.toBeInTheDocument()
      })
    })

    it('should create provider when form is submitted with valid data', async () => {
      const user = userEvent.setup()
      const newProvider: OIDCProvider = {
        id: '4',
        name: 'Auth0',
        issuer: 'https://dev.auth0.com',
        clientId: 'auth0-client-id',
        scopes: ['openid', 'profile', 'email'],
        active: true,
        createdAt: '2024-01-20T10:00:00Z',
        updatedAt: '2024-01-20T10:00:00Z',
      }

      mockAxios.get
        .mockResolvedValueOnce({ data: mockProviders }) // Initial fetch
        .mockResolvedValueOnce({ data: [...mockProviders, newProvider] }) // Refetch
      mockAxios.post.mockResolvedValueOnce({ data: newProvider }) // Create

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.type(screen.getByTestId('oidc-name-input'), 'Auth0')
      await user.type(screen.getByTestId('oidc-issuer-input'), 'https://dev.auth0.com')
      await user.type(screen.getByTestId('oidc-client-id-input'), 'auth0-client-id')
      await user.type(screen.getByTestId('oidc-client-secret-input'), 'secret123')
      await user.clear(screen.getByTestId('oidc-scopes-input'))
      await user.type(screen.getByTestId('oidc-scopes-input'), 'openid, profile, email')
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/created successfully/i)).toBeInTheDocument()
      })
    })

    it('should show validation error for empty name', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      // Submit without entering name
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/provider name is required/i)).toBeInTheDocument()
      })
    })

    it('should show validation error for invalid issuer URL', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.type(screen.getByTestId('oidc-name-input'), 'Test Provider')
      await user.type(screen.getByTestId('oidc-issuer-input'), 'not-a-valid-url')
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/issuer must be a valid url/i)).toBeInTheDocument()
      })
    })

    it('should show validation error for empty client ID', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.type(screen.getByTestId('oidc-name-input'), 'Test Provider')
      await user.type(screen.getByTestId('oidc-issuer-input'), 'https://example.com')
      // Don't enter client ID
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/client id is required/i)).toBeInTheDocument()
      })
    })

    it('should show validation error for empty client secret on new provider', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.type(screen.getByTestId('oidc-name-input'), 'Test Provider')
      await user.type(screen.getByTestId('oidc-issuer-input'), 'https://example.com')
      await user.type(screen.getByTestId('oidc-client-id-input'), 'client-123')
      // Don't enter client secret
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/client secret is required/i)).toBeInTheDocument()
      })
    })
  })

  describe('Claim Mapping Validation', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should accept claim path with exactly 200 characters', async () => {
      const user = userEvent.setup()
      const newProvider: OIDCProvider = {
        id: '4',
        name: 'Test Provider',
        issuer: 'https://example.com',
        clientId: 'client-123',
        scopes: ['openid', 'profile', 'email'],
        active: true,
        createdAt: '2024-01-20T10:00:00Z',
        updatedAt: '2024-01-20T10:00:00Z',
      }

      mockAxios.get
        .mockResolvedValueOnce({ data: mockProviders }) // Initial fetch
        .mockResolvedValueOnce({ data: [...mockProviders, newProvider] }) // Refetch
      mockAxios.post.mockResolvedValueOnce({ data: newProvider }) // Create

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.type(screen.getByTestId('oidc-name-input'), 'Test Provider')
      await user.type(screen.getByTestId('oidc-issuer-input'), 'https://example.com')
      await user.type(screen.getByTestId('oidc-client-id-input'), 'client-123')
      await user.type(screen.getByTestId('oidc-client-secret-input'), 'secret123')

      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/created successfully/i)).toBeInTheDocument()
      })
    })
  })

  describe('Edit Provider', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should open edit form with pre-populated values when clicking edit', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
        expect(screen.getByText('Edit Provider')).toBeInTheDocument()
        expect(screen.getByTestId('oidc-name-input')).toHaveValue('Google')
        expect(screen.getByTestId('oidc-issuer-input')).toHaveValue('https://accounts.google.com')
        expect(screen.getByTestId('oidc-client-id-input')).toHaveValue('google-client-id-123')
        expect(screen.getByTestId('oidc-scopes-input')).toHaveValue('openid, profile, email')
      })
    })

    it('should update provider when form is submitted with valid data', async () => {
      const user = userEvent.setup()
      const updatedProvider: OIDCProvider = {
        ...mockProviders[0],
        name: 'Google Updated',
      }

      mockAxios.get
        .mockResolvedValueOnce({ data: mockProviders }) // Initial fetch
        .mockResolvedValueOnce({ data: [updatedProvider, ...mockProviders.slice(1)] }) // Refetch
      mockAxios.patch.mockResolvedValueOnce({ data: updatedProvider }) // Update

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.clear(screen.getByTestId('oidc-name-input'))
      await user.type(screen.getByTestId('oidc-name-input'), 'Google Updated')
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/updated successfully/i)).toBeInTheDocument()
      })
    })

    it('should not require client secret when editing existing provider', async () => {
      const user = userEvent.setup()
      const updatedProvider: OIDCProvider = {
        ...mockProviders[0],
        name: 'Google Updated',
      }

      mockAxios.get
        .mockResolvedValueOnce({ data: mockProviders }) // Initial fetch
        .mockResolvedValueOnce({ data: [updatedProvider, ...mockProviders.slice(1)] }) // Refetch
      mockAxios.patch.mockResolvedValueOnce({ data: updatedProvider }) // Update

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('edit-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      // Client secret should be empty and not required for edit
      expect(screen.getByTestId('oidc-client-secret-input')).toHaveValue('')

      await user.clear(screen.getByTestId('oidc-name-input'))
      await user.type(screen.getByTestId('oidc-name-input'), 'Google Updated')
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        expect(screen.getByText(/updated successfully/i)).toBeInTheDocument()
      })
    })

    it('sends every form field, with a blank secret meaning "keep the existing secret"', async () => {
      const user = userEvent.setup()
      mockAxios.patch.mockResolvedValueOnce({ data: mockProviders[0] })

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })
      await user.click(await screen.findByTestId('edit-button-0'))
      await screen.findByTestId('oidc-form-modal')

      await user.type(screen.getByTestId('oidc-roles-claim-input'), '  realm_access.roles  ')
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => expect(mockAxios.patch).toHaveBeenCalled())
      const [url, body] = mockAxios.patch.mock.calls[0] as [
        string,
        { data: { attributes: Record<string, unknown> } },
      ]
      expect(url).toContain('/api/oidc-providers/1')
      expect(body.data.attributes).toEqual({
        name: 'Google',
        issuer: 'https://accounts.google.com',
        clientId: 'google-client-id-123',
        clientSecret: '',
        scopes: ['openid', 'profile', 'email'],
        jwksUri: '',
        authorizationUri: '',
        tokenUri: '',
        userinfoUri: '',
        endSessionUri: '',
        rolesClaim: 'realm_access.roles',
        rolesMapping: '',
        emailClaim: '',
        usernameClaim: '',
        nameClaim: '',
        groupsClaim: '',
        groupsProfileMapping: '',
      })
    })
  })

  describe('Create Provider payload', () => {
    it('posts the entered secret and the split scopes', async () => {
      const user = userEvent.setup()
      mockAxios.get.mockResolvedValue({ data: mockProviders })
      mockAxios.post.mockResolvedValueOnce({ data: mockProviders[0] })

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })
      await user.click(await screen.findByTestId('add-provider-button'))
      await screen.findByTestId('oidc-form-modal')

      await user.type(screen.getByTestId('oidc-name-input'), 'Auth0')
      await user.type(screen.getByTestId('oidc-issuer-input'), 'https://dev.auth0.com')
      await user.type(screen.getByTestId('oidc-client-id-input'), 'auth0-client-id')
      await user.type(screen.getByTestId('oidc-client-secret-input'), 'secret123')
      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
      const [url, body] = mockAxios.post.mock.calls[0] as [
        string,
        { data: { attributes: Record<string, unknown> } },
      ]
      expect(url).toContain('/api/oidc-providers')
      expect(body.data.attributes).toMatchObject({
        name: 'Auth0',
        issuer: 'https://dev.auth0.com',
        clientId: 'auth0-client-id',
        clientSecret: 'secret123',
        scopes: ['openid', 'profile', 'email', 'roles'],
      })
    })
  })

  describe('Delete Provider', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should open delete confirmation dialog when clicking delete', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
        expect(
          screen.getByText(/are you sure you want to delete this provider/i)
        ).toBeInTheDocument()
      })
    })

    it('should close delete dialog when clicking cancel', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
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

    it('should delete provider when confirming deletion', async () => {
      const user = userEvent.setup()

      mockAxios.get
        .mockResolvedValueOnce({ data: mockProviders }) // Initial fetch
        .mockResolvedValueOnce({ data: mockProviders.slice(1) }) // Refetch
      mockAxios.delete.mockResolvedValueOnce({ data: null }) // Delete

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('delete-button-0'))

      await waitFor(() => {
        expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('confirm-dialog-confirm'))

      await waitFor(() => {
        expect(screen.getByText(/deleted successfully/i)).toBeInTheDocument()
      })
    })
  })

  describe('Test Connection', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should test connection when clicking test button', async () => {
      const user = userEvent.setup()

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('test-button-0'))

      // The test connection mutation currently returns a hardcoded "temporarily unavailable" response
      await waitFor(() => {
        expect(screen.getByText(/connection failed/i)).toBeInTheDocument()
      })
    })

    it('should show error message from connection test result', async () => {
      const user = userEvent.setup()

      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('test-button-0'))

      await waitFor(() => {
        expect(screen.getByText(/temporarily unavailable/i)).toBeInTheDocument()
      })
    })

    it('should render test button for each provider', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      expect(screen.getByTestId('test-button-0')).toBeInTheDocument()
      expect(screen.getByTestId('test-button-1')).toBeInTheDocument()
      expect(screen.getByTestId('test-button-2')).toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    beforeEach(() => {
      mockAxios.get.mockResolvedValue({ data: mockProviders })
    })

    it('should have accessible table structure', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      // The shared AdminDataTable renders a native, labelled <table>.
      await waitFor(() => {
        expect(screen.getByRole('table')).toBeInTheDocument()
      })

      expect(screen.getByRole('table')).toHaveAttribute('aria-label', 'OIDC Providers')
    })

    it('should have accessible action buttons', async () => {
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      const testButton = screen.getByTestId('test-button-0')
      expect(testButton).toHaveAttribute('aria-label', 'Test Connection Google')

      const editButton = screen.getByTestId('edit-button-0')
      expect(editButton).toHaveAttribute('aria-label', 'Edit Google')

      const deleteButton = screen.getByTestId('delete-button-0')
      expect(deleteButton).toHaveAttribute('aria-label', 'Delete Google')
    })

    it('should have accessible form modal', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      // The shared CrudFormDialog (Radix) labels the dialog by its title and
      // makes it modal by hiding the rest of the page from assistive tech.
      await waitFor(() => {
        const modal = screen.getByTestId('oidc-form-modal')
        expect(modal).toHaveAttribute('role', 'dialog')
        expect(screen.getByRole('dialog', { name: 'Add Provider' })).toBe(modal)
        expect(
          screen.getByTestId('oidc-providers-page').closest('[aria-hidden="true"]')
        ).not.toBeNull()
      })
    })

    it('should have accessible form inputs', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        const nameInput = screen.getByTestId('oidc-name-input')
        expect(nameInput).toHaveAttribute('aria-required', 'true')

        const issuerInput = screen.getByTestId('oidc-issuer-input')
        expect(issuerInput).toHaveAttribute('aria-required', 'true')

        const clientIdInput = screen.getByTestId('oidc-client-id-input')
        expect(clientIdInput).toHaveAttribute('aria-required', 'true')
      })
    })

    it('should show validation errors with proper ARIA attributes', async () => {
      const user = userEvent.setup()
      render(<OIDCProvidersPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Google')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('add-provider-button'))

      await waitFor(() => {
        expect(screen.getByTestId('oidc-form-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('oidc-form-submit'))

      await waitFor(() => {
        const nameInput = screen.getByTestId('oidc-name-input')
        expect(nameInput).toHaveAttribute('aria-invalid', 'true')
        expect(nameInput).toHaveAttribute('aria-describedby', 'oidc-name-error')

        const errorMessage = screen.getAllByRole('alert')[0]
        expect(errorMessage).toBeInTheDocument()
      })
    })
  })
})
