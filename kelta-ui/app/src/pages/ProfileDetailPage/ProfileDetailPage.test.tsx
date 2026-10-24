/**
 * ProfileDetailPage Tests
 *
 * Unit tests for the ProfileDetailPage component.
 * Tests cover:
 * - Rendering profile detail with basic info
 * - System permissions display and editing
 * - Object permissions display
 * - Delete functionality
 * - System profile protection
 * - Loading and error states
 */

import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ProfileDetailPage } from './ProfileDetailPage'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'

// Mock TenantContext
vi.mock('../../context/TenantContext', () => ({
  TenantProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useTenant: () => ({
    tenantSlug: 'test-tenant',
    tenantBasePath: '/test-tenant',
  }),
  getTenantSlug: () => 'test-tenant',
  setResolvedTenantId: vi.fn(),
  getResolvedTenantId: () => null,
}))

// Mock useParams and useNavigate
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useParams: () => ({ id: 'prof1' }),
    useNavigate: () => vi.fn(),
  }
})

// JSON:API formatted profile response with included permissions
function makeProfileResponse(
  profile: { id: string; name: string; description: string; isSystem: boolean },
  sysPerms: Array<{
    id: string
    profileId: string
    permissionName: string
    granted: boolean
  }> = mockSysPerms,
  objPerms: Array<{
    id: string
    profileId: string
    collectionId: string
    canCreate: boolean
    canRead: boolean
    canEdit: boolean
    canDelete: boolean
  }> = mockObjPerms
) {
  return {
    data: {
      type: 'profiles',
      id: profile.id,
      attributes: {
        name: profile.name,
        description: profile.description,
        isSystem: profile.isSystem,
        createdAt: '2024-01-15T10:00:00Z',
        updatedAt: '2024-01-15T10:00:00Z',
      },
    },
    included: [
      ...sysPerms.map((sp) => ({
        type: 'profile-system-permissions',
        id: sp.id,
        attributes: {
          permissionName: sp.permissionName,
          granted: sp.granted,
        },
        relationships: {
          profileId: { data: { type: 'profiles', id: sp.profileId } },
        },
      })),
      ...objPerms.map((op) => ({
        type: 'profile-object-permissions',
        id: op.id,
        attributes: {
          canCreate: op.canCreate,
          canRead: op.canRead,
          canEdit: op.canEdit,
          canDelete: op.canDelete,
        },
        relationships: {
          profileId: { data: { type: 'profiles', id: op.profileId } },
          collectionId: { data: { type: 'collections', id: op.collectionId } },
        },
      })),
    ],
  }
}

const mockProfileAttrs = {
  id: 'prof1',
  name: 'Standard User',
  description: 'Default profile for standard users',
  isSystem: false,
}

const mockSystemProfileAttrs = {
  id: 'prof2',
  name: 'System Admin',
  description: 'Default profile for standard users',
  isSystem: true,
}

const mockSysPerms = [
  { id: 'sp1', profileId: 'prof1', permissionName: 'VIEW_SETUP', granted: true },
  { id: 'sp2', profileId: 'prof1', permissionName: 'MANAGE_USERS', granted: true },
  { id: 'sp3', profileId: 'prof1', permissionName: 'API_ACCESS', granted: false },
]

const mockObjPerms = [
  {
    id: 'op1',
    profileId: 'prof1',
    collectionId: 'col1',
    canCreate: true,
    canRead: true,
    canEdit: true,
    canDelete: false,
  },
]

const mockCollections = {
  content: [{ id: 'col1', name: 'accounts', displayName: 'Accounts' }],
  totalElements: 1,
  totalPages: 1,
  size: 1000,
  number: 0,
}

/**
 * Field pages as the gateway actually serves them: `collectionId` lives in
 * `relationships`, never in `attributes`, and the HTTP page size clamps at 200
 * so a large tenant spans several pages.
 */
interface MockFieldPage {
  metadata: { totalPages: number }
  data: Array<Record<string, unknown>>
}

function makeField(id: string, name: string, collectionId: string | null): Record<string, unknown> {
  return {
    type: 'fields',
    id,
    attributes: { name, type: 'TEXT' },
    relationships: {
      collectionId: collectionId
        ? { data: { type: 'collections', id: collectionId } }
        : { data: null },
    },
  }
}

const mockFieldPages: MockFieldPage[] = [
  { metadata: { totalPages: 2 }, data: [makeField('f1', 'email', 'col1')] },
  { metadata: { totalPages: 2 }, data: [makeField('f2', 'phone', 'col1')] },
]

function setupMocks(profileAttrs = mockProfileAttrs, fieldPages: MockFieldPage[] = []) {
  mockAxios.get.mockImplementation((url: string) => {
    if (url.includes('/api/fields')) {
      const match = /page\[number\]=(\d+)/.exec(url)
      const pageNumber = match ? Number(match[1]) : 1
      return Promise.resolve({
        data: fieldPages[pageNumber - 1] ?? { metadata: { totalPages: 1 } },
      })
    }
    if (url.includes('/api/collections')) return Promise.resolve({ data: mockCollections })
    if (url.includes('/api/profiles/'))
      return Promise.resolve({ data: makeProfileResponse(profileAttrs) })
    if (url.includes('/custom-rules')) return Promise.resolve({ data: [] })
    return Promise.resolve({ data: {} })
  })
}

describe('ProfileDetailPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('renders loading state initially', () => {
    mockAxios.get.mockReturnValue(new Promise(() => {}))
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    expect(screen.getByTestId('loading-spinner-label')).toBeInTheDocument()
  })

  it('renders error state with retry', async () => {
    mockAxios.get.mockRejectedValue(new Error('Network error'))
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByTestId('error-message')).toBeInTheDocument()
    })
    expect(screen.getByTestId('error-message-retry')).toBeInTheDocument()
  })

  it('renders profile detail with basic info', async () => {
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    expect(screen.getByText('Default profile for standard users')).toBeInTheDocument()
  })

  it('renders system badge for system profiles', async () => {
    setupMocks(mockSystemProfileAttrs)
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('System Admin')).toBeInTheDocument()
    })
    expect(screen.getByTestId('system-badge')).toBeInTheDocument()
  })

  it('renders system permissions section', async () => {
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('System Permissions')).toBeInTheDocument()
    })
    expect(screen.getByTestId('system-permissions-section')).toBeInTheDocument()
  })

  it('renders a profile whose response carries no included grants', async () => {
    // The worker drops included grant rows the caller may not read (PLT-657), and an
    // envelope whose includes were all dropped has no `included` member at all.
    mockAxios.get.mockImplementation((url: string) => {
      if (url.includes('/api/collections')) return Promise.resolve({ data: mockCollections })
      if (url.includes('/api/profiles/')) {
        const { data } = makeProfileResponse(mockProfileAttrs, [], [])
        return Promise.resolve({ data: { data } })
      }
      return Promise.resolve({ data: {} })
    })
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    expect(screen.getByTestId('system-permissions-section')).toBeInTheDocument()
  })

  it('shows edit permissions button for non-system profiles', async () => {
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    expect(screen.getByTestId('edit-permissions-button')).toBeInTheDocument()
  })

  it('hides edit permissions button for system profiles', async () => {
    setupMocks(mockSystemProfileAttrs)
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('System Admin')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('edit-permissions-button')).not.toBeInTheDocument()
  })

  it('shows save/cancel buttons when editing permissions', async () => {
    const user = userEvent.setup()
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    await user.click(screen.getByTestId('edit-permissions-button'))
    expect(screen.getByTestId('save-permissions-button')).toBeInTheDocument()
    expect(screen.getByTestId('cancel-edit-button')).toBeInTheDocument()
    expect(screen.queryByTestId('edit-permissions-button')).not.toBeInTheDocument()
  })

  it('cancels editing and hides save/cancel buttons', async () => {
    const user = userEvent.setup()
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    await user.click(screen.getByTestId('edit-permissions-button'))
    expect(screen.getByTestId('save-permissions-button')).toBeInTheDocument()
    await user.click(screen.getByTestId('cancel-edit-button'))
    expect(screen.queryByTestId('save-permissions-button')).not.toBeInTheDocument()
    expect(screen.getByTestId('edit-permissions-button')).toBeInTheDocument()
  })

  it('shows delete button for non-system profiles', async () => {
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    expect(screen.getByTestId('delete-button')).toBeInTheDocument()
  })

  it('hides delete button for system profiles', async () => {
    setupMocks(mockSystemProfileAttrs)
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('System Admin')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('delete-button')).not.toBeInTheDocument()
  })

  it('opens delete confirmation dialog', async () => {
    const user = userEvent.setup()
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    await user.click(screen.getByTestId('delete-button'))
    await waitFor(() => {
      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
    })
  })

  it('has back link to profiles list', async () => {
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    expect(screen.getByTestId('back-link')).toBeInTheDocument()
    expect(screen.getByText('Back to Profiles')).toBeInTheDocument()
  })

  it('renders object permissions section with collection names', async () => {
    setupMocks()
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByTestId('object-permissions-section')).toBeInTheDocument()
    })
  })

  it('reads each field collection from the JSON:API relationship, not attributes', async () => {
    setupMocks(mockProfileAttrs, mockFieldPages)
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByTestId('field-permissions-section')).toBeInTheDocument()
    })
    // A field whose collectionId only exists as a relationship must still land in
    // a real collection — an empty id would blow up the Radix <SelectItem />.
    expect(screen.getByTestId('field-permissions-collection-select')).toBeInTheDocument()
    expect(screen.queryByTestId('field-permissions-no-collections')).not.toBeInTheDocument()
    expect(screen.getByText('email')).toBeInTheDocument()
  })

  it('walks every page of fields instead of relying on an oversized page size', async () => {
    setupMocks(mockProfileAttrs, mockFieldPages)
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByTestId('field-permissions-section')).toBeInTheDocument()
    })
    // `phone` only exists on page 2 — a single clamped request would drop it.
    await waitFor(() => {
      expect(screen.getByText('phone')).toBeInTheDocument()
    })
    const fieldRequests = mockAxios.get.mock.calls
      .map((call) => call[0] as string)
      .filter((url) => url.includes('/api/fields'))
    expect(fieldRequests).toHaveLength(2)
    expect(fieldRequests[0]).toContain('page[size]=200')
    expect(fieldRequests[1]).toContain('page[number]=2')
  })

  it('drops fields that carry no collection id', async () => {
    setupMocks(mockProfileAttrs, [
      {
        metadata: { totalPages: 1 },
        data: [
          {
            type: 'fields',
            id: 'f-orphan',
            attributes: { name: 'orphan', type: 'TEXT' },
            relationships: { collectionId: { data: null } },
          },
        ],
      },
    ])
    render(<ProfileDetailPage />, { wrapper: createTestWrapper() })
    await waitFor(() => {
      expect(screen.getByText('Standard User')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('field-permissions-section')).not.toBeInTheDocument()
    expect(screen.queryByText('orphan')).not.toBeInTheDocument()
  })
})
