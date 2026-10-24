/**
 * React Context Providers
 *
 * This module exports all React context providers used for global state management.
 * Contexts include: AuthContext, ConfigContext, ThemeContext, I18nContext, PluginContext, etc.
 */

// Authentication context
export { AuthProvider, useAuth, AuthContext } from './AuthContext'

// API context
export { ApiProvider, useApi } from './ApiContext'

// Collection store context
export { CollectionStoreProvider, useCollectionStore } from './CollectionStoreContext'
export type { CollectionStoreValue, StoredField } from './CollectionStoreContext'

// Configuration context
export { ConfigProvider, useConfig, ConfigContext } from './ConfigContext'
export type { ConfigContextValue, ConfigProviderProps } from './ConfigContext'

// Theme context
export { ThemeProvider, useTheme, ThemeContext } from './ThemeContext'
export type {
  ThemeMode,
  ResolvedTheme,
  ThemeColors,
  ThemeContextValue,
  ThemeProviderProps,
} from './ThemeContext'

// I18n context
export { I18nProvider, useI18n, I18nContext } from './I18nContext'
export type {
  TextDirection,
  SupportedLocale,
  TranslationDictionary,
  I18nContextValue,
  I18nProviderProps,
} from './I18nContext'

// Plugin context
export { PluginProvider, usePlugins, PluginContext } from './PluginContext'
export type { PluginContextValue, PluginProviderProps } from './PluginContext'
