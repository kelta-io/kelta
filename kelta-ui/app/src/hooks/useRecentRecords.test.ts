/**
 * useRecentRecords Unit Tests — storage is keyed by workspace AND user.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useRecentRecords, getStorageKey } from './useRecentRecords'
import { useMemoryLocalStorage } from '../test/memoryStorage'

const tenant = vi.hoisted(() => ({ slug: 'spotopened' }))

vi.mock('../context/TenantContext', () => ({
  useTenantStorageScope: () => tenant.slug,
}))

const entry = (id: string, displayValue: string) => ({
  id,
  collectionName: 'titles',
  collectionDisplayName: 'Titles',
  displayValue,
  viewedAt: '2026-09-12T00:00:00.000Z',
})

describe('useRecentRecords', () => {
  let storage: Storage
  const originalStorage = window.localStorage

  beforeEach(() => {
    tenant.slug = 'spotopened'
    storage = useMemoryLocalStorage()
  })

  afterEach(() => {
    Object.defineProperty(window, 'localStorage', { value: originalStorage, configurable: true })
  })

  it('reads only entries for the current workspace and user', () => {
    storage.setItem(getStorageKey('couchpicks', 'u1'), JSON.stringify([entry('a', 'Other')]))
    storage.setItem(getStorageKey('spotopened', 'u1'), JSON.stringify([entry('b', 'Mine')]))
    storage.setItem(getStorageKey('spotopened', 'u2'), JSON.stringify([entry('c', 'Theirs')]))

    const { result } = renderHook(() => useRecentRecords('u1'))

    expect(result.current.recentRecords.map((r) => r.displayValue)).toEqual(['Mine'])
  })

  it('drops the legacy user-only key rather than leaking it across workspaces', () => {
    storage.setItem('kelta_recent_u1', JSON.stringify([entry('a', 'Legacy')]))

    const { result } = renderHook(() => useRecentRecords('u1'))

    expect(result.current.recentRecords).toEqual([])
    expect(storage.getItem('kelta_recent_u1')).toBeNull()
  })

  it('writes under the workspace-scoped key', () => {
    const { result } = renderHook(() => useRecentRecords('u1'))

    act(() =>
      result.current.addRecentRecord({
        id: 'x',
        collectionName: 'titles',
        collectionDisplayName: 'Titles',
        displayValue: 'New',
      })
    )

    const saved = JSON.parse(storage.getItem(getStorageKey('spotopened', 'u1')) ?? '[]')
    expect(saved).toHaveLength(1)
    expect(saved[0]).toMatchObject({ id: 'x', displayValue: 'New' })
    expect(storage.getItem('kelta_recent_u1')).toBeNull()
  })

  it('re-reads when the workspace changes', () => {
    storage.setItem(getStorageKey('spotopened', 'u1'), JSON.stringify([entry('b', 'Spot')]))
    storage.setItem(getStorageKey('couchpicks', 'u1'), JSON.stringify([entry('a', 'Couch')]))

    const { result, rerender } = renderHook(() => useRecentRecords('u1'))
    expect(result.current.recentRecords[0]?.displayValue).toBe('Spot')

    tenant.slug = 'couchpicks'
    rerender()

    expect(result.current.recentRecords[0]?.displayValue).toBe('Couch')
  })

  it('filters malformed entries', () => {
    storage.setItem(
      getStorageKey('spotopened', 'u1'),
      JSON.stringify([entry('ok', 'Good'), { id: 'partial' }, 42])
    )

    const { result } = renderHook(() => useRecentRecords('u1'))

    expect(result.current.recentRecords.map((r) => r.id)).toEqual(['ok'])
  })
})
