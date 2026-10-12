/**
 * QuickActionsMenu Component
 *
 * Renders a dropdown menu of quick actions for a record or list view.
 * Fetches available quick actions for the collection and displays them
 * as a dropdown menu button. Actions are filtered by context (record vs list).
 *
 * When no quick actions are configured, the component renders nothing.
 */

import React, { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Zap,
  PlusCircle,
  Pencil,
  Play,
  MessageSquare,
  Mail,
  Loader2,
  ChevronDown,
  type LucideIcon,
} from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useApi } from '@/context/ApiContext'
import { useQuickActions } from '@/hooks/useQuickActions'
import { useScriptExecution } from '@/hooks/useScriptExecution'
import { useRecordStack } from '@/context/RecordStackContext'
import { recordRoutes } from '@/utils/recordRoutes'
import { componentRegistry, type QuickActionComponentProps } from '@/services/componentRegistry'
import { UpdateFieldValueDialog } from './UpdateFieldValueDialog'
import type {
  QuickActionDefinition,
  QuickActionContext,
  QuickActionExecutionContext,
  RunScriptConfig,
  CreateRelatedConfig,
  UpdateFieldConfig,
  LogActivityConfig,
  SendEmailConfig,
  CustomActionConfig,
} from '@/types/quickActions'

/**
 * Map of action type to default icon
 */
const ACTION_TYPE_ICONS: Record<string, LucideIcon> = {
  create_related: PlusCircle,
  update_field: Pencil,
  run_script: Play,
  log_activity: MessageSquare,
  send_email: Mail,
  custom: Zap,
}

export interface QuickActionsMenuProps {
  /** Collection name to fetch actions for */
  collectionName: string
  /** Context filter — show record actions, list actions, or both */
  context: QuickActionContext
  /** Execution context for running actions */
  executionContext: QuickActionExecutionContext
  /** Optional: callback after action completes */
  onActionComplete?: () => void
  /** Optional: button label override */
  label?: string
  /** Optional: button variant */
  variant?: 'default' | 'outline' | 'ghost' | 'secondary'
  /** Optional: button size */
  size?: 'default' | 'sm' | 'lg' | 'icon'
}

export function QuickActionsMenu({
  collectionName,
  context,
  executionContext,
  onActionComplete,
  label = 'Quick Actions',
  variant = 'outline',
  size = 'sm',
}: QuickActionsMenuProps): React.ReactElement | null {
  const navigate = useNavigate()
  const { apiClient } = useApi()
  const { actions, isLoading } = useQuickActions({
    collectionName,
    context,
  })
  const { execute, isPending } = useScriptExecution()
  const recordStack = useRecordStack()
  const [customAction, setCustomAction] = useState<{
    Component: React.ComponentType<QuickActionComponentProps>
    props?: Record<string, unknown>
  } | null>(null)
  const [updateFieldAction, setUpdateFieldAction] = useState<{
    action: QuickActionDefinition
    config: UpdateFieldConfig
  } | null>(null)

  const handleAction = useCallback(
    async (action: QuickActionDefinition) => {
      try {
        switch (action.config.type) {
          case 'create_related': {
            const config = action.config as CreateRelatedConfig
            const params = new URLSearchParams()
            if (executionContext.recordId) {
              params.set(config.lookupField, executionContext.recordId)
            }
            if (config.defaults) {
              for (const [key, value] of Object.entries(config.defaults)) {
                params.set(key, String(value))
              }
            }
            navigate(
              recordRoutes(recordStack, executionContext.tenantSlug).new(
                config.targetCollection,
                params
              )
            )
            break
          }

          case 'update_field': {
            const config = action.config as UpdateFieldConfig
            if (!executionContext.recordId) {
              toast.error('No record to update')
              break
            }
            if (config.setValue !== undefined) {
              await apiClient.patch(
                `/api/${executionContext.collectionName}/${executionContext.recordId}`,
                { [config.fieldName]: config.setValue }
              )
              toast.success(`${config.fieldName} updated`)
              onActionComplete?.()
            } else {
              // No preset value — open the value-entry dialog. The dialog itself is the
              // interaction, so requiresConfirmation never adds a second prompt here.
              setUpdateFieldAction({ action, config })
            }
            break
          }

          case 'run_script': {
            const config = action.config as RunScriptConfig
            await execute({
              scriptId: config.scriptId,
              context: executionContext,
            })
            onActionComplete?.()
            break
          }

          case 'log_activity': {
            const config = action.config as LogActivityConfig
            if (!executionContext.collectionId || !executionContext.recordId) {
              toast.error('No record to log activity on')
              break
            }
            const label = config.activityType ? `${config.activityType}: ` : ''
            await apiClient.postResource(`/api/notes`, {
              collectionId: executionContext.collectionId,
              recordId: executionContext.recordId,
              content: `${label}${action.label}`,
            })
            toast.success('Activity logged')
            onActionComplete?.()
            break
          }

          case 'send_email': {
            const config = action.config as SendEmailConfig
            if (!config.templateId) {
              toast.error('No email template configured for this action')
              break
            }
            const to = config.recipientField
              ? (executionContext.record?.[config.recipientField] as string | undefined)
              : undefined
            if (!to) {
              toast.error('No recipient email found on this record')
              break
            }
            await apiClient.post('/api/email/send', {
              templateId: config.templateId,
              to,
              // The template is admin-authored; the record supplies ${field} merge values only.
              mergeContext: executionContext.record ?? {},
              // Stamped as email_log.source_id so the record's activity timeline lists this email
              recordId: executionContext.recordId,
            })
            toast.success('Email sent')
            onActionComplete?.()
            break
          }

          case 'custom': {
            const config = action.config as CustomActionConfig
            const Component = componentRegistry.getQuickAction(config.componentName)
            if (!Component) {
              toast.info('Custom action requires a registered plugin component.')
              break
            }
            setCustomAction({ Component, props: config.props })
            break
          }
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Action failed'
        toast.error(message)
      }
    },
    [executionContext, execute, navigate, onActionComplete, apiClient, recordStack]
  )

  // Don't render anything if there are no actions and we're done loading
  if (!isLoading && actions.length === 0) {
    return null
  }

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant={variant} size={size} disabled={isLoading || isPending}>
            {isPending ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Zap className="mr-1.5 h-3.5 w-3.5" />
            )}
            {label}
            <ChevronDown className="ml-1.5 h-3.5 w-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          {isLoading ? (
            <DropdownMenuItem disabled>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Loading actions...
            </DropdownMenuItem>
          ) : (
            actions.map((action, index) => {
              const Icon = ACTION_TYPE_ICONS[action.type] || Zap
              return (
                <React.Fragment key={action.id}>
                  {index > 0 && action.type !== actions[index - 1].type && (
                    <DropdownMenuSeparator />
                  )}
                  <DropdownMenuItem onClick={() => handleAction(action)}>
                    <Icon className="mr-2 h-4 w-4" />
                    {action.label}
                  </DropdownMenuItem>
                </React.Fragment>
              )
            })
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {updateFieldAction && executionContext.recordId && (
        <UpdateFieldValueDialog
          action={updateFieldAction.action}
          config={updateFieldAction.config}
          executionContext={executionContext}
          onSubmit={async (value) => {
            await apiClient.patch(
              `/api/${executionContext.collectionName}/${executionContext.recordId}`,
              { [updateFieldAction.config.fieldName]: value }
            )
            toast.success(`${updateFieldAction.config.fieldName} updated`)
            setUpdateFieldAction(null)
            onActionComplete?.()
          }}
          onClose={() => setUpdateFieldAction(null)}
        />
      )}
      {customAction && (
        <customAction.Component
          collectionName={executionContext.collectionName}
          recordId={executionContext.recordId}
          record={executionContext.record}
          tenantSlug={executionContext.tenantSlug}
          config={customAction.props}
          onComplete={() => {
            setCustomAction(null)
            onActionComplete?.()
          }}
        />
      )}
    </>
  )
}
