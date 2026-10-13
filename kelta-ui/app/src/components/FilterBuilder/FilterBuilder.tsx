/**
 * FilterBuilder
 *
 * Visual editor for a LayoutFilter expression: an AND/OR group of
 * { field, op, value } clauses. Used to build the LIST-layout default filter
 * and the layout-assignment condition. Stores no state of its own — fully
 * controlled by the parent.
 */

import React, { useCallback, useMemo } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

export type FilterOperator =
  | 'equals'
  | 'not_equals'
  | 'contains'
  | 'starts_with'
  | 'ends_with'
  | 'gt'
  | 'lt'
  | 'gte'
  | 'lte'
  | 'is_null'
  | 'is_not_null'

export interface FilterClause {
  field: string
  op: FilterOperator
  value?: unknown
}

export interface FilterExpression {
  logic: 'AND' | 'OR'
  filters: FilterClause[]
}

export interface FieldOption {
  name: string
  displayName: string
  /** Optional field UUID — carried by callers that need it but unused by the filter UI. */
  id?: string
}

const OPERATOR_LABELS: Record<FilterOperator, string> = {
  equals: 'equals',
  not_equals: 'not equals',
  contains: 'contains',
  starts_with: 'starts with',
  ends_with: 'ends with',
  gt: '>',
  lt: '<',
  gte: '>=',
  lte: '<=',
  is_null: 'is empty',
  is_not_null: 'is not empty',
}

/** Every operator, in menu order (the default offer). */
const ALL_OPERATORS = Object.keys(OPERATOR_LABELS) as FilterOperator[]

function opTakesValue(op: FilterOperator): boolean {
  return op !== 'is_null' && op !== 'is_not_null'
}

export interface FilterBuilderProps {
  value: FilterExpression | null
  onChange: (next: FilterExpression | null) => void
  fields: FieldOption[]
  /** Optional id prefix to namespace input ids. */
  idPrefix?: string
  /**
   * The operators offered (default: all). A consumer whose filter grammar has no equivalent for
   * some operator passes the subset it can express.
   */
  operators?: readonly FilterOperator[]
  /**
   * Offer the ALL / ANY switch (default true). A consumer that can only AND its clauses passes
   * false; the expression it receives then always has `logic: 'AND'`.
   */
  allowOr?: boolean
}

export function FilterBuilder({
  value,
  onChange,
  fields,
  idPrefix = 'filter',
  operators = ALL_OPERATORS,
  allowOr = true,
}: FilterBuilderProps): React.ReactElement {
  const expr: FilterExpression = useMemo(() => value ?? { logic: 'AND', filters: [] }, [value])

  const emit = useCallback(
    (next: FilterExpression) => {
      if (next.filters.length === 0) {
        onChange(null)
      } else {
        onChange(next)
      }
    },
    [onChange]
  )

  const addClause = useCallback(() => {
    const defaultField = fields[0]?.name ?? ''
    emit({
      ...expr,
      filters: [...expr.filters, { field: defaultField, op: 'equals', value: '' }],
    })
  }, [emit, expr, fields])

  const removeClause = useCallback(
    (index: number) => {
      emit({ ...expr, filters: expr.filters.filter((_, i) => i !== index) })
    },
    [emit, expr]
  )

  const updateClause = useCallback(
    (index: number, patch: Partial<FilterClause>) => {
      emit({
        ...expr,
        filters: expr.filters.map((c, i) => (i === index ? { ...c, ...patch } : c)),
      })
    },
    [emit, expr]
  )

  const setLogic = useCallback((logic: 'AND' | 'OR') => emit({ ...expr, logic }), [emit, expr])

  return (
    <div className="flex flex-col gap-2" data-testid="filter-builder">
      {allowOr && expr.filters.length > 1 && (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">Match</span>
          <Select value={expr.logic} onValueChange={(v) => setLogic(v as 'AND' | 'OR')}>
            <SelectTrigger className="h-8 w-20" data-testid={`${idPrefix}-logic`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="AND">ALL</SelectItem>
              <SelectItem value="OR">ANY</SelectItem>
            </SelectContent>
          </Select>
          <span className="text-muted-foreground">of the following</span>
        </div>
      )}

      {expr.filters.map((clause, i) => (
        <div
          key={i}
          className="flex flex-wrap items-center gap-2"
          data-testid={`${idPrefix}-clause-${i}`}
        >
          <Select value={clause.field} onValueChange={(v) => updateClause(i, { field: v })}>
            <SelectTrigger className="h-8 w-48" data-testid={`${idPrefix}-field-${i}`}>
              <SelectValue placeholder="Field" />
            </SelectTrigger>
            <SelectContent>
              {fields.map((f) => (
                <SelectItem key={f.name} value={f.name}>
                  {f.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            value={clause.op}
            onValueChange={(v) =>
              updateClause(i, {
                op: v as FilterOperator,
                value: opTakesValue(v as FilterOperator) ? (clause.value ?? '') : undefined,
              })
            }
          >
            <SelectTrigger className="h-8 w-36" data-testid={`${idPrefix}-op-${i}`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {operators.map((op) => (
                <SelectItem key={op} value={op}>
                  {OPERATOR_LABELS[op]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {opTakesValue(clause.op) && (
            <Input
              className="h-8 w-48"
              value={typeof clause.value === 'string' ? clause.value : String(clause.value ?? '')}
              onChange={(e) => updateClause(i, { value: e.target.value })}
              data-testid={`${idPrefix}-value-${i}`}
            />
          )}

          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="Remove filter"
            onClick={() => removeClause(i)}
            data-testid={`${idPrefix}-remove-${i}`}
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      ))}

      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={addClause}
          disabled={fields.length === 0}
          data-testid={`${idPrefix}-add`}
        >
          <Plus className="mr-1 h-4 w-4" />
          Add filter
        </Button>
      </div>
    </div>
  )
}
