/**
 * useSavedViews Hook
 *
 * Manages saved list views for collection record browsers.
 * Each view captures filter conditions, sort configuration,
 * visible columns, and page size.
 *
 * Persistence (app-data-entry slice 1): server-side per user via the
 * `user-ui-preferences` store (prefType `list-view`, prefKey = collection),
 * with the legacy localStorage key as warm cache, offline fallback, and
 * one-time migration source. Views finally follow the user across browsers.
 */

import { useEffect, useRef, useState, useCallback } from 'react'
import { uuid } from '@/utils/uuid'
import { usePreferenceValue } from './usePreferenceStore'

/**
 * A single filter condition within a saved view.
 */
export interface FilterCondition {
  id: string
  field: string
  operator: string
  value: string
}

/**
 * A saved list view configuration for a collection.
 */
export type SavedViewType = 'table' | 'kanban' | 'calendar' | 'gallery'
export type SavedViewDensity = 'compact' | 'normal' | 'comfortable'

export interface SavedViewSort {
  field: string
  direction: 'asc' | 'desc'
}

export interface SavedView {
  id: string
  name: string
  collectionName: string
  filters: FilterCondition[]
  sortField: string | null
  sortDirection: 'asc' | 'desc'
  visibleColumns: string[]
  pageSize: number
  isDefault: boolean
  createdAt: string
  // ---- v2 (app-data-entry slice 2) — all optional; absent reads as today's behavior.
  /** Ordered multi-sort; supersedes sortField/sortDirection when present. */
  sorts?: SavedViewSort[]
  /** Renderer for this view; absent = 'table'. */
  viewType?: SavedViewType
  /** Row density; absent = 'normal'. */
  density?: SavedViewDensity
  /** This-page grouping field (table view; slice 3 consumes). */
  groupBy?: string | null
  /** Per-view-type config (kanban lanes, calendar date field, gallery card). */
  typeConfig?: {
    kanban?: { laneField: string; cardFields?: string[] }
    calendar?: { dateField: string; endDateField?: string }
    gallery?: { imageField?: string; titleField?: string; cardFields?: string[] }
  }
}

/** Effective ordered sorts for a view (v2 `sorts` wins; v1 fields as fallback). */
export function viewSorts(view: SavedView): SavedViewSort[] {
  if (view.sorts && view.sorts.length > 0) return view.sorts
  if (view.sortField) return [{ field: view.sortField, direction: view.sortDirection }]
  return []
}

export interface UseSavedViewsReturn {
  views: SavedView[]
  activeView: SavedView | null
  saveView: (
    name: string,
    config: Omit<SavedView, 'id' | 'name' | 'collectionName' | 'createdAt'>
  ) => void
  deleteView: (viewId: string) => void
  renameView: (viewId: string, newName: string) => void
  setDefaultView: (viewId: string) => void
  selectView: (viewId: string | null) => void
  getDefaultView: () => SavedView | null
}

function getStorageKey(collectionName: string): string {
  return `kelta_views_${collectionName}`
}

function loadViews(collectionName: string): SavedView[] {
  try {
    const raw = localStorage.getItem(getStorageKey(collectionName))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function persistViews(collectionName: string, views: SavedView[]): void {
  try {
    localStorage.setItem(getStorageKey(collectionName), JSON.stringify(views))
  } catch {
    // localStorage full or unavailable
  }
}

/**
 * Hook to manage saved list views for a collection.
 * Views are stored in localStorage and include filters, sort,
 * visible columns, page size, and a default flag.
 *
 * On mount, the default view (if one exists) is automatically selected.
 *
 * @param collectionName - The collection these views belong to
 * @param options.enabled - False when the list's saved-views feature is off: the server
 *   preference is neither fetched nor migrated to. Default true.
 */
export function useSavedViews(
  collectionName: string,
  { enabled = true }: { enabled?: boolean } = {}
): UseSavedViewsReturn {
  const [currentCollection, setCurrentCollection] = useState(collectionName)
  const [views, setViews] = useState<SavedView[]>(() => loadViews(collectionName))
  const [activeView, setActiveView] = useState<SavedView | null>(() => {
    const loaded = loadViews(collectionName)
    return loaded.find((v) => v.isDefault) ?? null
  })

  // Server-side persistence: one preference row per collection holding the view array.
  const pref = usePreferenceValue<SavedView[]>('list-view', collectionName, {
    localKey: getStorageKey(collectionName),
    enabled,
  })
  const prefSave = pref.save
  const syncedRef = useRef<string | null>(null)

  // When the server value loads: server wins (cross-device source of truth); an empty
  // server with existing local views triggers the one-time migration push.
  useEffect(() => {
    if (!enabled || !pref.isLoaded || syncedRef.current === collectionName) return
    syncedRef.current = collectionName
    if (pref.value !== null) {
      const serverViews = Array.isArray(pref.value) ? pref.value : []
      persistViews(collectionName, serverViews)
      // Deferred so the adoption never sets state synchronously inside the effect
      // (avoids a cascading render when the server answer arrives on mount).
      const timer = setTimeout(() => {
        setViews(serverViews)
        setActiveView((prev) => prev ?? serverViews.find((v) => v.isDefault) ?? null)
      }, 0)
      return () => clearTimeout(timer)
    }
    const local = loadViews(collectionName)
    if (local.length > 0) {
      prefSave(local)
    }
  }, [enabled, pref.isLoaded, pref.value, collectionName, prefSave])

  // Reload views when collectionName changes (derived state pattern). No ref reset
  // needed: the sync guard compares syncedRef.current to the CURRENT collection name,
  // so a collection switch re-arms it naturally.
  if (collectionName !== currentCollection) {
    setCurrentCollection(collectionName)
    const loaded = loadViews(collectionName)
    setViews(loaded)
    setActiveView(loaded.find((v) => v.isDefault) ?? null)
  }

  const saveView = useCallback(
    (name: string, config: Omit<SavedView, 'id' | 'name' | 'collectionName' | 'createdAt'>) => {
      setViews((prev) => {
        const existingIndex = prev.findIndex((v) => v.name === name)

        const newView: SavedView = {
          id: existingIndex >= 0 ? prev[existingIndex].id : uuid(),
          name,
          collectionName,
          filters: config.filters,
          sortField: config.sortField,
          sortDirection: config.sortDirection,
          visibleColumns: config.visibleColumns,
          pageSize: config.pageSize,
          isDefault: config.isDefault,
          sorts: config.sorts,
          viewType: config.viewType,
          density: config.density,
          groupBy: config.groupBy,
          typeConfig: config.typeConfig,
          createdAt: existingIndex >= 0 ? prev[existingIndex].createdAt : new Date().toISOString(),
        }

        let updated: SavedView[]
        if (existingIndex >= 0) {
          updated = [...prev]
          updated[existingIndex] = newView
        } else {
          updated = [...prev, newView]
        }

        // If this view is set as default, clear default from all others
        if (newView.isDefault) {
          updated = updated.map((v) => (v.id === newView.id ? v : { ...v, isDefault: false }))
        }

        persistViews(collectionName, updated)
        prefSave(updated)
        return updated
      })
    },
    [collectionName, prefSave]
  )

  const deleteView = useCallback(
    (viewId: string) => {
      setViews((prev) => {
        const updated = prev.filter((v) => v.id !== viewId)
        persistViews(collectionName, updated)
        prefSave(updated)
        return updated
      })
      setActiveView((prev) => (prev?.id === viewId ? null : prev))
    },
    [collectionName, prefSave]
  )

  const renameView = useCallback(
    (viewId: string, newName: string) => {
      setViews((prev) => {
        const updated = prev.map((v) => (v.id === viewId ? { ...v, name: newName } : v))
        persistViews(collectionName, updated)
        prefSave(updated)
        return updated
      })
      setActiveView((prev) => (prev?.id === viewId ? { ...prev, name: newName } : prev))
    },
    [collectionName, prefSave]
  )

  const setDefaultView = useCallback(
    (viewId: string) => {
      setViews((prev) => {
        const updated = prev.map((v) => ({
          ...v,
          isDefault: v.id === viewId,
        }))
        persistViews(collectionName, updated)
        prefSave(updated)
        return updated
      })
      setActiveView((prev) => {
        if (prev?.id === viewId) {
          return { ...prev, isDefault: true }
        }
        if (prev) {
          return { ...prev, isDefault: false }
        }
        return prev
      })
    },
    [collectionName, prefSave]
  )

  const selectView = useCallback((viewId: string | null) => {
    if (viewId === null) {
      setActiveView(null)
      return
    }
    setViews((prev) => {
      const found = prev.find((v) => v.id === viewId) ?? null
      setActiveView(found)
      return prev
    })
  }, [])

  const getDefaultView = useCallback((): SavedView | null => {
    return views.find((v) => v.isDefault) ?? null
  }, [views])

  return {
    views,
    activeView,
    saveView,
    deleteView,
    renameView,
    setDefaultView,
    selectView,
    getDefaultView,
  }
}
