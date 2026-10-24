/**
 * SummaryStatsBar Component
 *
 * Displays a summary bar above data tables showing record count,
 * filter description, and optional field aggregates (sums for numeric fields).
 *
 * Features:
 * - Total and filtered record counts
 * - Human-readable filter description with operator labels
 * - Client-side sum aggregates for numeric fields
 * - Clear filters action
 * - Locale-aware number formatting
 * - Internationalization via I18n context
 */

import React, { useMemo } from 'react'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'

/**
 * A single filter condition applied to the data
 */
interface FilterCondition {
  id: string
  field: string
  operator: string
  value: string
}

/**
 * Definition of a field in the collection
 */
interface FieldDefinition {
  name: string
  displayName?: string
  type: string
}

/**
 * Props for the SummaryStatsBar component
 */
export interface SummaryStatsBarProps {
  /** Total number of records in the collection */
  totalCount: number
  /** Number of records after filters are applied */
  filteredCount: number
  /** Active filter conditions */
  filters: FilterCondition[]
  /** Fields currently visible in the table */
  visibleFields: FieldDefinition[]
  /** Currently visible page of records */
  records: Array<Record<string, unknown>>
  /** Callback when the clear filters link is clicked */
  onClearFilters: () => void
}

/**
 * Map of filter operators to human-readable labels
 */
const OPERATOR_LABELS: Record<string, string> = {
  equals: '=',
  not_equals: '\u2260',
  contains: 'contains',
  starts_with: 'starts with',
  ends_with: 'ends with',
  greater_than: '>',
  less_than: '<',
  greater_than_or_equal: '\u2265',
  less_than_or_equal: '\u2264',
}

/**
 * Numeric field types that support aggregation
 */
const NUMERIC_FIELD_TYPES = new Set([
  'number',
  'integer',
  'long',
  'double',
  'float',
  'decimal',
  'currency',
  'percent',
])

/**
 * Build a human-readable filter description from filter conditions.
 *
 * Converts filters like:
 *   [{ field: 'Status', operator: 'equals', value: 'Active' }]
 * into:
 *   "Status = Active"
 */
function buildFilterDescription(filters: FilterCondition[]): string {
  if (filters.length === 0) {
    return ''
  }

  return filters
    .map((filter) => {
      const operatorLabel = OPERATOR_LABELS[filter.operator] || filter.operator
      return `${filter.field} ${operatorLabel} ${filter.value}`
    })
    .join(' AND ')
}

/**
 * Compute sum aggregates for numeric fields from the visible records.
 *
 * Returns an array of { label, value } for each numeric field that has
 * at least one numeric value in the records.
 */
function computeAggregates(
  visibleFields: FieldDefinition[],
  records: Array<Record<string, unknown>>
): Array<{ label: string; sum: number }> {
  if (records.length === 0) {
    return []
  }

  const numericFields = visibleFields.filter((field) =>
    NUMERIC_FIELD_TYPES.has(field.type.toLowerCase())
  )

  const aggregates: Array<{ label: string; sum: number }> = []

  for (const field of numericFields) {
    let sum = 0
    let hasNumericValue = false

    for (const record of records) {
      const rawValue = record[field.name]
      if (rawValue === null || rawValue === undefined || rawValue === '') {
        continue
      }
      const numValue = typeof rawValue === 'number' ? rawValue : Number(rawValue)
      if (!isNaN(numValue)) {
        sum += numValue
        hasNumericValue = true
      }
    }

    if (hasNumericValue) {
      aggregates.push({
        label: field.displayName || field.name,
        sum,
      })
    }
  }

  return aggregates
}

/**
 * SummaryStatsBar Component
 *
 * Renders a compact bar with record counts, filter descriptions,
 * and optional numeric field aggregates.
 *
 * @example
 * ```tsx
 * <SummaryStatsBar
 *   totalCount={150}
 *   filteredCount={42}
 *   filters={[{ id: '1', field: 'Status', operator: 'equals', value: 'Active' }]}
 *   visibleFields={[{ name: 'amount', displayName: 'Amount', type: 'number' }]}
 *   records={records}
 *   onClearFilters={() => clearFilters()}
 * />
 * ```
 */
export function SummaryStatsBar({
  totalCount,
  filteredCount,
  filters,
  visibleFields,
  records,
  onClearFilters,
}: SummaryStatsBarProps): React.ReactElement {
  const { t } = useI18n()

  const hasActiveFilters = filters.length > 0

  const filterDescription = useMemo(() => buildFilterDescription(filters), [filters])

  const aggregates = useMemo(
    () => computeAggregates(visibleFields, records),
    [visibleFields, records]
  )

  return (
    <div
      className={cn(
        'flex justify-between items-center flex-wrap gap-4',
        'px-4 py-2 bg-muted/50 border border-border/50 rounded-md mb-4',
        'text-sm text-foreground',
        'max-md:flex-col max-md:items-start max-md:gap-1'
      )}
      data-testid="summary-stats-bar"
    >
      {/* Left side: record count and filter info */}
      <div className="flex items-center gap-2 flex-wrap min-w-0">
        <span className="font-semibold whitespace-nowrap" data-testid="summary-record-count">
          {t('summaryStats.recordCount', { count: totalCount })}
        </span>

        {hasActiveFilters ? (
          <>
            <span className="text-muted-foreground text-xs select-none" aria-hidden="true">
              &middot;
            </span>
            <span data-testid="summary-filtered-count">
              {t('summaryStats.filteredCount', { count: filteredCount })}
            </span>
            <span className="text-muted-foreground text-xs select-none" aria-hidden="true">
              &middot;
            </span>
            <span
              className="text-muted-foreground whitespace-nowrap overflow-hidden text-ellipsis max-w-[400px] max-lg:max-w-[250px] max-md:max-w-full"
              title={filterDescription}
              data-testid="summary-filter-description"
            >
              {filterDescription}
            </span>
            <button
              type="button"
              className={cn(
                'text-primary bg-transparent border-0 p-0 text-sm font-medium',
                'cursor-pointer whitespace-nowrap no-underline',
                'transition-colors motion-reduce:transition-none',
                'hover:text-primary/80 hover:underline',
                'focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-2'
              )}
              onClick={onClearFilters}
              data-testid="summary-clear-filters"
            >
              {t('summaryStats.clearFilters')}
            </button>
          </>
        ) : (
          <>
            <span className="text-muted-foreground text-xs select-none" aria-hidden="true">
              &middot;
            </span>
            <span data-testid="summary-showing-all">{t('summaryStats.showingAll')}</span>
          </>
        )}
      </div>

      {/* Right side: aggregates for numeric fields */}
      {aggregates.length > 0 && (
        <div
          className="flex items-center gap-4 text-muted-foreground shrink-0 max-md:flex-wrap"
          data-testid="summary-aggregates"
        >
          {aggregates.map((agg, index) => (
            <React.Fragment key={agg.label}>
              {index > 0 && (
                <span className="text-muted-foreground/60 text-xs select-none" aria-hidden="true">
                  &middot;
                </span>
              )}
              <span
                className="text-xs text-muted-foreground whitespace-nowrap"
                data-testid={`summary-aggregate-${agg.label}`}
              >
                {t('summaryStats.sumOf', {
                  field: agg.label,
                  value: agg.sum.toLocaleString(),
                })}
              </span>
            </React.Fragment>
          ))}
        </div>
      )}
    </div>
  )
}
