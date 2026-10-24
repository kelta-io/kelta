/**
 * Configuration Types
 *
 * Types related to bootstrap configuration, pages, menus, themes, and branding.
 */

/**
 * Bootstrap configuration returned from the JSON:API bootstrap endpoint
 */
export interface BootstrapConfig {
  pages: PageConfig[]
  menus: MenuConfig[]
  theme: ThemeConfig
  branding: BrandingConfig
  oidcProviders: OIDCProviderSummary[]
  /**
   * Tenant-authored translation overlay (app-intelligence slice 4), keyed
   * locale → flat dotted key → value. Merged over the static bundles by
   * I18nContext (tenant wins); absent/empty when the tenant has none.
   */
  translations?: Record<string, Record<string, string>>
}

/**
 * Page configuration for dynamic routing
 */
interface PageConfig {
  id: string
  path: string
  title: string
  component: string
  props?: Record<string, unknown>
  policies?: string[]
}

/**
 * Menu configuration for navigation
 */
export interface MenuConfig {
  id: string
  name: string
  items: MenuItemConfig[]
  /** Lucide icon name shown in the app switcher (apps/nav v2). */
  icon?: string
  /** The app selected when the user has no stored preference (apps/nav v2). */
  isDefault?: boolean
  /** Inactive apps are hidden from the end-user shell (apps/nav v2). Absent = active. */
  active?: boolean
  displayOrder?: number
}

/**
 * Menu item configuration
 */
export interface MenuItemConfig {
  id: string
  label: string
  path?: string
  icon?: string
  children?: MenuItemConfig[]
  policies?: string[]
}

/**
 * Theme configuration
 */
export interface ThemeConfig {
  primaryColor: string
  secondaryColor: string
  fontFamily: string
  borderRadius: string
}

/**
 * Branding configuration
 */
export interface BrandingConfig {
  logoUrl: string
  applicationName: string
  faviconUrl: string
}

/**
 * OIDC provider summary for login page
 */
export interface OIDCProviderSummary {
  id: string
  name: string
  issuer: string
  clientId: string
  isInternal?: boolean
}
