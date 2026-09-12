/**
 * App Component
 *
 * The root component that initializes all providers and routing.
 * Wires together all context providers and configures React Router.
 *
 * Requirements:
 * - 1.1: Fetch bootstrap configuration from JSON:API bootstrap endpoint on startup
 * - 1.2: Configure application routes based on page definitions
 * - 1.3: Configure navigation menus based on menu definitions
 * - 2.1: Redirect unauthenticated users to OIDC provider login page
 */

import React from 'react'
import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  useNavigate,
  useLocation,
  useParams,
  Link,
} from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AlertTriangle } from 'lucide-react'

// Context Providers
import { AuthProvider } from './context/AuthContext'
import { ApiProvider } from './context/ApiContext'
import { CollectionStoreProvider } from './context/CollectionStoreContext'
import { ConfigProvider, useConfig } from './context/ConfigContext'
import { ThemeProvider } from './context/ThemeContext'
import { I18nProvider } from './context/I18nContext'
import { TenantTranslationsBridge } from './components/TenantTranslationsBridge/TenantTranslationsBridge'
import { PluginProvider } from './context/PluginContext'
import { TenantProvider, useTenant, isCustomDomainHost } from './context/TenantContext'
import { AppContextProvider } from './context/AppContext'
import { useEffect } from 'react'
import { useAuth } from './context/AuthContext'
import { AiChatProvider, AiChatPanel, AiChatTrigger } from './components/AiChat'

// Hooks
import { useGlobalShortcuts } from './hooks/useGlobalShortcuts'

// Components
import { ErrorBoundary } from './components/ErrorBoundary'
import { KeyboardShortcutsHelp } from './components/KeyboardShortcutsHelp/KeyboardShortcutsHelp'
import { ToastProvider } from './components/Toast'
import { LiveRegionProvider } from './components/LiveRegion'
import { ProtectedRoute } from './components/ProtectedRoute'
import { RequirePermission } from './components/RequirePermission/RequirePermission'
import { RequireUserManagementAccess } from './components/RequirePermission/RequireUserManagementAccess'
import { Header } from './components/Header'
import { PageTransition } from './components/PageTransition'
import { PageLoader } from './components/PageLoader'
import {
  Breadcrumb,
  BreadcrumbList,
  BreadcrumbItem,
  BreadcrumbLink,
} from './components/ui/breadcrumb'

// Admin/Setup pages (imported per-module, not via the barrel, so the eager barrel
// graph doesn't drag the heavy lazy pages into the main chunk).
import { NoTenantPage } from './pages/NoTenantPage/NoTenantPage'
// LoginPage stays eager — it is the unauthenticated entry point; putting it behind a
// lazy Suspense boundary raced the login route render (e2e `login.spec` flakes).
import { LoginPage } from './pages/LoginPage'

// End-User Shell & Pages (lazy-loaded for code splitting)
import { EndUserShell } from './shells/EndUserShell'

const AppHomePage = React.lazy(() =>
  import('./pages/app/AppHomePage/AppHomePage').then((m) => ({ default: m.AppHomePage }))
)
const EndUserObjectListPage = React.lazy(() =>
  import('./pages/app/ObjectListPage/ObjectListPage').then((m) => ({ default: m.ObjectListPage }))
)
const EndUserApprovalsInboxPage = React.lazy(() =>
  import('./pages/app/ApprovalsInboxPage/ApprovalsInboxPage').then((m) => ({
    default: m.ApprovalsInboxPage,
  }))
)
const MailboxAdminPage = React.lazy(() =>
  import('./pages/MailboxAdminPage/MailboxAdminPage').then((m) => ({
    default: m.MailboxAdminPage,
  }))
)
const EndUserMailboxConsolePage = React.lazy(() =>
  import('./pages/app/MailboxConsolePage/MailboxConsolePage').then((m) => ({
    default: m.MailboxConsolePage,
  }))
)
const EndUserChatConsolePage = React.lazy(() =>
  import('./pages/app/ChatConsolePage/ChatConsolePage').then((m) => ({
    default: m.ChatConsolePage,
  }))
)
const EndUserAppointmentsPage = React.lazy(() =>
  import('./pages/app/AppointmentsPage/AppointmentsPage').then((m) => ({
    default: m.AppointmentsPage,
  }))
)
const EndUserProviderAvailabilityPage = React.lazy(() =>
  import('./pages/ProviderAvailabilityPage/ProviderAvailabilityPage').then((m) => ({
    default: m.ProviderAvailabilityPage,
  }))
)
const EndUserVisitPage = React.lazy(() =>
  import('./pages/app/VisitPage/VisitPage').then((m) => ({
    default: m.VisitPage,
  }))
)
const EndUserObjectDetailPage = React.lazy(() =>
  import('./pages/app/ObjectDetailPage/ObjectDetailPage').then((m) => ({
    default: m.ObjectDetailPage,
  }))
)
const EndUserObjectFormPage = React.lazy(() =>
  import('./pages/app/ObjectFormPage/ObjectFormPage').then((m) => ({ default: m.ObjectFormPage }))
)
const GlobalSearchPage = React.lazy(() =>
  import('./pages/app/GlobalSearchPage/GlobalSearchPage').then((m) => ({
    default: m.GlobalSearchPage,
  }))
)
const EndUserCustomPage = React.lazy(() =>
  import('./pages/app/CustomPage/CustomPage').then((m) => ({ default: m.CustomPage }))
)
const EndUserAnalyticsHubPage = React.lazy(() =>
  import('./pages/app/AnalyticsHubPage/AnalyticsHubPage').then((m) => ({
    default: m.AnalyticsHubPage,
  }))
)
const EndUserDashboardViewPage = React.lazy(() =>
  import('./pages/app/DashboardViewPage/DashboardViewPage').then((m) => ({
    default: m.DashboardViewPage,
  }))
)
const EndUserReportViewPage = React.lazy(() =>
  import('./pages/app/ReportViewPage/ReportViewPage').then((m) => ({
    default: m.ReportViewPage,
  }))
)

// Heavy admin pages — lazy-loaded so their large libraries (react-flow, recharts, dnd-kit)
// split into their own chunks instead of bloating the main bundle the installable PWA
// precaches. Rendered under the Suspense boundary in TenantRoutes.
const PageBuilderPage = React.lazy(() =>
  import('./pages/PageBuilderPage').then((m) => ({ default: m.PageBuilderPage }))
)
const FlowDesignerPage = React.lazy(() =>
  import('./pages/FlowDesignerPage').then((m) => ({ default: m.FlowDesignerPage }))
)
const MonitoringLayout = React.lazy(() =>
  import('./pages/MonitoringPage').then((m) => ({ default: m.MonitoringLayout }))
)
const MonitoringOverviewPage = React.lazy(() =>
  import('./pages/MonitoringPage').then((m) => ({ default: m.MonitoringOverviewPage }))
)

// Admin/Setup pages — lazy-loaded (code-splitting) so the main bundle carries only
// the app shell; each admin route's code loads on demand under the TenantRoutes
// Suspense boundary. App imports them per-module (no ./pages barrel).
const AiSettingsPage = React.lazy(() =>
  import('./pages/AiSettingsPage').then((m) => ({ default: m.AiSettingsPage }))
)
const EmailSettingsPage = React.lazy(() =>
  import('./pages/EmailSettingsPage').then((m) => ({ default: m.EmailSettingsPage }))
)
const MapSettingsPage = React.lazy(() =>
  import('./pages/MapSettingsPage').then((m) => ({ default: m.MapSettingsPage }))
)
const TelehealthSettingsPage = React.lazy(() =>
  import('./pages/TelehealthSettingsPage').then((m) => ({ default: m.TelehealthSettingsPage }))
)
const NetworkAccessPage = React.lazy(() =>
  import('./pages/NetworkAccessPage').then((m) => ({ default: m.NetworkAccessPage }))
)
const DashboardPage = React.lazy(() =>
  import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage }))
)
const CollectionsPage = React.lazy(() =>
  import('./pages/CollectionsPage').then((m) => ({ default: m.CollectionsPage }))
)
const CollectionDetailPage = React.lazy(() =>
  import('./pages/CollectionDetailPage').then((m) => ({ default: m.CollectionDetailPage }))
)
const CollectionFormPage = React.lazy(() =>
  import('./pages/CollectionFormPage').then((m) => ({ default: m.CollectionFormPage }))
)
const CollectionWizardPage = React.lazy(() =>
  import('./pages/CollectionWizardPage').then((m) => ({ default: m.CollectionWizardPage }))
)
const OIDCProvidersPage = React.lazy(() =>
  import('./pages/OIDCProvidersPage').then((m) => ({ default: m.OIDCProvidersPage }))
)
const MenuBuilderPage = React.lazy(() =>
  import('./pages/MenuBuilderPage').then((m) => ({ default: m.MenuBuilderPage }))
)
const PackagesPage = React.lazy(() =>
  import('./pages/PackagesPage').then((m) => ({ default: m.PackagesPage }))
)
const MigrationsPage = React.lazy(() =>
  import('./pages/MigrationsPage').then((m) => ({ default: m.MigrationsPage }))
)
const EnvironmentsPage = React.lazy(() =>
  import('./pages/EnvironmentsPage').then((m) => ({ default: m.EnvironmentsPage }))
)
const ResourceBrowserPage = React.lazy(() =>
  import('./pages/ResourceBrowserPage').then((m) => ({ default: m.ResourceBrowserPage }))
)
const ResourceListPage = React.lazy(() =>
  import('./pages/ResourceListPage').then((m) => ({ default: m.ResourceListPage }))
)
const ResourceDetailPage = React.lazy(() =>
  import('./pages/ResourceDetailPage').then((m) => ({ default: m.ResourceDetailPage }))
)
const ResourceFormPage = React.lazy(() =>
  import('./pages/ResourceFormPage').then((m) => ({ default: m.ResourceFormPage }))
)
const PluginsPage = React.lazy(() =>
  import('./pages/PluginsPage').then((m) => ({ default: m.PluginsPage }))
)
const UsersPage = React.lazy(() =>
  import('./pages/UsersPage').then((m) => ({ default: m.UsersPage }))
)
const UserDetailPage = React.lazy(() =>
  import('./pages/UserDetailPage').then((m) => ({ default: m.UserDetailPage }))
)
const DelegatedAdminsPage = React.lazy(() =>
  import('./pages/DelegatedAdminsPage').then((m) => ({ default: m.DelegatedAdminsPage }))
)
const SetupAuditTrailPage = React.lazy(() =>
  import('./pages/SetupAuditTrailPage').then((m) => ({ default: m.SetupAuditTrailPage }))
)
const GovernorLimitsPage = React.lazy(() =>
  import('./pages/GovernorLimitsPage').then((m) => ({ default: m.GovernorLimitsPage }))
)
const TenantsPage = React.lazy(() =>
  import('./pages/TenantsPage').then((m) => ({ default: m.TenantsPage }))
)
const TenantDashboardPage = React.lazy(() =>
  import('./pages/TenantDashboardPage').then((m) => ({ default: m.TenantDashboardPage }))
)
const PicklistsPage = React.lazy(() =>
  import('./pages/PicklistsPage').then((m) => ({ default: m.PicklistsPage }))
)
const AiAgentsPage = React.lazy(() =>
  import('./pages/AiAgentsPage').then((m) => ({ default: m.AiAgentsPage }))
)
const PageLayoutsPage = React.lazy(() =>
  import('./pages/PageLayoutsPage').then((m) => ({ default: m.PageLayoutsPage }))
)
const ListViewsPage = React.lazy(() =>
  import('./pages/ListViewsPage').then((m) => ({ default: m.ListViewsPage }))
)
const AnalyticsPage = React.lazy(() =>
  import('./pages/AnalyticsPage').then((m) => ({ default: m.AnalyticsPage }))
)
const ApprovalProcessesPage = React.lazy(() =>
  import('./pages/ApprovalProcessesPage').then((m) => ({ default: m.ApprovalProcessesPage }))
)
const FlowsPage = React.lazy(() =>
  import('./pages/FlowsPage').then((m) => ({ default: m.FlowsPage }))
)
const ScheduledJobsPage = React.lazy(() =>
  import('./pages/ScheduledJobsPage').then((m) => ({ default: m.ScheduledJobsPage }))
)
const EmailTemplatesPage = React.lazy(() =>
  import('./pages/EmailTemplatesPage').then((m) => ({ default: m.EmailTemplatesPage }))
)
const CampaignsPage = React.lazy(() =>
  import('./pages/CampaignsPage').then((m) => ({ default: m.CampaignsPage }))
)
const ScriptsPage = React.lazy(() =>
  import('./pages/ScriptsPage').then((m) => ({ default: m.ScriptsPage }))
)
const WebhooksPage = React.lazy(() =>
  import('./pages/WebhooksPage').then((m) => ({ default: m.WebhooksPage }))
)
const ApiSpecsPage = React.lazy(() =>
  import('./pages/ApiSpecsPage').then((m) => ({ default: m.ApiSpecsPage }))
)
const ApiSpecDetailPage = React.lazy(() =>
  import('./pages/ApiSpecsPage').then((m) => ({ default: m.ApiSpecDetailPage }))
)
const ConnectedAppsPage = React.lazy(() =>
  import('./pages/ConnectedAppsPage').then((m) => ({ default: m.ConnectedAppsPage }))
)
const CredentialsPage = React.lazy(() =>
  import('./pages/CredentialsPage').then((m) => ({ default: m.CredentialsPage }))
)
const ApiTokensPage = React.lazy(() =>
  import('./pages/ApiTokensPage').then((m) => ({ default: m.ApiTokensPage }))
)
const ModulesPage = React.lazy(() =>
  import('./pages/ModulesPage').then((m) => ({ default: m.ModulesPage }))
)
const TranslationsPage = React.lazy(() =>
  import('./pages/TranslationsPage').then((m) => ({ default: m.TranslationsPage }))
)
const BulkJobsPage = React.lazy(() =>
  import('./pages/BulkJobsPage').then((m) => ({ default: m.BulkJobsPage }))
)
const DeduplicationPage = React.lazy(() =>
  import('./pages/DeduplicationPage').then((m) => ({ default: m.DeduplicationPage }))
)
const SetupHomePage = React.lazy(() =>
  import('./pages/SetupHomePage').then((m) => ({ default: m.SetupHomePage }))
)
const UnauthorizedPage = React.lazy(() =>
  import('./pages/UnauthorizedPage').then((m) => ({ default: m.UnauthorizedPage }))
)
const NotFoundPage = React.lazy(() =>
  import('./pages/NotFoundPage').then((m) => ({ default: m.NotFoundPage }))
)
const ProfilesPage = React.lazy(() =>
  import('./pages/ProfilesPage').then((m) => ({ default: m.ProfilesPage }))
)
const ProfileDetailPage = React.lazy(() =>
  import('./pages/ProfileDetailPage').then((m) => ({ default: m.ProfileDetailPage }))
)
const LoginHistoryPage = React.lazy(() =>
  import('./pages/LoginHistoryPage').then((m) => ({ default: m.LoginHistoryPage }))
)
const SecurityAuditPage = React.lazy(() =>
  import('./pages/SecurityAuditPage').then((m) => ({ default: m.SecurityAuditPage }))
)
const RequestLogPage = React.lazy(() =>
  import('./pages/RequestLogPage').then((m) => ({ default: m.RequestLogPage }))
)
const RequestLogDetailPage = React.lazy(() =>
  import('./pages/RequestLogDetailPage').then((m) => ({ default: m.RequestLogDetailPage }))
)
const LogViewerPage = React.lazy(() =>
  import('./pages/LogViewerPage').then((m) => ({ default: m.LogViewerPage }))
)
const ErrorDashboardPage = React.lazy(() =>
  import('./pages/ErrorDashboardPage').then((m) => ({ default: m.ErrorDashboardPage }))
)
const UserActivityPage = React.lazy(() =>
  import('./pages/UserActivityPage').then((m) => ({ default: m.UserActivityPage }))
)
const EndpointPerformancePage = React.lazy(() =>
  import('./pages/EndpointPerformancePage').then((m) => ({ default: m.EndpointPerformancePage }))
)
const ObservabilitySettingsPage = React.lazy(() =>
  import('./pages/ObservabilitySettingsPage').then((m) => ({
    default: m.ObservabilitySettingsPage,
  }))
)
const SearchSettingsPage = React.lazy(() =>
  import('./pages/SearchSettingsPage').then((m) => ({ default: m.SearchSettingsPage }))
)
const ConfigHealthPage = React.lazy(() =>
  import('./pages/ConfigHealthPage').then((m) => ({ default: m.ConfigHealthPage }))
)
const PasswordPolicyPage = React.lazy(() =>
  import('./pages/SecuritySettingsPage/PasswordPolicyPanel').then((m) => ({
    default: m.PasswordPolicyPanel,
  }))
)
const MfaPolicyPage = React.lazy(() =>
  import('./pages/SecuritySettingsPage/MfaPolicyPanel').then((m) => ({ default: m.MfaPolicyPanel }))
)

// Types
import type { Plugin } from './types/plugin'

// Create a QueryClient instance for TanStack Query
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000, // 5 minutes
      retry: 3,
      refetchOnWindowFocus: false,
    },
  },
})

/**
 * Props for the App component
 */
export interface AppProps {
  /** Optional plugins to load */
  plugins?: Plugin[]
}

/**
 * Auth callback page - shows loading while processing callback, error if it fails
 */
function AuthCallbackPage(): React.ReactElement {
  const { isLoading, error } = useAuth()
  const navigate = useNavigate()
  const { tenantBasePath } = useTenant()

  if (isLoading) {
    return <PageLoader fullPage message="Completing authentication..." />
  }

  if (error) {
    return (
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '100vh',
          padding: '2rem',
          textAlign: 'center',
          fontFamily: 'system-ui, -apple-system, sans-serif',
        }}
        role="alert"
      >
        <h1 style={{ margin: '0 0 1rem', fontSize: '1.5rem', fontWeight: 600 }}>
          Authentication Failed
        </h1>
        <p style={{ margin: '0 0 0.5rem', color: '#666', maxWidth: '500px' }}>{error.message}</p>
        <button
          onClick={() => {
            sessionStorage.removeItem('kelta_auth_login_error')
            navigate(`${tenantBasePath}/login`)
          }}
          style={{
            marginTop: '1.5rem',
            padding: '0.75rem 1.5rem',
            fontSize: '1rem',
            fontWeight: 500,
            color: '#fff',
            backgroundColor: '#0066cc',
            border: 'none',
            borderRadius: '0.375rem',
            cursor: 'pointer',
          }}
        >
          Try Again
        </button>
      </div>
    )
  }

  return <PageLoader fullPage message="Completing authentication..." />
}

/**
 * Bootstrap error display component
 * Shows when the bootstrap API call fails with retry option
 */
function BootstrapError({
  error,
  onRetry,
}: {
  error: Error
  onRetry: () => void
}): React.ReactElement {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        padding: '2rem',
        textAlign: 'center',
        fontFamily: 'system-ui, -apple-system, sans-serif',
      }}
      role="alert"
      aria-live="assertive"
    >
      <div
        style={{
          marginBottom: '1rem',
        }}
        aria-hidden="true"
      >
        <AlertTriangle size={48} />
      </div>
      <h1 style={{ margin: '0 0 1rem', fontSize: '1.5rem', fontWeight: 600 }}>
        Unable to Load Application
      </h1>
      <p style={{ margin: '0 0 0.5rem', color: '#666', maxWidth: '400px' }}>
        Failed to connect to the server. Please check your connection and try again.
      </p>
      <p style={{ margin: '0 0 1.5rem', color: '#999', fontSize: '0.875rem' }}>{error.message}</p>
      <button
        onClick={onRetry}
        style={{
          padding: '0.75rem 1.5rem',
          fontSize: '1rem',
          fontWeight: 500,
          color: '#fff',
          backgroundColor: '#0066cc',
          border: 'none',
          borderRadius: '0.375rem',
          cursor: 'pointer',
        }}
      >
        Retry
      </button>
    </div>
  )
}

/**
 * Admin layout without sidebar — the Setup page acts as the navigation hub.
 * Uses a simple flex layout instead of AppShell to avoid the sidebar aside element.
 */
function AdminLayout({ children }: { children: React.ReactNode }): React.ReactElement {
  const { user, logout } = useAuth()
  const { config, isLoading: configLoading, error, reload } = useConfig()
  const { helpOpen, setHelpOpen } = useGlobalShortcuts()
  const location = useLocation()
  const { tenantBasePath } = useTenant()

  const isSetupPage = location.pathname.endsWith('/setup')

  if (configLoading) {
    return <PageLoader fullPage message="Loading application..." />
  }

  if (error) {
    return <BootstrapError error={error} onRetry={reload} />
  }

  const branding = config?.branding ?? {
    logoUrl: '',
    applicationName: 'Kelta Admin',
    faviconUrl: '',
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100vh',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          position: 'sticky',
          top: 0,
          zIndex: 100,
          height: '60px',
          flexShrink: 0,
          backgroundColor: 'var(--color-surface, #ffffff)',
          borderBottom: '1px solid var(--color-border, #e0e0e0)',
          display: 'flex',
          alignItems: 'center',
        }}
      >
        <Header branding={branding} user={user} onLogout={logout} />
      </div>
      {!isSetupPage && (
        <div
          style={{
            padding: '8px 24px',
            borderBottom: '1px solid var(--color-border, #e0e0e0)',
            backgroundColor: 'var(--color-surface, #ffffff)',
            flexShrink: 0,
          }}
        >
          <Breadcrumb>
            <BreadcrumbList>
              <BreadcrumbItem>
                <BreadcrumbLink asChild>
                  <Link to={`${tenantBasePath}/setup`}>← Setup</Link>
                </BreadcrumbLink>
              </BreadcrumbItem>
            </BreadcrumbList>
          </Breadcrumb>
        </div>
      )}
      <main
        style={{
          flex: 1,
          overflowY: 'auto',
          overflowX: 'hidden',
          padding: '24px',
          backgroundColor: 'var(--color-background, #ffffff)',
        }}
      >
        <PageTransition type="fade" duration={200}>
          {children}
        </PageTransition>
      </main>
      <KeyboardShortcutsHelp isOpen={helpOpen} onClose={() => setHelpOpen(false)} />
    </div>
  )
}

/**
 * Redirect helper for legacy request-log/:traceId routes.
 * Reads the traceId param and redirects to monitoring/requests/:traceId.
 */
function NavigateWithTraceId(): React.ReactElement {
  const { traceId } = useParams()
  return <Navigate to={`monitoring/requests/${traceId}`} replace />
}

/**
 * Protected route wrapper for admin pages (no sidebar)
 */
function AdminPageRoute({
  children,
  requiredPolicies,
}: {
  children: React.ReactNode
  requiredPolicies?: string[]
}): React.ReactElement {
  const { tenantBasePath } = useTenant()

  return (
    <ProtectedRoute
      requiredPolicies={requiredPolicies}
      loginPath={`${tenantBasePath}/login`}
      unauthorizedPath={`${tenantBasePath}/unauthorized`}
    >
      <AdminLayout>{children}</AdminLayout>
    </ProtectedRoute>
  )
}

/**
 * Tenant-scoped application wrapper.
 * Wraps all providers that depend on the tenant slug from the URL.
 *
 * Provider hierarchy:
 * 1. TenantProvider - Tenant identity from URL slug
 * 2. AuthProvider - Authentication state and OIDC flow
 * 3. ApiProvider - Authenticated API client (slug-prefixed base URL)
 * 4. ConfigProvider - Bootstrap configuration
 * 5. ThemeProvider - Theme state and CSS custom properties
 * 6. I18nProvider - Internationalization
 * 7. PluginProvider - Plugin system
 * 8. ToastProvider - Toast notifications
 * 9. LiveRegionProvider - Screen reader announcements
 */
/**
 * Points the browser at a tenant-scoped web app manifest.
 *
 * The manifest file is one static asset, but its URLs are relative and resolve against the
 * manifest's own URL — so serving it as /{slug}/manifest.webmanifest makes start_url "./app"
 * mean /{slug}/app. That is the only way a Home Screen icon can remember its tenant: an
 * installed iOS web app gets its own empty storage container, so nothing but the URL survives
 * the install. On a custom domain the root manifest is right, since routes carry no slug.
 */
function useTenantManifest(tenantBasePath: string): void {
  useEffect(() => {
    const href = `${tenantBasePath}/manifest.webmanifest`
    let link = document.querySelector<HTMLLinkElement>('link[rel="manifest"]')
    if (!link) {
      link = document.createElement('link')
      link.rel = 'manifest'
      document.head.appendChild(link)
    }
    if (link.getAttribute('href') !== href) {
      link.setAttribute('href', href)
    }
  }, [tenantBasePath])
}

function TenantScopedApp({ plugins = [] }: { plugins?: Plugin[] }): React.ReactElement {
  const { tenantBasePath } = useTenant()
  useTenantManifest(tenantBasePath)
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || ''
  // tenantBasePath is "" on a custom domain, "/<slug>" on the platform host.
  // URLs follow the same rule: callbacks and API base both skip the slug
  // segment when we're on a custom domain.
  const apiClientBaseUrl = `${apiBaseUrl}${tenantBasePath}`

  return (
    <AuthProvider
      redirectUri={window.location.origin + tenantBasePath + '/auth/callback'}
      postLogoutRedirectUri={window.location.origin + (tenantBasePath || '/')}
    >
      <ApiProvider baseUrl={apiClientBaseUrl}>
        <CollectionStoreProvider>
          <ConfigProvider>
            <ThemeProvider>
              <I18nProvider>
                {/* Pushes tenant-authored translations from bootstrap into i18n (slice 4). */}
                <TenantTranslationsBridge />
                {/* loadModuleBundles: only the real app fetches runtime-module UI bundles;
                    test wrappers keep the default so they make no extra request. */}
                <PluginProvider plugins={plugins} loadModuleBundles>
                  <AppContextProvider>
                    <AiChatProvider>
                      <ToastProvider>
                        <LiveRegionProvider>
                          <TenantRoutes />
                          <AiChatPanel baseUrl={apiClientBaseUrl} />
                          <AiChatTrigger />
                        </LiveRegionProvider>
                      </ToastProvider>
                    </AiChatProvider>
                  </AppContextProvider>
                </PluginProvider>
              </I18nProvider>
            </ThemeProvider>
          </ConfigProvider>
        </CollectionStoreProvider>
      </ApiProvider>
    </AuthProvider>
  )
}

/**
 * All tenant-scoped routes. Paths are relative to /:tenantSlug/.
 */
function TenantRoutes(): React.ReactElement {
  const { tenantBasePath } = useTenant()
  return (
    <React.Suspense fallback={<PageLoader fullPage message="Loading..." />}>
      <Routes>
        {/* Public routes */}
        <Route path="login" element={<LoginPage />} />
        <Route path="unauthorized" element={<UnauthorizedPage />} />

        {/* OAuth callback route */}
        <Route path="auth/callback" element={<AuthCallbackPage />} />

        {/* Root redirects to end-user app */}
        <Route
          path=""
          element={
            <ProtectedRoute
              loginPath={`${tenantBasePath}/login`}
              unauthorizedPath={`${tenantBasePath}/unauthorized`}
            >
              <Navigate to="app" replace />
            </ProtectedRoute>
          }
        />

        {/* System Health Dashboard */}
        <Route
          path="system-health"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <DashboardPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Collections routes */}
        <Route
          path="collections"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <CollectionsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="collections/new"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <CollectionWizardPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="collections/:id"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <CollectionDetailPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="collections/:id/edit"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <CollectionFormPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* OIDC Providers route */}
        <Route
          path="oidc-providers"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_CONNECTED_APPS">
                <OIDCProvidersPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Workers route */}
        {/* UI Builder routes */}
        <Route
          path="pages"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <PageBuilderPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="menus"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <MenuBuilderPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Package Management route */}
        <Route
          path="packages"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <PackagesPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Migrations route */}
        <Route
          path="migrations"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <MigrationsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Environments route (sandboxes + metadata promotion) */}
        <Route
          path="environments"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_SANDBOXES">
                <EnvironmentsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Resource Browser routes */}
        <Route
          path="resources"
          element={
            <AdminPageRoute>
              <ResourceBrowserPage />
            </AdminPageRoute>
          }
        />
        <Route
          path="resources/:collection"
          element={
            <AdminPageRoute>
              <ResourceListPage />
            </AdminPageRoute>
          }
        />
        <Route
          path="resources/:collection/new"
          element={
            <AdminPageRoute>
              <ResourceFormPage />
            </AdminPageRoute>
          }
        />
        <Route
          path="resources/:collection/:id"
          element={
            <AdminPageRoute>
              <ResourceDetailPage />
            </AdminPageRoute>
          }
        />
        <Route
          path="resources/:collection/:id/edit"
          element={
            <AdminPageRoute>
              <ResourceFormPage />
            </AdminPageRoute>
          }
        />

        {/* Picklists route */}
        <Route
          path="picklists"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <PicklistsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* AI Agents route */}
        <Route
          path="ai-agents"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <AiAgentsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Page Layouts route */}
        <Route
          path="layouts"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <PageLayoutsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* List Views route */}
        <Route
          path="listviews"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_LISTVIEWS">
                <ListViewsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Analytics route (replaces Reports + Dashboards with Superset) */}
        <Route
          path="analytics"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_REPORTS">
                <AnalyticsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Permanent backward-compat aliases for /reports and /dashboards.
          The original ReportsPage and DashboardsPage components have been removed;
          both paths now render AnalyticsPage so external bookmarks keep working. */}
        <Route
          path="reports"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_REPORTS">
                <AnalyticsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="dashboards"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_REPORTS">
                <AnalyticsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Approval Processes route */}
        <Route
          path="approvals"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_APPROVALS">
                <ApprovalProcessesPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Flows route */}
        <Route
          path="flows"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_WORKFLOWS">
                <FlowsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Flow Designer route */}
        <Route
          path="flows/:flowId/design"
          element={
            <RequirePermission permission="MANAGE_WORKFLOWS">
              <FlowDesignerPage />
            </RequirePermission>
          }
        />

        {/* Modules route */}
        <Route
          path="modules"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_CONNECTED_APPS">
                <ModulesPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Scheduled Jobs route */}
        <Route
          path="scheduled-jobs"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_WORKFLOWS">
                <ScheduledJobsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Email Templates route */}
        <Route
          path="email-templates"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_EMAIL_TEMPLATES">
                <EmailTemplatesPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Support Mailboxes route. The wrapper is UX only — RequirePermission renders children
            while permissions load, so MANAGE_SUPPORT_MAILBOX is enforced server-side on every
            call the page makes. */}
        <Route
          path="mailboxes"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_SUPPORT_MAILBOX">
                <React.Suspense fallback={<PageLoader message="Loading..." />}>
                  <MailboxAdminPage />
                </React.Suspense>
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Campaigns route */}
        <Route
          path="campaigns"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_CAMPAIGNS">
                <CampaignsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Plugins route */}
        <Route
          path="plugins"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <PluginsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* User Management routes — the list page also admits delegated admins (scoped mode) */}
        <Route
          path="users"
          element={
            <AdminPageRoute>
              <RequireUserManagementAccess>
                <UsersPage />
              </RequireUserManagementAccess>
            </AdminPageRoute>
          }
        />
        <Route
          path="users/:id"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <UserDetailPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="delegated-admins"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_DELEGATED_ADMINS">
                <DelegatedAdminsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Audit Trail route */}
        <Route
          path="audit-trail"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <SetupAuditTrailPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Security Management routes */}
        <Route
          path="profiles"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <ProfilesPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="profiles/:id"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <ProfileDetailPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="password-policy"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <PasswordPolicyPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="mfa-policy"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <MfaPolicyPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="login-history"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <LoginHistoryPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="security-audit"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_USERS">
                <SecurityAuditPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Governor Limits route */}
        <Route
          path="governor-limits"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <GovernorLimitsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* AI Settings route (platform admin only) */}
        <Route
          path="ai-settings"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_TENANTS">
                <AiSettingsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Email Settings route — tenant admins configure SMTP + From + templates */}
        <Route
          path="email-settings"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_TENANTS">
                <EmailSettingsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Map Settings route — tenant admins configure Mapbox token + style */}
        <Route
          path="map-settings"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_TENANTS">
                <MapSettingsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Telehealth retention route — view for admins; retention edits + legal
            hold gated by MANAGE_DATA inside the page (server enforces the same). */}
        <Route
          path="telehealth-settings"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <TelehealthSettingsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Network Access route — tenant admins configure the IP allowlist */}
        <Route
          path="network-access"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_TENANTS">
                <NetworkAccessPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Monitoring hub — consolidated observability section */}
        <Route
          path="monitoring"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <MonitoringLayout />
              </RequirePermission>
            </AdminPageRoute>
          }
        >
          <Route index element={<Navigate to="overview" replace />} />
          <Route path="overview" element={<MonitoringOverviewPage />} />
          <Route path="requests" element={<RequestLogPage />} />
          <Route path="requests/:traceId" element={<RequestLogDetailPage />} />
          <Route path="logs" element={<LogViewerPage />} />
          <Route path="errors" element={<ErrorDashboardPage />} />
          <Route path="performance" element={<EndpointPerformancePage />} />
          <Route path="activity" element={<UserActivityPage />} />
          <Route path="health" element={<ConfigHealthPage />} />
          <Route
            path="settings"
            element={
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <ObservabilitySettingsPage />
              </RequirePermission>
            }
          />
        </Route>

        {/* Legacy observability route redirects */}
        <Route path="metrics" element={<Navigate to="monitoring/overview" replace />} />
        <Route path="request-log" element={<Navigate to="monitoring/requests" replace />} />
        <Route path="request-log/:traceId" element={<NavigateWithTraceId />} />
        <Route path="logs" element={<Navigate to="monitoring/logs" replace />} />
        <Route path="errors" element={<Navigate to="monitoring/errors" replace />} />
        <Route
          path="endpoint-performance"
          element={<Navigate to="monitoring/performance" replace />}
        />
        <Route path="user-activity" element={<Navigate to="monitoring/activity" replace />} />
        <Route
          path="observability-settings"
          element={<Navigate to="monitoring/settings" replace />}
        />

        {/* Tenant Management routes (platform admin) */}
        <Route
          path="tenants"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_TENANTS">
                <TenantsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />
        <Route
          path="tenant-dashboard"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <TenantDashboardPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Scripts route */}
        <Route
          path="scripts"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_CONNECTED_APPS">
                <ScriptsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Webhooks route */}
        <Route
          path="webhooks"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_CONNECTED_APPS">
                <WebhooksPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Connected Apps route */}
        <Route
          path="connected-apps"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_CONNECTED_APPS">
                <ConnectedAppsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Credentials route — tenant-managed secrets used by flows */}
        <Route
          path="credentials"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_CREDENTIALS">
                <CredentialsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* OpenAPI Spec Library — list + import */}
        <Route
          path="api-specs"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_API_SPECS">
                <ApiSpecsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Spec detail — overview, operations browser, raw */}
        <Route
          path="api-specs/:specId"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_API_SPECS">
                <ApiSpecDetailPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Tenant translations route (app-intelligence slice 4) */}
        <Route
          path="translations"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <TranslationsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Bulk Jobs route */}
        <Route
          path="bulk-jobs"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_DATA">
                <BulkJobsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Deduplication route */}
        <Route
          path="dedup"
          element={
            <AdminPageRoute>
              <RequirePermission permission="MANAGE_DATA">
                <DeduplicationPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Search Settings route */}
        <Route
          path="search-settings"
          element={
            <AdminPageRoute>
              <RequirePermission permission="CUSTOMIZE_APPLICATION">
                <SearchSettingsPage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* Setup Home Page */}
        <Route
          path="setup"
          element={
            <AdminPageRoute>
              <RequirePermission permission="VIEW_SETUP">
                <SetupHomePage />
              </RequirePermission>
            </AdminPageRoute>
          }
        />

        {/* ============================================
         * END-USER RUNTIME (shadcn/Tailwind)
         * All routes under /app use the EndUserShell
         * with horizontal top nav bar.
         * ============================================ */}
        <Route
          path="app"
          element={
            <ProtectedRoute
              loginPath={`${tenantBasePath}/login`}
              unauthorizedPath={`${tenantBasePath}/unauthorized`}
            >
              <EndUserShell />
            </ProtectedRoute>
          }
        >
          <Route index element={<Navigate to="home" replace />} />
          <Route
            path="home"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <AppHomePage />
              </React.Suspense>
            }
          />
          <Route
            path="o/:collection"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserObjectListPage />
              </React.Suspense>
            }
          />
          <Route
            path="o/:collection/new"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserObjectFormPage />
              </React.Suspense>
            }
          />
          <Route
            path="o/:collection/:id"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserObjectDetailPage />
              </React.Suspense>
            }
          />
          <Route
            path="o/:collection/:id/edit"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserObjectFormPage />
              </React.Suspense>
            }
          />
          <Route
            path="search"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <GlobalSearchPage />
              </React.Suspense>
            }
          />
          <Route
            path="p/:pageSlug"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserCustomPage />
              </React.Suspense>
            }
          />
          <Route
            path="approvals"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserApprovalsInboxPage />
              </React.Suspense>
            }
          />
          <Route
            path="chat"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserChatConsolePage />
              </React.Suspense>
            }
          />
          {/* No RequirePermission wrapper, matching chat and approvals: membership is enforced
              server-side and a non-member simply sees an empty state. A client gate here would
              also fail open while permissions load, so it would be decoration either way. */}
          <Route
            path="mailbox"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserMailboxConsolePage />
              </React.Suspense>
            }
          />
          <Route
            path="appointments"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserAppointmentsPage />
              </React.Suspense>
            }
          />
          <Route
            path="provider-availability"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserProviderAvailabilityPage />
              </React.Suspense>
            }
          />
          <Route
            path="visits/:appointmentId"
            element={
              <React.Suspense fallback={<PageLoader message="Loading..." />}>
                <EndUserVisitPage />
              </React.Suspense>
            }
          />
          <Route
            path="analytics"
            element={
              <RequirePermission permission="VIEW_ANALYTICS">
                <React.Suspense fallback={<PageLoader message="Loading..." />}>
                  <EndUserAnalyticsHubPage />
                </React.Suspense>
              </RequirePermission>
            }
          />
          <Route
            path="dashboards/:id"
            element={
              <RequirePermission permission="VIEW_ANALYTICS">
                <React.Suspense fallback={<PageLoader message="Loading..." />}>
                  <EndUserDashboardViewPage />
                </React.Suspense>
              </RequirePermission>
            }
          />
          <Route
            path="reports/:id"
            element={
              <RequirePermission permission="VIEW_ANALYTICS">
                <React.Suspense fallback={<PageLoader message="Loading..." />}>
                  <EndUserReportViewPage />
                </React.Suspense>
              </RequirePermission>
            }
          />
          <Route path="api-tokens" element={<ApiTokensPage />} />
        </Route>

        {/* 404 Not Found - catch all within tenant scope */}
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </React.Suspense>
  )
}

/**
 * App Component
 *
 * The root component that sets up routing with tenant slug prefix.
 * All routes are scoped under /:tenantSlug/.
 *
 * Route structure:
 * - /:tenantSlug/* → TenantScopedApp (all providers + routes)
 * - / → NoTenantPage (error: tenant slug required)
 * - * → NoTenantPage (catch-all)
 */
function App({ plugins = [] }: AppProps): React.ReactElement {
  // On a customer custom domain (anything not under .kelta.io) the URL has no
  // tenant slug — the gateway resolves the tenant from the Host header. Mount
  // the scoped app at the root in that mode so /login, /app/... etc. all work
  // without a slug prefix. On the platform host we keep the /:tenantSlug/*
  // route so existing URLs (and the NoTenantPage error UX) stay intact.
  const customDomain = isCustomDomainHost()
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Routes>
            {customDomain ? (
              <Route
                path="/*"
                element={
                  <TenantProvider>
                    <TenantScopedApp plugins={plugins} />
                  </TenantProvider>
                }
              />
            ) : (
              <>
                <Route
                  path="/:tenantSlug/*"
                  element={
                    <TenantProvider>
                      <TenantScopedApp plugins={plugins} />
                    </TenantProvider>
                  }
                />
                <Route path="/" element={<NoTenantPage />} />
                <Route path="*" element={<NoTenantPage />} />
              </>
            )}
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  )
}

export default App
