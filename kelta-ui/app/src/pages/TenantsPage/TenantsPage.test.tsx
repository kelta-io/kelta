/**
 * TenantsPage Tests
 *
 * Covers the tenant-admin claim flow: provisioned tenants have no usable admin password,
 * so the page offers 'Invite admin' per tenant and an optional admin email on create, plus a
 * 'Bootstrap token' action that mints a short-lived admin PAT and shows it once.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  createTestWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { TenantsPage } from './TenantsPage'

const tenantsResponse = {
  data: [
    {
      type: 'tenants',
      id: 'tenant-1',
      attributes: {
        slug: 'acme',
        name: 'Acme Corp',
        edition: 'PROFESSIONAL',
        status: 'ACTIVE',
        createdAt: '2026-09-01T10:00:00Z',
        updatedAt: '2026-09-01T10:00:00Z',
      },
    },
  ],
  metadata: { totalCount: 1, currentPage: 0, pageSize: 100, totalPages: 1 },
}

describe('TenantsPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    mockAxios.get.mockImplementation((url: string) => {
      if (url.startsWith('/api/tenants')) {
        return Promise.resolve({ data: tenantsResponse })
      }
      return Promise.resolve({ data: {} })
    })
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  async function renderLoaded() {
    render(<TenantsPage />, { wrapper: createTestWrapper() })
    await waitFor(() => expect(screen.getByTestId('tenants-table')).toBeInTheDocument())
  }

  it('lists tenants with an Invite admin action per row', async () => {
    await renderLoaded()

    const row = screen.getByTestId('tenant-row-0')
    expect(within(row).getByText('acme')).toBeInTheDocument()
    expect(
      within(row).getByRole('button', { name: 'Invite admin for Acme Corp' })
    ).toBeInTheDocument()
  })

  it('sends the admin invite to the tenant admin-invite endpoint', async () => {
    const user = userEvent.setup()
    mockAxios.post.mockResolvedValue({
      data: { status: 'INVITED', tenantId: 'tenant-1', userId: 'u1', email: 'owner@example.com' },
    })
    await renderLoaded()

    await user.click(screen.getByTestId('invite-admin-button-0'))
    const dialog = screen.getByTestId('invite-admin-dialog')
    expect(within(dialog).getByText(/Acme Corp/)).toBeInTheDocument()
    expect(screen.getByTestId('invite-admin-submit')).toBeDisabled()

    await user.type(screen.getByTestId('invite-admin-email-input'), 'owner@example.com')
    await user.click(screen.getByTestId('invite-admin-submit'))

    await waitFor(() =>
      expect(mockAxios.post).toHaveBeenCalledWith('/api/tenants/tenant-1/admin-invite', {
        email: 'owner@example.com',
      })
    )
    await waitFor(() => expect(screen.queryByTestId('invite-admin-dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Invite sent to owner@example.com.')).toBeInTheDocument()
  })

  it('keeps the dialog open and reports a failed invite', async () => {
    const user = userEvent.setup()
    mockAxios.post.mockRejectedValue(createAxiosError(403, { error: 'MANAGE_TENANTS required' }))
    await renderLoaded()

    await user.click(screen.getByTestId('invite-admin-button-0'))
    await user.type(screen.getByTestId('invite-admin-email-input'), 'owner@example.com')
    await user.click(screen.getByTestId('invite-admin-submit'))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
    expect(screen.getByTestId('invite-admin-dialog')).toBeInTheDocument()
  })

  it('cancel closes the invite dialog without calling the API', async () => {
    const user = userEvent.setup()
    await renderLoaded()

    await user.click(screen.getByTestId('invite-admin-button-0'))
    await user.click(screen.getByTestId('invite-admin-cancel'))

    expect(screen.queryByTestId('invite-admin-dialog')).not.toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('passes an optional adminEmail when creating a tenant', async () => {
    const user = userEvent.setup()
    mockAxios.post.mockResolvedValue({
      data: {
        data: { type: 'tenants', id: 'tenant-2', attributes: { slug: 'beta', name: 'Beta' } },
      },
    })
    await renderLoaded()

    await user.click(screen.getByTestId('create-tenant-button'))
    await user.type(screen.getByTestId('tenant-slug-input'), 'beta')
    await user.type(screen.getByTestId('tenant-name-input'), 'Beta')
    await user.type(screen.getByTestId('tenant-admin-email-input'), 'founder@example.com')
    await user.click(screen.getByTestId('tenant-form-submit'))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
    const [url, body] = mockAxios.post.mock.calls[0]
    expect(url).toBe('/api/tenants')
    expect(body.data.attributes).toMatchObject({
      slug: 'beta',
      name: 'Beta',
      adminEmail: 'founder@example.com',
    })
  })

  it('rejects a malformed admin email on create', async () => {
    const user = userEvent.setup()
    await renderLoaded()

    await user.click(screen.getByTestId('create-tenant-button'))
    await user.type(screen.getByTestId('tenant-slug-input'), 'beta')
    await user.type(screen.getByTestId('tenant-name-input'), 'Beta')
    await user.type(screen.getByTestId('tenant-admin-email-input'), 'not-an-email')
    await user.click(screen.getByTestId('tenant-form-submit'))

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('omits adminEmail when left blank', async () => {
    const user = userEvent.setup()
    mockAxios.post.mockResolvedValue({
      data: {
        data: { type: 'tenants', id: 'tenant-2', attributes: { slug: 'beta', name: 'Beta' } },
      },
    })
    await renderLoaded()

    await user.click(screen.getByTestId('create-tenant-button'))
    await user.type(screen.getByTestId('tenant-slug-input'), 'beta')
    await user.type(screen.getByTestId('tenant-name-input'), 'Beta')
    await user.click(screen.getByTestId('tenant-form-submit'))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
    const [, body] = mockAxios.post.mock.calls[0]
    expect(body.data.attributes).not.toHaveProperty('adminEmail')
  })

  describe('Bootstrap token', () => {
    const minted = {
      token: 'klt_bootstrapSecretValue0123456789abcdefgh',
      tokenPrefix: 'klt_boot',
      name: 'bootstrap-ops@example.com-2026-10-05T00:00:00Z',
      tenantId: 'tenant-1',
      userId: 'admin-1',
      expiresAt: '2026-10-05T01:00:00Z',
    }

    it('offers a Bootstrap token action per row', async () => {
      await renderLoaded()

      expect(
        within(screen.getByTestId('tenant-row-0')).getByRole('button', {
          name: 'Bootstrap token for Acme Corp',
        })
      ).toBeInTheDocument()
    })

    it('mints with the chosen lifetime and shows the token once with a copy button and a warning', async () => {
      const user = userEvent.setup()
      const writeText = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
      mockAxios.post.mockResolvedValue({ data: minted })
      await renderLoaded()

      await user.click(screen.getByTestId('bootstrap-token-button-0'))
      expect(screen.queryByTestId('bootstrap-token-value')).not.toBeInTheDocument()
      await user.selectOptions(screen.getByTestId('bootstrap-token-expires-in'), '4h')
      await user.click(screen.getByTestId('bootstrap-token-submit'))

      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/tenants/tenant-1/bootstrap-token', {
          expiresIn: '4h',
        })
      )
      expect(await screen.findByTestId('bootstrap-token-value')).toHaveTextContent(minted.token)
      expect(screen.getByTestId('bootstrap-token-warning')).toHaveTextContent(
        /will not be shown again/
      )
      expect(screen.getByTestId('bootstrap-token-warning')).toHaveTextContent('acme')

      await user.click(screen.getByTestId('bootstrap-token-copy'))
      expect(writeText).toHaveBeenCalledWith(minted.token)
      expect(await screen.findByText('Copied!')).toBeInTheDocument()

      await user.click(screen.getByTestId('bootstrap-token-done'))
      expect(screen.queryByTestId('bootstrap-token-dialog')).not.toBeInTheDocument()

      // Reopening starts over: the token is never shown a second time.
      await user.click(screen.getByTestId('bootstrap-token-button-0'))
      expect(screen.queryByText(minted.token)).not.toBeInTheDocument()
      expect(screen.getByTestId('bootstrap-token-submit')).toBeInTheDocument()
    })

    it('defaults to 1 hour', async () => {
      const user = userEvent.setup()
      mockAxios.post.mockResolvedValue({ data: minted })
      await renderLoaded()

      await user.click(screen.getByTestId('bootstrap-token-button-0'))
      await user.click(screen.getByTestId('bootstrap-token-submit'))

      await waitFor(() =>
        expect(mockAxios.post).toHaveBeenCalledWith('/api/tenants/tenant-1/bootstrap-token', {
          expiresIn: '1h',
        })
      )
    })

    it('keeps the form open and shows no token when the mint is refused', async () => {
      const user = userEvent.setup()
      mockAxios.post.mockRejectedValue(
        createAxiosError(403, { error: 'The platform tenant cannot be bootstrapped' })
      )
      await renderLoaded()

      await user.click(screen.getByTestId('bootstrap-token-button-0'))
      await user.click(screen.getByTestId('bootstrap-token-submit'))

      await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
      expect(screen.getByTestId('bootstrap-token-dialog')).toBeInTheDocument()
      expect(screen.queryByTestId('bootstrap-token-value')).not.toBeInTheDocument()
    })

    it('cancel closes the dialog without minting', async () => {
      const user = userEvent.setup()
      await renderLoaded()

      await user.click(screen.getByTestId('bootstrap-token-button-0'))
      await user.click(screen.getByTestId('bootstrap-token-cancel'))

      expect(screen.queryByTestId('bootstrap-token-dialog')).not.toBeInTheDocument()
      expect(mockAxios.post).not.toHaveBeenCalled()
    })
  })
})
