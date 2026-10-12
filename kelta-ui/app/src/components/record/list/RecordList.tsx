/**
 * RecordList — the one record list for a collection.
 *
 * Renders a collection's records inside `ListShell`: the toolbar (`ListViewToolbar` — title,
 * New, Actions, the selection bar), the view controls (`ListViewControls`), the filter chips
 * and the records error, the table (`ObjectDataTable`: sort / multi-sort, selection, grouping,
 * inline cell edit) or an alternate renderer (kanban, calendar, gallery), pagination, and the
 * dialogs (single + bulk delete, CSV import, mass edit). It also owns the permission gate and
 * the schema-error state.
 *
 * Data and state come from `useRecordListModel`. Optional features are switched on by the
 * caller (`features`), never by `variant`; the variant only selects the `ListShell` frame and
 * the record stack its links stay in. Navigation targets come from the caller's `routes`
 * (`recordRoutes`), and variant chrome — breadcrumb, header, extra controls, a filter editor,
 * content below the table — is passed as slots, so the end-user `ObjectListPage` is a thin
 * wrapper over it.
 *
 * The body is keyed on the collection: when the route switches collection (the same route
 * element is reused), selection, the applied view, the column override, density, grouping, the
 * renderer and open dialogs start fresh, and the new collection's default view applies.
 *
 * Record-stack convergence P9: moved from `ObjectListPage`. See
 * `.claude/docs/conventions.md` → Component reuse.
 */
import React from 'react'
import { AlertCircle, Loader2 } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { useI18n } from '@/context/I18nContext'
import { InsufficientPrivileges } from '@/components/InsufficientPrivileges'
import { QuickActionsMenu } from '@/components/QuickActions'
import { ListShell, type ListShellVariant } from '@/components/record/ListShell'
import { ObjectDataTable } from '@/components/ObjectDataTable/ObjectDataTable'
import { DataTablePagination } from '@/components/ObjectDataTable/DataTablePagination'
import { ListViewToolbar } from '@/components/ListViewToolbar'
import { FilterBar } from '@/components/FilterBar'
import { CsvImportDialog } from '@/components/CsvImportDialog/CsvImportDialog'
import { MassEditDialog } from '@/components/RelatedList/MassEditDialog'
import { KanbanBoard } from '@/components/KanbanBoard'
import { CalendarMonthView } from '@/components/CalendarMonthView'
import { GalleryGrid } from '@/components/GalleryGrid'
import { usePicklistDisplayMap, usePicklistOptions } from '@/hooks/usePicklistOptions'
import type { RecordRoutes } from '@/utils/recordRoutes'
import { ListViewControls } from './ListViewControls'
import { CALENDAR_FETCH_LIMIT } from './listViewState'
import type { ListUrlStateMode } from './useListParams'
import {
  useRecordListModel,
  type RecordListFeatures,
  type RecordListModel,
} from './useRecordListModel'

export type RecordListVariant = ListShellVariant

/** What chrome slots may read once the list has loaded. */
export interface RecordListChromeContext {
  collectionLabel: string
  model: RecordListModel
}

/** Chrome rendered from the loaded list (a function receives {@link RecordListChromeContext}). */
export type RecordListChromeSlot =
  | React.ReactNode
  | ((ctx: RecordListChromeContext) => React.ReactNode)

function renderSlot(
  slot: RecordListChromeSlot | undefined,
  ctx: RecordListChromeContext
): React.ReactNode {
  return typeof slot === 'function' ? slot(ctx) : slot
}

export interface RecordListProps {
  /** Which stack renders this list: the `ListShell` frame and the stack record links stay in. */
  variant: RecordListVariant
  /** Collection API name. */
  collectionName: string
  tenantSlug: string
  /** Record URLs of this list's stack — `recordRoutes(variant, tenantSlug)`. */
  routes: RecordRoutes
  /** Optional features (missing keys are off). Pass a stable (module-level) object. */
  features?: Partial<RecordListFeatures>
  /** Where the list state lives: the URL (`'sync'`, default) or a copy seeded from it (`'seed'`). */
  urlState?: ListUrlStateMode
  /** Where the permission gate's Back link goes. */
  permissionBackPath: string
  /** Breadcrumb row. */
  breadcrumb?: RecordListChromeSlot
  /** Header block above the toolbar. */
  header?: RecordListChromeSlot
  /** Extra controls at the end of the view-controls row. */
  toolbarExtras?: RecordListChromeSlot
  /**
   * A filter editor above the filter chips — e.g. a builder writing `model.setFilters`; the
   * applied conditions are `model.params.filters`.
   */
  filterEditor?: RecordListChromeSlot
  /** Content below the table + pagination. */
  belowTable?: RecordListChromeSlot
  /** `data-testid` of the `ListShell` frame (default `list-shell`). */
  testId?: string
}

export function RecordList(props: RecordListProps): React.ReactElement {
  return <RecordListBody key={props.collectionName} {...props} />
}

function RecordListBody({
  variant,
  collectionName,
  tenantSlug,
  routes,
  features,
  urlState,
  permissionBackPath,
  breadcrumb,
  header,
  toolbarExtras,
  filterEditor,
  belowTable,
  testId,
}: RecordListProps): React.ReactElement {
  const { t } = useI18n()
  const model = useRecordListModel({ collectionName, tenantSlug, routes, features, urlState })
  const {
    features: on,
    view,
    permissions,
    records,
    total,
    selectedIds,
    collectionLabel,
    recordsError,
    schemaError,
  } = model
  const { viewType } = view
  const ctx: RecordListChromeContext = { collectionLabel, model }

  // Kanban lanes come from the lane field's picklist values (fetched only in kanban view).
  const isKanban = viewType === 'kanban'
  const { options: laneOptions } = usePicklistOptions(model.kanbanLaneField ?? undefined, isKanban)
  const { displayMap: laneDisplayMap } = usePicklistDisplayMap(
    model.kanbanLaneField ?? undefined,
    isKanban
  )

  // Status branch (permission gate / schema error), rendered by the shell in place of the frame.
  const statusSlot: React.ReactNode = !permissions.canRead ? (
    <InsufficientPrivileges
      action="view"
      resource={collectionLabel}
      backPath={permissionBackPath}
    />
  ) : schemaError ? (
    <div className="space-y-4 p-6">
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>Error loading collection</AlertTitle>
        <AlertDescription>
          {schemaError.message || 'Failed to load collection schema.'}
        </AlertDescription>
      </Alert>
      <Button variant="outline" onClick={() => model.refetch()}>
        Retry
      </Button>
    </div>
  ) : null

  const emptyAltView = (testIdSuffix: string, message: string) => (
    <div
      className="rounded-[10px] border border-border bg-card p-8 text-center text-sm text-muted-foreground"
      data-testid={`${testIdSuffix}-empty-state`}
    >
      {message}
    </div>
  )

  const table =
    viewType === 'kanban' ? (
      model.kanbanLaneField ? (
        <KanbanBoard
          records={records}
          laneField={model.kanbanLaneField}
          laneOptions={laneOptions}
          titleField={model.titleField}
          cardFields={model.kanbanCardFields}
          canEdit={permissions.canEdit}
          onCardClick={model.goToDetail}
          onMoveCard={model.moveCard}
          tenantSlug={tenantSlug}
          lookupDisplayMap={model.lookupDisplayMap}
          laneDisplayMap={laneDisplayMap}
        />
      ) : (
        emptyAltView(
          'kanban',
          t('altViews.noLaneField', 'Kanban needs a picklist field to define lanes.')
        )
      )
    ) : viewType === 'calendar' ? (
      model.calendarDateField ? (
        <>
          <CalendarMonthView
            records={records}
            dateField={model.calendarDateField}
            titleField={model.titleField}
            month={view.calendarMonth}
            onMonthChange={view.setCalendarMonth}
            onRecordClick={model.goToDetail}
          />
          {total > CALENDAR_FETCH_LIMIT && (
            <p className="px-1 pt-1 text-xs text-muted-foreground" data-testid="calendar-clamp">
              {t('altViews.showingFirst', { count: CALENDAR_FETCH_LIMIT, total })}
            </p>
          )}
        </>
      ) : (
        emptyAltView(
          'calendar',
          t('altViews.noDateField', 'Calendar needs a date or datetime field.')
        )
      )
    ) : viewType === 'gallery' ? (
      <GalleryGrid
        records={records}
        imageField={model.galleryImageField}
        titleField={model.titleField}
        cardFields={model.visibleFields}
        onCardClick={model.goToDetail}
        tenantSlug={tenantSlug}
        lookupDisplayMap={model.lookupDisplayMap}
      />
    ) : (
      <>
        <ObjectDataTable
          records={records}
          fields={model.visibleFields}
          sort={model.params.sort}
          onSortChange={model.changeSort}
          sorts={model.params.sorts}
          density={view.density}
          stickyFirstColumn
          groupBy={view.groupBy ?? undefined}
          selectedIds={selectedIds}
          onSelectionChange={model.setSelectedIds}
          isLoading={model.recordsLoading}
          collectionName={collectionName}
          onEdit={permissions.canEdit ? model.goToEdit : undefined}
          onDelete={permissions.canDelete ? model.requestDelete : undefined}
          lookupDisplayMap={model.lookupDisplayMap}
          picklistDisplayMaps={model.picklistDisplayMaps}
          editable={model.inlineEditable}
          onCellCommit={model.commitCell}
        />
        {view.groupBy && (
          <p className="px-1 pt-1 text-xs text-muted-foreground" data-testid="group-caption">
            {t('listPower.groupsPageOnly', 'Groups reflect this page only')}
          </p>
        )}
      </>
    )

  return (
    <ListShell
      variant={variant}
      data-testid={testId}
      isLoading={model.isLoading}
      statusSlot={statusSlot}
      breadcrumb={renderSlot(breadcrumb, ctx)}
      header={renderSlot(header, ctx)}
      toolbar={
        <div className="flex flex-col gap-2">
          <ListViewToolbar
            collectionLabel={collectionLabel}
            selectedCount={selectedIds.size}
            totalCount={total}
            onNew={model.goToNew}
            onBulkDelete={model.requestBulkDelete}
            onExportCsv={on.export ? model.exportCsv : undefined}
            onExportJson={on.export ? model.exportJson : undefined}
            onImportCsv={
              on.import && permissions.canCreate ? () => model.setImportOpen(true) : undefined
            }
            onClearSelection={model.clearSelection}
            onMassEdit={model.canMassEdit ? () => model.setMassEditOpen(true) : undefined}
            canCreate={permissions.canCreate}
            canDelete={permissions.canDelete}
            quickActionsSlot={
              on.quickActions ? (
                <QuickActionsMenu
                  collectionName={collectionName}
                  context="list"
                  executionContext={model.quickActionContext}
                />
              ) : undefined
            }
          />
          <ListViewControls model={model} extras={renderSlot(toolbarExtras, ctx)} />
        </div>
      }
      filters={
        <>
          {renderSlot(filterEditor, ctx)}
          <FilterBar
            filters={model.params.filters}
            onRemoveFilter={model.removeFilter}
            onClearAll={model.clearFilters}
            picklistDisplayMaps={model.picklistDisplayMaps}
          />
          {recordsError && (
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle>Error loading records</AlertTitle>
              <AlertDescription>
                {recordsError.message || 'Failed to load records.'}
              </AlertDescription>
            </Alert>
          )}
        </>
      }
      table={table}
      pagination={
        viewType !== 'calendar' && !model.recordsLoading && records.length > 0 ? (
          <DataTablePagination
            page={model.params.page}
            pageSize={model.params.pageSize}
            total={total}
            selectedCount={selectedIds.size}
            onPageChange={model.changePage}
            onPageSizeChange={model.changePageSize}
          />
        ) : undefined
      }
      belowTable={renderSlot(belowTable, ctx)}
      dialogs={
        <>
          {model.massEditOpen && (
            <MassEditDialog
              fields={model.massEditFields}
              tenantSlug={tenantSlug}
              selectedCount={selectedIds.size}
              onSubmit={model.submitMassEdit}
              onClose={() => model.setMassEditOpen(false)}
            />
          )}

          {on.import && (
            <CsvImportDialog
              open={model.importOpen}
              onOpenChange={model.setImportOpen}
              collectionName={collectionName}
              onImported={model.refetch}
            />
          )}

          <AlertDialog
            open={!!model.deleteTarget}
            onOpenChange={(open) => {
              if (!open) model.cancelDelete()
            }}
          >
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete record?</AlertDialogTitle>
                <AlertDialogDescription>
                  This will permanently delete this record. This action cannot be undone.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={model.confirmDelete}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  {model.deletePending ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
                  Delete
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>

          <AlertDialog open={model.bulkDeleteOpen} onOpenChange={model.setBulkDeleteOpen}>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete {selectedIds.size} records?</AlertDialogTitle>
                <AlertDialogDescription>
                  This will permanently delete {selectedIds.size} record
                  {selectedIds.size !== 1 ? 's' : ''}. This action cannot be undone.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={model.confirmBulkDelete}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  {model.bulkDeletePending ? (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  ) : null}
                  Delete {selectedIds.size} records
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      }
    />
  )
}
