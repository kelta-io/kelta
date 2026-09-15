import { describe, it, expect } from 'vitest'
import {
  isSharedViewId,
  mapSharedListView,
  orderFieldsByView,
  SHARED_VIEW_PREFIX,
} from './listViewMapping'

describe('mapSharedListView', () => {
  it('maps an admin list-views row into a shared SavedView', () => {
    const view = mapSharedListView(
      {
        id: 'lv-1',
        name: 'Hot deals',
        columns: ['name', 'stage', 'amount'],
        filters: [{ id: 'f1', field: 'stage', operator: 'equals', value: 'hot' }],
        sortField: 'amount',
        sortDirection: 'DESC',
        rowLimit: 50,
        isDefault: true,
      },
      'opportunities'
    )
    expect(view.id).toBe(`${SHARED_VIEW_PREFIX}lv-1`)
    expect(isSharedViewId(view.id)).toBe(true)
    expect(view.visibleColumns).toEqual(['name', 'stage', 'amount'])
    expect(view.filters).toHaveLength(1)
    expect(view.sortDirection).toBe('desc')
    expect(view.pageSize).toBe(50)
    expect(view.isDefault).toBe(true)
  })

  it('parses JSON-string columns and drops non-conforming filters', () => {
    const view = mapSharedListView(
      {
        id: 'lv-2',
        name: 'Weird',
        columns: '["a","b"]',
        filters: [
          { fieldName: 'x', op: 'EQ' },
          { field: 'ok', operator: 'equals', value: '1' },
        ],
        rowLimit: 37,
      },
      'orders'
    )
    expect(view.visibleColumns).toEqual(['a', 'b'])
    expect(view.filters).toHaveLength(1)
    expect(view.filters[0].field).toBe('ok')
    // invalid rowLimit falls back to the default page size
    expect(view.pageSize).toBe(25)
  })
})

describe('mapSharedListView renderer (V196)', () => {
  const row = (extra: Record<string, unknown>) => ({ id: 'lv-1', name: 'Board', ...extra })

  it('maps a published KANBAN view onto the SavedView renderer fields', () => {
    const view = mapSharedListView(
      row({
        viewType: 'KANBAN',
        typeConfig: { kanban: { laneField: 'status', cardFields: ['title'] } },
      }),
      'projects'
    )
    expect(view.viewType).toBe('kanban')
    expect(view.typeConfig?.kanban).toEqual({ laneField: 'status', cardFields: ['title'] })
  })

  it('reads a typeConfig stored as a JSON string', () => {
    const view = mapSharedListView(
      row({ viewType: 'KANBAN', typeConfig: '{"kanban":{"laneField":"stage"}}' }),
      'projects'
    )
    expect(view.typeConfig?.kanban).toEqual({ laneField: 'stage', cardFields: undefined })
  })

  // undefined is what the page reads as 'table' (applyView), so "falls back" means
  // "returns undefined without throwing".
  it.each([[null], [''], ['TIMELINE'], ['timeline'], [42], [{ type: 'KANBAN' }]])(
    'falls back to the table renderer for viewType %p',
    (viewType) => {
      expect(mapSharedListView(row({ viewType }), 'projects').viewType).toBeUndefined()
    }
  )

  it('accepts a viewType regardless of case or surrounding space', () => {
    expect(mapSharedListView(row({ viewType: ' kanban ' }), 'p').viewType).toBe('kanban')
    expect(mapSharedListView(row({ viewType: 'Gallery' }), 'p').viewType).toBe('gallery')
  })

  it('drops renderer settings that do not match the shape', () => {
    const view = mapSharedListView(
      row({
        viewType: 'KANBAN',
        typeConfig: {
          kanban: { laneField: 42, cardFields: 'title' },
          calendar: { dateField: '' },
          gallery: { imageField: 'cover', cardFields: ['a', 7] },
        },
      }),
      'projects'
    )
    // a kanban section without a usable laneField is dropped entirely — the page
    // then resolves the lane field from the schema instead of half-applying one
    expect(view.typeConfig?.kanban).toBeUndefined()
    expect(view.typeConfig?.calendar).toBeUndefined()
    expect(view.typeConfig?.gallery).toEqual({
      imageField: 'cover',
      titleField: undefined,
      cardFields: ['a'],
    })
  })

  it('leaves a pre-V196 row (no viewType/typeConfig) rendering as today', () => {
    const view = mapSharedListView(row({ columns: ['a'] }), 'projects')
    expect(view.viewType).toBeUndefined()
    expect(view.typeConfig).toBeUndefined()
  })

  it('drops a typeConfig that is not an object', () => {
    expect(mapSharedListView(row({ typeConfig: 'not json' }), 'p').typeConfig).toBeUndefined()
    expect(mapSharedListView(row({ typeConfig: ['kanban'] }), 'p').typeConfig).toBeUndefined()
    expect(mapSharedListView(row({ typeConfig: 7 }), 'p').typeConfig).toBeUndefined()
  })
})

describe('orderFieldsByView', () => {
  const fields = [{ name: 'a' }, { name: 'b' }, { name: 'c' }]

  it('orders and filters fields by the view columns', () => {
    expect(orderFieldsByView(fields, ['c', 'a', 'missing'])).toEqual([{ name: 'c' }, { name: 'a' }])
  })

  it('returns null when the view has no columns (caller falls back to first-6)', () => {
    expect(orderFieldsByView(fields, [])).toBeNull()
    expect(orderFieldsByView(fields, ['nope'])).toBeNull()
  })
})
