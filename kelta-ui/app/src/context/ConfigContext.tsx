/**
 * Configuration Context
 *
 * Provides bootstrap configuration state and management for the application.
 * Composes configuration from parallel JSON:API calls to /api/ui-pages,
 * /api/ui-menus, and /api/oidc-providers (via bootstrapCache.ts).
 *
 * Requirements:
 * - 1.1: Fetch bootstrap configuration on startup (composed from JSON:API)
 * - 1.2: Configure application routes based on page definitions
 * - 1.3: Configure navigation menus based on menu definitions
 * - 1.4: Apply theme settings including colors, fonts, and spacing
 * - 1.5: Apply branding including logo, application name, and favicon
 * - 1.6: Display error page with retry option if bootstrap endpoint unavailable
 * - 1.7: Display error page with diagnostic information if config invalid
 * - 1.8: Offer to reload when config changes detected
 */

import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
  useRef,
} from 'react'
import type { BootstrapConfig } from '../types/config'
import { fetchBootstrapConfig, clearBootstrapCache } from '../utils/bootstrapCache'

/**
 * Configuration context value interface
 */
export interface ConfigContextValue {
  /** The bootstrap configuration, null if not loaded */
  config: BootstrapConfig | null
  /** Whether the configuration is currently being loaded */
  isLoading: boolean
  /** Error that occurred during configuration fetch, null if no error */
  error: Error | null
  /** Reload the configuration from the server */
  reload: () => Promise<void>
}

/**
 * Props for the ConfigProvider component
 */
export interface ConfigProviderProps {
  /** Child components to render */
  children: React.ReactNode
  /** Optional interval in ms to check for config changes (defaults to 0 = disabled) */
  pollInterval?: number
}

/**
 * Validate that the bootstrap config has required fields
 */
function validateBootstrapConfig(config: unknown): config is BootstrapConfig {
  if (!config || typeof config !== 'object') {
    return false
  }

  const c = config as Record<string, unknown>

  // Check required arrays exist
  if (!Array.isArray(c.pages)) {
    return false
  }
  if (!Array.isArray(c.menus)) {
    return false
  }

  // Check required objects exist
  if (!c.theme || typeof c.theme !== 'object') {
    return false
  }
  if (!c.branding || typeof c.branding !== 'object') {
    return false
  }
  // Validate theme has required fields
  const theme = c.theme as Record<string, unknown>
  if (typeof theme.primaryColor !== 'string') {
    return false
  }
  if (typeof theme.secondaryColor !== 'string') {
    return false
  }
  if (typeof theme.fontFamily !== 'string') {
    return false
  }
  if (typeof theme.borderRadius !== 'string') {
    return false
  }

  // Validate branding has required fields
  const branding = c.branding as Record<string, unknown>
  if (typeof branding.logoUrl !== 'string') {
    return false
  }
  if (typeof branding.applicationName !== 'string') {
    return false
  }
  if (typeof branding.faviconUrl !== 'string') {
    return false
  }

  return true
}

/**
 * Create a configuration validation error with diagnostic information
 */
function createValidationError(config: unknown): Error {
  const diagnostics: string[] = []

  if (!config || typeof config !== 'object') {
    diagnostics.push('Configuration is not an object')
  } else {
    const c = config as Record<string, unknown>

    if (!Array.isArray(c.pages)) {
      diagnostics.push('Missing or invalid "pages" array')
    }
    if (!Array.isArray(c.menus)) {
      diagnostics.push('Missing or invalid "menus" array')
    }
    if (!c.theme || typeof c.theme !== 'object') {
      diagnostics.push('Missing or invalid "theme" object')
    }
    if (!c.branding || typeof c.branding !== 'object') {
      diagnostics.push('Missing or invalid "branding" object')
    }
  }

  const error = new Error(`Invalid bootstrap configuration: ${diagnostics.join(', ')}`)
  error.name = 'ConfigValidationError'
  return error
}

// Create the context with undefined default
const ConfigContext = createContext<ConfigContextValue | undefined>(undefined)

/**
 * Configuration Provider Component
 *
 * Wraps the application to provide bootstrap configuration state and methods.
 * Fetches configuration from the bootstrap endpoint on mount.
 *
 * @example
 * ```tsx
 * <ConfigProvider>
 *   <App />
 * </ConfigProvider>
 * ```
 */
export function ConfigProvider({
  children,
  pollInterval = 0,
}: ConfigProviderProps): React.ReactElement {
  const [config, setConfig] = useState<BootstrapConfig | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)

  // Track the last config hash for change detection
  const lastConfigHashRef = useRef<string | null>(null)
  // Track if initial load has been attempted
  const initialLoadAttemptedRef = useRef(false)

  /**
   * Compute a simple hash of the config for change detection
   */
  const computeConfigHash = useCallback((cfg: BootstrapConfig): string => {
    return JSON.stringify(cfg)
  }, [])

  /**
   * Fetch bootstrap configuration from the server
   * Uses shared cache to avoid duplicate requests (AuthContext also reads bootstrap).
   * Requirements: 1.1, 1.6, 1.7
   */
  const fetchConfig = useCallback(async (): Promise<BootstrapConfig> => {
    let data: unknown
    try {
      data = await fetchBootstrapConfig()
    } catch (err) {
      const error =
        err instanceof Error
          ? err
          : new Error('Failed to fetch bootstrap configuration: Unknown error')
      error.name = 'ConfigFetchError'
      throw error
    }

    // Requirement 1.7: Validate configuration
    if (!validateBootstrapConfig(data)) {
      throw createValidationError(data)
    }

    return data
  }, [])

  /**
   * Load or reload the configuration
   * Requirements: 1.1, 1.6, 1.7, 1.8
   */
  const loadConfig = useCallback(
    async (isReload: boolean = false): Promise<void> => {
      if (!isReload) {
        setIsLoading(true)
      }
      setError(null)

      try {
        const newConfig = await fetchConfig()
        const newHash = computeConfigHash(newConfig)

        // Requirement 1.8: Detect config changes
        if (isReload && lastConfigHashRef.current !== null) {
          if (lastConfigHashRef.current !== newHash) {
            console.info('[Config] Configuration has changed')
          }
        }

        lastConfigHashRef.current = newHash
        setConfig(newConfig)
      } catch (err) {
        const configError =
          err instanceof Error ? err : new Error('Unknown error loading configuration')
        setError(configError)
        console.error('[Config] Failed to load configuration:', configError)
      } finally {
        setIsLoading(false)
      }
    },
    [fetchConfig, computeConfigHash]
  )

  /**
   * Reload the configuration from the server
   * Requirement 1.8: Offer to reload when config changes detected
   */
  const reload = useCallback(async (): Promise<void> => {
    clearBootstrapCache()
    await loadConfig(true)
  }, [loadConfig])

  /**
   * Initialize configuration on mount
   * Requirement 1.1: Fetch bootstrap configuration on startup
   */
  useEffect(() => {
    // Only attempt initial load once to prevent infinite loops on failure
    if (initialLoadAttemptedRef.current) {
      return
    }
    initialLoadAttemptedRef.current = true
    loadConfig(false)
  }, [loadConfig])

  /**
   * Set up polling for config changes if enabled
   * Requirement 1.8: Detect config changes
   */
  useEffect(() => {
    if (pollInterval <= 0) {
      return
    }

    const intervalId = setInterval(() => {
      // Only poll if we have a valid config (don't poll during error state)
      if (config && !error) {
        reload().catch((err) => {
          console.warn('[Config] Polling failed:', err)
        })
      }
    }, pollInterval)

    return () => {
      clearInterval(intervalId)
    }
  }, [pollInterval, config, error, reload])

  // Memoize context value to prevent unnecessary re-renders
  const contextValue = useMemo<ConfigContextValue>(
    () => ({
      config,
      isLoading,
      error,
      reload,
    }),
    [config, isLoading, error, reload]
  )

  return <ConfigContext.Provider value={contextValue}>{children}</ConfigContext.Provider>
}

/**
 * Hook to access configuration context
 *
 * @throws Error if used outside of ConfigProvider
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { config, isLoading, error, reload } = useConfig();
 *
 *   if (isLoading) return <LoadingSpinner />;
 *   if (error) return <ErrorMessage error={error} onRetry={reload} />;
 *
 *   return <div>{config?.branding.applicationName}</div>;
 * }
 * ```
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useConfig(): ConfigContextValue {
  const context = useContext(ConfigContext)
  if (context === undefined) {
    throw new Error('useConfig must be used within a ConfigProvider')
  }
  return context
}
