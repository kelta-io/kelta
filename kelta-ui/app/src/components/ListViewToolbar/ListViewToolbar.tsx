/**
 * ListViewToolbar Component
 *
 * Toolbar for the record list (`RecordList`) with:
 * - Collection title and "New" button (unless `showHeading` is false — the caller's header owns
 *   them)
 * - Actions dropdown (bulk delete, export CSV/JSON)
 * - Bulk selection indicator bar
 */

import React from 'react'
import { Plus, Download, Upload, Trash2, ChevronDown, Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

export interface ListViewToolbarProps {
  /** Collection display name */
  collectionLabel: string
  /** Number of selected rows */
  selectedCount: number
  /** Total record count */
  totalCount: number
  /** Callback to create a new record */
  onNew: () => void
  /** Callback for bulk delete */
  onBulkDelete?: () => void
  /** Callback for CSV export */
  onExportCsv?: () => void
  /** Callback for JSON export */
  onExportJson?: () => void
  /** Callback for CSV import */
  onImportCsv?: () => void
  /** Callback to clear selection */
  onClearSelection?: () => void
  /**
   * Mass edit (one field across the selection). Pass only when the caller's
   * gate holds (canEdit + MANAGE_DATA) — the toolbar stays permission-ignorant.
   */
  onMassEdit?: () => void
  /** Whether the user can create records (controls New button visibility) */
  canCreate?: boolean
  /** Whether the user can delete records (controls Delete action visibility) */
  canDelete?: boolean
  /** Optional slot for rendering quick actions (e.g., QuickActionsMenu) */
  quickActionsSlot?: React.ReactNode
  /**
   * Render the collection title and the New button (default true). False when the page's own
   * header already shows them; the actions stay.
   */
  showHeading?: boolean
}

export function ListViewToolbar({
  collectionLabel,
  selectedCount,
  totalCount,
  onNew,
  onBulkDelete,
  onExportCsv,
  onExportJson,
  onImportCsv,
  onClearSelection,
  onMassEdit,
  canCreate = true,
  canDelete = true,
  quickActionsSlot,
  showHeading = true,
}: ListViewToolbarProps): React.ReactElement {
  return (
    <div className="space-y-2">
      {/* Main toolbar row */}
      <div className={cn('flex items-center', showHeading ? 'justify-between' : 'justify-end')}>
        {showHeading && (
          <h1 className="text-[26px] font-bold tracking-[-0.01em] text-foreground">
            {collectionLabel}
            {totalCount > 0 && (
              <span className="ml-2 font-mono text-sm font-normal tabular-nums text-muted-foreground">
                ({totalCount.toLocaleString()})
              </span>
            )}
          </h1>
        )}

        <div className="flex items-center gap-2">
          {/* Quick Actions slot (rendered when configured) */}
          {quickActionsSlot}

          {/* Actions dropdown */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm">
                Actions
                <ChevronDown className="ml-1.5 h-3.5 w-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {onExportCsv && (
                <DropdownMenuItem onClick={onExportCsv}>
                  <Download className="mr-2 h-4 w-4" />
                  Export as CSV
                </DropdownMenuItem>
              )}
              {onExportJson && (
                <DropdownMenuItem onClick={onExportJson}>
                  <Download className="mr-2 h-4 w-4" />
                  Export as JSON
                </DropdownMenuItem>
              )}
              {onImportCsv && (
                <DropdownMenuItem onClick={onImportCsv}>
                  <Upload className="mr-2 h-4 w-4" />
                  Import CSV
                </DropdownMenuItem>
              )}
              {(onExportCsv || onExportJson || onImportCsv) && onBulkDelete && (
                <DropdownMenuSeparator />
              )}
              {canDelete && onBulkDelete && selectedCount > 0 && (
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onClick={onBulkDelete}
                >
                  <Trash2 className="mr-2 h-4 w-4" />
                  Delete selected ({selectedCount})
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>

          {/* New record button (hidden if user lacks create permission) */}
          {showHeading && canCreate && (
            <Button
              size="sm"
              onClick={onNew}
              aria-label={`New ${collectionLabel}`}
              data-testid="new-record-button"
            >
              <Plus className="mr-1.5 h-3.5 w-3.5" />
              New
            </Button>
          )}
        </div>
      </div>

      {/* Bulk selection bar */}
      {selectedCount > 0 && (
        <div className="flex items-center gap-3 rounded-md border border-primary/20 bg-primary/5 px-4 py-2 text-sm">
          <span className="font-medium">
            {selectedCount} record{selectedCount !== 1 ? 's' : ''} selected
          </span>
          {onMassEdit && (
            <Button
              variant="ghost"
              size="sm"
              className="h-7"
              onClick={onMassEdit}
              data-testid="mass-edit-button"
            >
              <Pencil className="mr-1.5 h-3.5 w-3.5" />
              Edit field
            </Button>
          )}
          {canDelete && onBulkDelete && (
            <Button
              variant="ghost"
              size="sm"
              className="h-7 text-destructive hover:text-destructive"
              onClick={onBulkDelete}
            >
              <Trash2 className="mr-1.5 h-3.5 w-3.5" />
              Delete
            </Button>
          )}
          {onClearSelection && (
            <Button variant="ghost" size="sm" className="ml-auto h-7" onClick={onClearSelection}>
              Clear selection
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
