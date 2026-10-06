/**
 * PackagesPage Tests
 *
 * Tests for the PackagesPage component including:
 * - Tab navigation between export, import, and history
 * - Export panel with item selection
 * - Import panel with file upload and preview
 * - History table display
 *
 * Requirements tested:
 * - 9.1: Display export and import options
 * - 9.10: Display package history
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
import { PackagesPage } from './PackagesPage'

// Mock URL.createObjectURL and revokeObjectURL
global.URL.createObjectURL = vi.fn(() => 'blob:mock-url')
global.URL.revokeObjectURL = vi.fn()

// Create a wrapper with all required providers

// Mock data
const mockPackageHistory = [
  {
    id: 'pkg-1',
    name: 'config-export-2024-01-15',
    version: '1.0.0',
    items: [
      { type: 'collection', id: 'col-1', name: 'users' },
      { type: 'role', id: 'role-1', name: 'admin' },
    ],
    createdAt: '2024-01-15T10:30:00Z',
    type: 'export',
    status: 'success',
  },
  {
    id: 'pkg-2',
    name: 'config-import-2024-01-14',
    version: '1.0.0',
    items: [{ type: 'policy', id: 'pol-1', name: 'admin_access' }],
    createdAt: '2024-01-14T14:20:00Z',
    type: 'import',
    status: 'success',
  },
  {
    id: 'pkg-3',
    name: 'failed-import',
    version: '1.0.0',
    items: [],
    createdAt: '2024-01-13T09:00:00Z',
    type: 'import',
    status: 'failed',
  },
]

const mockCollections = [
  { id: 'col-1', name: 'users' },
  { id: 'col-2', name: 'products' },
]

const mockPages = [{ id: 'page-1', name: 'dashboard' }]

const mockMenus = [{ id: 'menu-1', name: 'main_nav' }]

const mockImportPreview = {
  creates: [{ type: 'collection', id: 'col-new', name: 'new_collection' }],
  updates: [{ type: 'role', id: 'role-1', name: 'admin' }],
  conflicts: [],
}

const mockImportResult = {
  success: true,
  created: 1,
  updated: 1,
  skipped: 0,
  errors: [],
}

// Helper to setup Axios mocks for all endpoints
function setupAxiosMocks(overrides: Record<string, unknown> = {}) {
  mockAxios.get.mockImplementation((url: string) => {
    if (url.includes('/api/packages/history')) {
      return Promise.resolve({ data: overrides.history ?? mockPackageHistory })
    }
    if (url.includes('/api/collections')) {
      return Promise.resolve({ data: overrides.collections ?? mockCollections })
    }
    if (url.includes('/api/ui-pages')) {
      return Promise.resolve({ data: overrides.pages ?? mockPages })
    }
    if (url.includes('/api/ui-menus')) {
      return Promise.resolve({ data: overrides.menus ?? mockMenus })
    }
    return Promise.resolve({ data: {} })
  })

  mockAxios.post.mockImplementation((url: string) => {
    if (url.includes('/api/packages/export')) {
      return Promise.resolve({ data: {} })
    }
    if (url.includes('/api/packages/import/preview')) {
      return Promise.resolve({ data: overrides.importPreview ?? mockImportPreview })
    }
    if (url.includes('/api/packages/import')) {
      return Promise.resolve({ data: overrides.importResult ?? mockImportResult })
    }
    return Promise.resolve({ data: {} })
  })
}

describe('PackagesPage', () => {
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
    it('renders the page with title and tabs', async () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      expect(screen.getByText('Packages')).toBeInTheDocument()
      expect(screen.getByTestId('tab-export')).toBeInTheDocument()
      expect(screen.getByTestId('tab-import')).toBeInTheDocument()
      expect(screen.getByTestId('tab-history')).toBeInTheDocument()
    })

    it('renders with custom testId', () => {
      render(<PackagesPage testId="custom-packages" />, { wrapper: createTestWrapper() })

      expect(screen.getByTestId('custom-packages')).toBeInTheDocument()
    })

    it('shows export panel by default', async () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      expect(screen.getByTestId('export-panel')).toBeInTheDocument()
    })
  })

  describe('Tab Navigation', () => {
    it('switches to import panel when import tab is clicked', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      expect(screen.getByTestId('import-panel')).toBeInTheDocument()
      expect(screen.queryByTestId('export-panel')).not.toBeInTheDocument()
    })

    it('switches to history panel when history tab is clicked', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByTestId('history-table')).toBeInTheDocument()
      })
      expect(screen.queryByTestId('export-panel')).not.toBeInTheDocument()
    })

    it('marks active tab with correct aria-selected', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      const exportTab = screen.getByTestId('tab-export')
      const importTab = screen.getByTestId('tab-import')

      expect(exportTab).toHaveAttribute('aria-selected', 'true')
      expect(importTab).toHaveAttribute('aria-selected', 'false')

      await user.click(importTab)

      expect(exportTab).toHaveAttribute('aria-selected', 'false')
      expect(importTab).toHaveAttribute('aria-selected', 'true')
    })
  })

  describe('Export Panel - Requirement 9.1', () => {
    it('displays item selection sections for all types', async () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('item-section-collections')).toBeInTheDocument()
      })

      // Roles and policies were legacy authz; the export endpoint never accepted them.
      expect(screen.queryByTestId('item-section-roles')).not.toBeInTheDocument()
      expect(screen.queryByTestId('item-section-policies')).not.toBeInTheDocument()
      expect(screen.getByTestId('item-section-pages')).toBeInTheDocument()
      expect(screen.getByTestId('item-section-menus')).toBeInTheDocument()
    })

    it('loads and displays available items', async () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('item-col-1')).toBeInTheDocument()
      })

      expect(screen.getByTestId('item-col-2')).toBeInTheDocument()
      // The legacy /api/roles and /api/policies endpoints no longer exist (gateway 404).
      const requested = mockAxios.get.mock.calls.map(([url]) => String(url))
      expect(requested.some((url) => url.includes('/api/roles'))).toBe(false)
      expect(requested.some((url) => url.includes('/api/policies'))).toBe(false)
    })

    it('allows selecting items for export', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('checkbox-col-1')).toBeInTheDocument()
      })

      const checkbox = screen.getByTestId('checkbox-col-1')
      expect(checkbox).not.toBeChecked()

      await user.click(checkbox)

      expect(checkbox).toBeChecked()
    })

    it('allows selecting all items in a section', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('select-all-collections')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('select-all-collections'))

      expect(screen.getByTestId('checkbox-col-1')).toBeChecked()
      expect(screen.getByTestId('checkbox-col-2')).toBeChecked()
    })

    it('disables export button when no items selected', async () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('export-button')).toBeInTheDocument()
      })

      expect(screen.getByTestId('export-button')).toBeDisabled()
    })

    it('enables export button when items are selected', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('checkbox-col-1')).toBeInTheDocument()
      })

      // Fill in required fields using labels
      const nameInput = screen.getByLabelText(/Package Name/i)
      const versionInput = screen.getByLabelText(/Package Version/i)

      await user.clear(nameInput)
      await user.type(nameInput, 'test-package')
      await user.clear(versionInput)
      await user.type(versionInput, '1.0.0')

      // Select a collection
      const checkbox = screen.getByTestId('checkbox-col-1')
      await user.click(checkbox)

      // Wait for checkbox to be checked
      await waitFor(() => {
        expect(checkbox).toBeChecked()
      })

      // Then wait for export button to be enabled
      await waitFor(
        () => {
          expect(screen.getByTestId('export-button')).not.toBeDisabled()
        },
        { timeout: 3000 }
      )
    })

    it('triggers export when export button is clicked', async () => {
      let exportCalled = false
      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/packages/export')) {
          exportCalled = true
          return Promise.resolve({ data: {} })
        }
        if (url.includes('/api/packages/import/preview')) {
          return Promise.resolve({ data: mockImportPreview })
        }
        if (url.includes('/api/packages/import')) {
          return Promise.resolve({ data: mockImportResult })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('checkbox-col-1')).toBeInTheDocument()
      })

      // Fill in required fields using labels
      const nameInput = screen.getByLabelText(/Package Name/i)
      const versionInput = screen.getByLabelText(/Package Version/i)

      await user.clear(nameInput)
      await user.type(nameInput, 'test-package')
      await user.clear(versionInput)
      await user.type(versionInput, '1.0.0')

      // Select a collection
      const checkbox = screen.getByTestId('checkbox-col-1')
      await user.click(checkbox)

      // Wait for checkbox to be checked
      await waitFor(() => {
        expect(checkbox).toBeChecked()
      })

      // Then wait for export button to be enabled
      await waitFor(
        () => {
          expect(screen.getByTestId('export-button')).not.toBeDisabled()
        },
        { timeout: 3000 }
      )

      await user.click(screen.getByTestId('export-button'))

      await waitFor(() => {
        expect(exportCalled).toBe(true)
      })
    })
  })

  describe('Import Panel - Requirement 9.1', () => {
    it('displays file upload drop zone', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      expect(screen.getByTestId('drop-zone')).toBeInTheDocument()
      expect(screen.getByTestId('file-input')).toBeInTheDocument()
    })

    it('accepts file selection via input', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByText('test-package.json')).toBeInTheDocument()
      })
    })

    it('shows import preview after file selection', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('import-preview')).toBeInTheDocument()
      })

      // Check that preview stats are displayed (creates: 1, updates: 1, conflicts: 0)
      const previewSection = screen.getByTestId('import-preview')
      expect(within(previewSection).getByText('To Create')).toBeInTheDocument()
      expect(within(previewSection).getByText('To Update')).toBeInTheDocument()
    })

    it('allows clearing selected file', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('clear-file-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('clear-file-button'))

      expect(screen.queryByText('test-package.json')).not.toBeInTheDocument()
    })

    it('disables import buttons when no file selected', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      expect(screen.getByTestId('dry-run-button')).toBeDisabled()
      expect(screen.getByTestId('import-button')).toBeDisabled()
    })

    it('enables import buttons when file is selected', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('dry-run-button')).not.toBeDisabled()
        expect(screen.getByTestId('import-button')).not.toBeDisabled()
      })
    })

    it('triggers dry run when dry run button is clicked', async () => {
      let dryRunCalled = false
      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/packages/import/preview')) {
          return Promise.resolve({ data: mockImportPreview })
        }
        if (url.includes('/api/packages/import')) {
          dryRunCalled = true
          return Promise.resolve({ data: mockImportResult })
        }
        if (url.includes('/api/packages/export')) {
          return Promise.resolve({ data: {} })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('dry-run-button')).not.toBeDisabled()
      })

      await user.click(screen.getByTestId('dry-run-button'))

      await waitFor(() => {
        expect(dryRunCalled).toBe(true)
      })
    })

    it('shows import result after import', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('import-button')).not.toBeDisabled()
      })

      await user.click(screen.getByTestId('import-button'))

      // After successful import, the page switches to history tab
      await waitFor(() => {
        expect(screen.getByTestId('tab-history')).toHaveAttribute('aria-selected', 'true')
      })
    })
  })

  describe('History Panel - Requirement 9.10', () => {
    it('displays package history table', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByTestId('history-table')).toBeInTheDocument()
      })
    })

    it('displays all package history entries', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByTestId('history-row-pkg-1')).toBeInTheDocument()
      })

      expect(screen.getByTestId('history-row-pkg-2')).toBeInTheDocument()
      expect(screen.getByTestId('history-row-pkg-3')).toBeInTheDocument()
    })

    it('displays package names in history', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByText('config-export-2024-01-15')).toBeInTheDocument()
      })

      expect(screen.getByText('config-import-2024-01-14')).toBeInTheDocument()
    })

    it('displays type badges for export and import', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        const typeBadges = screen.getAllByTestId('type-badge')
        expect(typeBadges.length).toBeGreaterThan(0)
      })
    })

    it('displays status badges', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        const statusBadges = screen.getAllByTestId('status-badge')
        expect(statusBadges.length).toBeGreaterThan(0)
      })
    })

    it('displays empty state when no history', async () => {
      setupAxiosMocks({ history: [] })
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByTestId('history-empty')).toBeInTheDocument()
      })
    })

    it('displays item count for each package', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByTestId('history-row-pkg-1')).toBeInTheDocument()
      })

      // pkg-1 has 2 items
      const row1 = screen.getByTestId('history-row-pkg-1')
      expect(within(row1).getByText('2')).toBeInTheDocument()
    })
  })

  describe('Error Handling', () => {
    it('handles history fetch error', async () => {
      mockAxios.get.mockImplementation((url: string) => {
        if (url.includes('/api/packages/history')) {
          return Promise.reject(createAxiosError(500))
        }
        if (url.includes('/api/collections')) {
          return Promise.resolve({ data: mockCollections })
        }
        if (url.includes('/api/ui-pages')) {
          return Promise.resolve({ data: mockPages })
        }
        if (url.includes('/api/ui-menus')) {
          return Promise.resolve({ data: mockMenus })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-history'))

      await waitFor(() => {
        expect(screen.getByTestId('error-message')).toBeInTheDocument()
      })
    })

    it('handles export error gracefully', async () => {
      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/packages/export')) {
          return Promise.reject(createAxiosError(500, { message: 'Export failed' }))
        }
        if (url.includes('/api/packages/import/preview')) {
          return Promise.resolve({ data: mockImportPreview })
        }
        if (url.includes('/api/packages/import')) {
          return Promise.resolve({ data: mockImportResult })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('checkbox-col-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('checkbox-col-1'))
      await user.click(screen.getByTestId('export-button'))

      // Should not crash - error is handled via toast
      await waitFor(() => {
        expect(screen.getByTestId('export-panel')).toBeInTheDocument()
      })
    })

    it('handles import preview error gracefully - Requirement 9.9', async () => {
      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/packages/import/preview')) {
          return Promise.reject(createAxiosError(400, { message: 'Invalid package format' }))
        }
        if (url.includes('/api/packages/export')) {
          return Promise.resolve({ data: {} })
        }
        if (url.includes('/api/packages/import')) {
          return Promise.resolve({ data: mockImportResult })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['invalid'], 'bad-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      // Should not crash - error is handled via toast
      await waitFor(() => {
        expect(screen.getByTestId('import-panel')).toBeInTheDocument()
      })

      // Preview should not be shown on error
      expect(screen.queryByTestId('import-preview')).not.toBeInTheDocument()
    })

    it('handles import execution error gracefully - Requirement 9.9', async () => {
      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/packages/import/preview')) {
          return Promise.resolve({ data: mockImportPreview })
        }
        if (url.includes('/api/packages/import')) {
          return Promise.reject(createAxiosError(500, { message: 'Import failed: database error' }))
        }
        if (url.includes('/api/packages/export')) {
          return Promise.resolve({ data: {} })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('import-button')).not.toBeDisabled()
      })

      await user.click(screen.getByTestId('import-button'))

      // Should not crash - error is handled via toast
      await waitFor(() => {
        expect(screen.getByTestId('import-panel')).toBeInTheDocument()
      })
    })

    it('displays import errors in result - Requirement 9.9', async () => {
      const importResultWithErrors = {
        success: false,
        created: 0,
        updated: 0,
        skipped: 1,
        errors: [
          {
            item: { type: 'collection', id: 'col-1', name: 'users' },
            message: 'Validation failed',
          },
        ],
      }

      mockAxios.post.mockImplementation((url: string) => {
        if (url.includes('/api/packages/import/preview')) {
          return Promise.resolve({ data: mockImportPreview })
        }
        if (url.includes('/api/packages/import')) {
          return Promise.resolve({ data: importResultWithErrors })
        }
        if (url.includes('/api/packages/export')) {
          return Promise.resolve({ data: {} })
        }
        return Promise.resolve({ data: {} })
      })

      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const file = new File(['{}'], 'test-package.json', { type: 'application/json' })
      const input = screen.getByTestId('file-input')

      await user.upload(input, file)

      await waitFor(() => {
        expect(screen.getByTestId('dry-run-button')).not.toBeDisabled()
      })

      // Use dry-run to see the result without switching tabs
      await user.click(screen.getByTestId('dry-run-button'))

      await waitFor(() => {
        expect(screen.getByTestId('import-result')).toBeInTheDocument()
      })

      // Check that error details are displayed
      expect(screen.getByText(/users.*Validation failed/)).toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    it('has proper tab roles and aria attributes', () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      const tablist = screen.getByRole('tablist')
      expect(tablist).toBeInTheDocument()

      const tabs = screen.getAllByRole('tab')
      expect(tabs).toHaveLength(3)
    })

    it('has proper tabpanel roles', async () => {
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      const tabpanel = screen.getByRole('tabpanel')
      expect(tabpanel).toBeInTheDocument()
    })

    it('drop zone is keyboard accessible', async () => {
      const user = userEvent.setup()
      render(<PackagesPage />, { wrapper: createTestWrapper() })

      await user.click(screen.getByTestId('tab-import'))

      const dropZone = screen.getByTestId('drop-zone')
      expect(dropZone).toHaveAttribute('tabIndex', '0')
      expect(dropZone).toHaveAttribute('role', 'button')
    })
  })
})
