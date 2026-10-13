// ---------------------------------------------------------------------------
// Flow Types — shared type definitions for the flow designer
// ---------------------------------------------------------------------------

/** Supported flow trigger types. */
export type FlowType = 'RECORD_TRIGGERED' | 'SCHEDULED' | 'AUTOLAUNCHED' | 'NATS_TRIGGERED'

// ---------------------------------------------------------------------------
// Trigger Configuration
// ---------------------------------------------------------------------------

export interface RecordTriggerConfig {
  collection: string
  events: ('CREATED' | 'UPDATED' | 'DELETED')[]
  triggerFields?: string[]
  filterFormula?: string
}

export interface ScheduledTriggerConfig {
  cron: string
  timezone?: string
}

export interface AutolaunchedTriggerConfig {
  webhookPath?: string
  authentication?: 'NONE' | 'API_KEY' | 'WEBHOOK_SECRET'
}

export interface NatsTriggerConfig {
  /** Names the platform trigger subject `kelta.trigger.<tenantId>.<topic>`. */
  topic: string
}

export type TriggerConfig =
  | RecordTriggerConfig
  | ScheduledTriggerConfig
  | AutolaunchedTriggerConfig
  | NatsTriggerConfig

// ---------------------------------------------------------------------------
// Retry & Catch Rules
// ---------------------------------------------------------------------------

export interface RetryRule {
  errorEquals: string[]
  intervalSeconds: number
  maxAttempts: number
  backoffRate: number
}

export interface CatchRule {
  errorEquals: string[]
  resultPath: string
  next: string
}

// ---------------------------------------------------------------------------
// Choice Rule (UI representation)
// ---------------------------------------------------------------------------

/** All supported comparison operators in the definition JSON. */
export const CHOICE_OPERATORS = [
  'StringEquals',
  'StringNotEquals',
  'StringGreaterThan',
  'StringLessThan',
  'StringGreaterThanEquals',
  'StringLessThanEquals',
  'StringMatches',
  'NumericEquals',
  'NumericNotEquals',
  'NumericGreaterThan',
  'NumericLessThan',
  'NumericGreaterThanEquals',
  'NumericLessThanEquals',
  'BooleanEquals',
  'IsPresent',
  'IsNull',
] as const

export type ChoiceOperator = (typeof CHOICE_OPERATORS)[number]

export interface ChoiceRuleUI {
  variable: string
  operator: ChoiceOperator
  value: string
  next: string
}

// ---------------------------------------------------------------------------
// Wait mode
// ---------------------------------------------------------------------------

export type WaitMode = 'seconds' | 'timestamp' | 'timestampPath' | 'eventName'

// ---------------------------------------------------------------------------
// Node Data per State Type
//
// These interfaces describe the data stored in node.data for each state type
// on the React Flow canvas. The definitionConverter round-trips between these
// and the JSON definition format.
// ---------------------------------------------------------------------------

/** Common fields present on every node. */
export interface BaseNodeData {
  label: string
  stateType: string
  comment?: string
}

export interface TaskNodeData extends BaseNodeData {
  stateType: 'Task'
  resource: string
  parameters?: Record<string, unknown>
  inputPath?: string
  outputPath?: string
  resultPath?: string
  timeoutSeconds?: number
  retry: RetryRule[]
  catch: CatchRule[]
}

export interface ChoiceNodeData extends BaseNodeData {
  stateType: 'Choice'
  rules: ChoiceRuleUI[]
  defaultState?: string
}

export interface WaitNodeData extends BaseNodeData {
  stateType: 'Wait'
  waitMode: WaitMode
  seconds?: number
  timestamp?: string
  timestampPath?: string
  eventName?: string
}

export interface PassNodeData extends BaseNodeData {
  stateType: 'Pass'
  result?: string // JSON string
  inputPath?: string
  outputPath?: string
  resultPath?: string
}

export interface ParallelNodeData extends BaseNodeData {
  stateType: 'Parallel'
  branchCount: number
  branches?: unknown[] // raw branch definitions preserved for round-trip
  inputPath?: string
  outputPath?: string
  resultPath?: string
  retry: RetryRule[]
  catch: CatchRule[]
}

export interface MapNodeData extends BaseNodeData {
  stateType: 'Map'
  itemsPath?: string
  maxConcurrency?: number
  iterator?: unknown // raw iterator definition preserved for round-trip
  inputPath?: string
  outputPath?: string
  resultPath?: string
}

export interface FailNodeData extends BaseNodeData {
  stateType: 'Fail'
  error?: string
  cause?: string
}

export interface SucceedNodeData extends BaseNodeData {
  stateType: 'Succeed'
}

export type FlowNodeData =
  | TaskNodeData
  | ChoiceNodeData
  | WaitNodeData
  | PassNodeData
  | ParallelNodeData
  | MapNodeData
  | FailNodeData
  | SucceedNodeData

// ---------------------------------------------------------------------------
// Definition JSON interfaces (matching backend FlowDefinitionParser format)
// ---------------------------------------------------------------------------

export interface FlowDefinition {
  Comment?: string
  StartAt: string
  States: Record<string, StateConfig>
  _metadata?: {
    nodePositions?: Record<string, { x: number; y: number }>
  }
}

export interface StateConfig {
  Type: string
  Comment?: string
  Name?: string
  Next?: string
  End?: boolean
  Resource?: string
  Parameters?: Record<string, unknown>
  InputPath?: string
  OutputPath?: string
  ResultPath?: string
  TimeoutSeconds?: number
  Retry?: RetryConfig[]
  Catch?: CatchConfig[]
  Choices?: ChoiceRuleConfig[]
  Default?: string
  Seconds?: number
  Timestamp?: string
  TimestampPath?: string
  EventName?: string
  Result?: Record<string, unknown>
  ItemsPath?: string
  Iterator?: FlowDefinition
  MaxConcurrency?: number
  Branches?: FlowDefinition[]
  Error?: string
  Cause?: string
  [key: string]: unknown
}

export interface RetryConfig {
  ErrorEquals: string[]
  IntervalSeconds?: number
  MaxAttempts?: number
  BackoffRate?: number
}

export interface CatchConfig {
  ErrorEquals: string[]
  ResultPath?: string
  Next: string
}

export interface ChoiceRuleConfig {
  Variable?: string
  Next?: string
  // Comparison operator — exactly one will be present
  StringEquals?: string
  StringNotEquals?: string
  StringGreaterThan?: string
  StringLessThan?: string
  StringGreaterThanEquals?: string
  StringLessThanEquals?: string
  StringMatches?: string
  NumericEquals?: number
  NumericNotEquals?: number
  NumericGreaterThan?: number
  NumericLessThan?: number
  NumericGreaterThanEquals?: number
  NumericLessThanEquals?: number
  BooleanEquals?: boolean
  IsPresent?: boolean
  IsNull?: boolean
  // Compound rules
  And?: ChoiceRuleConfig[]
  Or?: ChoiceRuleConfig[]
  Not?: ChoiceRuleConfig
  [key: string]: unknown
}

// ---------------------------------------------------------------------------
// Flow API response
// ---------------------------------------------------------------------------

export const STEP_LOGGING_LEVELS = ['FULL', 'ERRORS_ONLY', 'NONE'] as const
export type StepLogging = (typeof STEP_LOGGING_LEVELS)[number]

export interface Flow {
  id: string
  name: string
  description: string | null
  flowType: string
  active: boolean
  definition: string | null
  triggerConfig: string | null
  /** Audit identity for userless runs (cron/NATS/webhook); null = flow owner. */
  runAsUserId?: string | null
  /** Which step-log rows runs write; absent on older payloads means FULL. */
  stepLogging?: StepLogging
  createdBy: string
  createdAt: string
  updatedAt: string
  version: number | null
  publishedVersion: number | null
}

// ---------------------------------------------------------------------------
// Resource options for Task state
// ---------------------------------------------------------------------------

export interface ResourceGroup {
  label: string
  options: { value: string; label: string }[]
}

export const RESOURCE_GROUPS: ResourceGroup[] = [
  {
    label: 'Data',
    options: [
      { value: 'FIELD_UPDATE', label: 'Field Update' },
      { value: 'CREATE_RECORD', label: 'Create Record' },
      { value: 'UPDATE_RECORD', label: 'Update Record' },
      { value: 'DELETE_RECORD', label: 'Delete Record' },
      { value: 'QUERY_RECORDS', label: 'Query Records' },
      { value: 'SQL_QUERY', label: 'SQL Query' },
    ],
  },
  {
    label: 'Communication',
    options: [
      { value: 'EMAIL_ALERT', label: 'Email Alert' },
      { value: 'SEND_NOTIFICATION', label: 'Send Notification' },
      { value: 'OUTBOUND_MESSAGE', label: 'Outbound Message' },
    ],
  },
  {
    label: 'Integration',
    options: [
      { value: 'CALL_API', label: 'Call API' },
      { value: 'HTTP_CALLOUT', label: 'HTTP Callout' },
      { value: 'PUBLISH_EVENT', label: 'Publish Event' },
      { value: 'INVOKE_SCRIPT', label: 'Invoke Script' },
    ],
  },
  {
    label: 'Utility',
    options: [
      { value: 'LOG_MESSAGE', label: 'Log Message' },
      { value: 'TRANSFORM_DATA', label: 'Transform Data' },
    ],
  },
]
