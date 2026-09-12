/**
 * EndUserShell Component
 *
 * The top-level layout for the end-user runtime. Provides a horizontal
 * top navigation bar and a full-width content area. This is the shadcn/Tailwind
 * equivalent of the existing AppShell used for admin pages.
 *
 * Uses React Router's <Outlet> to render child route content.
 */

import React, { useState, useCallback, useEffect } from 'react'
import { Outlet, useNavigate, useParams } from 'react-router-dom'
import { TopNavBar } from './TopNavBar'
import { GlobalSearch } from './GlobalSearch'
import { OfflineIndicator } from './OfflineIndicator'
import { OfflineProvider } from '@/offline'
import { RealtimeProvider } from '@/realtime'
import { activeMenus, buildNavTabs, resolveActiveMenu } from './navTabs'
import { useAuth } from '@/context/AuthContext'
import { useApi } from '@/context/ApiContext'
import { useConfig } from '@/context/ConfigContext'
import { useMyIdentity } from '@/hooks/useMyIdentity'
import { usePreferenceValue } from '@/hooks/usePreferenceStore'
import { usePendingApprovalsCount } from '@/hooks/useMyApprovals'
import { useUnreadChatCount } from '@/hooks/useChat'
import { initPushNotifications } from '@/push/deviceRegistration'
import { rememberTenant } from '@/lib/recentTenants'
import { PageLoader } from '@/components/PageLoader'
import { SkipLinks } from '@/components/SkipLinks'

export function EndUserShell(): React.ReactElement {
  const { user, logout } = useAuth()
  const { apiClient } = useApi()
  const { config, isLoading: configLoading } = useConfig()
  const [searchOpen, setSearchOpen] = useState(false)
  const navigate = useNavigate()
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const { identity } = useMyIdentity()
  const { count: pendingApprovals } = usePendingApprovalsCount(identity?.userId)
  const unreadChats = useUnreadChatCount(!!identity?.userId)

  // Register the device's push token when running in the Capacitor native shell.
  // No-op on the web (does not touch any Capacitor code).
  useEffect(() => {
    void initPushNotifications(apiClient)
  }, [apiClient])

  // Remember this tenant and user so the slug-less root can offer them next time. Runs again
  // once branding loads so the workspace gets its display name rather than just its slug.
  useEffect(() => {
    if (tenantSlug) {
      rememberTenant(tenantSlug, user ?? undefined, config?.branding?.applicationName)
    }
  }, [tenantSlug, user, config?.branding?.applicationName])

  // Apps (nav v2): an app IS a ui-menu. Render ONE active app's items; the
  // selection persists per user (server-backed preference, localStorage mirror).
  const apps = activeMenus(config?.menus)
  const { value: storedAppId, save: saveActiveApp } = usePreferenceValue<string>(
    'nav',
    'active-app',
    { localKey: 'kelta_active_app' }
  )
  const activeApp = resolveActiveMenu(apps, storedAppId)
  const tabs = buildNavTabs(activeApp ? [activeApp] : undefined)
  const handleAppChange = useCallback(
    (appId: string) => {
      saveActiveApp(appId)
      navigate(`/${tenantSlug}/app/home`)
    },
    [saveActiveApp, navigate, tenantSlug]
  )

  // Global keyboard shortcut: Cmd+K / Ctrl+K → open search
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [])

  const handleLogout = useCallback(() => {
    logout()
  }, [logout])

  if (configLoading) {
    return <PageLoader fullPage message="Loading application..." />
  }

  const appName = config?.branding?.applicationName || 'Kelta'

  return (
    <div className="flex h-full flex-col overflow-hidden bg-background">
      {/* Skip links for keyboard navigation — must be first focusable elements */}
      <SkipLinks
        targets={[
          { id: 'main-content', label: 'Skip to main content' },
          { id: 'main-navigation', label: 'Skip to navigation' },
        ]}
      />
      <TopNavBar
        appName={appName}
        tabs={tabs}
        apps={apps.map((a) => ({ id: a.id, name: a.name, icon: a.icon }))}
        activeAppId={activeApp?.id ?? null}
        onAppChange={handleAppChange}
        user={user}
        onLogout={handleLogout}
        onSearchOpen={() => setSearchOpen(true)}
        notificationCount={pendingApprovals + unreadChats}
        onNotificationsOpen={() =>
          navigate(
            // Approvals stay the primary feed; when only chat is unread, land there.
            pendingApprovals === 0 && unreadChats > 0
              ? `/${tenantSlug}/app/chat`
              : `/${tenantSlug}/app/approvals`
          )
        }
      />
      <OfflineIndicator />
      <main id="main-content" className="flex-1 overflow-auto" role="main" tabIndex={-1}>
        <OfflineProvider>
          <RealtimeProvider>
            <Outlet />
          </RealtimeProvider>
        </OfflineProvider>
      </main>
      <GlobalSearch open={searchOpen} onOpenChange={setSearchOpen} />
    </div>
  )
}
