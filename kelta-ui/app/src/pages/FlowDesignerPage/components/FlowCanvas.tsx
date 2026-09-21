import React, { useCallback, useRef, useMemo, useEffect } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  useReactFlow,
  type OnConnect,
  type Node,
  type Edge,
  type NodeTypes,
  type EdgeTypes,
  type Connection,
  BackgroundVariant,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import { autoLayout } from '../autoLayout'
import { catchLayoutEdges } from '../definitionConverter'
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

interface FlowCanvasProps {
  initialNodes: Node[]
  initialEdges: Edge[]
  onNodesChange?: (nodes: Node[]) => void
  onEdgesChange?: (edges: Edge[]) => void
  onNodeSelect?: (node: Node | null) => void
  /**
   * Monotonic counter — every increment re-runs auto layout over the live
   * canvas state (the canvas owns node positions, so the parent can't set them
   * directly). 0 / undefined means "never requested".
   */
  layoutRequest?: number
  /** Root state of the definition; ranked at the top by auto layout. */
  startAt?: string
}

let nodeIdCounter = 0

/**
 * Extract only the definition-relevant fields from a node, ignoring ephemeral
 * React Flow state (selected, dragging, measured, width, height, resizing, etc.)
 * that should NOT mark the flow as dirty.
 */
function nodeFingerprint(node: Node): string {
  return JSON.stringify({
    id: node.id,
    type: node.type,
    position: node.position,
    data: node.data,
  })
}

function edgeFingerprint(edge: Edge): string {
  return JSON.stringify({
    id: edge.id,
    source: edge.source,
    sourceHandle: edge.sourceHandle,
    target: edge.target,
    targetHandle: edge.targetHandle,
    label: edge.label,
    data: edge.data,
  })
}

/** Compare two node/edge arrays using only definition-relevant fields. */
function hasDefinitionChanged<T>(prev: T[], next: T[], fingerprint: (item: T) => string): boolean {
  if (prev.length !== next.length) return true
  for (let i = 0; i < prev.length; i++) {
    if (fingerprint(prev[i]) !== fingerprint(next[i])) return true
  }
  return false
}

function getNodeType(stateType: string): string {
  switch (stateType) {
    case 'Task':
      return 'task'
    case 'Choice':
      return 'choice'
    case 'Succeed':
    case 'Fail':
      return 'terminal'
    case 'Wait':
    case 'Pass':
    case 'Parallel':
    case 'Map':
      return 'control'
    default:
      return 'task'
  }
}

function FlowCanvasInner({
  initialNodes,
  initialEdges,
  onNodesChange: onNodesChangeProp,
  onEdgesChange: onEdgesChangeProp,
  onNodeSelect,
  layoutRequest = 0,
  startAt,
}: FlowCanvasProps) {
  const reactFlowWrapper = useRef<HTMLDivElement>(null)
  const reactFlowInstance = useReactFlow()
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)

  // Track whether React Flow has fully initialized (including fitView layout).
  // We suppress dirty-state propagation until init is complete so that the
  // automatic fitView position adjustments don't falsely mark the flow dirty.
  const readyRef = useRef(false)

  const handleInit = useCallback(() => {
    // fitView runs as part of init. Use rAF to let the post-init state settle
    // before we start tracking user changes.
    requestAnimationFrame(() => {
      readyRef.current = true
    })
  }, [])

  // Track last-propagated state so we can skip ephemeral-only changes
  // (e.g. selection, dragging) that don't affect the flow definition.
  const prevNodesRef = useRef<Node[]>(initialNodes)
  const prevEdgesRef = useRef<Edge[]>(initialEdges)

  // Sync node state to parent only when definition-relevant fields change
  // (position, data, type, count). Ephemeral fields like selected/dragging
  // are ignored so they don't falsely mark the flow dirty.
  useEffect(() => {
    if (!readyRef.current) return
    if (hasDefinitionChanged(prevNodesRef.current, nodes, nodeFingerprint)) {
      prevNodesRef.current = nodes
      onNodesChangeProp?.(nodes)
    }
  }, [nodes, onNodesChangeProp])

  useEffect(() => {
    if (!readyRef.current) return
    if (hasDefinitionChanged(prevEdgesRef.current, edges, edgeFingerprint)) {
      prevEdgesRef.current = edges
      onEdgesChangeProp?.(edges)
    }
  }, [edges, onEdgesChangeProp])

  // Auto layout: recompute positions from the current graph, then refit the view.
  // Runs only when the counter advances — never on mount — so opening a flow
  // does not disturb a saved layout.
  const lastLayoutRef = useRef(layoutRequest)
  useEffect(() => {
    if (layoutRequest === lastLayoutRef.current) return
    lastLayoutRef.current = layoutRequest
    setNodes((nds) => {
      const positions = autoLayout(
        nds.map((n) => ({
          id: n.id,
          type: n.type,
          width: n.measured?.width,
          height: n.measured?.height,
        })),
        [...edges, ...catchLayoutEdges(nds)],
        { startAt }
      )
      return nds.map((n) => {
        const p = positions[n.id]
        if (!p || (p.x === n.position.x && p.y === n.position.y)) return n
        return { ...n, position: p }
      })
    })
    requestAnimationFrame(() => {
      void reactFlowInstance.fitView({ padding: 0.2, duration: 300 })
    })
  }, [layoutRequest, edges, startAt, setNodes, reactFlowInstance])

  const onConnect: OnConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds) => addEdge({ ...connection, animated: false, style: { strokeWidth: 2 } }, eds))
    },
    [setEdges]
  )

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [])

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault()
      const stateType = event.dataTransfer.getData('application/reactflow-type')
      if (!stateType) return

      // Convert screen coordinates to flow coordinates (accounts for zoom/pan)
      const position = reactFlowInstance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })
      // Offset to center node under the cursor
      position.x -= 80
      position.y -= 20

      const id = `${stateType.toLowerCase()}_${++nodeIdCounter}`
      const newNode: Node = {
        id,
        type: getNodeType(stateType),
        position,
        data: {
          label: `${stateType} ${nodeIdCounter}`,
          stateType,
        },
      }

      setNodes((nds) => [...nds, newNode])
    },
    [setNodes, reactFlowInstance]
  )

  const onSelectionChange = useCallback(
    ({ nodes: selectedNodes }: { nodes: Node[] }) => {
      onNodeSelect?.(selectedNodes.length === 1 ? selectedNodes[0] : null)
    },
    [onNodeSelect]
  )

  const defaultEdgeOptions = useMemo(
    () => ({
      style: { strokeWidth: 2, stroke: 'var(--color-border)' },
      type: FLOW_EDGE_TYPE,
    }),
    []
  )

  return (
    <div ref={reactFlowWrapper} className="flex-1">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onDragOver={onDragOver}
        onDrop={onDrop}
        onInit={handleInit}
        onSelectionChange={onSelectionChange}
        nodeTypes={NODE_TYPES}
        edgeTypes={EDGE_TYPES}
        defaultEdgeOptions={defaultEdgeOptions}
        fitView
        snapToGrid
        snapGrid={[16, 16]}
        deleteKeyCode={['Backspace', 'Delete']}
        className="bg-background"
      >
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
        <Controls className="!bg-card !border-border !shadow-sm" />
        <MiniMap
          className="!bg-card !border-border"
          nodeColor={(n) => {
            switch (n.type) {
              case 'task':
                return '#93c5fd'
              case 'choice':
                return '#fcd34d'
              case 'terminal':
                return n.data?.stateType === 'Succeed' ? '#86efac' : '#fca5a5'
              case 'control':
                return '#c4b5fd'
              default:
                return '#d1d5db'
            }
          }}
        />
      </ReactFlow>
    </div>
  )
}

// Wrapper that ensures useReactFlow is called inside ReactFlowProvider context.
// The ReactFlowProvider is in FlowDesignerPage, so this component is always
// rendered within that provider. We export this directly.
export function FlowCanvas(props: FlowCanvasProps) {
  return <FlowCanvasInner {...props} />
}
