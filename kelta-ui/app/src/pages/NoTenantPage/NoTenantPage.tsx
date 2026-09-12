import { useCallback, useState } from 'react'
import { forgetTenant, recentTenants, type RecentTenant } from '@/lib/recentTenants'

/**
 * The platform host's root, where the URL has no tenant slug.
 *
 * On a first visit there is nothing to do but ask for one. On every visit after that, asking
 * again is hostile: this browser has signed in before and knows where. So the page shows the
 * tenants — and the users within them — it has seen, and one tap goes there. The original
 * message survives for the genuinely-new case, and as the fallback when storage is empty.
 *
 * Rendered outside every provider (no theme, no i18n, no API), so it stays self-contained and
 * cannot fail for want of context. It is the one page that must always render.
 */
export function NoTenantPage(): React.ReactElement {
  const [tenants, setTenants] = useState<RecentTenant[]>(() => recentTenants())

  const forget = useCallback((slug: string) => {
    forgetTenant(slug)
    setTenants(recentTenants())
  }, [])

  if (tenants.length === 0) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center p-8 text-center font-sans">
        <h1 className="mb-4 text-3xl font-semibold text-[#1a1a2e]">Tenant Required</h1>
        <p className="max-w-[480px] text-lg leading-relaxed text-[#555]">
          A tenant identifier is required in the URL. Please navigate to a valid tenant URL, for
          example: <code className="rounded bg-[#f0f0f0] px-1.5 py-0.5">/your-tenant/</code>
        </p>
      </main>
    )
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-8 font-sans">
      <div className="w-full max-w-md">
        <h1 className="mb-1 text-2xl font-semibold text-[#1a1a2e]">Choose a workspace</h1>
        <p className="mb-6 text-sm text-[#555]">
          Workspaces this browser has signed in to. Pick one to continue.
        </p>

        <ul className="space-y-3" data-testid="recent-tenants">
          {tenants.map((t) => (
            <li
              key={t.slug}
              className="rounded-lg border border-[#e2e2e8] bg-white p-4 shadow-sm"
              data-testid={`recent-tenant-${t.slug}`}
            >
              <div className="flex items-start justify-between gap-3">
                {/* The tenant link goes to /app: if a session exists it lands home, and if not
                    the app bounces to /{slug}/login — with the slug intact, which is the point. */}
                <a
                  href={`/${encodeURIComponent(t.slug)}/app`}
                  className="min-w-0 flex-1 rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-[#1a1a2e]"
                >
                  <div className="truncate text-base font-medium text-[#1a1a2e]">
                    {t.name || t.slug}
                  </div>
                  <div className="truncate text-xs text-[#777]">/{t.slug}/</div>
                </a>
                <button
                  type="button"
                  onClick={() => forget(t.slug)}
                  className="shrink-0 rounded px-2 py-1 text-xs text-[#777] hover:bg-[#f0f0f0] hover:text-[#1a1a2e]"
                  aria-label={`Forget ${t.name || t.slug}`}
                  data-testid={`forget-tenant-${t.slug}`}
                >
                  Forget
                </button>
              </div>

              {t.users.length > 0 && (
                <ul className="mt-3 space-y-1 border-t border-[#f0f0f0] pt-3">
                  {t.users.map((u) => (
                    <li key={u.email}>
                      {/* login_hint is standard OIDC. If the identity provider honours it the
                          email is pre-filled; if not, nothing is lost but the convenience. */}
                      <a
                        href={`/${encodeURIComponent(t.slug)}/login?login_hint=${encodeURIComponent(u.email)}`}
                        className="flex items-center justify-between rounded px-2 py-1.5 text-sm hover:bg-[#f7f7fa] focus:outline-none focus-visible:ring-2 focus-visible:ring-[#1a1a2e]"
                        data-testid={`recent-user-${t.slug}-${u.email}`}
                      >
                        <span className="min-w-0 truncate text-[#1a1a2e]">
                          {u.name ? (
                            <>
                              {u.name} <span className="text-[#777]">· {u.email}</span>
                            </>
                          ) : (
                            u.email
                          )}
                        </span>
                        <span className="ml-3 shrink-0 text-xs text-[#777]">Sign in</span>
                      </a>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>

        <p className="mt-6 text-xs text-[#777]">
          Somewhere else? Enter its address directly, for example{' '}
          <code className="rounded bg-[#f0f0f0] px-1.5 py-0.5">/your-tenant/</code>.
        </p>
      </div>
    </main>
  )
}
