/**
 * Where the SPA keeps each tenant's OAuth tokens, shared by AuthContext (which owns them) and the
 * pages that only need to know whether a workspace still has a session (the slug-less root, the
 * workspace switcher).
 *
 * Tokens are bound to their tenant — the gateway rejects a token on another tenant's URL — and on
 * the platform host every tenant shares one origin, so each tenant gets its own localStorage key.
 * That is also what lets several workspaces stay signed in side by side. A custom domain is its own
 * origin and needs no suffix.
 */

export const TOKENS_STORAGE_KEY = 'kelta_auth_tokens'

/** localStorage key for a tenant's tokens; `null` means the origin itself is the tenant. */
export function tenantTokensKey(slug: string | null): string {
  return slug ? `${TOKENS_STORAGE_KEY}:${slug}` : TOKENS_STORAGE_KEY
}

/**
 * True when this browser holds a session for the tenant that should still work: a refresh token,
 * or an access token that has not expired. Never throws — storage may be absent or blocked.
 */
export function hasStoredSession(
  slug: string,
  w: Window | undefined = globalThis.window,
  now: number = Date.now()
): boolean {
  try {
    const raw = w?.localStorage.getItem(tenantTokensKey(slug))
    if (!raw) return false
    const tokens = JSON.parse(raw) as { refreshToken?: string; expiresAt?: number }
    return Boolean(tokens.refreshToken) || (tokens.expiresAt ?? 0) > now
  } catch {
    return false
  }
}
