/**
 * ModulesPage Tests
 *
 * Tests for the ModulesPage component including:
 * - Module list rendering
 * - Plugin Components section backed by the client-side componentRegistry
 *   (empty state + grouped counts and name chips)
 * - Status badges, the FAILED/DEGRADED error line, enable/disable
 * - Install by URL as the only install path
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'
import { componentRegistry } from '../../services/componentRegistry'
import { ModulesPage } from './ModulesPage'

const mockModules = [
  {
    id: 'tm-1',
    moduleId: 'mod-analytics',
    name: 'Analytics Module',
    version: '1.2.0',
    description: 'Adds analytics action handlers',
    status: 'ACTIVE',
    installedBy: 'user-1',
    installedAt: '2026-01-15T10:00:00Z',
    updatedAt: '2026-01-15T10:00:00Z',
    actions: [],
    baseUrl: 'https://analytics.example.test',
    protocolVersion: 1,
  },
]

describe('ModulesPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    mockAxios.get.mockResolvedValue({ data: mockModules })
  })

  afterEach(() => {
    componentRegistry.clear()
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('renders the page title and installed modules', async () => {
    render(<ModulesPage />, { wrapper: createTestWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Modules')).toBeInTheDocument()
    })
    expect(screen.getByText('Analytics Module')).toBeInTheDocument()
    expect(screen.getByText('v1.2.0')).toBeInTheDocument()
  })

  it('renders the modules empty state when no modules are installed', async () => {
    mockAxios.get.mockResolvedValue({ data: [] })
    render(<ModulesPage />, { wrapper: createTestWrapper() })

    await waitFor(() => {
      expect(screen.getByText('No modules installed')).toBeInTheDocument()
    })
  })

  describe('Plugin Components section', () => {
    it('shows the empty state when no components are registered', async () => {
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plugin-components-section')).toBeInTheDocument()
      })
      expect(screen.getByText('Plugin Components')).toBeInTheDocument()
      expect(screen.getByTestId('plugin-components-empty')).toHaveTextContent(
        'No plugin components registered'
      )
      expect(screen.queryByTestId('plugin-components-field-renderers')).not.toBeInTheDocument()
    })

    it('renders registered components grouped by type with counts and name chips', async () => {
      const Fake = () => null
      componentRegistry.registerFieldRenderer('progress_bar', Fake)
      componentRegistry.registerFieldRenderer('color_swatch', Fake)
      componentRegistry.registerPageComponent('dashboard_widget', Fake)
      componentRegistry.registerQuickAction('approve_order', Fake)

      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plugin-components-section')).toBeInTheDocument()
      })
      expect(screen.queryByTestId('plugin-components-empty')).not.toBeInTheDocument()

      const fieldGroup = screen.getByTestId('plugin-components-field-renderers')
      expect(within(fieldGroup).getByText('Field Renderers')).toBeInTheDocument()
      expect(within(fieldGroup).getByText('2')).toBeInTheDocument()
      expect(within(fieldGroup).getByText('color_swatch')).toBeInTheDocument()
      expect(within(fieldGroup).getByText('progress_bar')).toBeInTheDocument()

      const pageGroup = screen.getByTestId('plugin-components-page-components')
      expect(within(pageGroup).getByText('1')).toBeInTheDocument()
      expect(within(pageGroup).getByText('dashboard_widget')).toBeInTheDocument()

      const actionGroup = screen.getByTestId('plugin-components-quick-actions')
      expect(within(actionGroup).getByText('approve_order')).toBeInTheDocument()

      // Empty group still renders with a zero count and no chips
      const columnGroup = screen.getByTestId('plugin-components-column-renderers')
      expect(within(columnGroup).getByText('0')).toBeInTheDocument()
      expect(within(columnGroup).getByText('None registered')).toBeInTheDocument()
    })

    it('lists names alphabetically within a group', async () => {
      const Fake = () => null
      componentRegistry.registerFieldRenderer('zeta_field', Fake)
      componentRegistry.registerFieldRenderer('alpha_field', Fake)

      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('plugin-components-field-renderers')).toBeInTheDocument()
      })
      const fieldGroup = screen.getByTestId('plugin-components-field-renderers')
      const chips = within(fieldGroup)
        .getAllByText(/_field$/)
        .map((el) => el.textContent)
      expect(chips).toEqual(['alpha_field', 'zeta_field'])
    })
  })

  describe('Status and errors', () => {
    it('shows why a FAILED install did not finish and offers to retry it', async () => {
      const user = userEvent.setup()
      mockAxios.get.mockResolvedValue({
        data: [
          {
            ...mockModules[0],
            status: 'FAILED',
            lastError: 'module answered 503 during install',
          },
        ],
      })
      mockAxios.post.mockResolvedValue({ data: { status: 'enabled' } })
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => expect(screen.getByText('Failed')).toBeInTheDocument())
      expect(screen.getByTestId('module-last-error-mod-analytics')).toHaveTextContent(
        'Install did not finish: module answered 503 during install'
      )

      await user.click(screen.getByRole('button', { name: /retry install/i }))
      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/mod-analytics/enable', {})
      )
    })

    it('does not show a stale error on a healthy module', async () => {
      mockAxios.get.mockResolvedValue({
        data: [{ ...mockModules[0], lastError: 'an old failure' }],
      })
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => expect(screen.getByText('Analytics Module')).toBeInTheDocument())
      expect(screen.queryByTestId('module-last-error-mod-analytics')).not.toBeInTheDocument()
    })

    it('disables an ACTIVE module and enables a DISABLED one', async () => {
      const user = userEvent.setup()
      mockAxios.get.mockResolvedValue({
        data: [
          mockModules[0],
          { ...mockModules[0], id: 'tm-2', moduleId: 'mod-off', name: 'Off', status: 'DISABLED' },
        ],
      })
      mockAxios.post.mockResolvedValue({ data: {} })
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      const active = await screen.findByTestId('module-card-mod-analytics')
      await user.click(within(active).getByRole('button', { name: /disable/i }))
      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/mod-analytics/disable', {})
      )

      const disabled = screen.getByTestId('module-card-mod-off')
      expect(within(disabled).getByText('Disabled')).toBeInTheDocument()
      await user.click(within(disabled).getByRole('button', { name: /^enable$/i }))
      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/mod-off/enable', {})
      )
    })
  })

  describe('Install', () => {
    it('installs by URL only: no JAR upload or install-mode toggle', async () => {
      const user = userEvent.setup()
      render(<ModulesPage />, { wrapper: createTestWrapper() })
      await waitFor(() => expect(screen.getByText('Analytics Module')).toBeInTheDocument())

      await user.click(screen.getByRole('button', { name: /install module/i }))

      expect(screen.getByTestId('external-module-install')).toBeInTheDocument()
      expect(screen.getByLabelText(/module url/i)).toBeInTheDocument()
      expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
      expect(screen.queryByLabelText(/module jar/i)).not.toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /cancel/i }))
      expect(screen.queryByTestId('external-module-install')).not.toBeInTheDocument()
    })
  })
})
