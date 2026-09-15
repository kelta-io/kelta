/**
 * FilterBar Component
 *
 * Displays active filter conditions as dismissible badges.
 * Shows when the list view has URL-persisted filter conditions.
 * Users can remove individual filters or clear all at once.
 */

import React from 'react'
import { X, Filter } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { FilterCondition } from '@/hooks/useCollectionRecords'

export interface FilterBarProps {
  /** Active filter conditions */
  filters: FilterCondition[]
  /** Callback to remove a single filter by ID */
  onRemoveFilter: (filterId: string) => void
  /** Callback to clear all filters */
  onClearAll: () => void
  /**
   * Field name → raw value→`{label, color}` (see `usePicklistDisplayMap`) for picklist/
   * multi_picklist fields being filtered on. The badge shows the authored label; the
   * filter itself (and thus the query) keeps comparing on the raw stored value.
   */
  picklistDisplayMaps?: Record<string, Map<string, { label: string; color?: string }>>
}

/** Resolve the display text for one filter's value: authored label if the field is a mapped picklist. */
function formatFilterValue(
  filter: FilterCondition,
  picklistDisplayMaps?: Record<string, Map<string, { label: string; color?: string }>>
): string {
  const displayMap = picklistDisplayMaps?.[filter.field]
  if (!displayMap) return filter.value
  return displayMap.get(filter.value)?.label || filter.value
}

/**
 * Format a filter operator for display.
 */
function formatOperator(operator: string): string {
  const labels: Record<string, string> = {
    equals: '=',
    not_equals: '≠',
    contains: 'contains',
    starts_with: 'starts with',
    ends_with: 'ends with',
    greater_than: '>',
    less_than: '<',
    greater_than_or_equal: '≥',
    less_than_or_equal: '≤',
  }
  return labels[operator] || operator
}

export function FilterBar({
  filters,
  onRemoveFilter,
  onClearAll,
  picklistDisplayMaps,
}: FilterBarProps): React.ReactElement | null {
  if (filters.length === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/30 px-3 py-2">
      <Filter className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
      <span className="text-xs font-medium text-muted-foreground">Filters:</span>

      {filters.map((filter) => (
        <Badge key={filter.id} variant="secondary" className="gap-1 pr-1 text-xs">
          <span className="font-medium">{filter.field}</span>
          <span className="text-muted-foreground">{formatOperator(filter.operator)}</span>
          <span>{formatFilterValue(filter, picklistDisplayMaps)}</span>
          <button
            type="button"
            onClick={() => onRemoveFilter(filter.id)}
            className="ml-0.5 rounded-sm p-0.5 hover:bg-muted"
            aria-label={`Remove filter: ${filter.field} ${formatOperator(filter.operator)} ${filter.value}`}
          >
            <X className="h-3 w-3" />
          </button>
        </Badge>
      ))}

      {filters.length > 1 && (
        <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={onClearAll}>
          Clear all
        </Button>
      )}
    </div>
  )
}
