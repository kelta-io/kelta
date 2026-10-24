/**
 * TenantDashboardPage Component
 *
 * Per-tenant usage and health metrics visible to tenant admins.
 * Shows API calls, storage, user counts, and collection counts against limits.
 *
 * Requirements:
 * - A14: Tenant Dashboard UI
 */

import React, { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

/**
 * Governor limits for the current tenant
 */
interface GovernorLimits {
  apiCallsPerDay: number
  storageGb: number
  maxUsers: number
  maxCollections: number
  maxFieldsPerCollection: number
  maxWorkflows: number
  maxReports: number
}

/**
 * Usage statistics for the current tenant
 */
interface TenantUsage {
  apiCallsToday: number
  storageUsedGb: number
  activeUsers: number
  collectionsCount: number
}

/**
 * Dashboard data from the tenant-scoped API
 */
interface TenantDashboardData {
  limits: GovernorLimits
  usage: TenantUsage
}

/**
 * Props for TenantDashboardPage component
 */
export interface TenantDashboardPageProps {
  testId?: string
}

/**
 * UsageCard Component — displays a single metric against its limit.
 */
interface UsageCardProps {
  title: string
  current: number
  limit: number
  unit?: string
  testId?: string
}

function UsageCard({
  title,
  current,
  limit,
  unit = '',
  testId,
}: UsageCardProps): React.ReactElement {
  const percentage = limit > 0 ? Math.min((current / limit) * 100, 100) : 0

  const barColor = useMemo(() => {
    if (percentage >= 90) return 'bg-red-500'
    if (percentage >= 75) return 'bg-amber-500'
    return 'bg-emerald-500'
  }, [percentage])

  return (
    <div
      className="flex flex-col gap-2 rounded-md border border-border bg-card p-6"
      data-testid={testId}
      role="article"
      aria-label={title}
    >
      <h3 className="m-0 text-sm font-medium text-muted-foreground">{title}</h3>
      <div className="flex items-baseline gap-1">
        <span className="text-2xl font-semibold text-foreground">
          {current.toLocaleString()}
          {unit}
        </span>
        <span className="text-sm text-muted-foreground">
          / {limit.toLocaleString()}
          {unit}
        </span>
      </div>
      <div className="h-2 w-full overflow-hidden rounded bg-muted" aria-hidden="true">
        <div
          className={cn('h-full rounded transition-[width] duration-300', barColor)}
          style={{ width: `${percentage}%` }}
        />
      </div>
      <span className="text-xs text-muted-foreground">{percentage.toFixed(1)}% used</span>
    </div>
  )
}

/**
 * TenantDashboardPage Component
 *
 * Displays per-tenant usage metrics against governor limits.
 */
export function TenantDashboardPage({
  testId = 'tenant-dashboard-page',
}: TenantDashboardPageProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()

  const {
    data: dashboardData,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['tenant-dashboard'],
    queryFn: () => apiClient.get<TenantDashboardData>('/api/tenant-dashboard'),
    refetchInterval: 60000, // Refresh every minute
  })

  // Default values when data hasn't loaded yet
  const limits = dashboardData?.limits ?? {
    apiCallsPerDay: 100000,
    storageGb: 10,
    maxUsers: 100,
    maxCollections: 200,
    maxFieldsPerCollection: 500,
    maxWorkflows: 50,
    maxReports: 200,
  }

  const usage = dashboardData?.usage ?? {
    apiCallsToday: 0,
    storageUsedGb: 0,
    activeUsers: 0,
    collectionsCount: 0,
  }

  if (isLoading) {
    return (
      <div className="flex w-full flex-col gap-6 p-6" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex w-full flex-col gap-6 p-6" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error(t('errors.generic'))}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  return (
    <div className="flex w-full flex-col gap-6 p-6 max-[767px]:p-2" data-testid={testId}>
      <header className="flex items-center justify-between">
        <h1 className="m-0 text-2xl font-semibold text-foreground">Tenant Dashboard</h1>
      </header>

      <section className="flex flex-col gap-4" aria-labelledby="usage-heading">
        <h2 id="usage-heading" className="m-0 text-lg font-semibold text-foreground">
          Usage & Limits
        </h2>
        <div
          className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-4 max-[767px]:grid-cols-1"
          data-testid="usage-cards"
        >
          <UsageCard
            title="API Calls Today"
            current={usage.apiCallsToday}
            limit={limits.apiCallsPerDay}
            testId="usage-api-calls"
          />
          <UsageCard
            title="Storage"
            current={usage.storageUsedGb}
            limit={limits.storageGb}
            unit=" GB"
            testId="usage-storage"
          />
          <UsageCard
            title="Active Users"
            current={usage.activeUsers}
            limit={limits.maxUsers}
            testId="usage-users"
          />
          <UsageCard
            title="Collections"
            current={usage.collectionsCount}
            limit={limits.maxCollections}
            testId="usage-collections"
          />
        </div>
      </section>

      <section className="flex flex-col gap-4" aria-labelledby="limits-heading">
        <h2 id="limits-heading" className="m-0 text-lg font-semibold text-foreground">
          Governor Limits
        </h2>
        <div className="overflow-x-auto rounded-md border border-border bg-card">
          <table
            className="w-full border-collapse text-sm"
            role="grid"
            aria-label="Governor Limits"
          >
            <thead>
              <tr className="bg-muted">
                <th className="border-b-2 border-border p-4 text-left font-semibold text-foreground">
                  Limit
                </th>
                <th className="border-b-2 border-border p-4 text-left font-semibold text-foreground">
                  Value
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="border-b border-border p-4 text-foreground">API Calls / Day</td>
                <td className="border-b border-border p-4 text-foreground">
                  {limits.apiCallsPerDay.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td className="border-b border-border p-4 text-foreground">Storage</td>
                <td className="border-b border-border p-4 text-foreground">
                  {limits.storageGb} GB
                </td>
              </tr>
              <tr>
                <td className="border-b border-border p-4 text-foreground">Max Users</td>
                <td className="border-b border-border p-4 text-foreground">
                  {limits.maxUsers.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td className="border-b border-border p-4 text-foreground">Max Collections</td>
                <td className="border-b border-border p-4 text-foreground">
                  {limits.maxCollections.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td className="border-b border-border p-4 text-foreground">
                  Max Fields / Collection
                </td>
                <td className="border-b border-border p-4 text-foreground">
                  {limits.maxFieldsPerCollection.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td className="border-b border-border p-4 text-foreground">Max Workflows</td>
                <td className="border-b border-border p-4 text-foreground">
                  {limits.maxWorkflows.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td className="p-4 text-foreground">Max Reports</td>
                <td className="p-4 text-foreground">{limits.maxReports.toLocaleString()}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
