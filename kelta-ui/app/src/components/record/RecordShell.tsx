/**
 * RecordShell — the one page skeleton for a record detail view.
 *
 * Owns the shared frame both record stacks duplicated: the loading branch, the
 * status branch (error / not-found / permission gate), and the ordered layout
 * — breadcrumb → header → (optional left section nav + main body + optional
 * right rail) → bottom tab bar → below-tabs extras → dialogs. Variant-specific
 * chrome (breadcrumb style, header, section nav, rail, admin-only Sharing) is
 * supplied as slots so the same skeleton drives both the end-user (`/app/o`)
 * and admin (`/resources`) stacks.
 *
 * The record BODY is normally `<RecordDetailBody/>`; the tab bar is normally
 * `<DetailTabBar/>`. Both are already shared across stacks.
 *
 * Part of the unified record experience — see
 * `.claude/docs/specs/unified-record/2-record-shell.md` /
 * `8-admin-convergence.md`.
 */

import React from 'react'
import { LoadingSpinner } from '@/components/LoadingSpinner'
import { cn } from '@/lib/utils'
import { RecordStackProvider } from '@/context/RecordStackContext'

type RecordShellVariant = 'enduser' | 'admin'

export interface RecordShellProps {
  /** Which stack renders this shell. Drives no layout itself — the slots do — but it
   *  provides the record stack (`RecordStackProvider`) to everything rendered inside, so
   *  shared components' record links stay in this stack, and is exposed as `data-variant`. */
  variant: RecordShellVariant
  /** Render only the loading state. Takes precedence over everything. */
  isLoading?: boolean
  /**
   * When set, render this instead of the record frame — the caller's error /
   * not-found / permission-gate branch. Precedence: `isLoading` > `statusSlot` >
   * frame.
   */
  statusSlot?: React.ReactNode
  /** Breadcrumb row (end-user `Crumb`, admin plain nav). */
  breadcrumb?: React.ReactNode
  /** Header block: `RecordHeader` + quick-actions / actions bar. */
  header?: React.ReactNode
  /** Main-column body — normally `<RecordDetailBody/>`. */
  body: React.ReactNode
  /**
   * Optional left panel — normally a page-owned sticky wrapper holding
   * `<RecordSectionNav/>` with the Activity timeline docked beneath it.
   * Renders as a left column of the body grid on `lg+`; hidden on smaller
   * screens (the main column already reads top-to-bottom there).
   */
  sectionNav?: React.ReactNode
  /** Optional right rail. When omitted, the body spans full width. */
  rail?: React.ReactNode
  /** Bottom tab bar — normally `<DetailTabBar/>`. */
  tabBar?: React.ReactNode
  /** Extra content below the tabs. */
  belowTabs?: React.ReactNode
  /** Dialogs / modals (delete confirm, share form). */
  dialogs?: React.ReactNode
  className?: string
  'data-testid'?: string
}

export function RecordShell({
  variant,
  isLoading,
  statusSlot,
  breadcrumb,
  header,
  body,
  sectionNav,
  rail,
  tabBar,
  belowTabs,
  dialogs,
  className,
  'data-testid': testId = 'record-shell',
}: RecordShellProps): React.ReactElement {
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
      <div className={cn('space-y-4', className)} data-testid={testId} data-variant={variant}>
        {breadcrumb}
        {header}
        <div
          className={cn(
            // gap-4 matches the pre-convergence end-user grid so the record-detail
            // visual-regression baseline stays pixel-stable (admin is single-column).
            'grid grid-cols-1 gap-4',
            !sectionNav && rail && 'lg:grid-cols-[minmax(0,1fr)_340px]',
            sectionNav && rail && 'lg:grid-cols-[280px_minmax(0,1fr)_340px]',
            sectionNav && !rail && 'lg:grid-cols-[280px_minmax(0,1fr)]'
          )}
        >
          {sectionNav && (
            <div className="hidden self-start lg:block" data-testid={`${testId}-section-nav`}>
              {sectionNav}
            </div>
          )}
          <div className="min-w-0">{body}</div>
          {rail && <aside className="space-y-4">{rail}</aside>}
        </div>
        {tabBar && <div className="space-y-4">{tabBar}</div>}
        {belowTabs}
        {dialogs}
      </div>
    </RecordStackProvider>
  )
}
