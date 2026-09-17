/**
 * AuthContext Unit Tests
 *
 * Tests for the authentication context and useAuth hook.
 * Validates requirements 2.1-2.8 for authentication flow.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider, useAuth, __resetRefreshStateForTests } from './AuthContext'
import { SessionExpiredError, TokenUnavailableError } from './authErrors'
import { clearBootstrapCache } from '../utils/bootstrapCache'
import type { ReactNode } from 'react'

// Store original fetch
const originalFetch = global.fetch

// Mock sessionStorage
const mockSessionStorage: Record<string, string> = {}
const sessionStorageMock = {
  getItem: vi.fn((key: string) => mockSessionStorage[key] || null),
  setItem: vi.fn((key: string, value: string) => {
    mockSessionStorage[key] = value
  }),
  removeItem: vi.fn((key: string) => {
    delete mockSessionStorage[key]
  }),
  clear: vi.fn(() => {
    Object.keys(mockSessionStorage).forEach((key) => delete mockSessionStorage[key])
  }),
  length: 0,
  key: vi.fn(),
}

// Mock window.location
const originalLocation = window.location
let mockLocationHref = 'http://localhost:3000/dashboard'

// Mock bootstrap config response
const mockBootstrapConfig = {
  oidcProviders: [
    {
      id: 'internal-1',
      name: 'Kelta Platform (Internal)',
      issuer: 'https://auth.example.com',
      clientId: 'kelta-platform',
      isInternal: true,
    },
    {
      id: 'provider-1',
      name: 'External IdP',
      issuer: 'https://external.example.com',
      clientId: 'external-client',
    },
  ],
  pages: [],
  menus: [],
  theme: {
    primaryColor: '#000',
    secondaryColor: '#fff',
    fontFamily: 'sans-serif',
    borderRadius: '4px',
  },
  branding: {
    logoUrl: '/logo.png',
    applicationName: 'Test App',
    faviconUrl: '/favicon.ico',
  },
  features: {
    enableBuilder: true,
    enableResourceBrowser: true,
    enablePackages: true,
    enableMigrations: true,
    enableDashboard: true,
  },
}

// Mock OIDC discovery document
const mockDiscoveryDoc = {
  issuer: 'https://auth.example.com',
  authorization_endpoint: 'https://auth.example.com/authorize',
  token_endpoint: 'https://auth.example.com/token',
  userinfo_endpoint: 'https://auth.example.com/userinfo',
  end_session_endpoint: 'https://auth.example.com/logout',
  jwks_uri: 'https://auth.example.com/.well-known/jwks.json',
  response_types_supported: ['code'],
}

/**
 * Build JSON:API list response wrapper for mock data.
 */
function jsonApiList(type: string, items: Record<string, unknown>[]) {
  return {
    data: items.map((item, i) => ({
      type,
      id: item.id ?? `${type}-${i + 1}`,
      attributes: Object.fromEntries(Object.entries(item).filter(([k]) => k !== 'id')),
    })),
    metadata: {
      totalCount: items.length,
      currentPage: 0,
      pageSize: 500,
      totalPages: 1,
    },
  }
}

// Create mock fetch function
// The bootstrap config is now composed from 4 parallel JSON:API calls:
//   /api/ui-pages, /api/ui-menus, /api/oidc-providers, /api/tenants
function createMockFetch(overrides: Record<string, unknown> = {}) {
  const cfg = (overrides.bootstrapConfig || mockBootstrapConfig) as Record<string, unknown>

  return vi.fn(async (input: RequestInfo | URL) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url

    // Handle /api/ui-pages
    if (url.includes('/api/ui-pages')) {
      const pages = (cfg.pages as Record<string, unknown>[]) || []
      return {
        ok: true,
        json: async () => jsonApiList('ui-pages', pages),
      } as Response
    }

    // Handle /api/ui-menus
    if (url.includes('/api/ui-menus')) {
      const menus = (cfg.menus as Record<string, unknown>[]) || []
      return {
        ok: true,
        json: async () => jsonApiList('ui-menus', menus),
      } as Response
    }

    // Handle /api/oidc-providers
    if (url.includes('/api/oidc-providers')) {
      const providers = (cfg.oidcProviders as Record<string, unknown>[]) || []
      return {
        ok: true,
        json: async () => jsonApiList('oidc-providers', providers),
      } as Response
    }

    // Handle /api/tenants
    if (url.includes('/api/tenants')) {
      return {
        ok: true,
        json: async () =>
          jsonApiList('tenants', [{ id: 'tenant-1', slug: 'default', name: 'Default Tenant' }]),
      } as Response
    }

    if (url.includes('.well-known/openid-configuration')) {
      return {
        ok: true,
        json: async () => mockDiscoveryDoc,
      } as Response
    }

    if (url === mockDiscoveryDoc.token_endpoint) {
      if (overrides.tokenError) {
        return {
          ok: false,
          statusText: 'Unauthorized',
        } as Response
      }
      return {
        ok: true,
        json: async () =>
          overrides.tokenResponse || {
            access_token: 'new-access-token',
            id_token: 'new-id-token',
            refresh_token: 'new-refresh-token',
            token_type: 'Bearer',
            expires_in: 3600,
          },
      } as Response
    }

    return {
      ok: false,
      statusText: 'Not Found',
    } as Response
  })
}

// Test component that uses useAuth
function TestComponent({ onRender }: { onRender?: (auth: ReturnType<typeof useAuth>) => void }) {
  const auth = useAuth()
  onRender?.(auth)
  return (
    <div>
      <div data-testid="loading">{auth.isLoading ? 'loading' : 'not-loading'}</div>
      <div data-testid="authenticated">
        {auth.isAuthenticated ? 'authenticated' : 'not-authenticated'}
      </div>
      <div data-testid="user">{auth.user ? auth.user.email : 'no-user'}</div>
      <div data-testid="error">{auth.error ? auth.error.message : 'no-error'}</div>
      <button onClick={() => auth.login().catch(() => {})}>Login</button>
      <button onClick={() => auth.logout()}>Logout</button>
    </div>
  )
}

// Helper to render with AuthProvider
function renderWithAuth(ui: ReactNode = <TestComponent />) {
  return render(<AuthProvider>{ui}</AuthProvider>)
}

// Helper to create a valid JWT token
function createMockJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const body = btoa(JSON.stringify(payload))
  return `${header}.${body}.signature`
}

describe('AuthContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearBootstrapCache()
    __resetRefreshStateForTests()

    // Clear mock storage
    Object.keys(mockSessionStorage).forEach((key) => delete mockSessionStorage[key])

    // Reset location mock
    mockLocationHref = 'http://localhost:3000/dashboard'

    // Setup sessionStorage mock
    Object.defineProperty(window, 'sessionStorage', {
      value: sessionStorageMock,
      writable: true,
    })

    // Setup location mock
    delete (window as { location?: Location }).location
    Object.defineProperty(window, 'location', {
      value: {
        origin: 'http://localhost:3000',
        pathname: '/dashboard',
        search: '',
        get href() {
          return mockLocationHref
        },
        set href(value: string) {
          mockLocationHref = value
        },
      },
      writable: true,
      configurable: true,
    })

    // Setup history mock
    Object.defineProperty(window, 'history', {
      value: {
        replaceState: vi.fn(),
      },
      writable: true,
    })

    // Setup default fetch mock
    global.fetch = createMockFetch()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    global.fetch = originalFetch

    // Restore original location
    Object.defineProperty(window, 'location', {
      value: originalLocation,
      writable: true,
      configurable: true,
    })
  })

  describe('Initial State', () => {
    it('should start in loading state', async () => {
      renderWithAuth()

      // Initially loading
      expect(screen.getByTestId('loading')).toHaveTextContent('loading')

      // Wait for initialization to complete
      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })
    })

    it('should be unauthenticated initially when no tokens stored', async () => {
      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })

      expect(screen.getByTestId('authenticated')).toHaveTextContent('not-authenticated')
      expect(screen.getByTestId('user')).toHaveTextContent('no-user')
    })

    it('should fetch bootstrap config via JSON:API endpoints on mount', async () => {
      const mockFetch = createMockFetch()
      global.fetch = mockFetch

      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })

      // Verify fetch was called with the 4 JSON:API endpoints
      expect(mockFetch).toHaveBeenCalled()
      const calledUrls = mockFetch.mock.calls.map((call: unknown[]) => {
        const arg = call[0]
        return typeof arg === 'string' ? arg : (arg as { url?: string })?.url
      })
      expect(calledUrls.some((u: string | undefined) => u?.includes('/api/ui-pages'))).toBe(true)
      expect(calledUrls.some((u: string | undefined) => u?.includes('/api/ui-menus'))).toBe(true)
      expect(calledUrls.some((u: string | undefined) => u?.includes('/api/oidc-providers'))).toBe(
        true
      )
      expect(calledUrls.some((u: string | undefined) => u?.includes('/api/tenants'))).toBe(true)
    })
  })

  describe('useAuth Hook', () => {
    it('should throw error when used outside AuthProvider', () => {
      // Suppress console.error for this test
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

      expect(() => {
        render(<TestComponent />)
      }).toThrow('useAuth must be used within an AuthProvider')

      consoleSpy.mockRestore()
    })

    it('should provide auth context value', async () => {
      let authValue: ReturnType<typeof useAuth> | undefined

      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )

      await waitFor(() => {
        expect(authValue).toBeDefined()
        expect(authValue?.isLoading).toBe(false)
      })

      expect(authValue?.user).toBeNull()
      expect(authValue?.isAuthenticated).toBe(false)
      expect(typeof authValue?.login).toBe('function')
      expect(typeof authValue?.logout).toBe('function')
      expect(typeof authValue?.getAccessToken).toBe('function')
    })
  })

  describe('Token Storage (Requirement 2.3)', () => {
    it('should restore user from stored tokens on mount', async () => {
      // Create a valid JWT token
      const payload = {
        sub: 'user-123',
        email: 'test@example.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }
      const mockToken = createMockJwt(payload)

      // Store tokens before rendering
      const storedTokens = {
        accessToken: mockToken,
        idToken: mockToken,
        expiresAt: Date.now() + 3600000,
      }
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify(storedTokens)

      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })

      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      expect(screen.getByTestId('user')).toHaveTextContent('test@example.com')
    })
  })

  describe('Logout (Requirement 2.6)', () => {
    it('should clear tokens and redirect on logout', async () => {
      // Setup authenticated state
      const payload = {
        sub: 'user-123',
        email: 'test@example.com',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }
      const mockToken = createMockJwt(payload)

      const storedTokens = {
        accessToken: mockToken,
        idToken: mockToken,
        expiresAt: Date.now() + 3600000,
      }
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify(storedTokens)
      mockSessionStorage['kelta_auth_provider_id'] = 'internal-1'

      const user = userEvent.setup()
      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      })

      // Click logout
      await user.click(screen.getByText('Logout'))

      // Should have cleared storage
      expect(sessionStorageMock.removeItem).toHaveBeenCalled()
    })

    it('should route logout through the SP-initiated SAML Single Logout initiator', async () => {
      const payload = {
        sub: 'user-123',
        email: 'test@example.com',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }
      const mockToken = createMockJwt(payload)
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify({
        accessToken: mockToken,
        idToken: mockToken,
        expiresAt: Date.now() + 3600000,
      })
      mockSessionStorage['kelta_auth_provider_id'] = 'internal-1'

      const user = userEvent.setup()
      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      })

      await user.click(screen.getByText('Logout'))

      // Navigates to the SAML logout initiator (on the auth origin), passing the
      // OIDC end-session URL as the post-logout target so a SAML session is logged
      // out at the IdP first, then the OIDC session, then back to the app.
      await waitFor(() => {
        expect(mockLocationHref).toContain('https://auth.example.com/logout/saml2/initiate')
      })
      expect(mockLocationHref).toContain('post_logout_redirect_uri=')
      const initiated = new URL(mockLocationHref)
      const target = initiated.searchParams.get('post_logout_redirect_uri') ?? ''
      expect(target).toContain('https://auth.example.com/logout')
      expect(target).toContain('id_token_hint=')
    })
  })

  describe('getAccessToken (Requirement 2.7)', () => {
    it('should return access token when valid', async () => {
      const payload = {
        sub: 'user-123',
        email: 'test@example.com',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }
      const mockToken = createMockJwt(payload)

      const storedTokens = {
        accessToken: mockToken,
        idToken: mockToken,
        expiresAt: Date.now() + 3600000,
      }
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify(storedTokens)

      let authValue: ReturnType<typeof useAuth> | undefined

      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )

      await waitFor(() => {
        expect(authValue?.isLoading).toBe(false)
      })

      const token = await authValue?.getAccessToken()
      expect(token).toBe(mockToken)
    })

    it('should throw error when no tokens available', async () => {
      let authValue: ReturnType<typeof useAuth> | undefined

      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )

      await waitFor(() => {
        expect(authValue?.isLoading).toBe(false)
      })

      await expect(authValue?.getAccessToken()).rejects.toThrow('No tokens available')
    })
  })

  describe('Error Handling', () => {
    it('should handle bootstrap config fetch failure gracefully', async () => {
      global.fetch = vi.fn(async () => ({
        ok: false,
        statusText: 'Internal Server Error',
      })) as unknown as typeof fetch

      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })

      // Should still render but with no providers
      expect(screen.getByTestId('authenticated')).toHaveTextContent('not-authenticated')
    })

    it('should handle invalid stored tokens gracefully', async () => {
      // Store invalid JSON
      mockSessionStorage['kelta_auth_tokens'] = 'invalid-json'

      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })

      // Should be unauthenticated
      expect(screen.getByTestId('authenticated')).toHaveTextContent('not-authenticated')
    })
  })

  describe('Login Routes Through Internal Provider', () => {
    it('targets the internal provider and forwards an idp_hint for externals', async () => {
      let authValue: ReturnType<typeof useAuth> | undefined

      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )

      await waitFor(() => {
        expect(authValue?.isLoading).toBe(false)
      })

      await authValue?.login('provider-1').catch(() => {})

      // The browser is redirected to kelta-auth (the internal provider's
      // authorization endpoint) with idp_hint = {tenantId}:{externalProviderId}.
      // The external IdP token is never seen by the SPA — kelta-auth federates
      // server-side and mints its own platform JWT.
      expect(mockLocationHref).toContain('https://auth.example.com/authorize')
      expect(mockLocationHref).toContain('client_id=kelta-platform')
      expect(mockLocationHref).toContain('idp_hint=tenant-1%3Aprovider-1')

      // Stored PROVIDER_ID is the internal provider so refresh and callback
      // always target kelta-auth, never the external IdP.
      expect(mockSessionStorage['kelta_auth_provider_id']).toBe('internal-1')
    })
  })

  describe('Proactive Refresh (Requirement 2.4)', () => {
    const tokenEndpointCalls = () =>
      (global.fetch as ReturnType<typeof vi.fn>).mock.calls.filter(
        ([input]) => String(input) === mockDiscoveryDoc.token_endpoint
      ).length

    function storeValidTokens(expiresInMs: number) {
      const payload = {
        sub: 'user-123',
        email: 'test@example.com',
        exp: Math.floor((Date.now() + expiresInMs) / 1000),
      }
      const mockToken = createMockJwt(payload)
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify({
        accessToken: mockToken,
        idToken: mockToken,
        refreshToken: 'stored-refresh-token',
        expiresAt: Date.now() + expiresInMs,
      })
    }

    afterEach(() => {
      vi.useRealTimers()
    })

    /** Replace the token endpoint's response; everything else keeps the default mock. */
    function mockTokenEndpoint(handler: () => Promise<Response> | Response) {
      const baseFetch = createMockFetch()
      global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === mockDiscoveryDoc.token_endpoint) {
          return handler()
        }
        return baseFetch(input)
      }) as typeof fetch
    }

    it('retries with backoff after a failed proactive refresh instead of abandoning the session', async () => {
      vi.useFakeTimers()
      storeValidTokens(10 * 60 * 1000) // expires in 10 minutes → proactive refresh at 5 minutes

      // Token endpoint fails with a network error (transient)
      mockTokenEndpoint(() => {
        throw new Error('network down')
      })

      renderWithAuth()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      expect(tokenEndpointCalls()).toBe(0)

      // Advance to the proactive refresh point (expiry - 5 min): first attempt fails
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 60 * 1000 + 100)
      })
      expect(tokenEndpointCalls()).toBe(1)

      // Retry after the 5s base backoff
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 1000 + 100)
      })
      expect(tokenEndpointCalls()).toBe(2)

      // Then 10s (doubled)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 1000)
      })
      expect(tokenEndpointCalls()).toBe(2)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 1000 + 100)
      })
      expect(tokenEndpointCalls()).toBe(3)

      // Transient failures never discard the refresh token or the session
      const stored = JSON.parse(mockSessionStorage['kelta_auth_tokens'])
      expect(stored.refreshToken).toBe('stored-refresh-token')
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
    })

    it('treats a 5xx from the token endpoint as transient and keeps the refresh token', async () => {
      vi.useFakeTimers()
      storeValidTokens(60 * 1000) // already inside the proactive window → refresh at once
      mockTokenEndpoint(
        () =>
          ({
            ok: false,
            status: 502,
            statusText: 'Bad Gateway',
            json: async () => {
              throw new Error('not json')
            },
          }) as unknown as Response
      )

      renderWithAuth()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(100)
      })
      expect(tokenEndpointCalls()).toBe(1)
      const stored = JSON.parse(mockSessionStorage['kelta_auth_tokens'])
      expect(stored.refreshToken).toBe('stored-refresh-token')
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')

      // Backoff retry keeps going
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 1000 + 100)
      })
      expect(tokenEndpointCalls()).toBe(2)
    })

    it('drops the refresh token and ends the session on invalid_grant', async () => {
      vi.useFakeTimers()
      storeValidTokens(60 * 1000)
      mockTokenEndpoint(
        () =>
          ({
            ok: false,
            status: 400,
            statusText: 'Bad Request',
            json: async () => ({ error: 'invalid_grant', error_description: 'revoked' }),
          }) as unknown as Response
      )

      let authValue: ReturnType<typeof useAuth> | undefined
      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(100)
      })
      expect(tokenEndpointCalls()).toBe(1)
      expect(JSON.parse(mockSessionStorage['kelta_auth_tokens']).refreshToken).toBeUndefined()

      // No retry is armed for a terminal failure
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 60 * 1000)
      })
      expect(tokenEndpointCalls()).toBe(1)

      // The next token request ends the session with a terminal error
      vi.setSystemTime(Date.now() + 2 * 60 * 1000)
      let thrown: unknown
      await act(async () => {
        await authValue!.getAccessToken().catch((e) => {
          thrown = e
        })
      })
      expect(thrown).toBeInstanceOf(SessionExpiredError)
      expect(mockSessionStorage['kelta_auth_tokens']).toBeUndefined()
      expect(screen.getByTestId('authenticated')).toHaveTextContent('not-authenticated')
    })

    it('getAccessToken keeps the session and returns the current token on a transient failure', async () => {
      vi.useFakeTimers()
      storeValidTokens(2 * 60 * 1000) // inside proactive window, still valid for 2 min
      const currentToken = JSON.parse(mockSessionStorage['kelta_auth_tokens']).accessToken
      mockTokenEndpoint(() => {
        throw new Error('network down')
      })

      let authValue: ReturnType<typeof useAuth> | undefined
      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )
      await act(async () => {
        await vi.advanceTimersByTimeAsync(100)
      })

      // Inside the 30s API buffer but before real expiry: refresh fails, token still handed back
      vi.setSystemTime(Date.now() + 2 * 60 * 1000 - 10 * 1000)
      let token: string | undefined
      await act(async () => {
        token = await authValue!.getAccessToken()
      })
      expect(token).toBe(currentToken)
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')

      // Past real expiry: this one request fails, but the session is NOT torn down
      vi.setSystemTime(Date.now() + 60 * 1000)
      let thrown: unknown
      await act(async () => {
        await authValue!.getAccessToken().catch((e) => {
          thrown = e
        })
      })
      expect(thrown).toBeInstanceOf(TokenUnavailableError)
      expect(JSON.parse(mockSessionStorage['kelta_auth_tokens']).refreshToken).toBe(
        'stored-refresh-token'
      )
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
    })

    it('getAccessToken({ force: true }) refreshes an unexpired token after a server-side 401', async () => {
      storeValidTokens(60 * 60 * 1000)

      let authValue: ReturnType<typeof useAuth> | undefined
      renderWithAuth(
        <TestComponent
          onRender={(auth) => {
            authValue = auth
          }}
        />
      )
      await waitFor(() => expect(authValue?.isLoading).toBe(false))
      expect(tokenEndpointCalls()).toBe(0)

      const token = await authValue!.getAccessToken({ force: true })
      expect(token).toBe('new-access-token')
      expect(tokenEndpointCalls()).toBe(1)
    })

    it('refreshes an expired token on page load using the freshly loaded providers', async () => {
      // Regression: initAuth's first-render closure saw providers=[] and the
      // refresh "failed" with no internal provider, clearing the session on
      // every reload with an expired token.
      const payload = { sub: 'user-123', email: 'test@example.com', exp: 0 }
      const mockToken = createMockJwt(payload)
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify({
        accessToken: mockToken,
        idToken: mockToken,
        refreshToken: 'stored-refresh-token',
        expiresAt: Date.now() - 60 * 60 * 1000,
      })

      renderWithAuth()
      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })
      expect(tokenEndpointCalls()).toBe(1)
      const stored = JSON.parse(mockSessionStorage['kelta_auth_tokens'])
      expect(stored.accessToken).toBe('new-access-token')
      expect(stored.refreshToken).toBe('new-refresh-token')
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
    })

    it('keeps the session on page load when the refresh fails transiently', async () => {
      const payload = { sub: 'user-123', email: 'test@example.com', exp: 0 }
      const mockToken = createMockJwt(payload)
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify({
        accessToken: mockToken,
        idToken: mockToken,
        refreshToken: 'stored-refresh-token',
        expiresAt: Date.now() - 60 * 60 * 1000,
      })
      mockTokenEndpoint(() => {
        throw new Error('network down')
      })

      renderWithAuth()
      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })
      expect(tokenEndpointCalls()).toBe(1)
      expect(JSON.parse(mockSessionStorage['kelta_auth_tokens']).refreshToken).toBe(
        'stored-refresh-token'
      )
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
    })

    it('refreshes when the network comes back online inside the proactive window', async () => {
      vi.useFakeTimers()
      storeValidTokens(60 * 60 * 1000)

      renderWithAuth()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(tokenEndpointCalls()).toBe(0)

      // Wall clock jumps to inside the proactive window without timers firing
      vi.setSystemTime(Date.now() + 57 * 60 * 1000)
      await act(async () => {
        window.dispatchEvent(new Event('online'))
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(tokenEndpointCalls()).toBe(1)
      expect(JSON.parse(mockSessionStorage['kelta_auth_tokens']).accessToken).toBe(
        'new-access-token'
      )
    })

    it('refreshes immediately when the tab wakes inside the expiry window', async () => {
      vi.useFakeTimers()
      storeValidTokens(5 * 60 * 1000)

      renderWithAuth()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(screen.getByTestId('authenticated')).toHaveTextContent('authenticated')
      expect(tokenEndpointCalls()).toBe(0)

      // Simulate a long sleep: wall clock jumps past expiry without timers firing
      vi.setSystemTime(Date.now() + 60 * 60 * 1000)

      await act(async () => {
        document.dispatchEvent(new Event('visibilitychange'))
        await vi.advanceTimersByTimeAsync(0)
      })

      expect(tokenEndpointCalls()).toBe(1)
      const stored = JSON.parse(mockSessionStorage['kelta_auth_tokens'])
      expect(stored.accessToken).toBe('new-access-token')
    })
  })

  describe('Token Expiration', () => {
    it('should clear expired tokens without refresh token', async () => {
      // Create expired tokens without refresh token
      const payload = {
        sub: 'user-123',
        email: 'test@example.com',
        exp: Math.floor(Date.now() / 1000) - 3600, // Expired
      }
      const mockToken = createMockJwt(payload)

      const storedTokens = {
        accessToken: mockToken,
        idToken: mockToken,
        expiresAt: Date.now() - 3600000, // Expired
      }
      mockSessionStorage['kelta_auth_tokens'] = JSON.stringify(storedTokens)

      renderWithAuth()

      await waitFor(() => {
        expect(screen.getByTestId('loading')).toHaveTextContent('not-loading')
      })

      // Should be unauthenticated after expired tokens are cleared
      expect(screen.getByTestId('authenticated')).toHaveTextContent('not-authenticated')
    })
  })
})
