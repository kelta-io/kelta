import type { ReactNode } from 'react'

/**
 * Authentication Types
 *
 * Types related to authentication, users, and OIDC providers.
 */

/**
 * User information from the OIDC token
 */
export interface User {
  id: string
  email: string
  name?: string
  picture?: string
  /** Raw claims from the ID token */
  claims?: Record<string, unknown>
}

/**
 * OIDC token response
 */
export interface TokenResponse {
  access_token: string
  id_token?: string
  refresh_token?: string
  token_type: string
  expires_in: number
  scope?: string
}

/**
 * Stored token data with expiration
 */
export interface StoredTokens {
  accessToken: string
  idToken?: string
  refreshToken?: string
  expiresAt: number
}

/**
 * OIDC provider configuration for authentication
 */
export interface OIDCProviderConfig {
  id: string
  name: string
  issuer: string
  clientId: string
  scopes: string[]
  authorizationEndpoint?: string
  tokenEndpoint?: string
  userinfoEndpoint?: string
  endSessionEndpoint?: string
}

/**
 * OIDC discovery document
 */
export interface OIDCDiscoveryDocument {
  issuer: string
  authorization_endpoint: string
  token_endpoint: string
  userinfo_endpoint?: string
  end_session_endpoint?: string
  jwks_uri: string
  scopes_supported?: string[]
  response_types_supported: string[]
  grant_types_supported?: string[]
}

/**
 * Authentication state
 */
export interface AuthState {
  user: User | null
  isAuthenticated: boolean
  isLoading: boolean
  error: Error | null
}

/**
 * Authentication context value
 */
export interface AuthContextValue extends AuthState {
  /**
   * Initiate login flow
   * @param providerId - Optional provider ID for multi-provider setups
   */
  login: (providerId?: string) => Promise<void>

  /**
   * Logout and clear tokens
   */
  logout: () => Promise<void>

  /**
   * Get the current access token, refreshing if necessary.
   *
   * Throws `SessionExpiredError` (see `context/authErrors.ts`) only when the
   * session is genuinely over; a transient refresh failure throws
   * `TokenUnavailableError` and leaves the session intact.
   *
   * @param options.force refresh even if the token is not locally expired —
   *   for callers that just received a 401 with it.
   */
  getAccessToken: (options?: { force?: boolean }) => Promise<string>
}

/**
 * Auth provider props
 */
export interface AuthProviderProps {
  children: ReactNode
  /** Optional callback URL for OIDC redirect */
  redirectUri?: string
  /** Optional post-logout redirect URL */
  postLogoutRedirectUri?: string
}
