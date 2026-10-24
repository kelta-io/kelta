import React, { useState, useCallback, useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import { useNavigate } from 'react-router-dom'

export interface LogViewerPageProps {
  className?: string
}

const TIME_RANGES = [
  { label: '1h', seconds: 3600 },
  { label: '6h', seconds: 21600 },
  { label: '24h', seconds: 86400 },
  { label: '7d', seconds: 604800 },
  { label: '30d', seconds: 2592000 },
]

const LOG_LEVELS = ['', 'ERROR', 'WARN', 'INFO', 'DEBUG']
const SERVICES = ['', 'kelta-gateway', 'kelta-worker']

export function LogViewerPage({ className }: LogViewerPageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const navigate = useNavigate()

  const [timeRange, setTimeRange] = useState(3600)
  const [queryInput, setQueryInput] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [level, setLevel] = useState('')
  const [service, setService] = useState('')
  const [page, setPage] = useState(0)
  const [expandedRows, setExpandedRows] = useState<Set<number>>(new Set())
  const pageSize = 50

  // Debounce the search query to avoid firing on every keystroke
  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => {
    debounceRef.current = setTimeout(() => {
      setDebouncedQuery(queryInput)
      setPage(0)
    }, 400)
    return () => clearTimeout(debounceRef.current)
  }, [queryInput])

  const now = new Date()
  const start = new Date(now.getTime() - timeRange * 1000).toISOString()
  const end = now.toISOString()

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['log-viewer', debouncedQuery, level, service, timeRange, page],
    queryFn: () =>
      keltaClient.admin.observability.searchLogs({
        query: debouncedQuery || undefined,
        level: level || undefined,
        service: service || undefined,
        start,
        end,
        page,
        size: pageSize,
      }),
    refetchInterval: 30000,
  })

  const toggleRow = useCallback((idx: number) => {
    setExpandedRows((prev) => {
      const next = new Set(prev)
      if (next.has(idx)) next.delete(idx)
      else next.add(idx)
      return next
    })
  }, [])

  if (error) return <ErrorMessage error={t('logViewer.loadError')} />

  const hits = [...(data?.hits ?? [])].sort((a, b) => {
    const ta = a['@timestamp'] ? new Date(a['@timestamp']).getTime() : 0
    const tb = b['@timestamp'] ? new Date(b['@timestamp']).getTime() : 0
    return tb - ta
  })
  const totalHits = data?.totalHits ?? 0
  const totalPages = Math.max(1, Math.ceil(totalHits / pageSize))

  const getLevelColor = (lvl: string) => {
    switch (lvl?.toUpperCase()) {
      case 'ERROR':
        return 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
      case 'WARN':
        return 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
      case 'INFO':
        return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
      case 'DEBUG':
        return 'bg-muted text-muted-foreground'
      default:
        return 'bg-muted text-muted-foreground'
    }
  }

  return (
    <div className={cn('mx-auto max-w-[1400px]', className)} data-testid="log-viewer-page">
      <div className="mb-4">
        <h2 className="m-0 text-lg font-semibold text-foreground">{t('logViewer.title')}</h2>
      </div>

      {/* Time range selector */}
      <div
        className="mb-4 flex gap-1 rounded-lg border border-border bg-card p-1"
        data-testid="log-viewer-date-range"
      >
        {TIME_RANGES.map(({ label, seconds }) => (
          <button
            key={label}
            onClick={() => {
              setTimeRange(seconds)
              setPage(0)
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
            {t('logViewer.search')}
          </label>
          <input
            data-testid="log-viewer-search"
            type="text"
            placeholder={t('logViewer.searchPlaceholder')}
            className="min-w-[250px] rounded-md border border-border bg-background p-2 text-sm text-foreground placeholder:text-muted-foreground"
            value={queryInput}
            onChange={(e) => setQueryInput(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-sm font-medium text-muted-foreground">
            {t('logViewer.level')}
          </label>
          <select
            data-testid="log-viewer-level-filter"
            className="min-w-[120px] rounded-md border border-border bg-background p-2 text-sm text-foreground"
            value={level}
            onChange={(e) => {
              setLevel(e.target.value)
              setPage(0)
            }}
          >
            <option value="">{t('common.all')}</option>
            {LOG_LEVELS.filter(Boolean).map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-sm font-medium text-muted-foreground">
            {t('logViewer.service')}
          </label>
          <select
            data-testid="log-viewer-service-filter"
            className="min-w-[150px] rounded-md border border-border bg-background p-2 text-sm text-foreground"
            value={service}
            onChange={(e) => {
              setService(e.target.value)
              setPage(0)
            }}
          >
            <option value="">{t('common.all')}</option>
            {SERVICES.filter(Boolean).map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Results */}
      {isLoading && !data ? (
        <LoadingSpinner />
      ) : hits.length === 0 ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('logViewer.noEntries')}</p>
        </div>
      ) : (
        <>
          <div
            className={cn(
              'overflow-x-auto rounded-lg border border-border bg-card',
              isFetching && 'opacity-60'
            )}
            data-testid="log-viewer-table"
          >
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="bg-muted">
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('logViewer.timestamp')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('logViewer.level')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('logViewer.service')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('logViewer.message')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('requestLog.traceId')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground"></th>
                </tr>
              </thead>
              <tbody>
                {hits.map((log, idx) => (
                  <React.Fragment key={idx}>
                    <tr
                      data-testid={`log-viewer-row-${idx}`}
                      className={cn(
                        'cursor-pointer hover:bg-accent',
                        expandedRows.has(idx) && 'bg-muted'
                      )}
                      onClick={() => toggleRow(idx)}
                    >
                      <td className="border-b border-border p-3 text-xs text-muted-foreground whitespace-nowrap">
                        {new Date(log['@timestamp']).toLocaleString()}
                      </td>
                      <td className="border-b border-border p-3">
                        <span
                          data-testid="log-level-badge"
                          className={cn(
                            'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
                            getLevelColor(log.level)
                          )}
                        >
                          {log.level}
                        </span>
                      </td>
                      <td className="border-b border-border p-3 text-xs">{log.service || ''}</td>
                      <td className="border-b border-border p-3 max-w-[400px] truncate font-mono text-xs">
                        {log.message}
                      </td>
                      <td className="border-b border-border p-3">
                        {log.traceId && (
                          <button
                            data-testid="log-viewer-trace-link"
                            className="font-mono text-xs text-primary hover:underline"
                            onClick={(e) => {
                              e.stopPropagation()
                              navigate(`../requests/${log.traceId}`)
                            }}
                          >
                            {log.traceId.substring(0, 16)}...
                          </button>
                        )}
                      </td>
                      <td className="border-b border-border p-3">
                        <span className="text-xs text-muted-foreground">
                          {expandedRows.has(idx) ? '\u25BC' : '\u25B6'}
                        </span>
                      </td>
                    </tr>
                    {expandedRows.has(idx) && (
                      <tr className="bg-muted">
                        <td colSpan={6} className="!p-0">
                          <div className="p-4" data-testid="log-viewer-expanded">
                            <pre className="m-0 max-h-[300px] overflow-auto whitespace-pre-wrap break-all rounded border border-border bg-card p-3 text-xs">
                              {JSON.stringify(log, null, 2)}
                            </pre>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="mt-4 flex items-center justify-center gap-4 text-sm">
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
