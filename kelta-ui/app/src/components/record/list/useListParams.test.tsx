/**
 * `useListParams` — where a record list keeps its state: the URL (`'sync'`) or a local copy
 * seeded from it (`'seed'`). Both read and write the `listUrlState` param format.
 */
import { describe, it, expect } from 'vitest'
import React from 'react'
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { applyListParamUpdates, useListParams, type ListUrlStateMode } from './useListParams'

describe('applyListParamUpdates', () => {
  it('sets and deletes params without touching the original', () => {
    const prev = new URLSearchParams('sort=title&view=v1')
    const next = applyListParamUpdates(prev, { sort: '-title', view: undefined, page: '3' })
    expect(next.toString()).toBe('sort=-title&page=3')
    expect(prev.toString()).toBe('sort=title&view=v1')
  })

  it('drops the default page and page size', () => {
    const next = applyListParamUpdates(new URLSearchParams('page=4&pageSize=50'), {
      page: '1',
      pageSize: '25',
    })
    expect(next.toString()).toBe('')
  })
})

function renderParams(mode: ListUrlStateMode, initialUrl: string) {
  return renderHook(() => ({ list: useListParams(mode), location: useLocation() }), {
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <MemoryRouter initialEntries={[initialUrl]}>{children}</MemoryRouter>
    ),
  })
}

const FILTER = JSON.stringify([{ id: 'f1', field: 'status', operator: 'equals', value: 'open' }])

describe('useListParams', () => {
  it('reads the list state from the URL in either mode', () => {
    for (const mode of ['sync', 'seed'] as const) {
      const { result } = renderParams(
        mode,
        `/acme/app/o/projects?page=2&sort=-title&filter=${encodeURIComponent(FILTER)}`
      )
      expect(result.current.list.params.page).toBe(2)
      expect(result.current.list.params.sorts).toEqual([{ field: 'title', direction: 'desc' }])
      expect(result.current.list.params.filters).toHaveLength(1)
    }
  })

  it("'sync' writes updates to the URL", () => {
    const { result } = renderParams('sync', '/acme/app/o/projects?page=2')
    act(() => result.current.list.update({ sort: 'title', page: undefined }))

    expect(result.current.location.search).toBe('?sort=title')
    expect(result.current.list.params.sort).toEqual({ field: 'title', direction: 'asc' })
    expect(result.current.list.params.page).toBe(1)
  })

  it("'seed' keeps updates local and never writes the URL", () => {
    const { result } = renderParams('seed', '/acme/resources/projects?page=2')
    act(() => result.current.list.update({ sort: 'title', page: undefined }))

    expect(result.current.location.search).toBe('?page=2')
    expect(result.current.list.params.sort).toEqual({ field: 'title', direction: 'asc' })
    expect(result.current.list.params.page).toBe(1)
    expect(result.current.list.searchParams.toString()).toBe('sort=title')
  })
})
