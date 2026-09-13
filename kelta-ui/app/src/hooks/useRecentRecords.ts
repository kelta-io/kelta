/**
 * useRecentRecords Hook
 *
 * Manages a list of recently viewed/edited records stored in localStorage.
 * Provides reactive updates across tabs via storage events.
 */

import { useState, useCallback, useEffect } from 'react'
import { useTenantStorageScope } from '../context/TenantContext'

/**
 * A recently viewed record entry
 */
export interface RecentRecord {
  id: string
  collectionName: string
  collectionDisplayName: string
  displayValue: string
  viewedAt: string
}

const MAX_ITEMS = 50
const STORAGE_KEY_PREFIX = 'kelta_recent_'

/**
 * Storage key for one user in one workspace. The tenant scope is part of the
 * key: localStorage is per-origin and every workspace on the platform host
 * shares the origin, so a user-only key leaks records across tenants.
 */
export function getStorageKey(scope: string, userId: string): string {
  return `${STORAGE_KEY_PREFIX}${scope}:${userId}`
}

/** Pre-tenant-scoping key; entries under it carry no tenant and are dropped. */
function getLegacyStorageKey(userId: string): string {
  return `${STORAGE_KEY_PREFIX}${userId}`
}

function isRecentRecord(value: unknown): value is RecentRecord {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.id === 'string' &&
    typeof v.collectionName === 'string' &&
    typeof v.collectionDisplayName === 'string' &&
    typeof v.displayValue === 'string' &&
    typeof v.viewedAt === 'string'
  )
}

function loadRecords(scope: string, userId: string): RecentRecord[] {
  try {
    localStorage.removeItem(getLegacyStorageKey(userId))
    const raw = localStorage.getItem(getStorageKey(scope, userId))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isRecentRecord) : []
  } catch {
    return []
  }
}

function saveRecords(scope: string, userId: string, records: RecentRecord[]): void {
  try {
    localStorage.setItem(getStorageKey(scope, userId), JSON.stringify(records))
  } catch {
    // localStorage full or unavailable
  }
}

export interface UseRecentRecordsReturn {
  recentRecords: RecentRecord[]
  addRecentRecord: (record: Omit<RecentRecord, 'viewedAt'>) => void
  clearRecentRecords: () => void
}

/**
 * Hook to manage recently viewed records.
 * Records are stored in localStorage (per workspace, per user) and synced
 * across tabs.
 *
 * @param userId - The current user's ID (used as storage key namespace)
 */
export function useRecentRecords(userId: string): UseRecentRecordsReturn {
  const scope = useTenantStorageScope()
  const [records, setRecords] = useState<RecentRecord[]>(() => loadRecords(scope, userId))

  // Sync across tabs
  useEffect(() => {
    const handleStorage = (e: StorageEvent) => {
      if (e.key === getStorageKey(scope, userId)) {
        setRecords(loadRecords(scope, userId))
      }
    }
    window.addEventListener('storage', handleStorage)
    return () => window.removeEventListener('storage', handleStorage)
  }, [scope, userId])

  // Reload when the workspace or user changes
  useEffect(() => {
    setRecords(loadRecords(scope, userId))
  }, [scope, userId])

  const addRecentRecord = useCallback(
    (record: Omit<RecentRecord, 'viewedAt'>) => {
      setRecords((prev) => {
        // Remove duplicate (move to top)
        const filtered = prev.filter(
          (r) => !(r.id === record.id && r.collectionName === record.collectionName)
        )
        const entry: RecentRecord = { ...record, viewedAt: new Date().toISOString() }
        const updated = [entry, ...filtered].slice(0, MAX_ITEMS)
        saveRecords(scope, userId, updated)
        return updated
      })
    },
    [scope, userId]
  )

  const clearRecentRecords = useCallback(() => {
    setRecords([])
    try {
      localStorage.removeItem(getStorageKey(scope, userId))
    } catch {
      // ignore
    }
  }, [scope, userId])

  return { recentRecords: records, addRecentRecord, clearRecentRecords }
}
