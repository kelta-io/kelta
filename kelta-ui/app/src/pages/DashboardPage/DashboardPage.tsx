/**
 * DashboardPage Component
 *
 * Displays system health status, metrics, and recent errors for the Kelta platform.
 * Provides an overview of the platform's operational status.
 *
 * Requirements:
 * - 13.1: Dashboard displays system health status
 * - 13.2: Dashboard displays health status for control plane, database, NATS, and Redis
 * - 13.3: Dashboard displays key API metrics including request rate, error rate, and latency
 * - 13.4: Dashboard displays recent errors and warnings from the system logs
 * - 13.5: Dashboard allows configuring time range for metrics display
 * - 13.6: Dashboard auto-refreshes at configurable interval
 * - 13.7: Dashboard displays health alerts when services are unhealthy
 */

import React, { useMemo, useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

/**
 * Health status for a service
 */
export interface HealthStatus {
  service: string
  status: 'healthy' | 'unhealthy' | 'unknown'
  details?: string
  lastChecked: string
}

/**
 * Metric data point for time-series data
 */
export interface MetricDataPoint {
  timestamp: string
  value: number
}

/**
 * System metrics data
 */
export interface SystemMetrics {
  requestRate: MetricDataPoint[]
  errorRate: MetricDataPoint[]
  latencyP50: MetricDataPoint[]
  latencyP99: MetricDataPoint[]
}

/**
 * Recent error entry
 */
export interface RecentError {
  id: string
  timestamp: string
  level: 'error' | 'warning'
  message: string
  source: string
  traceId?: string
}

/**
 * Dashboard data from API
 */
export interface DashboardData {
  health: HealthStatus[]
  metrics: SystemMetrics
  recentErrors: RecentError[]
}

/**
 * Time range options for metrics display
 */
type TimeRangeValue = '5m' | '15m' | '1h' | '6h' | '24h'

/**
 * Auto-refresh interval options
 */
type RefreshIntervalValue = '10s' | '30s' | '1m' | '5m' | 'off'

/**
 * Time range option configuration
 */
interface TimeRangeOption {
  value: TimeRangeValue
  label: string
  minutes: number
}

/**
 * Refresh interval option configuration
 */
interface RefreshIntervalOption {
  value: RefreshIntervalValue
  label: string
  milliseconds: number | null
}

/**
 * Props for DashboardPage component
 */
export interface DashboardPageProps {
  /** Optional test ID for testing */
  testId?: string
}

/**
 * Time range options for metrics display
 */
const TIME_RANGE_OPTIONS: TimeRangeOption[] = [
  { value: '5m', label: '5 minutes', minutes: 5 },
  { value: '15m', label: '15 minutes', minutes: 15 },
  { value: '1h', label: '1 hour', minutes: 60 },
  { value: '6h', label: '6 hours', minutes: 360 },
  { value: '24h', label: '24 hours', minutes: 1440 },
]

/**
 * Auto-refresh interval options
 */
const REFRESH_INTERVAL_OPTIONS: RefreshIntervalOption[] = [
  { value: '10s', label: '10 seconds', milliseconds: 10000 },
  { value: '30s', label: '30 seconds', milliseconds: 30000 },
  { value: '1m', label: '1 minute', milliseconds: 60000 },
  { value: '5m', label: '5 minutes', milliseconds: 300000 },
  { value: 'off', label: 'Off', milliseconds: null },
]

/**
 * HealthCard Component
 * Displays health status for a single service
 */
interface HealthCardProps {
  service: string
  status: 'healthy' | 'unhealthy' | 'unknown'
  details?: string
  lastChecked: string
  testId?: string
}

function HealthCard({
  service,
  status,
  details,
  lastChecked,
  testId,
}: HealthCardProps): React.ReactElement {
  const { t, formatDate } = useI18n()

  const statusLabel = useMemo(() => {
    switch (status) {
      case 'healthy':
        return t('dashboard.healthy')
      case 'unhealthy':
        return t('dashboard.unhealthy')
      default:
        return t('dashboard.unknown')
    }
  }, [status, t])

  const borderColor = useMemo(() => {
    switch (status) {
      case 'healthy':
        return 'border-l-emerald-500'
      case 'unhealthy':
        return 'border-l-red-500'
      default:
        return 'border-l-amber-500'
    }
  }, [status])

  const badgeColor = useMemo(() => {
    switch (status) {
      case 'healthy':
        return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-300'
      case 'unhealthy':
        return 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300'
      default:
        return 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300'
    }
  }, [status])

  return (
    <div
      className={cn(
        'flex flex-col gap-2 rounded-md border border-border border-l-4 bg-card p-4 transition-shadow hover:shadow-md',
        borderColor
      )}
      data-testid={testId}
      role="article"
      aria-label={`${service} health status: ${statusLabel}`}
    >
      <div className="flex items-center justify-between gap-2">
        <h3 className="m-0 text-base font-medium text-foreground">{service}</h3>
        <span
          className={cn(
            'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium capitalize',
            badgeColor
          )}
          aria-label={`Status: ${statusLabel}`}
        >
          {statusLabel}
        </span>
      </div>
      {details && <p className="m-0 text-sm text-muted-foreground">{details}</p>}
      <p className="m-0 text-xs text-muted-foreground/70">
        {formatDate(new Date(lastChecked), {
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
        })}
      </p>
    </div>
  )
}

/**
 * MetricsCard Component
 * Displays a single metric with its current value and trend
 */
interface MetricsCardProps {
  title: string
  data: MetricDataPoint[]
  unit?: string
  testId?: string
}

function MetricsCard({ title, data, unit = '', testId }: MetricsCardProps): React.ReactElement {
  const { formatNumber } = useI18n()

  // Calculate current value (latest data point)
  const currentValue = useMemo(() => {
    if (data.length === 0) return 0
    return data[data.length - 1].value
  }, [data])

  // Calculate average value
  const averageValue = useMemo(() => {
    if (data.length === 0) return 0
    const sum = data.reduce((acc, point) => acc + point.value, 0)
    return sum / data.length
  }, [data])

  // Calculate trend (comparing last value to average)
  const trend = useMemo(() => {
    if (data.length < 2) return 'stable'
    const diff = currentValue - averageValue
    const threshold = averageValue * 0.1 // 10% threshold
    if (diff > threshold) return 'up'
    if (diff < -threshold) return 'down'
    return 'stable'
  }, [currentValue, averageValue, data.length])

  const trendIcon = useMemo(() => {
    switch (trend) {
      case 'up':
        return '\u2191'
      case 'down':
        return '\u2193'
      default:
        return '\u2192'
    }
  }, [trend])

  const trendColor = useMemo(() => {
    switch (trend) {
      case 'up':
        return 'text-red-500'
      case 'down':
        return 'text-emerald-500'
      default:
        return 'text-muted-foreground/70'
    }
  }, [trend])

  // Simple sparkline visualization using CSS
  const sparklineData = useMemo(() => {
    if (data.length === 0) return []
    const maxValue = Math.max(...data.map((d) => d.value))
    const minValue = Math.min(...data.map((d) => d.value))
    const range = maxValue - minValue || 1
    return data.slice(-10).map((d) => ({
      height: ((d.value - minValue) / range) * 100,
      value: d.value,
    }))
  }, [data])

  return (
    <div
      className="flex flex-col gap-2 rounded-md border border-border bg-card p-4 transition-shadow hover:shadow-md"
      data-testid={testId}
      role="article"
      aria-label={`${title}: ${formatNumber(currentValue)}${unit}`}
    >
      <h3 className="m-0 text-sm font-medium text-muted-foreground">{title}</h3>
      <div className="flex items-baseline gap-2">
        <span className="text-2xl font-bold text-foreground">
          {formatNumber(currentValue, { maximumFractionDigits: 2 })}
          {unit}
        </span>
        <span className={cn('text-lg font-medium', trendColor)} aria-label={`Trend: ${trend}`}>
          {trendIcon}
        </span>
      </div>
      {sparklineData.length > 0 && (
        <div className="flex h-10 items-end gap-0.5 py-1" aria-hidden="true">
          {sparklineData.map((point, index) => (
            <div
              key={index}
              className="flex-1 min-w-1 rounded-t-sm bg-primary opacity-70 transition-opacity hover:opacity-100"
              style={{ height: `${Math.max(point.height, 5)}%` }}
              title={`${formatNumber(point.value, { maximumFractionDigits: 2 })}${unit}`}
            />
          ))}
        </div>
      )}
      <p className="m-0 text-xs text-muted-foreground/70">
        Avg: {formatNumber(averageValue, { maximumFractionDigits: 2 })}
        {unit}
      </p>
    </div>
  )
}

/**
 * ErrorsList Component
 * Displays recent errors and warnings
 */
interface ErrorsListProps {
  errors: RecentError[]
  testId?: string
}

function ErrorsList({ errors, testId }: ErrorsListProps): React.ReactElement {
  const { t, formatDate } = useI18n()

  if (errors.length === 0) {
    return (
      <div
        className="flex items-center justify-center rounded-md bg-muted p-8 text-muted-foreground"
        data-testid={testId}
      >
        <p className="m-0">{t('common.noResults')}</p>
      </div>
    )
  }

  return (
    <ul
      className="m-0 flex list-none flex-col gap-2 p-0"
      data-testid={testId}
      aria-label={t('dashboard.recentErrors')}
    >
      {errors.map((error) => (
        <li
          key={error.id}
          className={cn(
            'flex flex-col gap-1 rounded-md border border-border border-l-4 bg-card p-4',
            error.level === 'error' ? 'border-l-red-500' : 'border-l-amber-500'
          )}
          data-testid={`error-item-${error.id}`}
        >
          <div className="flex items-center justify-between gap-2">
            <span
              className={cn(
                'inline-flex items-center rounded px-2 py-1 text-xs font-bold',
                error.level === 'error'
                  ? 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300'
                  : 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300'
              )}
              aria-label={`Level: ${error.level}`}
            >
              {error.level.toUpperCase()}
            </span>
            <span className="text-xs text-muted-foreground/70">
              {formatDate(new Date(error.timestamp), {
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
          </div>
          <p className="m-0 text-sm leading-relaxed text-foreground">{error.message}</p>
          <div className="flex gap-4 text-xs text-muted-foreground/70">
            <span className="font-medium">{error.source}</span>
            {error.traceId && (
              <span className="font-mono" title="Trace ID">
                {error.traceId.substring(0, 8)}...
              </span>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * TimeRangeSelector Component
 * Allows selecting the time range for metrics display
 */
interface TimeRangeSelectorProps {
  value: TimeRangeValue
  onChange: (value: TimeRangeValue) => void
  testId?: string
}

function TimeRangeSelector({
  value,
  onChange,
  testId,
}: TimeRangeSelectorProps): React.ReactElement {
  const { t } = useI18n()

  const handleChange = useCallback(
    (event: React.ChangeEvent<HTMLSelectElement>) => {
      onChange(event.target.value as TimeRangeValue)
    },
    [onChange]
  )

  return (
    <div className="flex items-center gap-2" data-testid={testId}>
      <label
        htmlFor="time-range-select"
        className="whitespace-nowrap text-sm font-medium text-muted-foreground"
      >
        {t('dashboard.timeRange')}
      </label>
      <select
        id="time-range-select"
        className="min-w-[120px] rounded border border-border bg-background px-2 py-1 text-sm text-foreground hover:border-primary focus:outline-none focus:ring-2 focus:ring-primary"
        value={value}
        onChange={handleChange}
        aria-label={t('dashboard.timeRange')}
      >
        {TIME_RANGE_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  )
}

/**
 * RefreshIntervalSelector Component
 * Allows selecting the auto-refresh interval
 */
interface RefreshIntervalSelectorProps {
  value: RefreshIntervalValue
  onChange: (value: RefreshIntervalValue) => void
  testId?: string
}

function RefreshIntervalSelector({
  value,
  onChange,
  testId,
}: RefreshIntervalSelectorProps): React.ReactElement {
  const { t } = useI18n()

  const handleChange = useCallback(
    (event: React.ChangeEvent<HTMLSelectElement>) => {
      onChange(event.target.value as RefreshIntervalValue)
    },
    [onChange]
  )

  return (
    <div className="flex items-center gap-2" data-testid={testId}>
      <label
        htmlFor="refresh-interval-select"
        className="whitespace-nowrap text-sm font-medium text-muted-foreground"
      >
        {t('dashboard.autoRefresh')}
      </label>
      <select
        id="refresh-interval-select"
        className="min-w-[120px] rounded border border-border bg-background px-2 py-1 text-sm text-foreground hover:border-primary focus:outline-none focus:ring-2 focus:ring-primary"
        value={value}
        onChange={handleChange}
        aria-label={t('dashboard.autoRefresh')}
      >
        {REFRESH_INTERVAL_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  )
}

/**
 * HealthAlerts Component
 * Displays alerts when services are unhealthy
 */
interface HealthAlertsProps {
  healthStatuses: HealthStatus[]
  testId?: string
}

function HealthAlerts({ healthStatuses, testId }: HealthAlertsProps): React.ReactElement | null {
  const { t } = useI18n()

  // Filter for unhealthy services
  const unhealthyServices = useMemo(() => {
    return healthStatuses.filter((health) => health.status === 'unhealthy')
  }, [healthStatuses])

  // Don't render if no unhealthy services
  if (unhealthyServices.length === 0) {
    return null
  }

  return (
    <div
      className="flex flex-col gap-2 rounded-md border border-red-500 border-l-4 bg-red-50 p-4 dark:bg-red-950/50"
      data-testid={testId}
      role="alert"
      aria-live="polite"
    >
      <div className="flex items-center gap-2">
        <span aria-hidden="true">
          <AlertTriangle size={18} />
        </span>
        <h3 className="m-0 text-base font-semibold text-red-700 dark:text-red-400">
          {t('dashboard.healthAlerts')}
        </h3>
      </div>
      {/* eslint-disable-next-line jsx-a11y/no-redundant-roles */}
      <ul className="m-0 flex list-none flex-col gap-1 p-0" role="list">
        {unhealthyServices.map((health) => (
          <li
            key={health.service}
            className="flex flex-col gap-1 rounded bg-white/50 p-2 dark:bg-gray-800/50"
            data-testid={`health-alert-${health.service.toLowerCase().replace(/\s+/g, '-')}`}
          >
            <span className="text-sm font-semibold text-red-700 dark:text-red-400">
              {health.service}
            </span>
            {health.details && <span className="text-sm text-foreground">{health.details}</span>}
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * DashboardPage Component
 *
 * Main dashboard page for monitoring Kelta platform health and metrics.
 * Displays health status cards, metrics charts, and recent errors.
 */
export function DashboardPage({
  testId = 'dashboard-page',
}: DashboardPageProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()

  // State for time range and refresh interval
  const [timeRange, setTimeRange] = useState<TimeRangeValue>('15m')
  const [refreshInterval, setRefreshInterval] = useState<RefreshIntervalValue>('30s')

  // Calculate refresh interval in milliseconds
  const refreshIntervalMs = useMemo(() => {
    const option = REFRESH_INTERVAL_OPTIONS.find((opt) => opt.value === refreshInterval)
    return option?.milliseconds ?? false
  }, [refreshInterval])

  // Fetch dashboard data with time range parameter
  const {
    data: dashboardData,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['dashboard', timeRange],
    queryFn: () => apiClient.getOne<DashboardData>(`/api/admin/dashboard?timeRange=${timeRange}`),
    refetchInterval: refreshIntervalMs,
  })

  // Handle time range change
  const handleTimeRangeChange = useCallback((value: TimeRangeValue) => {
    setTimeRange(value)
  }, [])

  // Handle refresh interval change
  const handleRefreshIntervalChange = useCallback((value: RefreshIntervalValue) => {
    setRefreshInterval(value)
  }, [])

  // Default health statuses for the four main services
  const healthStatuses = useMemo(() => {
    if (!dashboardData?.health) {
      return [
        {
          service: 'Control Plane',
          status: 'unknown' as const,
          lastChecked: new Date().toISOString(),
        },
        { service: 'Database', status: 'unknown' as const, lastChecked: new Date().toISOString() },
        { service: 'NATS', status: 'unknown' as const, lastChecked: new Date().toISOString() },
        { service: 'Redis', status: 'unknown' as const, lastChecked: new Date().toISOString() },
      ]
    }
    return dashboardData.health
  }, [dashboardData])

  // Default metrics
  const metrics = useMemo(() => {
    if (!dashboardData?.metrics) {
      return {
        requestRate: [],
        errorRate: [],
        latencyP50: [],
        latencyP99: [],
      }
    }
    return dashboardData.metrics
  }, [dashboardData])

  // Recent errors
  const recentErrors = useMemo(() => {
    return dashboardData?.recentErrors || []
  }, [dashboardData])

  // Render loading state
  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-8 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Render error state
  if (error) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-8 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error(t('errors.generic'))}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-8 p-6 lg:p-8" data-testid={testId}>
      {/* Page Header */}
      <header className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="m-0 text-2xl font-semibold text-foreground">{t('dashboard.title')}</h1>
        <div className="flex flex-wrap items-center gap-4" data-testid="dashboard-controls">
          <TimeRangeSelector
            value={timeRange}
            onChange={handleTimeRangeChange}
            testId="time-range-selector"
          />
          <RefreshIntervalSelector
            value={refreshInterval}
            onChange={handleRefreshIntervalChange}
            testId="refresh-interval-selector"
          />
        </div>
      </header>

      {/* Health Alerts Section */}
      <HealthAlerts healthStatuses={healthStatuses} testId="health-alerts" />

      {/* System Health Section */}
      <section className="flex flex-col gap-4" aria-labelledby="health-heading">
        <h2 id="health-heading" className="m-0 text-lg font-semibold text-foreground">
          {t('dashboard.systemHealth')}
        </h2>
        <div
          className="grid grid-cols-[repeat(auto-fit,minmax(250px,1fr))] gap-4"
          data-testid="health-cards"
        >
          {healthStatuses.map((health, index) => (
            <HealthCard
              key={health.service}
              service={health.service}
              status={health.status}
              details={health.details}
              lastChecked={health.lastChecked}
              testId={`health-card-${index}`}
            />
          ))}
        </div>
      </section>

      {/* Metrics Section */}
      <section className="flex flex-col gap-4" aria-labelledby="metrics-heading">
        <h2 id="metrics-heading" className="m-0 text-lg font-semibold text-foreground">
          {t('dashboard.metrics')}
        </h2>
        <div
          className="grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-4"
          data-testid="metrics-cards"
        >
          <MetricsCard
            title={t('dashboard.requestRate')}
            data={metrics.requestRate}
            unit="/s"
            testId="metrics-request-rate"
          />
          <MetricsCard
            title={t('dashboard.errorRate')}
            data={metrics.errorRate}
            unit="%"
            testId="metrics-error-rate"
          />
          <MetricsCard
            title={`${t('dashboard.latency')} (P50)`}
            data={metrics.latencyP50}
            unit="ms"
            testId="metrics-latency-p50"
          />
          <MetricsCard
            title={`${t('dashboard.latency')} (P99)`}
            data={metrics.latencyP99}
            unit="ms"
            testId="metrics-latency-p99"
          />
        </div>
      </section>

      {/* Recent Errors Section */}
      <section className="flex flex-col gap-4" aria-labelledby="errors-heading">
        <h2 id="errors-heading" className="m-0 text-lg font-semibold text-foreground">
          {t('dashboard.recentErrors')}
        </h2>
        <ErrorsList errors={recentErrors} testId="errors-list" />
      </section>
    </div>
  )
}
