/**
 * The record list's filter builder panel (`RecordListFeatures.filterBuilder`): the app's
 * `FilterBuilder` over the list's readable fields, editing a draft of the applied conditions that
 * Apply writes through `model.setFilters` (back to page 1; in `urlState="sync"` the URL's
 * `filter=` param). Clear removes every condition.
 *
 * AND-only, with the operators the list can express — see `listFilterMapping.ts`. The panel is
 * opened by the `filter-toggle` button in `ListViewControls`; the draft restarts from the applied
 * conditions whenever they change elsewhere (a chip removed, a view applied).
 */
import React, { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { FilterBuilder, type FieldOption, type FilterExpression } from '@/components/FilterBuilder'
import { useI18n } from '@/context/I18nContext'
import type { FilterCondition } from '@/hooks/useCollectionRecords'
import {
  LIST_BUILDER_OPERATORS,
  LIST_FILTER_BUILDER_ALLOWS_OR,
  expressionToListFilters,
  listFiltersToExpression,
} from './listFilterMapping'
import type { RecordListModel } from './useRecordListModel'

export interface ListFilterBuilderProps {
  model: RecordListModel
}

export function ListFilterBuilder({ model }: ListFilterBuilderProps): React.ReactElement | null {
  const applied = model.params.filters
  if (!model.filterBuilderOpen) return null
  // Keyed on the applied conditions: an outside change restarts the draft from them.
  return <FilterBuilderPanel key={JSON.stringify(applied)} model={model} applied={applied} />
}

function FilterBuilderPanel({
  model,
  applied,
}: {
  model: RecordListModel
  applied: FilterCondition[]
}): React.ReactElement {
  const { t } = useI18n()
  const [draft, setDraft] = useState<FilterExpression | null>(() =>
    listFiltersToExpression(applied)
  )
  const fields = useMemo<FieldOption[]>(
    () =>
      model.accessibleFields.map((f) => ({ name: f.name, displayName: f.displayName || f.name })),
    [model.accessibleFields]
  )

  return (
    <div
      className="flex flex-col gap-3 rounded-md border border-border bg-card p-3"
      data-testid="list-filter-builder"
      role="group"
      aria-label={t('listFilter.title', 'Filter records')}
    >
      <FilterBuilder
        value={draft}
        onChange={setDraft}
        fields={fields}
        idPrefix="list-filter"
        operators={LIST_BUILDER_OPERATORS}
        allowOr={LIST_FILTER_BUILDER_ALLOWS_OR}
      />
      {(draft?.filters.length ?? 0) > 1 && (
        <p className="text-xs text-muted-foreground" data-testid="list-filter-and-note">
          {t('listFilter.allMatch', 'Records must match all conditions.')}
        </p>
      )}
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          onClick={() => model.setFilters(expressionToListFilters(draft))}
          data-testid="list-filter-apply"
        >
          {t('listFilter.apply', 'Apply')}
        </Button>
        {(applied.length > 0 || (draft?.filters.length ?? 0) > 0) && (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              setDraft(null)
              model.clearFilters()
            }}
            data-testid="list-filter-clear"
          >
            {t('listFilter.clear', 'Clear all')}
          </Button>
        )}
      </div>
    </div>
  )
}
