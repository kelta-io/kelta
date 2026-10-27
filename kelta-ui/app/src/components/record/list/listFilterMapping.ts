/**
 * Mapping between the app's `FilterBuilder` expression (`{ logic, filters: [{ field, op, value }] }`,
 * operators `gt` / `lt` / …) and the record list's filter conditions (`FilterCondition[]`,
 * operators `greater_than` / `less_than` / …) — the shape the list URL (`filter=`), the filter
 * chips, saved views and `useCollectionRecords` all carry.
 *
 * The list's conditions are a conjunction: the collection read grammar
 * (`filter[<field>][<op>]=<value>`) ANDs every condition and has no OR, and the URL / saved-view
 * format is a flat array. So the list builder is AND-only (`LIST_FILTER_BUILDER_ALLOWS_OR`) and
 * offers only the operators that map ({@link LIST_BUILDER_OPERATORS}); `is_null` /
 * `is_not_null` have no list equivalent and are not offered.
 */
import type {
  FilterClause,
  FilterExpression,
  FilterOperator as BuilderOperator,
} from '@/components/FilterBuilder'
import type { FilterCondition, FilterOperator as ListOperator } from '@/hooks/useCollectionRecords'

/** Builder operator → list operator, for every operator the list can express. */
const BUILDER_TO_LIST: Partial<Record<BuilderOperator, ListOperator>> = {
  equals: 'equals',
  not_equals: 'not_equals',
  contains: 'contains',
  starts_with: 'starts_with',
  ends_with: 'ends_with',
  gt: 'greater_than',
  lt: 'less_than',
  gte: 'greater_than_or_equal',
  lte: 'less_than_or_equal',
}

const LIST_TO_BUILDER: Record<ListOperator, BuilderOperator> = {
  equals: 'equals',
  not_equals: 'not_equals',
  contains: 'contains',
  starts_with: 'starts_with',
  ends_with: 'ends_with',
  greater_than: 'gt',
  less_than: 'lt',
  greater_than_or_equal: 'gte',
  less_than_or_equal: 'lte',
}

/** The builder operators a record list can apply, in the builder's menu order. */
export const LIST_BUILDER_OPERATORS: readonly BuilderOperator[] = Object.keys(
  BUILDER_TO_LIST
) as BuilderOperator[]

/** The list ANDs its conditions — see the module comment. */
export const LIST_FILTER_BUILDER_ALLOWS_OR = false

/** The list operator for a builder operator, or `null` when the list cannot express it. */
export function toListOperator(op: BuilderOperator): ListOperator | null {
  return BUILDER_TO_LIST[op] ?? null
}

/** The builder's view of the list's applied conditions (`null` when there are none). */
export function listFiltersToExpression(filters: FilterCondition[]): FilterExpression | null {
  if (filters.length === 0) return null
  return {
    logic: 'AND',
    filters: filters.map((f): FilterClause => ({
      field: f.field,
      op: LIST_TO_BUILDER[f.operator] ?? 'equals',
      value: f.value,
    })),
  }
}

/**
 * The list conditions for a builder expression. Clauses without a field or a value, and clauses
 * whose operator the list cannot express, are dropped (an empty value would filter on `''`).
 * Ids are positional (`f1`, `f2`, …) — the format `buildListUrl` writes.
 */
export function expressionToListFilters(expr: FilterExpression | null): FilterCondition[] {
  if (!expr) return []
  const conditions: FilterCondition[] = []
  for (const clause of expr.filters) {
    const operator = toListOperator(clause.op)
    const value = clause.value == null ? '' : String(clause.value).trim()
    if (!clause.field || !operator || value === '') continue
    conditions.push({ id: `f${conditions.length + 1}`, field: clause.field, operator, value })
  }
  return conditions
}
