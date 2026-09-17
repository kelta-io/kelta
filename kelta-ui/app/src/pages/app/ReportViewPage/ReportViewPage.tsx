import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Download } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '../../../components/ui/button'
import { useApi } from '../../../context/ApiContext'
import { useI18n } from '../../../context/I18nContext'
import { useReportExecution } from '../../../hooks/useReportExecution'

const PAGE_SIZE = 100

function extractErrorDetail(error: unknown): string | null {
  const maybe = error as { response?: { data?: { errors?: Array<{ detail?: string }> } } }
  return maybe?.response?.data?.errors?.[0]?.detail ?? null
}

/** End-user report runner: execute + paginate + export CSV/PDF. */
export function ReportViewPage() {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { tenantSlug, id } = useParams<{ tenantSlug: string; id: string }>()
  const [page, setPage] = useState(1)
  const { data, isLoading, error } = useReportExecution(id, page, PAGE_SIZE)
  const [exportingFormat, setExportingFormat] = useState<'csv' | 'pdf' | null>(null)

  const download = async (format: 'csv' | 'pdf') => {
    if (!id) return
    setExportingFormat(format)
    try {
      const filename = `${(data?.reportName ?? 'report').replace(/[^a-zA-Z0-9._-]/g, '_')}.${format}`
      let blob: Blob
      if (format === 'csv') {
        const csv = await apiClient.get<string>(`/api/reports/${id}/export?format=csv`)
        blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      } else {
        blob = await apiClient.getBlob(`/api/reports/${id}/export?format=pdf`)
      }
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    } catch {
      toast.error(t('analytics.exportFailed', 'Export failed'))
    } finally {
      setExportingFormat(null)
    }
  }

  const renderRows = () => {
    if (!data) return null
    const columns = data.columns
    const body = (records: Array<Record<string, unknown>>) =>
      records.map((row, i) => (
        <tr key={i} className="border-b border-border even:bg-muted/40" data-testid="report-row">
          {columns.map((c) => {
            const v = row[c.fieldName]
            const text =
              v === null || v === undefined
                ? '—'
                : typeof v === 'object'
                  ? JSON.stringify(v)
                  : String(v)
            return (
              <td
                key={c.fieldName}
                className={`px-3 py-2 truncate max-w-64 ${c.type === 'number' ? 'font-mono tabular-nums text-right' : ''}`}
              >
                {text}
              </td>
            )
          })}
        </tr>
      ))

    if (data.groups) {
      return Object.entries(data.groups).map(([groupLabel, records]) => (
        <tbody key={groupLabel}>
          <tr className="border-b border-border bg-muted/60">
            <td
              colSpan={columns.length}
              className="px-3 py-1.5 text-[11px] font-semibold uppercase tracking-[0.08em]"
            >
              {groupLabel || '—'}
            </td>
          </tr>
          {body(records)}
        </tbody>
      ))
    }
    return <tbody>{body(data.records)}</tbody>
  }

  return (
    <main role="main" className="mx-auto w-full max-w-[1180px] px-4 py-6">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <Link
            to={`/${tenantSlug}/app/analytics`}
            className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
            data-testid="back-to-analytics"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden />
            {t('analytics.title', 'Analytics')}
          </Link>
          <h1 className="truncate text-[26px] font-bold tracking-[-0.01em]">
            {data?.reportName ?? '…'}
          </h1>
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={exportingFormat === 'csv'}
            onClick={() => download('csv')}
            data-testid="export-csv"
          >
            <Download className="mr-1.5 h-4 w-4" aria-hidden />
            {t('analytics.exportCsv', 'Export CSV')}
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={exportingFormat === 'pdf'}
            onClick={() => download('pdf')}
            data-testid="export-pdf"
          >
            <Download className="mr-1.5 h-4 w-4" aria-hidden />
            {t('analytics.exportPdf', 'Export PDF')}
          </Button>
        </div>
      </div>

      {error ? (
        <div
          className="rounded-[10px] border border-border bg-card p-8 text-center text-sm text-muted-foreground"
          data-testid="report-error"
        >
          {extractErrorDetail(error) ??
            t(
              'analytics.loadFailed',
              "Couldn't load this report. Retry, or check its configuration."
            )}
        </div>
      ) : isLoading || !data ? (
        <div
          className="h-48 animate-pulse rounded-[10px] bg-muted/40"
          data-testid="report-loading"
        />
      ) : (
        <>
          <div className="rounded-[10px] border border-border bg-card overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/60">
                  {data.columns.map((c) => (
                    <th
                      key={c.fieldName}
                      scope="col"
                      className="px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-[0.09em] text-foreground/80"
                    >
                      {c.label}
                    </th>
                  ))}
                </tr>
              </thead>
              {renderRows()}
            </table>
          </div>
          <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
            <span data-testid="report-meta">
              {t('analytics.reportMeta', {
                count: String(data.totalCount),
                page: String(data.currentPage),
                pages: String(Math.max(data.totalPages, 1)),
              })}
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={data.currentPage <= 1}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                data-testid="report-prev"
              >
                {t('analytics.prev', 'Prev')}
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={data.currentPage >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
                data-testid="report-next"
              >
                {t('analytics.next', 'Next')}
              </Button>
            </div>
          </div>
        </>
      )}
    </main>
  )
}
