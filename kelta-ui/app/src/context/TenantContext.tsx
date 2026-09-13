/**
 * Tenant Context
 *
 * Two modes of operation:
 * - slug: tenant identity comes from the URL path (/:tenantSlug/...). Used on
 *   the canonical platform host (app.kelta.io).
 * - custom-domain: the request hostname is bound to a tenant by the gateway,
 *   so the URL contains no slug (https://acme.com/...). The slug is fetched
 *   from /api/whoami once the API client is available.
 */

import React, { createContext, useContext, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'

export type TenantMode = 'slug' | 'custom-domain'

interface TenantContextValue {
  tenantSlug: string
  tenantBasePath: string
  mode: TenantMode
}

const TenantContext = createContext<TenantContextValue | null>(null)

// Module-level variable for non-hook access (set once per page load)
let _currentTenantSlug: string = 'default'
let _currentTenantId: string | null = null

/**
 * Top-level route segments that the SPA itself owns. When one of these appears
 * as the first path segment we know the URL doesn't carry a tenant slug, so a
 * slug-less mode is in effect (custom domain or first visit before redirect).
 * Keep in sync with TenantRoutes in App.tsx.
 */
const KNOWN_PLATFORM_ROUTES = new Set([
  'login',
  'logout',
  'auth',
  'app',
  'setup',
  'system-health',
  'unauthorized',
  'admin',
])

const TENANT_SLUG_REGEX = /^[a-z][a-z0-9-]{1,61}[a-z0-9]$/

/**
 * Returns true when the browser is on a tenant custom domain rather than the
 * canonical platform host (*.kelta.io).
 *
 * <p>URL path wins over hostname: if the first segment looks like a tenant
 * slug and isn't a known platform route, we're in slug mode regardless of
 * hostname. This keeps the dev / E2E hosts (kelta-ui, etc.) on the legacy
 * slug-based flow without needing an env var override.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function isCustomDomainHost(): boolean {
  if (typeof window === 'undefined') return false
  const host = window.location.hostname.toLowerCase()
  if (!host) return false
  if (host === 'localhost' || host === '127.0.0.1') return false

  // Path-based signal first: a slug-shaped first segment that isn't a known
  // route means slug mode (e.g. /default/app, /acme-corp/setup).
  const firstSegment = window.location.pathname.split('/').filter(Boolean)[0]
  if (firstSegment) {
    if (TENANT_SLUG_REGEX.test(firstSegment) && !KNOWN_PLATFORM_ROUTES.has(firstSegment)) {
      return false
    }
  }

  // Platform hosts under kelta.io are slug-mode regardless of path.
  if (host === 'kelta.io' || host.endsWith('.kelta.io')) return false

  // Anything else: custom-domain mode.
  return true
}

/**
 * Returns the current tenant slug for use outside React components.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function getTenantSlug(): string {
  return _currentTenantSlug
}

function syncTenantSlug(slug: string): void {
  _currentTenantSlug = slug
}

// eslint-disable-next-line react-refresh/only-export-components
export function setResolvedTenantId(tenantId: string | null): void {
  _currentTenantId = tenantId
}

// eslint-disable-next-line react-refresh/only-export-components
export function getResolvedTenantId(): string | null {
  return _currentTenantId
}

// eslint-disable-next-line react-refresh/only-export-components
export function useTenant(): TenantContextValue {
  const ctx = useContext(TenantContext)
  if (!ctx) {
    throw new Error('useTenant must be used within a TenantProvider')
  }
  return ctx
}

/**
 * Namespace for per-tenant browser state (recent items, favorites).
 *
 * <p>localStorage is per-origin, and on the platform host every workspace
 * shares the origin, so state written without a tenant key bleeds from one
 * workspace into another (records from one workspace showing up in another's
 * "Recent items" card). On a custom domain the origin itself is bound to a
 * single tenant, so a constant scope is both correct and stable while the
 * slug is still hydrating from /api/whoami.
 *
 * <p>Safe without a provider: falls back to the module-level slug so hooks
 * that use it keep working in test wrappers that omit TenantProvider.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useTenantStorageScope(): string {
  const ctx = useContext(TenantContext)
  if (isCustomDomainHost()) return 'custom-domain'
  return ctx?.tenantSlug || getTenantSlug()
}

export function TenantProvider({ children }: { children: React.ReactNode }): React.ReactElement {
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const customDomain = isCustomDomainHost()
  const mode: TenantMode = customDomain ? 'custom-domain' : 'slug'

  // In custom-domain mode the slug isn't in the URL — we hydrate it later
  // from /api/whoami (async). Until that resolves it is ''. In slug mode the
  // slug is derived directly from the URL param, so it needs no state at all.
  const [customDomainSlug, setCustomDomainSlug] = useState<string>('')

  useEffect(() => {
    if (!customDomain) return

    // Worker echoes the gateway-injected X-Tenant-Slug header back so the UI
    // can label things by tenant. No auth needed (custom-domain mode resolves
    // tenant from Host pre-auth) — but the endpoint is also fine post-auth.
    let cancelled = false
    fetch('/api/whoami', { credentials: 'include' })
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => {
        if (!cancelled && j && typeof j.tenantSlug === 'string' && j.tenantSlug) {
          setCustomDomainSlug(j.tenantSlug)
        }
      })
      .catch(() => {
        // Silent: tenant labels degrade gracefully without a slug
      })
    return () => {
      cancelled = true
    }
  }, [customDomain])

  // Derived during render — slug mode tracks the URL param reactively, and
  // custom-domain mode reflects the hydrated slug ('' until /api/whoami resolves).
  const resolvedSlug = customDomain ? customDomainSlug : tenantSlug || 'default'

  // Mirror to the module-level variable for non-hook callers (getTenantSlug).
  syncTenantSlug(resolvedSlug)

  const value: TenantContextValue = {
    tenantSlug: resolvedSlug,
    tenantBasePath: customDomain ? '' : `/${resolvedSlug}`,
    mode,
  }

  return <TenantContext.Provider value={value}>{children}</TenantContext.Provider>
}
