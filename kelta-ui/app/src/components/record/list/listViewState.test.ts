import { describe, it, expect } from 'vitest'
import {
  accessibleListFields,
  massEditableFields,
  nextDensity,
  nextSortParam,
  resolveCalendarDateField,
  resolveGalleryImageField,
  resolveKanbanCardFields,
  resolveKanbanLaneField,
  resolveTitleField,
  resolveVisibleFields,
  withCalendarRange,
  withGroupSort,
} from './listViewState'

const f = (name: string, type = 'string') => ({ name, type })

describe('accessibleListFields', () => {
  it('drops the audit fields and FLS-hidden fields', () => {
    const fields = [f('title'), f('createdAt', 'datetime'), f('secret'), f('updatedBy', 'lookup')]
    expect(accessibleListFields(fields, (n) => n !== 'secret').map((x) => x.name)).toEqual([
      'title',
    ])
  })
})

describe('resolveVisibleFields', () => {
  const accessible = ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((n) => f(n))

  it('defaults to the first six accessible fields', () => {
    expect(resolveVisibleFields(accessible, null, null).map((x) => x.name)).toEqual([
      'a',
      'b',
      'c',
      'd',
      'e',
      'f',
    ])
  })

  it("uses the active view's columns, in the view's order", () => {
    expect(resolveVisibleFields(accessible, null, ['g', 'a']).map((x) => x.name)).toEqual([
      'g',
      'a',
    ])
  })

  it('lets the column chooser override the view', () => {
    expect(resolveVisibleFields(accessible, ['c'], ['g', 'a']).map((x) => x.name)).toEqual(['c'])
  })

  it('falls through when the named columns no longer exist', () => {
    expect(resolveVisibleFields(accessible, ['gone'], ['b']).map((x) => x.name)).toEqual(['b'])
    expect(resolveVisibleFields(accessible, null, ['gone'])).toHaveLength(6)
  })

  it('is empty when nothing is accessible', () => {
    expect(resolveVisibleFields([], ['a'], ['a'])).toEqual([])
  })
})

describe('alternate-view field resolution', () => {
  const fields = [
    f('title'),
    f('stage', 'picklist'),
    f('status', 'picklist'),
    f('due', 'date'),
    f('startsAt', 'datetime'),
    f('photo', 'url'),
  ]

  it('binds kanban lanes to the configured picklist, else the first one', () => {
    expect(resolveKanbanLaneField(fields, { kanban: { laneField: 'status' } })?.name).toBe('status')
    expect(resolveKanbanLaneField(fields, null)?.name).toBe('stage')
    // a configured field that is not a picklist is ignored
    expect(resolveKanbanLaneField(fields, { kanban: { laneField: 'title' } })?.name).toBe('stage')
    expect(resolveKanbanLaneField([f('title')], null)).toBeNull()
  })

  it('binds the calendar to the configured date field, else the first date/datetime', () => {
    expect(resolveCalendarDateField(fields, { calendar: { dateField: 'startsAt' } })?.name).toBe(
      'startsAt'
    )
    expect(resolveCalendarDateField(fields, undefined)?.name).toBe('due')
    expect(resolveCalendarDateField([f('title')], null)).toBeNull()
  })

  it('shows a gallery image only for a configured url field', () => {
    expect(resolveGalleryImageField(fields, null)).toBeUndefined()
    expect(resolveGalleryImageField(fields, { gallery: { imageField: 'photo' } })?.name).toBe(
      'photo'
    )
    expect(resolveGalleryImageField(fields, { gallery: { imageField: 'title' } })).toBeUndefined()
  })

  it('titles cards by the gallery title, else the display field, else name', () => {
    expect(resolveTitleField({ gallery: { titleField: 'code' } }, 'title')).toBe('code')
    expect(resolveTitleField(null, 'title')).toBe('title')
    expect(resolveTitleField(null, undefined)).toBe('name')
  })

  it('fills kanban cards from the configured fields, else the visible non-title columns', () => {
    const visible = [f('title'), f('stage', 'picklist'), f('due', 'date'), f('photo', 'url')]
    expect(resolveKanbanCardFields(fields, visible, 'title', null).map((x) => x.name)).toEqual([
      'stage',
      'due',
      'photo',
    ])
    expect(
      resolveKanbanCardFields(fields, visible, 'title', {
        kanban: { laneField: 'stage', cardFields: ['due', 'missing', 'title'] },
      }).map((x) => x.name)
    ).toEqual(['due', 'title'])
  })
})

describe('massEditableFields', () => {
  it('offers editable schema fields only', () => {
    const fields = [f('id'), f('title'), f('createdAt', 'datetime'), f('total', 'formula')]
    expect(massEditableFields(fields).map((x) => x.name)).toEqual(['title'])
  })
})

describe('withGroupSort', () => {
  it('prepends the group field ascending and drops its own level', () => {
    expect(
      withGroupSort(
        [
          { field: 'status', direction: 'desc' },
          { field: 'title', direction: 'asc' },
        ],
        'status'
      )
    ).toEqual([
      { field: 'status', direction: 'asc' },
      { field: 'title', direction: 'asc' },
    ])
  })

  it('leaves the sort alone without grouping', () => {
    const sorts = [{ field: 'title', direction: 'asc' as const }]
    expect(withGroupSort(sorts, null)).toBe(sorts)
  })
})

describe('withCalendarRange', () => {
  it("adds the month's bounds on the date field after the user's filters", () => {
    const user = [{ id: 'u', field: 'status', operator: 'equals' as const, value: 'open' }]
    const merged = withCalendarRange(user, 'due', '2026-02')
    expect(merged[0]).toBe(user[0])
    expect(merged.slice(1).map((c) => [c.id, c.field, c.operator])).toEqual([
      ['__calendar_gte', 'due', 'greater_than_or_equal'],
      ['__calendar_lte', 'due', 'less_than_or_equal'],
    ])
    expect(merged[1].value.startsWith('2026-02-01')).toBe(true)
    expect(merged[2].value.startsWith('2026-02-28')).toBe(true)
  })

  it('adds nothing without a date field', () => {
    expect(withCalendarRange([], null, '2026-02')).toEqual([])
  })
})

describe('nextSortParam', () => {
  it('cycles a single column asc → desc → none on a plain click', () => {
    expect(nextSortParam([], 'title', false)).toBe('title')
    expect(nextSortParam([{ field: 'title', direction: 'asc' }], 'title', false)).toBe('-title')
    expect(nextSortParam([{ field: 'title', direction: 'desc' }], 'title', false)).toBeUndefined()
  })

  it('replaces a multi-level sort with the clicked column on a plain click', () => {
    expect(
      nextSortParam(
        [
          { field: 'a', direction: 'asc' },
          { field: 'b', direction: 'asc' },
        ],
        'a',
        false
      )
    ).toBe('a')
  })

  it('appends, flips and removes levels on shift-click', () => {
    const one = [{ field: 'a', direction: 'asc' as const }]
    expect(nextSortParam(one, 'b', true)).toBe('a,b')
    expect(
      nextSortParam(
        [
          { field: 'a', direction: 'asc' },
          { field: 'b', direction: 'asc' },
        ],
        'b',
        true
      )
    ).toBe('a,-b')
    expect(
      nextSortParam(
        [
          { field: 'a', direction: 'asc' },
          { field: 'b', direction: 'desc' },
        ],
        'b',
        true
      )
    ).toBe('a')
    expect(nextSortParam([{ field: 'a', direction: 'desc' }], 'a', true)).toBeUndefined()
  })
})

describe('nextDensity', () => {
  it('cycles normal → compact → comfortable → normal', () => {
    expect(nextDensity('normal')).toBe('compact')
    expect(nextDensity('compact')).toBe('comfortable')
    expect(nextDensity('comfortable')).toBe('normal')
  })
})
