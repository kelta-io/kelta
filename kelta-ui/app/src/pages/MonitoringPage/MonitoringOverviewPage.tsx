import React, { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import {
  LineChart,
  Line,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import type { MetricsTimeSeries } from '@kelta/sdk'
import { formatLatency } from '@/utils/formatDuration'

// ---------------------------------------------------------------------------
// Types & Constants
// ---------------------------------------------------------------------------

type TimeRange = '1h' | '6h' | '24h' | '7d' | '30d'

interface ChartConfig {
  id: string
  titleKey: string
  metrics: string[]
  type: 'line' | 'area'
  unit?: string
  stacked?: boolean
}

const TIME_RANGES: { key: TimeRange; labelKey: string; seconds: number }[] = [
  { key: '1h', labelKey: 'metrics.timeRange1h', seconds: 3600 },
  { key: '6h', labelKey: 'metrics.timeRange6h', seconds: 21600 },
  { key: '24h', labelKey: 'metrics.timeRange24h', seconds: 86400 },
  { key: '7d', labelKey: 'metrics.timeRange7d', seconds: 604800 },
  { key: '30d', labelKey: 'metrics.timeRange30d', seconds: 2592000 },
]

const CHART_CONFIGS: ChartConfig[] = [
  {
    id: 'request-rate',
    titleKey: 'metrics.chartRequestRate',
    metrics: ['requests'],
    type: 'line',
    unit: 'req/s',
  },
  {
    id: 'latency',
    titleKey: 'metrics.chartLatency',
    metrics: ['latency_p50', 'latency_p95', 'latency_p99'],
    type: 'line',
    unit: 'ms',
  },
  {
    id: 'errors',
    titleKey: 'metrics.chartErrors',
    metrics: ['errors'],
    type: 'area',
    stacked: true,
  },
  {
    id: 'auth-failures',
    titleKey: 'metrics.chartAuthFailures',
    metrics: ['auth_failures'],
    type: 'line',
  },
  {
    id: 'rate-limit',
    titleKey: 'metrics.chartRateLimit',
    metrics: ['rate_limit'],
    type: 'line',
  },
  {
    id: 'active-requests',
    titleKey: 'metrics.chartActiveRequests',
    metrics: ['active_requests'],
    type: 'area',
  },
]

const CHART_COLORS = [
  '#3b82f6', // blue
  '#f59e0b', // amber
  '#ef4444', // red
  '#10b981', // emerald
  '#8b5cf6', // violet
  '#ec4899', // pink
]

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatTimestamp(ts: number): string {
  const date = new Date(ts * 1000)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function formatValue(value: number, unit?: string): string {
  if (unit === 'ms') return `${(value * 1000).toFixed(1)} ms`
  if (unit === 'req/s') return `${value.toFixed(2)}/s`
  return value.toFixed(2)
}

function mergeSeriesData(
  allSeries: Map<string, MetricsTimeSeries[]>
): { timestamp: number; [key: string]: number }[] {
  type Point = { timestamp: number; [key: string]: number }
  const pointMap = new Map<number, Point>()
  for (const [metricName, seriesList] of allSeries) {
    for (const series of seriesList) {
      const labelParts = Object.entries(series.labels)
        .filter(([k]) => k !== '__name__' && k !== 'job' && k !== 'instance')
        .map(([, v]) => v)
      const label = labelParts.length > 0 ? `${metricName}:${labelParts.join(',')}` : metricName
      for (const dp of series.dataPoints) {
        const ts = Math.round(dp.timestamp)
        if (!pointMap.has(ts)) pointMap.set(ts, { timestamp: ts })
        pointMap.get(ts)![label] = dp.value
      }
    }
  }
  return Array.from(pointMap.values()).sort((a, b) => a.timestamp - b.timestamp)
}

function generateZeroFilledData(
  startIso: string,
  endIso: string,
  metricNames: string[]
): { timestamp: number; [key: string]: number }[] {
  const startSec = Math.floor(new Date(startIso).getTime() / 1000)
  const endSec = Math.floor(new Date(endIso).getTime() / 1000)
  const durationSec = endSec - startSec
  let stepSec: number
  if (durationSec <= 3600) stepSec = 60
  else if (durationSec <= 21600) stepSec = 300
  else if (durationSec <= 86400) stepSec = 900
  else if (durationSec <= 604800) stepSec = 3600
  else stepSec = 14400

  const points: { timestamp: number; [key: string]: number }[] = []
  for (let ts = startSec; ts <= endSec; ts += stepSec) {
    const point: { timestamp: number; [key: string]: number } = { timestamp: ts }
    for (const name of metricNames) point[name] = 0
    points.push(point)
  }
  return points
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function MonitoringOverviewPage(): React.ReactElement {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const navigate = useNavigate()

  const [timeRange, setTimeRange] = useState<TimeRange>('24h')
  const [route, setRoute] = useState<string>('')

  const timeRangeConfig = TIME_RANGES.find((r) => r.key === timeRange)!

  const { start, end } = useMemo(() => {
    const now = new Date()
    const startDate = new Date(now.getTime() - timeRangeConfig.seconds * 1000)
    return { start: startDate.toISOString(), end: now.toISOString() }
  }, [timeRangeConfig.seconds])

  // Summary query
  const {
    data: summary,
    isLoading: summaryLoading,
    error: summaryError,
  } = useQuery({
    queryKey: ['metrics-summary'],
    queryFn: () => keltaClient.admin.metrics.summary(),
    refetchInterval: 30000,
  })

  // Route options
  const routeQuery = useQuery({
    queryKey: ['metrics-routes', timeRange],
    queryFn: async () => {
      const result = await keltaClient.admin.metrics.query({
        metric: 'requests_by_route',
        start,
        end,
      })
      const routes = new Set<string>()
      for (const series of result.series) {
        const r = series.labels.route
        if (r) routes.add(r)
      }
      return Array.from(routes).sort()
    },
    refetchInterval: 300000,
  })

  // Top errors
  const { data: errorsData } = useQuery({
    queryKey: ['overview-top-errors'],
    queryFn: () => keltaClient.admin.metrics.errors(5),
    refetchInterval: 30000,
  })

  // Top endpoints
  const { data: endpointsData } = useQuery({
    queryKey: ['overview-top-endpoints'],
    queryFn: () => keltaClient.admin.metrics.endpoints(5),
    refetchInterval: 60000,
  })

  if (summaryLoading) return <LoadingSpinner />
  if (summaryError) return <ErrorMessage error={t('metrics.loadError')} />

  const topErrors = (errorsData?.errors ?? []) as Array<{
    path: string
    count: number
    statusCodes?: Record<string, number>
  }>
  const topEndpoints = [...(endpointsData?.endpoints ?? [])]
    .sort((a, b) => (b.p95 ?? 0) - (a.p95 ?? 0))
    .slice(0, 5)

  return (
    <div data-testid="monitoring-overview-page">
      {/* Summary cards */}
      <div
        className="mb-8 grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-4"
        data-testid="monitoring-summary-cards"
      >
        <SummaryCard
          testId="monitoring-summary-requests"
          label={t('metrics.totalRequests')}
          value={summary?.totalRequests?.toLocaleString() ?? '0'}
          subtitle={t('metrics.last24h')}
        />
        <SummaryCard
          testId="monitoring-summary-error-rate"
          label={t('metrics.errorRate')}
          value={`${summary?.errorRate?.toFixed(2) ?? '0'}%`}
          subtitle={t('metrics.last24h')}
          alert={summary != null && summary.errorRate > 5}
        />
        <SummaryCard
          testId="monitoring-summary-latency"
          label={t('metrics.avgLatency')}
          value={`${summary?.avgLatencyMs?.toFixed(1) ?? '0'} ms`}
          subtitle={t('metrics.last5m')}
        />
        <SummaryCard
          testId="monitoring-summary-active"
          label={t('metrics.activeRequests')}
          value={summary?.activeRequests?.toLocaleString() ?? '0'}
          subtitle={t('metrics.current')}
        />
      </div>

      {/* Toolbar: time range + route filter */}
      <div className="mb-6 flex flex-wrap items-center gap-4">
        <div
          className="flex gap-1 rounded-lg border border-border p-1"
          data-testid="overview-time-range"
        >
          {TIME_RANGES.map((range) => (
            <button
              key={range.key}
              data-testid={`overview-time-range-${range.key}`}
              onClick={() => setTimeRange(range.key)}
              className={cn(
                'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                timeRange === range.key
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-muted'
              )}
            >
              {t(range.labelKey)}
            </button>
          ))}
        </div>
        <select
          data-testid="overview-route-filter"
          value={route}
          onChange={(e) => setRoute(e.target.value)}
          className="rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground"
        >
          <option value="">{t('metrics.allRoutes')}</option>
          {routeQuery.data?.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      </div>

      {/* Chart grid */}
      <div className="mb-8 grid grid-cols-1 gap-6 lg:grid-cols-2" data-testid="overview-chart-grid">
        {CHART_CONFIGS.map((config) => (
          <MetricsChartWithQuery
            key={config.id}
            config={config}
            start={start}
            end={end}
            timeRange={timeRange}
            route={route}
          />
        ))}
      </div>

      {/* Bottom section: Top Errors + Slowest Endpoints */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Top Errors */}
        <div
          className="rounded-lg border border-border bg-card p-5"
          data-testid="overview-top-errors"
        >
          <div className="mb-4 flex items-center justify-between">
            <h3 className="text-base font-semibold text-foreground">
              {t('monitoring.overview.topErrors')}
            </h3>
            <button
              onClick={() => navigate('../errors')}
              className="text-sm text-primary hover:underline"
              data-testid="overview-view-all-errors"
            >
              {t('monitoring.overview.viewAll')} &rarr;
            </button>
          </div>
          {topErrors.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">
              {t('errorDashboard.noErrors')}
            </p>
          ) : (
            <table className="w-full text-sm">
              <tbody>
                {topErrors.map((err, idx) => (
                  <tr
                    key={idx}
                    className="cursor-pointer hover:bg-accent"
                    onClick={() =>
                      navigate(`../requests?path=${encodeURIComponent(err.path)}&status=4xx`)
                    }
                  >
                    <td className="border-b border-border px-2 py-2 font-mono text-xs">
                      {err.path}
                    </td>
                    <td className="border-b border-border px-2 py-2 text-right font-semibold">
                      {err.count.toLocaleString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* Slowest Endpoints */}
        <div
          className="rounded-lg border border-border bg-card p-5"
          data-testid="overview-slowest-endpoints"
        >
          <div className="mb-4 flex items-center justify-between">
            <h3 className="text-base font-semibold text-foreground">
              {t('monitoring.overview.slowestEndpoints')}
            </h3>
            <button
              onClick={() => navigate('../performance')}
              className="text-sm text-primary hover:underline"
              data-testid="overview-view-all-endpoints"
            >
              {t('monitoring.overview.viewAll')} &rarr;
            </button>
          </div>
          {topEndpoints.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">
              {t('endpointPerformance.noData')}
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr>
                  <th className="border-b border-border px-2 py-1 text-left text-xs font-medium text-muted-foreground">
                    {t('endpointPerformance.endpoint')}
                  </th>
                  <th className="border-b border-border px-2 py-1 text-right text-xs font-medium text-muted-foreground">
                    P95
                  </th>
                  <th className="border-b border-border px-2 py-1 text-right text-xs font-medium text-muted-foreground">
                    {t('endpointPerformance.requests')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {topEndpoints.map((ep, idx) => (
                  <tr
                    key={idx}
                    className="cursor-pointer hover:bg-accent"
                    onClick={() => {
                      const parts = ep.endpoint.match(/^(GET|POST|PUT|PATCH|DELETE)\s+(.+)$/)
                      if (parts) {
                        navigate(
                          `../requests?method=${encodeURIComponent(parts[1])}&path=${encodeURIComponent(parts[2])}`
                        )
                      } else {
                        navigate(`../requests?path=${encodeURIComponent(ep.endpoint)}`)
                      }
                    }}
                  >
                    <td className="border-b border-border px-2 py-2 font-mono text-xs">
                      {ep.endpoint}
                    </td>
                    <td className="border-b border-border px-2 py-2 text-right font-mono text-xs">
                      {formatLatency(ep.p95)}
                    </td>
                    <td className="border-b border-border px-2 py-2 text-right">
                      {ep.requestCount.toLocaleString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function SummaryCard({
  testId,
  label,
  value,
  subtitle,
  alert,
}: {
  testId: string
  label: string
  value: string
  subtitle: string
  alert?: boolean
}) {
  return (
    <div
      data-testid={testId}
      className={cn(
        'rounded-lg border border-border bg-card p-5',
        alert && 'border-red-300 dark:border-red-800'
      )}
    >
      <div className="mb-1 text-sm text-muted-foreground">{label}</div>
      <div
        className={cn(
          'text-2xl font-bold',
          alert ? 'text-red-600 dark:text-red-400' : 'text-foreground'
        )}
      >
        {value}
      </div>
      <div className="mt-1 text-xs text-muted-foreground">{subtitle}</div>
    </div>
  )
}

function MetricsChartWithQuery({
  config,
  start,
  end,
  timeRange,
  route,
}: {
  config: ChartConfig
  start: string
  end: string
  timeRange: string
  route: string
}) {
  const { t } = useI18n()
  const { keltaClient } = useApi()

  const { data, isLoading } = useQuery({
    queryKey: ['metrics-chart', config.id, timeRange, route],
    queryFn: async () => {
      const allSeries = new Map<string, MetricsTimeSeries[]>()
      for (const metric of config.metrics) {
        const result = await keltaClient.admin.metrics.query({
          metric,
          start,
          end,
          route: route || undefined,
        })
        allSeries.set(metric, result.series)
      }
      return mergeSeriesData(allSeries)
    },
    refetchInterval: 60000,
  })

  const chartData = useMemo(() => {
    if (!data || data.length === 0) {
      return generateZeroFilledData(start, end, config.metrics)
    }
    return data
  }, [data, start, end, config.metrics])

  return (
    <ChartPanel
      testId={`overview-chart-${config.id}`}
      title={t(config.titleKey)}
      data={chartData}
      isLoading={isLoading}
      type={config.type}
      unit={config.unit}
      stacked={config.stacked}
    />
  )
}

function ChartPanel({
  testId,
  title,
  data,
  isLoading,
  type,
  unit,
  stacked,
}: {
  testId: string
  title: string
  data: Record<string, number>[]
  isLoading: boolean
  type: 'line' | 'area'
  unit?: string
  stacked?: boolean
}) {
  const seriesKeys = useMemo(() => {
    const keys = new Set<string>()
    for (const point of data) {
      for (const key of Object.keys(point)) {
        if (key !== 'timestamp') keys.add(key)
      }
    }
    return Array.from(keys)
  }, [data])

  return (
    <div data-testid={testId} className="rounded-lg border border-border bg-card p-5">
      <h3 className="mb-4 text-base font-semibold text-foreground">{title}</h3>
      {isLoading ? (
        <div className="flex h-[250px] items-center justify-center">
          <LoadingSpinner />
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={250}>
          {type === 'area' ? (
            <AreaChart data={data}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
              <XAxis dataKey="timestamp" tickFormatter={formatTimestamp} className="text-xs" />
              <YAxis tickFormatter={(v) => formatValue(v, unit)} className="text-xs" width={80} />
              <Tooltip
                labelFormatter={(ts) => formatTimestamp(ts as number)}
                formatter={(value) => [formatValue(value as number, unit), ''] as [string, '']}
              />
              <Legend />
              {seriesKeys.map((key, i) => (
                <Area
                  key={key}
                  type="monotone"
                  dataKey={key}
                  stackId={stacked ? 'stack' : undefined}
                  fill={CHART_COLORS[i % CHART_COLORS.length]}
                  stroke={CHART_COLORS[i % CHART_COLORS.length]}
                  fillOpacity={0.3}
                />
              ))}
            </AreaChart>
          ) : (
            <LineChart data={data}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
              <XAxis dataKey="timestamp" tickFormatter={formatTimestamp} className="text-xs" />
              <YAxis tickFormatter={(v) => formatValue(v, unit)} className="text-xs" width={80} />
              <Tooltip
                labelFormatter={(ts) => formatTimestamp(ts as number)}
                formatter={(value) => [formatValue(value as number, unit), ''] as [string, '']}
              />
              <Legend />
              {seriesKeys.map((key, i) => (
                <Line
                  key={key}
                  type="monotone"
                  dataKey={key}
                  stroke={CHART_COLORS[i % CHART_COLORS.length]}
                  strokeWidth={2}
                  dot={false}
                />
              ))}
            </LineChart>
          )}
        </ResponsiveContainer>
      )}
    </div>
  )
}

export default MonitoringOverviewPage
