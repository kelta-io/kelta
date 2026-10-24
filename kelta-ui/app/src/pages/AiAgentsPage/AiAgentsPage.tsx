import React, { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation } from '@tanstack/react-query'
import { z } from 'zod'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

export interface AiAgentsPageProps {
  testId?: string
}

interface AgentDefinition {
  id: string
  tenantId: string
  name: string
  description: string | null
  systemPrompt: string
  model: string | null
  maxTokens: number | null
  allowedTools: string[]
  monthlyTokenBudget: number | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}

interface AgentToolTrace {
  name: string
  input: Record<string, unknown>
  resultJson: string
  isError: boolean
  permitted: boolean
}

interface AgentRunResult {
  finalText: string
  toolCalls: AgentToolTrace[]
  inputTokens: number
  outputTokens: number
  iterations: number
  stopReason: string | null
  budgetExceeded: boolean
  maxIterationsReached: boolean
}

const agentSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.trim().length <= 255, 'Name must be at most 255 characters'),
  description: z.string(),
  systemPrompt: z.string().refine((v) => v.trim().length > 0, 'System prompt is required'),
  model: z.string(),
  maxTokens: z.string(),
  allowedTools: z.string(),
  monthlyTokenBudget: z.string(),
  enabled: z.boolean(),
})

type AgentFormData = z.infer<typeof agentSchema>

function toFormData(agent?: AgentDefinition): AgentFormData {
  return {
    name: agent?.name ?? '',
    description: agent?.description ?? '',
    systemPrompt: agent?.systemPrompt ?? '',
    model: agent?.model ?? '',
    maxTokens: agent?.maxTokens != null ? String(agent.maxTokens) : '',
    allowedTools: agent?.allowedTools?.join(', ') ?? '',
    monthlyTokenBudget: agent?.monthlyTokenBudget != null ? String(agent.monthlyTokenBudget) : '',
    enabled: agent?.enabled ?? true,
  }
}

function toRequestBody(data: AgentFormData): Record<string, unknown> {
  const allowedTools = data.allowedTools
    .split(',')
    .map((t) => t.trim())
    .filter((t) => t.length > 0)
  return {
    name: data.name.trim(),
    description: data.description.trim() || null,
    systemPrompt: data.systemPrompt,
    model: data.model.trim() || null,
    maxTokens: data.maxTokens.trim() ? Number(data.maxTokens) : null,
    allowedTools,
    monthlyTokenBudget: data.monthlyTokenBudget.trim() ? Number(data.monthlyTokenBudget) : null,
    enabled: data.enabled,
  }
}

const inputClass =
  'w-full rounded-md border border-border bg-background px-3 py-2.5 text-sm focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20'

function RunPanel({
  agent,
  onClose,
}: {
  agent: AgentDefinition
  onClose: () => void
}): React.ReactElement {
  const { apiClient } = useApi()
  const [input, setInput] = useState('')
  const [result, setResult] = useState<AgentRunResult | null>(null)

  const runMutation = useMutation({
    mutationFn: (text: string) =>
      apiClient.post<AgentRunResult>(`/api/ai/agents/${agent.id}/run`, { input: text }),
    onSuccess: (data) => setResult(data),
    onError: (err: Error) => toast.error(err.message || 'Run failed'),
  })

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        className="max-h-[90vh] w-full max-w-[720px] overflow-y-auto rounded-lg bg-background shadow-xl"
        data-testid="run-panel"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 className="text-lg font-semibold">Run “{agent.name}”</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="text-muted-foreground"
          >
            ×
          </button>
        </div>
        <div className="space-y-4 p-6">
          <textarea
            data-testid="run-input"
            className={cn(inputClass, 'min-h-[80px] resize-y')}
            placeholder="Message for the agent…"
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
          <div className="flex justify-end">
            <Button
              data-testid="run-submit"
              disabled={runMutation.isPending || input.trim().length === 0}
              onClick={() => runMutation.mutate(input)}
            >
              {runMutation.isPending ? 'Running…' : 'Run'}
            </Button>
          </div>

          {result && (
            <div data-testid="run-result" className="space-y-3 rounded-md border border-border p-4">
              <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
                <span>{result.iterations} iteration(s)</span>
                <span>{result.inputTokens + result.outputTokens} tokens</span>
                <span>stop: {result.stopReason ?? '—'}</span>
                {result.budgetExceeded && <span className="text-destructive">budget exceeded</span>}
                {result.maxIterationsReached && (
                  <span className="text-destructive">max iterations</span>
                )}
              </div>
              <pre className="whitespace-pre-wrap text-sm">{result.finalText || '(no text)'}</pre>
              {result.toolCalls.length > 0 && (
                <div>
                  <p className="mb-1 text-xs font-semibold">Tool calls</p>
                  <ul className="space-y-1">
                    {result.toolCalls.map((tc, i) => (
                      <li key={i} className="text-xs">
                        <span className="font-mono">{tc.name}</span>{' '}
                        {!tc.permitted && <span className="text-destructive">(refused)</span>}
                        {tc.isError && <span className="text-destructive">(error)</span>}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export function AiAgentsPage({ testId = 'ai-agents-page' }: AiAgentsPageProps): React.ReactElement {
  const { apiClient } = useApi()
  const [runningAgent, setRunningAgent] = useState<AgentDefinition | null>(null)

  const {
    data: agents,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['ai-agents'],
    queryFn: () => apiClient.get<AgentDefinition[]>('/api/ai/agents'),
  })
  const list = agents ?? []

  const crud = useAdminCrud<AgentDefinition, AgentFormData>({
    queryKey: ['ai-agents'],
    create: (data) => apiClient.post('/api/ai/agents', toRequestBody(data)),
    update: (agent, data) => apiClient.put(`/api/ai/agents/${agent.id}`, toRequestBody(data)),
    remove: (agent) => apiClient.delete(`/api/ai/agents/${agent.id}`),
    messages: {
      created: 'Agent created',
      updated: 'Agent updated',
      deleted: 'Agent deleted',
      error: 'An error occurred',
      createError: 'Failed to create agent',
      updateError: 'Failed to update agent',
      deleteError: 'Failed to delete agent',
    },
  })

  const columns = useMemo<AdminColumn<AgentDefinition>[]>(
    () => [
      { id: 'name', header: 'Name', cellClassName: 'font-medium text-foreground' },
      {
        id: 'model',
        header: 'Model',
        cell: (agent) => agent.model ?? 'default',
        cellClassName: 'text-muted-foreground',
      },
      {
        id: 'allowedTools',
        header: 'Tools',
        accessor: (agent) => agent.allowedTools.length,
        cellClassName: 'text-muted-foreground',
      },
      {
        id: 'enabled',
        header: 'Status',
        cell: (agent) => (
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-xs',
              agent.enabled ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
            )}
          >
            {agent.enabled ? 'Enabled' : 'Disabled'}
          </span>
        ),
      },
    ],
    []
  )

  return (
    <AdminCrudPage
      testId={testId}
      title="AI Agents"
      description="Governed assistants with a fixed prompt, an allowed tool subset, and token budgets."
      actions={
        <Button data-testid="new-agent-button" onClick={crud.openCreate}>
          New Agent
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={list.length === 0}
      emptyMessage="No agents yet. Create one to get started."
      list={
        <AdminDataTable
          tableId="ai-agents"
          testId="ai-agents-table"
          ariaLabel="AI Agents"
          columns={columns}
          rows={list}
          rowKey={(agent) => agent.id}
          renderActions={(agent) => (
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                size="sm"
                data-testid={`run-agent-button-${agent.id}`}
                onClick={() => setRunningAgent(agent)}
              >
                Run
              </Button>
              <Button
                variant="outline"
                size="sm"
                data-testid={`edit-button-${agent.id}`}
                onClick={() => crud.openEdit(agent)}
              >
                Edit
              </Button>
              <Button
                variant="outline"
                size="sm"
                data-testid={`delete-button-${agent.id}`}
                onClick={() => crud.requestDelete(agent)}
              >
                Delete
              </Button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<AgentFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={crud.editing ? 'Edit Agent' : 'New Agent'}
        schema={agentSchema}
        defaultValues={toFormData(crud.editing)}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="agent"
        className="sm:max-w-[640px]"
      >
        <CrudTextField<AgentFormData>
          name="name"
          id="af-name"
          label="Name"
          required
          testId="agent-name-input"
        />
        <CrudTextField<AgentFormData> name="description" id="af-description" label="Description" />
        <CrudTextField<AgentFormData>
          name="systemPrompt"
          id="af-system-prompt"
          label="System Prompt"
          required
          multiline
          rows={5}
          testId="agent-system-prompt-input"
        />
        <CrudTextField<AgentFormData>
          name="allowedTools"
          id="af-tools"
          label="Allowed tools"
          placeholder="comma-separated, e.g. search, get_record"
          hint="The agent may call only these tools. Leave blank for a text-only agent."
          testId="agent-tools-input"
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <CrudTextField<AgentFormData>
            name="model"
            id="af-model"
            label="Model"
            placeholder="default"
          />
          <CrudTextField<AgentFormData>
            name="maxTokens"
            id="af-max-tokens"
            label="Max tokens"
            type="number"
            placeholder="default"
          />
          <CrudTextField<AgentFormData>
            name="monthlyTokenBudget"
            id="af-budget"
            label="Monthly budget"
            type="number"
            placeholder="tenant quota"
          />
        </div>
        <CrudCheckboxField<AgentFormData>
          name="enabled"
          id="af-enabled"
          label="Enabled"
          testId="agent-enabled-input"
        />
      </CrudFormDialog>

      {runningAgent && <RunPanel agent={runningAgent} onClose={() => setRunningAgent(null)} />}

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete agent"
        message={`Delete “${crud.deleteTarget?.name}”? This cannot be undone.`}
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}
