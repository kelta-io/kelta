export type ToolCallStatus = 'pending' | 'done' | 'error'

export interface ToolCallState {
  id: string
  name: string
  status: ToolCallStatus
  summary?: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  proposals?: AiProposal[]
  toolCall?: ToolCallState
  contentBlocks?: Array<Record<string, unknown>>
  tokensInput?: number
  tokensOutput?: number
  createdAt: string
}

type AiProposalType =
  | 'collection'
  | 'layout'
  | 'add_fields'
  | 'update_field'
  | 'remove_field'
  | 'picklist'
  | 'ui_page'

export interface AiProposal {
  id: string
  type: AiProposalType
  status: 'pending' | 'applied' | 'dismissed'
  data: Record<string, unknown>
  createdAt: string
}

export interface ConversationSummary {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface AiChatState {
  isOpen: boolean
  conversations: ConversationSummary[]
  activeConversationId: string | null
  messages: ChatMessage[]
  isStreaming: boolean
  streamingText: string
  proposals: AiProposal[]
  tokenUsage: { used: number; limit: number } | null
}

export interface ProposedField {
  name: string
  type: string
  nullable?: boolean
  unique?: boolean
  defaultValue?: string
  validationRules?: Array<{ type: string; value: string }>
  enumValues?: string[]
  referenceConfig?: {
    targetCollection: string
    relationshipName?: string
    cascadeDelete?: boolean
  }
}

export interface ProposedSection {
  heading: string
  columns: number
  style: string
  fieldPlacements: Array<{
    fieldName: string
    columnNumber: number
    sortOrder: number
  }>
}

export interface ProposedFieldChange {
  displayName?: string
  required?: boolean
  unique?: boolean
  description?: string
  enumValues?: string[]
  validationRules?: Array<{ type: string; value: string }>
}

export interface ProposedPicklistValue {
  value: string
  label?: string
  sortOrder?: number
}
