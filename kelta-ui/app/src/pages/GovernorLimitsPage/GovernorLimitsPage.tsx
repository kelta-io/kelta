import React, { useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

interface GovernorLimits {
  apiCallsPerDay: number
  storageGb: number
  maxUsers: number
  maxCollections: number
  maxFieldsPerCollection: number
  maxWorkflows: number
  maxReports: number
}

export interface GovernorLimitsPageProps {
  className?: string
}

export function GovernorLimitsPage({ className }: GovernorLimitsPageProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient, keltaClient } = useApi()
  const { hasPermission } = useSystemPermissions()
  const queryClient = useQueryClient()

  const canManageTenants = hasPermission('MANAGE_TENANTS')
  const [isEditing, setIsEditing] = useState(false)
  const [editLimits, setEditLimits] = useState<GovernorLimits | null>(null)

  const TIERS = ['FREE', 'PROFESSIONAL', 'ENTERPRISE', 'UNLIMITED'] as const
  type Tier = (typeof TIERS)[number]

  const {
    data: status,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['governor-limits'],
    queryFn: () => keltaClient.admin.governorLimits.getStatus(),
    refetchInterval: 60000,
  })

  const saveMutation = useMutation({
    mutationFn: (limits: GovernorLimits) =>
      apiClient.put<GovernorLimits>('/api/governor-limits', limits),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['governor-limits'] })
      toast.success(t('governorLimits.saveSuccess'))
      setIsEditing(false)
      setEditLimits(null)
    },
    onError: () => {
      toast.error(t('governorLimits.saveError'))
    },
  })

  const tierMutation = useMutation({
    mutationFn: (tier: Tier) => keltaClient.admin.governorLimits.updateTier(tier),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['governor-limits'] })
      toast.success(t('governorLimits.tierUpdated'))
    },
    onError: () => {
      toast.error(t('governorLimits.tierUpdateError'))
    },
  })

  const handleEdit = () => {
    if (status) {
      setEditLimits({ ...status.limits })
      setIsEditing(true)
    }
  }

  const handleCancel = () => {
    setIsEditing(false)
    setEditLimits(null)
  }

  const handleSave = () => {
    if (editLimits) {
      saveMutation.mutate(editLimits)
    }
  }

  const updateField = (field: keyof GovernorLimits, value: string) => {
    if (editLimits) {
      const num = parseInt(value, 10)
      if (!isNaN(num) && num >= 0) {
        setEditLimits({ ...editLimits, [field]: num })
      }
    }
  }

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('governorLimits.loadError')} />

  if (!status)
    return (
      <div className="p-12 text-center text-muted-foreground">{t('governorLimits.noData')}</div>
    )

  const metrics = [
    {
      label: t('governorLimits.apiCalls'),
      used: status.apiCallsUsed,
      limit: status.apiCallsLimit,
    },
    {
      label: t('governorLimits.users'),
      used: status.usersUsed,
      limit: status.usersLimit,
    },
    {
      label: t('governorLimits.collections'),
      used: status.collectionsUsed,
      limit: status.collectionsLimit,
    },
  ]

  const limitRows: { key: keyof GovernorLimits; labelKey: string; suffix?: string }[] = [
    { key: 'apiCallsPerDay', labelKey: 'governorLimits.apiCallsPerDay' },
    { key: 'storageGb', labelKey: 'governorLimits.storageGb', suffix: ' GB' },
    { key: 'maxUsers', labelKey: 'governorLimits.maxUsers' },
    { key: 'maxCollections', labelKey: 'governorLimits.maxCollections' },
    { key: 'maxFieldsPerCollection', labelKey: 'governorLimits.maxFieldsPerCollection' },
    { key: 'maxWorkflows', labelKey: 'governorLimits.maxWorkflows' },
    { key: 'maxReports', labelKey: 'governorLimits.maxReports' },
  ]

  const tierClass = (() => {
    switch (status.tier) {
      case 'FREE':
        return 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-200'
      case 'PROFESSIONAL':
        return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200'
      case 'ENTERPRISE':
        return 'bg-purple-100 text-purple-800 dark:bg-purple-950 dark:text-purple-200'
      case 'UNLIMITED':
        return 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200'
      default:
        return 'bg-muted text-muted-foreground'
    }
  })()

  return (
    <div className={cn('mx-auto max-w-[1200px] p-6', className)} data-testid="governor-limits-page">
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="m-0 text-2xl font-semibold text-foreground">
            {t('governorLimits.title')}
          </h1>
          {status.tier && !canManageTenants && (
            <span
              className={cn(
                'rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wide',
                tierClass
              )}
              data-testid="governor-limits-tier-badge"
              title={t('governorLimits.tierTooltip')}
            >
              {status.tier}
            </span>
          )}
          {status.tier && canManageTenants && (
            <select
              className={cn(
                'rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wide outline-none cursor-pointer',
                tierClass
              )}
              data-testid="governor-limits-tier-select"
              value={status.tier}
              disabled={tierMutation.isPending}
              onChange={(e) => {
                const next = e.target.value as Tier
                if (next !== status.tier) {
                  tierMutation.mutate(next)
                }
              }}
              title={t('governorLimits.tierTooltip')}
            >
              {TIERS.map((tier) => (
                <option key={tier} value={tier}>
                  {tier}
                </option>
              ))}
            </select>
          )}
        </div>
        {canManageTenants && !isEditing && (
          <button
            onClick={handleEdit}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
            data-testid="governor-limits-edit-button"
          >
            {t('governorLimits.editLimits')}
          </button>
        )}
        {isEditing && (
          <div className="flex gap-2">
            <button
              onClick={handleCancel}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-muted"
              data-testid="governor-limits-cancel-button"
            >
              {t('common.cancel')}
            </button>
            <button
              onClick={handleSave}
              disabled={saveMutation.isPending}
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              data-testid="governor-limits-save-button"
            >
              {saveMutation.isPending ? t('common.saving') : t('common.save')}
            </button>
          </div>
        )}
      </div>

      <div
        className="mb-8 grid grid-cols-[repeat(auto-fit,minmax(300px,1fr))] gap-6"
        data-testid="governor-limits-metric-cards"
      >
        {metrics.map((metric, metricIndex) => {
          const used = metric.used ?? 0
          const limit = metric.limit ?? 0
          const percentage = limit > 0 ? (used / limit) * 100 : 0
          const isWarning = percentage >= 80
          const isCritical = percentage >= 95

          return (
            <div
              key={metric.label}
              className="rounded-lg border border-border bg-card p-6"
              data-testid={`metric-card-${metricIndex}`}
            >
              <div className="mb-4 flex items-center justify-between">
                <h3 className="m-0 text-base font-semibold text-foreground">{metric.label}</h3>
                {isWarning && (
                  <span
                    className={cn(
                      'rounded-full px-2 py-0.5 text-xs font-medium',
                      isCritical
                        ? 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
                        : 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                    )}
                  >
                    {isCritical ? t('governorLimits.critical') : t('governorLimits.warning')}
                  </span>
                )}
              </div>
              <div className="flex flex-col gap-2">
                <div className="flex items-baseline gap-1">
                  <span className="text-2xl font-bold text-foreground">
                    {used.toLocaleString()}
                  </span>
                  <span className="text-muted-foreground">/</span>
                  <span className="text-muted-foreground">{limit.toLocaleString()}</span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className={cn(
                      'h-full rounded-full transition-[width] duration-300',
                      isCritical ? 'bg-red-500' : isWarning ? 'bg-amber-500' : 'bg-emerald-500'
                    )}
                    style={{ width: `${Math.min(percentage, 100)}%` }}
                  />
                </div>
                <div className="text-right text-sm text-muted-foreground">
                  {percentage.toFixed(1)}%
                </div>
              </div>
            </div>
          )
        })}
      </div>

      <div className="mt-8">
        <h2 className="mb-4 text-lg font-semibold text-foreground">
          {t('governorLimits.allLimits')}
        </h2>
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full border-collapse text-sm" data-testid="governor-limits-table">
            <thead>
              <tr className="bg-muted">
                <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                  {t('governorLimits.limitName')}
                </th>
                <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                  {t('governorLimits.limitValue')}
                </th>
              </tr>
            </thead>
            <tbody>
              {limitRows.map((row, index) => (
                <tr key={row.key} data-testid={`limit-row-${row.key}`}>
                  <td
                    className={cn('p-3', index < limitRows.length - 1 && 'border-b border-border')}
                  >
                    {t(row.labelKey)}
                  </td>
                  <td
                    className={cn('p-3', index < limitRows.length - 1 && 'border-b border-border')}
                  >
                    {isEditing && editLimits ? (
                      <input
                        type="number"
                        min="0"
                        value={editLimits[row.key]}
                        onChange={(e) => updateField(row.key, e.target.value)}
                        className="w-32 rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground"
                        data-testid={`limit-input-${row.key}`}
                      />
                    ) : (
                      <>
                        {(status.limits?.[row.key] ?? 0).toLocaleString()}
                        {row.suffix ?? ''}
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default GovernorLimitsPage
