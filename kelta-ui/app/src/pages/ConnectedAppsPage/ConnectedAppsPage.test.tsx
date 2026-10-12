/**
 * ConnectedAppsPage tests — list rendering, the create/edit payload mapping
 * (JSON-array text fields, grant types, PKCE/consent only with authorization
 * code), client-side validation and the one-time client-secret reveal.
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'
import { ConnectedAppsPage } from './ConnectedAppsPage'

const APP = {
  id: 'app-1',
  name: 'Billing sync',
  description: null,
  clientId: 'cid-123',
  redirectUris: ['https://a.example.com/cb'],
  ipRestrictions: null,
  active: true,
  rateLimitPerHour: 5000,
  grantTypes: ['client_credentials', 'authorization_code'],
  requirePkce: true,
  consentRequired: false,
  lastUsedAt: null,
  createdBy: 'u1',
  createdAt: '2026-09-01T10:00:00Z',
  updatedAt: '2026-09-01T10:00:00Z',
}

interface JsonApiBody {
  data: { type: string; id?: string; attributes: Record<string, unknown> }
}

function setupList(apps: unknown[]) {
  mockAxios.get.mockImplementation((url: string) => {
    if (url === '/api/connected-apps') return Promise.resolve({ data: apps })
    return Promise.resolve({ data: [] })
  })
}

describe('ConnectedAppsPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('renders the apps table', async () => {
    setupList([APP])
    render(<ConnectedAppsPage />, { wrapper: createTestWrapper() })

    const row = await screen.findByTestId('connected-app-row-0')
    expect(within(row).getByText('Billing sync')).toBeInTheDocument()
    expect(within(row).getByText('cid-123')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Edit Billing sync' })).toBeInTheDocument()
  })

  it('creates an app with the mapped payload and reveals the secret once', async () => {
    const user = userEvent.setup()
    setupList([])
    mockAxios.post.mockResolvedValue({
      data: {
        data: {
          type: 'connected-apps',
          id: 'app-2',
          attributes: { name: 'New app', clientId: 'cid-new', clientSecret: 'sec-xyz' },
        },
      },
    })
    render(<ConnectedAppsPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-connected-app-button'))
    await user.type(await screen.findByTestId('connected-app-name-input'), 'New app')
    await user.type(screen.getByTestId('connected-app-scopes-input'), '[["api"]')
    await user.type(
      screen.getByTestId('connected-app-redirect-uris-input'),
      ' https://x.example.com/cb {Enter}{Enter}https://y.example.com/cb'
    )
    await user.click(screen.getByTestId('connected-app-form-submit'))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalledTimes(1))
    const [url, body] = mockAxios.post.mock.calls[0] as [string, JsonApiBody]
    expect(url).toBe('/api/connected-apps')
    expect(body.data.attributes).toMatchObject({
      name: 'New app',
      scopes: '["api"]',
      redirectUris: '["https://x.example.com/cb","https://y.example.com/cb"]',
      rateLimitPerHour: 10000,
      active: true,
      grantTypes: '["client_credentials"]',
      requirePkce: false,
      consentRequired: false,
    })
    expect(body.data.attributes.description).toBeUndefined()
    expect(body.data.attributes.ipRestrictions).toBeUndefined()

    expect(await screen.findByTestId('credentials-client-secret')).toHaveTextContent('sec-xyz')
    expect(screen.getByTestId('credentials-client-id')).toHaveTextContent('cid-new')
    await waitFor(() =>
      expect(screen.queryByTestId('connected-app-form-modal')).not.toBeInTheDocument()
    )

    await user.click(screen.getByTestId('credentials-dialog-done'))
    expect(screen.queryByTestId('credentials-dialog-modal')).not.toBeInTheDocument()
  })

  it('blocks submit on invalid scopes, redirect URIs and IP restrictions', async () => {
    const user = userEvent.setup()
    setupList([])
    render(<ConnectedAppsPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-connected-app-button'))
    await user.type(await screen.findByTestId('connected-app-name-input'), 'Bad app')
    await user.type(screen.getByTestId('connected-app-scopes-input'), '{{"api": true}')
    await user.type(screen.getByTestId('connected-app-redirect-uris-input'), 'not a url')
    await user.type(screen.getByTestId('connected-app-ip-restrictions-input'), '10.0.0/8')
    await user.click(screen.getByTestId('connected-app-form-submit'))

    expect(
      await screen.findByText('Scopes must be a JSON array (e.g. ["api"])')
    ).toBeInTheDocument()
    expect(
      screen.getByText('Each redirect URI must be a valid URL (e.g. https://example.com/callback)')
    ).toBeInTheDocument()
    expect(
      screen.getByText('Each entry must be a valid IP or CIDR (e.g. 10.0.0.0/8)')
    ).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('edits an app, keeping its grants and hiding the scopes field', async () => {
    const user = userEvent.setup()
    setupList([APP])
    mockAxios.patch.mockResolvedValue({
      data: { data: { type: 'connected-apps', id: 'app-1', attributes: {} } },
    })
    render(<ConnectedAppsPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('edit-button-0'))
    expect(await screen.findByTestId('connected-app-name-input')).toHaveValue('Billing sync')
    expect(screen.queryByTestId('connected-app-scopes-input')).not.toBeInTheDocument()
    expect(screen.getByTestId('connected-app-pkce-input')).toBeChecked()
    expect(screen.getByTestId('connected-app-consent-input')).not.toBeChecked()

    await user.click(screen.getByTestId('connected-app-form-submit'))

    await waitFor(() => expect(mockAxios.patch).toHaveBeenCalledTimes(1))
    const [url, body] = mockAxios.patch.mock.calls[0] as [string, JsonApiBody]
    expect(url).toBe('/api/connected-apps/app-1')
    expect(body.data.attributes).toMatchObject({
      name: 'Billing sync',
      redirectUris: '["https://a.example.com/cb"]',
      rateLimitPerHour: 5000,
      grantTypes: '["client_credentials","authorization_code"]',
      requirePkce: true,
      consentRequired: false,
    })
  })
})
