/**
 * PromotionHistory Component
 *
 * Lists past and in-flight metadata promotions with status polling, and a
 * detail modal showing per-item results. Supports the second-user half of the
 * four-eyes flow (approve a PENDING promotion created by someone else,
 * execute an APPROVED one) plus rollback of COMPLETED promotions that have a
 * target snapshot (remote targets cannot roll back — the API answers 409).
 */

import React, { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useAuth } from '../../context/AuthContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import {
  approvePromotion,
  executePromotion,
  fetchPromotionItems,
  fetchPromotions,
  rollbackPromotion,
} from './types'
import type { Promotion, PromotionStatus } from './types'

/**
 * Promotion status badge — pill with leading dot per DESIGN.md §6.
 */
export function PromotionStatusBadge({ status }: { status: PromotionStatus }): React.ReactElement {
  const { t } = useI18n()
  const statusLabels: Record<PromotionStatus, string> = {
    PENDING: t('environments.promotionStatuses.pending'),
    APPROVED: t('environments.promotionStatuses.approved'),
    IN_PROGRESS: t('environments.promotionStatuses.inProgress'),
    COMPLETED: t('environments.promotionStatuses.completed'),
    FAILED: t('environments.promotionStatuses.failed'),
    ROLLED_BACK: t('environments.promotionStatuses.rolledBack'),
  }

  const statusColorMap: Record<PromotionStatus, string> = {
    PENDING: 'bg-amber-500/15 text-amber-700 border-amber-500/40 dark:text-amber-300',
    APPROVED: 'bg-blue-500/15 text-blue-700 border-blue-500/40 dark:text-blue-300',
    IN_PROGRESS: 'bg-amber-500/15 text-amber-700 border-amber-500/40 dark:text-amber-300',
    COMPLETED: 'bg-emerald-500/15 text-emerald-700 border-emerald-500/40 dark:text-emerald-300',
    FAILED: 'bg-destructive/15 text-destructive border-destructive/40',
    ROLLED_BACK: 'bg-slate-500/15 text-slate-700 border-slate-500/40 dark:text-slate-300',
  }

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 h-[22px] px-2.5 rounded text-[11px] font-semibold border',
        statusColorMap[status]
      )}
      data-testid="promotion-status-badge"
    >
      <span className="size-1.5 rounded-full bg-current" />
      {statusLabels[status] || status}
    </span>
  )
}

/**
 * Per-item results table shared by the wizard and the history detail modal.
 */
export function PromotionItemsTable({ promotionId }: { promotionId: string }): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()

  const {
    data: items = [],
    isLoading,
    error,
  } = useQuery({
    queryKey: ['promotion-items', promotionId],
    queryFn: () => fetchPromotionItems(apiClient, promotionId),
  })

  if (isLoading) {
    return (
      <div className="flex justify-center p-4">
        <LoadingSpinner size="small" label={t('common.loading')} />
      </div>
    )
  }

  if (error) {
    return <ErrorMessage error={error as Error} />
  }

  if (items.length === 0) {
    return (
      <p className="m-0 text-sm text-muted-foreground" data-testid="promotion-items-empty">
        —
      </p>
    )
  }

  return (
    <div
      className="overflow-x-auto rounded-md border border-border"
      data-testid="promotion-items-table"
    >
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="bg-muted">
            <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('environments.itemType')}
            </th>
            <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('environments.itemName')}
            </th>
            <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('environments.status')}
            </th>
            <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('environments.itemError')}
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id} data-testid={`promotion-item-${item.id}`}>
              <td className="px-3 py-2 text-foreground border-b border-border">{item.item_type}</td>
              <td className="px-3 py-2 font-medium text-foreground border-b border-border">
                {item.item_name}
              </td>
              <td className="px-3 py-2 text-foreground border-b border-border">{item.status}</td>
              <td className="px-3 py-2 text-destructive border-b border-border">
                {item.error_message || '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Detail modal for a single promotion.
 */
interface PromotionDetailsModalProps {
  promotion: Promotion
  onClose: () => void
}

function PromotionDetailsModal({
  promotion,
  onClose,
}: PromotionDetailsModalProps): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { apiClient } = useApi()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [pendingRollback, setPendingRollback] = useState(false)
  const [pendingExecute, setPendingExecute] = useState(false)

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['promotions'] })
    queryClient.invalidateQueries({ queryKey: ['promotion-items', promotion.id] })
  }

  const approveMutation = useMutation({
    mutationFn: () => approvePromotion(apiClient, promotion.id),
    onSuccess: () => {
      toast.success(t('environments.approvedToast'))
      invalidate()
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const executeMutation = useMutation({
    mutationFn: () => executePromotion(apiClient, promotion.id),
    onSuccess: () => {
      toast.success(t('environments.executeStartedToast'))
      invalidate()
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const rollbackMutation = useMutation({
    mutationFn: () => rollbackPromotion(apiClient, promotion.id),
    onSuccess: () => {
      toast.success(t('environments.rolledBackToast'))
      invalidate()
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const isCreator = !!user?.id && promotion.promoted_by === user.id
  const canApprove = promotion.status === 'PENDING'
  const canExecute = promotion.status === 'APPROVED'
  const canRollback = promotion.status === 'COMPLETED' && !!promotion.target_snapshot_id
  const showItems =
    promotion.status === 'COMPLETED' ||
    promotion.status === 'FAILED' ||
    promotion.status === 'ROLLED_BACK' ||
    promotion.status === 'IN_PROGRESS'

  const handleExecuteClick = () => {
    if (promotion.conflict_mode === 'OVERWRITE') {
      setPendingExecute(true)
    } else {
      executeMutation.mutate()
    }
  }

  const overviewField = (label: string, value: React.ReactNode) => (
    <div className="flex flex-col gap-1">
      <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
        {label}
      </span>
      <span className="text-sm text-foreground">{value ?? '—'}</span>
    </div>
  )

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
      onKeyDown={(e) => e.key === 'Escape' && onClose()}
      role="presentation"
      data-testid="promotion-details-modal"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="w-full max-w-[640px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="promotion-details-title"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="promotion-details-title" className="m-0 text-lg font-semibold text-foreground">
            {t('environments.promotionDetails')}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded-md border-none bg-transparent text-xl text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="close-promotion-details-button"
          >
            ×
          </button>
        </div>

        <div className="p-6">
          <div className="mb-6 grid grid-cols-2 gap-4 max-sm:grid-cols-1">
            {overviewField(
              t('environments.sourceTarget'),
              `${promotion.source_env_name || '—'} → ${promotion.target_env_name || '—'}`
            )}
            {overviewField(
              t('environments.status'),
              <PromotionStatusBadge status={promotion.status} />
            )}
            {overviewField(t('environments.promotionType'), promotion.promotion_type)}
            {overviewField(t('environments.conflictMode'), promotion.conflict_mode)}
            {overviewField(t('environments.promotedBy'), promotion.promoted_by || '—')}
            {overviewField(t('environments.approvedBy'), promotion.approved_by || '—')}
            {overviewField(
              t('environments.created'),
              promotion.created_at
                ? formatDate(new Date(promotion.created_at), {
                    year: 'numeric',
                    month: 'short',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                  })
                : '—'
            )}
            {overviewField(
              t('environments.completedAt'),
              promotion.completed_at
                ? formatDate(new Date(promotion.completed_at), {
                    year: 'numeric',
                    month: 'short',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                  })
                : '—'
            )}
            {overviewField(
              t('environments.items'),
              `${promotion.items_promoted ?? 0} / ${promotion.items_skipped ?? 0} / ${promotion.items_failed ?? 0}`
            )}
          </div>

          {promotion.error_message && (
            <div
              className="mb-6 rounded-md border border-destructive/30 bg-destructive/10 p-4"
              data-testid="promotion-error"
            >
              <span className="font-semibold text-destructive">{t('common.error')}:</span>
              <span className="ml-2 text-sm text-destructive">{promotion.error_message}</span>
            </div>
          )}

          {canApprove && isCreator && (
            <p
              className="mb-4 text-sm text-muted-foreground"
              data-testid="detail-creator-approve-hint"
            >
              {t('environments.creatorCannotApprove')}
            </p>
          )}

          {showItems && (
            <div className="mb-2">
              <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                {t('environments.itemResults')}
              </h3>
              <PromotionItemsTable promotionId={promotion.id} />
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-border p-6">
          {canApprove && (
            <button
              type="button"
              className="rounded-md bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
              onClick={() => approveMutation.mutate()}
              disabled={isCreator || approveMutation.isPending}
              data-testid="detail-approve-button"
            >
              {approveMutation.isPending ? t('common.loading') : t('environments.approve')}
            </button>
          )}
          {canExecute && (
            <button
              type="button"
              className="rounded-md bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
              onClick={handleExecuteClick}
              disabled={executeMutation.isPending}
              data-testid="detail-execute-button"
            >
              {executeMutation.isPending ? t('common.loading') : t('environments.execute')}
            </button>
          )}
          {canRollback && (
            <button
              type="button"
              className="rounded-md border border-destructive px-5 py-2.5 text-sm font-medium text-destructive hover:bg-destructive hover:text-destructive-foreground disabled:cursor-not-allowed disabled:opacity-50"
              onClick={() => setPendingRollback(true)}
              disabled={rollbackMutation.isPending}
              data-testid="rollback-promotion-button"
            >
              {rollbackMutation.isPending ? t('common.loading') : t('environments.rollback')}
            </button>
          )}
          <button
            type="button"
            className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
            onClick={onClose}
            data-testid="close-promotion-details-footer-button"
          >
            {t('common.close')}
          </button>
        </div>
      </div>

      <ConfirmDialog
        open={pendingRollback}
        title={t('environments.rollbackTitle')}
        message={t('environments.rollbackMessage')}
        confirmLabel={t('environments.rollback')}
        cancelLabel={t('common.cancel')}
        onConfirm={() => {
          setPendingRollback(false)
          rollbackMutation.mutate()
        }}
        onCancel={() => setPendingRollback(false)}
        variant="danger"
      />

      <ConfirmDialog
        open={pendingExecute}
        title={t('environments.executeTitle')}
        message={t('environments.executeOverwriteMessage')}
        confirmLabel={t('environments.execute')}
        cancelLabel={t('common.cancel')}
        onConfirm={() => {
          setPendingExecute(false)
          executeMutation.mutate()
        }}
        onCancel={() => setPendingExecute(false)}
        variant="danger"
      />
    </div>
  )
}

/**
 * PromotionHistory — the promotion list of the "Promotions" tab.
 */
export function PromotionHistory(): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { apiClient } = useApi()
  const [selectedPromotionId, setSelectedPromotionId] = useState<string | null>(null)

  const {
    data: promotions = [],
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['promotions'],
    queryFn: () => fetchPromotions(apiClient),
    // Poll while any promotion is executing
    refetchInterval: (query) => {
      const rows = query.state.data
      const hasRunning = Array.isArray(rows) && rows.some((p) => p.status === 'IN_PROGRESS')
      return hasRunning ? 3000 : false
    },
  })

  const selectedPromotion = useMemo(
    () => promotions.find((p) => p.id === selectedPromotionId) || null,
    [promotions, selectedPromotionId]
  )

  if (isLoading) {
    return (
      <div className="flex min-h-[300px] items-center justify-center">
        <LoadingSpinner label={t('common.loading')} />
      </div>
    )
  }

  if (error) {
    return (
      <div className="py-4">
        <ErrorMessage error={error as Error} onRetry={() => refetch()} />
      </div>
    )
  }

  if (promotions.length === 0) {
    return (
      <div
        className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card px-8 py-16 text-center text-muted-foreground"
        data-testid="promotions-empty"
      >
        <p>{t('environments.noPromotions')}</p>
        <p className="text-sm">{t('environments.noPromotionsHint')}</p>
      </div>
    )
  }

  return (
    <div data-testid="promotion-history">
      <div
        className="overflow-x-auto rounded-lg border border-border"
        data-testid="promotions-table"
      >
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="bg-muted">
              <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                {t('environments.sourceTarget')}
              </th>
              <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                {t('environments.promotionType')}
              </th>
              <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                {t('environments.conflictMode')}
              </th>
              <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                {t('environments.status')}
              </th>
              <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
                {t('environments.items')}
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
            {promotions.map((promotion) => (
              <tr
                key={promotion.id}
                className="hover:bg-accent/50"
                data-testid={`promotion-row-${promotion.id}`}
              >
                <td className="px-4 py-3 font-medium text-foreground border-b border-border">
                  {promotion.source_env_name || '—'} → {promotion.target_env_name || '—'}
                </td>
                <td className="px-4 py-3 text-foreground border-b border-border">
                  {promotion.promotion_type}
                </td>
                <td className="px-4 py-3 text-foreground border-b border-border">
                  {promotion.conflict_mode}
                </td>
                <td className="px-4 py-3 border-b border-border">
                  <PromotionStatusBadge status={promotion.status} />
                </td>
                <td className="px-4 py-3 font-mono tabular-nums text-foreground border-b border-border">
                  {promotion.items_promoted ?? 0} / {promotion.items_skipped ?? 0} /{' '}
                  {promotion.items_failed ?? 0}
                </td>
                <td className="px-4 py-3 text-foreground border-b border-border">
                  {promotion.created_at
                    ? formatDate(new Date(promotion.created_at), {
                        year: 'numeric',
                        month: 'short',
                        day: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                      })
                    : '—'}
                </td>
                <td className="px-4 py-3 border-b border-border">
                  <button
                    type="button"
                    className="rounded-md px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/10 hover:underline"
                    onClick={() => setSelectedPromotionId(promotion.id)}
                    aria-label={t('environments.viewDetails')}
                    data-testid={`view-promotion-${promotion.id}`}
                  >
                    {t('environments.viewDetails')}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selectedPromotion && (
        <PromotionDetailsModal
          promotion={selectedPromotion}
          onClose={() => setSelectedPromotionId(null)}
        />
      )}
    </div>
  )
}
