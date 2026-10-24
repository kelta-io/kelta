import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import { useNavigate } from 'react-router-dom'

export interface ErrorDashboardPageProps {
  className?: string
}

export function ErrorDashboardPage({ className }: ErrorDashboardPageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const navigate = useNavigate()

  const { data, isLoading, error } = useQuery({
    queryKey: ['error-dashboard'],
    queryFn: () => keltaClient.admin.metrics.errors(20),
    refetchInterval: 30000,
  })

  const { data: summary } = useQuery({
    queryKey: ['error-dashboard-summary'],
    queryFn: () => keltaClient.admin.metrics.summary(),
    refetchInterval: 30000,
  })

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('errorDashboard.loadError')} />

  interface ErrorEntry {
    path: string
    count: number
    statusCodes?: Record<string, number>
  }
  const errors = (data?.errors ?? []) as ErrorEntry[]

  return (
    <div className={cn('mx-auto max-w-[1400px]', className)} data-testid="error-dashboard-page">
      <div className="mb-4">
        <h2 className="m-0 text-lg font-semibold text-foreground">{t('errorDashboard.title')}</h2>
      </div>

      {/* Summary cards */}
      <div className="mb-8 grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-4">
        <div className="rounded-lg border border-border bg-card p-5">
          <div className="mb-1 text-sm text-muted-foreground">{t('errorDashboard.errorRate')}</div>
          <div
            className={cn(
              'text-2xl font-bold',
              (summary?.errorRate ?? 0) > 5 ? 'text-red-600 dark:text-red-400' : 'text-foreground'
            )}
          >
            {summary?.errorRate?.toFixed(2) ?? '0'}%
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-5">
          <div className="mb-1 text-sm text-muted-foreground">
            {t('errorDashboard.totalErrors')}
          </div>
          <div className="text-2xl font-bold text-foreground">
            {errors.reduce((sum: number, e: ErrorEntry) => sum + e.count, 0).toLocaleString()}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-5">
          <div className="mb-1 text-sm text-muted-foreground">
            {t('errorDashboard.uniqueEndpoints')}
          </div>
          <div className="text-2xl font-bold text-foreground">{errors.length}</div>
        </div>
      </div>

      {/* Top errors table */}
      {errors.length === 0 ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('errorDashboard.noErrors')}</p>
        </div>
      ) : (
        <div
          className="overflow-x-auto rounded-lg border border-border bg-card"
          data-testid="error-dashboard-table"
        >
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="bg-muted">
                <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                  {t('errorDashboard.endpoint')}
                </th>
                <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                  {t('errorDashboard.count')}
                </th>
                <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                  {t('errorDashboard.statusCodes')}
                </th>
                <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground"></th>
              </tr>
            </thead>
            <tbody>
              {errors.map((err: ErrorEntry, idx: number) => (
                <tr
                  key={idx}
                  data-testid={`error-dashboard-row-${idx}`}
                  className="cursor-pointer hover:bg-accent"
                  onClick={() =>
                    navigate(`../requests?path=${encodeURIComponent(err.path)}&status=4xx`)
                  }
                >
                  <td className="border-b border-border p-3 font-mono text-xs">{err.path}</td>
                  <td className="border-b border-border p-3 font-semibold">
                    {err.count.toLocaleString()}
                  </td>
                  <td className="border-b border-border p-3">
                    <div className="flex flex-wrap gap-1">
                      {Object.entries(err.statusCodes || {}).map(([code, count]) => (
                        <span
                          key={code}
                          className={cn(
                            'rounded-full px-2 py-0.5 text-xs font-medium',
                            code.startsWith('4')
                              ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                              : 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
                          )}
                        >
                          {code}: {count}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="border-b border-border p-3">
                    <span className="text-xs text-primary">&rarr;</span>
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
