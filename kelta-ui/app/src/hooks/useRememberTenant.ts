import { useEffect } from 'react'
import { useAuth } from '@/context/AuthContext'
import { useConfig } from '@/context/ConfigContext'
import { useTenant } from '@/context/TenantContext'
import { rememberTenant } from '@/lib/recentTenants'

/**
 * Remembers this tenant and user so the slug-less root and the workspace switcher can offer them
 * next time. Called by every authenticated shell (admin and end-user), so a workspace used only
 * through Setup is remembered too. Runs again once branding loads so the workspace gets its display
 * name rather than just its slug.
 */
export function useRememberTenant(): void {
  const { user } = useAuth()
  const { config } = useConfig()
  const { tenantSlug, mode } = useTenant()
  const applicationName = config?.branding?.applicationName

  useEffect(() => {
    // A custom domain is its own origin: slug links from the platform host do not apply there.
    if (mode === 'slug' && tenantSlug && user) {
      rememberTenant(tenantSlug, user, applicationName)
    }
  }, [mode, tenantSlug, user, applicationName])
}
