import { describe, it, expect } from 'vitest'
import {
  buildListUrl,
  buildSortParam,
  parseFilters,
  parseListViewParams,
  parseSortParam,
} from './listUrlState'

describe('listUrlState', () => {
  it('parses defaults from empty params', () => {
    const state = parseListViewParams(new URLSearchParams())
    expect(state).toEqual({
      page: 1,
      pageSize: 25,
      sort: undefined,
      sorts: [],
      filters: [],
      viewId: null,
    })
  })

  it('parses page, pageSize, sort direction, and filters', () => {
    const params = new URLSearchParams(
      'page=3&pageSize=50&sort=-createdAt&filter=' +
        encodeURIComponent(
          JSON.stringify([{ id: 'f1', field: 'status', operator: 'equals', value: 'active' }])
        )
    )
    const state = parseListViewParams(params)
    expect(state.page).toBe(3)
    expect(state.pageSize).toBe(50)
    expect(state.sort).toEqual({ field: 'createdAt', direction: 'desc' })
    expect(state.filters).toEqual([
      { id: 'f1', field: 'status', operator: 'equals', value: 'active' },
    ])
  })

  it('rejects malformed filters and invalid page sizes', () => {
    const params = new URLSearchParams('pageSize=37&filter=not-json')
    const state = parseListViewParams(params)
    expect(state.pageSize).toBe(25)
    expect(state.filters).toEqual([])
    expect(parseFilters('[{"nope":1}]')).toEqual([])
  })

  it('buildListUrl round-trips through parseListViewParams', () => {
    const url = buildListUrl('acme', 'orders', [
      { field: 'status', operator: 'equals', value: 'open' },
    ])
    expect(url.startsWith('/acme/app/o/orders?')).toBe(true)
    const params = new URLSearchParams(url.split('?')[1])
    const state = parseListViewParams(params)
    expect(state.filters).toEqual([
      { id: 'f1', field: 'status', operator: 'equals', value: 'open' },
    ])
  })

  it('buildListUrl omits the query string with no filters and encodes sort', () => {
    expect(buildListUrl('acme', 'orders', [])).toBe('/acme/app/o/orders')
    const url = buildListUrl('acme', 'orders', [], { field: 'total', direction: 'desc' })
    expect(url).toBe('/acme/app/o/orders?sort=-total')
  })
})

describe('multi-sort grammar (app-data-entry slice 2)', () => {
  it('parses the comma grammar into ordered levels', () => {
    expect(parseSortParam('stage,-amount, name')).toEqual([
      { field: 'stage', direction: 'asc' },
      { field: 'amount', direction: 'desc' },
      { field: 'name', direction: 'asc' },
    ])
    expect(parseSortParam(null)).toEqual([])
    expect(parseSortParam('-')).toEqual([])
  })

  it('round-trips through buildSortParam', () => {
    const sorts = parseSortParam('a,-b')
    expect(buildSortParam(sorts)).toBe('a,-b')
    expect(buildSortParam([])).toBeUndefined()
  })

  it('parseListViewParams exposes sorts, first-level sort, and the view id', () => {
    const state = parseListViewParams(new URLSearchParams('sort=a,-b&view=v42'))
    expect(state.sorts).toHaveLength(2)
    expect(state.sort).toEqual({ field: 'a', direction: 'asc' })
    expect(state.viewId).toBe('v42')
  })
})
