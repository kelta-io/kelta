/**
 * Remembers which tenants (and which users within them) this browser has signed in as.
 *
 * The platform host has no notion of a "current tenant" outside the URL: land on `/` and the
 * only honest thing the app can say is "tenant required". That is fine for a first visit and
 * hostile for every visit after it — especially from an iOS Home Screen icon, which runs in its
 * own storage container, starts every launch signed out, and can easily arrive without a slug.
 *
 * This is deliberately `localStorage`, not `sessionStorage`: the whole point is to survive the
 * session. It holds only what is needed to render a choice — slug, a display name, and the emails
 * signed in as — never a token. Every read is guarded because storage can be absent (private
 * mode) or throw (some embedded webviews), and a root page that crashes is worse than one that
 * merely asks for a slug.
 */

export interface RecentUser {
  email: string
  name?: string
  lastUsedAt: string
}

export interface RecentTenant {
  slug: string
  /** Branding application name when known; falls back to the slug in the UI. */
  name?: string
  users: RecentUser[]
  lastUsedAt: string
}

export const STORAGE_KEY = 'kelta_recent_tenants'
const MAX_TENANTS = 8
const MAX_USERS_PER_TENANT = 5

function read(storage: Storage | undefined): RecentTenant[] {
  try {
    const raw = storage?.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (t): t is RecentTenant =>
        typeof t === 'object' && t !== null && typeof (t as RecentTenant).slug === 'string'
    )
  } catch {
    return []
  }
}

function write(storage: Storage | undefined, tenants: RecentTenant[]): void {
  try {
    storage?.setItem(STORAGE_KEY, JSON.stringify(tenants))
  } catch {
    // Quota, private mode, or a webview that throws: forgetting is acceptable, failing is not.
  }
}

function storageOf(w: Window | undefined): Storage | undefined {
  try {
    return w?.localStorage
  } catch {
    return undefined
  }
}

/** Tenants this browser has used, most recent first. Never throws. */
export function recentTenants(w: Window | undefined = globalThis.window): RecentTenant[] {
  return read(storageOf(w)).sort((a, b) => b.lastUsedAt.localeCompare(a.lastUsedAt))
}

/**
 * Records a sign-in. Idempotent: the same tenant and user only move to the front.
 *
 * @param slug  the tenant slug from the URL — the one thing that must be right
 * @param user  who signed in, when known; omitted while the identity is still loading
 * @param name  the tenant's display name, when known
 */
export function rememberTenant(
  slug: string,
  user?: { email?: string | null; name?: string | null },
  name?: string | null,
  w: Window | undefined = globalThis.window,
  now: Date = new Date()
): void {
  if (!slug || slug === 'default') return
  const storage = storageOf(w)
  const stamp = now.toISOString()
  const tenants = read(storage)

  const existing = tenants.find((t) => t.slug === slug)
  const tenant: RecentTenant = existing ?? { slug, users: [], lastUsedAt: stamp }
  tenant.lastUsedAt = stamp
  if (name && name.trim()) tenant.name = name.trim()

  const email = user?.email?.trim().toLowerCase()
  if (email) {
    const others = tenant.users.filter((u) => u.email !== email)
    const previous = tenant.users.find((u) => u.email === email)
    others.unshift({
      email,
      name: user?.name?.trim() || previous?.name,
      lastUsedAt: stamp,
    })
    tenant.users = others.slice(0, MAX_USERS_PER_TENANT)
  }

  const rest = tenants.filter((t) => t.slug !== slug)
  rest.unshift(tenant)
  write(storage, rest.slice(0, MAX_TENANTS))
}

/** Removes one remembered tenant — a shared machine, a workspace you no longer use. */
export function forgetTenant(slug: string, w: Window | undefined = globalThis.window): void {
  const storage = storageOf(w)
  write(
    storage,
    read(storage).filter((t) => t.slug !== slug)
  )
}
