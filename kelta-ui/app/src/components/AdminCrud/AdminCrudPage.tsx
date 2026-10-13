/**
 * AdminCrudPage
 *
 * Page shell for an admin CRUD screen: page container + header (title,
 * optional description, actions such as the "New X" button), then exactly one
 * of loading spinner / load error with retry / empty state / the list. Dialogs
 * passed as `children` render once the list has loaded (including in the empty
 * state, so "create" works from an empty list).
 *
 * A page whose list is driven by its own controls (server-side search, status
 * filter, paging) passes them as `toolbar`: the header and toolbar then stay
 * mounted while the list loads or fails — only the list area switches — so a
 * search box keeps its focus and value across refetches, and `children` are
 * always mounted.
 */

import React from 'react'
import { LoadingSpinner } from '../LoadingSpinner'
import { ErrorMessage } from '../ErrorMessage'
import { useI18n } from '../../context/I18nContext'

export interface AdminCrudPageProps {
  testId: string
  title: string
  /** Rendered beside the title (e.g. a scope badge). */
  titleAdornment?: React.ReactNode
  description?: React.ReactNode
  /** Header actions, typically the create button. */
  actions?: React.ReactNode
  /**
   * Controls between the header and the list (search, filters). When set, the
   * header and toolbar stay mounted through loading and error states.
   */
  toolbar?: React.ReactNode
  isLoading: boolean
  /** The list query's error, if any. */
  error?: unknown
  /** Shown instead of the raw error message when the list fails to load. */
  loadErrorMessage?: string
  onRetry?: () => void
  /** When true the empty state renders instead of `list`. */
  isEmpty: boolean
  emptyMessage: React.ReactNode
  /** The list itself, usually an `AdminDataTable`. */
  list: React.ReactNode
  /** Form / confirm dialogs. */
  children?: React.ReactNode
}

export function AdminCrudPage({
  testId,
  title,
  titleAdornment,
  description,
  actions,
  toolbar,
  isLoading,
  error,
  loadErrorMessage,
  onRetry,
  isEmpty,
  emptyMessage,
  list,
  children,
}: AdminCrudPageProps): React.ReactElement {
  const { t } = useI18n()

  const loading = (
    <div className="flex min-h-[400px] items-center justify-center">
      <LoadingSpinner size="large" label={t('common.loading')} />
    </div>
  )
  const loadError = (
    <ErrorMessage
      error={loadErrorMessage ?? (error instanceof Error ? error : new Error(t('errors.generic')))}
      onRetry={onRetry}
    />
  )
  const header = (
    <header className="flex items-center justify-between gap-4">
      <div>
        {titleAdornment ? (
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold text-foreground">{title}</h1>
            {titleAdornment}
          </div>
        ) : (
          <h1 className="text-2xl font-semibold text-foreground">{title}</h1>
        )}
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions}
    </header>
  )
  const listOrEmpty = isEmpty ? (
    <div
      className="rounded-lg border border-border bg-card py-16 text-center text-muted-foreground"
      data-testid="empty-state"
    >
      <p>{emptyMessage}</p>
    </div>
  ) : (
    list
  )

  let body: React.ReactNode
  if (toolbar !== undefined) {
    body = (
      <>
        {header}
        {toolbar}
        {isLoading ? loading : error ? loadError : listOrEmpty}
        {children}
      </>
    )
  } else if (isLoading) {
    body = loading
  } else if (error) {
    body = loadError
  } else {
    body = (
      <>
        {header}
        {listOrEmpty}
        {children}
      </>
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      {body}
    </div>
  )
}
