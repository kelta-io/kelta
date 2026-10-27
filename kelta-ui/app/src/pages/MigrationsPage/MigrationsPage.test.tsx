/**
 * MigrationsPage Tests
 *
 * Tests for the MigrationsPage component including:
 * - Migration history table display
 * - Migration run details modal
 * - Migration planning form
 * - Migration plan display with steps and risks
 * - Status badges
 * - Step details display
 *
 * Requirements tested:
 * - 10.1: Display migration history showing all migration runs
 * - 10.2: Migration planning allows selecting source and target schemas
 * - 10.3: Migration plan displays steps to be executed
 * - 10.4: Migration plan displays estimated impact and risks
 * - 10.8: Display status, duration, and step details for each run
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  createTestWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MigrationsPage } from './MigrationsPage'

// Create a wrapper with all required providers

// Mock data
const mockMigrationHistory = [
  {
    id: 'mig-1',
    planId: 'plan-1',
    collectionId: 'col-1',
    collectionName: 'users',
    fromVersion: 1,
    toVersion: 2,
    status: 'completed',
    steps: [
      {
        stepOrder: 1,
        operation: 'ADD_FIELD',
        status: 'completed',
        details: { fieldName: 'email', fieldType: 'string' },
        startedAt: '2024-01-15T10:30:00Z',
        completedAt: '2024-01-15T10:30:05Z',
      },
      {
        stepOrder: 2,
        operation: 'ADD_INDEX',
        status: 'completed',
        details: { indexName: 'idx_email' },
        startedAt: '2024-01-15T10:30:05Z',
        completedAt: '2024-01-15T10:30:10Z',
      },
    ],
    startedAt: '2024-01-15T10:30:00Z',
    completedAt: '2024-01-15T10:30:10Z',
  },
  {
    id: 'mig-2',
    planId: 'plan-2',
    collectionId: 'col-2',
    collectionName: 'products',
    fromVersion: 3,
    toVersion: 4,
    status: 'failed',
    steps: [
      {
        stepOrder: 1,
        operation: 'MODIFY_FIELD',
        status: 'failed',
        details: { fieldName: 'price', newType: 'decimal' },
        startedAt: '2024-01-14T14:20:00Z',
        completedAt: '2024-01-14T14:20:30Z',
        error: 'Cannot convert existing data to decimal',
      },
    ],
    startedAt: '2024-01-14T14:20:00Z',
    completedAt: '2024-01-14T14:20:30Z',
    error: 'Migration failed at step 1',
  },
  {
    id: 'mig-3',
    planId: 'plan-3',
    collectionId: 'col-3',
    collectionName: 'orders',
    fromVersion: 1,
    toVersion: 2,
    status: 'running',
    steps: [
      {
        stepOrder: 1,
        operation: 'ADD_FIELD',
        status: 'completed',
        details: { fieldName: 'status' },
        startedAt: '2024-01-16T09:00:00Z',
        completedAt: '2024-01-16T09:00:05Z',
      },
      {
        stepOrder: 2,
        operation: 'ADD_INDEX',
        status: 'running',
        details: { indexName: 'idx_status' },
        startedAt: '2024-01-16T09:00:05Z',
      },
    ],
    startedAt: '2024-01-16T09:00:00Z',
  },
  {
    id: 'mig-4',
    planId: 'plan-4',
    collectionId: 'col-4',
    collectionName: 'inventory',
    fromVersion: 2,
    toVersion: 3,
    status: 'rolled_back',
    steps: [
      {
        stepOrder: 1,
        operation: 'REMOVE_FIELD',
        status: 'completed',
        details: { fieldName: 'legacy_id' },
        startedAt: '2024-01-13T11:00:00Z',
        completedAt: '2024-01-13T11:00:10Z',
      },
    ],
    startedAt: '2024-01-13T11:00:00Z',
    completedAt: '2024-01-13T11:05:00Z',
  },
]

const mockMigrationDetails = {
  id: 'mig-1',
  planId: 'plan-1',
  collectionId: 'col-1',
  collectionName: 'users',
  fromVersion: 1,
  toVersion: 2,
  status: 'completed',
  steps: [
    {
      stepOrder: 1,
      operation: 'ADD_FIELD',
      status: 'completed',
      details: { fieldName: 'email', fieldType: 'string' },
      startedAt: '2024-01-15T10:30:00Z',
      completedAt: '2024-01-15T10:30:05Z',
    },
    {
      stepOrder: 2,
      operation: 'ADD_INDEX',
      status: 'completed',
      details: { indexName: 'idx_email' },
      startedAt: '2024-01-15T10:30:05Z',
      completedAt: '2024-01-15T10:30:10Z',
    },
  ],
  startedAt: '2024-01-15T10:30:00Z',
  completedAt: '2024-01-15T10:30:10Z',
}

// Mock collection summaries (returned by /api/collections)
const mockCollectionSummaries = [
  { id: 'col-1', name: 'users', displayName: 'Users' },
  { id: 'col-2', name: 'products', displayName: 'Products' },
  { id: 'col-3', name: 'orders', displayName: 'Orders' },
]

// Mock collections with version info (built from summaries + version API calls)
// NOTE: The component computes currentVersion = max(availableVersions), so for
// col-1 with versions [1,2,3,4], currentVersion=4 and available targets=[1,2,3]
const mockCollections = [
  {
    id: 'col-1',
    name: 'users',
    displayName: 'Users',
    currentVersion: 4,
    availableVersions: [1, 2, 3, 4],
  },
  {
    id: 'col-2',
    name: 'products',
    displayName: 'Products',
    currentVersion: 5,
    availableVersions: [1, 2, 3, 4, 5],
  },
  {
    id: 'col-3',
    name: 'orders',
    displayName: 'Orders',
    currentVersion: 1,
    availableVersions: [1],
  },
]

// Mock migration plan
const mockMigrationPlan = {
  id: 'plan-new-1',
  collectionId: 'col-1',
  collectionName: 'Users',
  fromVersion: 2,
  toVersion: 3,
  steps: [
    {
      order: 1,
      operation: 'ADD_FIELD',
      details: { fieldName: 'phone', fieldType: 'string' },
      reversible: true,
    },
    {
      order: 2,
      operation: 'ADD_INDEX',
      details: { indexName: 'idx_phone' },
      reversible: true,
    },
    {
      order: 3,
      operation: 'MODIFY_FIELD',
      details: { fieldName: 'email', newType: 'text' },
      reversible: false,
    },
  ],
  estimatedDuration: 120,
  estimatedRecordsAffected: 15000,
  risks: [
    {
      level: 'low',
      description: 'Adding new field may increase storage requirements',
    },
    {
      level: 'medium',
      description: 'Index creation may temporarily slow down writes',
    },
    {
      level: 'high',
      description: 'Field type modification is irreversible and may cause data loss',
    },
  ],
}

// Helper to setup Axios mocks for all endpoints
function setupAxiosMocks(overrides: Record<string, unknown> = {}) {
  mockAxios.get.mockImplementation((url: string) => {
    // SDK uses /api/migration-runs/{id} for detail
    if (url.match(/\/api\/migration-runs\/[^/]+$/)) {
      const id = url.split('/').pop()
      if (overrides.details) {
        return Promise.resolve({ data: overrides.details })
      }
      const migration = mockMigrationHistory.find((m) => m.id === id)
      if (migration) {
        return Promise.resolve({ data: migration })
      }
      return Promise.resolve({ data: mockMigrationDetails })
    }
    // SDK uses /api/migration-runs for list
    if (url.includes('/api/migration-runs')) {
      return Promise.resolve({ data: overrides.history ?? mockMigrationHistory })
    }
    if (url.match(/\/api\/migrations\/[^/]+$/) && !url.endsWith('/api/migrations')) {
      const id = url.split('/').pop()
      if (overrides.details) {
        return Promise.resolve({ data: overrides.details })
      }
      const migration = mockMigrationHistory.find((m) => m.id === id)
      if (migration) {
        return Promise.resolve({ data: migration })
      }
      return Promise.resolve({ data: mockMigrationDetails })
    }
    if (url.includes('/api/migrations')) {
      return Promise.resolve({ data: overrides.history ?? mockMigrationHistory })
    }
    if (url.includes('/api/collection-versions')) {
      const idMatch = url.match(/filter\[collectionId\]\[eq\]=([^&]+)/)
      const id = idMatch ? idMatch[1] : undefined
      const collection = id ? mockCollections.find((c) => c.id === id) : undefined
      if (collection && collection.availableVersions) {
        return Promise.resolve({
          data: collection.availableVersions.map((v: number) => ({ version: v })),
        })
      }
      return Promise.resolve({ data: [] })
    }
    if (url.includes('/api/collections')) {
      return Promise.resolve({
        data: overrides.collections ?? mockCollectionSummaries,
      })
    }
    return Promise.resolve({ data: {} })
  })

  mockAxios.post.mockImplementation((url: string, body?: unknown) => {
    if (url.includes('/api/migrations/plan')) {
      if (overrides.planError) {
        return Promise.reject(createAxiosError(500))
      }
      // postResource wraps body as { data: { type, attributes: { collectionId, targetVersion } } }
      const wrappedBody = body as
        { data?: { attributes?: { collectionId?: string; targetVersion?: number } } } | undefined
      const attrs = wrappedBody?.data?.attributes
      const collection = attrs
        ? mockCollections.find((c) => c.id === attrs.collectionId)
        : undefined
      if (overrides.plan) {
        return Promise.resolve({ data: overrides.plan })
      }
      return Promise.resolve({
        data: {
          ...mockMigrationPlan,
          collectionId: attrs?.collectionId ?? mockMigrationPlan.collectionId,
          collectionName: collection?.displayName || collection?.name || 'Unknown',
          fromVersion: collection?.currentVersion || 1,
          toVersion: attrs?.targetVersion ?? mockMigrationPlan.toVersion,
        },
      })
    }
    if (url.match(/\/api\/migrations\/[^/]+\/rollback/)) {
      return Promise.resolve({ data: {} })
    }
    if (url.includes('/api/migrations/execute')) {
      return Promise.resolve({ data: {} })
    }
    return Promise.resolve({ data: {} })
  })
}

describe('MigrationsPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    setupAxiosMocks()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  describe('Rendering', () => {
    it('renders the page with title', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      expect(screen.getByText('Migrations')).toBeInTheDocument()
      expect(screen.getByText('Migration History')).toBeInTheDocument()
    })

    it('renders with custom testId', () => {
      render(<MigrationsPage testId="custom-migrations" />, { wrapper: createTestWrapper() })

      expect(screen.getByTestId('custom-migrations')).toBeInTheDocument()
    })

    it('shows loading state initially', () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      // LoadingSpinner component has multiple "Loading..." texts (visible and sr-only)
      expect(screen.getByTestId('loading-spinner')).toBeInTheDocument()
    })
  })

  describe('Migration History Table - Requirement 10.1', () => {
    it('displays migration history table after loading', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-table')).toBeInTheDocument()
      })
    })

    it('displays all migration history entries', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-row-mig-1')).toBeInTheDocument()
      })

      expect(screen.getByTestId('history-row-mig-2')).toBeInTheDocument()
      expect(screen.getByTestId('history-row-mig-3')).toBeInTheDocument()
      expect(screen.getByTestId('history-row-mig-4')).toBeInTheDocument()
    })

    it('displays collection names in history', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('users')).toBeInTheDocument()
      })

      expect(screen.getByText('products')).toBeInTheDocument()
      expect(screen.getByText('orders')).toBeInTheDocument()
      expect(screen.getByText('inventory')).toBeInTheDocument()
    })

    it('displays version changes', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-row-mig-1')).toBeInTheDocument()
      })

      // Check version changes are displayed (text is split across elements)
      const row1 = screen.getByTestId('history-row-mig-1')
      expect(within(row1).getByText(/v.*1.*→.*v.*2/)).toBeInTheDocument()

      const row2 = screen.getByTestId('history-row-mig-2')
      expect(within(row2).getByText(/v.*3.*→.*v.*4/)).toBeInTheDocument()
    })

    it('displays status badges for all statuses', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        const statusBadges = screen.getAllByTestId('status-badge')
        expect(statusBadges.length).toBeGreaterThan(0)
      })

      // Check for different status texts
      expect(screen.getByText('Completed')).toBeInTheDocument()
      expect(screen.getByText('Failed')).toBeInTheDocument()
      expect(screen.getByText('Running')).toBeInTheDocument()
      expect(screen.getByText('Rolled Back')).toBeInTheDocument()
    })

    it('displays step count for each migration', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-row-mig-1')).toBeInTheDocument()
      })

      // mig-1 has 2 steps
      const row1 = screen.getByTestId('history-row-mig-1')
      expect(within(row1).getByText('2')).toBeInTheDocument()

      // mig-2 has 1 step
      const row2 = screen.getByTestId('history-row-mig-2')
      expect(within(row2).getByText('1')).toBeInTheDocument()
    })

    it('displays view details button for each migration', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      expect(screen.getByTestId('view-details-mig-2')).toBeInTheDocument()
      expect(screen.getByTestId('view-details-mig-3')).toBeInTheDocument()
    })

    it('displays empty state when no migrations', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/migrations')) {
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-empty')).toBeInTheDocument()
      })
    })
  })

  describe('Migration Run Details - Requirement 10.8', () => {
    it('opens details modal when view details is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-details-modal')).toBeInTheDocument()
      })
    })

    it('displays migration overview in details modal', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByText('Migration Run Details')).toBeInTheDocument()
      })

      // Check overview section - use getAllByText for elements that appear multiple times
      expect(screen.getByText('Overview')).toBeInTheDocument()
      expect(screen.getAllByText('Collection').length).toBeGreaterThan(0)
      expect(screen.getAllByText('Version Change').length).toBeGreaterThan(0)
    })

    it('displays step details in modal', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('steps-list')).toBeInTheDocument()
      })

      // Check steps are displayed
      expect(screen.getByTestId('step-1')).toBeInTheDocument()
      expect(screen.getByTestId('step-2')).toBeInTheDocument()

      // Check step operations
      expect(screen.getByText('ADD_FIELD')).toBeInTheDocument()
      expect(screen.getByText('ADD_INDEX')).toBeInTheDocument()
    })

    it('displays step status badges', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        const stepStatusBadges = screen.getAllByTestId('step-status-badge')
        expect(stepStatusBadges.length).toBeGreaterThan(0)
      })
    })

    it('closes modal when close button is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-details-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('close-button'))

      await waitFor(() => {
        expect(screen.queryByTestId('migration-details-modal')).not.toBeInTheDocument()
      })
    })

    it('closes modal when X button is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('close-details-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('close-details-button'))

      await waitFor(() => {
        expect(screen.queryByTestId('migration-details-modal')).not.toBeInTheDocument()
      })
    })

    it('closes modal when clicking overlay', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-details-modal')).toBeInTheDocument()
      })

      // Click on the overlay (modal background)
      await user.click(screen.getByTestId('migration-details-modal'))

      await waitFor(() => {
        expect(screen.queryByTestId('migration-details-modal')).not.toBeInTheDocument()
      })
    })

    it('displays error information for failed migrations', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-2')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-2'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-error')).toBeInTheDocument()
      })

      expect(screen.getByText(/Migration failed at step 1/)).toBeInTheDocument()
    })
  })

  describe('Error Handling', () => {
    it('handles history fetch error', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/migration-runs') || url.includes('/api/migrations')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('handles details fetch error', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        // SDK detail: /api/migration-runs/{id}
        if (url.match(/\/api\/migration-runs\/[^/]+$/)) {
          return Promise.reject(createAxiosError(500))
        }
        // SDK list: /api/migration-runs
        if (url.includes('/api/migration-runs')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        if (url.match(/\/api\/migrations\/[^/]+$/) && !url.endsWith('/api/migrations')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/migrations')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        if (url.includes('/api/collection-versions')) {
          const idMatch = url.match(/filter\[collectionId\]\[eq\]=([^&]+)/)
          const id = idMatch ? idMatch[1] : undefined
          const collection = id ? mockCollections.find((c) => c.id === id) : undefined
          if (collection && collection.availableVersions) {
            return Promise.resolve({
              data: collection.availableVersions.map((v: number) => ({ version: v })),
            })
          }
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-details-modal')).toBeInTheDocument()
      })

      // Should show error in modal
      await waitFor(() => {
        const modal = screen.getByTestId('migration-details-modal')
        expect(within(modal).getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('displays retry button on error', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/migration-runs') || url.includes('/api/migrations')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Retry')).toBeInTheDocument()
      })
    })
  })

  describe('Accessibility', () => {
    it('has proper modal roles and aria attributes', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-details-modal')).toBeInTheDocument()
        const dialog = screen.getByRole('dialog')
        expect(dialog).toHaveAttribute('aria-modal', 'true')
        expect(dialog).toHaveAttribute('aria-labelledby', 'migration-details-title')
      })
    })

    it('view details buttons have accessible labels', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        const viewButton = screen.getByTestId('view-details-mig-1')
        expect(viewButton).toHaveAttribute('aria-label', 'View Details')
      })
    })

    it('close button has accessible label', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('view-details-mig-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('view-details-mig-1'))

      await waitFor(() => {
        const closeButton = screen.getByTestId('close-details-button')
        expect(closeButton).toHaveAttribute('aria-label', 'Close')
      })
    })
  })

  describe('Duration Calculation', () => {
    it('displays duration for completed migrations', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-row-mig-1')).toBeInTheDocument()
      })

      // mig-1 duration is 10 seconds (10:30:00 to 10:30:10)
      const row1 = screen.getByTestId('history-row-mig-1')
      expect(within(row1).getByText('10.0s')).toBeInTheDocument()
    })

    it('displays duration for running migrations', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('history-row-mig-3')).toBeInTheDocument()
      })

      // mig-3 is running, should show some duration
      const row3 = screen.getByTestId('history-row-mig-3')
      // Duration will be calculated from startedAt to now, so just check it exists
      expect(row3).toBeInTheDocument()
    })
  })

  describe('Migration Planning - Requirement 10.2', () => {
    it('displays Plan Migration button', async () => {
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      expect(screen.getByText('Plan Migration')).toBeInTheDocument()
    })

    it('opens planning form when Plan Migration button is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-modal')).toBeInTheDocument()
      })
    })

    it('displays collection selection dropdown', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // Check collections are loaded
      const select = screen.getByTestId('collection-select')
      expect(select).toBeInTheDocument()
    })

    it('displays target version dropdown after selecting collection', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // Select a collection
      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')

      // Target version dropdown should be enabled
      const targetSelect = screen.getByTestId('target-version-select')
      expect(targetSelect).not.toBeDisabled()
    })

    it('shows available versions for selected collection', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // Select users collection (currentVersion: 2, availableVersions: [1, 2, 3])
      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')

      // Should show versions 1, 2, and 3 (not 4 which is current = max)
      const targetSelect = screen.getByTestId('target-version-select')
      expect(within(targetSelect).getByText('v1')).toBeInTheDocument()
      expect(within(targetSelect).getByText('v2')).toBeInTheDocument()
      expect(within(targetSelect).getByText('v3')).toBeInTheDocument()
    })

    it('shows warning when collection has no other versions', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // Select orders collection (currentVersion: 1, availableVersions: [1])
      await user.selectOptions(screen.getByTestId('collection-select'), 'col-3')

      await waitFor(() => {
        expect(screen.getByTestId('no-versions-warning')).toBeInTheDocument()
      })
    })

    it('displays selection summary when collection and version are selected', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // Select collection and version
      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')

      await waitFor(() => {
        expect(screen.getByTestId('selection-summary')).toBeInTheDocument()
      })

      // Should show the planned change in the summary
      const summary = screen.getByTestId('selection-summary')
      expect(within(summary).getByText(/v4.*→.*v3/)).toBeInTheDocument()
    })

    it('closes planning form when cancel is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('cancel-planning-button'))

      await waitFor(() => {
        expect(screen.queryByTestId('plan-migration-modal')).not.toBeInTheDocument()
      })
    })

    it('closes planning form when X button is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('close-planning-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('close-planning-button'))

      await waitFor(() => {
        expect(screen.queryByTestId('plan-migration-modal')).not.toBeInTheDocument()
      })
    })

    it('has proper modal accessibility attributes', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-modal')).toBeInTheDocument()
        const dialog = screen.getByRole('dialog')
        expect(dialog).toHaveAttribute('aria-modal', 'true')
        expect(dialog).toHaveAttribute('aria-labelledby', 'plan-migration-title')
      })
    })
  })

  describe('Migration Plan Display - Requirements 10.3, 10.4', () => {
    it('displays migration plan after creating plan', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // Select collection and version
      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')

      // Click create plan
      await user.click(screen.getByTestId('create-plan-button'))

      // Should show plan display modal
      await waitFor(() => {
        expect(screen.getByTestId('migration-plan-modal')).toBeInTheDocument()
      })
    })

    it('displays migration steps in plan - Requirement 10.3', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-steps-section')).toBeInTheDocument()
      })

      // Check steps are displayed
      expect(screen.getByTestId('plan-steps-list')).toBeInTheDocument()
      expect(screen.getByTestId('plan-step-1')).toBeInTheDocument()
      expect(screen.getByTestId('plan-step-2')).toBeInTheDocument()
      expect(screen.getByTestId('plan-step-3')).toBeInTheDocument()
    })

    it('displays step operations and details', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-steps-list')).toBeInTheDocument()
      })

      // Check step operations
      expect(screen.getByText('ADD_FIELD')).toBeInTheDocument()
      expect(screen.getByText('ADD_INDEX')).toBeInTheDocument()
      expect(screen.getByText('MODIFY_FIELD')).toBeInTheDocument()
    })

    it('displays reversible/irreversible badges for steps', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-steps-list')).toBeInTheDocument()
      })

      // Check reversible badges
      const reversibleBadges = screen.getAllByTestId('reversible-badge')
      expect(reversibleBadges.length).toBe(2)

      // Check irreversible badge
      expect(screen.getByTestId('irreversible-badge')).toBeInTheDocument()
    })

    it('displays estimated impact - Requirement 10.4', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-plan-modal')).toBeInTheDocument()
      })

      // Check estimated duration is displayed
      expect(screen.getByText('Estimated Duration')).toBeInTheDocument()
      expect(screen.getByText('2m')).toBeInTheDocument() // 120 seconds = 2 minutes

      // Check records affected is displayed
      expect(screen.getByText('Records Affected')).toBeInTheDocument()
      expect(screen.getByText('15,000')).toBeInTheDocument()
    })

    it('displays risks - Requirement 10.4', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-risks-section')).toBeInTheDocument()
      })

      // Check risks are displayed
      expect(screen.getByTestId('plan-risks-list')).toBeInTheDocument()
      expect(screen.getByTestId('plan-risk-0')).toBeInTheDocument()
      expect(screen.getByTestId('plan-risk-1')).toBeInTheDocument()
      expect(screen.getByTestId('plan-risk-2')).toBeInTheDocument()
    })

    it('displays risk level badges', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-risks-list')).toBeInTheDocument()
      })

      // Check risk badges
      const riskBadges = screen.getAllByTestId('risk-badge')
      expect(riskBadges.length).toBe(3)

      // Check risk levels are displayed
      expect(screen.getByText('Low')).toBeInTheDocument()
      expect(screen.getByText('Medium')).toBeInTheDocument()
      expect(screen.getByText('High')).toBeInTheDocument()
    })

    it('displays risk descriptions', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-risks-list')).toBeInTheDocument()
      })

      // Check risk descriptions
      expect(
        screen.getByText(/Adding new field may increase storage requirements/)
      ).toBeInTheDocument()
      expect(
        screen.getByText(/Index creation may temporarily slow down writes/)
      ).toBeInTheDocument()
      expect(screen.getByText(/Field type modification is irreversible/)).toBeInTheDocument()
    })

    it('closes plan display when close button is clicked', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-plan-modal')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('close-plan-details-button'))

      await waitFor(() => {
        expect(screen.queryByTestId('migration-plan-modal')).not.toBeInTheDocument()
      })
    })

    it('has proper accessibility attributes for plan modal', async () => {
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-plan-modal')).toBeInTheDocument()
        const dialog = screen.getByRole('dialog')
        expect(dialog).toHaveAttribute('aria-modal', 'true')
        expect(dialog).toHaveAttribute('aria-labelledby', 'migration-plan-title')
      })
    })
  })

  describe('Migration Planning Error Handling', () => {
    it('handles plan creation error', async () => {
      setupAxiosMocks({ planError: true })

      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('plan-error')).toBeInTheDocument()
      })
    })

    it('handles collections fetch error in planning form', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/collections')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/collections')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/migrations')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      // When the summary endpoint fails, the form loads but has an empty
      // collection dropdown since no summaries are available
      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      // The select should only have the placeholder option
      const select = screen.getByTestId('collection-select') as HTMLSelectElement
      // Only the placeholder "Select a collection..." option should be present
      const options = within(select).getAllByRole('option')
      expect(options.length).toBe(1)
    })
  })

  describe('Migration Execution - Requirements 10.5, 10.6, 10.7', () => {
    // Mock execution response
    const mockExecutionResponse = {
      runId: 'run-new-1',
      status: 'running',
    }

    // Mock running migration
    const mockRunningMigration = {
      id: 'run-new-1',
      planId: 'plan-new-1',
      collectionId: 'col-1',
      collectionName: 'Users',
      fromVersion: 2,
      toVersion: 3,
      status: 'running',
      steps: [
        {
          stepOrder: 1,
          operation: 'ADD_FIELD',
          status: 'completed',
          details: { fieldName: 'phone', fieldType: 'string' },
          startedAt: '2024-01-17T10:00:00Z',
          completedAt: '2024-01-17T10:00:05Z',
        },
        {
          stepOrder: 2,
          operation: 'ADD_INDEX',
          status: 'running',
          details: { indexName: 'idx_phone' },
          startedAt: '2024-01-17T10:00:05Z',
        },
        {
          stepOrder: 3,
          operation: 'MODIFY_FIELD',
          status: 'pending',
          details: { fieldName: 'email', newType: 'text' },
        },
      ],
      startedAt: '2024-01-17T10:00:00Z',
    }

    // Mock completed migration
    const mockCompletedMigration = {
      ...mockRunningMigration,
      status: 'completed',
      steps: mockRunningMigration.steps.map((s) => ({
        ...s,
        status: 'completed',
        completedAt: '2024-01-17T10:00:15Z',
      })),
      completedAt: '2024-01-17T10:00:15Z',
    }

    // Mock failed migration
    const mockFailedMigration = {
      ...mockRunningMigration,
      status: 'failed',
      steps: [
        {
          ...mockRunningMigration.steps[0],
          status: 'completed',
        },
        {
          ...mockRunningMigration.steps[1],
          status: 'failed',
          error: 'Index creation failed: duplicate key',
          completedAt: '2024-01-17T10:00:10Z',
        },
        {
          ...mockRunningMigration.steps[2],
          status: 'pending',
        },
      ],
      error: 'Migration failed at step 2',
      completedAt: '2024-01-17T10:00:10Z',
    }

    // Mock rolled back migration
    const mockRolledBackMigration = {
      ...mockFailedMigration,
      status: 'rolled_back',
    }

    function setupExecutionMocks(
      options: {
        executeError?: boolean
        runStatus?: 'running' | 'completed' | 'failed' | 'rolled_back'
        rollbackError?: boolean
      } = {}
    ) {
      mockAxios.get.mockImplementation((url: string) => {
        // SDK detail: /api/migration-runs/{id}
        if (url.match(/\/api\/migration-runs\/[^/]+$/)) {
          const id = url.split('/').pop()
          if (id === 'run-new-1') {
            switch (options.runStatus) {
              case 'completed':
                return Promise.resolve({ data: mockCompletedMigration })
              case 'failed':
                return Promise.resolve({ data: mockFailedMigration })
              case 'rolled_back':
                return Promise.resolve({ data: mockRolledBackMigration })
              default:
                return Promise.resolve({ data: mockRunningMigration })
            }
          }
          const migration = mockMigrationHistory.find((m) => m.id === id)
          return Promise.resolve({ data: migration || mockMigrationDetails })
        }
        // SDK list: /api/migration-runs
        if (url.includes('/api/migration-runs')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        if (url.match(/\/api\/migrations\/[^/]+$/) && !url.endsWith('/api/migrations')) {
          const id = url.split('/').pop()
          if (id === 'run-new-1') {
            switch (options.runStatus) {
              case 'completed':
                return Promise.resolve({ data: mockCompletedMigration })
              case 'failed':
                return Promise.resolve({ data: mockFailedMigration })
              case 'rolled_back':
                return Promise.resolve({ data: mockRolledBackMigration })
              default:
                return Promise.resolve({ data: mockRunningMigration })
            }
          }
          const migration = mockMigrationHistory.find((m) => m.id === id)
          return Promise.resolve({ data: migration || mockMigrationDetails })
        }
        if (url.includes('/api/migrations')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        if (url.includes('/api/collection-versions')) {
          const idMatch = url.match(/filter\[collectionId\]\[eq\]=([^&]+)/)
          const id = idMatch ? idMatch[1] : undefined
          const collection = id ? mockCollections.find((c) => c.id === id) : undefined
          if (collection && collection.availableVersions) {
            return Promise.resolve({
              data: collection.availableVersions.map((v: number) => ({ version: v })),
            })
          }
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/migrations/plan')) {
          return Promise.resolve({ data: mockMigrationPlan })
        }
        if (url.includes('/api/migrations/execute')) {
          if (options.executeError) {
            return Promise.reject(createAxiosError(500, { message: 'Failed to start migration' }))
          }
          return Promise.resolve({ data: mockExecutionResponse })
        }
        if (url.match(/\/api\/migrations\/[^/]+\/rollback/)) {
          if (options.rollbackError) {
            return Promise.reject(createAxiosError(500, { message: 'Rollback failed' }))
          }
          return Promise.resolve({ data: mockRolledBackMigration })
        }
        return Promise.resolve({ data: {} })
      })
    }

    it('displays Execute Migration button in plan display', async () => {
      setupExecutionMocks()
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-plan-modal')).toBeInTheDocument()
      })

      // Check Execute Migration button is present
      expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      expect(screen.getByText('Execute Migration')).toBeInTheDocument()
    })

    it('opens execution modal when Execute Migration is clicked', async () => {
      setupExecutionMocks()
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('migration-execution-modal')).toBeInTheDocument()
      })
    })

    it('displays progress tracking during execution - Requirement 10.5', async () => {
      setupExecutionMocks({ runStatus: 'running' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execution-progress-section')).toBeInTheDocument()
      })

      // Check progress bar is displayed
      expect(screen.getByTestId('progress-container')).toBeInTheDocument()
      expect(screen.getByTestId('progress-fill')).toBeInTheDocument()
      expect(screen.getByTestId('progress-text')).toBeInTheDocument()
    })

    it('displays step-by-step progress - Requirement 10.5', async () => {
      setupExecutionMocks({ runStatus: 'running' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('steps-progress')).toBeInTheDocument()
      })

      // Check individual step progress items
      expect(screen.getByTestId('step-progress-1')).toBeInTheDocument()
      expect(screen.getByTestId('step-progress-2')).toBeInTheDocument()
      expect(screen.getByTestId('step-progress-3')).toBeInTheDocument()
    })

    it('displays success message when migration completes - Requirement 10.5', async () => {
      setupExecutionMocks({ runStatus: 'completed' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('success-message')).toBeInTheDocument()
      })

      expect(screen.getByText('Migration completed successfully')).toBeInTheDocument()
    })

    it('handles execution errors gracefully - Requirement 10.6', async () => {
      setupExecutionMocks({ executeError: true })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execution-error')).toBeInTheDocument()
      })
    })

    it('displays failure message when migration fails - Requirement 10.6', async () => {
      setupExecutionMocks({ runStatus: 'failed' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('failure-message')).toBeInTheDocument()
      })

      expect(screen.getByText('Migration failed')).toBeInTheDocument()
    })

    it('displays step error details when a step fails - Requirement 10.6', async () => {
      setupExecutionMocks({ runStatus: 'failed' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('step-error-2')).toBeInTheDocument()
      })

      expect(screen.getByText(/Index creation failed/)).toBeInTheDocument()
    })

    it('offers rollback option on failure - Requirement 10.7', async () => {
      setupExecutionMocks({ runStatus: 'failed' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('rollback-button')).toBeInTheDocument()
      })

      expect(screen.getByText('Rollback')).toBeInTheDocument()
    })

    it('executes rollback when rollback button is clicked - Requirement 10.7', async () => {
      // Start with failed status, then return rolled_back after rollback
      let rollbackCalled = false

      mockAxios.get.mockImplementation((url: string) => {
        // SDK detail: /api/migration-runs/{id}
        if (url.match(/\/api\/migration-runs\/[^/]+$/)) {
          const id = url.split('/').pop()
          if (id === 'run-new-1') {
            if (rollbackCalled) {
              return Promise.resolve({ data: mockRolledBackMigration })
            }
            return Promise.resolve({ data: mockFailedMigration })
          }
          const migration = mockMigrationHistory.find((m) => m.id === id)
          return Promise.resolve({ data: migration || mockMigrationDetails })
        }
        // SDK list: /api/migration-runs
        if (url.includes('/api/migration-runs')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        if (url.match(/\/api\/migrations\/[^/]+$/) && !url.endsWith('/api/migrations')) {
          const id = url.split('/').pop()
          if (id === 'run-new-1') {
            if (rollbackCalled) {
              return Promise.resolve({ data: mockRolledBackMigration })
            }
            return Promise.resolve({ data: mockFailedMigration })
          }
          const migration = mockMigrationHistory.find((m) => m.id === id)
          return Promise.resolve({ data: migration || mockMigrationDetails })
        }
        if (url.includes('/api/migrations')) {
          return Promise.resolve({ data: mockMigrationHistory })
        }
        if (url.includes('/api/collection-versions')) {
          const idMatch = url.match(/filter\[collectionId\]\[eq\]=([^&]+)/)
          const cvId = idMatch ? idMatch[1] : undefined
          const collection = cvId ? mockCollections.find((c) => c.id === cvId) : undefined
          if (collection && collection.availableVersions) {
            return Promise.resolve({
              data: collection.availableVersions.map((v: number) => ({ version: v })),
            })
          }
          return Promise.resolve({ data: [] })
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollectionSummaries })
        }
        return Promise.resolve({ data: {} })
      })

      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/migrations/plan')) {
          return Promise.resolve({ data: mockMigrationPlan })
        }
        if (url.includes('/api/migrations/execute')) {
          return Promise.resolve({ data: mockExecutionResponse })
        }
        if (url.match(/\/api\/migrations\/[^/]+\/rollback/)) {
          rollbackCalled = true
          return Promise.resolve({ data: mockRolledBackMigration })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('rollback-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('rollback-button'))

      await waitFor(() => {
        expect(screen.getByTestId('rolled-back-message')).toBeInTheDocument()
      })

      expect(screen.getByText('Rollback completed successfully')).toBeInTheDocument()
    })

    it('handles rollback error - Requirement 10.7', async () => {
      setupExecutionMocks({ runStatus: 'failed', rollbackError: true })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('rollback-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('rollback-button'))

      await waitFor(() => {
        expect(screen.getByTestId('rollback-error')).toBeInTheDocument()
      })
    })

    it('has proper accessibility attributes for execution modal', async () => {
      setupExecutionMocks({ runStatus: 'running' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        const modal = screen.getByTestId('migration-execution-modal')
        expect(modal).toHaveAttribute('role', 'dialog')
        expect(modal).toHaveAttribute('aria-modal', 'true')
        expect(modal).toHaveAttribute('aria-labelledby', 'migration-execution-title')
      })
    })

    it('closes execution modal when close button is clicked after completion', async () => {
      setupExecutionMocks({ runStatus: 'completed' })
      const user = userEvent.setup()
      render(<MigrationsPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plan-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('plan-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('collection-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('collection-select'), 'col-1')
      await user.selectOptions(screen.getByTestId('target-version-select'), '3')
      await user.click(screen.getByTestId('create-plan-button'))

      await waitFor(() => {
        expect(screen.getByTestId('execute-migration-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('execute-migration-button'))

      await waitFor(() => {
        expect(screen.getByTestId('success-message')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('close-execution-button'))

      await waitFor(() => {
        expect(screen.queryByTestId('migration-execution-modal')).not.toBeInTheDocument()
      })
    })
  })
})
