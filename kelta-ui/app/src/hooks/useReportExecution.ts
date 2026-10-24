import { useQuery } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

interface ReportColumn {
  fieldName: string
  label: string
  type: string
}

export interface ReportExecutionResult {
  reportId: string
  reportName: string | null
  reportType: string | null
  columns: ReportColumn[]
  records: Array<Record<string, unknown>>
  groups: Record<string, Array<Record<string, unknown>>> | null
  groupAggregations: Record<string, Record<string, unknown>> | null
  totalCount: number
  currentPage: number
  pageSize: number
  totalPages: number
}

const REPORT_EXECUTION_QUERY_KEY = 'report-execution'

/**
 * Runs a report via POST /api/reports/{id}/execute with pagination. A 400 carries the
 * server's validation message verbatim (e.g. "Cannot group on a masked field: …") — the
 * page renders it as the error state, no client workaround.
 */
export function useReportExecution(reportId: string | undefined, page: number, pageSize: number) {
  const { apiClient } = useApi()

  return useQuery({
    queryKey: [REPORT_EXECUTION_QUERY_KEY, reportId, page, pageSize],
    enabled: !!reportId,
    staleTime: 60 * 1000,
    retry: false,
    queryFn: async (): Promise<ReportExecutionResult> => {
      const doc = await apiClient.post<{
        data?: { attributes?: Record<string, unknown> }
        meta?: Record<string, unknown>
      }>(
        `/api/reports/${encodeURIComponent(reportId!)}/execute` +
          `?page[number]=${page}&page[size]=${pageSize}`,
        {}
      )
      const attrs = doc?.data?.attributes ?? {}
      const meta = doc?.meta ?? {}
      return {
        reportId: String(attrs.reportId ?? reportId),
        reportName: (attrs.reportName as string) ?? null,
        reportType: (attrs.reportType as string) ?? null,
        columns: (attrs.columns as ReportColumn[]) ?? [],
        records: (attrs.records as Array<Record<string, unknown>>) ?? [],
        groups: (attrs.groups as ReportExecutionResult['groups']) ?? null,
        groupAggregations:
          (attrs.groupAggregations as ReportExecutionResult['groupAggregations']) ?? null,
        totalCount: Number(meta.totalCount ?? 0),
        currentPage: Number(meta.currentPage ?? page),
        pageSize: Number(meta.pageSize ?? pageSize),
        totalPages: Number(meta.totalPages ?? 1),
      }
    },
  })
}
