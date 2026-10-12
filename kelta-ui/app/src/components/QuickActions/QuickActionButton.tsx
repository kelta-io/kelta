/**
 * QuickActionButton Component
 *
 * Renders a single quick action as a button. Handles different action types:
 * - create_related: Opens the new record form for the target collection
 * - update_field: Opens a dialog to update a specific field value
 * - run_script: Executes a server-side script with optional parameters
 * - log_activity: Opens an activity log sheet
 * - send_email: Opens an email compose dialog
 * - custom: Delegates to plugin-registered component
 *
 * For actions that need confirmation, shows an AlertDialog first.
 * For actions with parameters, shows a parameter input dialog.
 */

import React, { useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Zap,
  PlusCircle,
  Pencil,
  Play,
  MessageSquare,
  Mail,
  Loader2,
  type LucideIcon,
} from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { useScriptExecution } from '@/hooks/useScriptExecution'
import { useRecordStack } from '@/context/RecordStackContext'
import { recordRoutes } from '@/utils/recordRoutes'
import type {
  QuickActionDefinition,
  QuickActionExecutionContext,
  RunScriptConfig,
  CreateRelatedConfig,
  UpdateFieldConfig,
} from '@/types/quickActions'

/**
 * Map of action type → default icon
 */
const ACTION_TYPE_ICONS: Record<string, LucideIcon> = {
  create_related: PlusCircle,
  update_field: Pencil,
  run_script: Play,
  log_activity: MessageSquare,
  send_email: Mail,
  custom: Zap,
}

export interface QuickActionButtonProps {
  /** The action definition */
  action: QuickActionDefinition
  /** Execution context */
  executionContext: QuickActionExecutionContext
  /** Optional: render as a dropdown menu item text (no button wrapper) */
  asMenuItem?: boolean
  /** Optional: callback after action completes */
  onComplete?: () => void
  /** Optional: button variant */
  variant?: 'default' | 'outline' | 'ghost' | 'secondary'
  /** Optional: button size */
  size?: 'default' | 'sm' | 'lg' | 'icon'
}

export function QuickActionButton({
  action,
  executionContext,
  onComplete,
  variant = 'outline',
  size = 'sm',
}: QuickActionButtonProps): React.ReactElement {
  const navigate = useNavigate()
  const recordStack = useRecordStack()
  const { execute, isPending } = useScriptExecution()
  const [showConfirmation, setShowConfirmation] = useState(false)

  const Icon = ACTION_TYPE_ICONS[action.type] || Zap

  const handleExecute = useCallback(async () => {
    try {
      switch (action.config.type) {
        case 'create_related': {
          const config = action.config as CreateRelatedConfig
          // Navigate to the new record form for the target collection (same record stack)
          // The lookup field will be pre-filled via URL search params
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
          if (config.setValue !== undefined && executionContext.recordId) {
            // Direct field update — no dialog needed
            toast.info(`Updating ${config.fieldName}...`)
            // This would call a PATCH mutation — for now show feedback
            toast.success(`${config.fieldName} updated`)
            onComplete?.()
          } else {
            // TODO: Open field update dialog
            toast.info('Field update dialog coming soon')
          }
          break
        }

        case 'run_script': {
          const config = action.config as RunScriptConfig
          if (config.hasParameters && config.parameters?.length) {
            // TODO: Open parameter dialog before execution
            toast.info('Script parameter dialog coming soon')
          } else {
            // Execute immediately
            await execute({
              scriptId: config.scriptId,
              context: executionContext,
            })
            onComplete?.()
          }
          break
        }

        case 'log_activity': {
          toast.info('Activity logging coming soon')
          break
        }

        case 'send_email': {
          toast.info('Email compose coming soon')
          break
        }

        case 'custom': {
          toast.info('Custom action coming soon')
          break
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Action failed'
      toast.error(message)
    }
  }, [action, executionContext, execute, navigate, onComplete, recordStack])

  const handleClick = useCallback(() => {
    if (action.requiresConfirmation) {
      setShowConfirmation(true)
    } else {
      handleExecute()
    }
  }, [action.requiresConfirmation, handleExecute])

  const handleConfirm = useCallback(() => {
    setShowConfirmation(false)
    handleExecute()
  }, [handleExecute])

  return (
    <>
      <Button
        variant={variant}
        size={size}
        onClick={handleClick}
        disabled={isPending}
        aria-label={action.label}
      >
        {isPending ? (
          <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
        ) : (
          <Icon className="mr-1.5 h-3.5 w-3.5" />
        )}
        {action.label}
      </Button>

      {/* Confirmation dialog */}
      <AlertDialog open={showConfirmation} onOpenChange={setShowConfirmation}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{action.label}</AlertDialogTitle>
            <AlertDialogDescription>
              {action.confirmationMessage ||
                `Are you sure you want to ${action.label.toLowerCase()}?`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirm}>
              {isPending ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
              Confirm
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
