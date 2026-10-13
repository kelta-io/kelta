import { describe, it, expect } from 'vitest'
import {
  LIST_BUILDER_OPERATORS,
  LIST_FILTER_BUILDER_ALLOWS_OR,
  expressionToListFilters,
  listFiltersToExpression,
  toListOperator,
} from './listFilterMapping'

describe('listFilterMapping', () => {
  it('maps every comparison operator onto the list operator names', () => {
    expect(
      (['equals', 'not_equals', 'contains', 'starts_with', 'ends_with'] as const).map(
        toListOperator
      )
    ).toEqual(['equals', 'not_equals', 'contains', 'starts_with', 'ends_with'])
    expect((['gt', 'lt', 'gte', 'lte'] as const).map(toListOperator)).toEqual([
      'greater_than',
      'less_than',
      'greater_than_or_equal',
      'less_than_or_equal',
    ])
  })

  it('has no list equivalent for is_null / is_not_null, so the builder does not offer them', () => {
    expect(toListOperator('is_null')).toBeNull()
    expect(toListOperator('is_not_null')).toBeNull()
    expect(LIST_BUILDER_OPERATORS).not.toContain('is_null')
    expect(LIST_BUILDER_OPERATORS).not.toContain('is_not_null')
    expect(LIST_BUILDER_OPERATORS).toHaveLength(9)
  })

  it('is AND-only', () => {
    expect(LIST_FILTER_BUILDER_ALLOWS_OR).toBe(false)
  })

  it('turns an expression into list conditions with positional ids', () => {
    expect(
      expressionToListFilters({
        logic: 'AND',
        filters: [
          { field: 'amount', op: 'gte', value: 10 },
          { field: 'title', op: 'contains', value: ' acme ' },
        ],
      })
    ).toEqual([
      { id: 'f1', field: 'amount', operator: 'greater_than_or_equal', value: '10' },
      { id: 'f2', field: 'title', operator: 'contains', value: 'acme' },
    ])
  })

  it('drops clauses without a value, without a field, or with an operator the list lacks', () => {
    expect(
      expressionToListFilters({
        logic: 'AND',
        filters: [
          { field: 'title', op: 'equals', value: '' },
          { field: 'title', op: 'equals', value: '   ' },
          { field: '', op: 'equals', value: 'x' },
          { field: 'title', op: 'is_null' },
          { field: 'status', op: 'not_equals', value: 'done' },
        ],
      })
    ).toEqual([{ id: 'f1', field: 'status', operator: 'not_equals', value: 'done' }])
    expect(expressionToListFilters(null)).toEqual([])
  })

  it('turns applied list conditions back into an AND expression', () => {
    expect(listFiltersToExpression([])).toBeNull()
    expect(
      listFiltersToExpression([
        { id: 'x', field: 'amount', operator: 'less_than', value: '5' },
        { id: 'y', field: 'status', operator: 'equals', value: 'open' },
      ])
    ).toEqual({
      logic: 'AND',
      filters: [
        { field: 'amount', op: 'lt', value: '5' },
        { field: 'status', op: 'equals', value: 'open' },
      ],
    })
  })

  it('round-trips list conditions (ids renumbered)', () => {
    const applied = [
      { id: 'a', field: 'amount', operator: 'greater_than' as const, value: '1' },
      { id: 'b', field: 'title', operator: 'ends_with' as const, value: 'Ltd' },
    ]
    expect(expressionToListFilters(listFiltersToExpression(applied))).toEqual([
      { ...applied[0], id: 'f1' },
      { ...applied[1], id: 'f2' },
    ])
  })
})
