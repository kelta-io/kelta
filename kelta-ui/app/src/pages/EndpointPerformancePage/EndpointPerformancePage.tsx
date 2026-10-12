import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import { formatLatency } from '@/utils/formatDuration'
import { useNavigate } from 'react-router-dom'

export interface EndpointPerformancePageProps {
  className?: string
}

type SortKey = 'endpoint' | 'requestCount' | 'p50' | 'p95' | 'p99' | 'avgDuration'
type SortDir = 'asc' | 'desc'

export function EndpointPerformancePage({ className }: EndpointPerformancePageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const navigate = useNavigate()

  const [sortKey, setSortKey] = useState<SortKey>('p95')
  const [sortDir, setSortDir] = useState<SortDir>('desc')

  const { data, isLoading, error } = useQuery({
    queryKey: ['endpoint-performance'],
    queryFn: () => keltaClient.admin.metrics.endpoints(50),
    refetchInterval: 60000,
  })

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('endpointPerformance.loadError')} />

  const endpoints = [...(data?.endpoints ?? [])].sort((a, b) => {
    const aVal = a[sortKey] ?? 0
    const bVal = b[sortKey] ?? 0
    if (typeof aVal === 'string' && typeof bVal === 'string') {
      return sortDir === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal)
    }
    return sortDir === 'asc' ? Number(aVal) - Number(bVal) : Number(bVal) - Number(aVal)
  })

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir('desc')
    }
  }

  const sortIndicator = (key: SortKey) =>
    sortKey === key ? (sortDir === 'asc' ? ' \u25B2' : ' \u25BC') : ''

  const formatMicros = (micros: number) => formatLatency(micros / 1000)

  return (
    <div
      className={cn('mx-auto max-w-[1400px]', className)}
      data-testid="endpoint-performance-page"
    >
      <div className="mb-4">
        <h2 className="m-0 text-lg font-semibold text-foreground">
          {t('endpointPerformance.title')}
        </h2>
      </div>

      {endpoints.length === 0 ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('endpointPerformance.noData')}</p>
        </div>
      ) : (
        <div
          className="overflow-x-auto rounded-lg border border-border bg-card"
          data-testid="endpoint-performance-table"
        >
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="bg-muted">
                <th
                  className="cursor-pointer border-b border-border p-3 text-left font-semibold text-muted-foreground"
                  onClick={() => handleSort('endpoint')}
                >
                  {t('endpointPerformance.endpoint')}
                  {sortIndicator('endpoint')}
                </th>
                <th
                  className="cursor-pointer border-b border-border p-3 text-right font-semibold text-muted-foreground"
                  onClick={() => handleSort('requestCount')}
                >
                  {t('endpointPerformance.requests')}
                  {sortIndicator('requestCount')}
                </th>
                <th
                  className="cursor-pointer border-b border-border p-3 text-right font-semibold text-muted-foreground"
                  onClick={() => handleSort('p50')}
                >
                  P50{sortIndicator('p50')}
                </th>
                <th
                  className="cursor-pointer border-b border-border p-3 text-right font-semibold text-muted-foreground"
                  onClick={() => handleSort('p95')}
                >
                  P95{sortIndicator('p95')}
                </th>
                <th
                  className="cursor-pointer border-b border-border p-3 text-right font-semibold text-muted-foreground"
                  onClick={() => handleSort('p99')}
                >
                  P99{sortIndicator('p99')}
                </th>
                <th
                  className="cursor-pointer border-b border-border p-3 text-right font-semibold text-muted-foreground"
                  onClick={() => handleSort('avgDuration')}
                >
                  {t('endpointPerformance.avg')}
                  {sortIndicator('avgDuration')}
                </th>
              </tr>
            </thead>
            <tbody>
              {endpoints.map((ep, idx) => (
                <tr
                  key={idx}
                  data-testid={`endpoint-performance-row-${idx}`}
                  className="cursor-pointer hover:bg-accent"
                  onClick={() => {
                    // ep.endpoint is "GET /api/collections" format — split into method + path
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
                  <td className="border-b border-border p-3 font-mono text-xs">{ep.endpoint}</td>
                  <td className="border-b border-border p-3 text-right">
                    {ep.requestCount.toLocaleString()}
                  </td>
                  <td className="border-b border-border p-3 text-right font-mono text-xs">
                    {formatMicros(ep.p50)}
                  </td>
                  <td className="border-b border-border p-3 text-right font-mono text-xs">
                    {formatMicros(ep.p95)}
                  </td>
                  <td className="border-b border-border p-3 text-right font-mono text-xs">
                    {formatMicros(ep.p99)}
                  </td>
                  <td className="border-b border-border p-3 text-right font-mono text-xs">
                    {formatMicros(ep.avgDuration)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export default EndpointPerformancePage
