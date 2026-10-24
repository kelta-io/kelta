import { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import { useNavigate, useSearchParams } from 'react-router-dom'

export interface RequestLogPageProps {
  className?: string
}

const TIME_RANGES = [
  { label: '1h', seconds: 3600 },
  { label: '6h', seconds: 21600 },
  { label: '24h', seconds: 86400 },
  { label: '7d', seconds: 604800 },
  { label: '30d', seconds: 2592000 },
]

const HTTP_METHODS = ['', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const STATUS_GROUPS = ['', '2xx', '3xx', '4xx', '5xx']

/**
 * Parse the `path` query param which may be:
 * - "GET /api/collections" (method + path from EndpointPerformancePage)
 * - "/api/collections" (path only from ErrorDashboardPage)
 */
function parsePathParam(raw: string): { method: string; path: string } {
  const match = raw.match(/^(GET|POST|PUT|PATCH|DELETE)\s+(.+)$/)
  if (match) {
    return { method: match[1], path: match[2] }
  }
  return { method: '', path: raw }
}

export function RequestLogPage({ className }: RequestLogPageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  // Initialize state from URL query params
  const initialPath = searchParams.get('path') || ''
  const parsed = parsePathParam(initialPath)
  const initialTimeRange = Number(searchParams.get('timeRange')) || 3600

  const [timeRange, setTimeRange] = useState(initialTimeRange)
  const [method, setMethod] = useState(searchParams.get('method') || parsed.method)
  const [status, setStatus] = useState(searchParams.get('status') || '')
  const [pathSearch, setPathSearch] = useState(parsed.path)
  const [page, setPage] = useState(0)
  const pageSize = 50

  // Sync filter changes back to URL
  const updateSearchParams = useCallback(
    (updates: Record<string, string>) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          Object.entries(updates).forEach(([k, v]) => {
            if (v) {
              next.set(k, v)
            } else {
              next.delete(k)
            }
          })
          return next
        },
        { replace: true }
      )
    },
    [setSearchParams]
  )

  const now = new Date()
  const start = new Date(now.getTime() - timeRange * 1000).toISOString()
  const end = now.toISOString()

  const { data, isLoading, error } = useQuery({
    queryKey: ['request-logs', timeRange, method, status, pathSearch, page],
    queryFn: () =>
      keltaClient.admin.observability.searchRequestLogs({
        method: method || undefined,
        status: status || undefined,
        path: pathSearch || undefined,
        start,
        end,
        page,
        size: pageSize,
      }),
    refetchInterval: 30000,
  })

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('requestLog.loadError')} />

  interface RequestLogHit {
    traceID?: string
    operationName?: string
    duration?: number
    startTimeMillis?: number
    startTime?: number
    tagMap?: Record<string, unknown>
  }
  const hits = (data?.hits ?? []) as RequestLogHit[]
  const totalHits = data?.totalHits ?? 0
  const totalPages = Math.max(1, Math.ceil(totalHits / pageSize))

  const getStatusColor = (statusCode: string) => {
    if (statusCode?.startsWith('2'))
      return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
    if (statusCode?.startsWith('3'))
      return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
    if (statusCode?.startsWith('4'))
      return 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
    if (statusCode?.startsWith('5'))
      return 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
    return 'bg-muted text-muted-foreground'
  }

  const getMethodColor = (m: string) => {
    switch (m) {
      case 'GET':
        return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
      case 'POST':
        return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
      case 'PUT':
      case 'PATCH':
        return 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
      case 'DELETE':
        return 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
      default:
        return 'bg-muted text-muted-foreground'
    }
  }

  return (
    <div className={cn('mx-auto max-w-[1400px]', className)} data-testid="request-log-page">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="m-0 text-lg font-semibold text-foreground">{t('requestLog.title')}</h2>
      </div>

      {/* Time range selector */}
      <div
        className="mb-4 flex gap-1 rounded-lg border border-border bg-card p-1"
        data-testid="request-log-date-range"
      >
        {TIME_RANGES.map(({ label, seconds }) => (
          <button
            key={label}
            onClick={() => {
              setTimeRange(seconds)
              setPage(0)
              updateSearchParams({ timeRange: String(seconds) })
            }}
            className={cn(
              'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
              timeRange === seconds
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted'
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Filters */}
      <div className="mb-6 flex flex-wrap gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-sm font-medium text-muted-foreground">
            {t('requestLog.method')}
          </label>
          <select
            data-testid="request-log-method-filter"
            className="min-w-[120px] rounded-md border border-border bg-background p-2 text-sm text-foreground"
            value={method}
            onChange={(e) => {
              setMethod(e.target.value)
              setPage(0)
              updateSearchParams({ method: e.target.value })
            }}
          >
            <option value="">{t('common.all')}</option>
            {HTTP_METHODS.filter(Boolean).map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-sm font-medium text-muted-foreground">
            {t('requestLog.status')}
          </label>
          <select
            data-testid="request-log-status-filter"
            className="min-w-[120px] rounded-md border border-border bg-background p-2 text-sm text-foreground"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value)
              setPage(0)
              updateSearchParams({ status: e.target.value })
            }}
          >
            <option value="">{t('common.all')}</option>
            {STATUS_GROUPS.filter(Boolean).map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-sm font-medium text-muted-foreground">
            {t('requestLog.path')}
          </label>
          <input
            data-testid="request-log-path-search"
            type="text"
            placeholder={t('requestLog.pathPlaceholder')}
            className="min-w-[200px] rounded-md border border-border bg-background p-2 text-sm text-foreground placeholder:text-muted-foreground"
            value={pathSearch}
            onChange={(e) => {
              setPathSearch(e.target.value)
              setPage(0)
              updateSearchParams({ path: e.target.value })
            }}
          />
        </div>
      </div>

      {/* Results */}
      {hits.length === 0 ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('requestLog.noEntries')}</p>
        </div>
      ) : (
        <>
          <div
            className="overflow-x-auto rounded-lg border border-border bg-card"
            data-testid="request-log-table"
          >
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="bg-muted">
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.time')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.method')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.path')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.status')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.duration')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.traceId')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {hits.map((hit: RequestLogHit, idx: number) => {
                  const tags = hit.tagMap || {}
                  const statusCode = String(tags['http.response.status_code'] ?? '')
                  const httpMethod = String(tags['http.request.method'] ?? '')
                  const httpRoute = String(
                    tags['http.route'] || tags['http.url.path'] || hit.operationName || ''
                  )
                  const durationMs = hit.duration ? (hit.duration / 1000).toFixed(0) : '?'
                  const time = hit.startTimeMillis
                    ? new Date(hit.startTimeMillis).toLocaleString()
                    : hit.startTime
                      ? new Date(hit.startTime / 1000).toLocaleString()
                      : ''

                  return (
                    <tr
                      key={hit.traceID + '-' + idx}
                      data-testid={`request-log-row-${idx}`}
                      className="cursor-pointer hover:bg-accent"
                      onClick={() => navigate(`${hit.traceID}`)}
                    >
                      <td className="border-b border-border p-3 text-muted-foreground">{time}</td>
                      <td className="border-b border-border p-3">
                        <span
                          data-testid="request-log-method"
                          className={cn(
                            'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
                            getMethodColor(httpMethod)
                          )}
                        >
                          {httpMethod}
                        </span>
                      </td>
                      <td className="border-b border-border p-3 font-mono text-xs">{httpRoute}</td>
                      <td className="border-b border-border p-3">
                        <span
                          data-testid="request-log-status"
                          className={cn(
                            'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
                            getStatusColor(statusCode)
                          )}
                        >
                          {statusCode}
                        </span>
                      </td>
                      <td className="border-b border-border p-3">{durationMs}ms</td>
                      <td className="border-b border-border p-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {hit.traceID?.substring(0, 16)}...
                        </span>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div
            className="mt-4 flex items-center justify-center gap-4 text-sm"
            data-testid="request-log-pagination"
          >
            <button
              className="cursor-pointer rounded-md border border-border bg-card px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              {t('common.previous')}
            </button>
            <span className="text-muted-foreground">
              {t('common.pageOf', { current: String(page + 1), total: String(totalPages) })}
            </span>
            <button
              className="cursor-pointer rounded-md border border-border bg-card px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              {t('common.next')}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
