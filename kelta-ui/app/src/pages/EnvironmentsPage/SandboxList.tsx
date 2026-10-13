/**
 * SandboxList Component
 *
 * Lists all environments (production, sandboxes, remote targets) with status
 * polling for transient states (CREATING / REFRESHING), sandbox refresh with
 * destructive confirm, remote connection test, and archive with destructive
 * confirm. Creation is handled by CreateEnvironmentDialog.
 */

import React, { useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plug, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { CreateEnvironmentDialog } from './CreateEnvironmentDialog'
import {
  archiveEnvironment,
  fetchEnvironments,
  refreshEnvironment,
  testEnvironmentConnection,
} from './types'
import type { ConnectionTestResult, Environment, EnvironmentStatus, EnvironmentType } from './types'

/**
 * Environment status badge — pill with leading dot per DESIGN.md §6.
 */
export function EnvironmentStatusBadge({
  status,
}: {
  status: EnvironmentStatus
}): React.ReactElement {
  const { t } = useI18n()
  const statusLabels: Record<EnvironmentStatus, string> = {
    CREATING: t('environments.statuses.creating'),
    ACTIVE: t('environments.statuses.active'),
    REFRESHING: t('environments.statuses.refreshing'),
    ARCHIVED: t('environments.statuses.archived'),
    FAILED: t('environments.statuses.failed'),
  }

  const statusColorMap: Record<EnvironmentStatus, string> = {
    CREATING: 'bg-amber-500/15 text-amber-700 border-amber-500/40 dark:text-amber-300',
    ACTIVE: 'bg-emerald-500/15 text-emerald-700 border-emerald-500/40 dark:text-emerald-300',
    REFRESHING: 'bg-amber-500/15 text-amber-700 border-amber-500/40 dark:text-amber-300',
    ARCHIVED: 'bg-slate-500/15 text-slate-700 border-slate-500/40 dark:text-slate-300',
    FAILED: 'bg-destructive/15 text-destructive border-destructive/40',
  }

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 h-[22px] px-2.5 rounded text-[11px] font-semibold border',
        statusColorMap[status]
      )}
      data-testid="env-status-badge"
    >
      <span className="size-1.5 rounded-full bg-current" />
      {statusLabels[status] || status}
    </span>
  )
}

/**
 * Environment type badge.
 */
function EnvironmentTypeBadge({ type }: { type: EnvironmentType }): React.ReactElement {
  const { t } = useI18n()
  const typeLabels: Record<EnvironmentType, string> = {
    PRODUCTION: t('environments.types.production'),
    SANDBOX: t('environments.types.sandbox'),
    STAGING: t('environments.types.staging'),
  }

  const typeColorMap: Record<EnvironmentType, string> = {
    PRODUCTION: 'text-blue-800 bg-blue-50 dark:text-blue-300 dark:bg-blue-950',
    SANDBOX: 'text-purple-800 bg-purple-50 dark:text-purple-300 dark:bg-purple-950',
    STAGING: 'text-amber-800 bg-amber-50 dark:text-amber-300 dark:bg-amber-950',
  }

  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-2 py-1 text-xs font-medium',
        typeColorMap[type]
      )}
      data-testid="env-type-badge"
    >
      {typeLabels[type] || type}
    </span>
  )
}

/**
 * SandboxList — the "Sandboxes" tab of the Environments page.
 */
export function SandboxList(): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const [showCreateDialog, setShowCreateDialog] = useState(false)
  const [pendingRefresh, setPendingRefresh] = useState<Environment | null>(null)
  const [pendingDelete, setPendingDelete] = useState<Environment | null>(null)

  const {
    data: environments = [],
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['environments'],
    queryFn: () => fetchEnvironments(apiClient),
    // Poll while any sandbox is provisioning or refreshing
    refetchInterval: (query) => {
      const envs = query.state.data
      const hasTransient =
        Array.isArray(envs) &&
        envs.some((e) => e.status === 'CREATING' || e.status === 'REFRESHING')
      return hasTransient ? 5000 : false
    },
  })

  const refreshMutation = useMutation({
    mutationFn: (envId: string) => refreshEnvironment(apiClient, envId),
    onSuccess: () => {
      toast.success(t('environments.refreshStarted'))
      queryClient.invalidateQueries({ queryKey: ['environments'] })
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const testMutation = useMutation({
    mutationFn: (envId: string) => testEnvironmentConnection(apiClient, envId),
    onSuccess: (result: ConnectionTestResult) => {
      if (result.ok) {
        toast.success(t('environments.connectionOk'))
      } else {
        toast.error(result.error || t('errors.generic'))
      }
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (envId: string) => archiveEnvironment(apiClient, envId),
    onSuccess: () => {
      toast.success(t('environments.deleted'))
      queryClient.invalidateQueries({ queryKey: ['environments'] })
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const handleRefreshConfirm = () => {
    if (pendingRefresh) {
      refreshMutation.mutate(pendingRefresh.id)
      setPendingRefresh(null)
    }
  }

  const handleDeleteConfirm = () => {
    if (pendingDelete) {
      deleteMutation.mutate(pendingDelete.id)
      setPendingDelete(null)
    }
  }

  const sourceName = (env: Environment): string => {
    if (env.remote_base_url) {
      return env.remote_tenant_slug
        ? `${env.remote_base_url} (${env.remote_tenant_slug})`
        : env.remote_base_url
    }
    if (env.source_env_id) {
      const source = environments.find((e) => e.id === env.source_env_id)
      return source?.name || env.source_env_id
    }
    return '—'
  }

  return (
    <div data-testid="sandbox-list">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="m-0 text-xl font-semibold text-foreground">
          {t('environments.tabSandboxes')}
        </h2>
        <button
          type="button"
          className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={() => setShowCreateDialog(true)}
          data-testid="create-environment-button"
        >
          <Plus size={16} />
          {t('environments.newEnvironment')}
        </button>
      </div>

      {isLoading && (
        <div className="flex min-h-[300px] items-center justify-center">
          <LoadingSpinner label={t('common.loading')} />
        </div>
      )}

      {error && (
        <div className="py-4">
          <ErrorMessage error={error as Error} onRetry={() => refetch()} />
        </div>
      )}

      {!isLoading && !error && environments.length === 0 && (
        <div
          className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card px-8 py-16 text-center text-muted-foreground"
          data-testid="environments-empty"
        >
          <p>{t('environments.noEnvironments')}</p>
          <p className="text-sm">{t('environments.noEnvironmentsHint')}</p>
        </div>
      )}

      {!isLoading && !error && environments.length > 0 && (
        <div
          className="overflow-x-auto rounded-lg border border-border"
          data-testid="environments-table"
        >
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="bg-muted">
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                  {t('environments.name')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                  {t('environments.type')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                  {t('environments.status')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                  {t('environments.source')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                  {t('environments.created')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                  {t('common.actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {environments.map((env) => (
                <tr key={env.id} className="hover:bg-accent/50" data-testid={`env-row-${env.id}`}>
                  <td className="px-4 py-3 border-b border-border">
                    <div className="font-medium text-foreground">{env.name}</div>
                    {env.description && (
                      <div className="text-xs text-muted-foreground">{env.description}</div>
                    )}
                  </td>
                  <td className="px-4 py-3 border-b border-border">
                    <EnvironmentTypeBadge type={env.type} />
                  </td>
                  <td className="px-4 py-3 border-b border-border">
                    <EnvironmentStatusBadge status={env.status} />
                  </td>
                  <td className="px-4 py-3 text-foreground border-b border-border">
                    {sourceName(env)}
                  </td>
                  <td className="px-4 py-3 text-foreground border-b border-border">
                    {env.created_at
                      ? formatDate(new Date(env.created_at), {
                          year: 'numeric',
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                        })
                      : '—'}
                  </td>
                  <td className="px-4 py-3 border-b border-border">
                    <div className="flex items-center gap-1">
                      {env.type === 'SANDBOX' && (
                        <button
                          type="button"
                          className="inline-flex items-center gap-1 rounded-md px-2 py-1.5 text-sm font-medium text-primary hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
                          onClick={() => setPendingRefresh(env)}
                          disabled={env.status !== 'ACTIVE' || refreshMutation.isPending}
                          aria-label={t('environments.refresh')}
                          data-testid={`refresh-env-${env.id}`}
                        >
                          <RefreshCw size={14} />
                          {t('environments.refresh')}
                        </button>
                      )}
                      {env.remote_base_url && (
                        <button
                          type="button"
                          className="inline-flex items-center gap-1 rounded-md px-2 py-1.5 text-sm font-medium text-primary hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
                          onClick={() => testMutation.mutate(env.id)}
                          disabled={testMutation.isPending}
                          aria-label={t('environments.testConnection')}
                          data-testid={`test-env-${env.id}`}
                        >
                          <Plug size={14} />
                          {t('environments.testConnection')}
                        </button>
                      )}
                      {env.status !== 'ARCHIVED' && (
                        <button
                          type="button"
                          className="inline-flex items-center gap-1 rounded-md px-2 py-1.5 text-sm font-medium text-destructive hover:bg-destructive/10 disabled:cursor-not-allowed disabled:opacity-50"
                          onClick={() => setPendingDelete(env)}
                          disabled={deleteMutation.isPending}
                          aria-label={t('environments.delete')}
                          data-testid={`delete-env-${env.id}`}
                        >
                          <Trash2 size={14} />
                          {t('environments.delete')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showCreateDialog && (
        <CreateEnvironmentDialog
          onClose={() => setShowCreateDialog(false)}
          onCreated={() => queryClient.invalidateQueries({ queryKey: ['environments'] })}
        />
      )}

      <ConfirmDialog
        open={pendingRefresh !== null}
        title={t('environments.refreshTitle')}
        message={
          pendingRefresh ? t('environments.refreshMessage', { name: pendingRefresh.name }) : ''
        }
        confirmLabel={t('environments.refresh')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleRefreshConfirm}
        onCancel={() => setPendingRefresh(null)}
        variant="danger"
      />

      <ConfirmDialog
        open={pendingDelete !== null}
        title={t('environments.deleteTitle')}
        message={pendingDelete ? t('environments.deleteMessage', { name: pendingDelete.name }) : ''}
        confirmLabel={t('environments.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setPendingDelete(null)}
        variant="danger"
      />
    </div>
  )
}

export default SandboxList
