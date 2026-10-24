import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

export type DashboardTimeRange = 'ALL' | 'TODAY' | '7D' | '30D' | '90D' | '1Y'

/** A dashboard-components row (layout + config), from the generic JSON:API route. */
export interface DashboardComponentRow {
  id: string
  componentType: 'metric' | 'chart' | 'table' | 'recent' | string
  title: string | null
  columnPosition: number
  rowPosition: number
  columnSpan: number
  rowSpan: number
  sortOrder: number
  config: Record<string, unknown>
}

/** Per-widget result from POST /api/dashboards/{id}/data — data XOR error. */
export interface WidgetPayload {
  type?: string
  data?: Record<string, unknown>
  pagination?: {
    totalCount: number
    currentPage: number
    pageSize: number
    totalPages: number
  }
  error?: string
}

export interface DashboardData {
  dashboardName: string | null
  components: DashboardComponentRow[]
  widgets: Record<string, WidgetPayload>
}

const DASHBOARD_DATA_QUERY_KEY = 'dashboard-data'

function parseConfig(raw: unknown): Record<string, unknown> {
  if (raw && typeof raw === 'object') return raw as Record<string, unknown>
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

/**
 * Joined dashboard view model: layout rows from `dashboard-components` (the data endpoint
 * returns widget data keyed by componentId with NO layout metadata) + widget payloads from
 * POST /api/dashboards/{id}/data. Re-runs when the preset time range changes.
 */
export function useDashboardData(dashboardId: string | undefined, timeRange: DashboardTimeRange) {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: [DASHBOARD_DATA_QUERY_KEY, dashboardId, timeRange],
    enabled: !!dashboardId,
    staleTime: 60 * 1000,
    queryFn: async (): Promise<DashboardData> => {
      const [components, dataDoc] = await Promise.all([
        apiClient.getList<Record<string, unknown>>(
          `/api/dashboard-components?filter[dashboardId][eq]=${encodeURIComponent(dashboardId!)}` +
            `&sort=sortOrder&page[size]=100`
        ),
        apiClient.post<{
          data?: {
            attributes?: {
              dashboardName?: string
              widgets?: Record<string, WidgetPayload>
            }
          }
        }>(
          `/api/dashboards/${encodeURIComponent(dashboardId!)}/data`,
          timeRange === 'ALL' ? {} : { timeRange }
        ),
      ])

      const rows: DashboardComponentRow[] = (components ?? []).map((c) => ({
        id: String(c.id),
        componentType: String(c.componentType ?? ''),
        title: (c.title as string) ?? null,
        columnPosition: Number(c.columnPosition ?? 1),
        rowPosition: Number(c.rowPosition ?? 1),
        columnSpan: Number(c.columnSpan ?? 1),
        rowSpan: Number(c.rowSpan ?? 1),
        sortOrder: Number(c.sortOrder ?? 0),
        config: parseConfig(c.config),
      }))

      const attributes = dataDoc?.data?.attributes ?? {}
      return {
        dashboardName: attributes.dashboardName ?? null,
        components: rows,
        widgets: attributes.widgets ?? {},
      }
    },
  })

  return {
    ...query,
    invalidate: () =>
      queryClient.invalidateQueries({ queryKey: [DASHBOARD_DATA_QUERY_KEY, dashboardId] }),
  }
}
