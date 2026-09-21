import type { Node, Edge } from '@xyflow/react'
import type {
  FlowDefinition,
  StateConfig,
  ChoiceRuleConfig,
  ChoiceRuleUI,
  ChoiceOperator,
  RetryRule,
  CatchRule,
  RetryConfig,
  CatchConfig,
  WaitMode,
} from './types'
import { CHOICE_OPERATORS } from './types'
import { autoLayout, type LayoutEdge } from './autoLayout'
import { FLOW_EDGE_TYPE } from './components/edges/FlowEdge'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Maps state type to React Flow node type. */
export function getNodeType(stateType: string): string {
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

/** Safely parse a definition JSON string or object. */
export function parseFlowDefinition(definition: unknown): FlowDefinition | null {
  if (!definition) return null
  // Already a parsed object (e.g., from pre-parsed JSON)
  if (typeof definition === 'object' && definition !== null) {
    const obj = definition as Record<string, unknown>
    if (obj.States) return definition as FlowDefinition
    // Handle JSONB wrapper { type: "jsonb", value: "..." }
    if (obj.type === 'jsonb' && typeof obj.value === 'string') {
      try {
        return JSON.parse(obj.value) as FlowDefinition
      } catch {
        return null
      }
    }
    return null
  }
  if (typeof definition !== 'string') return null
  try {
    return JSON.parse(definition) as FlowDefinition
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// Choice Rule Helpers
// ---------------------------------------------------------------------------

/** Detects the comparison operator key from a backend ChoiceRuleConfig. */
function detectOperator(rule: ChoiceRuleConfig): ChoiceOperator | null {
  for (const op of CHOICE_OPERATORS) {
    if (rule[op] !== undefined) {
      return op
    }
  }
  return null
}

/** Extracts the comparison value as a string from a ChoiceRuleConfig. */
function extractRuleValue(rule: ChoiceRuleConfig, operator: ChoiceOperator): string {
  const val = rule[operator]
  if (val === undefined || val === null) return ''
  return String(val)
}

/** Converts a backend ChoiceRuleConfig to a UI-friendly ChoiceRuleUI. */
function choiceRuleToUI(rule: ChoiceRuleConfig): ChoiceRuleUI | null {
  const operator = detectOperator(rule)
  if (!operator) return null
  return {
    variable: rule.Variable || '',
    operator,
    value: extractRuleValue(rule, operator),
    next: rule.Next || '',
  }
}

/** Converts a UI ChoiceRuleUI back to a backend ChoiceRuleConfig. */
function choiceRuleToConfig(rule: ChoiceRuleUI): ChoiceRuleConfig {
  const config: ChoiceRuleConfig = {
    Variable: rule.variable,
    Next: rule.next,
  }

  // Determine the correct typed value
  const op = rule.operator
  const cfg = config as Record<string, unknown>
  if (op.startsWith('Numeric')) {
    cfg[op] = parseFloat(rule.value) || 0
  } else if (op === 'BooleanEquals' || op === 'IsPresent' || op === 'IsNull') {
    cfg[op] = rule.value === 'true'
  } else {
    // String comparisons
    cfg[op] = rule.value
  }

  return config
}

/** Detects the wait mode from a state config. */
function detectWaitMode(state: StateConfig): WaitMode {
  if (state.EventName) return 'eventName'
  if (state.TimestampPath) return 'timestampPath'
  if (state.Timestamp) return 'timestamp'
  return 'seconds'
}

// ---------------------------------------------------------------------------
// Retry / Catch Helpers
// ---------------------------------------------------------------------------

function retryConfigToRule(rc: RetryConfig): RetryRule {
  return {
    errorEquals: rc.ErrorEquals || [],
    intervalSeconds: rc.IntervalSeconds ?? 1,
    maxAttempts: rc.MaxAttempts ?? 3,
    backoffRate: rc.BackoffRate ?? 2.0,
  }
}

function retryRuleToConfig(rule: RetryRule): RetryConfig {
  return {
    ErrorEquals: rule.errorEquals,
    IntervalSeconds: rule.intervalSeconds,
    MaxAttempts: rule.maxAttempts,
    BackoffRate: rule.backoffRate,
  }
}

function catchConfigToRule(cc: CatchConfig): CatchRule {
  return {
    errorEquals: cc.ErrorEquals || [],
    resultPath: cc.ResultPath || '$',
    next: cc.Next,
  }
}

function catchRuleToConfig(rule: CatchRule): CatchConfig {
  return {
    ErrorEquals: rule.errorEquals,
    ResultPath: rule.resultPath,
    Next: rule.next,
  }
}

// ---------------------------------------------------------------------------
// definitionToNodesAndEdges
// ---------------------------------------------------------------------------

/**
 * Converts a FlowDefinition JSON structure into React Flow nodes and edges,
 * populating ALL state fields into node.data for property editing.
 */
export function definitionToNodesAndEdges(definition: FlowDefinition | null): {
  nodes: Node[]
  edges: Edge[]
} {
  if (!definition || !definition.States) {
    return { nodes: [], edges: [] }
  }

  const positions = definition._metadata?.nodePositions || {}
  const hasSavedPositions = Object.keys(positions).length > 0
  const stateIds = Object.keys(definition.States)
  const nodes: Node[] = []
  const edges: Edge[] = []

  stateIds.forEach((stateId, index) => {
    const state = definition.States[stateId]
    // Nodes missing from a partially-saved map keep the legacy column fallback so a
    // user's hand-placed layout is never reflowed under them.
    const pos = positions[stateId] || { x: 250, y: index * 120 }

    // Build node data based on state type
    const nodeData = buildNodeData(stateId, state)

    nodes.push({
      id: stateId,
      type: getNodeType(state.Type),
      position: pos,
      data: nodeData,
    })

    // Build edges from transitions
    buildEdges(stateId, state, edges)
  })

  // No saved positions at all (API/MCP/AI-created flow): open it already laid out.
  if (!hasSavedPositions && nodes.length > 0) {
    const laid = autoLayout(
      nodes.map((n) => ({ id: n.id, type: n.type })),
      [...edges, ...catchLayoutEdges(nodes)],
      { startAt: definition.StartAt }
    )
    for (const node of nodes) {
      const p = laid[node.id]
      if (p) node.position = p
    }
  }

  return { nodes, edges }
}

/**
 * Layout-only edges for `Catch[].Next` targets. Catch handlers are stored in
 * `node.data.catch` (not as canvas edges — `nodesToDefinition` derives `Next`
 * from the edge list), but the layout needs them so error handlers rank below
 * the step that raises them instead of floating as disconnected roots.
 */
export function catchLayoutEdges(nodes: Node[]): LayoutEdge[] {
  const out: LayoutEdge[] = []
  for (const node of nodes) {
    const rules = (node.data as { catch?: CatchRule[] }).catch
    if (!Array.isArray(rules)) continue
    for (const rule of rules) {
      if (rule?.next) out.push({ source: node.id, target: rule.next })
    }
  }
  return out
}

/** Builds the full node.data object for a given state. */
function buildNodeData(stateId: string, state: StateConfig): Record<string, unknown> {
  const base = {
    label: stateId,
    stateType: state.Type,
    comment: state.Comment || '',
  }

  switch (state.Type) {
    case 'Task':
      return {
        ...base,
        resource: state.Resource || '',
        parameters: state.Parameters || undefined,
        inputPath: state.InputPath || '',
        outputPath: state.OutputPath || '',
        resultPath: state.ResultPath || '',
        timeoutSeconds: state.TimeoutSeconds ?? undefined,
        retry: (state.Retry || []).map(retryConfigToRule),
        catch: (state.Catch || []).map(catchConfigToRule),
      }

    case 'Choice':
      return {
        ...base,
        rules: (state.Choices || [])
          .map(choiceRuleToUI)
          .filter((r): r is ChoiceRuleUI => r !== null),
        defaultState: state.Default || '',
        ruleCount: state.Choices?.length || 0,
      }

    case 'Wait':
      return {
        ...base,
        waitMode: detectWaitMode(state),
        seconds: state.Seconds ?? undefined,
        timestamp: state.Timestamp || '',
        timestampPath: state.TimestampPath || '',
        eventName: state.EventName || '',
      }

    case 'Pass':
      return {
        ...base,
        result: state.Result ? JSON.stringify(state.Result, null, 2) : '',
        inputPath: state.InputPath || '',
        outputPath: state.OutputPath || '',
        resultPath: state.ResultPath || '',
      }

    case 'Parallel':
      return {
        ...base,
        branchCount: state.Branches?.length || 0,
        branches: state.Branches, // preserve raw for round-trip
        inputPath: state.InputPath || '',
        outputPath: state.OutputPath || '',
        resultPath: state.ResultPath || '',
        retry: (state.Retry || []).map(retryConfigToRule),
        catch: (state.Catch || []).map(catchConfigToRule),
      }

    case 'Map':
      return {
        ...base,
        itemsPath: state.ItemsPath || '',
        maxConcurrency: state.MaxConcurrency ?? undefined,
        iterator: state.Iterator, // preserve raw for round-trip
        inputPath: state.InputPath || '',
        outputPath: state.OutputPath || '',
        resultPath: state.ResultPath || '',
      }

    case 'Fail':
      return {
        ...base,
        error: state.Error || '',
        cause: state.Cause || '',
      }

    case 'Succeed':
      return base

    default:
      return base
  }
}

/** Builds edges from a state's transitions. */
function buildEdges(stateId: string, state: StateConfig, edges: Edge[]): void {
  // Normal Next transitions
  if (state.Next) {
    edges.push({
      id: `${stateId}->${state.Next}`,
      source: stateId,
      target: state.Next,
      type: FLOW_EDGE_TYPE,
      style: { strokeWidth: 2 },
    })
  }

  // Choice branches
  if (state.Choices) {
    state.Choices.forEach((choice, ci) => {
      if (choice.Next) {
        edges.push({
          id: `${stateId}->choice-${ci}->${choice.Next}`,
          source: stateId,
          target: choice.Next as string,
          type: FLOW_EDGE_TYPE,
          label: `Rule ${ci + 1}`,
          style: { strokeWidth: 2 },
        })
      }
    })
  }
  if (state.Default) {
    edges.push({
      id: `${stateId}->default->${state.Default}`,
      source: stateId,
      target: state.Default,
      type: FLOW_EDGE_TYPE,
      label: 'Default',
      style: { strokeWidth: 2, strokeDasharray: '5,5' },
    })
  }
}

// ---------------------------------------------------------------------------
// nodesToDefinition
// ---------------------------------------------------------------------------

/**
 * Converts React Flow nodes and edges back into a FlowDefinition JSON,
 * serializing ALL node.data fields into the appropriate definition format.
 */
export function nodesToDefinition(
  nodes: Node[],
  edges: Edge[],
  existing: FlowDefinition | null
): FlowDefinition {
  if (nodes.length === 0) {
    return existing || { StartAt: '', States: {} }
  }

  const states: Record<string, StateConfig> = {}
  const positions: Record<string, { x: number; y: number }> = {}

  // Build edge map: source -> target edges
  const edgeMap = new Map<string, Edge[]>()
  for (const edge of edges) {
    const list = edgeMap.get(edge.source) || []
    list.push(edge)
    edgeMap.set(edge.source, list)
  }

  for (const node of nodes) {
    const data = node.data as Record<string, unknown>
    const stateType = (data.stateType as string) || 'Task'
    positions[node.id] = { x: node.position.x, y: node.position.y }

    const outEdges = edgeMap.get(node.id) || []
    states[node.id] = buildStateConfig(stateType, data, outEdges)
  }

  // Determine StartAt: preserve existing or use topmost node
  const topNode = [...nodes].sort((a, b) => a.position.y - b.position.y)[0]
  const startAt =
    existing?.StartAt && states[existing.StartAt] ? existing.StartAt : topNode?.id || ''

  return {
    Comment: existing?.Comment,
    StartAt: startAt,
    States: states,
    _metadata: { nodePositions: positions },
  }
}

/** Builds a StateConfig from node data and outgoing edges. */
function buildStateConfig(
  stateType: string,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  const state: StateConfig = { Type: stateType }

  // Comment
  if (data.comment) state.Comment = data.comment as string

  switch (stateType) {
    case 'Task':
      return buildTaskConfig(state, data, outEdges)
    case 'Choice':
      return buildChoiceConfig(state, data, outEdges)
    case 'Wait':
      return buildWaitConfig(state, data, outEdges)
    case 'Pass':
      return buildPassConfig(state, data, outEdges)
    case 'Parallel':
      return buildParallelConfig(state, data, outEdges)
    case 'Map':
      return buildMapConfig(state, data, outEdges)
    case 'Fail':
      return buildFailConfig(state, data)
    case 'Succeed':
      state.End = true
      return state
    default: {
      const next = outEdges[0]
      if (next) state.Next = next.target
      else state.End = true
      return state
    }
  }
}

function buildTaskConfig(
  state: StateConfig,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  if (data.resource) state.Resource = data.resource as string
  const params = data.parameters as Record<string, unknown> | undefined
  if (params && Object.keys(params).length > 0) state.Parameters = params
  if (data.inputPath) state.InputPath = data.inputPath as string
  if (data.outputPath) state.OutputPath = data.outputPath as string
  if (data.resultPath) state.ResultPath = data.resultPath as string
  if (data.timeoutSeconds) state.TimeoutSeconds = data.timeoutSeconds as number

  // Retry policies
  const retryRules = data.retry as RetryRule[] | undefined
  if (retryRules && retryRules.length > 0) {
    state.Retry = retryRules.map(retryRuleToConfig)
  }

  // Catch policies
  const catchRules = data.catch as CatchRule[] | undefined
  if (catchRules && catchRules.length > 0) {
    state.Catch = catchRules.map(catchRuleToConfig)
  }

  // Transition
  const next = outEdges.find((e) => !e.label)
  if (next) state.Next = next.target
  else if (outEdges.length === 0) state.End = true

  return state
}

function buildChoiceConfig(
  state: StateConfig,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  // Serialize rules from UI representation
  const rules = data.rules as ChoiceRuleUI[] | undefined
  if (rules && rules.length > 0) {
    state.Choices = rules.map(choiceRuleToConfig)
  } else {
    // Fallback: build from edges (backward compatible)
    const defaultEdge = outEdges.find((e) => e.label === 'Default')
    const ruleEdges = outEdges.filter((e) => e.label !== 'Default')
    state.Choices = ruleEdges.map((e) => ({ Next: e.target }))
    if (defaultEdge) state.Default = defaultEdge.target
  }

  if (data.defaultState) state.Default = data.defaultState as string

  return state
}

function buildWaitConfig(
  state: StateConfig,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  const mode = (data.waitMode as WaitMode) || 'seconds'

  switch (mode) {
    case 'seconds':
      if (data.seconds != null) state.Seconds = data.seconds as number
      break
    case 'timestamp':
      if (data.timestamp) state.Timestamp = data.timestamp as string
      break
    case 'timestampPath':
      if (data.timestampPath) state.TimestampPath = data.timestampPath as string
      break
    case 'eventName':
      if (data.eventName) state.EventName = data.eventName as string
      break
  }

  const next = outEdges[0]
  if (next) state.Next = next.target
  else state.End = true

  return state
}

function buildPassConfig(
  state: StateConfig,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  if (data.inputPath) state.InputPath = data.inputPath as string
  if (data.outputPath) state.OutputPath = data.outputPath as string
  if (data.resultPath) state.ResultPath = data.resultPath as string

  // Parse Result JSON string back to object
  const resultStr = data.result as string | undefined
  if (resultStr) {
    try {
      state.Result = JSON.parse(resultStr)
    } catch {
      // If invalid JSON, skip
    }
  }

  const next = outEdges[0]
  if (next) state.Next = next.target
  else state.End = true

  return state
}

function buildParallelConfig(
  state: StateConfig,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  if (data.inputPath) state.InputPath = data.inputPath as string
  if (data.outputPath) state.OutputPath = data.outputPath as string
  if (data.resultPath) state.ResultPath = data.resultPath as string

  // Preserve raw branches from round-trip
  if (data.branches) state.Branches = data.branches as FlowDefinition[]

  // Retry policies
  const retryRules = data.retry as RetryRule[] | undefined
  if (retryRules && retryRules.length > 0) {
    state.Retry = retryRules.map(retryRuleToConfig)
  }

  // Catch policies
  const catchRules = data.catch as CatchRule[] | undefined
  if (catchRules && catchRules.length > 0) {
    state.Catch = catchRules.map(catchRuleToConfig)
  }

  const next = outEdges[0]
  if (next) state.Next = next.target
  else state.End = true

  return state
}

function buildMapConfig(
  state: StateConfig,
  data: Record<string, unknown>,
  outEdges: Edge[]
): StateConfig {
  if (data.itemsPath) state.ItemsPath = data.itemsPath as string
  if (data.maxConcurrency) state.MaxConcurrency = data.maxConcurrency as number
  if (data.inputPath) state.InputPath = data.inputPath as string
  if (data.outputPath) state.OutputPath = data.outputPath as string
  if (data.resultPath) state.ResultPath = data.resultPath as string

  // Preserve raw iterator from round-trip
  if (data.iterator) state.Iterator = data.iterator as FlowDefinition

  const next = outEdges[0]
  if (next) state.Next = next.target
  else state.End = true

  return state
}

function buildFailConfig(state: StateConfig, data: Record<string, unknown>): StateConfig {
  state.End = true
  if (data.error) state.Error = data.error as string
  if (data.cause) state.Cause = data.cause as string
  return state
}
