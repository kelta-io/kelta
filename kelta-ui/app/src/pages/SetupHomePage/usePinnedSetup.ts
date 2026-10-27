import { useCallback, useEffect, useState } from 'react'

const MAX_PINNED = 6

function storageKey(tenantSlug: string): string {
  return `kelta:setup:pinned:${tenantSlug}`
}

function readPinned(tenantSlug: string): string[] {
  try {
    const raw = window.localStorage.getItem(storageKey(tenantSlug))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((p): p is string => typeof p === 'string').slice(0, MAX_PINNED)
  } catch {
    return []
  }
}

export interface UsePinnedSetup {
  pinned: string[]
  isPinned: (path: string) => boolean
  pin: (path: string) => void
  unpin: (path: string) => void
  toggle: (path: string) => void
}

export function usePinnedSetup(tenantSlug: string): UsePinnedSetup {
  const [pinned, setPinned] = useState<string[]>(() => readPinned(tenantSlug))

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- existing sync-from-prop/storage effect; refactor tracked as follow-up to PLT-854
    setPinned(readPinned(tenantSlug))
  }, [tenantSlug])

  useEffect(() => {
    try {
      window.localStorage.setItem(storageKey(tenantSlug), JSON.stringify(pinned))
    } catch {
      // ignore quota / disabled storage
    }
  }, [tenantSlug, pinned])

  const pin = useCallback((path: string) => {
    setPinned((current) => {
      if (current.includes(path)) return current
      return [...current, path].slice(-MAX_PINNED)
    })
  }, [])

  const unpin = useCallback((path: string) => {
    setPinned((current) => current.filter((p) => p !== path))
  }, [])

  const toggle = useCallback((path: string) => {
    setPinned((current) => {
      if (current.includes(path)) {
        return current.filter((p) => p !== path)
      }
      return [...current, path].slice(-MAX_PINNED)
    })
  }, [])

  const isPinned = useCallback((path: string) => pinned.includes(path), [pinned])

  return { pinned, isPinned, pin, unpin, toggle }
}
