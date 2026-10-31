/**
 * ModulesPage Tests
 *
 * Tests for the ModulesPage component including:
 * - Module list rendering
 * - Plugin Components section backed by the client-side componentRegistry
 *   (empty state + grouped counts and name chips)
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
    tenantId: 'tenant-1',
    moduleId: 'mod-analytics',
    name: 'Analytics Module',
    version: '1.2.0',
    description: 'Adds analytics action handlers',
    sourceUrl: 'https://modules.example.com/analytics.jar',
    jarChecksum: 'abc123',
    jarSizeBytes: 1024,
    moduleClass: 'com.example.AnalyticsModule',
    manifest: '{}',
    status: 'ACTIVE',
    installedBy: 'user-1',
    installedAt: '2026-01-15T10:00:00Z',
    updatedAt: '2026-01-15T10:00:00Z',
    actions: [],
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

  describe('Load failures', () => {
    it('shows a quarantined module with its reason and a Re-enable action that reloads it', async () => {
      const user = userEvent.setup()
      mockAxios.get.mockResolvedValue({
        data: [
          {
            ...mockModules[0],
            status: 'QUARANTINED',
            lastError: 'ModuleSignatureException: checksum mismatch',
          },
        ],
      })
      mockAxios.post.mockResolvedValue({ data: { status: 'enabled' } })
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => expect(screen.getByText('Quarantined')).toBeInTheDocument())
      expect(screen.getByTestId('module-last-error-mod-analytics')).toHaveTextContent(
        'Quarantined: ModuleSignatureException: checksum mismatch'
      )

      await user.click(screen.getByRole('button', { name: /re-enable/i }))
      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/mod-analytics/enable', {})
      )
    })

    it('shows an ACTIVE module failing on a transient error as retrying, with Retry now', async () => {
      const user = userEvent.setup()
      mockAxios.get.mockResolvedValue({
        data: [
          {
            ...mockModules[0],
            loadRetrying: true,
            lastError: 'SdkClientException: Connection refused',
          },
        ],
      })
      mockAxios.post.mockResolvedValue({ data: { status: 'enabled' } })
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() =>
        expect(screen.getByTestId('module-retrying-mod-analytics')).toBeInTheDocument()
      )
      expect(screen.getByTestId('module-last-error-mod-analytics')).toHaveTextContent(
        /retrying automatically: SdkClientException: Connection refused/
      )

      await user.click(screen.getByRole('button', { name: /retry now/i }))
      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/mod-analytics/enable', {})
      )
    })

    it('does not show a stale error on a healthy module', async () => {
      mockAxios.get.mockResolvedValue({
        data: [{ ...mockModules[0], loadRetrying: false, lastError: null }],
      })
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await waitFor(() => expect(screen.getByText('Analytics Module')).toBeInTheDocument())
      expect(screen.queryByTestId('module-last-error-mod-analytics')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /retry now/i })).not.toBeInTheDocument()
    })
  })

  describe('Install', () => {
    it('posts the JAR as multipart to /install-jar, which is the only path that runs code', async () => {
      // Before this, the form only ever called /api/modules/install with the manifest JSON, so
      // every module installed through the UI had no implementation at all.
      const user = userEvent.setup()
      mockAxios.post.mockResolvedValue({ data: mockModules[0] })
      render(<ModulesPage />, { wrapper: createTestWrapper() })
      await waitFor(() => expect(screen.getByText('Analytics Module')).toBeInTheDocument())

      await user.click(screen.getByRole('button', { name: /install module/i }))
      // Install by URL is the default; the JAR form is the legacy tab.
      await user.click(screen.getByTestId('install-mode-jar'))
      await user.type(
        screen.getByLabelText(/module manifest/i),
        '{{"id":"m","name":"M","version":"1.0.0","moduleClass":"C"}'
      )
      await user.upload(
        screen.getByLabelText(/module jar/i),
        new File([new Uint8Array([1, 2, 3])], 'm.jar', { type: 'application/java-archive' })
      )
      await user.type(screen.getByLabelText(/publisher signature/i), 'sig-base64')
      await user.click(screen.getByRole('button', { name: /^install$/i }))

      await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
      const [url, body] = mockAxios.post.mock.calls[0]
      expect(url).toBe('/api/modules/install-jar')
      expect(body).toBeInstanceOf(FormData)
      expect((body as FormData).get('jar')).toBeInstanceOf(File)
      expect((body as FormData).get('signature')).toBe('sig-base64')
    })

    it('warns that a manifest-only install produces a module that cannot run', async () => {
      const user = userEvent.setup()
      render(<ModulesPage />, { wrapper: createTestWrapper() })
      await waitFor(() => expect(screen.getByText('Analytics Module')).toBeInTheDocument())

      await user.click(screen.getByRole('button', { name: /install module/i }))
      await user.click(screen.getByTestId('install-mode-jar'))

      expect(screen.getByText(/every action it\s+declares is quarantined/i)).toBeInTheDocument()
    })
  })
})
