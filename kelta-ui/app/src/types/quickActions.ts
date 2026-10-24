/**
 * Quick Action Types
 *
 * Defines the shape of quick actions that appear on record headers
 * and list view toolbars. Quick actions allow users to perform
 * common operations quickly, such as creating related records,
 * updating specific fields, running scripts, or logging activities.
 *
 * Quick actions are configured per collection in the control plane
 * and fetched at runtime. When the backend endpoint is unavailable,
 * the UI gracefully falls back to an empty actions list.
 */

/**
 * The type of quick action determines the UI behavior.
 */
export type QuickActionType =
  | 'create_related'
  | 'update_field'
  | 'run_script'
  | 'log_activity'
  | 'send_email'
  | 'custom'

/**
 * Where the quick action can appear.
 */
export type QuickActionContext = 'record' | 'list' | 'both'

/**
 * Definition of a quick action as returned by the control plane.
 */
export interface QuickActionDefinition {
  /** Unique identifier */
  id: string
  /** Display label */
  label: string
  /** Optional icon name (Lucide icon) */
  icon?: string
  /** The action type */
  type: QuickActionType
  /** Where this action appears */
  context: QuickActionContext
  /** Sort order for display */
  sortOrder: number
  /** Whether this action requires confirmation before executing */
  requiresConfirmation?: boolean
  /** Confirmation message to display */
  confirmationMessage?: string
  /** Type-specific configuration */
  config: QuickActionConfig
}

/**
 * Configuration specific to each action type.
 */
export type QuickActionConfig =
  | CreateRelatedConfig
  | UpdateFieldConfig
  | RunScriptConfig
  | LogActivityConfig
  | SendEmailConfig
  | CustomActionConfig

export interface CreateRelatedConfig {
  type: 'create_related'
  /** Target collection to create a record in */
  targetCollection: string
  /** Field on target collection that links back to the source record */
  lookupField: string
  /** Optional default field values */
  defaults?: Record<string, unknown>
}

export interface UpdateFieldConfig {
  type: 'update_field'
  /** Field name to update */
  fieldName: string
  /** Optional: restrict to specific values */
  allowedValues?: unknown[]
  /** Optional: set a specific value (no dialog needed) */
  setValue?: unknown
}

export interface RunScriptConfig {
  type: 'run_script'
  /** Script ID to execute */
  scriptId: string
  /** Whether to show a parameter input dialog */
  hasParameters?: boolean
  /** Parameter definitions for the input dialog */
  parameters?: ScriptParameter[]
}

interface ScriptParameter {
  name: string
  label: string
  type: 'string' | 'number' | 'boolean' | 'date'
  required?: boolean
  defaultValue?: unknown
}

export interface LogActivityConfig {
  type: 'log_activity'
  /** Activity type (e.g., 'call', 'meeting', 'note') */
  activityType?: string
}

export interface SendEmailConfig {
  type: 'send_email'
  /** Email template ID */
  templateId?: string
  /** Field name containing the recipient email */
  recipientField?: string
}

export interface CustomActionConfig {
  type: 'custom'
  /** Custom component name from the plugin registry */
  componentName: string
  /** Custom props to pass to the component */
  props?: Record<string, unknown>
}

/**
 * Result returned after executing a script-based quick action.
 * Determines what the UI does next.
 */
export interface ScriptExecutionResult {
  /** Whether the script succeeded */
  success: boolean
  /** Action for the UI to take */
  action?: ScriptResultAction
  /** Message to display to the user */
  message?: string
  /** Optional data returned by the script */
  data?: Record<string, unknown>
}

/**
 * Actions the UI can take after a script executes.
 */
export type ScriptResultAction =
  | { type: 'refresh' }
  | { type: 'redirect'; url: string }
  | { type: 'toast'; message: string; variant?: 'default' | 'success' | 'error' }
  | { type: 'open_record'; collection: string; recordId: string }
  | { type: 'none' }

/**
 * Context passed to a quick action when executing.
 */
export interface QuickActionExecutionContext {
  /** Current collection name */
  collectionName: string
  /** Current collection id (UUID) — needed to write a note (log_activity) */
  collectionId?: string
  /** Current record ID (for record-scoped actions) */
  recordId?: string
  /** Current record data (for record-scoped actions) */
  record?: Record<string, unknown>
  /** Selected record IDs (for list-scoped bulk actions) */
  selectedIds?: string[]
  /** Tenant slug for navigation */
  tenantSlug: string
}
