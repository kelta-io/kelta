/**
 * Shared bootstrap config cache.
 *
 * Both AuthContext and ConfigContext need data from the bootstrap
 * configuration. This module composes the bootstrap response from
 * parallel JSON:API calls to /api/ui-pages, /api/ui-menus, and
 * /api/oidc-providers (served by the DynamicCollectionRouter).
 *
 * The URLs are tenant-scoped: /{tenantSlug}/api/{collection}.
 *
 * A single in-flight promise is cached so concurrent callers
 * (AuthContext + ConfigContext) share the same set of fetches.
 */

import { getTenantSlug } from '../context/TenantContext'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

let cachedPromise: Promise<unknown> | null = null
let cachedSlug: string | null = null

/**
 * Default theme when no theme collection exists yet.
 */
const DEFAULT_THEME = {
  primaryColor: '#1976d2',
  secondaryColor: '#dc004e',
  fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
  borderRadius: '4px',
}

/**
 * Default branding when no branding collection exists yet.
 */
const DEFAULT_BRANDING = {
  logoUrl: '/logo.svg',
  applicationName: 'Kelta Platform',
  faviconUrl: '/favicon.ico',
}

/**
 * Unwrap a JSON:API list response into a flat array of objects.
 * Each resource object's id + attributes are merged into a plain object.
 */
function unwrapList(body: unknown): Record<string, unknown>[] {
  if (!body || typeof body !== 'object') return []
  const obj = body as Record<string, unknown>
  if (Array.isArray(obj.data)) {
    return (obj.data as Array<Record<string, unknown>>).map((item) => {
      const attrs = (item.attributes || {}) as Record<string, unknown>
      return { id: item.id, ...attrs }
    })
  }
  if (Array.isArray(body)) return body as Record<string, unknown>[]
  return []
}

/**
 * Unwrap a JSON:API menus response that includes `ui-menu-items` via
 * the `?include=ui-menu-items` query parameter.
 *
 * The response shape is:
 * ```json
 * {
 *   "data": [ { "type":"ui-menus", "id":"…", "attributes":{ "name":"…" } } ],
 *   "included": [ { "type":"ui-menu-items", "id":"…", "attributes":{ "menuId":"…", … } } ]
 * }
 * ```
 *
 * Each menu is returned with an `items` array of its child menu-items,
 * sorted by `displayOrder`.
 */
/**
 * Group ui-translations rows into locale → key → value (app-intelligence slice 4).
 * Later duplicates win (there should be none — the table is unique per
 * tenant+locale+key). Exported for unit tests.
 */
export function groupTranslations(
  rows: Array<Record<string, unknown>>
): Record<string, Record<string, string>> {
  const out: Record<string, Record<string, string>> = {}
  for (const row of rows) {
    const locale = typeof row.locale === 'string' ? row.locale : undefined
    const key = typeof row.key === 'string' ? row.key : undefined
    const value = typeof row.value === 'string' ? row.value : undefined
    if (!locale || !key || value === undefined) continue
    ;(out[locale] ??= {})[key] = value
  }
  return out
}

function unwrapMenusWithItems(body: unknown): Record<string, unknown>[] {
  if (!body || typeof body !== 'object') return []
  const obj = body as Record<string, unknown>

  // Unwrap primary menu data
  const menus = unwrapList(body)

  // Extract included resources (menu items)
  const included = Array.isArray(obj.included)
    ? (obj.included as Array<Record<string, unknown>>)
    : []

  // Group included menu items by their parent menuId.
  // menuId may appear as a plain attribute OR as a JSON:API relationship
  // (the worker puts FK/reference fields into `relationships`).
  const itemsByMenuId = new Map<string, Record<string, unknown>[]>()
  for (const resource of included) {
    const type = resource.type as string | undefined
    if (type !== 'ui-menu-items') continue

    const attrs = (resource.attributes || {}) as Record<string, unknown>

    // Extract menuId: first try attributes, then relationships
    let menuId = attrs.menuId as string | undefined
    if (!menuId) {
      const rels = resource.relationships as
        | Record<string, { data?: { id?: string } | null }>
        | undefined
      menuId = rels?.menuId?.data?.id as string | undefined
    }
    if (!menuId) continue

    const item: Record<string, unknown> = { id: resource.id, ...attrs, menuId }

    // Also flatten any other relationship IDs into the item
    const rels = resource.relationships as
      | Record<string, { data?: { id?: string } | null }>
      | undefined
    if (rels) {
      for (const [key, rel] of Object.entries(rels)) {
        if (item[key] === undefined) {
          item[key] = rel?.data?.id ?? null
        }
      }
    }

    const existing = itemsByMenuId.get(menuId)
    if (existing) {
      existing.push(item)
    } else {
      itemsByMenuId.set(menuId, [item])
    }
  }

  // Attach items to each menu, sorted by displayOrder
  for (const menu of menus) {
    const menuId = menu.id as string
    const items = itemsByMenuId.get(menuId) || []
    items.sort((a, b) => {
      const orderA = (a.displayOrder as number) ?? 0
      const orderB = (b.displayOrder as number) ?? 0
      return orderA - orderB
    })
    menu.items = buildItemTree(items)
  }

  return menus
}

/**
 * Assemble the submenu tree: items whose `parentId` resolves within the same
 * menu nest under that parent's `children` (order preserved on both levels
 * from the sorted input). An unresolvable parentId keeps the item at the top
 * level. Defensive: if a parentId cycle would swallow every item (no roots
 * remain), fall back to the flat list rather than rendering an empty menu.
 */
export function buildItemTree(items: Record<string, unknown>[]): Record<string, unknown>[] {
  const byId = new Map(items.map((i) => [i.id as string, i]))
  const roots: Record<string, unknown>[] = []
  for (const item of items) {
    const parentId = item.parentId as string | null | undefined
    const parent = parentId ? byId.get(parentId) : undefined
    if (parent && parent !== item) {
      const children = (parent.children as Record<string, unknown>[] | undefined) ?? []
      children.push(item)
      parent.children = children
    } else {
      roots.push(item)
    }
  }
  if (roots.length === 0 && items.length > 0) {
    for (const item of items) delete item.children
    return items
  }
  return roots
}

/**
 * Fetch the bootstrap config by composing parallel JSON:API calls.
 * Returns a shape compatible with the BootstrapConfig interface.
 * Callers are responsible for validation.
 *
 * The URLs are scoped to the current tenant slug from the URL path.
 */
export function fetchBootstrapConfig(): Promise<unknown> {
  const slug = getTenantSlug()

  // If slug changed, invalidate cache
  if (cachedSlug !== slug) {
    cachedPromise = null
    cachedSlug = slug
  }

  if (cachedPromise) {
    return cachedPromise
  }

  const base = `${BASE_URL}/${slug}`

  cachedPromise = Promise.all([
    fetch(`${base}/api/ui-pages?page[size]=500`).then(async (r) => {
      if (!r.ok) return { data: [] }
      return r.json()
    }),
    fetch(`${base}/api/ui-menus?include=ui-menu-items&page[size]=500`).then(async (r) => {
      if (!r.ok) return { data: [] }
      return r.json()
    }),
    fetch(`${base}/api/oidc-providers?page[size]=100`).then(async (r) => {
      if (!r.ok) return { data: [] }
      return r.json()
    }),
    // Fetch tenant info to get tenantId
    fetch(`${base}/api/tenants?filter[slug][eq]=${encodeURIComponent(slug)}&page[size]=1`).then(
      async (r) => {
        if (!r.ok) return { data: [] }
        return r.json()
      }
    ),
    // Tenant translation overlay (app-intelligence slice 4) — failure-tolerant:
    // a non-ok response OR a thrown fetch resolves empty so the overlay can never
    // take the whole bootstrap down. It warns on a non-ok response because silence
    // is how this went unnoticed: the endpoint was missing from the gateway's
    // public-paths allowlist, so every pre-login fetch 401'd and the overlay was
    // permanently empty with nothing in the console to say so.
    fetch(`${base}/api/ui-translations?page[size]=2000`)
      .then(async (r) => {
        if (!r.ok) {
          console.warn(
            `Tenant translations unavailable (HTTP ${r.status}); falling back to built-in strings`
          )
          return { data: [] }
        }
        return r.json()
      })
      .catch(() => ({ data: [] })),
  ])
    .then(([pagesRes, menusRes, providersRes, tenantsRes, translationsRes]) => {
      const pages = unwrapList(pagesRes)
      const menus = unwrapMenusWithItems(menusRes)
      // Only active providers are sign-in options. /api/oidc-providers returns every row, so
      // without this an IdP an admin deactivated stayed on the login page — and a fresh
      // install's dead baseline IdPs kept the single-internal-provider auto-login off (#1591).
      const oidcProviders = unwrapList(providersRes).filter((p) => p.active !== false)
      const tenants = unwrapList(tenantsRes)
      const tenantId = tenants.length > 0 ? (tenants[0].id as string) : undefined
      const tenantName = tenants.length > 0 ? (tenants[0].name as string) : undefined
      const translations = groupTranslations(unwrapList(translationsRes))

      return {
        pages,
        menus,
        oidcProviders,
        translations,
        theme: DEFAULT_THEME,
        branding: {
          ...DEFAULT_BRANDING,
          ...(tenantName ? { applicationName: tenantName } : {}),
        },
        tenantId,
      }
    })
    .catch((err) => {
      // Clear cache on error so next call retries
      cachedPromise = null
      throw err instanceof Error
        ? err
        : new Error('Failed to fetch bootstrap configuration: Unknown error')
    })

  return cachedPromise
}

/**
 * Clear the cached bootstrap response.
 * Call this when you need to force a fresh fetch (e.g. config reload).
 */
export function clearBootstrapCache(): void {
  cachedPromise = null
  cachedSlug = null
}
