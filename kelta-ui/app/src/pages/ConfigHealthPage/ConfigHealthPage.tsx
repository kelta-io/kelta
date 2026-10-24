import { useQuery } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type Severity = 'ERROR' | 'WARNING' | 'INFO'

interface HealthFinding {
  ruleKey: string
  severity: Severity
  title: string
  detail: string
  targetType: string | null
  targetId: string | null
  targetName: string | null
}

interface HealthReport {
  score: number
  rulesRun: number
  findingCount: number
  summary: Record<string, number>
  findings: HealthFinding[]
}

const SEVERITY_ORDER: Record<Severity, number> = { ERROR: 0, WARNING: 1, INFO: 2 }

const SEVERITY_BADGE: Record<Severity, string> = {
  ERROR: 'bg-destructive/10 text-destructive border-destructive/30',
  WARNING: 'bg-amber-500/10 text-amber-600 border-amber-500/30',
  INFO: 'bg-muted text-muted-foreground border-border',
}

function scoreColor(score: number): string {
  if (score >= 80) return 'text-green-600'
  if (score >= 50) return 'text-amber-600'
  return 'text-destructive'
}

export interface ConfigHealthPageProps {
  /** Test seam — overrides the default query key (unused in app). */
  queryKey?: string
}

export function ConfigHealthPage({ queryKey = 'config-health' }: ConfigHealthPageProps = {}) {
  const { apiClient } = useApi()
  const { t } = useI18n()

  const { data, isLoading, isError, error, refetch, isFetching } = useQuery({
    queryKey: [queryKey],
    queryFn: () => apiClient.get<{ data: HealthReport }>('/api/config-health'),
  })

  if (isLoading) return <LoadingSpinner />
  if (isError || !data?.data) {
    return (
      <ErrorMessage
        error={(error as Error | undefined) ?? new Error(t('configHealth.loadError'))}
        onRetry={() => void refetch()}
      />
    )
  }

  const report = data.data
  const findings = [...report.findings].sort(
    (a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]
  )

  return (
    <div data-testid="config-health-page" className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="m-0 text-lg font-semibold text-foreground">{t('configHealth.title')}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{t('configHealth.subtitle')}</p>
        </div>
        <Button variant="outline" onClick={() => void refetch()} disabled={isFetching}>
          {isFetching ? t('configHealth.refreshing') : t('configHealth.refresh')}
        </Button>
      </div>

      {/* Score + summary */}
      <div className="flex flex-wrap items-center gap-8 rounded-lg border border-border p-6">
        <div className="text-center" data-testid="config-health-score">
          <div className={cn('text-5xl font-bold', scoreColor(report.score))}>{report.score}</div>
          <div className="mt-1 text-xs uppercase tracking-wide text-muted-foreground">
            {t('configHealth.score')}
          </div>
        </div>
        <div className="flex flex-wrap gap-4 text-sm">
          {(['ERROR', 'WARNING', 'INFO'] as Severity[]).map((sev) => (
            <div
              key={sev}
              className="flex items-center gap-2"
              data-testid={`config-health-count-${sev}`}
            >
              <span
                className={cn(
                  'rounded-full border px-2 py-0.5 text-xs font-medium',
                  SEVERITY_BADGE[sev]
                )}
              >
                {report.summary?.[sev.toLowerCase()] ?? 0}
              </span>
              <span className="text-muted-foreground">{t(`configHealth.severity.${sev}`)}</span>
            </div>
          ))}
          <div className="text-muted-foreground">
            {t('configHealth.rulesRun')}: {report.rulesRun}
          </div>
        </div>
      </div>

      {/* Findings */}
      {findings.length === 0 ? (
        <div
          data-testid="config-health-empty"
          className="rounded-lg border border-border p-8 text-center text-sm text-muted-foreground"
        >
          {t('configHealth.noFindings')}
        </div>
      ) : (
        <ul className="space-y-3" data-testid="config-health-findings">
          {findings.map((f, i) => (
            <li
              key={`${f.ruleKey}-${f.targetId ?? i}`}
              className="rounded-lg border border-border p-4"
              data-testid="config-health-finding"
            >
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    'rounded-full border px-2 py-0.5 text-xs font-medium',
                    SEVERITY_BADGE[f.severity]
                  )}
                >
                  {t(`configHealth.severity.${f.severity}`)}
                </span>
                <span className="font-medium text-foreground">{f.title}</span>
                {f.targetName && (
                  <span className="text-xs text-muted-foreground">— {f.targetName}</span>
                )}
              </div>
              <p className="mt-1 text-sm text-muted-foreground">{f.detail}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
