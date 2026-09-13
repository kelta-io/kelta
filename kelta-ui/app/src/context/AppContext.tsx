/**
 * AppContext
 *
 * Manages end-user application state: recent items, favorites,
 * and active app/tab selection. State is persisted to localStorage.
 */

import React, { createContext, useContext, useState, useCallback, useMemo, useEffect } from 'react'
import { useTenantStorageScope } from './TenantContext'

// ---- Types ----

export interface RecentItem {
  /** Record ID */
  id: string
  /** Collection API name */
  collectionName: string
  /** Display label (e.g., record name) */
  label: string
  /** Timestamp of last access (ms since epoch) */
  timestamp: number
}

export interface Favorite {
  /** Unique key (e.g., "collection:accounts" or "record:accounts:abc-123") */
  key: string
  /** Display label */
  label: string
  /** Type of favorite */
  type: 'collection' | 'record' | 'listview'
  /** Collection API name */
  collectionName: string
  /** Record ID (for record favorites) */
  recordId?: string
}

export interface AppContextValue {
  /** Recently accessed records */
  recentItems: RecentItem[]
  /** User favorites */
  favorites: Favorite[]
  /** Add a record to recent items */
  addRecentItem: (item: Omit<RecentItem, 'timestamp'>) => void
  /** Toggle a favorite on/off */
  toggleFavorite: (item: Favorite) => void
  /** Check if an item is favorited */
  isFavorite: (key: string) => boolean
  /** Clear all recent items */
  clearRecentItems: () => void
}

// ---- Constants ----

const MAX_RECENT_ITEMS = 25
/**
 * Pre-tenant-scoping keys. Recents under these are dropped (they carry no
 * tenant, so a UUID from one workspace looks valid in every other one);
 * favorites are adopted by the first workspace that loads them and the
 * legacy key is removed — see {@link loadFavorites}.
 */
const LEGACY_RECENT_ITEMS_KEY = 'kelta_recent_items'
const LEGACY_FAVORITES_KEY = 'kelta_favorites'

/** Per-tenant localStorage keys. `scope` comes from useTenantStorageScope. */
// eslint-disable-next-line react-refresh/only-export-components
export function recentItemsStorageKey(scope: string): string {
  return `${LEGACY_RECENT_ITEMS_KEY}:${scope}`
}

// eslint-disable-next-line react-refresh/only-export-components
export function favoritesStorageKey(scope: string): string {
  return `${LEGACY_FAVORITES_KEY}:${scope}`
}

// ---- Storage helpers ----

function readJsonArray(key: string): unknown[] {
  try {
    const stored = localStorage.getItem(key)
    if (!stored) return []
    const parsed: unknown = JSON.parse(stored)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    // localStorage may not be available, or the value is not JSON
    return []
  }
}

function isRecentItemEntry(value: unknown): value is RecentItem {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.id === 'string' &&
    typeof v.collectionName === 'string' &&
    typeof v.label === 'string' &&
    typeof v.timestamp === 'number'
  )
}

function isFavoriteEntry(value: unknown): value is Favorite {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.key === 'string' &&
    typeof v.label === 'string' &&
    typeof v.collectionName === 'string' &&
    (v.type === 'collection' || v.type === 'record' || v.type === 'listview')
  )
}

function loadRecentItems(scope: string): RecentItem[] {
  // Legacy unscoped recents are junk once a browser has seen two workspaces —
  // discard rather than guess which tenant they belonged to.
  try {
    localStorage.removeItem(LEGACY_RECENT_ITEMS_KEY)
  } catch {
    // localStorage may not be available
  }
  return readJsonArray(recentItemsStorageKey(scope)).filter(isRecentItemEntry)
}

function loadFavorites(scope: string): Favorite[] {
  const scoped = readJsonArray(favoritesStorageKey(scope)).filter(isFavoriteEntry)
  const legacy = readJsonArray(LEGACY_FAVORITES_KEY).filter(isFavoriteEntry)
  if (legacy.length === 0) return scoped
  // One-time adoption: a single-workspace browser (the common case) keeps its
  // favorites; a multi-workspace browser gets them under whichever workspace
  // loads first and can un-star the strays.
  const known = new Set(scoped.map((f) => f.key))
  const merged = [...scoped, ...legacy.filter((f) => !known.has(f.key))]
  saveToStorage(favoritesStorageKey(scope), merged)
  try {
    localStorage.removeItem(LEGACY_FAVORITES_KEY)
  } catch {
    // localStorage may not be available
  }
  return merged
}

function saveToStorage<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // localStorage may not be available
  }
}

// ---- Context ----

const AppContext = createContext<AppContextValue | undefined>(undefined)

interface AppContextProviderProps {
  children: React.ReactNode
}

export function AppContextProvider({ children }: AppContextProviderProps): React.ReactElement {
  // Everything below is keyed by workspace so state never crosses tenants.
  const scope = useTenantStorageScope()
  const [recentItems, setRecentItems] = useState<RecentItem[]>(() => loadRecentItems(scope))
  const [favorites, setFavorites] = useState<Favorite[]>(() => loadFavorites(scope))

  // Re-hydrate when the workspace changes (in-app navigation between slugs).
  // Writes happen inside the updaters, not in a persist effect, so a scope
  // change can never copy one workspace's state into another's key.
  useEffect(() => {
    setRecentItems(loadRecentItems(scope))
    setFavorites(loadFavorites(scope))
  }, [scope])

  const addRecentItem = useCallback(
    (item: Omit<RecentItem, 'timestamp'>) => {
      setRecentItems((prev) => {
        // Remove existing entry for same record
        const filtered = prev.filter(
          (r) => !(r.id === item.id && r.collectionName === item.collectionName)
        )
        // Add to front with current timestamp, cap at max
        const updated = [{ ...item, timestamp: Date.now() }, ...filtered].slice(0, MAX_RECENT_ITEMS)
        saveToStorage(recentItemsStorageKey(scope), updated)
        return updated
      })
    },
    [scope]
  )

  const toggleFavorite = useCallback(
    (item: Favorite) => {
      setFavorites((prev) => {
        const exists = prev.some((f) => f.key === item.key)
        const updated = exists ? prev.filter((f) => f.key !== item.key) : [...prev, item]
        saveToStorage(favoritesStorageKey(scope), updated)
        return updated
      })
    },
    [scope]
  )

  const isFavorite = useCallback(
    (key: string) => {
      return favorites.some((f) => f.key === key)
    },
    [favorites]
  )

  const clearRecentItems = useCallback(() => {
    setRecentItems([])
    saveToStorage(recentItemsStorageKey(scope), [])
  }, [scope])

  const value = useMemo<AppContextValue>(
    () => ({
      recentItems,
      favorites,
      addRecentItem,
      toggleFavorite,
      isFavorite,
      clearRecentItems,
    }),
    [recentItems, favorites, addRecentItem, toggleFavorite, isFavorite, clearRecentItems]
  )

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>
}

/**
 * Hook to access end-user application context.
 * @throws Error if used outside of AppContextProvider
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useAppContext(): AppContextValue {
  const context = useContext(AppContext)
  if (context === undefined) {
    throw new Error('useAppContext must be used within an AppContextProvider')
  }
  return context
}
