/**
 * The record list's view-controls row: view selector, filter-builder toggle, column chooser,
 * density, group-by, the renderer switcher and each renderer's field picker. Every control is shown only when its
 * feature is on (`RecordListFeatures`); the row renders nothing when none is.
 *
 * Part of the record list core (`RecordList`), moved out of `ObjectListPage` (P9).
 */
import React from 'react'
import {
  CalendarDays,
  ChevronDown,
  Filter,
  Layers,
  LayoutGrid,
  Rows3,
  SquareKanban,
  Table2,
} from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'
import { ColumnChooser } from '@/components/ColumnChooser/ColumnChooser'
import { ViewSelector } from '@/components/ViewSelector/ViewSelector'
import { useI18n } from '@/context/I18nContext'
import type { SavedViewType } from '@/hooks/useSavedViews'
import { nextDensity } from './listViewState'
import type { RecordListModel } from './useRecordListModel'

/** Renderer switcher entries; labels come from `altViews.<type>` keys. */
const VIEW_TYPE_META: Array<{
  type: SavedViewType
  icon: React.ComponentType<{ className?: string }>
}> = [
  { type: 'table', icon: Table2 },
  { type: 'kanban', icon: SquareKanban },
  { type: 'calendar', icon: CalendarDays },
  { type: 'gallery', icon: LayoutGrid },
]

export interface ListViewControlsProps {
  model: RecordListModel
  /** Caller content at the end of the row. */
  extras?: React.ReactNode
}

export function ListViewControls({
  model,
  extras,
}: ListViewControlsProps): React.ReactElement | null {
  const { t } = useI18n()
  const { features, view, accessibleFields, columnFields, visibleFields, fields } = model
  const { density, groupBy, viewType, typeConfig, changeTypeConfig } = view

  const anyControl =
    features.savedViews ||
    features.filterBuilder ||
    features.columnChooser ||
    features.density ||
    features.groupBy ||
    features.altViews ||
    !!extras
  if (!anyControl) return null

  const viewTypeMeta = VIEW_TYPE_META.find((m) => m.type === viewType) ?? VIEW_TYPE_META[0]
  const ViewTypeIcon = viewTypeMeta.icon

  return (
    <div className="flex flex-wrap items-center gap-2">
      {features.savedViews && (
        <ViewSelector
          views={view.views}
          activeView={view.activeView}
          onSelectView={view.selectView}
          onSaveView={model.saveView}
          onDeleteView={view.deleteView}
          onRenameView={view.renameView}
          onSetDefault={view.setDefaultView}
        />
      )}
      {features.filterBuilder && (
        <Button
          variant={model.filterBuilderOpen ? 'secondary' : 'outline'}
          size="sm"
          onClick={() => model.setFilterBuilderOpen(!model.filterBuilderOpen)}
          aria-expanded={model.filterBuilderOpen}
          data-testid="filter-toggle"
        >
          <Filter className="mr-1.5 h-4 w-4" aria-hidden />
          {t('listFilter.toggle', 'Filter')}
          {model.params.filters.length > 0 && (
            <span
              className="ml-1.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-primary px-1 text-xs font-semibold text-primary-foreground"
              data-testid="filter-count"
            >
              {model.params.filters.length}
            </span>
          )}
        </Button>
      )}
      {features.columnChooser && (
        <ColumnChooser
          fields={columnFields.map((f) => ({ name: f.name, displayName: f.displayName }))}
          visibleColumns={visibleFields.map((f) => f.name)}
          onChange={view.setColumnOverride}
        />
      )}
      {features.density && (
        <Button
          variant="outline"
          size="sm"
          onClick={() => view.setDensity(nextDensity(density))}
          data-testid="density-toggle"
          aria-label={t('listPower.density', 'Row density')}
        >
          <Rows3 className="mr-1.5 h-4 w-4" aria-hidden />
          {density === 'compact'
            ? t('listPower.compact', 'Compact')
            : density === 'comfortable'
              ? t('listPower.comfortable', 'Comfortable')
              : t('listPower.normal', 'Normal')}
        </Button>
      )}
      {features.groupBy && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              size="sm"
              data-testid="group-by-trigger"
              aria-label={t('listPower.groupBy', 'Group by')}
            >
              <Layers className="mr-1.5 h-4 w-4" aria-hidden />
              {groupBy
                ? (fields.find((f) => f.name === groupBy)?.displayName ?? groupBy)
                : t('listPower.groupBy', 'Group by')}
              <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuItem onClick={() => view.setGroupBy(null)} data-testid="group-by-none">
              {t('listPower.noGrouping', 'No grouping')}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            {accessibleFields.map((f) => (
              <DropdownMenuItem
                key={f.name}
                onClick={() => view.setGroupBy(f.name)}
                data-testid={`group-by-${f.name}`}
              >
                {f.displayName || f.name}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
      {features.altViews && (
        <>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="sm"
                data-testid="view-type-trigger"
                aria-label={t('altViews.viewType', 'View type')}
              >
                <ViewTypeIcon className="mr-1.5 h-4 w-4" aria-hidden />
                {t(`altViews.${viewTypeMeta.type}`, viewTypeMeta.type)}
                <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {VIEW_TYPE_META.map(({ type, icon: Icon }) => (
                <DropdownMenuItem
                  key={type}
                  onClick={() => view.changeViewType(type)}
                  data-testid={`view-type-${type}`}
                >
                  <Icon className="mr-2 h-4 w-4" aria-hidden />
                  {t(`altViews.${type}`, type)}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
          {viewType === 'kanban' && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" data-testid="lane-field-trigger">
                  {t('altViews.laneField', 'Lane')}:{' '}
                  {model.kanbanLaneField
                    ? model.kanbanLaneField.displayName || model.kanbanLaneField.name
                    : '—'}
                  <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {accessibleFields
                  .filter((f) => f.type === 'picklist')
                  .map((f) => (
                    <DropdownMenuItem
                      key={f.name}
                      onClick={() =>
                        changeTypeConfig({ ...typeConfig, kanban: { laneField: f.name } })
                      }
                      data-testid={`lane-field-${f.name}`}
                    >
                      {f.displayName || f.name}
                    </DropdownMenuItem>
                  ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
          {viewType === 'calendar' && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" data-testid="date-field-trigger">
                  {t('altViews.dateField', 'Date')}:{' '}
                  {model.calendarDateField
                    ? model.calendarDateField.displayName || model.calendarDateField.name
                    : '—'}
                  <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {accessibleFields
                  .filter((f) => f.type === 'date' || f.type === 'datetime')
                  .map((f) => (
                    <DropdownMenuItem
                      key={f.name}
                      onClick={() =>
                        changeTypeConfig({ ...typeConfig, calendar: { dateField: f.name } })
                      }
                      data-testid={`date-field-${f.name}`}
                    >
                      {f.displayName || f.name}
                    </DropdownMenuItem>
                  ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
          {viewType === 'gallery' && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" data-testid="image-field-trigger">
                  {t('altViews.imageField', 'Image')}:{' '}
                  {model.galleryImageField
                    ? model.galleryImageField.displayName || model.galleryImageField.name
                    : t('altViews.none', 'None')}
                  <ChevronDown className="ml-1 h-3.5 w-3.5" aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuItem
                  onClick={() => {
                    const next = { ...typeConfig }
                    delete next.gallery
                    changeTypeConfig(next)
                  }}
                  data-testid="image-field-none"
                >
                  {t('altViews.none', 'None')}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                {accessibleFields
                  .filter((f) => f.type === 'url')
                  .map((f) => (
                    <DropdownMenuItem
                      key={f.name}
                      onClick={() =>
                        changeTypeConfig({ ...typeConfig, gallery: { imageField: f.name } })
                      }
                      data-testid={`image-field-${f.name}`}
                    >
                      {f.displayName || f.name}
                    </DropdownMenuItem>
                  ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </>
      )}
      {extras}
    </div>
  )
}
