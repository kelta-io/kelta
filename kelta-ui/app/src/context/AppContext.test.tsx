/**
 * AppContext Unit Tests
 *
 * Recent items and favorites are keyed by workspace: a browser signed in to
 * two tenants on the same origin must never show one tenant's records in the
 * other's "Recent items" card.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
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

function renderAt(path: string) {
  return render(
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
  )
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
    storage = useMemoryLocalStorage()
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
