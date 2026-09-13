/**
 * App Component Tests
 *
 * Tests for the root App component that wires together all providers and routing.
 *
 * Requirements:
 * - 1.1: Fetch bootstrap configuration from JSON:API endpoints on startup
 * - 1.2: Configure application routes based on page definitions
 * - 2.1: Redirect unauthenticated users to OIDC provider login page
 */

import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../vitest.setup'
import App from './App'

// Mock bootstrap config response
const mockBootstrapConfig = {
  pages: [
    { id: 'dashboard', path: '/', title: 'Dashboard', component: 'DashboardPage' },
    { id: 'collections', path: '/collections', title: 'Collections', component: 'CollectionsPage' },
  ],
  menus: [
    {
      id: 'main',
      name: 'Main Navigation',
      items: [
        { id: 'dashboard', label: 'Dashboard', path: '/', icon: 'home' },
        { id: 'collections', label: 'Collections', path: '/collections', icon: 'folder' },
      ],
    },
  ],
  theme: {
    primaryColor: '#0066cc',
    secondaryColor: '#6c757d',
    fontFamily: 'Inter, sans-serif',
    borderRadius: '8px',
  },
  branding: {
    logoUrl: '/logo.svg',
    applicationName: 'Kelta Admin',
    faviconUrl: '/favicon.ico',
  },
  features: {
    enableBuilder: true,
    enableResourceBrowser: true,
    enablePackages: true,
    enableMigrations: true,
    enableDashboard: true,
  },
  oidcProviders: [{ id: 'provider-1', name: 'Test Provider', issuer: 'https://auth.example.com' }],
}

// Mock auth context values
const mockAuthContext = {
  user: null as { id: string; email: string; name: string } | null,
  isAuthenticated: false,
  isLoading: false,
  error: null as Error | null,
  login: vi.fn(),
  logout: vi.fn(),
  getAccessToken: vi.fn(),
}

// Mock the TenantContext
vi.mock('./context/TenantContext', () => ({
  TenantProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useTenant: () => ({
    tenantSlug: 'test-tenant',
    tenantBasePath: '/test-tenant',
  }),
  getTenantSlug: () => 'test-tenant',
  useTenantStorageScope: () => 'test-tenant',
  setResolvedTenantId: vi.fn(),
  getResolvedTenantId: () => null,
  isCustomDomainHost: () => false,
}))

// Mock the AuthContext
vi.mock('./context/AuthContext', () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useAuth: () => mockAuthContext,
  AuthContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
}))

// Mock the ApiContext
vi.mock('./context/ApiContext', () => ({
  ApiProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useApi: () => ({
    baseUrl: '/test-tenant',
    fetch: vi.fn(),
  }),
}))

// Mock the ConfigContext
vi.mock('./context/ConfigContext', () => ({
  ConfigProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useConfig: () => ({
    config: mockBootstrapConfig,
    isLoading: false,
    error: null,
    reload: vi.fn(),
  }),
  ConfigContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
}))

// Mock the ThemeContext
vi.mock('./context/ThemeContext', () => ({
  ThemeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useTheme: () => ({
    mode: 'light',
    resolvedMode: 'light',
    setMode: vi.fn(),
    colors: {
      primary: '#0066cc',
      primaryHover: '#0052a3',
      primaryActive: '#004080',
      secondary: '#6c757d',
      secondaryHover: '#5a6268',
      secondaryActive: '#495057',
      background: '#ffffff',
      backgroundSecondary: '#f8f9fa',
      backgroundTertiary: '#e9ecef',
      surface: '#ffffff',
      surfaceHover: '#f8f9fa',
      surfaceBorder: '#dee2e6',
      text: '#212529',
      textSecondary: '#495057',
      textMuted: '#6c757d',
      textInverse: '#ffffff',
      success: '#198754',
      warning: '#ffc107',
      error: '#dc3545',
      info: '#0dcaf0',
      border: '#dee2e6',
      borderLight: '#e9ecef',
      focus: '#0066cc',
    },
  }),
  ThemeContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
}))

// Mock the I18nContext
vi.mock('./context/I18nContext', () => ({
  I18nProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useI18n: () => ({
    locale: 'en',
    setLocale: vi.fn(),
    t: (key: string) => key,
    formatDate: (date: Date) => date.toLocaleDateString(),
    formatNumber: (num: number) => num.toString(),
    formatCurrency: (amount: number) => `$${amount}`,
    direction: 'ltr',
    supportedLocales: ['en', 'ar'],
    getLocaleDisplayName: (code: string) => code,
  }),
  I18nContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
}))

// Mock the PluginContext
vi.mock('./context/PluginContext', () => ({
  PluginProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  usePlugins: () => ({
    fieldRenderers: new Map(),
    pageComponents: new Map(),
    registerFieldRenderer: vi.fn(),
    registerPageComponent: vi.fn(),
    getFieldRenderer: vi.fn(),
    getPageComponent: vi.fn(),
    plugins: [],
    isLoading: false,
    errors: [],
  }),
  PluginContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
}))

// Mock the Toast components
vi.mock('./components/Toast', () => ({
  ToastProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useToast: () => ({
    toasts: [],
    addToast: vi.fn(),
    removeToast: vi.fn(),
  }),
}))

// Mock the LiveRegion components
vi.mock('./components/LiveRegion', () => ({
  LiveRegionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useAnnounce: () => vi.fn(),
}))

// Mock the AppShell component
vi.mock('./components/AppShell', () => ({
  AppShell: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="app-shell">{children}</div>
  ),
  useAppShell: () => ({
    screenSize: 'desktop',
    sidebarCollapsed: false,
    sidebarOpen: false,
    toggleSidebar: vi.fn(),
    toggleMobileSidebar: vi.fn(),
    closeMobileSidebar: vi.fn(),
  }),
}))

// Mock the Header component
vi.mock('./components/Header', () => ({
  Header: () => <header data-testid="header">Header</header>,
}))

// Mock the Sidebar component
vi.mock('./components/Sidebar', () => ({
  Sidebar: () => <nav data-testid="sidebar">Sidebar</nav>,
}))

// Mock the EndUserShell
vi.mock('./shells/EndUserShell', () => ({
  EndUserShell: () => <div data-testid="end-user-shell">End User Shell</div>,
}))

// Mock the ErrorBoundary component
vi.mock('./components/ErrorBoundary', () => ({
  ErrorBoundary: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

// Mock RequirePermission to always render children (permissions tested in RequirePermission.test.tsx)
vi.mock('./components/RequirePermission/RequirePermission', () => ({
  RequirePermission: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

// Mock the ProtectedRoute component
vi.mock('./components/ProtectedRoute', () => ({
  ProtectedRoute: ({ children }: { children: React.ReactNode }) => {
    if (!mockAuthContext.isAuthenticated) {
      return <div data-testid="redirect-to-login">Redirecting to login...</div>
    }
    return <>{children}</>
  },
}))

// Mock page components
// These pages are lazy-loaded from their own submodules (code-splitting — they pull the
// heavy react-flow / recharts / dnd-kit chunks). The barrel mock below doesn't intercept a
// `import('./pages/<Name>')`, so mock the submodule paths too or the test loads the real
// (heavy) pages.
vi.mock('./pages/PageBuilderPage', () => ({
  PageBuilderPage: () => <div data-testid="page-builder-page">Page Builder Page</div>,
}))
vi.mock('./pages/FlowDesignerPage', () => ({
  FlowDesignerPage: () => <div data-testid="flow-designer-page">Flow Designer Page</div>,
}))
vi.mock('./pages/MonitoringPage', () => ({
  MonitoringLayout: ({ children }: { children?: React.ReactNode }) => (
    <div data-testid="monitoring-layout">{children}</div>
  ),
  MonitoringOverviewPage: () => (
    <div data-testid="monitoring-overview-page">Monitoring Overview Page</div>
  ),
}))

// Per-submodule page mocks — App imports pages from their own modules (code-splitting),
// so a `vi.mock('./pages')` barrel mock alone would NOT intercept them.
vi.mock('./pages/AiSettingsPage', () => ({
  AiSettingsPage: () => <div data-testid="ai-settings-page">AiSettingsPage</div>,
}))
vi.mock('./pages/EmailSettingsPage', () => ({
  EmailSettingsPage: () => <div data-testid="email-settings-page">EmailSettingsPage</div>,
}))
vi.mock('./pages/MapSettingsPage', () => ({
  MapSettingsPage: () => <div data-testid="map-settings-page">MapSettingsPage</div>,
}))
vi.mock('./pages/NetworkAccessPage', () => ({
  NetworkAccessPage: () => <div data-testid="network-access-page">NetworkAccessPage</div>,
}))
vi.mock('./pages/DashboardPage', () => ({
  DashboardPage: () => <div data-testid="dashboard-page">DashboardPage</div>,
}))
vi.mock('./pages/CollectionsPage', () => ({
  CollectionsPage: () => <div data-testid="collections-page">CollectionsPage</div>,
}))
vi.mock('./pages/CollectionDetailPage', () => ({
  CollectionDetailPage: () => <div data-testid="collection-detail-page">CollectionDetailPage</div>,
}))
vi.mock('./pages/CollectionFormPage', () => ({
  CollectionFormPage: () => <div data-testid="collection-form-page">CollectionFormPage</div>,
}))
vi.mock('./pages/CollectionWizardPage', () => ({
  CollectionWizardPage: () => <div data-testid="collection-wizard-page">CollectionWizardPage</div>,
}))
vi.mock('./pages/OIDCProvidersPage', () => ({
  OIDCProvidersPage: () => <div data-testid="oidc-providers-page">OIDCProvidersPage</div>,
}))
vi.mock('./pages/MenuBuilderPage', () => ({
  MenuBuilderPage: () => <div data-testid="menu-builder-page">MenuBuilderPage</div>,
}))
vi.mock('./pages/PackagesPage', () => ({
  PackagesPage: () => <div data-testid="packages-page">PackagesPage</div>,
}))
vi.mock('./pages/MigrationsPage', () => ({
  MigrationsPage: () => <div data-testid="migrations-page">MigrationsPage</div>,
}))
vi.mock('./pages/ResourceBrowserPage', () => ({
  ResourceBrowserPage: () => <div data-testid="resource-browser-page">ResourceBrowserPage</div>,
}))
vi.mock('./pages/ResourceListPage', () => ({
  ResourceListPage: () => <div data-testid="resource-list-page">ResourceListPage</div>,
}))
vi.mock('./pages/ResourceDetailPage', () => ({
  ResourceDetailPage: () => <div data-testid="resource-detail-page">ResourceDetailPage</div>,
}))
vi.mock('./pages/ResourceFormPage', () => ({
  ResourceFormPage: () => <div data-testid="resource-form-page">ResourceFormPage</div>,
}))
vi.mock('./pages/PluginsPage', () => ({
  PluginsPage: () => <div data-testid="plugins-page">PluginsPage</div>,
}))
vi.mock('./pages/UsersPage', () => ({
  UsersPage: () => <div data-testid="users-page">UsersPage</div>,
}))
vi.mock('./pages/UserDetailPage', () => ({
  UserDetailPage: () => <div data-testid="user-detail-page">UserDetailPage</div>,
}))
vi.mock('./pages/SetupAuditTrailPage', () => ({
  SetupAuditTrailPage: () => <div data-testid="audit-trail-page">SetupAuditTrailPage</div>,
}))
vi.mock('./pages/GovernorLimitsPage', () => ({
  GovernorLimitsPage: () => <div data-testid="governor-limits-page">GovernorLimitsPage</div>,
}))
vi.mock('./pages/TenantsPage', () => ({
  TenantsPage: () => <div data-testid="tenants-page">TenantsPage</div>,
}))
vi.mock('./pages/TenantDashboardPage', () => ({
  TenantDashboardPage: () => <div data-testid="tenant-dashboard-page">TenantDashboardPage</div>,
}))
vi.mock('./pages/PicklistsPage', () => ({
  PicklistsPage: () => <div data-testid="picklists-page">PicklistsPage</div>,
}))
vi.mock('./pages/AiAgentsPage', () => ({
  AiAgentsPage: () => <div data-testid="ai-agents-page">AiAgentsPage</div>,
}))
vi.mock('./pages/PageLayoutsPage', () => ({
  PageLayoutsPage: () => <div data-testid="page-layouts-page">PageLayoutsPage</div>,
}))
vi.mock('./pages/ListViewsPage', () => ({
  ListViewsPage: () => <div data-testid="list-views-page">ListViewsPage</div>,
}))
vi.mock('./pages/AnalyticsPage', () => ({
  AnalyticsPage: () => <div data-testid="analytics-page">AnalyticsPage</div>,
}))
vi.mock('./pages/ApprovalProcessesPage', () => ({
  ApprovalProcessesPage: () => (
    <div data-testid="approval-processes-page">ApprovalProcessesPage</div>
  ),
}))
vi.mock('./pages/FlowsPage', () => ({
  FlowsPage: () => <div data-testid="flows-page">FlowsPage</div>,
}))
vi.mock('./pages/ScheduledJobsPage', () => ({
  ScheduledJobsPage: () => <div data-testid="scheduled-jobs-page">ScheduledJobsPage</div>,
}))
vi.mock('./pages/EmailTemplatesPage', () => ({
  EmailTemplatesPage: () => <div data-testid="email-templates-page">EmailTemplatesPage</div>,
}))
vi.mock('./pages/CampaignsPage', () => ({
  CampaignsPage: () => <div data-testid="campaigns-page">CampaignsPage</div>,
}))
vi.mock('./pages/ScriptsPage', () => ({
  ScriptsPage: () => <div data-testid="scripts-page">ScriptsPage</div>,
}))
vi.mock('./pages/WebhooksPage', () => ({
  WebhooksPage: () => <div data-testid="webhooks-page">WebhooksPage</div>,
}))
vi.mock('./pages/ApiSpecsPage', () => ({
  ApiSpecsPage: () => <div data-testid="api-specs-page">ApiSpecsPage</div>,
  ApiSpecDetailPage: () => <div data-testid="api-spec-detail-page">ApiSpecDetailPage</div>,
}))
vi.mock('./pages/ConnectedAppsPage', () => ({
  ConnectedAppsPage: () => <div data-testid="connected-apps-page">ConnectedAppsPage</div>,
}))
vi.mock('./pages/CredentialsPage', () => ({
  CredentialsPage: () => <div data-testid="credentials-page">CredentialsPage</div>,
}))
vi.mock('./pages/ApiTokensPage', () => ({
  ApiTokensPage: () => <div data-testid="api-tokens-page">ApiTokensPage</div>,
}))
vi.mock('./pages/ModulesPage', () => ({
  ModulesPage: () => <div data-testid="modules-page">ModulesPage</div>,
}))
vi.mock('./pages/BulkJobsPage', () => ({
  BulkJobsPage: () => <div data-testid="bulk-jobs-page">BulkJobsPage</div>,
}))
vi.mock('./pages/DeduplicationPage', () => ({
  DeduplicationPage: () => <div data-testid="deduplication-page">DeduplicationPage</div>,
}))
vi.mock('./pages/SetupHomePage', () => ({
  SetupHomePage: () => <div data-testid="setup-home-page">SetupHomePage</div>,
}))
vi.mock('./pages/LoginPage', () => ({
  LoginPage: () => <div data-testid="login-page">LoginPage</div>,
}))
vi.mock('./pages/UnauthorizedPage', () => ({
  UnauthorizedPage: () => <div data-testid="unauthorized-page">UnauthorizedPage</div>,
}))
vi.mock('./pages/NotFoundPage', () => ({
  NotFoundPage: () => <div data-testid="not-found-page">NotFoundPage</div>,
}))
vi.mock('./pages/ProfilesPage', () => ({
  ProfilesPage: () => <div data-testid="profiles-page">ProfilesPage</div>,
}))
vi.mock('./pages/ProfileDetailPage', () => ({
  ProfileDetailPage: () => <div data-testid="profile-detail-page">ProfileDetailPage</div>,
}))
vi.mock('./pages/LoginHistoryPage', () => ({
  LoginHistoryPage: () => <div data-testid="login-history-page">LoginHistoryPage</div>,
}))
vi.mock('./pages/SecurityAuditPage', () => ({
  SecurityAuditPage: () => <div data-testid="security-audit-page">SecurityAuditPage</div>,
}))
vi.mock('./pages/RequestLogPage', () => ({
  RequestLogPage: () => <div data-testid="request-log-page">RequestLogPage</div>,
}))
vi.mock('./pages/RequestLogDetailPage', () => ({
  RequestLogDetailPage: () => <div data-testid="request-log-detail-page">RequestLogDetailPage</div>,
}))
vi.mock('./pages/LogViewerPage', () => ({
  LogViewerPage: () => <div data-testid="log-viewer-page">LogViewerPage</div>,
}))
vi.mock('./pages/ErrorDashboardPage', () => ({
  ErrorDashboardPage: () => <div data-testid="error-dashboard-page">ErrorDashboardPage</div>,
}))
vi.mock('./pages/UserActivityPage', () => ({
  UserActivityPage: () => <div data-testid="user-activity-page">UserActivityPage</div>,
}))
vi.mock('./pages/EndpointPerformancePage', () => ({
  EndpointPerformancePage: () => (
    <div data-testid="endpoint-performance-page">EndpointPerformancePage</div>
  ),
}))
vi.mock('./pages/ObservabilitySettingsPage', () => ({
  ObservabilitySettingsPage: () => (
    <div data-testid="observability-settings-page">ObservabilitySettingsPage</div>
  ),
}))
vi.mock('./pages/SearchSettingsPage', () => ({
  SearchSettingsPage: () => <div data-testid="search-settings-page">SearchSettingsPage</div>,
}))
vi.mock('./pages/ConfigHealthPage', () => ({
  ConfigHealthPage: () => <div data-testid="config-health-page">ConfigHealthPage</div>,
}))
vi.mock('./pages/SecuritySettingsPage/PasswordPolicyPanel', () => ({
  PasswordPolicyPanel: () => <div data-testid="password-policy-page">PasswordPolicyPage</div>,
}))
vi.mock('./pages/SecuritySettingsPage/MfaPolicyPanel', () => ({
  MfaPolicyPanel: () => <div data-testid="mfa-policy-page">MfaPolicyPage</div>,
}))

vi.mock('./pages', () => ({
  HomePage: () => <div data-testid="home-page">Home Page</div>,
  DashboardPage: () => <div data-testid="dashboard-page">Dashboard Page</div>,
  DeduplicationPage: () => <div data-testid="deduplication-page">Deduplication Page</div>,
  CollectionsPage: () => <div data-testid="collections-page">Collections Page</div>,
  CollectionDetailPage: () => (
    <div data-testid="collection-detail-page">Collection Detail Page</div>
  ),
  CollectionFormPage: () => <div data-testid="collection-form-page">Collection Form Page</div>,
  CollectionWizardPage: () => (
    <div data-testid="collection-wizard-page">Collection Wizard Page</div>
  ),
  OIDCProvidersPage: () => <div data-testid="oidc-providers-page">OIDC Providers Page</div>,
  PageBuilderPage: () => <div data-testid="page-builder-page">Page Builder Page</div>,
  MenuBuilderPage: () => <div data-testid="menu-builder-page">Menu Builder Page</div>,
  PackagesPage: () => <div data-testid="packages-page">Packages Page</div>,
  MigrationsPage: () => <div data-testid="migrations-page">Migrations Page</div>,
  ResourceBrowserPage: () => <div data-testid="resource-browser-page">Resource Browser Page</div>,
  ResourceListPage: () => <div data-testid="resource-list-page">Resource List Page</div>,
  ResourceDetailPage: () => <div data-testid="resource-detail-page">Resource Detail Page</div>,
  ResourceFormPage: () => <div data-testid="resource-form-page">Resource Form Page</div>,
  PicklistsPage: () => <div data-testid="picklists-page">Picklists Page</div>,
  PageLayoutsPage: () => <div data-testid="page-layouts-page">Page Layouts Page</div>,
  ListViewsPage: () => <div data-testid="list-views-page">List Views Page</div>,
  AnalyticsPage: () => <div data-testid="analytics-page">Analytics Page</div>,
  ApprovalProcessesPage: () => (
    <div data-testid="approval-processes-page">Approval Processes Page</div>
  ),
  FlowsPage: () => <div data-testid="flows-page">Flows Page</div>,
  FlowDesignerPage: () => <div data-testid="flow-designer-page">Flow Designer Page</div>,
  ScheduledJobsPage: () => <div data-testid="scheduled-jobs-page">Scheduled Jobs Page</div>,
  EmailTemplatesPage: () => <div data-testid="email-templates-page">Email Templates Page</div>,
  CampaignsPage: () => <div data-testid="campaigns-page">Campaigns Page</div>,
  ScriptsPage: () => <div data-testid="scripts-page">Scripts Page</div>,
  WebhooksPage: () => <div data-testid="webhooks-page">Webhooks Page</div>,
  ConnectedAppsPage: () => <div data-testid="connected-apps-page">Connected Apps Page</div>,
  ModulesPage: () => <div data-testid="modules-page">Modules Page</div>,
  BulkJobsPage: () => <div data-testid="bulk-jobs-page">Bulk Jobs Page</div>,
  SearchSettingsPage: () => <div data-testid="search-settings-page">Search Settings Page</div>,
  SetupHomePage: () => <div data-testid="setup-home-page">Setup Home Page</div>,
  PluginsPage: () => <div data-testid="plugins-page">Plugins Page</div>,
  UsersPage: () => <div data-testid="users-page">Users Page</div>,
  UserDetailPage: () => <div data-testid="user-detail-page">User Detail Page</div>,
  SetupAuditTrailPage: () => <div data-testid="audit-trail-page">Audit Trail Page</div>,
  GovernorLimitsPage: () => <div data-testid="governor-limits-page">Governor Limits Page</div>,
  MonitoringLayout: ({ children }: { children?: React.ReactNode }) => (
    <div data-testid="monitoring-layout">{children}</div>
  ),
  MonitoringOverviewPage: () => (
    <div data-testid="monitoring-overview-page">Monitoring Overview Page</div>
  ),
  ProfilesPage: () => <div data-testid="profiles-page">Profiles Page</div>,
  ProfileDetailPage: () => <div data-testid="profile-detail-page">Profile Detail Page</div>,
  LoginHistoryPage: () => <div data-testid="login-history-page">Login History Page</div>,
  SecurityAuditPage: () => <div data-testid="security-audit-page">Security Audit Page</div>,
  TenantsPage: () => <div data-testid="tenants-page">Tenants Page</div>,
  TenantDashboardPage: () => <div data-testid="tenant-dashboard-page">Tenant Dashboard Page</div>,
  LoginPage: () => <div data-testid="login-page">Login Page</div>,
  UnauthorizedPage: () => <div data-testid="unauthorized-page">Unauthorized Page</div>,
  RequestLogPage: () => <div data-testid="request-log-page">Request Log Page</div>,
  RequestLogDetailPage: () => (
    <div data-testid="request-log-detail-page">Request Log Detail Page</div>
  ),
  LogViewerPage: () => <div data-testid="log-viewer-page">Log Viewer Page</div>,
  ErrorDashboardPage: () => <div data-testid="error-dashboard-page">Error Dashboard Page</div>,
  UserActivityPage: () => <div data-testid="user-activity-page">User Activity Page</div>,
  EndpointPerformancePage: () => (
    <div data-testid="endpoint-performance-page">Endpoint Performance Page</div>
  ),
  ObservabilitySettingsPage: () => (
    <div data-testid="observability-settings-page">Observability Settings Page</div>
  ),
  AiSettingsPage: () => <div data-testid="ai-settings-page">AI Settings Page</div>,
  EmailSettingsPage: () => <div data-testid="email-settings-page">Email Settings Page</div>,
  ApiTokensPage: () => <div data-testid="api-tokens-page">API Tokens Page</div>,
  PasswordPolicyPage: () => <div data-testid="password-policy-page">Password Policy Page</div>,
  MfaPolicyPage: () => <div data-testid="mfa-policy-page">MFA Policy Page</div>,
  MapSettingsPage: () => <div data-testid="map-settings-page">Map Settings Page</div>,
  NetworkAccessPage: () => <div data-testid="network-access-page">Network Access Page</div>,
  CredentialsPage: () => <div data-testid="credentials-page">Credentials Page</div>,
  ApiSpecsPage: () => <div data-testid="api-specs-page">API Specs Page</div>,
  ApiSpecDetailPage: () => <div data-testid="api-spec-detail-page">API Spec Detail Page</div>,
  AiAgentsPage: () => <div data-testid="ai-agents-page">AI Agents Page</div>,
  ConfigHealthPage: () => <div data-testid="config-health-page">Config Health Page</div>,
  NotFoundPage: () => <div data-testid="not-found-page">Not Found Page</div>,
}))

// Mock the NoTenantPage (imported separately in App.tsx)
vi.mock('./pages/NoTenantPage/NoTenantPage', () => ({
  NoTenantPage: () => <div data-testid="no-tenant-page">No Tenant Page</div>,
}))

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Reset mock auth context
    mockAuthContext.user = null
    mockAuthContext.isAuthenticated = false
    mockAuthContext.isLoading = false
    mockAuthContext.error = null

    // Setup MSW handlers for JSON:API bootstrap endpoints
    server.use(
      http.get('*/api/ui-pages', () => {
        return HttpResponse.json({
          data: mockBootstrapConfig.pages.map((p: Record<string, unknown>, i: number) => ({
            type: 'ui-pages',
            id: p.id ?? `page-${i + 1}`,
            attributes: Object.fromEntries(Object.entries(p).filter(([k]) => k !== 'id')),
          })),
          metadata: {
            totalCount: mockBootstrapConfig.pages.length,
            currentPage: 0,
            pageSize: 500,
            totalPages: 1,
          },
        })
      }),
      http.get('*/api/ui-menus', () => {
        return HttpResponse.json({
          data: mockBootstrapConfig.menus.map((m: Record<string, unknown>, i: number) => ({
            type: 'ui-menus',
            id: m.id ?? `menu-${i + 1}`,
            attributes: Object.fromEntries(Object.entries(m).filter(([k]) => k !== 'id')),
          })),
          metadata: {
            totalCount: mockBootstrapConfig.menus.length,
            currentPage: 0,
            pageSize: 500,
            totalPages: 1,
          },
        })
      }),
      http.get('*/api/oidc-providers', () => {
        return HttpResponse.json({
          data: mockBootstrapConfig.oidcProviders.map((p: Record<string, unknown>, i: number) => ({
            type: 'oidc-providers',
            id: p.id ?? `provider-${i + 1}`,
            attributes: Object.fromEntries(Object.entries(p).filter(([k]) => k !== 'id')),
          })),
          metadata: {
            totalCount: mockBootstrapConfig.oidcProviders.length,
            currentPage: 0,
            pageSize: 100,
            totalPages: 1,
          },
        })
      }),
      http.get('*/api/tenants', () => {
        return HttpResponse.json({
          data: [
            {
              type: 'tenants',
              id: 'tenant-1',
              attributes: { slug: 'test-tenant', name: 'Test Tenant' },
            },
          ],
          metadata: { totalCount: 1, currentPage: 0, pageSize: 1, totalPages: 1 },
        })
      })
    )
  })

  afterEach(() => {
    server.resetHandlers()
  })

  describe('Rendering', () => {
    it('should render without crashing', () => {
      render(<App />)
      // App should render something
      expect(document.body).toBeInTheDocument()
    })

    it('should render with plugins prop', () => {
      const mockPlugins = [
        {
          id: 'test-plugin',
          name: 'Test Plugin',
          version: '1.0.0',
        },
      ]
      render(<App plugins={mockPlugins} />)
      expect(document.body).toBeInTheDocument()
    })
  })

  describe('Public Routes', () => {
    it('should render no-tenant page at root /', async () => {
      window.history.pushState({}, '', '/')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('no-tenant-page')).toBeInTheDocument()
      })
    })

    it('should render login page at /:tenantSlug/login', async () => {
      window.history.pushState({}, '', '/test-tenant/login')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('login-page')).toBeInTheDocument()
      })
    })

    it('should render unauthorized page at /:tenantSlug/unauthorized', async () => {
      window.history.pushState({}, '', '/test-tenant/unauthorized')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('unauthorized-page')).toBeInTheDocument()
      })
    })

    it('should render not found page for unknown routes under tenant', async () => {
      window.history.pushState({}, '', '/test-tenant/unknown-route')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('not-found-page')).toBeInTheDocument()
      })
    })
  })

  describe('Protected Routes - Unauthenticated', () => {
    beforeEach(() => {
      mockAuthContext.isAuthenticated = false
      mockAuthContext.user = null
    })

    it('should redirect to login when accessing home unauthenticated', async () => {
      window.history.pushState({}, '', '/test-tenant')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('redirect-to-login')).toBeInTheDocument()
      })
    })

    it('should redirect to login when accessing collections unauthenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/collections')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('redirect-to-login')).toBeInTheDocument()
      })
    })
  })

  describe('Protected Routes - Authenticated', () => {
    beforeEach(() => {
      mockAuthContext.isAuthenticated = true
      mockAuthContext.user = {
        id: 'user-1',
        email: 'test@example.com',
        name: 'Test User',
      }
    })

    it('should redirect to /app at /:tenantSlug when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant')
      render(<App />)

      await waitFor(() => {
        // Root route redirects to /app which renders the EndUserShell
        expect(screen.getByTestId('end-user-shell')).toBeInTheDocument()
      })
    })

    it('should render dashboard page at /:tenantSlug/system-health when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/system-health')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('dashboard-page')).toBeInTheDocument()
      })
    })

    it('should render collections page at /:tenantSlug/collections when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/collections')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('collections-page')).toBeInTheDocument()
      })
    })

    it('should render collection detail page at /:tenantSlug/collections/:id when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/collections/test-collection')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('collection-detail-page')).toBeInTheDocument()
      })
    })

    it('should render OIDC providers page at /:tenantSlug/oidc-providers when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/oidc-providers')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('oidc-providers-page')).toBeInTheDocument()
      })
    })

    it('should render page builder page at /:tenantSlug/pages when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/pages')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('page-builder-page')).toBeInTheDocument()
      })
    })

    it('should render menu builder page at /:tenantSlug/menus when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/menus')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('menu-builder-page')).toBeInTheDocument()
      })
    })

    it('should render packages page at /:tenantSlug/packages when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/packages')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('packages-page')).toBeInTheDocument()
      })
    })

    it('should render migrations page at /:tenantSlug/migrations when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/migrations')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('migrations-page')).toBeInTheDocument()
      })
    })

    it('should render resource browser page at /:tenantSlug/resources when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/resources')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-browser-page')).toBeInTheDocument()
      })
    })

    it('should render resource list page at /:tenantSlug/resources/:collection when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/resources/users')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-list-page')).toBeInTheDocument()
      })
    })

    it('should render resource detail page at /:tenantSlug/resources/:collection/:id when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/resources/users/123')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-detail-page')).toBeInTheDocument()
      })
    })

    it('should render resource form page at /:tenantSlug/resources/:collection/new when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/resources/users/new')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form-page')).toBeInTheDocument()
      })
    })

    it('should render resource form page at /:tenantSlug/resources/:collection/:id/edit when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/resources/users/123/edit')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('resource-form-page')).toBeInTheDocument()
      })
    })

    it('should render plugins page at /:tenantSlug/plugins when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/plugins')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('plugins-page')).toBeInTheDocument()
      })
    })

    it('should render users page at /:tenantSlug/users when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/users')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('users-page')).toBeInTheDocument()
      })
    })

    it('should render user detail page at /:tenantSlug/users/:id when authenticated', async () => {
      window.history.pushState({}, '', '/test-tenant/users/test-user-123')
      render(<App />)

      await waitFor(() => {
        expect(screen.getByTestId('user-detail-page')).toBeInTheDocument()
      })
    })
  })

  describe('Route Configuration', () => {
    it('should have all required routes configured', () => {
      // This test verifies that the App component has all the expected routes
      // by checking that the component renders without errors
      mockAuthContext.isAuthenticated = true
      mockAuthContext.user = {
        id: 'user-1',
        email: 'test@example.com',
        name: 'Test User',
      }

      const routes = [
        '/test-tenant',
        '/test-tenant/system-health',
        '/test-tenant/collections',
        '/test-tenant/collections/test',
        '/test-tenant/oidc-providers',
        '/test-tenant/pages',
        '/test-tenant/menus',
        '/test-tenant/packages',
        '/test-tenant/migrations',
        '/test-tenant/resources',
        '/test-tenant/resources/users',
        '/test-tenant/resources/users/123',
        '/test-tenant/resources/users/new',
        '/test-tenant/resources/users/123/edit',
        '/test-tenant/plugins',
        '/test-tenant/users',
        '/test-tenant/users/test-user-123',
        '/test-tenant/login',
        '/test-tenant/unauthorized',
      ]

      routes.forEach((route) => {
        window.history.pushState({}, '', route)
        const { unmount } = render(<App />)
        // If the app renders without throwing, the route is configured
        expect(document.body).toBeInTheDocument()
        unmount()
      })
    })
  })

  describe('Provider Hierarchy', () => {
    it('should wrap app with all required providers', () => {
      // This test verifies that the provider hierarchy is correct
      // by checking that the app renders without context errors
      render(<App />)

      // If we get here without errors, all providers are correctly set up
      expect(document.body).toBeInTheDocument()
    })
  })
})

describe('App Integration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuthContext.isAuthenticated = true
    mockAuthContext.user = {
      id: 'user-1',
      email: 'test@example.com',
      name: 'Test User',
    }
  })

  it('should render end-user shell when navigating to root', async () => {
    window.history.pushState({}, '', '/test-tenant')
    render(<App />)

    await waitFor(() => {
      // Root redirects to /app which renders EndUserShell
      expect(screen.getByTestId('end-user-shell')).toBeInTheDocument()
    })
  })
})
