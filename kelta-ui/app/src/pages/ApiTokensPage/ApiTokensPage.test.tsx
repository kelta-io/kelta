/**
 * Tests for the API tokens page: the create -> reveal-once flow, revoke, and
 * the helpers added in Phase 10 of the kelta-mcp rollout (the `claude mcp add`
 * command builder + the MCP base URL resolver), which compose into the
 * "Use with Claude Code" panel shown after token creation.
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ApiTokensPage, resolveMcpBaseUrl, buildMcpAddCommand } from './ApiTokensPage'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'

const TOKEN = {
  id: 'tok-1',
  name: 'CI Pipeline',
  tokenPrefix: 'klt_ab12',
  expiresAt: '2026-12-01T00:00:00Z',
  lastUsedAt: null,
  createdAt: '2026-09-01T00:00:00Z',
}

describe('ApiTokensPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('lists tokens by prefix', async () => {
    mockAxios.get.mockResolvedValue({ data: { data: [TOKEN] } })
    render(<ApiTokensPage />, { wrapper: createTestWrapper() })

    const table = await screen.findByTestId('tokens-table')
    expect(within(table).getByText('CI Pipeline')).toBeInTheDocument()
    expect(within(table).getByText('klt_ab12...')).toBeInTheDocument()
    expect(within(table).getByText('Never')).toBeInTheDocument()
  })

  it('shows the empty state when there are no tokens', async () => {
    mockAxios.get.mockResolvedValue({ data: { data: [] } })
    render(<ApiTokensPage />, { wrapper: createTestWrapper() })
    expect(await screen.findByTestId('empty-state')).toHaveTextContent('No API tokens yet')
  })

  it('requires a name before creating', async () => {
    const user = userEvent.setup()
    mockAxios.get.mockResolvedValue({ data: { data: [] } })
    render(<ApiTokensPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('create-token-button'))
    await user.click(await screen.findByTestId('create-token-submit'))

    expect(await screen.findByText('Name is required')).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('creates a token with the chosen expiry and reveals it once', async () => {
    const user = userEvent.setup()
    mockAxios.get.mockResolvedValue({ data: { data: [] } })
    mockAxios.post.mockResolvedValueOnce({ data: { ...TOKEN, token: 'klt_secret_value' } })
    render(<ApiTokensPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('create-token-button'))
    expect(await screen.findByTestId('create-token-form-modal')).toBeInTheDocument()
    expect(screen.getByTestId<HTMLSelectElement>('token-expiry-select')).toHaveValue('90')

    await user.type(screen.getByTestId('token-name-input'), 'Deploy bot')
    await user.selectOptions(screen.getByTestId('token-expiry-select'), '30')
    await user.click(screen.getByTestId('create-token-submit'))

    await waitFor(() =>
      expect(mockAxios.post).toHaveBeenCalledWith('/api/me/tokens', {
        name: 'Deploy bot',
        expiresInDays: 30,
      })
    )
    expect(await screen.findByTestId('created-token-value')).toHaveTextContent('klt_secret_value')
    expect(screen.queryByTestId('create-token-form-modal')).not.toBeInTheDocument()
    expect(screen.getByTestId('mcp-add-user-value')).toHaveTextContent('klt_secret_value')

    await user.click(screen.getByTestId('token-created-done'))
    expect(screen.queryByTestId('created-token-value')).not.toBeInTheDocument()
  })

  it('revokes a token after confirmation', async () => {
    const user = userEvent.setup()
    mockAxios.get.mockResolvedValue({ data: { data: [TOKEN] } })
    mockAxios.delete.mockResolvedValueOnce({ data: {} })
    render(<ApiTokensPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('revoke-token-tok-1'))
    const confirm = await screen.findByTestId('confirm-dialog')
    expect(within(confirm).getByText(/revoke "CI Pipeline"/)).toBeInTheDocument()
    await user.click(within(confirm).getByRole('button', { name: 'Revoke' }))

    await waitFor(() => expect(mockAxios.delete).toHaveBeenCalledWith('/api/me/tokens/tok-1'))
    expect(await screen.findByText('Token revoked')).toBeInTheDocument()
  })
})

describe('buildMcpAddCommand', () => {
  it('builds a user-profile URL with the slug before /mcp/...', () => {
    const cmd = buildMcpAddCommand(
      'user',
      'https://api.kelta.io',
      'threadline-clothing',
      'klt_abc123'
    )
    expect(cmd).toBe(
      "claude mcp add kelta-user --transport http --url https://api.kelta.io/threadline-clothing/mcp/user --header 'Authorization: Bearer klt_abc123'"
    )
  })

  it('builds an admin-profile command at the right slug-prefixed URL', () => {
    const cmd = buildMcpAddCommand('admin', 'https://api.kelta.io', 'another-tenant', 'klt_xyz')
    expect(cmd).toContain('--url https://api.kelta.io/another-tenant/mcp/admin')
    expect(cmd).toContain('kelta-admin')
  })

  it('uses single quotes around the auth header so the token is opaque to the shell', () => {
    const cmd = buildMcpAddCommand('user', 'https://api.kelta.io', 't', 'klt_token_with$pecial')
    expect(cmd).toContain("'Authorization: Bearer klt_token_with$pecial'")
  })
})

describe('resolveMcpBaseUrl', () => {
  const originalEnv = import.meta.env.VITE_API_BASE_URL

  beforeEach(() => {
    // jsdom always sets window.location; reset the env var explicitly.
    vi.stubEnv('VITE_API_BASE_URL', '')
  })

  afterEach(() => {
    if (originalEnv !== undefined) {
      vi.stubEnv('VITE_API_BASE_URL', originalEnv)
    }
    vi.unstubAllEnvs()
  })

  it('prefers VITE_API_BASE_URL when set', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.kelta.io')
    expect(resolveMcpBaseUrl()).toBe('https://api.kelta.io')
  })

  it('falls back to window.location.origin when env is empty (local dev)', () => {
    expect(resolveMcpBaseUrl()).toBe(window.location.origin)
  })
})
