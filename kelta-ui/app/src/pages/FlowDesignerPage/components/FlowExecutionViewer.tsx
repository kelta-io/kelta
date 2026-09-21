import { useCallback, useMemo } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  BackgroundVariant,
  type Node,
  type Edge,
  type NodeTypes,
  type EdgeTypes,
  type NodeMouseHandler,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import { TaskNode } from './nodes/TaskNode'
import { ChoiceNode } from './nodes/ChoiceNode'
import { TerminalNode } from './nodes/TerminalNode'
import { ControlNode } from './nodes/ControlNode'
import { FlowEdge, FLOW_EDGE_TYPE } from './edges/FlowEdge'

const NODE_TYPES: NodeTypes = {
  task: TaskNode,
  choice: ChoiceNode,
  terminal: TerminalNode,
  control: ControlNode,
}

const EDGE_TYPES: EdgeTypes = {
  [FLOW_EDGE_TYPE]: FlowEdge,
}

interface StepLog {
  stateId: string
  status: string
  durationMs: number | null
  errorMessage: string | null
}

interface FlowExecutionViewerProps {
  nodes: Node[]
  edges: Edge[]
  steps: StepLog[]
  selectedNodeId: string | null
  onNodeSelect: (stateId: string | null) => void
}

function formatDuration(ms: number | null): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}m`
}

/**
 * Read-only flow canvas with execution status overlaid on nodes and edges.
 * Nodes show status badges (green check, red X, blue pulse, gray dash).
 * Edges are colored green for traversed paths, red for error paths, gray for untaken.
 */
export function FlowExecutionViewer({
  nodes: baseNodes,
  edges: baseEdges,
  steps,
  selectedNodeId,
  onNodeSelect,
}: FlowExecutionViewerProps) {
  // Build a map of stateId -> step status/duration
  const stepMap = useMemo(() => {
    const map = new Map<string, StepLog>()
    for (const step of steps) {
      // Keep the last step for each stateId (in case of retries)
      map.set(step.stateId, step)
    }
    return map
  }, [steps])

  // Set of traversed state IDs
  const traversedStates = useMemo(() => new Set(steps.map((s) => s.stateId)), [steps])

  // Decorate nodes with execution status
  const decoratedNodes = useMemo(() => {
    return baseNodes.map((node) => {
      const step = stepMap.get(node.id)
      const isSelected = selectedNodeId === node.id

      let borderColor = 'border-gray-300 dark:border-gray-600' // not reached
      let ringClass = ''

      if (step) {
        switch (step.status) {
          case 'SUCCEEDED':
            borderColor = 'border-emerald-500'
            break
          case 'FAILED':
            borderColor = 'border-red-500'
            break
          case 'RUNNING':
            borderColor = 'border-blue-500 animate-pulse'
            break
          case 'SKIPPED':
            borderColor = 'border-gray-300 opacity-50'
            break
        }
      } else {
        borderColor = 'border-gray-300 opacity-40 dark:border-gray-600'
      }

      if (isSelected) {
        ringClass = 'ring-2 ring-primary ring-offset-1'
      }

      return {
        ...node,
        data: {
          ...node.data,
          debugStatus: step?.status || null,
          debugDuration: step ? formatDuration(step.durationMs) : null,
          debugError: step?.errorMessage || null,
          debugBorderClass: `${borderColor} ${ringClass}`,
        },
        selectable: true,
        draggable: false,
      }
    })
  }, [baseNodes, stepMap, selectedNodeId])

  // Use onNodeClick + onPaneClick instead of onSelectionChange so that
  // programmatic selection (e.g. timeline click) doesn't get immediately
  // reset by React Flow firing onSelectionChange with an empty selection.
  const handleNodeClick: NodeMouseHandler = useCallback(
    (_event, node) => {
      onNodeSelect(node.id)
    },
    [onNodeSelect]
  )

  const handlePaneClick = useCallback(() => {
    onNodeSelect(null)
  }, [onNodeSelect])

  // Decorate edges with traversal status
  const decoratedEdges = useMemo(() => {
    return baseEdges.map((edge) => {
      const sourceTraversed = traversedStates.has(edge.source)
      const targetTraversed = traversedStates.has(edge.target)
      const isTraversed = sourceTraversed && targetTraversed

      // Check if the source step failed
      const sourceStep = stepMap.get(edge.source)
      const isErrorPath = sourceStep?.status === 'FAILED'

      let strokeColor = '#d1d5db' // gray for untaken
      let strokeDasharray = '5,5'

      if (isTraversed) {
        if (isErrorPath) {
          strokeColor = '#ef4444' // red
          strokeDasharray = ''
        } else {
          strokeColor = '#10b981' // green
          strokeDasharray = ''
        }
      }

      return {
        ...edge,
        style: {
          ...edge.style,
          stroke: strokeColor,
          strokeWidth: isTraversed ? 2.5 : 1.5,
          strokeDasharray,
        },
        animated: sourceStep?.status === 'RUNNING',
      }
    })
  }, [baseEdges, traversedStates, stepMap])

  return (
    <div className="flex-1">
      <ReactFlow
        nodes={decoratedNodes}
        edges={decoratedEdges}
        nodeTypes={NODE_TYPES}
        edgeTypes={EDGE_TYPES}
        onNodeClick={handleNodeClick}
        onPaneClick={handlePaneClick}
        fitView
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        className="bg-background"
      >
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
        <Controls className="!border-border !bg-card !shadow-sm" showInteractive={false} />
        <MiniMap
          className="!border-border !bg-card"
          nodeColor={(n) => {
            const status = n.data?.debugStatus
            switch (status) {
              case 'SUCCEEDED':
                return '#10b981'
              case 'FAILED':
                return '#ef4444'
              case 'RUNNING':
                return '#3b82f6'
              default:
                return '#d1d5db'
            }
          }}
        />
      </ReactFlow>
    </div>
  )
}
