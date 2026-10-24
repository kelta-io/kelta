import { useCallback, useEffect, useState } from 'react'

const MAX_RECENT = 5

interface RecentEntry {
  path: string
  visitedAt: number
}

function storageKey(tenantSlug: string): string {
  return `kelta:setup:recent:${tenantSlug}`
}

function readRecent(tenantSlug: string): RecentEntry[] {
  try {
    const raw = window.localStorage.getItem(storageKey(tenantSlug))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter(
        (e): e is RecentEntry =>
          e != null &&
          typeof e === 'object' &&
          typeof e.path === 'string' &&
          typeof e.visitedAt === 'number'
      )
      .slice(0, MAX_RECENT)
  } catch {
    return []
  }
}

export interface UseRecentSetup {
  recent: RecentEntry[]
  recordVisit: (path: string) => void
  clear: () => void
}

export function useRecentSetup(tenantSlug: string): UseRecentSetup {
  const [recent, setRecent] = useState<RecentEntry[]>(() => readRecent(tenantSlug))

  useEffect(() => {
    setRecent(readRecent(tenantSlug))
  }, [tenantSlug])

  useEffect(() => {
    try {
      window.localStorage.setItem(storageKey(tenantSlug), JSON.stringify(recent))
    } catch {
      // ignore
    }
  }, [tenantSlug, recent])

  const recordVisit = useCallback((path: string) => {
    setRecent((current) => {
      const filtered = current.filter((e) => e.path !== path)
      return [{ path, visitedAt: Date.now() }, ...filtered].slice(0, MAX_RECENT)
    })
  }, [])

  const clear = useCallback(() => setRecent([]), [])

  return { recent, recordVisit, clear }
}
