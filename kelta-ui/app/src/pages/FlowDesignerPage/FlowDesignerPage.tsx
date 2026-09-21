import { useState, useCallback, useMemo } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ReactFlowProvider } from '@xyflow/react'
import type { Node, Edge } from '@xyflow/react'

import { useApi } from '../../context/ApiContext'
import type { Flow } from './types'
import { unwrapResource } from '../../utils/jsonapi'
import { useToast, LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import { FlowToolbar } from './components/FlowToolbar'
import { StepsPalette } from './components/StepsPalette'
import { FlowCanvas } from './components/FlowCanvas'
import { PropertiesPanel } from './components/PropertiesPanel'
import { ExecutionsTab } from './components/ExecutionsTab'
import { DebugTab } from './components/DebugTab'
import { Pencil, List, Bug, X } from 'lucide-react'
import {
  parseFlowDefinition,
  definitionToNodesAndEdges,
  nodesToDefinition,
} from './definitionConverter'
import { TriggerEditSheet } from './components/TriggerEditSheet'
import { TestFlowDialog } from './components/TestFlowDialog'
import { GenerateFlowDialog } from './components/GenerateFlowDialog'

type ActiveTab = 'design' | 'executions' | { type: 'debug'; executionId: string }

export function FlowDesignerPage() {
  const { flowId } = useParams<{ flowId: string }>()
  const [searchParams] = useSearchParams()
  const { apiClient, keltaClient } = useApi()
  const { showToast } = useToast()
  const queryClient = useQueryClient()

  // Tab state
  const initialExecutionId = searchParams.get('executionId')
  const [activeTab, setActiveTab] = useState<ActiveTab>(
    initialExecutionId ? { type: 'debug', executionId: initialExecutionId } : 'design'
  )
  const [debugTabs, setDebugTabs] = useState<string[]>(
    initialExecutionId ? [initialExecutionId] : []
  )

  const [selectedNode, setSelectedNode] = useState<Node | null>(null)
  const [paletteCollapsed, setPaletteCollapsed] = useState(false)
  const [propertiesCollapsed, setPropertiesCollapsed] = useState(false)
  const [showJson, setShowJson] = useState(false)
  const [isDirty, setIsDirty] = useState(false)
  const [currentNodes, setCurrentNodes] = useState<Node[]>([])
  const [currentEdges, setCurrentEdges] = useState<Edge[]>([])
  const [triggerSheetOpen, setTriggerSheetOpen] = useState(false)
  const [testDialogOpen, setTestDialogOpen] = useState(false)
  const [generateDialogOpen, setGenerateDialogOpen] = useState(false)
  const [isGenerating, setIsGenerating] = useState(false)
  const [layoutRequest, setLayoutRequest] = useState(0)

  const {
    data: flow,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['flow', flowId],
    queryFn: () => keltaClient.admin.flows.get(flowId!),
    enabled: !!flowId,
  })

  const parsedDefinition = useMemo(
    () => parseFlowDefinition(flow?.definition ?? null),
    [flow?.definition]
  )

  const { nodes: initialNodes, edges: initialEdges } = useMemo(
    () => definitionToNodesAndEdges(parsedDefinition),
    [parsedDefinition]
  )

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!flowId || !flow) return
      const definition = nodesToDefinition(
        currentNodes.length > 0 ? currentNodes : initialNodes,
        currentEdges.length > 0 ? currentEdges : initialEdges,
        parsedDefinition
      )
      // Only send mutable fields; JSON-type fields must be objects (not strings)
      await keltaClient.admin.flows.update(flowId, {
        name: flow.name,
        description: flow.description,
        flowType: flow.flowType,
        active: flow.active,
        definition,
        triggerConfig: flow.triggerConfig
          ? typeof flow.triggerConfig === 'string'
            ? JSON.parse(flow.triggerConfig)
            : flow.triggerConfig
          : null,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['flow', flowId] })
      showToast('Flow saved successfully', 'success')
      setIsDirty(false)
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to save flow', 'error')
    },
  })

  // Compute all node IDs for dropdown menus in the properties panel
  const allNodeIds = useMemo(() => {
    const nodes = currentNodes.length > 0 ? currentNodes : initialNodes
    return nodes.map((n) => n.id)
  }, [currentNodes, initialNodes])

  const handleNodesChange = useCallback((nodes: Node[]) => {
    setCurrentNodes(nodes)
    setIsDirty(true)
  }, [])

  const handleEdgesChange = useCallback((edges: Edge[]) => {
    setCurrentEdges(edges)
    setIsDirty(true)
  }, [])

  const handleNodeSelect = useCallback(
    (node: Node | null) => {
      setSelectedNode(node)
      if (node && propertiesCollapsed) {
        setPropertiesCollapsed(false)
      }
    },
    [propertiesCollapsed]
  )

  const handleNodeUpdate = useCallback((nodeId: string, data: Record<string, unknown>) => {
    setCurrentNodes((prev) =>
      prev.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...data } } : n))
    )
    // Keep selectedNode in sync so the properties panel reflects changes
    setSelectedNode((prev) => {
      if (prev && prev.id === nodeId) {
        return { ...prev, data: { ...prev.data, ...data } }
      }
      return prev
    })
    setIsDirty(true)
  }, [])

  const testMutation = useMutation({
    mutationFn: async (state: Record<string, unknown>) => {
      if (!flowId) return
      const resp = await apiClient.fetch(`/api/flows/${flowId}/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ state, test: true }),
      })
      if (!resp.ok) throw new Error('Failed to start test execution')
      // JSON:API envelope ({ data: { type: 'flow-executions', id, attributes } }) — id is `data.id`.
      return unwrapResource<{ id: string }>(await resp.json())
    },
    onSuccess: (data) => {
      if (data?.id) {
        showToast('Test execution started', 'success')
        setTestDialogOpen(false)
        handleViewExecution(data.id)
      }
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to start test', 'error')
    },
  })

  const publishMutation = useMutation({
    mutationFn: async () => {
      if (!flowId) return
      const resp = await apiClient.fetch(`/api/flows/${flowId}/publish`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      })
      if (!resp.ok) throw new Error('Failed to publish flow')
      return (await resp.json()) as { versionNumber: number }
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['flow', flowId] })
      showToast(`Published as v${data?.versionNumber}`, 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to publish', 'error')
    },
  })

  const handleViewExecution = useCallback((executionId: string) => {
    setDebugTabs((prev) => (prev.includes(executionId) ? prev : [...prev, executionId]))
    setActiveTab({ type: 'debug', executionId })
  }, [])

  const handleCloseDebugTab = useCallback(
    (executionId: string) => {
      setDebugTabs((prev) => prev.filter((id) => id !== executionId))
      // If closing the active tab, switch to executions
      if (
        typeof activeTab === 'object' &&
        activeTab.type === 'debug' &&
        activeTab.executionId === executionId
      ) {
        setActiveTab('executions')
      }
    },
    [activeTab]
  )

  const handleGenerate = useCallback(
    async (description: string) => {
      setIsGenerating(true)
      try {
        const resp = await apiClient.fetch('/api/ai/flows/generate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ description }),
        })
        if (!resp.ok) {
          const err = await resp.json().catch(() => ({}))
          throw new Error((err as Record<string, string>).error ?? 'Generation failed')
        }
        const data = (await resp.json()) as { definition: unknown }
        const parsed = parseFlowDefinition(data.definition)
        if (!parsed) throw new Error('Invalid flow definition returned')
        const { nodes, edges } = definitionToNodesAndEdges(parsed)
        setCurrentNodes(nodes)
        setCurrentEdges(edges)
        setIsDirty(true)
        setGenerateDialogOpen(false)
        showToast('Flow generated — review and save when ready', 'success')
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Generation failed', 'error')
      } finally {
        setIsGenerating(false)
      }
    },
    [apiClient, showToast]
  )

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <LoadingSpinner size="large" label="Loading flow..." />
      </div>
    )
  }

  if (error || !flow) {
    return (
      <div className="flex h-screen items-center justify-center p-8">
        <ErrorMessage
          error={error instanceof Error ? error : new Error('Flow not found')}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  const isDesignTab = activeTab === 'design'
  const isExecutionsTab = activeTab === 'executions'

  return (
    <div className="flex h-screen flex-col">
      {/* Toolbar — only show save/json for design tab */}
      <FlowToolbar
        flowName={flow.name}
        flowType={flow.flowType}
        isActive={flow.active}
        isDirty={isDirty}
        isSaving={saveMutation.isPending}
        showJson={showJson}
        publishedVersion={(flow as unknown as { publishedVersion?: number }).publishedVersion}
        currentVersion={flow.version}
        onSave={() => saveMutation.mutate()}
        onToggleJson={() => setShowJson((v) => !v)}
        onTest={() => setTestDialogOpen(true)}
        onPublish={() => publishMutation.mutate()}
        onGenerate={() => setGenerateDialogOpen(true)}
        onAutoLayout={() => setLayoutRequest((v) => v + 1)}
      />

      {/* Tab Navigation */}
      <div className="flex shrink-0 items-center gap-0 border-b border-border bg-card px-2">
        <button
          className={cn(
            'flex items-center gap-1.5 border-b-2 px-4 py-2 text-xs font-medium transition-colors',
            isDesignTab
              ? 'border-primary text-primary'
              : 'border-transparent text-muted-foreground hover:text-foreground'
          )}
          onClick={() => setActiveTab('design')}
        >
          <Pencil className="h-3 w-3" />
          Design
        </button>
        <button
          className={cn(
            'flex items-center gap-1.5 border-b-2 px-4 py-2 text-xs font-medium transition-colors',
            isExecutionsTab
              ? 'border-primary text-primary'
              : 'border-transparent text-muted-foreground hover:text-foreground'
          )}
          onClick={() => setActiveTab('executions')}
        >
          <List className="h-3 w-3" />
          Executions
        </button>
        {debugTabs.map((execId) => {
          const isActive =
            typeof activeTab === 'object' &&
            activeTab.type === 'debug' &&
            activeTab.executionId === execId
          return (
            <div
              key={execId}
              className={cn(
                'flex items-center gap-1 border-b-2 px-3 py-2',
                isActive
                  ? 'border-primary text-primary'
                  : 'border-transparent text-muted-foreground'
              )}
            >
              <button
                className="flex items-center gap-1.5 text-xs font-medium transition-colors hover:text-foreground"
                onClick={() => setActiveTab({ type: 'debug', executionId: execId })}
              >
                <Bug className="h-3 w-3" />
                Debug: {execId.slice(0, 8)}
              </button>
              <button
                className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                onClick={(e) => {
                  e.stopPropagation()
                  handleCloseDebugTab(execId)
                }}
                aria-label="Close debug tab"
              >
                <X className="h-3 w-3" />
              </button>
            </div>
          )
        })}
      </div>

      {/* Tab Content — Design tab kept mounted (hidden) to preserve canvas state */}
      <div className={cn('flex flex-1 overflow-hidden', !isDesignTab && 'hidden')}>
        <StepsPalette
          collapsed={paletteCollapsed}
          onToggle={() => setPaletteCollapsed((v) => !v)}
        />

        <ReactFlowProvider>
          {/* JSON overlay — canvas stays mounted underneath */}
          {showJson && (
            <div className="flex-1 overflow-auto bg-background p-4">
              <pre className="whitespace-pre-wrap rounded-lg border border-border bg-muted p-4 font-mono text-xs text-foreground">
                {JSON.stringify(
                  nodesToDefinition(
                    currentNodes.length > 0 ? currentNodes : initialNodes,
                    currentEdges.length > 0 ? currentEdges : initialEdges,
                    parsedDefinition
                  ),
                  null,
                  2
                )}
              </pre>
            </div>
          )}
          <div className={cn('flex flex-1 flex-col', showJson && 'hidden')}>
            <FlowCanvas
              initialNodes={initialNodes}
              initialEdges={initialEdges}
              onNodesChange={handleNodesChange}
              onEdgesChange={handleEdgesChange}
              onNodeSelect={handleNodeSelect}
              layoutRequest={layoutRequest}
              startAt={parsedDefinition?.StartAt}
            />
          </div>
        </ReactFlowProvider>

        <PropertiesPanel
          selectedNode={selectedNode}
          collapsed={propertiesCollapsed}
          onToggle={() => setPropertiesCollapsed((v) => !v)}
          onNodeUpdate={handleNodeUpdate}
          flow={flow as unknown as Flow}
          allNodeIds={allNodeIds}
          onEditTrigger={() => setTriggerSheetOpen(true)}
        />
      </div>

      {isExecutionsTab && flowId && (
        <ExecutionsTab flowId={flowId} onViewExecution={handleViewExecution} />
      )}

      {typeof activeTab === 'object' && activeTab.type === 'debug' && (
        <DebugTab
          key={activeTab.executionId}
          executionId={activeTab.executionId}
          nodes={initialNodes}
          edges={initialEdges}
        />
      )}

      <TriggerEditSheet
        open={triggerSheetOpen}
        onOpenChange={setTriggerSheetOpen}
        flow={flow as unknown as Flow}
      />

      <GenerateFlowDialog
        open={generateDialogOpen}
        isLoading={isGenerating}
        onOpenChange={setGenerateDialogOpen}
        onGenerate={handleGenerate}
      />

      <TestFlowDialog
        open={testDialogOpen}
        onOpenChange={setTestDialogOpen}
        flowType={flow.flowType}
        onSubmit={(state) => testMutation.mutate(state)}
        isLoading={testMutation.isPending}
      />
    </div>
  )
}

export default FlowDesignerPage
