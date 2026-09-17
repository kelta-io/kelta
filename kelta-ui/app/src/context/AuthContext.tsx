/**
 * Authentication Context
 *
 * Provides authentication state and OIDC authentication flow for the application.
 * Implements redirect-based OIDC authentication with token storage and refresh.
 *
 * Requirements:
 * - 2.1: Redirect unauthenticated users to OIDC provider login
 * - 2.2: Display provider selection page for multiple providers
 * - 2.3: Store access token securely and redirect after auth
 * - 2.4: Attempt silent token refresh when token expires
 * - 2.5: Redirect to login if refresh fails
 * - 2.6: Clear tokens and redirect on logout
 * - 2.7: Include access token in all API requests
 * - 2.8: Trigger token refresh on 401 responses
 */

import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
  useRef,
} from 'react'
import type {
  User,
  AuthContextValue,
  AuthProviderProps,
  StoredTokens,
  TokenResponse,
  OIDCDiscoveryDocument,
} from '../types/auth'
import type { OIDCProviderSummary } from '../types/config'
import { fetchBootstrapConfig } from '../utils/bootstrapCache'
import { SessionExpiredError, TokenUnavailableError } from './authErrors'
import { getTenantSlug, setResolvedTenantId, getResolvedTenantId } from './TenantContext'

/**
 * Find the internal kelta-auth provider in a list of OIDC providers.
 * The internal provider is the OAuth target for ALL logins — external providers
 * are passed as an idp_hint so kelta-auth handles the federation server-side
 * and mints a platform-issued token.
 */
function findInternalProvider(providers: OIDCProviderSummary[]): OIDCProviderSummary | undefined {
  return providers.find((p) => p.isInternal === true)
}

// Storage keys
const STORAGE_KEYS = {
  TOKENS: 'kelta_auth_tokens',
  STATE: 'kelta_auth_state',
  NONCE: 'kelta_auth_nonce',
  CODE_VERIFIER: 'kelta_auth_code_verifier',
  REDIRECT_PATH: 'kelta_auth_redirect_path',
  REDIRECT_URI: 'kelta_auth_redirect_uri',
  PROVIDER_ID: 'kelta_auth_provider_id',
  CALLBACK_PROCESSED: 'kelta_auth_callback_processed',
  LOGIN_ERROR: 'kelta_auth_login_error',
  JUST_LOGGED_OUT: 'kelta_auth_just_logged_out',
} as const

// Token refresh buffer: an access token is treated as expired for API calls
// 30 seconds before its real expiry so a request never leaves with a token
// that dies in flight.
const TOKEN_REFRESH_BUFFER_MS = 30 * 1000

// Proactive refresh lead: the background timer renews the token this long
// before expiry. Far larger than the buffer on purpose — it leaves room for
// several retries (auth-pod rollout, laptop waking before Wi-Fi is back)
// while the current token is still perfectly usable.
const PROACTIVE_REFRESH_LEAD_MS = 5 * 60 * 1000

// Retry backoff after a transient refresh failure. Doubles per consecutive
// failure, capped, and resets on success.
const REFRESH_RETRY_BASE_MS = 5 * 1000
const REFRESH_RETRY_MAX_MS = 60 * 1000

// Cooldown after a failed refresh attempt: callers that hit refresh inside this
// window get the last outcome instead of firing another request.
const REFRESH_FAILURE_COOLDOWN_MS = 5 * 1000

/**
 * Outcome of a refresh attempt.
 *
 * - `ok`        new tokens stored
 * - `transient` the attempt failed for a reason that may clear on its own
 *               (network error, 5xx/429 from the token endpoint, bootstrap
 *               config not loaded yet). The refresh token is KEPT and the
 *               session stays alive — retry later.
 * - `terminal`  the authorization server rejected the refresh token itself
 *               (`invalid_grant`: revoked, expired, or rotated away) or there
 *               is nothing to refresh with. Only this outcome ends the session.
 */
type RefreshResult =
  | { status: 'ok'; tokens: StoredTokens }
  | { status: 'transient' }
  | { status: 'terminal' }

// Module-level state for refresh deduplication and failure tracking.
// Shared across all callers within the same page load.
let inflightRefreshPromise: Promise<RefreshResult> | null = null
let lastRefreshFailureTime: number = 0
let lastRefreshResult: RefreshResult | null = null
let consecutiveRefreshFailures: number = 0

/** Reset module refresh state (exported for tests only). */
// eslint-disable-next-line react-refresh/only-export-components
export function __resetRefreshStateForTests(): void {
  inflightRefreshPromise = null
  lastRefreshFailureTime = 0
  lastRefreshResult = null
  consecutiveRefreshFailures = 0
}

/**
 * Classify a non-2xx token-endpoint response. Only an explicit OAuth
 * `invalid_grant` (or a 401 — client rejected outright) means the refresh
 * token is dead; everything else (502/503 during a rollout, 429, a stray
 * HTML error page from the ingress) is treated as transient.
 */
async function classifyRefreshFailure(response: Response): Promise<RefreshResult> {
  if (response.status === 401) return { status: 'terminal' }
  if (response.status === 400) {
    try {
      const body = (await response.json()) as { error?: string }
      if (body?.error === 'invalid_grant') return { status: 'terminal' }
    } catch {
      // Not JSON — an ingress error page; fall through to transient.
    }
  }
  return { status: 'transient' }
}

/**
 * Generate a random string for state/nonce/code_verifier
 */
function generateRandomString(length: number = 32): string {
  const array = new Uint8Array(length)
  crypto.getRandomValues(array)
  return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * Generate code challenge from code verifier (PKCE)
 */
async function generateCodeChallenge(codeVerifier: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(codeVerifier)
  const digest = await crypto.subtle.digest('SHA-256', data)
  return btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/**
 * Parse JWT token to extract claims
 */
function parseJwt(token: string): Record<string, unknown> | null {
  try {
    const base64Url = token.split('.')[1]
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    return JSON.parse(jsonPayload)
  } catch {
    return null
  }
}

/**
 * Extract user information from ID token or access token.
 */
function extractUserFromToken(idToken?: string, accessToken?: string): User | null {
  const token = idToken || accessToken
  if (!token) return null

  const claims = parseJwt(token)
  if (!claims) return null

  return {
    id: (claims.sub as string) || '',
    email: (claims.email as string) || '',
    name: (claims.name as string) || (claims.preferred_username as string),
    picture: claims.picture as string | undefined,
    claims,
  }
}

/**
 * Check if tokens are expired or about to expire
 */
function isTokenExpired(expiresAt: number): boolean {
  return Date.now() >= expiresAt - TOKEN_REFRESH_BUFFER_MS
}

/**
 * Store tokens in sessionStorage (more secure than localStorage)
 */
function storeTokens(tokens: StoredTokens): void {
  sessionStorage.setItem(STORAGE_KEYS.TOKENS, JSON.stringify(tokens))
}

/**
 * Retrieve stored tokens
 */
function getStoredTokens(): StoredTokens | null {
  const stored = sessionStorage.getItem(STORAGE_KEYS.TOKENS)
  if (!stored) return null
  try {
    return JSON.parse(stored) as StoredTokens
  } catch {
    return null
  }
}

/**
 * Clear all stored auth data
 */
function clearAuthStorage(): void {
  Object.values(STORAGE_KEYS).forEach((key) => {
    sessionStorage.removeItem(key)
  })
}

// Module-level guard to prevent concurrent login() calls within the same page load.
// Using a module variable (not sessionStorage) ensures it resets on page reload,
// so the callback page gets a clean slate after returning from the IdP.
let loginInProgress = false

// Create the context with undefined default
const AuthContext = createContext<AuthContextValue | undefined>(undefined)

/**
 * Authentication Provider Component
 *
 * Wraps the application to provide authentication state and methods.
 */
export function AuthProvider({
  children,
  redirectUri = window.location.origin + '/auth/callback',
  postLogoutRedirectUri = window.location.origin,
}: AuthProviderProps): React.ReactElement {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [providers, setProviders] = useState<OIDCProviderSummary[]>([])
  const [discoveryDocs, setDiscoveryDocs] = useState<Map<string, OIDCDiscoveryDocument>>(new Map())

  const isAuthenticated = user !== null
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Providers are also mirrored in a ref so refresh paths that run from a stale
  // closure (initAuth's first-render closure, timers) see the loaded list. Before
  // this, a page reload with an expired token always failed its refresh: the
  // closure's `providers` was still `[]`, so it looked like "no internal
  // provider" and the session was cleared.
  const providersRef = useRef<OIDCProviderSummary[]>([])

  /**
   * Fetch OIDC discovery document for a provider
   */
  const fetchDiscoveryDocument = useCallback(
    async (issuer: string): Promise<OIDCDiscoveryDocument> => {
      // Check cache first
      const cached = discoveryDocs.get(issuer)
      if (cached) return cached

      const wellKnownUrl = `${issuer.replace(/\/$/, '')}/.well-known/openid-configuration`
      const response = await fetch(wellKnownUrl)
      if (!response.ok) {
        throw new Error(`Failed to fetch OIDC discovery document: ${response.statusText}`)
      }
      const doc = (await response.json()) as OIDCDiscoveryDocument

      // Cache the document
      setDiscoveryDocs((prev) => new Map(prev).set(issuer, doc))
      return doc
    },
    [discoveryDocs]
  )

  /**
   * Fetch available OIDC providers from bootstrap config.
   * Uses shared cache to avoid duplicate request (ConfigContext also reads bootstrap).
   */
  const fetchProviders = useCallback(async (): Promise<OIDCProviderSummary[]> => {
    try {
      const config = (await fetchBootstrapConfig()) as Record<string, unknown>
      // Store resolved tenant ID from bootstrap response
      if (config.tenantId) {
        setResolvedTenantId(config.tenantId as string)
      }
      return (config.oidcProviders as OIDCProviderSummary[]) || []
    } catch (err) {
      // Don't throw - just return empty. ConfigProvider will handle the error display.
      console.warn('[Auth] Failed to fetch providers:', err)
      return []
    }
  }, [])

  /**
   * Perform the actual token refresh (called only by refreshAccessToken).
   * This function is NOT deduplication-aware — callers must go through refreshAccessToken.
   */
  const doRefresh = useCallback(async (): Promise<RefreshResult> => {
    const storedTokens = getStoredTokens()
    if (!storedTokens?.refreshToken) {
      return { status: 'terminal' }
    }

    // Refresh always targets the internal kelta-auth provider — it is the
    // issuer of the stored token regardless of which external IdP federated
    // the original login. Providers come from bootstrap config; if that has
    // not loaded (or failed to load) yet, that is a transient condition.
    const internal = findInternalProvider(providersRef.current)
    if (!internal) {
      return { status: 'transient' }
    }

    try {
      const discovery = await fetchDiscoveryDocument(internal.issuer)
      const tokenUrl = discovery.token_endpoint

      const params = new URLSearchParams({
        grant_type: 'refresh_token',
        client_id: internal.clientId,
        refresh_token: storedTokens.refreshToken,
      })

      const response = await fetch(tokenUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: params.toString(),
      })

      if (!response.ok) {
        const outcome = await classifyRefreshFailure(response)
        console.error(
          `[Auth] Token refresh failed (${response.status} ${response.statusText}) — ${outcome.status}`
        )
        if (outcome.status === 'terminal') {
          // The refresh token is dead — drop it so nothing retries with it.
          storeTokens({ ...storedTokens, refreshToken: undefined })
        }
        return outcome
      }

      const tokenResponse: TokenResponse = await response.json()
      const newTokens: StoredTokens = {
        accessToken: tokenResponse.access_token,
        idToken: tokenResponse.id_token,
        refreshToken: tokenResponse.refresh_token || storedTokens.refreshToken,
        expiresAt: Date.now() + tokenResponse.expires_in * 1000,
      }

      storeTokens(newTokens)
      const newUser = extractUserFromToken(newTokens.idToken, newTokens.accessToken)
      if (newUser) {
        setUser(newUser)
      }

      return { status: 'ok', tokens: newTokens }
    } catch (err) {
      // fetch() rejection = network down / DNS / CORS preflight during a
      // rollout. Never fatal on its own.
      console.error('[Auth] Token refresh error:', err)
      return { status: 'transient' }
    }
  }, [fetchDiscoveryDocument])

  /**
   * Refresh the access token using the refresh token.
   * Deduplicates concurrent calls — only one refresh request is in-flight at a time.
   * Enforces a short cooldown after failures to prevent retry storms; callers
   * inside the cooldown get the last outcome back rather than a fresh attempt.
   */
  const refreshAccessToken = useCallback(async (): Promise<RefreshResult> => {
    // If a refresh is already in-flight, wait for it instead of making a new request
    if (inflightRefreshPromise) {
      return inflightRefreshPromise
    }

    // If we just failed, replay that outcome until the cooldown expires
    if (lastRefreshFailureTime > 0 && lastRefreshResult) {
      const elapsed = Date.now() - lastRefreshFailureTime
      if (elapsed < REFRESH_FAILURE_COOLDOWN_MS) {
        return lastRefreshResult
      }
    }

    // Start the refresh and share the promise with any concurrent callers
    inflightRefreshPromise = doRefresh().then(
      (result) => {
        inflightRefreshPromise = null
        lastRefreshResult = result
        if (result.status === 'ok') {
          lastRefreshFailureTime = 0
          consecutiveRefreshFailures = 0
        } else {
          lastRefreshFailureTime = Date.now()
          consecutiveRefreshFailures += 1
        }
        return result
      },
      (err) => {
        // doRefresh catches everything itself; this is belt-and-braces.
        inflightRefreshPromise = null
        const result: RefreshResult = { status: 'transient' }
        lastRefreshResult = result
        lastRefreshFailureTime = Date.now()
        consecutiveRefreshFailures += 1
        console.error('[Auth] Unexpected refresh failure:', err)
        return result
      }
    )

    return inflightRefreshPromise
  }, [doRefresh])

  /**
   * Schedule a proactive token refresh before the token expires.
   * Refreshes PROACTIVE_REFRESH_LEAD_MS before expiry (or immediately if
   * already inside that window). A transient failure re-arms the timer with
   * exponential backoff and keeps going until the refresh succeeds or the
   * server says the refresh token is dead — the session is never abandoned
   * to die at expiry because one attempt happened to hit a blip.
   */
  const scheduleTokenRefresh = useCallback(() => {
    // Clear any existing timer
    if (refreshTimerRef.current) {
      clearTimeout(refreshTimerRef.current)
      refreshTimerRef.current = null
    }

    const storedTokens = getStoredTokens()
    if (!storedTokens?.refreshToken || !storedTokens.expiresAt) {
      return
    }

    const refreshAt = storedTokens.expiresAt - PROACTIVE_REFRESH_LEAD_MS
    const delay = Math.max(refreshAt - Date.now(), 0)

    refreshTimerRef.current = setTimeout(async () => {
      refreshTimerRef.current = null
      const result = await refreshAccessToken()
      if (result.status === 'ok') {
        // Schedule the next refresh after this one succeeds
        scheduleTokenRefresh()
      } else if (result.status === 'transient' && getStoredTokens()?.refreshToken) {
        const backoff = Math.min(
          REFRESH_RETRY_BASE_MS * 2 ** Math.max(consecutiveRefreshFailures - 1, 0),
          REFRESH_RETRY_MAX_MS
        )
        console.warn(`[Auth] Proactive token refresh failed; retrying in ${backoff / 1000}s`)
        refreshTimerRef.current = setTimeout(() => {
          refreshTimerRef.current = null
          scheduleTokenRefresh()
        }, backoff)
      }
      // terminal: nothing to re-arm. The next API call's getAccessToken (or
      // the 401 interceptor) ends the session and routes to login.
    }, delay)
  }, [refreshAccessToken])

  /**
   * Get the current access token, refreshing if necessary
   * Requirement 2.4: Attempt silent token refresh when token expires
   * Requirement 2.7: Include access token in all API requests
   *
   * Only a TERMINAL refresh failure (refresh token rejected or absent) ends the
   * session. A transient failure keeps the stored tokens: if the access token is
   * still within its real lifetime it is returned as-is, otherwise a
   * `TokenUnavailableError` is thrown so the caller can fail this one request
   * without logging the user out — the background timer keeps retrying.
   *
   * @param options.force refresh even if the token is not locally expired
   *   (used by the 401 interceptor: the server has rejected the token, so
   *   the local clock is not to be trusted).
   */
  const getAccessToken = useCallback(
    async (options?: { force?: boolean }): Promise<string> => {
      const storedTokens = getStoredTokens()

      if (!storedTokens) {
        throw new SessionExpiredError('No tokens available. Please log in.')
      }

      if (!options?.force && !isTokenExpired(storedTokens.expiresAt)) {
        return storedTokens.accessToken
      }

      const result = await refreshAccessToken()
      if (result.status === 'ok') {
        return result.tokens.accessToken
      }
      if (result.status === 'terminal') {
        // Requirement 2.5: Redirect to login if refresh fails
        clearAuthStorage()
        setUser(null)
        throw new SessionExpiredError('Session expired. Please log in again.')
      }

      // Transient: keep the session. Hand back the current token if it has
      // not actually expired yet (a forced refresh after a 401 can only
      // fall through here when the server disagrees with our clock).
      if (!options?.force && Date.now() < storedTokens.expiresAt) {
        return storedTokens.accessToken
      }
      throw new TokenUnavailableError(
        'Could not refresh the access token; will retry. Please try again.'
      )
    },
    [refreshAccessToken]
  )

  /**
   * Initiate login flow
   * Requirement 2.1: Redirect unauthenticated users to OIDC provider login
   * Requirement 2.2: Display provider selection page for multiple providers
   */
  const login = useCallback(
    async (providerId?: string): Promise<void> => {
      // Guard against concurrent login calls within the same page load.
      // Uses a module-level variable (not sessionStorage) so it resets on page reload,
      // ensuring the callback page gets a clean slate after returning from the IdP.
      if (loginInProgress) {
        console.log('[Auth] Login already in progress, skipping duplicate call')
        return
      }
      loginInProgress = true

      setError(null)

      // If no provider specified and multiple providers available, redirect to login page
      if (!providerId && providers.length > 1) {
        // Store current path for redirect after auth
        sessionStorage.setItem(STORAGE_KEYS.REDIRECT_PATH, window.location.pathname)
        loginInProgress = false
        const slug = getTenantSlug()
        window.location.href = `/${slug}/login`
        return
      }

      // All logins authenticate against the internal kelta-auth provider.
      // When the caller picks an external provider, we forward its id as an
      // idp_hint so kelta-auth runs the federation server-side and returns
      // a platform-issued token (iss=auth.kelta.io).
      const internal = findInternalProvider(providers)
      if (!internal) {
        loginInProgress = false
        throw new Error('Internal kelta-auth provider not configured for this tenant')
      }

      const selectedProviderId = providerId || internal.id
      const target = providers.find((p) => p.id === selectedProviderId)
      if (!target) {
        loginInProgress = false
        throw new Error(`Provider not found: ${selectedProviderId}`)
      }

      try {
        // Clear any previous login error
        sessionStorage.removeItem(STORAGE_KEYS.LOGIN_ERROR)

        // Generate all auth parameters BEFORE any async operations to minimize
        // the window for race conditions
        const codeVerifier = generateRandomString(64)
        const state = generateRandomString(32)
        const nonce = generateRandomString(32)

        // Store auth parameters immediately (before async calls). PROVIDER_ID
        // tracks the internal provider so refresh and callback always target
        // kelta-auth — the selected external provider is only a hint.
        sessionStorage.setItem(STORAGE_KEYS.CODE_VERIFIER, codeVerifier)
        sessionStorage.setItem(STORAGE_KEYS.STATE, state)
        sessionStorage.setItem(STORAGE_KEYS.NONCE, nonce)
        sessionStorage.setItem(STORAGE_KEYS.PROVIDER_ID, internal.id)
        sessionStorage.setItem(STORAGE_KEYS.REDIRECT_PATH, window.location.pathname)
        sessionStorage.setItem(STORAGE_KEYS.REDIRECT_URI, redirectUri)

        // Now do async operations (these are what caused the race condition)
        const discovery = await fetchDiscoveryDocument(internal.issuer)
        const codeChallenge = await generateCodeChallenge(codeVerifier)

        // Build authorization URL targeting kelta-auth (the internal provider).
        const authUrl = new URL(discovery.authorization_endpoint)
        authUrl.searchParams.set('response_type', 'code')
        authUrl.searchParams.set('client_id', internal.clientId)
        authUrl.searchParams.set('scope', 'openid profile email')
        authUrl.searchParams.set('redirect_uri', redirectUri)
        authUrl.searchParams.set('state', state)
        authUrl.searchParams.set('nonce', nonce)
        authUrl.searchParams.set('code_challenge', codeChallenge)
        authUrl.searchParams.set('code_challenge_method', 'S256')

        // For external providers, pass an idp_hint matching the kelta-auth
        // ClientRegistration id ({tenantId}:{providerId}) so kelta-auth auto-
        // federates to that IdP without rendering its login page.
        if (!target.isInternal) {
          const tenantId = getResolvedTenantId()
          const hint = tenantId ? `${tenantId}:${target.id}` : target.id
          authUrl.searchParams.set('idp_hint', hint)
        }

        // A remembered-user link from the slug-less root carries ?login_hint=<email>. Standard
        // OIDC; the IdP may pre-fill the identifier or ignore it. Passed only when well-formed.
        const loginHint = new URLSearchParams(window.location.search).get('login_hint')
        if (loginHint && /^[^\s@]+@[^\s@]+$/.test(loginHint)) {
          authUrl.searchParams.set('login_hint', loginHint)
        }

        // If user just logged out, force the IdP to show the login form
        // instead of silently re-authenticating with the existing session
        if (sessionStorage.getItem(STORAGE_KEYS.JUST_LOGGED_OUT) === 'true') {
          authUrl.searchParams.set('prompt', 'login')
          sessionStorage.removeItem(STORAGE_KEYS.JUST_LOGGED_OUT)
        }

        // Redirect to authorization endpoint
        window.location.href = authUrl.toString()
      } catch (err) {
        loginInProgress = false
        const error = err instanceof Error ? err : new Error('Login failed')
        setError(error)
        throw error
      }
    },
    [providers, fetchDiscoveryDocument, redirectUri]
  )

  /**
   * Logout and clear tokens
   * Requirement 2.6: Clear tokens and redirect on logout
   */
  const logout = useCallback(async (): Promise<void> => {
    const storedTokens = getStoredTokens()
    const internal = findInternalProvider(providers)

    // Set logout flag BEFORE clearing storage so LoginPage knows to skip auto-login.
    // This survives clearAuthStorage() because it's set after the clear,
    // and it's more reliable than URL params which the IdP may strip.
    sessionStorage.setItem(STORAGE_KEYS.JUST_LOGGED_OUT, 'true')

    // Clear local auth state
    clearAuthStorage()
    setUser(null)
    setError(null)

    // Re-set the flag since clearAuthStorage just cleared it
    sessionStorage.setItem(STORAGE_KEYS.JUST_LOGGED_OUT, 'true')

    // Build logout redirect URL (keep ?logged_out=true as belt-and-suspenders with sessionStorage)
    const logoutRedirect = new URL(postLogoutRedirectUri)
    logoutRedirect.searchParams.set('logged_out', 'true')
    const logoutRedirectStr = logoutRedirect.toString()

    // Terminate the kelta-auth session via its OIDC end_session_endpoint.
    if (internal && storedTokens?.idToken) {
      try {
        const discovery = await fetchDiscoveryDocument(internal.issuer)
        if (discovery.end_session_endpoint) {
          const endSessionUrl = new URL(discovery.end_session_endpoint)
          endSessionUrl.searchParams.set('id_token_hint', storedTokens.idToken)
          endSessionUrl.searchParams.set('post_logout_redirect_uri', logoutRedirectStr)

          // Route through the SP-initiated SAML Single Logout initiator so a user
          // who signed in via SAML is also logged out at their IdP before the OIDC
          // end-session. For non-SAML sessions the initiator is a safe passthrough
          // that immediately redirects to the end-session URL.
          const samlLogoutUrl = new URL('/logout/saml2/initiate', discovery.end_session_endpoint)
          samlLogoutUrl.searchParams.set('post_logout_redirect_uri', endSessionUrl.toString())
          window.location.href = samlLogoutUrl.toString()
          return
        }
      } catch (err) {
        console.error('[Auth] OIDC logout failed:', err)
      }
    }

    // Fallback: redirect to home with logged_out flag
    window.location.href = logoutRedirectStr
  }, [providers, fetchDiscoveryDocument, postLogoutRedirectUri])

  /**
   * Handle the OAuth callback
   * Requirement 2.3: Store access token securely and redirect after auth
   */
  const handleCallback = useCallback(
    async (availableProviders: OIDCProviderSummary[]): Promise<void> => {
      const urlParams = new URLSearchParams(window.location.search)
      const code = urlParams.get('code')
      const state = urlParams.get('state')
      const errorParam = urlParams.get('error')
      const errorDescription = urlParams.get('error_description')

      if (errorParam) {
        throw new Error(errorDescription || errorParam)
      }

      // Validate state
      const storedState = sessionStorage.getItem(STORAGE_KEYS.STATE)
      if (!state || state !== storedState) {
        throw new Error('Invalid state parameter')
      }

      // Get stored parameters
      const codeVerifier = sessionStorage.getItem(STORAGE_KEYS.CODE_VERIFIER)

      if (!code || !codeVerifier) {
        throw new Error('Missing authentication parameters')
      }

      // Token exchange always targets the internal kelta-auth provider —
      // that is the OAuth client we initiated the flow with, regardless of
      // which external IdP federated the user.
      const internal = findInternalProvider(availableProviders)
      if (!internal) {
        throw new Error('Internal kelta-auth provider not configured for this tenant')
      }

      // Get discovery document
      const discovery = await fetchDiscoveryDocument(internal.issuer)

      // Use the stored redirect_uri (exact match with authorization request)
      const storedRedirectUri = sessionStorage.getItem(STORAGE_KEYS.REDIRECT_URI) || redirectUri

      // Exchange code for tokens
      const tokenUrl = discovery.token_endpoint
      const params = new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: internal.clientId,
        code,
        redirect_uri: storedRedirectUri,
        code_verifier: codeVerifier,
      })

      const response = await fetch(tokenUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: params.toString(),
      })

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}))
        throw new Error(
          `Token exchange failed: ${errorData.error_description || errorData.error || response.statusText}`
        )
      }

      const tokenResponse = (await response.json()) as TokenResponse

      const tokens: StoredTokens = {
        accessToken: tokenResponse.access_token,
        idToken: tokenResponse.id_token,
        refreshToken: tokenResponse.refresh_token,
        expiresAt: Date.now() + tokenResponse.expires_in * 1000,
      }

      storeTokens(tokens)

      const newUser = extractUserFromToken(tokens.idToken, tokens.accessToken)
      if (newUser) {
        setUser(newUser)
      } else {
        throw new Error('Failed to extract user information from tokens')
      }

      // Clean up temporary storage
      sessionStorage.removeItem(STORAGE_KEYS.STATE)
      sessionStorage.removeItem(STORAGE_KEYS.NONCE)
      sessionStorage.removeItem(STORAGE_KEYS.CODE_VERIFIER)

      const slug = getTenantSlug()
      const tenantBase = `/${slug}`
      let redirectPath = sessionStorage.getItem(STORAGE_KEYS.REDIRECT_PATH) || `${tenantBase}/app`
      sessionStorage.removeItem(STORAGE_KEYS.REDIRECT_PATH)

      // Redirect to app home if path points to login/callback/root pages
      if (
        redirectPath === '/login' ||
        redirectPath === '/auth/callback' ||
        redirectPath === `${tenantBase}/login` ||
        redirectPath === `${tenantBase}/auth/callback` ||
        redirectPath === '/' ||
        redirectPath === tenantBase
      ) {
        redirectPath = `${tenantBase}/app`
      }

      console.log('[Auth] Callback complete, redirecting to:', redirectPath)
      window.location.replace(window.location.origin + redirectPath)
    },
    [fetchDiscoveryDocument, redirectUri]
  )

  /**
   * Initialize authentication state on mount
   */
  useEffect(() => {
    const initAuth = async () => {
      setIsLoading(true)
      setError(null)

      try {
        // Check for previous login error (persisted in sessionStorage)
        const previousLoginError = sessionStorage.getItem(STORAGE_KEYS.LOGIN_ERROR)
        if (previousLoginError) {
          setError(new Error(previousLoginError))
        }

        // Check if this is a callback FIRST, before fetching providers
        const urlParams = new URLSearchParams(window.location.search)
        const code = urlParams.get('code')
        const isCallback = urlParams.has('code') || urlParams.has('error')

        // If this is a callback, check if we've already processed this specific code
        if (isCallback && code) {
          const processedCode = sessionStorage.getItem(STORAGE_KEYS.CALLBACK_PROCESSED)
          if (processedCode === code) {
            setIsLoading(false)
            return
          }
          // Mark this code as being processed
          sessionStorage.setItem(STORAGE_KEYS.CALLBACK_PROCESSED, code)
        }

        // Fetch available providers
        const availableProviders = await fetchProviders()
        providersRef.current = availableProviders
        setProviders(availableProviders)

        // If no providers available and this is a callback, we're in a bad state
        if (isCallback && availableProviders.length === 0) {
          setError(new Error('Authentication configuration unavailable. Please contact support.'))
          // Clear URL to prevent loop
          window.history.replaceState({}, document.title, window.location.pathname)
          clearAuthStorage()
          setIsLoading(false)
          return
        }

        // Handle OAuth callback
        if (isCallback) {
          try {
            await handleCallback(availableProviders)
            // Clear the processed code marker after successful processing
            sessionStorage.removeItem(STORAGE_KEYS.CALLBACK_PROCESSED)
          } catch (callbackError) {
            console.error('[Auth] Callback failed:', callbackError)
            const authErr =
              callbackError instanceof Error ? callbackError : new Error('Authentication failed')
            setError(authErr)

            // Store the error in sessionStorage so it persists across page loads
            // and prevents the auto-login loop
            sessionStorage.setItem(STORAGE_KEYS.LOGIN_ERROR, authErr.message)

            // Clear URL parameters and state to prevent infinite loop
            window.history.replaceState({}, document.title, window.location.pathname)
            // Clear auth storage but keep the LOGIN_ERROR flag
            sessionStorage.removeItem(STORAGE_KEYS.STATE)
            sessionStorage.removeItem(STORAGE_KEYS.NONCE)
            sessionStorage.removeItem(STORAGE_KEYS.CODE_VERIFIER)
            sessionStorage.removeItem(STORAGE_KEYS.REDIRECT_URI)
            sessionStorage.removeItem(STORAGE_KEYS.TOKENS)
            sessionStorage.removeItem(STORAGE_KEYS.CALLBACK_PROCESSED)
            sessionStorage.removeItem(STORAGE_KEYS.PROVIDER_ID)
            sessionStorage.removeItem(STORAGE_KEYS.REDIRECT_PATH)
          }
          setIsLoading(false)
          return
        }

        // Check for existing tokens
        const storedTokens = getStoredTokens()

        if (storedTokens) {
          // Check if tokens are still valid
          if (!isTokenExpired(storedTokens.expiresAt)) {
            const existingUser = extractUserFromToken(
              storedTokens.idToken,
              storedTokens.accessToken
            )
            if (existingUser) {
              setUser(existingUser)
            }
          } else if (storedTokens.refreshToken) {
            // Expired but refreshable — try now. A terminal rejection ends the
            // session; a transient failure (offline, auth rolling) keeps the
            // stored tokens and restores the user from them so the app renders
            // and the proactive timer keeps retrying instead of forcing a
            // re-login because the network was down for a moment.
            const result = await refreshAccessToken()
            if (result.status === 'terminal') {
              clearAuthStorage()
            } else if (result.status === 'transient') {
              const staleUser = extractUserFromToken(storedTokens.idToken, storedTokens.accessToken)
              if (staleUser) {
                setUser(staleUser)
              } else {
                clearAuthStorage()
              }
            }
          } else {
            // No refresh token and expired, clear
            clearAuthStorage()
          }
        }
      } catch (err) {
        const error = err instanceof Error ? err : new Error('Authentication initialization failed')
        setError(error)
        console.error('[Auth] Initialization error:', error)
      } finally {
        setIsLoading(false)
      }
    }

    initAuth()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []) // Run only once on mount

  /**
   * Proactive token refresh: schedule a timer whenever the user is authenticated.
   * This ensures tokens are refreshed before they expire, even if no API calls are being made.
   */
  useEffect(() => {
    if (isAuthenticated) {
      scheduleTokenRefresh()
    }
    return () => {
      if (refreshTimerRef.current) {
        clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
    }
  }, [isAuthenticated, scheduleTokenRefresh])

  /**
   * Browsers throttle or suspend timers in background tabs and across machine
   * sleep, so the proactive timer can fire long after the token expired. When
   * the tab becomes visible again, or the network comes back, refresh
   * immediately if we're inside the proactive window and re-arm the timer.
   * The timer is re-armed even when no refresh is due: a throttled timer may
   * otherwise fire far later than scheduled.
   */
  useEffect(() => {
    if (!isAuthenticated) return
    const refreshIfDue = (reason: string) => {
      const tokens = getStoredTokens()
      if (!tokens?.refreshToken) return
      const due = Date.now() >= tokens.expiresAt - PROACTIVE_REFRESH_LEAD_MS
      if (!due) {
        scheduleTokenRefresh()
        return
      }
      refreshAccessToken()
        .then((result) => {
          if (result.status === 'ok') {
            scheduleTokenRefresh()
          } else if (result.status === 'transient') {
            // Re-arm: delay is 0 (already due) so the backoff chain in
            // scheduleTokenRefresh takes over.
            scheduleTokenRefresh()
          }
        })
        .catch((err) => console.warn(`[Auth] Refresh on ${reason} failed:`, err))
    }
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') refreshIfDue('tab wake')
    }
    const onOnline = () => refreshIfDue('network online')
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('online', onOnline)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('online', onOnline)
    }
  }, [isAuthenticated, refreshAccessToken, scheduleTokenRefresh])

  // Memoize context value to prevent unnecessary re-renders
  const contextValue = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated,
      isLoading,
      error,
      login,
      logout,
      getAccessToken,
    }),
    [user, isAuthenticated, isLoading, error, login, logout, getAccessToken]
  )

  return <AuthContext.Provider value={contextValue}>{children}</AuthContext.Provider>
}

/**
 * Hook to access authentication context
 *
 * @throws Error if used outside of AuthProvider
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}

/**
 * Hook for a provider that must work with or without an surrounding AuthProvider — it does its
 * core job regardless, and only does *more* when a session exists.
 *
 * Prefer {@link useAuth} everywhere else: for a component that genuinely needs the session,
 * failing fast beats silently behaving as if signed out.
 *
 * @returns the context, or `undefined` when there is no surrounding AuthProvider
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useOptionalAuth(): AuthContextValue | undefined {
  return useContext(AuthContext)
}

// Export the context for testing purposes
export { AuthContext }
