/**
 * URL scheme allow-list (SECURITY — parent §"Security — binding & action output safety", slice 2e).
 *
 * `navigate.to` and `openUrl.url` are author- AND data-controlled: a `{$bind}` can resolve at fire time
 * to a `javascript:` or `data:` URL, which would execute as XSS the moment it reaches
 * `window.location.assign` / `window.open` / the router. {@link assertSafeUrl} runs on the ALREADY-resolved
 * target (so it sees the final string a `{$bind}` produced, not the literal `{$bind}` marker) and rejects
 * any scheme not in {@link ALLOWED_SCHEMES}. A relative URL (an in-app path like `/app/p/orders`) has no
 * scheme and is allowed.
 *
 * 2g reuses this same list for `link.href` / `image.src`.
 */

/** Schemes a navigate/openUrl target may carry. A relative path has no scheme and is allowed implicitly. */
const ALLOWED_SCHEMES = ['http', 'https', 'mailto', 'tel'] as const

/** Thrown when a resolved URL carries a disallowed scheme (e.g. `javascript:`/`data:`). */
export class UnsafeUrlError extends Error {
  readonly url: string
  constructor(url: string) {
    super(`Blocked URL with disallowed scheme: ${url}`)
    this.name = 'UnsafeUrlError'
    this.url = url
  }
}

/**
 * Extract the leading scheme of a URL, lowercased, or `null` if the string has no scheme (i.e. it is a
 * relative URL / in-app path). A scheme matches `^[a-z][a-z0-9+.-]*:` per RFC 3986 — note the colon must
 * appear before any `/`, `?`, or `#`, so a path like `/a:b` (colon inside a path segment) is NOT a scheme.
 */
export function urlScheme(url: string): string | null {
  const match = /^([a-z][a-z0-9+.-]*):/i.exec(url.trim())
  if (!match) return null
  const scheme = match[1].toLowerCase()
  // A "scheme" candidate that actually sits inside a path (e.g. "/foo:bar" or "foo/bar:baz") is not a
  // real scheme — a real scheme's colon precedes any path/query/fragment delimiter.
  const colonIdx = url.trim().indexOf(':')
  const delimIdx = url.trim().search(/[/?#]/)
  if (delimIdx !== -1 && delimIdx < colonIdx) return null
  return scheme
}

/** True when the (already-resolved) URL is safe to navigate to / open. */
export function isSafeUrl(url: unknown): boolean {
  if (typeof url !== 'string') return false
  const trimmed = url.trim()
  if (trimmed.length === 0) return false
  const scheme = urlScheme(trimmed)
  if (scheme === null) return true // relative URL (no scheme) — allowed
  return (ALLOWED_SCHEMES as readonly string[]).includes(scheme)
}

/**
 * Throw {@link UnsafeUrlError} if `url` carries a disallowed scheme. Called by the action runtime before
 * every `navigate` / `openUrl` dispatch — a rejected URL stops the action chain and surfaces an error
 * toast (the action does NOT navigate/open).
 */
export function assertSafeUrl(url: unknown): asserts url is string {
  if (!isSafeUrl(url)) {
    throw new UnsafeUrlError(typeof url === 'string' ? url : String(url))
  }
}
