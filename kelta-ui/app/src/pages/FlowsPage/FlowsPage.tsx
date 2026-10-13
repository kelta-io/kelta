import React, { useState, useCallback } from 'react'
import { toast } from 'sonner'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { getTenantSlug } from '../../context/TenantContext'
import { unwrapCollection, unwrapResource } from '../../utils/jsonapi'
import { ConfirmDialog, LoadingSpinner, ErrorMessage, ExecutionLogModal } from '../../components'
import type { LogColumn } from '../../components'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { AdminDataTable, type AdminColumn } from '@/components/AdminDataTable'
import { CreateFlowWizard } from './CreateFlowWizard'
import { TestFlowDialog } from '../FlowDesignerPage/components/TestFlowDialog'

interface Flow {
  id: string
  name: string
  description: string | null
  flowType: string
  active: boolean
  definition: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

interface FlowExecutionLog {
  id: string
  flowId: string
  flowName: string
  status: string
  startedBy: string | null
  triggerRecordId: string | null
  currentNodeId: string | null
  errorMessage: string | null
  startedAt: string
  completedAt: string | null
}

export interface FlowsPageProps {
  testId?: string
}

export function FlowsPage({ testId = 'flows-page' }: FlowsPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { t, formatDate } = useI18n()
  const { apiClient, keltaClient } = useApi()

  const [wizardOpen, setWizardOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [flowToDelete, setFlowToDelete] = useState<Flow | null>(null)
  const [execItemId, setExecItemId] = useState<string | null>(null)
  const [_execItemName, setExecItemName] = useState('')
  const [runDialogOpen, setRunDialogOpen] = useState(false)
  const [runFlowTarget, setRunFlowTarget] = useState<Flow | null>(null)

  const {
    data: flows,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['flows'],
    queryFn: () => keltaClient.admin.flows.list(),
  })

  const flowList: Flow[] = (flows ?? []) as unknown as Flow[]

  const deleteMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.flows.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['flows'] })
      toast.success('Flow deleted successfully')
      setDeleteDialogOpen(false)
      setFlowToDelete(null)
    },
    onError: (err: Error) => {
      toast.error(err.message || 'An error occurred')
    },
  })

  const {
    data: executions,
    isLoading: execLoading,
    error: execError,
  } = useQuery({
    queryKey: ['flow-executions', execItemId],
    queryFn: async () => {
      const resp = await apiClient.fetch(`/api/flows/${execItemId}/flow-executions`)
      if (!resp.ok) throw new Error('Failed to load executions')
      const json = await resp.json()
      return unwrapCollection<FlowExecutionLog>(json).data
    },
    enabled: !!execItemId,
  })

  const executeMutation = useMutation({
    mutationFn: async ({ flowId, state }: { flowId: string; state: Record<string, unknown> }) => {
      const resp = await apiClient.fetch(`/api/flows/${flowId}/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ state }),
      })
      if (!resp.ok) {
        const errBody = await resp.json().catch(() => ({}))
        throw new Error(
          (errBody as Record<string, string>).message || `Execute failed: ${resp.statusText}`
        )
      }
      // The execute endpoint returns a JSON:API envelope
      // ({ data: { type: 'flow-executions', id, attributes } }); the execution id is `data.id`.
      return unwrapResource<{ id: string }>(await resp.json())
    },
    onSuccess: (data) => {
      toast.success(t('flows.executionStarted'))
      setRunDialogOpen(false)
      if (execItemId) {
        queryClient.invalidateQueries({ queryKey: ['flow-executions', execItemId] })
      }
      // Navigate to the debug view in the flow designer
      if (runFlowTarget && data?.id) {
        navigate(`/${getTenantSlug()}/flows/${runFlowTarget.id}/design?executionId=${data.id}`)
      }
    },
    onError: (err: Error) => toast.error(err.message),
  })

  const cancelMutation = useMutation({
    mutationFn: async (executionId: string) => {
      const resp = await apiClient.fetch(`/api/flows/executions/${executionId}/cancel`, {
        method: 'POST',
      })
      if (!resp.ok) throw new Error(`Cancel failed: ${resp.statusText}`)
    },
    onSuccess: () => {
      toast.success(t('flows.executionCancelled'))
      queryClient.invalidateQueries({ queryKey: ['flow-executions', execItemId] })
    },
    onError: (err: Error) => toast.error(err.message),
  })

  const execColumns: LogColumn<FlowExecutionLog>[] = [
    {
      key: 'status',
      header: t('logs.status'),
      render: (v) => {
        const status = v as string
        const colorMap: Record<string, { color: string; bg: string }> = {
          SUCCESS: { color: '#065f46', bg: '#d1fae5' },
          FAILED: { color: '#991b1b', bg: '#fee2e2' },
          RUNNING: { color: '#1e40af', bg: '#dbeafe' },
          CANCELLED: { color: '#6b7280', bg: '#f3f4f6' },
        }
        const style = colorMap[status] ?? { color: '#6b7280', bg: '#f3f4f6' }
        return (
          <span
            style={{
              display: 'inline-block',
              padding: '0.125rem 0.5rem',
              borderRadius: '9999px',
              fontSize: '0.75rem',
              fontWeight: 600,
              color: style.color,
              backgroundColor: style.bg,
            }}
          >
            {status}
          </span>
        )
      },
    },
    { key: 'startedBy', header: t('flows.startedBy') },
    { key: 'triggerRecordId', header: t('flows.triggerRecord') },
    { key: 'currentNodeId', header: t('flows.currentNode') },
    { key: 'errorMessage', header: t('logs.errorMessage') },
    {
      key: 'startedAt',
      header: t('logs.startedAt'),
      render: (v) =>
        v
          ? formatDate(new Date(v as string), {
              year: 'numeric',
              month: 'short',
              day: 'numeric',
              hour: '2-digit',
              minute: '2-digit',
            })
          : '-',
    },
    {
      key: 'completedAt',
      header: t('logs.completedAt'),
      render: (v) =>
        v
          ? formatDate(new Date(v as string), {
              year: 'numeric',
              month: 'short',
              day: 'numeric',
              hour: '2-digit',
              minute: '2-digit',
            })
          : '-',
    },
    {
      key: 'actions',
      header: '',
      render: (_v: unknown, row: FlowExecutionLog) =>
        row.status === 'RUNNING' ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-auto border-destructive/30 px-2 py-1 text-xs text-destructive hover:bg-destructive/10"
            onClick={() => cancelMutation.mutate(row.id)}
            disabled={cancelMutation.isPending}
          >
            {t('flows.cancelExecution')}
          </Button>
        ) : null,
    },
  ]
  void execColumns

  const handleDeleteClick = useCallback((flow: Flow) => {
    setFlowToDelete(flow)
    setDeleteDialogOpen(true)
  }, [])

  const handleDeleteConfirm = useCallback(() => {
    if (flowToDelete) {
      deleteMutation.mutate(flowToDelete.id)
    }
  }, [flowToDelete, deleteMutation])

  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
    setFlowToDelete(null)
  }, [])

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading flows..." />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error('An error occurred')}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-foreground">Flows</h1>
        <Button
          type="button"
          onClick={() => setWizardOpen(true)}
          aria-label="Create Flow"
          data-testid="add-flow-button"
        >
          Create Flow
        </Button>
      </header>

      {flowList.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card py-16 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          <p>No flows found.</p>
        </div>
      ) : (
        <div
          className="overflow-x-auto rounded-lg border border-border bg-card"
          role="grid"
          aria-label="Flows"
          data-testid="flows-table"
        >
          <AdminDataTable
            tableId="flows"
            rows={flowList}
            rowKey={(f) => f.id}
            columns={
              [
                { id: 'name', header: 'Name', accessor: (r) => r.name },
                {
                  id: 'flowType',
                  header: 'Flow Type',
                  accessor: (r) => r.flowType,
                  cell: (r) => (
                    <span className="inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold uppercase tracking-wider text-primary">
                      {r.flowType}
                    </span>
                  ),
                },
                {
                  id: 'active',
                  header: 'Active',
                  accessor: (r) => r.active,
                  cell: (r) => (
                    <span
                      className={cn(
                        'inline-block rounded-full px-3 py-1 text-xs font-semibold',
                        r.active
                          ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                          : 'bg-muted text-muted-foreground'
                      )}
                    >
                      {r.active ? 'Yes' : 'No'}
                    </span>
                  ),
                },
                {
                  id: 'createdAt',
                  header: 'Created',
                  accessor: (r) => r.createdAt,
                  cell: (r) =>
                    formatDate(new Date(r.createdAt), {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    }),
                },
              ] as AdminColumn<Flow>[]
            }
            renderActions={(flow) => {
              const index = flowList.indexOf(flow)
              return (
                <div className="flex justify-end gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => navigate(`/${getTenantSlug()}/flows/${flow.id}/design`)}
                    aria-label={`Design ${flow.name}`}
                    data-testid={`design-button-${index}`}
                  >
                    Design
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setExecItemId(flow.id)
                      setExecItemName(flow.name)
                    }}
                    aria-label={`View executions for ${flow.name}`}
                    data-testid={`executions-button-${index}`}
                  >
                    {t('flows.executions')}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setRunFlowTarget(flow)
                      setRunDialogOpen(true)
                    }}
                    disabled={!flow.active || executeMutation.isPending}
                    aria-label={`Run ${flow.name}`}
                    data-testid={`run-button-${index}`}
                  >
                    {t('flows.runFlow')}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => navigate(`/${getTenantSlug()}/flows/${flow.id}/design`)}
                    aria-label={`Edit ${flow.name}`}
                    data-testid={`edit-button-${index}`}
                  >
                    Edit
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="border-destructive/30 text-destructive hover:bg-destructive/10"
                    onClick={() => handleDeleteClick(flow)}
                    aria-label={`Delete ${flow.name}`}
                    data-testid={`delete-button-${index}`}
                  >
                    Delete
                  </Button>
                </div>
              )
            }}
          />
        </div>
      )}

      <CreateFlowWizard open={wizardOpen} onOpenChange={setWizardOpen} />

      <ConfirmDialog
        open={deleteDialogOpen}
        title="Delete Flow"
        message="Are you sure you want to delete this flow? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
        variant="danger"
      />

      {execItemId && (
        <ExecutionLogModal<FlowExecutionLog>
          title={t('flows.flowExecutionHistory')}
          subtitle={_execItemName}
          columns={execColumns}
          data={executions ?? []}
          isLoading={execLoading}
          error={execError instanceof Error ? execError : null}
          onClose={() => setExecItemId(null)}
          emptyMessage={t('flows.noExecutions')}
        />
      )}

      <TestFlowDialog
        open={runDialogOpen}
        onOpenChange={setRunDialogOpen}
        flowType={runFlowTarget?.flowType ?? 'AUTOLAUNCHED'}
        onSubmit={(state) => {
          if (runFlowTarget) {
            executeMutation.mutate({ flowId: runFlowTarget.id, state })
          }
        }}
        isLoading={executeMutation.isPending}
      />
    </div>
  )
}

export default FlowsPage
