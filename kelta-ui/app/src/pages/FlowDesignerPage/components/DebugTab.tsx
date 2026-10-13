import { useState, useMemo } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation } from '@tanstack/react-query'
import { ReactFlowProvider } from '@xyflow/react'
import type { Node, Edge } from '@xyflow/react'
import { useApi } from '../../../context/ApiContext'
import { unwrapResource, unwrapCollection } from '../../../utils/jsonapi'
import { LoadingSpinner } from '../../../components'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { CheckCircle2, XCircle, Clock, RotateCcw, Copy, ChevronDown, ChevronUp } from 'lucide-react'
import { FlowExecutionViewer } from './FlowExecutionViewer'
import { StepDetailPanel } from './StepDetailPanel'
import { ExecutionTimeline } from './ExecutionTimeline'
import { formatDuration } from '@/utils/formatDuration'

interface FlowExecution {
  id: string
  flowId: string
  tenantId: string
  status: string
  startedBy: string | null
  triggerRecordId: string | null
  currentNodeId: string | null
  stepCount: number
  durationMs: number | null
  errorMessage: string | null
  isTest: boolean
  stateData: Record<string, unknown> | null
  initialInput: Record<string, unknown> | null
  startedAt: string | null
  completedAt: string | null
}

interface StepLog {
  id: string
  stateId: string
  stateName: string | null
  stateType: string
  status: string
  inputSnapshot: Record<string, unknown> | null
  outputSnapshot: Record<string, unknown> | null
  errorMessage: string | null
  errorCode: string | null
  attemptNumber: number
  durationMs: number | null
  startedAt: string | null
  completedAt: string | null
}

interface DebugTabProps {
  executionId: string
  nodes: Node[]
  edges: Edge[]
}

function JsonViewer({ data, label }: { data: unknown; label: string }) {
  const [expanded, setExpanded] = useState(false)
  if (data == null) return null
  return (
    <div className="mt-2">
      <button
        className="flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
        {label}
      </button>
      {expanded && (
        <pre className="mt-1 max-h-[200px] overflow-auto rounded border border-border bg-muted p-2 font-mono text-[10px] text-foreground">
          {JSON.stringify(data, null, 2)}
        </pre>
      )}
    </div>
  )
}

export function DebugTab({ executionId, nodes, edges }: DebugTabProps) {
  const { apiClient } = useApi()
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)

  // Fetch execution details
  const { data: execution, isLoading: execLoading } = useQuery({
    queryKey: ['flow-execution-detail', executionId],
    queryFn: async () => {
      const resp = await apiClient.fetch(`/api/flows/executions/${executionId}`)
      if (!resp.ok) throw new Error('Failed to load execution')
      const json = await resp.json()
      return unwrapResource(json) as unknown as FlowExecution
    },
    refetchInterval: (query) => {
      const exec = query.state.data
      if (exec && (exec.status === 'RUNNING' || exec.status === 'WAITING')) return 2000
      return false
    },
  })

  // Fetch step logs
  const { data: stepsData, isLoading: stepsLoading } = useQuery({
    queryKey: ['flow-execution-steps', executionId],
    queryFn: async () => {
      const resp = await apiClient.fetch(`/api/flows/executions/${executionId}/steps`)
      if (!resp.ok) throw new Error('Failed to load steps')
      const json = await resp.json()
      return unwrapCollection(json).data as unknown as StepLog[]
    },
    refetchInterval: () => {
      if (execution && (execution.status === 'RUNNING' || execution.status === 'WAITING'))
        return 2000
      return false
    },
  })

  const steps = stepsData || []

  // Find selected step
  const selectedStep = useMemo(() => {
    if (!selectedNodeId) return null
    // Find the most recent step for this node
    for (let i = steps.length - 1; i >= 0; i--) {
      if (steps[i].stateId === selectedNodeId) return steps[i]
    }
    return null
  }, [selectedNodeId, steps])

  const retryMutation = useMutation({
    mutationFn: async (mode: string) => {
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
    onSuccess: () => {
      toast.success('Retry started')
    },
    onError: (err: Error) => toast.error(err.message),
  })

  const handleCopyId = () => {
    navigator.clipboard.writeText(executionId)
    toast.success('Execution ID copied')
  }

  if (execLoading || stepsLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <LoadingSpinner />
      </div>
    )
  }

  if (!execution) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-muted-foreground">Execution not found</p>
      </div>
    )
  }

  const isTerminal =
    execution.status === 'COMPLETED' ||
    execution.status === 'FAILED' ||
    execution.status === 'CANCELLED'

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Execution Summary Bar */}
      <div className="flex items-center justify-between border-b border-border bg-card px-4 py-2">
        <div className="flex items-center gap-3">
          {execution.status === 'COMPLETED' && (
            <CheckCircle2 className="h-4 w-4 text-emerald-600" />
          )}
          {execution.status === 'FAILED' && <XCircle className="h-4 w-4 text-red-600" />}
          {execution.status === 'RUNNING' && (
            <div className="h-3 w-3 animate-pulse rounded-full bg-blue-500" />
          )}
          {execution.status === 'WAITING' && <Clock className="h-4 w-4 text-amber-600" />}

          <span
            className={cn(
              'text-sm font-medium',
              execution.status === 'COMPLETED' && 'text-emerald-700 dark:text-emerald-400',
              execution.status === 'FAILED' && 'text-red-700 dark:text-red-400',
              execution.status === 'RUNNING' && 'text-blue-700 dark:text-blue-400',
              execution.status === 'WAITING' && 'text-amber-700 dark:text-amber-400',
              execution.status === 'CANCELLED' && 'text-muted-foreground'
            )}
          >
            {execution.status}
          </span>

          {execution.isTest && (
            <span className="rounded border border-dashed border-border px-1.5 py-0.5 text-[10px] text-muted-foreground">
              TEST
            </span>
          )}

          <span className="text-xs text-muted-foreground">
            {execution.stepCount} steps &middot; {formatDuration(execution.durationMs)}
          </span>

          <button
            onClick={handleCopyId}
            className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-mono text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            title="Copy execution ID"
          >
            <Copy className="h-2.5 w-2.5" />
            {executionId.slice(0, 8)}...
          </button>
        </div>

        <div className="flex items-center gap-2">
          {isTerminal && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 gap-1 px-2 text-xs"
              onClick={() => retryMutation.mutate('full')}
              disabled={retryMutation.isPending}
            >
              <RotateCcw className="h-3 w-3" />
              Retry
            </Button>
          )}
        </div>
      </div>

      {/* Main Content: Canvas + Side Panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: State Summary */}
        <div className="flex w-56 shrink-0 flex-col overflow-auto border-r border-border bg-card p-3">
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Execution Info
          </h3>
          <div className="space-y-2 text-xs">
            <div>
              <span className="text-muted-foreground">Started By:</span>{' '}
              <span className="text-foreground">{execution.startedBy || 'Trigger'}</span>
            </div>
            {execution.triggerRecordId && (
              <div>
                <span className="text-muted-foreground">Trigger Record:</span>{' '}
                <span className="font-mono text-foreground">
                  {execution.triggerRecordId.slice(0, 8)}...
                </span>
              </div>
            )}
            <div>
              <span className="text-muted-foreground">Current Node:</span>{' '}
              <span className="text-foreground">{execution.currentNodeId || 'Complete'}</span>
            </div>
            {execution.errorMessage && (
              <div className="rounded border border-red-200 bg-red-50 p-2 text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
                {execution.errorMessage}
              </div>
            )}
          </div>

          <JsonViewer data={execution.initialInput} label="Initial Input" />
          <JsonViewer data={execution.stateData} label="Final State" />
        </div>

        {/* Center: Canvas */}
        <ReactFlowProvider>
          <FlowExecutionViewer
            nodes={nodes}
            edges={edges}
            steps={steps}
            selectedNodeId={selectedNodeId}
            onNodeSelect={setSelectedNodeId}
          />
        </ReactFlowProvider>

        {/* Right: Step Detail */}
        <StepDetailPanel step={selectedStep} onClose={() => setSelectedNodeId(null)} />
      </div>

      {/* Bottom: Timeline */}
      {steps.length > 0 && (
        <ExecutionTimeline
          steps={steps}
          selectedStepId={selectedNodeId}
          onStepSelect={setSelectedNodeId}
        />
      )}
    </div>
  )
}
