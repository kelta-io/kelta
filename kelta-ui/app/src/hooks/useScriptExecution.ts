/**
 * useScriptExecution Hook
 *
 * Executes server-side scripts and handles the result actions
 * (refresh, redirect, toast, open record).
 *
 * Scripts are executed with a record context (collection, record ID,
 * record data) and optional user-provided parameters.
 *
 * Runs the script server-side via `POST /api/scripts/{id}/execute` (sandboxed
 * GraalVM). The script's returned `output` may carry `{ action, message }` to
 * drive the post-run UI behavior handled below.
 */

import { useCallback } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApi } from '../context/ApiContext'
import { parseAxiosError } from '../services/apiClient'
import { useRecordStack } from '../context/RecordStackContext'
import { recordRoutes } from '../utils/recordRoutes'
import type {
  ScriptExecutionResult,
  ScriptResultAction,
  QuickActionExecutionContext,
} from '@/types/quickActions'

export interface ExecuteScriptParams {
  /** Script ID to execute */
  scriptId: string
  /** Execution context (record, collection, etc.) */
  context: QuickActionExecutionContext
  /** Optional parameters from user input */
  parameters?: Record<string, unknown>
}

export interface UseScriptExecutionReturn {
  /** Execute a script */
  execute: (params: ExecuteScriptParams) => Promise<ScriptExecutionResult>
  /** Whether a script is currently running */
  isPending: boolean
  /** Error from the last execution, if any */
  error: Error | null
}

/**
 * Hook for executing server-side scripts and handling their return actions.
 *
 * After a script executes, the hook automatically handles the result:
 * - `refresh` → invalidates React Query caches to reload data
 * - `redirect` → navigates to the specified URL
 * - `toast` → shows a toast notification
 * - `open_record` → navigates to a record detail page
 * - `none` → does nothing
 *
 * @returns Script execution function and state
 */
/** Shape of the worker `POST /api/scripts/{id}/execute` response. */
interface ScriptExecuteResponse {
  success: boolean
  output?: Record<string, unknown> | null
  executionTimeMs?: number
  message?: string
}

/** Maps the worker execute response onto the UI's ScriptExecutionResult contract. */
function toScriptResult(response: ScriptExecuteResponse): ScriptExecutionResult {
  const output = (response.output ?? {}) as Record<string, unknown>
  const action = output.action as ScriptResultAction | undefined
  const message =
    response.message ?? (typeof output.message === 'string' ? output.message : undefined)
  return { success: response.success, action, message, data: output }
}

export function useScriptExecution(): UseScriptExecutionReturn {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { apiClient } = useApi()
  const recordStack = useRecordStack()

  const handleResultAction = useCallback(
    (result: ScriptExecutionResult, context: QuickActionExecutionContext) => {
      if (!result.action) {
        // No action specified — show success/error toast based on result
        if (result.success) {
          toast.success(result.message || 'Action completed successfully')
        } else {
          toast.error(result.message || 'Action failed')
        }
        return
      }

      switch (result.action.type) {
        case 'refresh':
          // Invalidate relevant queries to refresh data
          queryClient.invalidateQueries({
            queryKey: ['collection-records', context.collectionName],
          })
          if (context.recordId) {
            queryClient.invalidateQueries({
              queryKey: ['record', context.collectionName, context.recordId],
            })
          }
          if (result.message) {
            toast.success(result.message)
          }
          break

        case 'redirect':
          navigate(result.action.url)
          break

        case 'toast': {
          const variant = result.action.variant || 'default'
          if (variant === 'success') {
            toast.success(result.action.message)
          } else if (variant === 'error') {
            toast.error(result.action.message)
          } else {
            toast(result.action.message)
          }
          break
        }

        case 'open_record': {
          const { collection, recordId } = result.action
          navigate(recordRoutes(recordStack, context.tenantSlug).detail(collection, recordId))
          break
        }

        case 'none':
          // Do nothing
          break
      }
    },
    [navigate, queryClient, recordStack]
  )

  const mutation = useMutation({
    mutationFn: async (params: ExecuteScriptParams): Promise<ScriptExecutionResult> => {
      try {
        const response = await apiClient.post<ScriptExecuteResponse>(
          `/api/scripts/${params.scriptId}/execute`,
          {
            input: params.parameters ?? {},
            context: {
              collectionName: params.context.collectionName,
              recordId: params.context.recordId,
            },
          }
        )
        return toScriptResult(response)
      } catch (err) {
        return { success: false, message: parseAxiosError(err).message }
      }
    },
  })

  const execute = useCallback(
    async (params: ExecuteScriptParams): Promise<ScriptExecutionResult> => {
      const result = await mutation.mutateAsync(params)
      handleResultAction(result, params.context)
      return result
    },
    [mutation, handleResultAction]
  )

  return {
    execute,
    isPending: mutation.isPending,
    error: mutation.error as Error | null,
  }
}
