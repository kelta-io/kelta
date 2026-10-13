import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { useSavedViews, type SavedView } from './useSavedViews'
import { usePreferenceValue } from './usePreferenceStore'

let mockPref: { value: unknown; isLoaded: boolean; save: ReturnType<typeof vi.fn> }
vi.mock('./usePreferenceStore', () => ({
  usePreferenceValue: vi.fn(() => mockPref),
}))

const localBacking = new Map<string, string>()
function wireLocalStorageBacking() {
  localBacking.clear()
  vi.mocked(localStorage.getItem).mockImplementation((k: string) => localBacking.get(k) ?? null)
  vi.mocked(localStorage.setItem).mockImplementation(
    (k: string, v: string) => void localBacking.set(k, String(v))
  )
  vi.mocked(localStorage.removeItem).mockImplementation((k: string) => void localBacking.delete(k))
}

function view(partial: Partial<SavedView>): SavedView {
  return {
    id: 'v1',
    name: 'Mine',
    collectionName: 'orders',
    filters: [],
    sortField: null,
    sortDirection: 'asc',
    visibleColumns: [],
    pageSize: 25,
    isDefault: false,
    createdAt: '2026-01-01',
    ...partial,
  }
}

beforeEach(() => {
  wireLocalStorageBacking()
  mockPref = { value: null, isLoaded: false, save: vi.fn() }
})

describe('useSavedViews (server-backed)', () => {
  it('adopts the server value when it loads (server wins over localStorage)', async () => {
    localStorage.setItem('kelta_views_orders', JSON.stringify([view({ id: 'stale' })]))
    mockPref = { value: [view({ id: 'srv' })], isLoaded: true, save: vi.fn() }

    const { result } = renderHook(() => useSavedViews('orders'))
    await waitFor(() => expect(result.current.views.map((v) => v.id)).toEqual(['srv']))
    // localStorage cache refreshed to the server value
    expect(JSON.parse(localStorage.getItem('kelta_views_orders')!)[0].id).toBe('srv')
  })

  it('migrates existing local views to the server when the server has none', async () => {
    localStorage.setItem('kelta_views_orders', JSON.stringify([view({ id: 'legacy' })]))
    mockPref = { value: null, isLoaded: true, save: vi.fn() }

    renderHook(() => useSavedViews('orders'))
    await waitFor(() => expect(mockPref.save).toHaveBeenCalledTimes(1))
    expect((mockPref.save.mock.calls[0][0] as SavedView[])[0].id).toBe('legacy')
  })

  it('pushes every mutation to the server store', async () => {
    mockPref = { value: [], isLoaded: true, save: vi.fn() }
    const { result } = renderHook(() => useSavedViews('orders'))
    await waitFor(() => expect(result.current.views).toEqual([]))

    act(() =>
      result.current.saveView('New view', {
        filters: [],
        sortField: null,
        sortDirection: 'asc',
        visibleColumns: ['name'],
        pageSize: 25,
        isDefault: false,
      })
    )
    expect(mockPref.save).toHaveBeenCalled()
    const pushed = mockPref.save.mock.calls.at(-1)![0] as SavedView[]
    expect(pushed).toHaveLength(1)
    expect(pushed[0].name).toBe('New view')

    act(() => result.current.deleteView(pushed[0].id))
    const afterDelete = mockPref.save.mock.calls.at(-1)![0] as SavedView[]
    expect(afterDelete).toHaveLength(0)
  })

  it('keeps working from localStorage while the server has not answered', () => {
    localStorage.setItem('kelta_views_orders', JSON.stringify([view({ id: 'local' })]))
    mockPref = { value: null, isLoaded: false, save: vi.fn() }
    const { result } = renderHook(() => useSavedViews('orders'))
    expect(result.current.views.map((v) => v.id)).toEqual(['local'])
  })
  it('when disabled, passes that to the preference store and never migrates local views to it', async () => {
    localStorage.setItem('kelta_views_orders', JSON.stringify([view({ id: 'legacy' })]))
    // A disabled preference reports loaded with no value — exactly the migration trigger.
    mockPref = { value: null, isLoaded: true, save: vi.fn() }

    renderHook(() => useSavedViews('orders', { enabled: false }))
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(vi.mocked(usePreferenceValue)).toHaveBeenLastCalledWith('list-view', 'orders', {
      localKey: 'kelta_views_orders',
      enabled: false,
    })
    expect(mockPref.save).not.toHaveBeenCalled()
  })
})
