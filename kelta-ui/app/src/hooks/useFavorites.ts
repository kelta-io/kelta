/**
 * useFavorites Hook
 *
 * Manages a list of favorite (starred/pinned) records and collections
 * stored in localStorage. Provides reactive updates across tabs.
 */

import { useState, useCallback, useEffect } from 'react'

/**
 * A favorite item entry
 */
interface FavoriteItem {
  id: string
  type: 'record' | 'collection'
  collectionName?: string
  collectionDisplayName?: string
  displayValue: string
  addedAt: string
}

const MAX_FAVORITES = 25
const STORAGE_KEY_PREFIX = 'kelta_favorites_'

function getStorageKey(userId: string): string {
  return `${STORAGE_KEY_PREFIX}${userId}`
}

function loadFavorites(userId: string): FavoriteItem[] {
  try {
    const raw = localStorage.getItem(getStorageKey(userId))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function saveFavorites(userId: string, items: FavoriteItem[]): void {
  try {
    localStorage.setItem(getStorageKey(userId), JSON.stringify(items))
  } catch {
    // localStorage full or unavailable
  }
}

export interface UseFavoritesReturn {
  favorites: FavoriteItem[]
  addFavorite: (item: Omit<FavoriteItem, 'addedAt'>) => boolean
  removeFavorite: (id: string, type: 'record' | 'collection') => void
  isFavorite: (id: string, type: 'record' | 'collection') => boolean
  clearFavorites: () => void
}

/**
 * Hook to manage favorite records and collections.
 * Items are stored in localStorage and synced across tabs.
 *
 * @param userId - The current user's ID (used as storage key namespace)
 */
export function useFavorites(userId: string): UseFavoritesReturn {
  const [items, setItems] = useState<FavoriteItem[]>(() => loadFavorites(userId))

  // Sync across tabs
  useEffect(() => {
    const handleStorage = (e: StorageEvent) => {
      if (e.key === getStorageKey(userId)) {
        setItems(loadFavorites(userId))
      }
    }
    window.addEventListener('storage', handleStorage)
    return () => window.removeEventListener('storage', handleStorage)
  }, [userId])

  // Reload when userId changes
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- existing sync-from-prop/storage effect; refactor tracked as follow-up to PLT-854
    setItems(loadFavorites(userId))
  }, [userId])

  const addFavorite = useCallback(
    (item: Omit<FavoriteItem, 'addedAt'>): boolean => {
      let added = false
      setItems((prev) => {
        // Already exists
        if (prev.some((f) => f.id === item.id && f.type === item.type)) {
          return prev
        }
        if (prev.length >= MAX_FAVORITES) {
          return prev
        }
        const entry: FavoriteItem = { ...item, addedAt: new Date().toISOString() }
        const updated = [entry, ...prev]
        saveFavorites(userId, updated)
        added = true
        return updated
      })
      return added
    },
    [userId]
  )

  const removeFavorite = useCallback(
    (id: string, type: 'record' | 'collection') => {
      setItems((prev) => {
        const updated = prev.filter((f) => !(f.id === id && f.type === type))
        saveFavorites(userId, updated)
        return updated
      })
    },
    [userId]
  )

  const isFavorite = useCallback(
    (id: string, type: 'record' | 'collection'): boolean => {
      return items.some((f) => f.id === id && f.type === type)
    },
    [items]
  )

  const clearFavorites = useCallback(() => {
    setItems([])
    try {
      localStorage.removeItem(getStorageKey(userId))
    } catch {
      // ignore
    }
  }, [userId])

  return { favorites: items, addFavorite, removeFavorite, isFavorite, clearFavorites }
}
