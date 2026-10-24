import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

export interface RequestLogDetailPageProps {
  className?: string
}

type Tab = 'request' | 'response' | 'trace' | 'logs' | 'audit'

/**
 * Deep link into Grafana Explore, querying the Tempo datasource for one trace.
 * Grafana encodes Explore state in a `panes` object keyed by an arbitrary pane
 * id; `schemaVersion=1` is the format Grafana 10.1+ reads.
 */
function buildTraceUrl(traceId: string): string {
  const panes = {
    trace: {
      datasource: 'tempo',
      queries: [
        {
          refId: 'A',
          datasource: { type: 'tempo', uid: 'tempo' },
          queryType: 'traceql',
          query: traceId,
        },
      ],
      range: { from: 'now-24h', to: 'now' },
    },
  }
  const params = new URLSearchParams({
    schemaVersion: '1',
    orgId: '1',
    panes: JSON.stringify(panes),
  })
  return `https://grafana.rzware.com/explore?${params.toString()}`
}

export function RequestLogDetailPage({ className }: RequestLogDetailPageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const { traceId } = useParams<{ traceId: string }>()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<Tab>('request')

  const { data, isLoading, error } = useQuery({
    queryKey: ['request-log-detail', traceId],
    queryFn: () => keltaClient.admin.observability.getRequestLog(traceId!),
    enabled: !!traceId,
  })

  const { data: logsData } = useQuery({
    queryKey: ['request-log-detail-logs', traceId],
    queryFn: () => keltaClient.admin.observability.searchLogs({ traceId, page: 0, size: 100 }),
    enabled: !!traceId && activeTab === 'logs',
  })

  const { data: auditData } = useQuery({
    queryKey: ['request-log-detail-audit', traceId],
    queryFn: () =>
      keltaClient.admin.observability.searchAudit({
        start: undefined,
        end: undefined,
        page: 0,
        size: 100,
      }),
    enabled: !!traceId && activeTab === 'audit',
  })

  if (isLoading) return <LoadingSpinner />
  if (error || !data) return <ErrorMessage error={t('requestLog.detail.loadError')} />

  interface Span {
    spanID?: string
    operationName?: string
    duration?: number
    startTimeMillis?: number
    startTime?: number
    tagMap?: Record<string, unknown>
    references?: Array<{ spanID?: string }>
    process?: { serviceName?: string }
  }
  const spans = ((data as { spans?: Span[] }).spans || []) as Span[]
  // Find the root span: the span whose parent is not in the result set.
  // Spans come back in the Jaeger data model, which Tempo also emits: the
  // parent is in the 'references' array, not in the flat tagMap.
  const rootSpan =
    spans.find((s: Span) => {
      const parentSpanId = s.references?.[0]?.spanID
      return !parentSpanId || !spans.some((p: Span) => p.spanID === parentSpanId)
    }) || spans[0]
  const childSpans = spans.filter((s: Span) => s !== rootSpan)

  const tags = rootSpan?.tagMap || {}
  const httpMethod = String(tags['http.request.method'] ?? '')
  const httpRoute = String(
    tags['http.route'] || tags['http.url.path'] || rootSpan?.operationName || ''
  )
  const statusCode = String(tags['http.response.status_code'] ?? '')
  const durationMs = rootSpan?.duration ? (rootSpan.duration / 1000).toFixed(0) : '?'
  const startTime = rootSpan?.startTimeMillis
    ? new Date(rootSpan.startTimeMillis).toLocaleString()
    : rootSpan?.startTime
      ? new Date(rootSpan.startTime / 1000).toLocaleString()
      : ''
  const serviceName = String(tags['process.serviceName'] || rootSpan?.process?.serviceName || '')
  const requestBody = String(tags['http.request.body'] ?? '')
  const responseBody = String(tags['http.response.body'] ?? '')
  const userId = String(tags['kelta.user.email'] || tags['kelta.user.id'] || '')

  const getStatusColor = (code: string) => {
    if (code?.startsWith('2'))
      return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
    if (code?.startsWith('3'))
      return 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
    if (code?.startsWith('4'))
      return 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
    if (code?.startsWith('5')) return 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
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

  const tabs: { key: Tab; labelKey: string; testId: string }[] = [
    {
      key: 'request',
      labelKey: 'requestLog.detail.requestTab',
      testId: 'request-detail-request-tab',
    },
    {
      key: 'response',
      labelKey: 'requestLog.detail.responseTab',
      testId: 'request-detail-response-tab',
    },
    { key: 'trace', labelKey: 'requestLog.detail.traceTab', testId: 'request-detail-trace-tab' },
    { key: 'logs', labelKey: 'requestLog.detail.logsTab', testId: 'request-detail-logs-tab' },
    { key: 'audit', labelKey: 'requestLog.detail.auditTab', testId: 'request-detail-audit-tab' },
  ]

  return (
    <div className={cn('mx-auto max-w-[1400px]', className)} data-testid="request-log-detail-page">
      {/* Back link */}
      <button className="mb-4 text-sm text-primary hover:underline" onClick={() => navigate(-1)}>
        &larr; {t('requestLog.detail.backToList')}
      </button>

      {/* Summary card */}
      <div
        className="mb-6 rounded-lg border border-border bg-card p-5"
        data-testid="request-detail-summary"
      >
        <div className="flex flex-wrap items-center gap-4">
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-xs font-medium',
              getMethodColor(httpMethod)
            )}
          >
            {httpMethod}
          </span>
          <span className="font-mono text-sm">{httpRoute}</span>
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-xs font-medium',
              getStatusColor(statusCode)
            )}
          >
            {statusCode}
          </span>
          <span className="text-sm text-muted-foreground">{durationMs}ms</span>
          <span className="text-sm text-muted-foreground">{startTime}</span>
          {userId && <span className="text-sm text-muted-foreground">{userId}</span>}
          {serviceName && (
            <span className="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
              {serviceName}
            </span>
          )}
        </div>
        <div className="mt-3 flex items-center gap-4">
          <span className="font-mono text-xs text-muted-foreground">
            {t('requestLog.traceId')}: {traceId}
          </span>
          <a
            href={buildTraceUrl(traceId!)}
            target="_blank"
            rel="noopener noreferrer"
            className="text-xs text-primary hover:underline"
            data-testid="request-detail-trace-link"
          >
            {t('requestLog.detail.viewInTempo')}
          </a>
        </div>
      </div>

      {/* Tabs */}
      <div className="mb-4 flex gap-1 border-b border-border">
        {tabs.map(({ key, labelKey, testId }) => (
          <button
            key={key}
            data-testid={testId}
            onClick={() => setActiveTab(key)}
            className={cn(
              'border-b-2 px-4 py-2 text-sm font-medium transition-colors',
              activeTab === key
                ? 'border-primary text-primary'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            {t(labelKey)}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="rounded-lg border border-border bg-card p-5">
        {activeTab === 'request' && (
          <div data-testid="request-detail-request-body">
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.requestHeaders')}
            </h3>
            <div className="mb-4" data-testid="request-detail-request-headers">
              <table className="w-full text-sm">
                <tbody>
                  {Object.entries(tags)
                    .filter(([k]) => k.startsWith('http.request.header.'))
                    .map(([k, v]) => (
                      <tr key={k} className="border-b border-border">
                        <td className="p-2 font-mono text-xs text-muted-foreground">
                          {k.replace('http.request.header.', '')}
                        </td>
                        <td className="p-2 font-mono text-xs">{String(v)}</td>
                      </tr>
                    ))}
                  {Object.entries(tags).filter(([k]) => k.startsWith('http.request.header.'))
                    .length === 0 && (
                    <tr>
                      <td className="p-2 text-sm text-muted-foreground" colSpan={2}>
                        {t('requestLog.detail.noHeaders')}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.requestBody')}
            </h3>
            <pre className="max-h-[400px] overflow-auto rounded border border-border bg-muted p-3 font-mono text-xs">
              {formatJson(requestBody)}
            </pre>
          </div>
        )}

        {activeTab === 'response' && (
          <div data-testid="request-detail-response-body">
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.responseHeaders')}
            </h3>
            <div className="mb-4" data-testid="request-detail-response-headers">
              <table className="w-full text-sm">
                <tbody>
                  {Object.entries(tags)
                    .filter(([k]) => k.startsWith('http.response.header.'))
                    .map(([k, v]) => (
                      <tr key={k} className="border-b border-border">
                        <td className="p-2 font-mono text-xs text-muted-foreground">
                          {k.replace('http.response.header.', '')}
                        </td>
                        <td className="p-2 font-mono text-xs">{String(v)}</td>
                      </tr>
                    ))}
                  {Object.entries(tags).filter(([k]) => k.startsWith('http.response.header.'))
                    .length === 0 && (
                    <tr>
                      <td className="p-2 text-sm text-muted-foreground" colSpan={2}>
                        {t('requestLog.detail.noHeaders')}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.responseBody')}
            </h3>
            <pre className="max-h-[400px] overflow-auto rounded border border-border bg-muted p-3 font-mono text-xs">
              {formatJson(responseBody)}
            </pre>
          </div>
        )}

        {activeTab === 'trace' && (
          <div data-testid="request-detail-trace">
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.childSpans')} ({childSpans.length})
            </h3>
            {childSpans.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('requestLog.detail.noChildSpans')}</p>
            ) : (
              <div className="space-y-2">
                {childSpans.map((span: Span, idx: number) => {
                  const spanDur = span.duration ? (span.duration / 1000).toFixed(0) : '?'
                  const spanTags = span.tagMap || {}
                  const spanService = String(
                    spanTags['process.serviceName'] || span.process?.serviceName || ''
                  )
                  return (
                    <div
                      key={span.spanID || idx}
                      className="flex items-center gap-3 rounded border border-border p-3"
                      data-testid={`request-detail-span-${idx}`}
                    >
                      <span className="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                        {spanService}
                      </span>
                      <span className="flex-1 font-mono text-xs">{span.operationName}</span>
                      <span className="text-xs text-muted-foreground">{spanDur}ms</span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        )}

        {activeTab === 'logs' && (
          <div data-testid="request-detail-logs">
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.correlatedLogs')}
            </h3>
            {!logsData || logsData.hits.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('requestLog.detail.noLogs')}</p>
            ) : (
              <div className="space-y-2">
                {logsData.hits.map(
                  (
                    log: {
                      '@timestamp': string
                      level: string
                      message: string
                      stack_trace?: string
                    },
                    idx: number
                  ) => (
                    <div
                      key={idx}
                      className="rounded border border-border p-3"
                      data-testid={`request-detail-log-${idx}`}
                    >
                      <div className="flex items-center gap-3">
                        <span className="text-xs text-muted-foreground">
                          {new Date(log['@timestamp']).toLocaleTimeString()}
                        </span>
                        <LogLevelBadge level={log.level} />
                        <span className="flex-1 font-mono text-xs">{log.message}</span>
                      </div>
                      {log.stack_trace && (
                        <pre className="mt-2 max-h-[200px] overflow-auto rounded bg-muted p-2 font-mono text-xs text-red-600 dark:text-red-400">
                          {log.stack_trace}
                        </pre>
                      )}
                    </div>
                  )
                )}
              </div>
            )}
          </div>
        )}

        {activeTab === 'audit' && (
          <div data-testid="request-detail-audit">
            <h3 className="mb-3 text-sm font-semibold text-muted-foreground uppercase">
              {t('requestLog.detail.auditEvents')}
            </h3>
            {!auditData || auditData.hits.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                {t('requestLog.detail.noAuditEvents')}
              </p>
            ) : (
              <div className="space-y-2">
                {(
                  auditData.hits as Array<{
                    traceId?: string
                    timestamp?: string
                    action?: string
                    entity_type?: string
                    entity_name?: string
                  }>
                )
                  .filter((a) => a.traceId === traceId)
                  .map((audit, idx: number) => (
                    <div
                      key={idx}
                      className="rounded border border-border p-3"
                      data-testid={`request-detail-audit-${idx}`}
                    >
                      <div className="flex items-center gap-3">
                        <span className="text-xs text-muted-foreground">
                          {audit.timestamp
                            ? new Date(audit.timestamp as string).toLocaleTimeString()
                            : ''}
                        </span>
                        <span className="rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800 dark:bg-blue-950 dark:text-blue-300">
                          {String(audit.action || '')}
                        </span>
                        <span className="text-xs">{String(audit.entity_type || '')}</span>
                        <span className="text-xs text-muted-foreground">
                          {String(audit.entity_name || '')}
                        </span>
                      </div>
                    </div>
                  ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function LogLevelBadge({ level }: { level: string }) {
  const color = (() => {
    switch (level?.toUpperCase()) {
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
  })()

  return (
    <span
      data-testid="log-level-badge"
      className={cn('rounded-full px-2 py-0.5 text-xs font-medium', color)}
    >
      {level}
    </span>
  )
}

function formatJson(value?: string): string {
  if (!value) return '-'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}
