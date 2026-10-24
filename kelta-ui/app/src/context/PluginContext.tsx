/**
 * Plugin Context
 *
 * Provides plugin system state and management for the application.
 * Loads and initializes configured plugins, manages the ComponentRegistry
 * for custom field renderers and page components, and handles plugin lifecycle.
 *
 * Syncs registrations from the @kelta/plugin-sdk ComponentRegistry into the
 * host application so that plugins built with BasePlugin/ComponentRegistry
 * are automatically discovered and rendered.
 *
 * Requirements:
 * - 12.1: Plugin system loads configured plugins on startup
 * - 12.2: Plugin system supports custom field renderers via ComponentRegistry
 * - 12.3: Plugin system supports custom page components via ComponentRegistry
 * - 12.7: Plugin system handles plugin load failures gracefully
 * - 12.8: Plugin system calls plugin lifecycle hooks (onLoad, onUnload)
 * - 12.10: Sync SDK ComponentRegistry into host rendering pipeline
 */

import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
  useRef,
  type ComponentType,
} from 'react'
import { ComponentRegistry } from '@kelta/plugin-sdk'
import type {
  Plugin,
  PluginInitContext,
  LoadedPlugin,
  PluginStatus,
  FieldRendererProps,
  PageComponentProps,
} from '../types/plugin'
import { componentRegistry } from '../services/componentRegistry'
import { loadModuleUiBundles } from '../services/moduleUiBundles'
import { useOptionalApi } from './ApiContext'
import { useOptionalAuth } from './AuthContext'

/**
 * Plugin context value interface
 */
export interface PluginContextValue {
  /** Map of field type to renderer component */
  fieldRenderers: Map<string, ComponentType<FieldRendererProps>>
  /** Map of component name to page component */
  pageComponents: Map<string, ComponentType<PageComponentProps>>
  /** Register a custom field renderer */
  registerFieldRenderer: (type: string, renderer: ComponentType<FieldRendererProps>) => void
  /** Register a custom page component */
  registerPageComponent: (name: string, component: ComponentType<PageComponentProps>) => void
  /** Get a field renderer by type */
  getFieldRenderer: (type: string) => ComponentType<FieldRendererProps> | undefined
  /** Get a page component by name */
  getPageComponent: (name: string) => ComponentType<PageComponentProps> | undefined
  /** List of loaded plugins with their status */
  plugins: LoadedPlugin[]
  /** Whether plugins are currently being loaded */
  isLoading: boolean
  /** Errors that occurred during plugin loading */
  errors: Array<{ pluginId: string; error: string }>
}

/**
 * Props for the PluginProvider component
 */
export interface PluginProviderProps {
  /** Child components to render */
  children: React.ReactNode
  /** Array of plugins to load */
  plugins?: Plugin[]
  /** Optional function to get current locale (for plugin context) */
  getLocale?: () => string
  /** Optional function to get current theme mode (for plugin context) */
  getThemeMode?: () => 'light' | 'dark'
  /**
   * Fetch and evaluate the UI bundles of the tenant's runtime-installed modules on mount.
   *
   * Off by default, and enabled only by the real app shell (App.tsx). Turning it on adds a
   * `GET /api/modules` on every mount, which every test rendering through a shared wrapper
   * would otherwise have to account for — a page test that stubs responses positionally
   * (`mockResolvedValueOnce`) would silently hand the provider's request the page's own data.
   */
  loadModuleBundles?: boolean
}

// Create the context with undefined default
const PluginContext = createContext<PluginContextValue | undefined>(undefined)

/**
 * Plugin Provider Component
 *
 * Wraps the application to provide plugin system state and methods.
 * Loads and initializes plugins on mount, managing their lifecycle.
 * Also syncs registrations from the SDK's static ComponentRegistry
 * so that plugins using BasePlugin/ComponentRegistry are discovered.
 *
 * @example
 * ```tsx
 * const plugins = [myCustomPlugin, anotherPlugin];
 *
 * <PluginProvider plugins={plugins}>
 *   <App />
 * </PluginProvider>
 * ```
 */
export function PluginProvider({
  children,
  plugins = [],
  getLocale = () => 'en',
  getThemeMode = () => 'light',
  loadModuleBundles = false,
}: PluginProviderProps): React.ReactElement {
  // Authenticated client used to fetch runtime-module UI bundles. Optional on purpose: the
  // provider's core job — registering statically-passed plugins — needs no API, so a
  // PluginProvider mounted without an ApiProvider must still work. In the app ApiProvider
  // wraps it (App.tsx), so module bundles do load.
  const apiClient = useOptionalApi()?.apiClient
  // Module bundles are fetched from an AUTHENTICATED endpoint, so they must wait for a session.
  // Requesting /api/modules while signed out returns 401, and the client's 401 handling sends the
  // app back to /login — where this provider mounts again and repeats it. That was a login loop
  // on every tenant.
  const isAuthenticated = useOptionalAuth()?.isAuthenticated ?? false

  // Component registry state
  const [fieldRenderers, setFieldRenderers] = useState<
    Map<string, ComponentType<FieldRendererProps>>
  >(new Map())
  const [pageComponents, setPageComponents] = useState<
    Map<string, ComponentType<PageComponentProps>>
  >(new Map())

  // Plugin loading state
  const [loadedPlugins, setLoadedPlugins] = useState<LoadedPlugin[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [errors, setErrors] = useState<Array<{ pluginId: string; error: string }>>([])

  // Track if we've already initialized to prevent double loading
  const initializedRef = useRef(false)
  // Track loaded plugin IDs for cleanup
  const loadedPluginIdsRef = useRef<Set<string>>(new Set())

  /**
   * Register a custom field renderer.
   * Also syncs to the componentRegistry singleton so CustomPage / other
   * consumers that read from the singleton stay in sync.
   * Requirement 12.2: Support custom field renderers via ComponentRegistry
   */
  const registerFieldRenderer = useCallback(
    (type: string, renderer: ComponentType<FieldRendererProps>) => {
      setFieldRenderers((prev) => {
        const next = new Map(prev)
        next.set(type, renderer)
        return next
      })
      // Sync to singleton componentRegistry for consumers like CustomPage
      componentRegistry.registerFieldRenderer(
        type,
        renderer as unknown as Parameters<typeof componentRegistry.registerFieldRenderer>[1]
      )
      console.info(`[Plugin] Registered field renderer for type: ${type}`)
    },
    []
  )

  /**
   * Register a custom page component.
   * Also syncs to the componentRegistry singleton.
   * Requirement 12.3: Support custom page components via ComponentRegistry
   */
  const registerPageComponent = useCallback(
    (name: string, component: ComponentType<PageComponentProps>) => {
      setPageComponents((prev) => {
        const next = new Map(prev)
        next.set(name, component)
        return next
      })
      // Sync to singleton componentRegistry
      componentRegistry.registerPageComponent(
        name,
        component as unknown as Parameters<typeof componentRegistry.registerPageComponent>[1]
      )
      console.info(`[Plugin] Registered page component: ${name}`)
    },
    []
  )

  /**
   * Get a field renderer by type
   */
  const getFieldRenderer = useCallback(
    (type: string): ComponentType<FieldRendererProps> | undefined => {
      return fieldRenderers.get(type)
    },
    [fieldRenderers]
  )

  /**
   * Get a page component by name
   */
  const getPageComponent = useCallback(
    (name: string): ComponentType<PageComponentProps> | undefined => {
      return pageComponents.get(name)
    },
    [pageComponents]
  )

  /**
   * Sync registrations from the SDK's static ComponentRegistry into context state.
   * This discovers components registered by plugins that use the SDK's
   * ComponentRegistry.registerFieldRenderer / registerPageComponent directly.
   *
   * Requirement 12.10: Sync SDK ComponentRegistry into host rendering pipeline
   */
  const syncFromSdkRegistry = useCallback(() => {
    // Sync field renderers from SDK registry
    const sdkFieldTypes = ComponentRegistry.getFieldRendererTypes()
    for (const fieldType of sdkFieldTypes) {
      const sdkRenderer = ComponentRegistry.getFieldRenderer(fieldType)
      if (sdkRenderer) {
        // Wrap SDK renderer as a React ComponentType compatible with our FieldRendererProps
        const WrappedRenderer: ComponentType<FieldRendererProps> = (props: FieldRendererProps) => {
          // Bridge admin UI FieldRendererProps → SDK FieldRendererProps
          const sdkProps = {
            value: props.value,
            field: {
              name: props.name,
              type: 'string' as const,
              displayName: props.name,
              ...(props.metadata as Record<string, unknown>),
            },
            onChange: props.onChange,
            readOnly: props.readOnly ?? props.disabled,
          }
          return sdkRenderer(sdkProps)
        }
        WrappedRenderer.displayName = `SdkFieldRenderer(${fieldType})`
        registerFieldRenderer(fieldType, WrappedRenderer)
      }
    }

    // Sync page components from SDK registry
    const sdkPageComponents = ComponentRegistry.getAllPageComponents()
    for (const registered of sdkPageComponents) {
      // Wrap SDK page component to bridge props
      const WrappedPage: ComponentType<PageComponentProps> = (props: PageComponentProps) => {
        const sdkProps = {
          client: {} as never, // Plugins should use their own client from init context
          params: (props.config as Record<string, string>) ?? {},
          user: null,
        }
        return registered.component(sdkProps)
      }
      WrappedPage.displayName = `SdkPageComponent(${registered.name})`
      registerPageComponent(registered.name, WrappedPage)
    }
  }, [registerFieldRenderer, registerPageComponent])

  /**
   * Load a single plugin
   * Requirements: 12.1, 12.7, 12.8
   */
  const loadPlugin = useCallback(
    async (plugin: Plugin): Promise<LoadedPlugin> => {
      const loadedPlugin: LoadedPlugin = {
        plugin,
        status: 'loading' as PluginStatus,
      }

      try {
        console.info(`[Plugin] Loading plugin: ${plugin.id} (${plugin.name} v${plugin.version})`)

        // Register static field renderers if provided
        if (plugin.fieldRenderers) {
          for (const [type, renderer] of Object.entries(plugin.fieldRenderers)) {
            registerFieldRenderer(type, renderer)
          }
        }

        // Register static page components if provided
        if (plugin.pageComponents) {
          for (const [name, component] of Object.entries(plugin.pageComponents)) {
            registerPageComponent(name, component)
          }
        }

        // Call onLoad lifecycle hook if provided
        // Requirement 12.8: Call plugin lifecycle hooks
        if (plugin.onLoad) {
          const context: PluginInitContext = {
            registerFieldRenderer,
            registerPageComponent,
            getLocale,
            getThemeMode,
          }
          await plugin.onLoad(context)
        }

        loadedPlugin.status = 'loaded'
        loadedPluginIdsRef.current.add(plugin.id)
        console.info(`[Plugin] Successfully loaded plugin: ${plugin.id}`)
      } catch (err) {
        // Requirement 12.7: Handle plugin load failures gracefully
        const errorMessage = err instanceof Error ? err.message : 'Unknown error'
        loadedPlugin.status = 'error'
        loadedPlugin.error = errorMessage
        console.error(`[Plugin] Failed to load plugin ${plugin.id}:`, err)
      }

      return loadedPlugin
    },
    [registerFieldRenderer, registerPageComponent, getLocale, getThemeMode]
  )

  /**
   * Unload a plugin
   * Requirement 12.8: Call plugin lifecycle hooks (onUnload)
   */
  const unloadPlugin = useCallback(async (plugin: Plugin): Promise<void> => {
    try {
      if (plugin.onUnload) {
        console.info(`[Plugin] Unloading plugin: ${plugin.id}`)
        await plugin.onUnload()
        console.info(`[Plugin] Successfully unloaded plugin: ${plugin.id}`)
      }
    } catch (err) {
      console.error(`[Plugin] Error unloading plugin ${plugin.id}:`, err)
    }
  }, [])

  /**
   * Initialize all plugins
   * Requirement 12.1: Load configured plugins on startup
   * Requirement 12.7: Continue loading other plugins if one fails
   */
  const initializePlugins = useCallback(async () => {
    setIsLoading(true)
    setErrors([])

    const results: LoadedPlugin[] = []
    const loadErrors: Array<{ pluginId: string; error: string }> = []

    // Step 1: Sync any components already registered in the SDK's static ComponentRegistry.
    // External plugins may have called ComponentRegistry.registerFieldRenderer() before
    // the React app mounted.
    syncFromSdkRegistry()

    // Module bundles are NOT loaded here: this runs on mount, including on the login page where
    // there is no session. They load from their own auth-gated effect below.

    // Step 2: Load explicitly-passed plugins sequentially to maintain order
    if (plugins.length > 0) {
      for (const plugin of plugins) {
        const result = await loadPlugin(plugin)
        results.push(result)

        if (result.status === 'error' && result.error) {
          loadErrors.push({ pluginId: plugin.id, error: result.error })
        }
      }
    }

    // Step 3: After plugin onLoad hooks have run, sync again to pick up everything they
    // registered via the SDK's ComponentRegistry during init.
    syncFromSdkRegistry()

    setLoadedPlugins(results)
    setErrors(loadErrors)

    const successCount = results.filter((r) => r.status === 'loaded').length
    const failCount = results.filter((r) => r.status === 'error').length
    console.info(
      `[Plugin] Plugin initialization complete: ${successCount} loaded, ${failCount} failed`
    )
  }, [plugins, loadPlugin, syncFromSdkRegistry, apiClient, loadModuleBundles])

  /**
   * Initialize plugins on mount
   */
  useEffect(() => {
    // Prevent double initialization in React StrictMode
    if (initializedRef.current) {
      return
    }
    initializedRef.current = true

    // `finally`, not `.catch` alone: whatever happens during init, the provider must stop
    // reporting isLoading. A throw that skipped this left every consumer gated on plugin
    // loading stuck on a spinner forever, with no error surfaced — see the module-bundle
    // loader, whose first version threw here.
    initializePlugins()
      .catch((err) => {
        console.error('[Plugin] Plugin initialization failed:', err)
      })
      .finally(() => {
        setIsLoading(false)
      })

    // Cleanup: unload plugins on unmount and clear SDK registry
    return () => {
      const cleanup = async () => {
        for (const loadedPlugin of loadedPlugins) {
          if (loadedPlugin.status === 'loaded') {
            await unloadPlugin(loadedPlugin.plugin)
          }
        }
        ComponentRegistry.clearAll()
      }
      cleanup()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
   * Load runtime-module UI bundles once a session exists.
   *
   * Separate from plugin init on purpose. Init runs on mount — including on the login page, where
   * there is no session — and `/api/modules` is authenticated. Calling it signed out returns 401,
   * the client's 401 handling redirects to /login, this provider mounts again, and the app loops.
   *
   * Keying on isAuthenticated also means bundles load right after sign-in, not only for users who
   * arrive already authenticated.
   */
  const bundlesLoadedRef = useRef(false)
  useEffect(() => {
    if (!loadModuleBundles || !isAuthenticated || !apiClient || bundlesLoadedRef.current) {
      return
    }
    bundlesLoadedRef.current = true
    loadModuleUiBundles(apiClient)
      .then((loaded) => {
        if (loaded.length > 0) {
          // Their top-level code registers into the SDK registry; pull it into host state.
          syncFromSdkRegistry()
        }
      })
      .catch((err) => {
        // Module UI is an enhancement — never let it take the admin UI down.
        console.error('[Plugin] Module UI bundle loading failed:', err)
      })
  }, [loadModuleBundles, isAuthenticated, apiClient, syncFromSdkRegistry])

  // Memoize context value to prevent unnecessary re-renders
  const contextValue = useMemo<PluginContextValue>(
    () => ({
      fieldRenderers,
      pageComponents,
      registerFieldRenderer,
      registerPageComponent,
      getFieldRenderer,
      getPageComponent,
      plugins: loadedPlugins,
      isLoading,
      errors,
    }),
    [
      fieldRenderers,
      pageComponents,
      registerFieldRenderer,
      registerPageComponent,
      getFieldRenderer,
      getPageComponent,
      loadedPlugins,
      isLoading,
      errors,
    ]
  )

  return <PluginContext.Provider value={contextValue}>{children}</PluginContext.Provider>
}

/**
 * Hook to access plugin context
 *
 * @throws Error if used outside of PluginProvider
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { fieldRenderers, getFieldRenderer, isLoading } = usePlugins();
 *
 *   if (isLoading) return <LoadingSpinner />;
 *
 *   const CustomRenderer = getFieldRenderer('custom-type');
 *   if (CustomRenderer) {
 *     return <CustomRenderer name="field" value={value} onChange={onChange} />;
 *   }
 *
 *   return <DefaultRenderer />;
 * }
 * ```
 */
// eslint-disable-next-line react-refresh/only-export-components
export function usePlugins(): PluginContextValue {
  const context = useContext(PluginContext)
  if (context === undefined) {
    throw new Error('usePlugins must be used within a PluginProvider')
  }
  return context
}
