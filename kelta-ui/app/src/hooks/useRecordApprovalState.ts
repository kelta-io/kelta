import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

export interface RecordApprovalState {
  /** An active (PENDING) approval exists for this record. */
  hasActiveApproval: boolean
  /** The record is write-locked by a pending approval (record_editability=LOCKED). */
  locked: boolean
  /** At least one active approval process targets this collection. */
  hasProcess: boolean
  isLoading: boolean
  invalidate: () => void
}

const RECORD_APPROVAL_QUERY_KEY = 'record-approval-state'

/**
 * Approval affordance state for a record detail page: whether a submit button should
 * show (active process exists, nothing pending) and whether to render the pending/lock
 * badge. `collectionId` is the collection UUID (schema.id), matching what the approval
 * endpoints and the approval-processes filter expect.
 */
export function useRecordApprovalState(
  collectionId: string | undefined,
  recordId: string | undefined
): RecordApprovalState {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const enabled = !!collectionId && !!recordId

  const { data, isLoading } = useQuery({
    queryKey: [RECORD_APPROVAL_QUERY_KEY, collectionId, recordId],
    enabled,
    staleTime: 60 * 1000,
    queryFn: async () => {
      const [status, lock, processes] = await Promise.all([
        apiClient
          .get<{
            hasActiveApproval: boolean
          }>(
            `/api/approvals/status?collectionId=${encodeURIComponent(collectionId!)}` +
              `&recordId=${encodeURIComponent(recordId!)}`
          )
          .catch(() => ({ hasActiveApproval: false })),
        apiClient
          .get<{
            locked: boolean
          }>(
            `/api/approvals/lock-status?collectionId=${encodeURIComponent(collectionId!)}` +
              `&recordId=${encodeURIComponent(recordId!)}`
          )
          .catch(() => ({ locked: false })),
        apiClient
          .getList<unknown>(
            `/api/approval-processes?filter[collectionId][eq]=${encodeURIComponent(collectionId!)}` +
              `&filter[active][eq]=true&page[size]=1`
          )
          .catch(() => [] as unknown[]),
      ])
      return {
        hasActiveApproval: status.hasActiveApproval === true,
        locked: lock.locked === true,
        hasProcess: processes.length > 0,
      }
    },
  })

  return {
    hasActiveApproval: data?.hasActiveApproval ?? false,
    locked: data?.locked ?? false,
    hasProcess: data?.hasProcess ?? false,
    isLoading,
    invalidate: () =>
      queryClient.invalidateQueries({
        queryKey: [RECORD_APPROVAL_QUERY_KEY, collectionId, recordId],
      }),
  }
}
