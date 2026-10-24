/**
 * MigrationsPage Component
 *
 * Manages schema migrations with history display, planning, execution, and run details.
 * Provides migration history list with status, duration, and step details.
 * Supports migration planning with schema selection, steps preview, and risk assessment.
 * Supports migration execution with real-time progress tracking and rollback on failure.
 *
 * Requirements:
 * - 10.1: Display migration history showing all migration runs
 * - 10.2: Migration planning allows selecting source and target schemas
 * - 10.3: Migration plan displays steps to be executed
 * - 10.4: Migration plan displays estimated impact and risks
 * - 10.5: Migration execution shows real-time progress
 * - 10.6: Migration execution handles errors gracefully
 * - 10.7: Migration execution offers rollback option on failure
 * - 10.8: Display status, duration, and step details for each run
 */

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useCollectionSummaries } from '../../hooks/useCollectionSummaries'
import { LoadingSpinner, ErrorMessage } from '../../components'
import type { ApiClient } from '../../services/apiClient'
import type { KeltaClient } from '@kelta/sdk'
import { cn } from '@/lib/utils'

/**
 * Migration run status type
 */
export type MigrationStatus = 'pending' | 'running' | 'completed' | 'failed' | 'rolled_back'

/**
 * Migration step result interface
 */
export interface MigrationStepResult {
  stepOrder: number
  operation: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  details?: Record<string, unknown>
  startedAt?: string
  completedAt?: string
  error?: string
}

/**
 * Migration run interface matching the API response
 */
export interface MigrationRun {
  id: string
  planId: string
  collectionId: string
  collectionName: string
  fromVersion: number
  toVersion: number
  status: MigrationStatus
  steps: MigrationStepResult[]
  startedAt?: string
  completedAt?: string
  error?: string
}

/**
 * Migration step interface for planning
 */
interface MigrationStep {
  order: number
  operation: 'ADD_FIELD' | 'REMOVE_FIELD' | 'MODIFY_FIELD' | 'ADD_INDEX' | 'REMOVE_INDEX'
  details: Record<string, unknown>
  reversible: boolean
}

/**
 * Migration risk interface
 */
interface MigrationRisk {
  level: 'low' | 'medium' | 'high'
  description: string
}

/**
 * Migration plan interface
 */
interface MigrationPlan {
  id: string
  collectionId: string
  collectionName: string
  fromVersion: number
  toVersion: number
  steps: MigrationStep[]
  estimatedDuration: number
  estimatedRecordsAffected: number
  risks: MigrationRisk[]
}

/**
 * Collection summary for selection
 */
interface CollectionSummary {
  id: string
  name: string
  displayName: string
  currentVersion: number
  availableVersions: number[]
}

/**
 * Props for MigrationsPage component
 */
export interface MigrationsPageProps {
  /** Optional test ID for testing */
  testId?: string
}

// API functions using apiClient / keltaClient
/**
 * Normalizes a migration run coming off the generic JSON:API route (`/api/migration-runs`).
 * The stored `status` is upper-case (`COMPLETED`) while the UI works in lower-case, and the run
 * resource carries no nested `steps` (migration-steps is a separate collection). Coercing both here
 * keeps the badge, the polling terminate-check, and the step list from breaking on the raw shape.
 */
function normalizeRun(run: MigrationRun): MigrationRun {
  return {
    ...run,
    status: (String(run?.status ?? '').toLowerCase() as MigrationStatus) || 'pending',
    steps: Array.isArray(run?.steps) ? run.steps : [],
  }
}

async function fetchMigrationHistory(keltaClient: KeltaClient): Promise<MigrationRun[]> {
  const runs = (await keltaClient.admin.migrations.listRuns()) as unknown as MigrationRun[]
  return runs.map(normalizeRun)
}

async function fetchMigrationDetails(keltaClient: KeltaClient, id: string): Promise<MigrationRun> {
  const run = (await keltaClient.admin.migrations.getRun(id)) as unknown as MigrationRun
  return normalizeRun(run)
}

async function fetchCollectionVersions(
  apiClient: ApiClient,
  collectionSummaries: Array<{ id: string; name: string; displayName: string }>
): Promise<CollectionSummary[]> {
  return Promise.all(
    collectionSummaries.map(async (collection) => {
      const versions = await apiClient.getList<Record<string, unknown>>(
        `/api/collection-versions?filter[collectionId][eq]=${collection.id}`
      )
      const versionNumbers = Array.isArray(versions)
        ? versions.map((v: Record<string, unknown>) => v.version as number)
        : []

      return {
        id: collection.id,
        name: collection.name,
        displayName: collection.displayName || collection.name,
        currentVersion: versionNumbers.length > 0 ? Math.max(...versionNumbers) : 1,
        availableVersions: versionNumbers.length > 0 ? versionNumbers : [1],
      }
    })
  )
}

interface CreateMigrationPlanRequest {
  collectionId: string
  targetVersion: number
}

async function createMigrationPlan(
  apiClient: ApiClient,
  request: CreateMigrationPlanRequest
): Promise<MigrationPlan> {
  return apiClient.postResource('/api/migrations/plan', request)
}

/**
 * Execute a migration plan
 * Requirement 10.5: Migration execution shows real-time progress
 */
interface ExecuteMigrationRequest {
  planId: string
}

interface ExecuteMigrationResponse {
  runId: string
  status: MigrationStatus
}

async function executeMigration(
  apiClient: ApiClient,
  request: ExecuteMigrationRequest
): Promise<ExecuteMigrationResponse> {
  return apiClient.postResource('/api/migrations/execute', request)
}

/**
 * Rollback a failed migration
 * Requirement 10.7: Migration execution offers rollback option on failure
 */
async function rollbackMigration(apiClient: ApiClient, runId: string): Promise<MigrationRun> {
  const run = (await apiClient.postResource(
    `/api/migrations/${runId}/rollback`,
    {}
  )) as unknown as MigrationRun
  return normalizeRun(run)
}

/**
 * Get migration run status for polling
 */
async function getMigrationRunStatus(
  keltaClient: KeltaClient,
  runId: string
): Promise<MigrationRun> {
  const run = (await keltaClient.admin.migrations.getRun(runId)) as unknown as MigrationRun
  return normalizeRun(run)
}

/**
 * Status Badge Component
 */
interface StatusBadgeProps {
  status: MigrationStatus
}

function StatusBadge({ status }: StatusBadgeProps): React.ReactElement {
  const { t } = useI18n()
  const statusLabels: Record<MigrationStatus, string> = {
    pending: t('migrations.status.pending'),
    running: t('migrations.status.running'),
    completed: t('migrations.status.completed'),
    failed: t('migrations.status.failed'),
    rolled_back: t('migrations.status.rolledBack'),
  }

  const statusColorMap: Record<MigrationStatus, string> = {
    pending: 'text-amber-800 bg-amber-50 dark:text-amber-300 dark:bg-amber-950',
    running: 'text-blue-800 bg-blue-50 dark:text-blue-300 dark:bg-blue-950',
    completed: 'text-green-800 bg-green-50 dark:text-green-300 dark:bg-green-950',
    failed: 'text-red-600 bg-red-50 dark:text-red-400 dark:bg-red-950',
    rolled_back: 'text-purple-800 bg-purple-50 dark:text-purple-300 dark:bg-purple-950',
  }
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium',
        statusColorMap[status]
      )}
      data-testid="status-badge"
    >
      {statusLabels[status] || status}
    </span>
  )
}

/**
 * Step Status Badge Component
 */
interface StepStatusBadgeProps {
  status: 'pending' | 'running' | 'completed' | 'failed'
}

function StepStatusBadge({ status }: StepStatusBadgeProps): React.ReactElement {
  const { t } = useI18n()
  const statusLabels: Record<string, string> = {
    pending: t('migrations.status.pending'),
    running: t('migrations.status.running'),
    completed: t('migrations.status.completed'),
    failed: t('migrations.status.failed'),
  }

  const stepStatusColorMap: Record<string, string> = {
    pending: 'text-amber-800 bg-amber-50 dark:text-amber-300 dark:bg-amber-950',
    running: 'text-blue-800 bg-blue-50 dark:text-blue-300 dark:bg-blue-950',
    completed: 'text-green-800 bg-green-50 dark:text-green-300 dark:bg-green-950',
    failed: 'text-red-600 bg-red-50 dark:text-red-400 dark:bg-red-950',
  }
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-2 py-1 text-xs font-medium',
        stepStatusColorMap[status]
      )}
      data-testid="step-status-badge"
    >
      {statusLabels[status] || status}
    </span>
  )
}

/**
 * Risk Level Badge Component
 * Requirement 10.4: Display estimated impact and risks
 */
interface RiskBadgeProps {
  level: 'low' | 'medium' | 'high'
}

function RiskBadge({ level }: RiskBadgeProps): React.ReactElement {
  const { t } = useI18n()
  const levelLabels: Record<string, string> = {
    low: t('migrations.riskLevels.low'),
    medium: t('migrations.riskLevels.medium'),
    high: t('migrations.riskLevels.high'),
  }

  const riskColorMap: Record<string, string> = {
    low: 'text-emerald-700 bg-emerald-100 dark:text-emerald-300 dark:bg-emerald-900/40',
    medium: 'text-amber-700 bg-amber-100 dark:text-amber-300 dark:bg-amber-900/40',
    high: 'text-red-700 bg-red-100 dark:text-red-300 dark:bg-red-900/40',
  }

  return (
    <span
      className={cn(
        'inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold',
        riskColorMap[level]
      )}
      data-testid="risk-badge"
    >
      {levelLabels[level] || level}
    </span>
  )
}

/**
 * Migration Plan Display Component
 * Requirements 10.3, 10.4: Display migration steps and estimated impact/risks
 */
interface MigrationPlanDisplayProps {
  plan: MigrationPlan
  onClose: () => void
  onExecute: (plan: MigrationPlan) => void
}

function MigrationPlanDisplay({
  plan,
  onClose,
  onExecute,
}: MigrationPlanDisplayProps): React.ReactElement {
  const { t } = useI18n()

  const formatDuration = (seconds: number): string => {
    if (seconds < 60) {
      return `${seconds}s`
    } else if (seconds < 3600) {
      const minutes = Math.floor(seconds / 60)
      const secs = seconds % 60
      return secs > 0 ? `${minutes}m ${secs}s` : `${minutes}m`
    } else {
      const hours = Math.floor(seconds / 3600)
      const minutes = Math.floor((seconds % 3600) / 60)
      return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`
    }
  }

  const handleExecute = () => {
    onExecute(plan)
  }

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
      onKeyDown={(e) => e.key === 'Escape' && onClose()}
      role="presentation"
      data-testid="migration-plan-modal"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="w-full max-w-[600px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="migration-plan-title"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="migration-plan-title" className="m-0 text-lg font-semibold text-foreground">
            {t('migrations.planDetails')}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded-md border-none bg-transparent text-xl text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="close-plan-button"
          >
            ×
          </button>
        </div>

        <div className="p-6">
          {/* Overview Section */}
          <div className="mb-6">
            <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
              {t('migrations.overview')}
            </h3>
            <div className="grid grid-cols-2 gap-4 max-sm:grid-cols-1">
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  {t('migrations.collection')}
                </span>
                <span className="text-sm text-foreground">{plan.collectionName}</span>
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  {t('migrations.versionChange')}
                </span>
                <span className="text-sm text-foreground">
                  v{plan.fromVersion} → v{plan.toVersion}
                </span>
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  {t('migrations.estimatedDuration')}
                </span>
                <span className="text-sm text-foreground">
                  {formatDuration(plan.estimatedDuration)}
                </span>
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  {t('migrations.recordsAffected')}
                </span>
                <span className="text-sm text-foreground">
                  {plan.estimatedRecordsAffected.toLocaleString()}
                </span>
              </div>
            </div>
          </div>

          {/* Steps Section - Requirement 10.3 */}
          <div className="mb-6" data-testid="plan-steps-section">
            <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
              {t('migrations.steps')} ({plan.steps.length})
            </h3>
            {plan.steps.length === 0 ? (
              <p className="text-sm italic text-muted-foreground">{t('migrations.noSteps')}</p>
            ) : (
              <div className="flex flex-col gap-3" data-testid="plan-steps-list">
                {plan.steps.map((step) => (
                  <div
                    key={step.order}
                    className="rounded-md border border-border bg-muted/30 p-3"
                    data-testid={`plan-step-${step.order}`}
                  >
                    <div className="flex items-center gap-3">
                      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
                        {step.order}
                      </span>
                      <span className="text-sm font-medium text-foreground">{step.operation}</span>
                      {step.reversible ? (
                        <span
                          className="inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold text-emerald-700 bg-emerald-100 dark:text-emerald-300 dark:bg-emerald-900/40"
                          data-testid="reversible-badge"
                        >
                          {t('migrations.reversible')}
                        </span>
                      ) : (
                        <span
                          className="inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold text-red-700 bg-red-100 dark:text-red-300 dark:bg-red-900/40"
                          data-testid="irreversible-badge"
                        >
                          {t('migrations.irreversible')}
                        </span>
                      )}
                    </div>
                    {step.details && Object.keys(step.details).length > 0 && (
                      <div className="mt-2 space-y-1 border-t border-border pt-2">
                        {Object.entries(step.details).map(([key, value]) => (
                          <div key={key} className="flex gap-2 text-xs">
                            <span className="font-medium text-muted-foreground">{key}:</span>
                            <span className="text-foreground">
                              {typeof value === 'object' ? JSON.stringify(value) : String(value)}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Risks Section - Requirement 10.4 */}
          <div className="mb-6" data-testid="plan-risks-section">
            <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
              {t('migrations.risks')} ({plan.risks.length})
            </h3>
            {plan.risks.length === 0 ? (
              <p className="text-sm italic text-muted-foreground" data-testid="no-risks">
                {t('migrations.noRisks')}
              </p>
            ) : (
              <div className="flex flex-col gap-2" data-testid="plan-risks-list">
                {plan.risks.map((risk, index) => (
                  <div
                    key={index}
                    className="flex items-center gap-3 rounded-md border border-border p-3"
                    data-testid={`plan-risk-${index}`}
                  >
                    <RiskBadge level={risk.level} />
                    <span className="text-sm text-foreground">{risk.description}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-border p-6">
          <button
            type="button"
            className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
            onClick={onClose}
            data-testid="close-plan-details-button"
          >
            {t('common.close')}
          </button>
          <button
            type="button"
            className="inline-flex items-center gap-2 rounded-md bg-primary px-6 py-3 text-base font-medium text-primary-foreground hover:bg-primary/90"
            onClick={handleExecute}
            data-testid="execute-migration-button"
          >
            {t('migrations.executeMigration')}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Migration Execution Modal Component
 * Requirements 10.5, 10.6, 10.7: Execute migration with progress tracking, error handling, and rollback
 */
interface MigrationExecutionModalProps {
  plan: MigrationPlan
  onClose: () => void
  onComplete: () => void
}

function MigrationExecutionModal({
  plan,
  onClose,
  onComplete,
}: MigrationExecutionModalProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient, keltaClient } = useApi()
  const queryClient = useQueryClient()
  const [runId, setRunId] = useState<string | null>(null)
  const [currentRun, setCurrentRun] = useState<MigrationRun | null>(null)
  const [isPolling, setIsPolling] = useState(false)
  const pollingIntervalRef = useRef<NodeJS.Timeout | null>(null)

  // Execute migration mutation
  const executeMutation = useMutation({
    mutationFn: (request: ExecuteMigrationRequest) => executeMigration(apiClient, request),
    onSuccess: (response) => {
      setRunId(response.runId)
      setIsPolling(true)
    },
    onError: () => {
      // Error is handled in the UI
    },
  })

  // Rollback mutation - Requirement 10.7
  const rollbackMutation = useMutation({
    mutationFn: (runId: string) => rollbackMigration(apiClient, runId),
    onSuccess: (run) => {
      setCurrentRun(run)
      queryClient.invalidateQueries({ queryKey: ['migration-history'] })
    },
    onError: () => {
      // Error is handled in the UI
    },
  })

  // Poll for migration status - Requirement 10.5
  useEffect(() => {
    if (!isPolling || !runId) return

    const pollStatus = async () => {
      try {
        const run = await getMigrationRunStatus(keltaClient, runId)
        setCurrentRun(run)

        // Stop polling when migration is complete or failed
        if (run.status === 'completed' || run.status === 'failed' || run.status === 'rolled_back') {
          setIsPolling(false)
          queryClient.invalidateQueries({ queryKey: ['migration-history'] })
        }
      } catch {
        // Continue polling on error
      }
    }

    // Initial poll
    pollStatus()

    // Set up polling interval
    pollingIntervalRef.current = setInterval(pollStatus, 1000)

    return () => {
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current)
      }
    }
  }, [isPolling, runId, queryClient, keltaClient])

  // Start execution when modal opens
  useEffect(() => {
    if (!runId && !executeMutation.isPending && !executeMutation.isError) {
      executeMutation.mutate({ planId: plan.id })
    }
  }, [plan.id, runId, executeMutation])

  const handleRollback = () => {
    if (runId) {
      rollbackMutation.mutate(runId)
    }
  }

  const handleClose = () => {
    if (pollingIntervalRef.current) {
      clearInterval(pollingIntervalRef.current)
    }
    if (currentRun?.status === 'completed') {
      onComplete()
    }
    onClose()
  }

  // Calculate progress percentage
  const progressPercentage = useMemo(() => {
    if (!currentRun || currentRun.steps.length === 0) return 0
    const completedSteps = currentRun.steps.filter((s) => s.status === 'completed').length
    return Math.round((completedSteps / currentRun.steps.length) * 100)
  }, [currentRun])

  // Determine if rollback is available - Requirement 10.7
  const canRollback = currentRun?.status === 'failed' && !rollbackMutation.isPending

  // Determine current step
  const currentStep = currentRun?.steps.find((s) => s.status === 'running')

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="migration-execution-title"
      data-testid="migration-execution-modal"
    >
      <div
        className="w-full max-w-[600px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        role="document"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="migration-execution-title" className="m-0 text-lg font-semibold text-foreground">
            {t('migrations.executingMigration')}
          </h2>
        </div>

        <div className="p-6">
          {/* Overview */}
          <div className="mb-6">
            <div className="grid grid-cols-2 gap-4 max-sm:grid-cols-1">
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  {t('migrations.collection')}
                </span>
                <span className="text-sm text-foreground">{plan.collectionName}</span>
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  {t('migrations.versionChange')}
                </span>
                <span className="text-sm text-foreground">
                  v{plan.fromVersion} → v{plan.toVersion}
                </span>
              </div>
            </div>
          </div>

          {/* Execution Error - Requirement 10.6 */}
          {executeMutation.isError && (
            <div
              className="mb-4 rounded-md border border-destructive/30 bg-destructive/10 p-4"
              data-testid="execution-error"
            >
              <span className="font-semibold text-destructive">{t('common.error')}:</span>
              <span className="ml-2 text-sm text-destructive">
                {(executeMutation.error as Error)?.message || t('migrations.executionFailed')}
              </span>
            </div>
          )}

          {/* Progress Section - Requirement 10.5 */}
          {(isPolling || currentRun) && (
            <div className="mb-6" data-testid="execution-progress-section">
              <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                {t('migrations.progress')}
              </h3>

              {/* Progress Bar */}
              <div className="mb-4 flex items-center gap-3" data-testid="progress-container">
                <div className="relative h-2.5 flex-1 overflow-hidden rounded-full bg-muted">
                  <div
                    className={cn(
                      'h-full rounded-full transition-all duration-300',
                      currentRun?.status === 'failed'
                        ? 'bg-destructive'
                        : currentRun?.status === 'completed'
                          ? 'bg-emerald-500'
                          : 'bg-primary'
                    )}
                    style={{ width: `${progressPercentage}%` }}
                    data-testid="progress-fill"
                  />
                </div>
                <span
                  className="text-sm font-medium text-muted-foreground"
                  data-testid="progress-text"
                >
                  {progressPercentage}%
                </span>
              </div>

              {/* Status */}
              <div className="mb-4" data-testid="execution-status">
                {currentRun?.status === 'running' && currentStep && (
                  <div className="flex items-center gap-2 text-sm text-foreground">
                    <LoadingSpinner size="small" />
                    <span>
                      {t('migrations.executingStep', {
                        step: String(currentStep.stepOrder),
                        total: String(currentRun.steps.length),
                        operation: currentStep.operation,
                      })}
                    </span>
                  </div>
                )}
                {currentRun?.status === 'completed' && (
                  <div
                    className="flex items-center gap-2 text-sm font-medium text-emerald-600 dark:text-emerald-400"
                    data-testid="success-message"
                  >
                    <span className="text-lg">✓</span>
                    <span>{t('migrations.executionCompleted')}</span>
                  </div>
                )}
                {currentRun?.status === 'failed' && (
                  <div
                    className="flex items-center gap-2 text-sm font-medium text-destructive"
                    data-testid="failure-message"
                  >
                    <span className="text-lg">✗</span>
                    <span>{t('migrations.executionFailed')}</span>
                  </div>
                )}
                {currentRun?.status === 'rolled_back' && (
                  <div
                    className="flex items-center gap-2 text-sm font-medium text-purple-600 dark:text-purple-400"
                    data-testid="rolled-back-message"
                  >
                    <span className="text-lg">↩</span>
                    <span>{t('migrations.rollbackCompleted')}</span>
                  </div>
                )}
              </div>

              {/* Steps Progress */}
              {currentRun && currentRun.steps.length > 0 && (
                <div className="flex flex-col gap-2" data-testid="steps-progress">
                  {currentRun.steps.map((step) => (
                    <div
                      key={step.stepOrder}
                      className={cn(
                        'rounded-md border p-3',
                        step.status === 'completed'
                          ? 'border-emerald-200 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-950/30'
                          : step.status === 'running'
                            ? 'border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-950/30'
                            : step.status === 'failed'
                              ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-950/30'
                              : 'border-border bg-muted/30'
                      )}
                      data-testid={`step-progress-${step.stepOrder}`}
                    >
                      <div className="flex items-center gap-3">
                        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-muted text-xs font-bold text-muted-foreground">
                          {step.stepOrder}
                        </span>
                        <span className="text-sm font-medium text-foreground">
                          {step.operation}
                        </span>
                        <StepStatusBadge status={step.status} />
                      </div>
                      {step.error && (
                        <div
                          className="mt-2 text-xs text-destructive"
                          data-testid={`step-error-${step.stepOrder}`}
                        >
                          {step.error}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {/* Error Details - Requirement 10.6 */}
              {currentRun?.error && (
                <div
                  className="mt-4 rounded-md border border-destructive/30 bg-destructive/10 p-4"
                  data-testid="migration-run-error"
                >
                  <span className="font-semibold text-destructive">{t('common.error')}:</span>
                  <span className="ml-2 text-sm text-destructive">{currentRun.error}</span>
                </div>
              )}

              {/* Rollback Error */}
              {rollbackMutation.isError && (
                <div
                  className="mt-4 rounded-md border border-destructive/30 bg-destructive/10 p-4"
                  data-testid="rollback-error"
                >
                  <span className="font-semibold text-destructive">
                    {t('migrations.rollbackFailed')}:
                  </span>
                  <span className="ml-2 text-sm text-destructive">
                    {(rollbackMutation.error as Error)?.message || t('errors.generic')}
                  </span>
                </div>
              )}
            </div>
          )}

          {/* Loading state while starting */}
          {executeMutation.isPending && !currentRun && (
            <div
              className="flex min-h-[200px] items-center justify-center"
              data-testid="starting-execution"
            >
              <LoadingSpinner label={t('migrations.startingExecution')} />
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-border p-6">
          {/* Rollback Button - Requirement 10.7 */}
          {canRollback && (
            <button
              type="button"
              className="rounded-md bg-destructive px-5 py-2.5 text-sm font-medium text-destructive-foreground hover:bg-destructive/90"
              onClick={handleRollback}
              disabled={rollbackMutation.isPending}
              data-testid="rollback-button"
            >
              {rollbackMutation.isPending ? t('common.loading') : t('migrations.rollback')}
            </button>
          )}

          <button
            type="button"
            className="inline-flex items-center gap-2 rounded-md bg-primary px-6 py-3 text-base font-medium text-primary-foreground hover:bg-primary/90"
            onClick={handleClose}
            disabled={isPolling && currentRun?.status === 'running'}
            data-testid="close-execution-button"
          >
            {currentRun?.status === 'completed'
              ? t('common.close')
              : currentRun?.status === 'failed' || currentRun?.status === 'rolled_back'
                ? t('common.close')
                : isPolling
                  ? t('migrations.executing')
                  : t('common.close')}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Migration Planning Form Component
 * Requirements 10.2, 10.3, 10.4: Plan migration with schema selection
 */
interface MigrationPlanningFormProps {
  onClose: () => void
  onPlanCreated: (plan: MigrationPlan) => void
}

function MigrationPlanningForm({
  onClose,
  onPlanCreated,
}: MigrationPlanningFormProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const [selectedCollectionId, setSelectedCollectionId] = useState<string>('')
  const [targetVersion, setTargetVersion] = useState<number | ''>('')

  const { summaries: collectionSummaries, isLoading: summariesLoading } = useCollectionSummaries()

  const {
    data: collections = [],
    isLoading: versionsLoading,
    error: collectionsError,
  } = useQuery({
    queryKey: ['collections-for-migration', collectionSummaries.length],
    queryFn: () => fetchCollectionVersions(apiClient, collectionSummaries),
    enabled: collectionSummaries.length > 0,
  })

  const collectionsLoading = summariesLoading || versionsLoading

  const planMutation = useMutation({
    mutationFn: (request: CreateMigrationPlanRequest) => createMigrationPlan(apiClient, request),
    onSuccess: (plan) => {
      onPlanCreated(plan)
    },
  })

  const collectionsArray = useMemo(() => {
    return Array.isArray(collections) ? collections : []
  }, [collections])

  const selectedCollection = useMemo(() => {
    return collectionsArray.find((c) => c.id === selectedCollectionId)
  }, [collectionsArray, selectedCollectionId])

  const availableTargetVersions = useMemo(() => {
    if (!selectedCollection) return []
    // Filter versions that are different from current version
    return selectedCollection.availableVersions.filter(
      (v) => v !== selectedCollection.currentVersion
    )
  }, [selectedCollection])

  const handleCollectionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setSelectedCollectionId(e.target.value)
    setTargetVersion('')
  }

  const handleTargetVersionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value
    setTargetVersion(value === '' ? '' : parseInt(value, 10))
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (selectedCollectionId && targetVersion !== '') {
      planMutation.mutate({
        collectionId: selectedCollectionId,
        targetVersion: targetVersion as number,
      })
    }
  }

  const isFormValid = selectedCollectionId !== '' && targetVersion !== ''

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
      onKeyDown={(e) => e.key === 'Escape' && onClose()}
      role="presentation"
      data-testid="plan-migration-modal"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="w-full max-w-[600px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="plan-migration-title"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="plan-migration-title" className="m-0 text-lg font-semibold text-foreground">
            {t('migrations.planMigration')}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded-md border-none bg-transparent text-xl text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="close-planning-button"
          >
            ×
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="p-6">
            {collectionsLoading && (
              <div className="flex min-h-[200px] items-center justify-center">
                <LoadingSpinner label={t('common.loading')} />
              </div>
            )}

            {collectionsError && (
              <div className="py-4">
                <ErrorMessage error={collectionsError as Error} />
              </div>
            )}

            {!collectionsLoading && !collectionsError && (
              <div className="flex flex-col gap-5">
                {/* Collection Selection - Requirement 10.2 */}
                <div className="flex flex-col gap-2">
                  <label
                    htmlFor="collection-select"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('migrations.selectCollection')}
                    <span className="ml-0.5 text-destructive">*</span>
                  </label>
                  <select
                    id="collection-select"
                    className="rounded-md border border-border bg-background px-3.5 py-2.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10"
                    value={selectedCollectionId}
                    onChange={handleCollectionChange}
                    data-testid="collection-select"
                    aria-describedby="collection-hint"
                  >
                    <option value="">{t('migrations.selectCollectionPlaceholder')}</option>
                    {collectionsArray.map((collection) => (
                      <option key={collection.id} value={collection.id}>
                        {collection.displayName || collection.name} (v{collection.currentVersion})
                      </option>
                    ))}
                  </select>
                  <span id="collection-hint" className="text-xs text-muted-foreground">
                    {t('migrations.selectCollectionHint')}
                  </span>
                </div>

                {/* Target Version Selection - Requirement 10.2 */}
                <div className="flex flex-col gap-2">
                  <label
                    htmlFor="target-version-select"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('migrations.targetVersion')}
                    <span className="ml-0.5 text-destructive">*</span>
                  </label>
                  <select
                    id="target-version-select"
                    className="rounded-md border border-border bg-background px-3.5 py-2.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
                    value={targetVersion}
                    onChange={handleTargetVersionChange}
                    disabled={!selectedCollectionId || availableTargetVersions.length === 0}
                    data-testid="target-version-select"
                    aria-describedby="target-version-hint"
                  >
                    <option value="">{t('migrations.selectVersionPlaceholder')}</option>
                    {availableTargetVersions.map((version) => (
                      <option key={version} value={version}>
                        v{version}
                      </option>
                    ))}
                  </select>
                  <span id="target-version-hint" className="text-xs text-muted-foreground">
                    {selectedCollection
                      ? t('migrations.currentVersionHint', {
                          version: String(selectedCollection.currentVersion),
                        })
                      : t('migrations.selectCollectionFirst')}
                  </span>
                  {selectedCollectionId && availableTargetVersions.length === 0 && (
                    <span
                      className="text-xs text-amber-600 dark:text-amber-400"
                      data-testid="no-versions-warning"
                    >
                      {t('migrations.noOtherVersions')}
                    </span>
                  )}
                </div>

                {/* Current Selection Summary */}
                {selectedCollection && targetVersion !== '' && (
                  <div
                    className="rounded-md border border-primary/20 bg-primary/5 p-3"
                    data-testid="selection-summary"
                  >
                    <span className="text-xs font-medium text-muted-foreground">
                      {t('migrations.plannedChange')}:
                    </span>
                    <span className="ml-2 text-sm font-medium text-foreground">
                      {selectedCollection.displayName || selectedCollection.name}: v
                      {selectedCollection.currentVersion} → v{targetVersion}
                    </span>
                  </div>
                )}

                {/* Error Display */}
                {planMutation.error && (
                  <div
                    className="rounded-md border border-destructive/30 bg-destructive/10 p-4"
                    data-testid="plan-error"
                  >
                    <ErrorMessage error={planMutation.error as Error} />
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="flex items-center justify-end gap-3 border-t border-border p-6">
            <button
              type="button"
              className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
              onClick={onClose}
              data-testid="cancel-planning-button"
            >
              {t('common.cancel')}
            </button>
            <button
              type="submit"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-6 py-3 text-base font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={!isFormValid || planMutation.isPending}
              data-testid="create-plan-button"
            >
              {planMutation.isPending ? t('common.loading') : t('migrations.createPlan')}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

/**
 * Calculate duration between two dates
 */
function calculateDuration(startedAt?: string, completedAt?: string): string {
  if (!startedAt) return '-'

  const start = new Date(startedAt)
  const end = completedAt ? new Date(completedAt) : new Date()
  const durationMs = end.getTime() - start.getTime()

  if (durationMs < 1000) {
    return `${durationMs}ms`
  } else if (durationMs < 60000) {
    return `${(durationMs / 1000).toFixed(1)}s`
  } else {
    const minutes = Math.floor(durationMs / 60000)
    const seconds = Math.floor((durationMs % 60000) / 1000)
    return `${minutes}m ${seconds}s`
  }
}

/**
 * Migration Run Details Modal Component
 * Requirement 10.8: Display step details for each run
 */
interface MigrationRunDetailsProps {
  migrationId: string
  onClose: () => void
}

function MigrationRunDetails({
  migrationId,
  onClose,
}: MigrationRunDetailsProps): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()

  const {
    data: migration,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['migration-details', migrationId],
    queryFn: () => fetchMigrationDetails(keltaClient, migrationId),
  })

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    },
    [onClose]
  )

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
      onKeyDown={handleKeyDown}
      role="presentation"
      data-testid="migration-details-modal"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="w-full max-w-[600px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="migration-details-title"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="migration-details-title" className="m-0 text-lg font-semibold text-foreground">
            {t('migrations.runDetails')}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded-md border-none bg-transparent text-xl text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="close-details-button"
          >
            ×
          </button>
        </div>

        <div className="p-6">
          {isLoading && (
            <div className="flex min-h-[200px] items-center justify-center">
              <LoadingSpinner label={t('common.loading')} />
            </div>
          )}

          {error && (
            <div className="py-4">
              <ErrorMessage error={error as Error} />
            </div>
          )}

          {migration && (
            <>
              <div className="mb-6">
                <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                  {t('migrations.overview')}
                </h3>
                <div className="grid grid-cols-2 gap-4 max-sm:grid-cols-1">
                  <div className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-muted-foreground">
                      {t('migrations.collection')}
                    </span>
                    <span className="text-sm text-foreground">{migration.collectionName}</span>
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-muted-foreground">
                      {t('migrations.versionChange')}
                    </span>
                    <span className="text-sm text-foreground">
                      v{migration.fromVersion} → v{migration.toVersion}
                    </span>
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-muted-foreground">
                      {t('packages.status')}
                    </span>
                    <StatusBadge status={migration.status} />
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-muted-foreground">
                      {t('migrations.duration')}
                    </span>
                    <span className="text-sm text-foreground">
                      {calculateDuration(migration.startedAt, migration.completedAt)}
                    </span>
                  </div>
                  {migration.startedAt && (
                    <div className="flex flex-col gap-1">
                      <span className="text-xs font-medium text-muted-foreground">
                        {t('migrations.startedAt')}
                      </span>
                      <span className="text-sm text-foreground">
                        {formatDate(new Date(migration.startedAt), {
                          year: 'numeric',
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                          second: '2-digit',
                        })}
                      </span>
                    </div>
                  )}
                  {migration.completedAt && (
                    <div className="flex flex-col gap-1">
                      <span className="text-xs font-medium text-muted-foreground">
                        {t('migrations.completedAt')}
                      </span>
                      <span className="text-sm text-foreground">
                        {formatDate(new Date(migration.completedAt), {
                          year: 'numeric',
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                          second: '2-digit',
                        })}
                      </span>
                    </div>
                  )}
                </div>

                {migration.error && (
                  <div
                    className="mt-4 rounded-md border border-destructive/30 bg-destructive/10 p-4"
                    data-testid="migration-error"
                  >
                    <span className="font-semibold text-destructive">{t('common.error')}:</span>
                    <span className="ml-2 text-sm text-destructive">{migration.error}</span>
                  </div>
                )}
              </div>

              <div className="mb-6">
                <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                  {t('migrations.steps')} ({migration.steps.length})
                </h3>
                {migration.steps.length === 0 ? (
                  <p className="text-sm italic text-muted-foreground">{t('migrations.noSteps')}</p>
                ) : (
                  <div className="flex flex-col gap-3" data-testid="steps-list">
                    {migration.steps.map((step, index) => (
                      <div
                        key={step.stepOrder}
                        className="rounded-md border border-border bg-muted/30 p-3"
                        data-testid={`step-${step.stepOrder}`}
                      >
                        <div className="flex items-center gap-3">
                          <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
                            {index + 1}
                          </span>
                          <span className="text-sm font-medium text-foreground">
                            {step.operation}
                          </span>
                          <StepStatusBadge status={step.status} />
                        </div>
                        {step.details && Object.keys(step.details).length > 0 && (
                          <div className="mt-2 space-y-1 border-t border-border pt-2">
                            {Object.entries(step.details).map(([key, value]) => (
                              <div key={key} className="flex gap-2 text-xs">
                                <span className="font-medium text-muted-foreground">{key}:</span>
                                <span className="text-foreground">
                                  {typeof value === 'object'
                                    ? JSON.stringify(value)
                                    : String(value)}
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                        {step.error && (
                          <div className="mt-2 rounded border border-destructive/20 bg-destructive/5 p-2">
                            <span className="text-xs font-semibold text-destructive">
                              {t('common.error')}:
                            </span>
                            <span className="ml-1 text-xs text-destructive">{step.error}</span>
                          </div>
                        )}
                        {step.startedAt && (
                          <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                            <span className="font-medium">{t('migrations.duration')}:</span>
                            <span>{calculateDuration(step.startedAt, step.completedAt)}</span>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </>
          )}
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-border p-6">
          <button
            type="button"
            className="inline-flex items-center gap-2 rounded-md bg-primary px-6 py-3 text-base font-medium text-primary-foreground hover:bg-primary/90"
            onClick={onClose}
            data-testid="close-button"
          >
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Migration History Table Component
 * Requirement 10.1: Display migration history showing all migration runs
 */
interface MigrationHistoryTableProps {
  migrations: MigrationRun[]
  onViewDetails: (id: string) => void
}

function MigrationHistoryTable({
  migrations,
  onViewDetails,
}: MigrationHistoryTableProps): React.ReactElement {
  const { t, formatDate } = useI18n()

  if (migrations.length === 0) {
    return (
      <div
        className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card px-8 py-16 text-center text-muted-foreground"
        data-testid="history-empty"
      >
        <p>{t('migrations.noHistory')}</p>
        <p className="text-sm">{t('migrations.noHistoryHint')}</p>
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border" data-testid="history-table">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="bg-muted">
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('migrations.collection')}
            </th>
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('migrations.versionChange')}
            </th>
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('packages.status')}
            </th>
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('migrations.steps')}
            </th>
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('migrations.duration')}
            </th>
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('packages.date')}
            </th>
            <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground border-b border-border">
              {t('common.actions')}
            </th>
          </tr>
        </thead>
        <tbody>
          {migrations.map((migration) => (
            <tr
              key={migration.id}
              className="hover:bg-accent/50"
              data-testid={`history-row-${migration.id}`}
            >
              <td className="px-4 py-3 font-medium text-foreground border-b border-border">
                {migration.collectionName}
              </td>
              <td className="px-4 py-3 text-foreground border-b border-border">
                v{migration.fromVersion} → v{migration.toVersion}
              </td>
              <td className="px-4 py-3 border-b border-border">
                <StatusBadge status={migration.status} />
              </td>
              <td className="px-4 py-3 text-foreground border-b border-border">
                {migration.steps.length}
              </td>
              <td className="px-4 py-3 text-foreground border-b border-border">
                {calculateDuration(migration.startedAt, migration.completedAt)}
              </td>
              <td className="px-4 py-3 text-foreground border-b border-border">
                {migration.startedAt
                  ? formatDate(new Date(migration.startedAt), {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                    })
                  : '-'}
              </td>
              <td className="px-4 py-3 border-b border-border">
                <button
                  type="button"
                  className="rounded-md px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/10 hover:underline"
                  onClick={() => onViewDetails(migration.id)}
                  aria-label={t('migrations.viewDetails')}
                  data-testid={`view-details-${migration.id}`}
                >
                  {t('migrations.viewDetails')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * MigrationsPage Component
 *
 * Main component for migration management with history display, planning, and execution.
 *
 * Requirements:
 * - 10.1: Display migration history showing all migration runs
 * - 10.2: Migration planning allows selecting source and target schemas
 * - 10.3: Migration plan displays steps to be executed
 * - 10.4: Migration plan displays estimated impact and risks
 * - 10.5: Migration execution shows real-time progress
 * - 10.6: Migration execution handles errors gracefully
 * - 10.7: Migration execution offers rollback option on failure
 * - 10.8: Display status, duration, and step details for each run
 */
export function MigrationsPage({ testId }: MigrationsPageProps): React.ReactElement {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const queryClient = useQueryClient()
  const [selectedMigrationId, setSelectedMigrationId] = useState<string | null>(null)
  const [showPlanningForm, setShowPlanningForm] = useState(false)
  const [currentPlan, setCurrentPlan] = useState<MigrationPlan | null>(null)
  const [executingPlan, setExecutingPlan] = useState<MigrationPlan | null>(null)

  const {
    data: migrations = [],
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['migration-history'],
    queryFn: () => fetchMigrationHistory(keltaClient),
  })

  const handleViewDetails = useCallback((id: string) => {
    setSelectedMigrationId(id)
  }, [])

  const handleCloseDetails = useCallback(() => {
    setSelectedMigrationId(null)
  }, [])

  const handleOpenPlanningForm = useCallback(() => {
    setShowPlanningForm(true)
  }, [])

  const handleClosePlanningForm = useCallback(() => {
    setShowPlanningForm(false)
  }, [])

  const handlePlanCreated = useCallback((plan: MigrationPlan) => {
    setShowPlanningForm(false)
    setCurrentPlan(plan)
  }, [])

  const handleClosePlanDisplay = useCallback(() => {
    setCurrentPlan(null)
  }, [])

  // Handle execute migration from plan display
  const handleExecuteMigration = useCallback((plan: MigrationPlan) => {
    setCurrentPlan(null)
    setExecutingPlan(plan)
  }, [])

  // Handle close execution modal
  const handleCloseExecution = useCallback(() => {
    setExecutingPlan(null)
  }, [])

  // Handle execution complete
  const handleExecutionComplete = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['migration-history'] })
    setExecutingPlan(null)
  }, [queryClient])

  return (
    <div
      className="mx-auto max-w-[1400px] p-8 max-md:p-4"
      data-testid={testId || 'migrations-page'}
    >
      <header className="mb-8 flex items-center justify-between">
        <h1 className="m-0 text-3xl font-semibold text-foreground">{t('migrations.title')}</h1>
        <button
          type="button"
          className="inline-flex items-center gap-2 rounded-md bg-primary px-6 py-3 text-base font-medium text-primary-foreground hover:bg-primary/90"
          onClick={handleOpenPlanningForm}
          data-testid="plan-migration-button"
        >
          {t('migrations.planMigration')}
        </button>
      </header>

      <div>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="m-0 text-xl font-semibold text-foreground">{t('migrations.history')}</h2>
        </div>

        {isLoading && (
          <div className="flex min-h-[400px] items-center justify-center">
            <LoadingSpinner label={t('common.loading')} />
          </div>
        )}

        {error && (
          <div className="py-4">
            <ErrorMessage error={error as Error} onRetry={() => refetch()} />
          </div>
        )}

        {!isLoading && !error && (
          <MigrationHistoryTable migrations={migrations} onViewDetails={handleViewDetails} />
        )}
      </div>

      {selectedMigrationId && (
        <MigrationRunDetails migrationId={selectedMigrationId} onClose={handleCloseDetails} />
      )}

      {showPlanningForm && (
        <MigrationPlanningForm
          onClose={handleClosePlanningForm}
          onPlanCreated={handlePlanCreated}
        />
      )}

      {currentPlan && (
        <MigrationPlanDisplay
          plan={currentPlan}
          onClose={handleClosePlanDisplay}
          onExecute={handleExecuteMigration}
        />
      )}

      {executingPlan && (
        <MigrationExecutionModal
          plan={executingPlan}
          onClose={handleCloseExecution}
          onComplete={handleExecutionComplete}
        />
      )}
    </div>
  )
}
