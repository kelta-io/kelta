/**
 * Theme Context
 *
 * Provides theme state and management for the application.
 * Supports light, dark, and system theme modes with CSS custom properties.
 *
 * Requirements:
 * - 16.1: Support light and dark color themes
 * - 16.2: Detect user's system theme preference
 * - 16.3: Allow users to manually select theme
 * - 16.4: Persist theme preference to localStorage
 * - 16.5: Update UI colors without page reload
 * - 16.6: Apply theme colors from bootstrap configuration
 * - 16.7: Maintain accessibility contrast requirements in both themes
 */

import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react'
import type { ThemeConfig } from '../types/config'

/**
 * Theme mode options
 */
export type ThemeMode = 'light' | 'dark' | 'system'

/**
 * Resolved theme (actual applied theme)
 */
type ResolvedTheme = 'light' | 'dark'

/**
 * Theme colors interface
 * Contains all color values for the current theme
 */
interface ThemeColors {
  // Primary colors
  primary: string
  primaryHover: string
  primaryActive: string

  // Secondary colors
  secondary: string
  secondaryHover: string
  secondaryActive: string

  // Background colors
  background: string
  backgroundSecondary: string
  backgroundTertiary: string

  // Surface colors (cards, modals, etc.)
  surface: string
  surfaceHover: string
  surfaceBorder: string

  // Text colors
  text: string
  textSecondary: string
  textMuted: string
  textInverse: string

  // Status colors
  success: string
  warning: string
  error: string
  info: string

  // Border colors
  border: string
  borderLight: string

  // Focus colors
  focus: string
}

/**
 * Theme context value interface
 */
export interface ThemeContextValue {
  /** Current theme mode setting (light/dark/system) */
  mode: ThemeMode
  /** Resolved theme based on mode and system preference */
  resolvedMode: ResolvedTheme
  /** Set the theme mode */
  setMode: (mode: ThemeMode) => void
  /** Current theme colors */
  colors: ThemeColors
}

/**
 * Props for the ThemeProvider component
 */
export interface ThemeProviderProps {
  /** Child components to render */
  children: React.ReactNode
  /** Optional initial theme mode (defaults to stored preference or 'system') */
  initialMode?: ThemeMode
  /** Optional theme configuration from bootstrap */
  themeConfig?: ThemeConfig
}

// Storage key for theme preference
const THEME_STORAGE_KEY = 'kelta_theme_mode'

// Media query for system dark mode preference
const DARK_MODE_MEDIA_QUERY = '(prefers-color-scheme: dark)'

/**
 * Default light theme colors
 * Designed to meet WCAG 2.1 AA contrast requirements (4.5:1 for normal text)
 */
const lightThemeColors: ThemeColors = {
  // Primary colors
  primary: '#0066cc',
  primaryHover: '#0052a3',
  primaryActive: '#004080',

  // Secondary colors
  secondary: '#6c757d',
  secondaryHover: '#5a6268',
  secondaryActive: '#495057',

  // Background colors
  background: '#ffffff',
  backgroundSecondary: '#f8f9fa',
  backgroundTertiary: '#e9ecef',

  // Surface colors
  surface: '#ffffff',
  surfaceHover: '#f8f9fa',
  surfaceBorder: '#dee2e6',

  // Text colors (contrast ratio >= 4.5:1 on white background)
  text: '#212529',
  textSecondary: '#495057',
  textMuted: '#6c757d',
  textInverse: '#ffffff',

  // Status colors
  success: '#198754',
  warning: '#ffc107',
  error: '#dc3545',
  info: '#0dcaf0',

  // Border colors
  border: '#dee2e6',
  borderLight: '#e9ecef',

  // Focus colors
  focus: '#0066cc',
}

/**
 * Default dark theme colors
 * Designed to meet WCAG 2.1 AA contrast requirements (4.5:1 for normal text)
 */
const darkThemeColors: ThemeColors = {
  // Primary colors
  primary: '#4da6ff',
  primaryHover: '#66b3ff',
  primaryActive: '#80c0ff',

  // Secondary colors
  secondary: '#adb5bd',
  secondaryHover: '#c4ccd4',
  secondaryActive: '#d3d9df',

  // Background colors
  background: '#121212',
  backgroundSecondary: '#1e1e1e',
  backgroundTertiary: '#2d2d2d',

  // Surface colors
  surface: '#1e1e1e',
  surfaceHover: '#2d2d2d',
  surfaceBorder: '#3d3d3d',

  // Text colors (contrast ratio >= 4.5:1 on dark background)
  text: '#f8f9fa',
  textSecondary: '#ced4da',
  textMuted: '#adb5bd',
  textInverse: '#212529',

  // Status colors (adjusted for dark background)
  success: '#4ade80',
  warning: '#fbbf24',
  error: '#f87171',
  info: '#38bdf8',

  // Border colors
  border: '#3d3d3d',
  borderLight: '#4d4d4d',

  // Focus colors
  focus: '#4da6ff',
}

/**
 * Get the system theme preference
 * Requirement 16.2: Detect user's system theme preference
 */
function getSystemTheme(): ResolvedTheme {
  if (typeof window === 'undefined') {
    return 'light'
  }
  return window.matchMedia(DARK_MODE_MEDIA_QUERY).matches ? 'dark' : 'light'
}

/**
 * Get stored theme preference from localStorage
 * Requirement 16.4: Persist theme preference
 */
function getStoredTheme(): ThemeMode | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      return stored
    }
    return null
  } catch {
    // localStorage may not be available
    return null
  }
}

/**
 * Store theme preference to localStorage
 * Requirement 16.4: Persist theme preference
 */
function storeTheme(mode: ThemeMode): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    localStorage.setItem(THEME_STORAGE_KEY, mode)
  } catch {
    // localStorage may not be available
    console.warn('[Theme] Failed to persist theme preference')
  }
}

/**
 * Resolve the actual theme based on mode and system preference
 */
function resolveTheme(mode: ThemeMode): ResolvedTheme {
  if (mode === 'system') {
    return getSystemTheme()
  }
  return mode
}

/**
 * Apply theme colors with optional bootstrap config overrides
 * Requirement 16.6: Apply theme colors from bootstrap configuration
 */
function getThemeColors(resolvedMode: ResolvedTheme, themeConfig?: ThemeConfig): ThemeColors {
  const baseColors = resolvedMode === 'dark' ? darkThemeColors : lightThemeColors

  if (!themeConfig) {
    return baseColors
  }

  // Apply bootstrap config overrides
  return {
    ...baseColors,
    primary: themeConfig.primaryColor || baseColors.primary,
    primaryHover: adjustColor(
      themeConfig.primaryColor || baseColors.primary,
      resolvedMode === 'dark' ? 10 : -10
    ),
    primaryActive: adjustColor(
      themeConfig.primaryColor || baseColors.primary,
      resolvedMode === 'dark' ? 20 : -20
    ),
    secondary: themeConfig.secondaryColor || baseColors.secondary,
    secondaryHover: adjustColor(
      themeConfig.secondaryColor || baseColors.secondary,
      resolvedMode === 'dark' ? 10 : -10
    ),
    secondaryActive: adjustColor(
      themeConfig.secondaryColor || baseColors.secondary,
      resolvedMode === 'dark' ? 20 : -20
    ),
  }
}

/**
 * Simple color adjustment function
 * Lightens or darkens a hex color by a percentage
 */
function adjustColor(hex: string, percent: number): string {
  // Remove # if present
  const cleanHex = hex.replace('#', '')

  // Parse RGB values
  const r = parseInt(cleanHex.substring(0, 2), 16)
  const g = parseInt(cleanHex.substring(2, 4), 16)
  const b = parseInt(cleanHex.substring(4, 6), 16)

  // Adjust each channel
  const adjust = (value: number): number => {
    const adjusted = value + Math.round((percent / 100) * 255)
    return Math.max(0, Math.min(255, adjusted))
  }

  const newR = adjust(r)
  const newG = adjust(g)
  const newB = adjust(b)

  // Convert back to hex
  const toHex = (n: number): string => n.toString(16).padStart(2, '0')
  return `#${toHex(newR)}${toHex(newG)}${toHex(newB)}`
}

/**
 * Apply CSS custom properties to document root
 * Requirement 16.5: Update UI colors without page reload
 */
function applyCSSCustomProperties(colors: ThemeColors, themeConfig?: ThemeConfig): void {
  if (typeof document === 'undefined') {
    return
  }

  const root = document.documentElement

  // Apply color custom properties
  root.style.setProperty('--color-primary', colors.primary)
  root.style.setProperty('--color-primary-hover', colors.primaryHover)
  root.style.setProperty('--color-primary-active', colors.primaryActive)

  root.style.setProperty('--color-secondary', colors.secondary)
  root.style.setProperty('--color-secondary-hover', colors.secondaryHover)
  root.style.setProperty('--color-secondary-active', colors.secondaryActive)

  root.style.setProperty('--color-background', colors.background)
  root.style.setProperty('--color-background-secondary', colors.backgroundSecondary)
  root.style.setProperty('--color-background-tertiary', colors.backgroundTertiary)

  root.style.setProperty('--color-surface', colors.surface)
  root.style.setProperty('--color-surface-hover', colors.surfaceHover)
  root.style.setProperty('--color-surface-border', colors.surfaceBorder)

  root.style.setProperty('--color-text', colors.text)
  root.style.setProperty('--color-text-secondary', colors.textSecondary)
  root.style.setProperty('--color-text-muted', colors.textMuted)
  root.style.setProperty('--color-text-inverse', colors.textInverse)

  root.style.setProperty('--color-success', colors.success)
  root.style.setProperty('--color-warning', colors.warning)
  root.style.setProperty('--color-error', colors.error)
  root.style.setProperty('--color-info', colors.info)

  root.style.setProperty('--color-border', colors.border)
  root.style.setProperty('--color-border-light', colors.borderLight)

  root.style.setProperty('--color-focus', colors.focus)

  // Apply additional theme config properties
  if (themeConfig) {
    root.style.setProperty('--font-family', themeConfig.fontFamily)
    root.style.setProperty('--border-radius', themeConfig.borderRadius)
  }
}

/**
 * Apply theme class to document for CSS selectors.
 *
 * Sets both legacy selectors (theme-light/theme-dark, data-theme) for existing
 * CSS Module pages and the .dark class for shadcn/Tailwind components.
 */
function applyThemeClass(resolvedMode: ResolvedTheme): void {
  if (typeof document === 'undefined') {
    return
  }

  const root = document.documentElement
  root.classList.remove('theme-light', 'theme-dark')
  root.classList.add(`theme-${resolvedMode}`)

  // Also set data attribute for CSS selectors (legacy admin pages)
  root.setAttribute('data-theme', resolvedMode)

  // Toggle .dark class for shadcn/ui + Tailwind dark mode
  if (resolvedMode === 'dark') {
    root.classList.add('dark')
  } else {
    root.classList.remove('dark')
  }
}

// Create the context with undefined default
const ThemeContext = createContext<ThemeContextValue | undefined>(undefined)

/**
 * Theme Provider Component
 *
 * Wraps the application to provide theme state and methods.
 * Handles theme persistence, system preference detection, and CSS custom properties.
 *
 * @example
 * ```tsx
 * <ThemeProvider>
 *   <App />
 * </ThemeProvider>
 * ```
 */
export function ThemeProvider({
  children,
  initialMode,
  themeConfig,
}: ThemeProviderProps): React.ReactElement {
  // Initialize mode from stored preference, initial prop, or default to 'system'
  const [mode, setModeState] = useState<ThemeMode>(() => {
    const stored = getStoredTheme()
    return stored ?? initialMode ?? 'system'
  })

  // Track system theme for 'system' mode
  const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(getSystemTheme)

  // Calculate resolved mode
  const resolvedMode: ResolvedTheme = mode === 'system' ? systemTheme : mode

  // Calculate colors based on resolved mode and config
  const colors = useMemo(
    () => getThemeColors(resolvedMode, themeConfig),
    [resolvedMode, themeConfig]
  )

  /**
   * Set theme mode and persist to localStorage
   * Requirement 16.3: Allow users to manually select theme
   * Requirement 16.4: Persist theme preference
   */
  const setMode = useCallback((newMode: ThemeMode): void => {
    setModeState(newMode)
    storeTheme(newMode)
  }, [])

  /**
   * Listen for system theme changes
   * Requirement 16.2: Detect user's system theme preference
   */
  useEffect(() => {
    if (typeof window === 'undefined') {
      return
    }

    const mediaQuery = window.matchMedia(DARK_MODE_MEDIA_QUERY)

    const handleChange = (event: MediaQueryListEvent): void => {
      setSystemTheme(event.matches ? 'dark' : 'light')
    }

    // Modern browsers
    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener('change', handleChange)
      return () => mediaQuery.removeEventListener('change', handleChange)
    }

    // Legacy browsers (Safari < 14)
    mediaQuery.addListener(handleChange)
    return () => mediaQuery.removeListener(handleChange)
  }, [])

  /**
   * Apply CSS custom properties when theme changes
   * Requirement 16.5: Update UI colors without page reload
   */
  useEffect(() => {
    applyCSSCustomProperties(colors, themeConfig)
    applyThemeClass(resolvedMode)
  }, [colors, resolvedMode, themeConfig])

  // Memoize context value to prevent unnecessary re-renders
  const contextValue = useMemo<ThemeContextValue>(
    () => ({
      mode,
      resolvedMode,
      setMode,
      colors,
    }),
    [mode, resolvedMode, setMode, colors]
  )

  return <ThemeContext.Provider value={contextValue}>{children}</ThemeContext.Provider>
}

/**
 * Hook to access theme context
 *
 * @throws Error if used outside of ThemeProvider
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { mode, resolvedMode, setMode, colors } = useTheme();
 *
 *   return (
 *     <button
 *       onClick={() => setMode(mode === 'dark' ? 'light' : 'dark')}
 *       style={{ backgroundColor: colors.primary }}
 *     >
 *       Toggle Theme
 *     </button>
 *   );
 * }
 * ```
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext)
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeProvider')
  }
  return context
}

// eslint-disable-next-line react-refresh/only-export-components
export { getSystemTheme, getStoredTheme, storeTheme, resolveTheme, getThemeColors }
