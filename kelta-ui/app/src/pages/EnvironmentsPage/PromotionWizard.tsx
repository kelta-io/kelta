/**
 * PromotionWizard Component
 *
 * Guided flow for promoting sandbox metadata to another environment:
 * 1. Pick a source sandbox and a target environment (defaults to the
 *    sandbox's parent).
 * 2. Review the diff (GET /api/promotions/preview) and choose FULL or
 *    SELECTIVE (per-change checkboxes) plus the conflict mode.
 * 3. Create the promotion, approve it (four-eyes: the creator cannot approve
 *    their own promotion — the button is disabled), execute it (destructive
 *    confirm when conflict mode is OVERWRITE), poll until it completes, and
 *    show the per-item results.
 */

import React, { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useAuth } from '../../context/AuthContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { PromotionStatusBadge, PromotionItemsTable } from './PromotionHistory'
import {
  approvePromotion,
  createPromotion,
  executePromotion,
  fetchEnvironments,
  fetchPromotion,
  fetchPromotionPreview,
} from './types'
import type {
  ConflictMode,
  CreatePromotionRequest,
  DiffChange,
  Promotion,
  PromotionType,
} from './types'

type WizardStep = 'select' | 'changes' | 'run'

export interface PromotionWizardProps {
  /** Called when the wizard should close (the parent refreshes the list) */
  onClose: () => void
}

const changeKey = (change: DiffChange): string => `${change.type}::${change.name}`

/**
 * Colored label for a diff change action (ADD / MODIFY / REMOVE).
 */
function ChangeActionLabel({ action }: { action: DiffChange['action'] }): React.ReactElement {
  const { t } = useI18n()
  const actionLabels: Record<DiffChange['action'], string> = {
    ADD: t('environments.changeActions.add'),
    MODIFY: t('environments.changeActions.modify'),
    REMOVE: t('environments.changeActions.remove'),
  }
  const actionColorMap: Record<DiffChange['action'], string> = {
    ADD: 'text-emerald-700 bg-emerald-500/15 dark:text-emerald-300',
    MODIFY: 'text-amber-700 bg-amber-500/15 dark:text-amber-300',
    REMOVE: 'text-destructive bg-destructive/15',
  }
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-2 py-0.5 text-[11px] font-semibold uppercase',
        actionColorMap[action]
      )}
      data-testid="change-action-label"
    >
      {actionLabels[action] || action}
    </span>
  )
}

export function PromotionWizard({ onClose }: PromotionWizardProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { user } = useAuth()
  const queryClient = useQueryClient()

  const [step, setStep] = useState<WizardStep>('select')
  const [sourceEnvId, setSourceEnvId] = useState('')
  const [targetEnvId, setTargetEnvId] = useState('')
  const [promotionType, setPromotionType] = useState<PromotionType>('FULL')
  const [conflictMode, setConflictMode] = useState<ConflictMode>('SKIP')
  const [selectedKeys, setSelectedKeys] = useState<Record<string, boolean>>({})
  const [promotionId, setPromotionId] = useState<string | null>(null)
  const [hasExecuted, setHasExecuted] = useState(false)
  const [pendingExecute, setPendingExecute] = useState(false)

  const { data: environments = [], isLoading: environmentsLoading } = useQuery({
    queryKey: ['environments'],
    queryFn: () => fetchEnvironments(apiClient),
  })

  const sandboxes = useMemo(
    () => environments.filter((e) => e.type === 'SANDBOX' && e.status === 'ACTIVE'),
    [environments]
  )

  const targets = useMemo(
    () => environments.filter((e) => e.id !== sourceEnvId && e.status !== 'ARCHIVED'),
    [environments, sourceEnvId]
  )

  const {
    data: diff,
    isLoading: diffLoading,
    error: diffError,
  } = useQuery({
    queryKey: ['promotion-preview', sourceEnvId],
    queryFn: () => fetchPromotionPreview(apiClient, sourceEnvId),
    enabled: step === 'changes' && sourceEnvId !== '',
  })

  const changes = useMemo(() => diff?.changes ?? [], [diff])

  const { data: promotion } = useQuery({
    queryKey: ['promotion', promotionId],
    queryFn: () => fetchPromotion(apiClient, promotionId as string),
    enabled: promotionId !== null,
    // Poll while the promotion executes (also right after execute is
    // accepted, while the status may still read APPROVED)
    refetchInterval: (query) => {
      const current = query.state.data
      if (!current) return false
      if (current.status === 'IN_PROGRESS') return 2000
      if (hasExecuted && current.status === 'APPROVED') return 2000
      return false
    },
  })

  const createMutation = useMutation({
    mutationFn: (request: CreatePromotionRequest) => createPromotion(apiClient, request),
    onSuccess: (created: Promotion) => {
      queryClient.setQueryData(['promotion', created.id], created)
      setPromotionId(created.id)
      setStep('run')
    },
  })

  const approveMutation = useMutation({
    mutationFn: (id: string) => approvePromotion(apiClient, id),
    onSuccess: () => {
      toast.success(t('environments.approvedToast'))
      queryClient.invalidateQueries({ queryKey: ['promotion', promotionId] })
    },
    onError: (mutationError: Error) => {
      // 409 — the creator cannot approve their own promotion
      toast.error(mutationError.message)
    },
  })

  const executeMutation = useMutation({
    mutationFn: (id: string) => executePromotion(apiClient, id),
    onSuccess: () => {
      setHasExecuted(true)
      queryClient.invalidateQueries({ queryKey: ['promotion', promotionId] })
    },
    onError: (mutationError: Error) => {
      toast.error(mutationError.message)
    },
  })

  const handleSourceChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const nextSourceId = e.target.value
    setSourceEnvId(nextSourceId)
    setSelectedKeys({})
    // Default the target to the sandbox's parent environment
    const source = environments.find((env) => env.id === nextSourceId)
    setTargetEnvId(source?.source_env_id || '')
  }

  const handleToggleChange = (key: string) => {
    setSelectedKeys((prev) => ({ ...prev, [key]: !prev[key] }))
  }

  const handleToggleAll = () => {
    if (changes.every((c) => selectedKeys[changeKey(c)])) {
      setSelectedKeys({})
    } else {
      setSelectedKeys(Object.fromEntries(changes.map((c) => [changeKey(c), true])))
    }
  }

  const selectedCount = changes.filter((c) => selectedKeys[changeKey(c)]).length

  const canCreate =
    changes.length > 0 &&
    (promotionType === 'FULL' || selectedCount > 0) &&
    !createMutation.isPending

  const handleCreate = () => {
    const request: CreatePromotionRequest = {
      sourceEnvId,
      targetEnvId,
      promotionType,
      conflictMode,
    }
    if (promotionType === 'SELECTIVE') {
      request.items = changes
        .filter((c) => selectedKeys[changeKey(c)])
        .map((c) => ({ itemType: c.type, itemName: c.name }))
    }
    createMutation.mutate(request)
  }

  const handleExecuteClick = () => {
    if (!promotionId) return
    if (conflictMode === 'OVERWRITE') {
      setPendingExecute(true)
    } else {
      executeMutation.mutate(promotionId)
    }
  }

  const isCreator = !!user?.id && promotion?.promoted_by === user.id
  const isTerminal =
    promotion?.status === 'COMPLETED' ||
    promotion?.status === 'FAILED' ||
    promotion?.status === 'ROLLED_BACK'
  const isRunning =
    promotion?.status === 'IN_PROGRESS' || (hasExecuted && promotion?.status === 'APPROVED')

  const selectClasses =
    'rounded-md border border-border bg-background px-3.5 py-2.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10 disabled:cursor-not-allowed disabled:opacity-50'

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="promotion-wizard-title"
      data-testid="promotion-wizard-modal"
    >
      <div
        className="w-full max-w-[640px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        role="document"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="promotion-wizard-title" className="m-0 text-lg font-semibold text-foreground">
            {t('environments.promotionWizardTitle')}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded-md border-none bg-transparent text-xl text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="close-wizard-button"
          >
            ×
          </button>
        </div>

        <div className="p-6">
          {/* Step 1 — pick source sandbox + target environment */}
          {step === 'select' && (
            <div className="flex flex-col gap-5" data-testid="wizard-step-select">
              {environmentsLoading ? (
                <div className="flex min-h-[160px] items-center justify-center">
                  <LoadingSpinner label={t('common.loading')} />
                </div>
              ) : (
                <>
                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="wizard-source-select"
                      className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                    >
                      {t('environments.sourceEnvironment')}
                      <span className="ml-0.5 text-destructive">*</span>
                    </label>
                    <select
                      id="wizard-source-select"
                      className={selectClasses}
                      value={sourceEnvId}
                      onChange={handleSourceChange}
                      data-testid="wizard-source-select"
                    >
                      <option value="">{t('environments.selectSource')}</option>
                      {sandboxes.map((env) => (
                        <option key={env.id} value={env.id}>
                          {env.name}
                        </option>
                      ))}
                    </select>
                    {sandboxes.length === 0 && (
                      <span
                        className="text-xs text-amber-600 dark:text-amber-400"
                        data-testid="no-sandboxes-warning"
                      >
                        {t('environments.noSandboxes')}
                      </span>
                    )}
                  </div>

                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="wizard-target-select"
                      className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                    >
                      {t('environments.targetEnvironment')}
                      <span className="ml-0.5 text-destructive">*</span>
                    </label>
                    <select
                      id="wizard-target-select"
                      className={selectClasses}
                      value={targetEnvId}
                      onChange={(e) => setTargetEnvId(e.target.value)}
                      disabled={sourceEnvId === ''}
                      data-testid="wizard-target-select"
                    >
                      <option value="">{t('environments.selectTarget')}</option>
                      {targets.map((env) => (
                        <option key={env.id} value={env.id}>
                          {env.name}
                        </option>
                      ))}
                    </select>
                  </div>
                </>
              )}
            </div>
          )}

          {/* Step 2 — review changes, promotion type + conflict mode */}
          {step === 'changes' && (
            <div className="flex flex-col gap-5" data-testid="wizard-step-changes">
              {diffLoading && (
                <div className="flex min-h-[160px] items-center justify-center">
                  <LoadingSpinner label={t('common.loading')} />
                </div>
              )}

              {diffError != null && <ErrorMessage error={diffError as Error} />}

              {!diffLoading && !diffError && (
                <>
                  <div>
                    <div className="mb-3 flex items-center justify-between">
                      <h3 className="m-0 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                        {t('environments.changes')} ({changes.length})
                      </h3>
                      {promotionType === 'SELECTIVE' && changes.length > 0 && (
                        <button
                          type="button"
                          className="px-2 py-1 text-xs text-primary hover:underline"
                          onClick={handleToggleAll}
                          data-testid="wizard-toggle-all-changes"
                        >
                          {changes.every((c) => selectedKeys[changeKey(c)])
                            ? t('common.deselectAll')
                            : t('common.selectAll')}
                        </button>
                      )}
                    </div>

                    {changes.length === 0 ? (
                      <p
                        className="m-0 text-sm italic text-muted-foreground"
                        data-testid="wizard-no-changes"
                      >
                        {t('environments.noChanges')}
                      </p>
                    ) : (
                      <div
                        className="flex max-h-[240px] flex-col gap-1 overflow-y-auto rounded-md border border-border p-2"
                        data-testid="wizard-diff-list"
                      >
                        {changes.map((change) => {
                          const key = changeKey(change)
                          return (
                            <label
                              key={key}
                              className={cn(
                                'flex items-center gap-2 rounded px-2 py-1.5',
                                promotionType === 'SELECTIVE' && 'cursor-pointer hover:bg-accent'
                              )}
                              data-testid={`diff-change-${key}`}
                            >
                              {promotionType === 'SELECTIVE' && (
                                <input
                                  type="checkbox"
                                  className="h-4 w-4 shrink-0 cursor-pointer"
                                  checked={!!selectedKeys[key]}
                                  onChange={() => handleToggleChange(key)}
                                  data-testid={`change-checkbox-${key}`}
                                />
                              )}
                              <ChangeActionLabel action={change.action} />
                              <span className="text-xs text-muted-foreground">{change.type}</span>
                              <span className="truncate text-sm font-medium text-foreground">
                                {change.name}
                              </span>
                            </label>
                          )
                        })}
                      </div>
                    )}
                  </div>

                  <div className="flex flex-col gap-2">
                    <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
                      {t('environments.promotionType')}
                    </span>
                    <div className="grid grid-cols-2 gap-3 max-sm:grid-cols-1" role="radiogroup">
                      {(
                        [
                          ['FULL', t('environments.typeFull'), t('environments.typeFullHint')],
                          [
                            'SELECTIVE',
                            t('environments.typeSelective'),
                            t('environments.typeSelectiveHint'),
                          ],
                        ] as Array<[PromotionType, string, string]>
                      ).map(([key, label, hint]) => (
                        <button
                          key={key}
                          type="button"
                          role="radio"
                          aria-checked={promotionType === key}
                          className={cn(
                            'flex flex-col items-start gap-1 rounded-md border p-3 text-left transition-colors',
                            promotionType === key
                              ? 'border-primary bg-primary/5'
                              : 'border-border bg-background hover:bg-muted'
                          )}
                          onClick={() => setPromotionType(key)}
                          data-testid={`wizard-type-${key.toLowerCase()}`}
                        >
                          <span className="text-sm font-medium text-foreground">{label}</span>
                          <span className="text-xs text-muted-foreground">{hint}</span>
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="wizard-conflict-mode"
                      className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                    >
                      {t('environments.conflictMode')}
                    </label>
                    <select
                      id="wizard-conflict-mode"
                      className={selectClasses}
                      value={conflictMode}
                      onChange={(e) => setConflictMode(e.target.value as ConflictMode)}
                      data-testid="wizard-conflict-mode"
                    >
                      <option value="SKIP">{t('environments.conflictSkip')}</option>
                      <option value="OVERWRITE">{t('environments.conflictOverwrite')}</option>
                    </select>
                  </div>

                  {createMutation.error && (
                    <div
                      className="rounded-md border border-destructive/30 bg-destructive/10 p-4"
                      data-testid="create-promotion-error"
                    >
                      <ErrorMessage error={createMutation.error as Error} />
                    </div>
                  )}
                </>
              )}
            </div>
          )}

          {/* Step 3 — approve, execute, poll, results */}
          {step === 'run' && promotion && (
            <div className="flex flex-col gap-5" data-testid="wizard-step-run">
              <div className="flex items-center gap-3" data-testid="promotion-status">
                <PromotionStatusBadge status={promotion.status} />
                <span className="text-sm text-foreground">
                  {promotion.source_env_name || '—'} → {promotion.target_env_name || '—'}
                </span>
              </div>

              {promotion.status === 'PENDING' && isCreator && (
                <p className="m-0 text-sm text-muted-foreground" data-testid="creator-approve-hint">
                  {t('environments.creatorCannotApprove')}
                </p>
              )}

              {isRunning && (
                <div
                  className="flex items-center gap-2 text-sm text-foreground"
                  data-testid="promotion-executing"
                >
                  <LoadingSpinner size="small" />
                  <span>{t('environments.executing')}</span>
                </div>
              )}

              {promotion.status === 'COMPLETED' && (
                <div
                  className="flex items-center gap-2 text-sm font-medium text-emerald-600 dark:text-emerald-400"
                  data-testid="promotion-success-message"
                >
                  <span className="text-lg">✓</span>
                  <span>{t('environments.promotionCompleted')}</span>
                </div>
              )}

              {promotion.status === 'FAILED' && (
                <div
                  className="flex items-center gap-2 text-sm font-medium text-destructive"
                  data-testid="promotion-failure-message"
                >
                  <span className="text-lg">✗</span>
                  <span>{t('environments.promotionFailed')}</span>
                </div>
              )}

              {promotion.error_message && (
                <div
                  className="rounded-md border border-destructive/30 bg-destructive/10 p-4"
                  data-testid="promotion-run-error"
                >
                  <span className="font-semibold text-destructive">{t('common.error')}:</span>
                  <span className="ml-2 text-sm text-destructive">{promotion.error_message}</span>
                </div>
              )}

              {isTerminal && promotionId && (
                <div>
                  <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                    {t('environments.itemResults')}
                  </h3>
                  <PromotionItemsTable promotionId={promotionId} />
                </div>
              )}
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-border p-6">
          {step === 'select' && (
            <>
              <button
                type="button"
                className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
                onClick={onClose}
                data-testid="wizard-cancel-button"
              >
                {t('common.cancel')}
              </button>
              <button
                type="button"
                className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                onClick={() => setStep('changes')}
                disabled={sourceEnvId === '' || targetEnvId === ''}
                data-testid="wizard-next-button"
              >
                {t('common.next')}
              </button>
            </>
          )}

          {step === 'changes' && (
            <>
              <button
                type="button"
                className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
                onClick={() => setStep('select')}
                data-testid="wizard-back-button"
              >
                {t('common.back')}
              </button>
              <button
                type="button"
                className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                onClick={handleCreate}
                disabled={!canCreate}
                data-testid="wizard-create-button"
              >
                {createMutation.isPending ? t('common.loading') : t('environments.createPromotion')}
              </button>
            </>
          )}

          {step === 'run' && promotion && (
            <>
              {promotion.status === 'PENDING' && (
                <button
                  type="button"
                  className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={() => promotionId && approveMutation.mutate(promotionId)}
                  disabled={isCreator || approveMutation.isPending}
                  data-testid="approve-promotion-button"
                >
                  {approveMutation.isPending ? t('common.loading') : t('environments.approve')}
                </button>
              )}
              {promotion.status === 'APPROVED' && !isRunning && (
                <button
                  type="button"
                  className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={handleExecuteClick}
                  disabled={executeMutation.isPending}
                  data-testid="execute-promotion-button"
                >
                  {executeMutation.isPending ? t('common.loading') : t('environments.execute')}
                </button>
              )}
              <button
                type="button"
                className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
                onClick={onClose}
                data-testid="wizard-close-button"
              >
                {t('common.close')}
              </button>
            </>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={pendingExecute}
        title={t('environments.executeTitle')}
        message={t('environments.executeOverwriteMessage')}
        confirmLabel={t('environments.execute')}
        cancelLabel={t('common.cancel')}
        onConfirm={() => {
          setPendingExecute(false)
          if (promotionId) {
            executeMutation.mutate(promotionId)
          }
        }}
        onCancel={() => setPendingExecute(false)}
        variant="danger"
      />
    </div>
  )
}
