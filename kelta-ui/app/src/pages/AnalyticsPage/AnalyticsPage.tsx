import React, { useCallback, useState } from 'react'
import { toast } from 'sonner'
import { useQuery } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { SupersetEmbed } from '../../components/SupersetEmbed'
import { Button } from '@/components/ui/button'
import { ArrowLeft, BarChart3, ExternalLink, FileDown, FileText, RefreshCw } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { Report, SupersetDashboard } from '@kelta/sdk'

type ExportFormat = 'csv' | 'pdf'

export interface AnalyticsPageProps {
  testId?: string
}

function getSupersetUrl(): string {
  return (
    ((window as unknown as Record<string, unknown>).__SUPERSET_URL__ as string | undefined) ||
    `${window.location.protocol}//superset.rzware.com`
  )
}

export function AnalyticsPage({
  testId = 'analytics-page',
}: AnalyticsPageProps): React.ReactElement {
  const { keltaClient, apiClient } = useApi()
  const [selectedDashboard, setSelectedDashboard] = useState<SupersetDashboard | null>(null)
  const [exportingKey, setExportingKey] = useState<string | null>(null)
  const supersetUrl = getSupersetUrl()

  const {
    data: dashboards,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['superset', 'dashboards'],
    queryFn: () => keltaClient.admin.superset.listDashboards(),
  })

  const {
    data: reports,
    isLoading: reportsLoading,
    error: reportsError,
    refetch: refetchReports,
  } = useQuery({
    queryKey: ['reports'],
    queryFn: () => keltaClient.admin.reports.list(),
  })

  const handleExport = useCallback(
    async (report: Report, format: ExportFormat) => {
      const key = `${report.id}:${format}`
      setExportingKey(key)
      try {
        let blob: Blob
        if (format === 'csv') {
          const csv = await apiClient.get<string>(`/api/reports/${report.id}/export?format=csv`)
          blob = new Blob([typeof csv === 'string' ? csv : JSON.stringify(csv)], {
            type: 'text/csv',
          })
        } else {
          blob = await apiClient.getBlob(`/api/reports/${report.id}/export?format=pdf`)
        }
        // Same safe-filename pattern the server uses for Content-Disposition
        const safeFileName = report.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const url = window.URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `${safeFileName}.${format}`
        document.body.appendChild(a)
        a.click()
        window.URL.revokeObjectURL(url)
        document.body.removeChild(a)
      } catch (err: unknown) {
        const message =
          err instanceof Error ? err.message : `Failed to export ${format.toUpperCase()}`
        toast.error(message)
      } finally {
        setExportingKey(null)
      }
    },
    [apiClient]
  )

  // Embedded dashboard view
  if (selectedDashboard) {
    return (
      <div className="flex flex-col" style={{ height: 'calc(100vh - 160px)' }} data-testid={testId}>
        <div className="flex shrink-0 items-center gap-3 border-b px-6 py-3">
          <Button variant="ghost" size="sm" onClick={() => setSelectedDashboard(null)}>
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back
          </Button>
          <h2 className="text-lg font-semibold">{selectedDashboard.dashboard_title}</h2>
        </div>
        <div className="min-h-0 flex-1 p-4">
          <SupersetEmbed
            dashboardId={selectedDashboard.embedded_id || String(selectedDashboard.id)}
            className="h-full w-full rounded-lg border"
          />
        </div>
      </div>
    )
  }

  // Dashboard list view
  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Analytics</h1>
          <p className="text-muted-foreground mt-1 text-sm">
            View dashboards and reports powered by Superset
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => refetch()}>
            <RefreshCw className="mr-1 h-4 w-4" />
            Refresh
          </Button>
          <Button variant="outline" size="sm" asChild>
            <a href={supersetUrl} target="_blank" rel="noopener noreferrer">
              <ExternalLink className="mr-1 h-4 w-4" />
              Open in Superset
            </a>
          </Button>
        </div>
      </div>

      {/* Content */}
      {isLoading && (
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading dashboards..." />
        </div>
      )}

      {error && (
        <ErrorMessage
          error="Failed to load dashboards. Superset may not be configured."
          onRetry={() => refetch()}
        />
      )}

      {!isLoading && !error && dashboards && dashboards.length === 0 && (
        <div className="flex min-h-[400px] flex-col items-center justify-center text-center">
          <BarChart3 className="text-muted-foreground mb-4 h-12 w-12" />
          <h3 className="text-lg font-medium">No dashboards available</h3>
          <p className="text-muted-foreground mt-1 max-w-md text-sm">
            Dashboards are created in Apache Superset. Once published, they will appear here for
            viewing.
          </p>
          <Button variant="outline" className="mt-4" asChild>
            <a href={supersetUrl} target="_blank" rel="noopener noreferrer">
              <ExternalLink className="mr-1 h-4 w-4" />
              Create in Superset
            </a>
          </Button>
        </div>
      )}

      {!isLoading && !error && dashboards && dashboards.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {dashboards.map((dashboard: SupersetDashboard) => (
            <button
              key={dashboard.id}
              onClick={() => setSelectedDashboard(dashboard)}
              className={cn(
                'group relative flex flex-col rounded-lg border bg-card p-5 text-left',
                'transition-colors hover:border-primary/50 hover:bg-accent/50',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'
              )}
            >
              <div className="mb-3 flex items-center gap-2">
                <BarChart3 className="text-muted-foreground h-5 w-5" />
                <h3 className="font-medium leading-tight">{dashboard.dashboard_title}</h3>
              </div>
              {dashboard.status && (
                <span
                  className={cn(
                    'mt-auto self-start rounded-full px-2 py-0.5 text-xs font-medium',
                    dashboard.published
                      ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                      : 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400'
                  )}
                >
                  {dashboard.published ? 'Published' : 'Draft'}
                </span>
              )}
              {dashboard.changed_on_utc && (
                <p className="text-muted-foreground mt-2 text-xs">
                  Updated {new Date(dashboard.changed_on_utc).toLocaleDateString()}
                </p>
              )}
            </button>
          ))}
        </div>
      )}

      {/* Reports */}
      <div className="space-y-4" data-testid="reports-section">
        <div>
          <h2 className="text-lg font-semibold tracking-tight">Reports</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            Export saved report data as CSV or PDF
          </p>
        </div>

        {reportsLoading && (
          <div className="flex items-center justify-center py-8">
            <LoadingSpinner size="medium" label="Loading reports..." />
          </div>
        )}

        {Boolean(reportsError) && (
          <ErrorMessage error="Failed to load reports." onRetry={() => refetchReports()} />
        )}

        {!reportsLoading && !reportsError && reports && reports.length === 0 && (
          <p className="text-muted-foreground text-sm">No reports have been created yet.</p>
        )}

        {!reportsLoading && !reportsError && reports && reports.length > 0 && (
          <div className="divide-y rounded-lg border bg-card">
            {reports.map((report: Report) => (
              <div key={report.id} className="flex items-center justify-between gap-4 p-4">
                <div className="min-w-0">
                  <h3 className="font-medium leading-tight">{report.name}</h3>
                  {report.description && (
                    <p className="text-muted-foreground mt-0.5 truncate text-sm">
                      {report.description}
                    </p>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={exportingKey === `${report.id}:csv`}
                    onClick={() => handleExport(report, 'csv')}
                    data-testid={`report-export-csv-${report.id}`}
                  >
                    <FileDown className="mr-1 h-4 w-4" />
                    {exportingKey === `${report.id}:csv` ? 'Exporting...' : 'Export CSV'}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={exportingKey === `${report.id}:pdf`}
                    onClick={() => handleExport(report, 'pdf')}
                    data-testid={`report-export-pdf-${report.id}`}
                  >
                    <FileText className="mr-1 h-4 w-4" />
                    {exportingKey === `${report.id}:pdf` ? 'Exporting...' : 'Export PDF'}
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
