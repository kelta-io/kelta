/**
 * Component Registry
 *
 * A client-side registry for dynamically registering and resolving
 * React components at runtime. Used by the plugin system to allow
 * plugins to contribute custom field renderers, quick actions,
 * page components, and column renderers.
 *
 * The registry is a singleton — components registered via plugins
 * are available globally throughout the application.
 *
 * Usage:
 * ```ts
 * // Plugin registration
 * componentRegistry.registerFieldRenderer('progress_bar', ProgressBarField)
 * componentRegistry.registerQuickAction('approve_order', ApproveOrderAction)
 * componentRegistry.registerPageComponent('dashboard', DashboardWidget)
 *
 * // Resolution
 * const Renderer = componentRegistry.getFieldRenderer('progress_bar')
 * if (Renderer) return <Renderer value={value} field={field} />
 * ```
 */

import type React from 'react'

/**
 * Props passed to a custom field renderer component.
 */
interface FieldRendererComponentProps {
  /** The field value */
  value: unknown
  /** Field name */
  fieldName: string
  /** Display name for the field */
  displayName: string
  /** The field type string */
  fieldType: string
  /** Whether to truncate long values */
  truncate?: boolean
  /** Tenant slug for building links */
  tenantSlug?: string
  /** Full record data (for computed fields) */
  record?: Record<string, unknown>
}

/**
 * Props passed to a custom page component.
 */
interface PageComponentProps {
  /** Page configuration props from the page definition */
  config?: Record<string, unknown>
  /** Tenant slug for navigation */
  tenantSlug: string
  /** Collection name (if page is collection-scoped) */
  collectionName?: string
}

/**
 * Props passed to a custom quick action component.
 */
export interface QuickActionComponentProps {
  /** Collection name */
  collectionName: string
  /** Record ID (for record-scoped actions) */
  recordId?: string
  /** Record data */
  record?: Record<string, unknown>
  /** Tenant slug for navigation */
  tenantSlug: string
  /** Callback when the action completes */
  onComplete?: () => void
  /** Custom action configuration */
  config?: Record<string, unknown>
}

/**
 * Props passed to a custom column renderer.
 */
interface ColumnRendererComponentProps {
  /** The cell value */
  value: unknown
  /** Column field name */
  fieldName: string
  /** Full row record data */
  record: Record<string, unknown>
  /** Collection name */
  collectionName: string
}

type FieldRendererComponent = React.ComponentType<FieldRendererComponentProps>
type PageComponent = React.ComponentType<PageComponentProps>
type QuickActionComponent = React.ComponentType<QuickActionComponentProps>
type ColumnRendererComponent = React.ComponentType<ColumnRendererComponentProps>

/**
 * Registry for dynamically registered React components.
 */
class ComponentRegistry {
  private fieldRenderers = new Map<string, FieldRendererComponent>()
  private pageComponents = new Map<string, PageComponent>()
  private quickActions = new Map<string, QuickActionComponent>()
  private columnRenderers = new Map<string, ColumnRendererComponent>()

  // --- Field Renderers ---

  /**
   * Register a custom field renderer for a field type.
   * Overrides the built-in renderer if one exists.
   */
  registerFieldRenderer(fieldType: string, component: FieldRendererComponent): void {
    this.fieldRenderers.set(fieldType, component)
  }

  /**
   * Get a registered field renderer by type.
   * Returns undefined if no custom renderer is registered.
   */
  getFieldRenderer(fieldType: string): FieldRendererComponent | undefined {
    return this.fieldRenderers.get(fieldType)
  }

  /**
   * Check if a custom field renderer is registered for a type.
   */
  hasFieldRenderer(fieldType: string): boolean {
    return this.fieldRenderers.has(fieldType)
  }

  /**
   * List registered field renderer types, sorted alphabetically.
   */
  listFieldRenderers(): string[] {
    return Array.from(this.fieldRenderers.keys()).sort()
  }

  // --- Page Components ---

  /**
   * Register a custom page component by name.
   */
  registerPageComponent(name: string, component: PageComponent): void {
    this.pageComponents.set(name, component)
  }

  /**
   * Get a registered page component by name.
   */
  getPageComponent(name: string): PageComponent | undefined {
    return this.pageComponents.get(name)
  }

  /**
   * Check if a custom page component is registered.
   */
  hasPageComponent(name: string): boolean {
    return this.pageComponents.has(name)
  }

  /**
   * List registered page component names, sorted alphabetically.
   */
  listPageComponents(): string[] {
    return Array.from(this.pageComponents.keys()).sort()
  }

  // --- Quick Actions ---

  /**
   * Register a custom quick action component by name.
   */
  registerQuickAction(name: string, component: QuickActionComponent): void {
    this.quickActions.set(name, component)
  }

  /**
   * Get a registered quick action component by name.
   */
  getQuickAction(name: string): QuickActionComponent | undefined {
    return this.quickActions.get(name)
  }

  /**
   * Check if a custom quick action is registered.
   */
  hasQuickAction(name: string): boolean {
    return this.quickActions.has(name)
  }

  /**
   * List registered quick action names, sorted alphabetically.
   */
  listQuickActions(): string[] {
    return Array.from(this.quickActions.keys()).sort()
  }

  // --- Column Renderers ---

  /**
   * Register a custom column renderer by name.
   */
  registerColumnRenderer(name: string, component: ColumnRendererComponent): void {
    this.columnRenderers.set(name, component)
  }

  /**
   * Get a registered column renderer by name.
   */
  getColumnRenderer(name: string): ColumnRendererComponent | undefined {
    return this.columnRenderers.get(name)
  }

  /**
   * Check if a custom column renderer is registered.
   */
  hasColumnRenderer(name: string): boolean {
    return this.columnRenderers.has(name)
  }

  /**
   * List registered column renderer names, sorted alphabetically.
   */
  listColumnRenderers(): string[] {
    return Array.from(this.columnRenderers.keys()).sort()
  }

  // --- Utilities ---

  /**
   * Get counts of all registered components.
   */
  getStats(): {
    fieldRenderers: number
    pageComponents: number
    quickActions: number
    columnRenderers: number
  } {
    return {
      fieldRenderers: this.fieldRenderers.size,
      pageComponents: this.pageComponents.size,
      quickActions: this.quickActions.size,
      columnRenderers: this.columnRenderers.size,
    }
  }

  /**
   * Clear all registrations. Useful for testing.
   */
  clear(): void {
    this.fieldRenderers.clear()
    this.pageComponents.clear()
    this.quickActions.clear()
    this.columnRenderers.clear()
  }
}

/**
 * Global singleton component registry.
 */
export const componentRegistry = new ComponentRegistry()
