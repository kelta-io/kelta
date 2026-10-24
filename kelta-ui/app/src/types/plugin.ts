/**
 * Plugin Types
 *
 * Types related to the plugin system for extending the Kelta Admin/Builder UI.
 * Plugins can register custom field renderers and page components.
 */

import type { ComponentType } from 'react'

/**
 * Props passed to custom field renderers
 */
export interface FieldRendererProps {
  /** The field name */
  name: string
  /** The field value */
  value: unknown
  /** Callback to update the field value */
  onChange: (value: unknown) => void
  /** Whether the field is disabled */
  disabled?: boolean
  /** Whether the field is read-only */
  readOnly?: boolean
  /** Validation error message */
  error?: string
  /** Additional field metadata */
  metadata?: Record<string, unknown>
}

/**
 * Props passed to custom page components
 */
export interface PageComponentProps {
  /** Component configuration from page builder */
  config?: Record<string, unknown>
  /** Additional props passed from parent */
  [key: string]: unknown
}

/**
 * Plugin context provided to plugins during initialization
 */
export interface PluginInitContext {
  /** Register a custom field renderer */
  registerFieldRenderer: (type: string, renderer: ComponentType<FieldRendererProps>) => void
  /** Register a custom page component */
  registerPageComponent: (name: string, component: ComponentType<PageComponentProps>) => void
  /** Get the current locale */
  getLocale: () => string
  /** Get the current theme mode */
  getThemeMode: () => 'light' | 'dark'
}

/**
 * Plugin interface that plugins must implement
 */
export interface Plugin {
  /** Unique plugin identifier */
  id: string
  /** Human-readable plugin name */
  name: string
  /** Plugin version (semver) */
  version: string
  /** Called when the plugin is loaded and initialized */
  onLoad?: (context: PluginInitContext) => Promise<void>
  /** Called when the plugin is being unloaded */
  onUnload?: () => Promise<void>
  /** Map of field type to renderer component */
  fieldRenderers?: Record<string, ComponentType<FieldRendererProps>>
  /** Map of component name to page component */
  pageComponents?: Record<string, ComponentType<PageComponentProps>>
}

/**
 * Plugin load status
 */
export type PluginStatus = 'pending' | 'loading' | 'loaded' | 'error'

/**
 * Information about a loaded plugin
 */
export interface LoadedPlugin {
  /** The plugin instance */
  plugin: Plugin
  /** Current load status */
  status: PluginStatus
  /** Error message if loading failed */
  error?: string
}
