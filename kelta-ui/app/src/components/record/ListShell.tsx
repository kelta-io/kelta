/**
 * ListShell — the one page skeleton for a collection list view.
 *
 * Owns the shared frame both list stacks duplicated: the loading branch, the
 * status branch (error / not-found / permission gate), and the ordered layout
 * — breadcrumb → header → toolbar → filters → table → pagination → below-table
 * extras → dialogs. Variant-specific chrome (breadcrumb style, header, toolbar,
 * filter UI, the table itself) is supplied as slots so the same skeleton drives
 * both the end-user (`/app/o`) and admin (`/resources`) stacks. The `variant`
 * also selects the frame spacing each stack shipped with (end-user `space-y`,
 * admin flex + gap).
 *
 * This is a dumb layout component — no data fetching, no state. It is the
 * list-page sibling of `RecordShell` and follows the same slot precedence:
 * `isLoading` > `statusSlot` > frame.
 *
 * Part of the unified record experience — see
 * `.claude/docs/specs/unified-record/2-record-shell.md` /
 * `8-admin-convergence.md`.
 */

import React from 'react'
import { LoadingSpinner } from '@/components/LoadingSpinner'
import { cn } from '@/lib/utils'
import { RecordStackProvider } from '@/context/RecordStackContext'

export type ListShellVariant = 'enduser' | 'admin'

/** Frame spacing each stack shipped with, keyed by variant. */
const FRAME_CLASSES: Record<ListShellVariant, string> = {
  enduser: 'space-y-4 p-4 sm:p-6',
  admin: 'flex flex-col gap-6 p-6 w-full max-md:p-2 max-md:gap-4 max-lg:p-4',
}

export interface ListShellProps {
  /** Which stack renders this shell. Selects the frame spacing classes, provides the
   *  record stack (`RecordStackProvider`) so shared components' record links stay in this
   *  stack, and is exposed as `data-variant` for variant-conditional styling/telemetry. */
  variant: ListShellVariant
  /** Render only the loading state. Takes precedence over everything. */
  isLoading?: boolean
  /**
   * When set, render this instead of the list frame — the caller's error /
   * not-found / permission-gate branch. Precedence: `isLoading` > `statusSlot`
   * > frame.
   */
  statusSlot?: React.ReactNode
  /** Breadcrumb row (end-user `Breadcrumb`, admin plain nav). */
  breadcrumb?: React.ReactNode
  /** Header block: page title + primary actions (admin `<header>` bar). */
  header?: React.ReactNode
  /** Toolbar row: list-view toolbar, view selector, bulk-action bar. */
  toolbar?: React.ReactNode
  /** Active-filters bar / filter builder (+ adjacent filter summaries). */
  filters?: React.ReactNode
  /** The data table (or its own loading / error / empty branch). */
  table: React.ReactNode
  /** Pagination controls, rendered directly below the table. */
  pagination?: React.ReactNode
  /** Extra content below the table + pagination. */
  belowTable?: React.ReactNode
  /** Dialogs / modals (delete confirms, CSV import). */
  dialogs?: React.ReactNode
  className?: string
  'data-testid'?: string
}

export function ListShell({
  variant,
  isLoading,
  statusSlot,
  breadcrumb,
  header,
  toolbar,
  filters,
  table,
  pagination,
  belowTable,
  dialogs,
  className,
  'data-testid': testId = 'list-shell',
}: ListShellProps): React.ReactElement {
  if (isLoading) {
    return (
      <div
        className="flex min-h-[40vh] items-center justify-center"
        data-testid={`${testId}-loading`}
      >
        <LoadingSpinner />
      </div>
    )
  }

  if (statusSlot) {
    return (
      <RecordStackProvider variant={variant}>
        <div data-testid={`${testId}-status`} data-variant={variant}>
          {statusSlot}
        </div>
      </RecordStackProvider>
    )
  }

  return (
    <RecordStackProvider variant={variant}>
      <div
        className={cn(FRAME_CLASSES[variant], className)}
        data-testid={testId}
        data-variant={variant}
      >
        {breadcrumb}
        {header}
        {toolbar}
        {filters}
        {table}
        {pagination}
        {belowTable}
        {dialogs}
      </div>
    </RecordStackProvider>
  )
}

export default ListShell
