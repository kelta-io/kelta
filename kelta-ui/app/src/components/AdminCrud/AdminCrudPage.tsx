/**
 * AdminCrudPage
 *
 * Page shell for an admin CRUD screen: page container + header (title,
 * optional description, actions such as the "New X" button), then exactly one
 * of loading spinner / load error with retry / empty state / the list. Dialogs
 * passed as `children` render once the list has loaded (including in the empty
 * state, so "create" works from an empty list).
 */

import React from 'react'
import { LoadingSpinner } from '../LoadingSpinner'
import { ErrorMessage } from '../ErrorMessage'
import { useI18n } from '../../context/I18nContext'

export interface AdminCrudPageProps {
  testId: string
  title: string
  description?: React.ReactNode
  /** Header actions, typically the create button. */
  actions?: React.ReactNode
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
  description,
  actions,
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

  let body: React.ReactNode
  if (isLoading) {
    body = (
      <div className="flex min-h-[400px] items-center justify-center">
        <LoadingSpinner size="large" label={t('common.loading')} />
      </div>
    )
  } else if (error) {
    body = (
      <ErrorMessage
        error={
          loadErrorMessage ?? (error instanceof Error ? error : new Error(t('errors.generic')))
        }
        onRetry={onRetry}
      />
    )
  } else {
    body = (
      <>
        <header className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold text-foreground">{title}</h1>
            {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
          </div>
          {actions}
        </header>
        {isEmpty ? (
          <div
            className="rounded-lg border border-border bg-card py-16 text-center text-muted-foreground"
            data-testid="empty-state"
          >
            <p>{emptyMessage}</p>
          </div>
        ) : (
          list
        )}
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
