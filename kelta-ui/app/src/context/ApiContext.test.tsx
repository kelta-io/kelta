/**
 * ApiContext Unit Tests
 *
 * Covers the 401 response interceptor: a server-rejected token forces a
 * refresh and one retry; only a genuinely dead session (SessionExpiredError)
 * redirects to login, a transient refresh failure just fails the request.
 *
 * The shared test setup replaces axios.create() with `mockAxios`, whose
 * interceptor registration is a spy — so the handler ApiProvider registers is
 * pulled back out of that spy and driven directly.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { ApiProvider, useApi } from './ApiContext'
import { AuthContext } from './AuthContext'
import type { AuthContextValue } from '../types/auth'
import { SessionExpiredError, TokenUnavailableError } from './authErrors'
import { mockAxios, resetMockAxios } from '../test/testUtils'

const mockGetAccessToken = vi.fn()

// AuthContext is already loaded by the shared test setup, so a module mock would
// not reach ApiContext's binding; feed useAuth() through the real provider instead.
const authValue = {
  user: null,
  isAuthenticated: true,
  isLoading: false,
  error: null,
  login: vi.fn(),
  logout: vi.fn(),
  getAccessToken: mockGetAccessToken,
} as unknown as AuthContextValue

type ErrorHandler = (error: unknown) => Promise<unknown>

function Capture({ onReady }: { onReady: (v: ReturnType<typeof useApi>) => void }) {
  onReady(useApi())
  return null
}

/** Render the provider and return the 401 error handler it registered on axios. */
async function renderAndGetHandler(): Promise<ErrorHandler> {
  let ready = false
  render(
    <AuthContext.Provider value={authValue}>
      <ApiProvider baseUrl="http://api.test">
        <Capture
          onReady={() => {
            ready = true
          }}
        />
      </ApiProvider>
    </AuthContext.Provider>
  )
  await waitFor(() => expect(ready).toBe(true))
  const calls = mockAxios.interceptors.response.use.mock.calls
  expect(calls.length).toBeGreaterThan(0)
  return calls[calls.length - 1][1] as ErrorHandler
}

function unauthorized(config: Record<string, unknown> = {}) {
  return {
    isAxiosError: true,
    config: { url: '/api/things', headers: { Authorization: 'Bearer stale' }, ...config },
    response: { status: 401, data: { errors: [{ status: '401' }] } },
  }
}

describe('ApiContext 401 interceptor', () => {
  const assign = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    resetMockAxios()
    mockAxios.interceptors.response.use.mockClear()
    sessionStorage.setItem('kelta_auth_tokens', '{"accessToken":"stale"}')
    Object.defineProperty(window, 'location', {
      value: {
        assign,
        origin: 'http://localhost',
        hostname: 'localhost',
        pathname: '/default/app',
        search: '',
      },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    sessionStorage.clear()
  })

  it('forces a refresh (ignoring the local clock) and retries the request once', async () => {
    mockGetAccessToken.mockResolvedValueOnce('fresh')
    mockAxios.request.mockResolvedValueOnce({ status: 200, data: { ok: true } })
    const onError = await renderAndGetHandler()

    const res = (await onError(unauthorized())) as { status: number }

    expect(res.status).toBe(200)
    expect(mockGetAccessToken).toHaveBeenCalledWith({ force: true })
    expect(mockAxios.request).toHaveBeenCalledTimes(1)
    const retry = mockAxios.request.mock.calls[0][0]
    expect(retry.__retried).toBe(true)
    expect(retry.headers.Authorization).toBe('Bearer fresh')
    expect(assign).not.toHaveBeenCalled()
  })

  it('redirects to login only when the session is genuinely over', async () => {
    mockGetAccessToken.mockRejectedValueOnce(new SessionExpiredError('Session expired'))
    const onError = await renderAndGetHandler()

    await expect(onError(unauthorized())).rejects.toMatchObject({ response: { status: 401 } })

    expect(mockAxios.request).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('kelta_auth_tokens')).toBeNull()
    expect(assign).toHaveBeenCalledWith('/default/login')
  })

  it('fails the request but keeps the session on a transient refresh failure', async () => {
    mockGetAccessToken.mockRejectedValueOnce(new TokenUnavailableError('auth server rolling'))
    const onError = await renderAndGetHandler()

    await expect(onError(unauthorized())).rejects.toMatchObject({ response: { status: 401 } })

    expect(mockAxios.request).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('kelta_auth_tokens')).toBe('{"accessToken":"stale"}')
    expect(assign).not.toHaveBeenCalled()
  })

  it('does not loop: a 401 on an already-retried request is surfaced as-is', async () => {
    const onError = await renderAndGetHandler()

    await expect(onError(unauthorized({ __retried: true }))).rejects.toMatchObject({
      response: { status: 401 },
    })

    expect(mockGetAccessToken).not.toHaveBeenCalled()
    expect(mockAxios.request).not.toHaveBeenCalled()
    expect(assign).not.toHaveBeenCalled()
  })

  it('ignores non-401 errors', async () => {
    const onError = await renderAndGetHandler()
    const serverError = { isAxiosError: true, config: {}, response: { status: 500 } }

    await expect(onError(serverError)).rejects.toBe(serverError)
    expect(mockGetAccessToken).not.toHaveBeenCalled()
  })
})
