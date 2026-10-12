import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../../context/ApiContext'
import { useToast, LoadingSpinner } from '../../../components'
import { unwrapCollection } from '../../../utils/jsonapi'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { formatDuration } from '@/utils/formatDuration'
import {
  CheckCircle2,
  XCircle,
  Loader2,
  Clock,
  Ban,
  RotateCcw,
  Eye,
  ChevronDown,
} from 'lucide-react'

interface FlowExecution {
  id: string
  flowId: string
  status: string
  startedBy: string | null
  triggerRecordId: string | null
  currentNodeId: string | null
  stepCount: number
  durationMs: number | null
  errorMessage: string | null
  isTest: boolean
  startedAt: string | null
  completedAt: string | null
}

interface ExecutionsTabProps {
  flowId: string
  onViewExecution: (executionId: string) => void
}

const statusFilters = ['All', 'COMPLETED', 'FAILED', 'RUNNING', 'WAITING', 'CANCELLED'] as const

function statusIcon(status: string) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600" />
    case 'FAILED':
      return <XCircle className="h-3.5 w-3.5 text-red-600" />
    case 'RUNNING':
      return <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-600" />
    case 'WAITING':
      return <Clock className="h-3.5 w-3.5 text-amber-600" />
    case 'CANCELLED':
      return <Ban className="h-3.5 w-3.5 text-gray-500" />
    default:
      return null
  }
}

function statusBadge(status: string) {
  const colorMap: Record<string, string> = {
    COMPLETED: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
    FAILED: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
    RUNNING: 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300',
    WAITING: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
    CANCELLED: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400',
  }
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
        colorMap[status] || 'bg-gray-100 text-gray-600'
      )}
    >
      {statusIcon(status)}
      {status}
    </span>
  )
}

function formatRelativeTime(dateStr: string | null): string {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 60) return `${diffSec}s ago`
  const diffMin = Math.floor(diffSec / 60)
  if (diffMin < 60) return `${diffMin}m ago`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}h ago`
  const diffDay = Math.floor(diffHr / 24)
  return `${diffDay}d ago`
}

export function ExecutionsTab({ flowId, onViewExecution }: ExecutionsTabProps) {
  const { apiClient } = useApi()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<string>('All')
  const [showTestRuns, setShowTestRuns] = useState(false)

  const { data, isLoading } = useQuery({
    queryKey: ['flow-executions-tab', flowId],
    queryFn: async () => {
      const resp = await apiClient.fetch(`/api/flows/${flowId}/flow-executions?limit=100`)
      if (!resp.ok) throw new Error('Failed to load executions')
      const json = await resp.json()
      return unwrapCollection<FlowExecution>(json).data
    },
    refetchInterval: 5000,
  })

  const cancelMutation = useMutation({
    mutationFn: async (executionId: string) => {
      const resp = await apiClient.fetch(`/api/flows/executions/${executionId}/cancel`, {
        method: 'POST',
      })
      if (!resp.ok) throw new Error('Failed to cancel execution')
    },
    onSuccess: () => {
      showToast('Execution cancelled', 'success')
      queryClient.invalidateQueries({ queryKey: ['flow-executions-tab', flowId] })
    },
    onError: (err: Error) => showToast(err.message, 'error'),
  })

  const retryMutation = useMutation({
    mutationFn: async ({ executionId, mode }: { executionId: string; mode: string }) => {
      const resp = await apiClient.fetch(
        `/api/flows/executions/${executionId}/retry?mode=${mode}`,
        { method: 'POST' }
      )
      if (!resp.ok) {
        const body = (await resp.json().catch(() => ({}))) as Record<string, string>
        throw new Error(body.error || 'Failed to retry execution')
      }
      return (await resp.json()) as { executionId: string }
    },
    onSuccess: (result) => {
      showToast('Retry started', 'success')
      queryClient.invalidateQueries({ queryKey: ['flow-executions-tab', flowId] })
      onViewExecution(result.executionId)
    },
    onError: (err: Error) => showToast(err.message, 'error'),
  })

  const executions = data || []
  const filtered = useMemo(() => {
    return executions.filter((exec) => {
      if (!showTestRuns && exec.isTest) return false
      if (statusFilter !== 'All' && exec.status !== statusFilter) return false
      return true
    })
  }, [executions, statusFilter, showTestRuns])

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <LoadingSpinner />
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Filters */}
      <div className="flex items-center gap-3 border-b border-border px-4 py-3">
        <div className="relative">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="appearance-none rounded-md border border-border bg-card py-1.5 pl-3 pr-8 text-xs text-foreground"
          >
            {statusFilters.map((s) => (
              <option key={s} value={s}>
                {s === 'All' ? 'All Statuses' : s}
              </option>
            ))}
          </select>
          <ChevronDown className="pointer-events-none absolute right-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
        </div>
        <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <input
            type="checkbox"
            checked={showTestRuns}
            onChange={(e) => setShowTestRuns(e.target.checked)}
            className="h-3.5 w-3.5 accent-primary"
          />
          Show test runs
        </label>
        <span className="text-xs text-muted-foreground">
          {filtered.length} execution{filtered.length !== 1 ? 's' : ''}
        </span>
      </div>

      {/* Table */}
      <div className="flex-1 overflow-auto">
        {filtered.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <p className="text-sm text-muted-foreground">No executions found</p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted text-xs font-medium uppercase tracking-wider text-muted-foreground">
                <th className="px-4 py-2 text-left">Status</th>
                <th className="px-4 py-2 text-left">Steps</th>
                <th className="px-4 py-2 text-left">Duration</th>
                <th className="px-4 py-2 text-left">Started</th>
                <th className="px-4 py-2 text-left">Error</th>
                <th className="px-4 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((exec) => (
                <tr
                  key={exec.id}
                  className="border-b border-border transition-colors last:border-b-0 hover:bg-muted/50"
                >
                  <td className="px-4 py-2.5">
                    <div className="flex items-center gap-2">
                      {statusBadge(exec.status)}
                      {exec.isTest && (
                        <span className="rounded border border-dashed border-border px-1.5 py-0.5 text-[10px] text-muted-foreground">
                          TEST
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-2.5 text-foreground">{exec.stepCount || 0}</td>
                  <td className="px-4 py-2.5 text-foreground">{formatDuration(exec.durationMs)}</td>
                  <td className="px-4 py-2.5 text-muted-foreground">
                    {formatRelativeTime(exec.startedAt)}
                  </td>
                  <td className="max-w-[200px] truncate px-4 py-2.5 text-xs text-red-600">
                    {exec.errorMessage || ''}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 gap-1 px-2 text-xs"
                        onClick={() => onViewExecution(exec.id)}
                      >
                        <Eye className="h-3 w-3" />
                        View
                      </Button>
                      {exec.status === 'RUNNING' && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="h-7 gap-1 border-destructive/30 px-2 text-xs text-destructive hover:bg-destructive/10"
                          onClick={() => cancelMutation.mutate(exec.id)}
                          disabled={cancelMutation.isPending}
                        >
                          <Ban className="h-3 w-3" />
                          Cancel
                        </Button>
                      )}
                      {(exec.status === 'FAILED' || exec.status === 'CANCELLED') && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="h-7 gap-1 px-2 text-xs"
                          onClick={() =>
                            retryMutation.mutate({ executionId: exec.id, mode: 'full' })
                          }
                          disabled={retryMutation.isPending}
                        >
                          <RotateCcw className="h-3 w-3" />
                          Retry
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
