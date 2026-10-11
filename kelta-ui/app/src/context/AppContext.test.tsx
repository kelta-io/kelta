/**
 * AppContext Unit Tests
 *
 * Recent items and favorites are keyed by workspace: a browser signed in to
 * two tenants on the same origin must never show one tenant's records in the
 * other's "Recent items" card. Favorites are additionally server-persisted per
 * user through the preference store (prefType `favorites`).
 */

import React from 'react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { TenantProvider } from './TenantContext'
import {
  AppContextProvider,
  useAppContext,
  recentItemsStorageKey,
  favoritesStorageKey,
  type Favorite,
} from './AppContext'
import { useMemoryLocalStorage } from '../test/memoryStorage'
import { usePreferenceValue } from '../hooks/usePreferenceStore'

// null → the real preference store runs (with the identity/API mocks below).
let mockPref: { value: unknown; isLoaded: boolean; save: ReturnType<typeof vi.fn> } | null = null
vi.mock('../hooks/usePreferenceStore', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../hooks/usePreferenceStore')>()
  return {
    usePreferenceValue: vi.fn(
      (...args: Parameters<typeof actual.usePreferenceValue>) =>
        mockPref ?? actual.usePreferenceValue(...args)
    ),
  }
})

let mockAuthenticated = true
vi.mock('./AuthContext', () => ({
  useAuth: vi.fn(() => ({ isAuthenticated: mockAuthenticated })),
}))

let mockIdentity: { userId: string } | undefined
vi.mock('../hooks/useMyIdentity', () => ({
  useMyIdentity: vi.fn(() => ({ identity: mockIdentity, isLoading: false })),
}))

const mockApiClient = {
  getList: vi.fn(),
  postResource: vi.fn(),
  patchResource: vi.fn(),
}
vi.mock('./ApiContext', () => ({
  useApi: vi.fn(() => ({ apiClient: mockApiClient })),
}))

function Consumer() {
  const { recentItems, favorites, addRecentItem, toggleFavorite, clearRecentItems } =
    useAppContext()
  return (
    <div>
      <ul data-testid="recents">
        {recentItems.map((r) => (
          <li key={`${r.collectionName}:${r.id}`}>{r.label}</li>
        ))}
      </ul>
      <ul data-testid="favorites">
        {favorites.map((f) => (
          <li key={f.key}>{f.label}</li>
        ))}
      </ul>
      <button
        onClick={() => addRecentItem({ id: 'rec-1', collectionName: 'titles', label: 'Added' })}
      >
        add
      </button>
      <button
        onClick={() =>
          toggleFavorite({
            key: 'collection:titles',
            label: 'Titles',
            type: 'collection',
            collectionName: 'titles',
          })
        }
      >
        fav
      </button>
      <button onClick={clearRecentItems}>clear</button>
    </div>
  )
}

function renderAt(path: string, { strict = false } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/:tenantSlug/*"
            element={
              <TenantProvider>
                <AppContextProvider>
                  <Consumer />
                </AppContextProvider>
              </TenantProvider>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
  const ui = strict ? <React.StrictMode>{tree}</React.StrictMode> : tree
  return render(ui)
}

const titlesFavorite: Favorite = {
  key: 'collection:titles',
  label: 'Titles',
  type: 'collection',
  collectionName: 'titles',
}

const recent = (id: string, label: string, collectionName = 'titles') => ({
  id,
  collectionName,
  label,
  timestamp: 1,
})

describe('AppContext tenant scoping', () => {
  let storage: Storage
  const originalStorage = window.localStorage

  beforeEach(() => {
    vi.clearAllMocks()
    storage = useMemoryLocalStorage()
    // No resolvable identity: the real store is localStorage-only, as before slice 1.
    mockPref = null
    mockAuthenticated = true
    mockIdentity = undefined
  })

  afterEach(() => {
    Object.defineProperty(window, 'localStorage', { value: originalStorage, configurable: true })
  })

  it('reads only the current workspace’s recents', () => {
    storage.setItem(
      recentItemsStorageKey('couchpicks'),
      JSON.stringify([recent('a', 'Graphic Desires')])
    )
    storage.setItem(
      recentItemsStorageKey('spotopened'),
      JSON.stringify([recent('b', 'Spot Watch', 'watches')])
    )

    renderAt('/spotopened/app/home')

    expect(screen.getByTestId('recents')).toHaveTextContent('Spot Watch')
    expect(screen.getByTestId('recents')).not.toHaveTextContent('Graphic Desires')
  })

  it('writes recents under the current workspace key only', () => {
    renderAt('/spotopened/app/home')

    act(() => screen.getByText('add').click())

    const spot = JSON.parse(storage.getItem(recentItemsStorageKey('spotopened')) ?? '[]')
    expect(spot).toHaveLength(1)
    expect(spot[0]).toMatchObject({ id: 'rec-1', collectionName: 'titles', label: 'Added' })
    expect(storage.getItem(recentItemsStorageKey('couchpicks'))).toBeNull()
    expect(storage.getItem('kelta_recent_items')).toBeNull()
  })

  it('drops legacy unscoped recents instead of surfacing them in every workspace', () => {
    storage.setItem('kelta_recent_items', JSON.stringify([recent('a', 'Graphic Desires')]))

    renderAt('/spotopened/app/home')

    expect(screen.getByTestId('recents')).toBeEmptyDOMElement()
    expect(storage.getItem('kelta_recent_items')).toBeNull()
  })

  it('ignores malformed entries and non-array payloads', () => {
    storage.setItem(
      recentItemsStorageKey('spotopened'),
      JSON.stringify([recent('ok', 'Good'), { id: 'no-label' }, 'junk', null])
    )
    storage.setItem(favoritesStorageKey('spotopened'), '{"not":"an array"}')

    renderAt('/spotopened/app/home')

    expect(screen.getByTestId('recents')).toHaveTextContent('Good')
    expect(screen.getByTestId('recents').children).toHaveLength(1)
    expect(screen.getByTestId('favorites')).toBeEmptyDOMElement()
  })

  it('clears only the current workspace’s recents', () => {
    storage.setItem(
      recentItemsStorageKey('couchpicks'),
      JSON.stringify([recent('a', 'Graphic Desires')])
    )
    storage.setItem(recentItemsStorageKey('spotopened'), JSON.stringify([recent('b', 'Mine')]))

    renderAt('/spotopened/app/home')
    act(() => screen.getByText('clear').click())

    expect(storage.getItem(recentItemsStorageKey('spotopened'))).toBe('[]')
    expect(JSON.parse(storage.getItem(recentItemsStorageKey('couchpicks')) ?? '[]')).toHaveLength(1)
  })

  it('adopts legacy favorites into the first workspace that loads them, once', () => {
    const legacy: Favorite[] = [
      { key: 'collection:titles', label: 'Titles', type: 'collection', collectionName: 'titles' },
    ]
    storage.setItem('kelta_favorites', JSON.stringify(legacy))

    renderAt('/spotopened/app/home')

    expect(screen.getByTestId('favorites')).toHaveTextContent('Titles')
    expect(storage.getItem('kelta_favorites')).toBeNull()
    expect(JSON.parse(storage.getItem(favoritesStorageKey('spotopened')) ?? '[]')).toEqual(legacy)
  })

  it('toggles favorites under the current workspace key', () => {
    renderAt('/couchpicks/app/home')

    act(() => screen.getByText('fav').click())
    expect(JSON.parse(storage.getItem(favoritesStorageKey('couchpicks')) ?? '[]')).toHaveLength(1)
    expect(storage.getItem(favoritesStorageKey('spotopened'))).toBeNull()

    act(() => screen.getByText('fav').click())
    expect(storage.getItem(favoritesStorageKey('couchpicks'))).toBe('[]')
  })
})

describe('AppContext server-backed favorites', () => {
  const originalStorage = window.localStorage
  let storage: Storage
  const watchlist: Favorite = {
    key: 'record:titles:t-1',
    label: 'Watchlist pick',
    type: 'record',
    collectionName: 'titles',
    recordId: 't-1',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    storage = useMemoryLocalStorage()
    mockPref = null
    mockAuthenticated = true
    mockIdentity = undefined
  })

  afterEach(() => {
    Object.defineProperty(window, 'localStorage', { value: originalStorage, configurable: true })
  })

  it('reads the favorites preference under the per-tenant local key', () => {
    mockPref = { value: null, isLoaded: false, save: vi.fn() }

    renderAt('/couchpicks/app/home')

    expect(usePreferenceValue).toHaveBeenCalledWith('favorites', 'default', {
      localKey: favoritesStorageKey('couchpicks'),
    })
  })

  it('replaces local favorites with the server value once it loads', async () => {
    storage.setItem(favoritesStorageKey('couchpicks'), JSON.stringify([titlesFavorite]))
    mockIdentity = { userId: 'user-1' }
    let answer: (rows: unknown[]) => void = () => {}
    mockApiClient.getList.mockReturnValue(new Promise((resolve) => (answer = resolve)))

    renderAt('/couchpicks/app/home')
    expect(screen.getByTestId('favorites')).toHaveTextContent('Titles')

    await act(async () =>
      answer([
        {
          id: 'row-1',
          userId: 'user-1',
          prefType: 'favorites',
          prefKey: 'default',
          value: [watchlist],
        },
      ])
    )

    await waitFor(() => expect(screen.getByTestId('favorites')).toHaveTextContent('Watchlist pick'))
    expect(screen.getByTestId('favorites')).not.toHaveTextContent('Titles')
    expect(JSON.parse(storage.getItem(favoritesStorageKey('couchpicks')) ?? '[]')).toEqual([
      watchlist,
    ])
    expect(mockApiClient.getList.mock.calls[0][0]).toContain('filter[prefType][eq]=favorites')
    expect(mockApiClient.postResource).not.toHaveBeenCalled()
    expect(mockApiClient.patchResource).not.toHaveBeenCalled()
  })

  it('pushes local favorites once when the server row is absent', async () => {
    storage.setItem(favoritesStorageKey('couchpicks'), JSON.stringify([titlesFavorite]))
    mockPref = { value: null, isLoaded: true, save: vi.fn() }

    renderAt('/couchpicks/app/home', { strict: true })

    await waitFor(() => expect(mockPref!.save).toHaveBeenCalledTimes(1))
    expect(mockPref.save).toHaveBeenCalledWith([titlesFavorite])
    expect(screen.getByTestId('favorites')).toHaveTextContent('Titles')
  })

  it('saves a toggle exactly once with the new array under StrictMode', async () => {
    mockPref = { value: [], isLoaded: true, save: vi.fn() }

    renderAt('/couchpicks/app/home', { strict: true })
    await waitFor(() => expect(screen.getByTestId('favorites')).toBeEmptyDOMElement())

    act(() => screen.getByText('fav').click())

    expect(mockPref.save).toHaveBeenCalledTimes(1)
    expect(mockPref.save).toHaveBeenCalledWith([titlesFavorite])
    expect(screen.getByTestId('favorites')).toHaveTextContent('Titles')
  })

  it('drops invalid server entries', async () => {
    mockPref = {
      value: [watchlist, { key: 'no-label', type: 'record' }, 'junk', null],
      isLoaded: true,
      save: vi.fn(),
    }

    renderAt('/couchpicks/app/home')

    await waitFor(() => expect(screen.getByTestId('favorites')).toHaveTextContent('Watchlist pick'))
    expect(screen.getByTestId('favorites').children).toHaveLength(1)
    expect(JSON.parse(storage.getItem(favoritesStorageKey('couchpicks')) ?? '[]')).toEqual([
      watchlist,
    ])
  })

  it('treats a non-array server value as no favorites', async () => {
    storage.setItem(favoritesStorageKey('couchpicks'), JSON.stringify([titlesFavorite]))
    mockPref = { value: { not: 'an array' }, isLoaded: true, save: vi.fn() }

    renderAt('/couchpicks/app/home')

    await waitFor(() => expect(screen.getByTestId('favorites')).toBeEmptyDOMElement())
  })

  it('keeps favorites in localStorage only when there is no identity', async () => {
    storage.setItem(favoritesStorageKey('couchpicks'), JSON.stringify([titlesFavorite]))

    renderAt('/couchpicks/app/home')
    expect(screen.getByTestId('favorites')).toHaveTextContent('Titles')

    act(() => screen.getByText('fav').click())
    await waitFor(() => expect(screen.getByTestId('favorites')).toBeEmptyDOMElement())
    expect(storage.getItem(favoritesStorageKey('couchpicks'))).toBe('[]')

    expect(mockApiClient.getList).not.toHaveBeenCalled()
    expect(mockApiClient.postResource).not.toHaveBeenCalled()
    expect(mockApiClient.patchResource).not.toHaveBeenCalled()
  })

  it('does not touch the preference store while signed out', () => {
    mockAuthenticated = false
    storage.setItem(favoritesStorageKey('couchpicks'), JSON.stringify([titlesFavorite]))

    renderAt('/couchpicks/app/home')
    act(() => screen.getByText('fav').click())

    expect(usePreferenceValue).not.toHaveBeenCalled()
    expect(storage.getItem(favoritesStorageKey('couchpicks'))).toBe('[]')
  })
})
