import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../../components/ui/select'
import { useI18n } from '../../../context/I18nContext'
import { useCollectionStore } from '../../../context/CollectionStoreContext'
import {
  useDashboardData,
  type DashboardComponentRow,
  type DashboardTimeRange,
  type WidgetPayload,
} from '../../../hooks/useDashboardData'
import { buildListUrl } from '../ObjectListPage/listUrlState'
import { WidgetFrame } from './widgets/WidgetFrame'
import { MetricWidget } from './widgets/MetricWidget'
import { ChartWidget } from './widgets/ChartWidget'
import { RecordsWidget } from './widgets/RecordsWidget'

const TIME_RANGES: DashboardTimeRange[] = ['ALL', 'TODAY', '7D', '30D', '90D', '1Y']

/**
 * End-user dashboard viewer: CSS-grid layout from dashboard-components rows joined with
 * widget payloads from POST /api/dashboards/{id}/data. First UI consumer of the native
 * dashboard data API.
 */
export function DashboardViewPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const { tenantSlug, id } = useParams<{ tenantSlug: string; id: string }>()
  const [timeRange, setTimeRange] = useState<DashboardTimeRange>('30D')
  const { data, isLoading, error } = useDashboardData(id, timeRange)
  const { collections } = useCollectionStore()

  /** Resolve a widget config's collection reference (name preferred, id fallback) to the API name. */
  const resolveCollectionName = useMemo(() => {
    return (config: Record<string, unknown>): string | null => {
      const name = config.collectionName
      if (typeof name === 'string' && name.length > 0) return name
      const idRef = config.collectionId
      if (typeof idRef === 'string' && idRef.length > 0) {
        return collections.find((c) => c.id === idRef)?.name ?? null
      }
      return null
    }
  }, [collections])

  const timeRangeLabel = (r: DashboardTimeRange) =>
    ({
      ALL: t('analytics.rangeAll', 'All time'),
      TODAY: t('analytics.rangeToday', 'Today'),
      '7D': t('analytics.range7d', 'Last 7 days'),
      '30D': t('analytics.range30d', 'Last 30 days'),
      '90D': t('analytics.range90d', 'Last 90 days'),
      '1Y': t('analytics.range1y', 'Last year'),
    })[r]

  /** Chip shown on a widget that opts out of the page's time range (config.ignoreTimeRange /
   * config.fixedTimeRange) so the reader knows the selected page range doesn't apply to it. */
  const widgetTimeRangeChip = (config: Record<string, unknown>): string | undefined => {
    if (config.ignoreTimeRange === true) return t('analytics.rangeAll', 'All time')
    const fixed = config.fixedTimeRange
    if (typeof fixed === 'string' && fixed.length > 0) {
      return timeRangeLabel(fixed as DashboardTimeRange) ?? fixed
    }
    return undefined
  }

  const renderWidgetBody = (
    component: DashboardComponentRow,
    payload: WidgetPayload | undefined
  ) => {
    if (!payload || payload.error) {
      return null // WidgetFrame renders the error / missing state
    }
    const collectionName = resolveCollectionName(component.config)
    switch (component.componentType) {
      case 'metric':
        return <MetricWidget data={payload.data ?? {}} />
      case 'chart':
        return (
          <ChartWidget
            data={payload.data ?? {}}
            chartStyle={component.config.chartStyle === 'pie' ? 'pie' : 'bar'}
            onSegmentClick={
              collectionName
                ? (label) => {
                    const groupByField = (payload.data?.groupByField as string) ?? null
                    if (groupByField) {
                      navigate(
                        buildListUrl(tenantSlug!, collectionName, [
                          { field: groupByField, operator: 'equals', value: label },
                        ])
                      )
                    }
                  }
                : undefined
            }
          />
        )
      case 'table':
      case 'recent':
        return (
          <RecordsWidget
            payload={payload}
            onRowClick={
              collectionName
                ? (recordId) => navigate(`/${tenantSlug}/app/o/${collectionName}/${recordId}`)
                : undefined
            }
          />
        )
      default:
        return (
          <div className="text-sm text-muted-foreground">
            {t('analytics.unknownWidget', 'Unsupported widget type')}: {component.componentType}
          </div>
        )
    }
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
            {data?.dashboardName ?? '…'}
          </h1>
        </div>
        <Select value={timeRange} onValueChange={(v) => setTimeRange(v as DashboardTimeRange)}>
          <SelectTrigger className="w-44" data-testid="time-range-select">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {TIME_RANGES.map((r) => (
              <SelectItem key={r} value={r}>
                {timeRangeLabel(r)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {error ? (
        <div
          className="rounded-[10px] border border-border bg-card p-8 text-center text-sm text-muted-foreground"
          data-testid="dashboard-error"
        >
          {t(
            'analytics.loadFailed',
            "Couldn't load this dashboard. Retry, or check its configuration."
          )}
        </div>
      ) : !isLoading && data && data.components.length === 0 ? (
        <div
          className="rounded-[10px] border border-border bg-card p-8 text-center text-sm text-muted-foreground"
          data-testid="dashboard-empty"
        >
          {t('analytics.dashboardEmpty', 'This dashboard has no widgets yet.')}
        </div>
      ) : (
        <div
          className="grid gap-4"
          style={{
            // Grid width = what the authored positions actually use (dashboard grids are
            // small — typically 2-4 columns), never below 2 so single-widget rows breathe.
            gridTemplateColumns: `repeat(${Math.max(
              2,
              ...(data?.components ?? []).map((c) => c.columnPosition + c.columnSpan - 1)
            )}, minmax(0, 1fr))`,
            gridAutoRows: 'minmax(120px, auto)',
          }}
          data-testid="dashboard-grid"
        >
          {(data?.components ?? []).map((component) => (
            <div
              key={component.id}
              style={{
                gridColumn: `${component.columnPosition} / span ${component.columnSpan}`,
                gridRow: `${component.rowPosition} / span ${component.rowSpan}`,
              }}
            >
              <WidgetFrame
                title={component.title}
                isLoading={isLoading}
                error={data?.widgets[component.id]?.error}
                timeRangeChip={widgetTimeRangeChip(component.config)}
              >
                {renderWidgetBody(component, data?.widgets[component.id])}
              </WidgetFrame>
            </div>
          ))}
        </div>
      )}
    </main>
  )
}
