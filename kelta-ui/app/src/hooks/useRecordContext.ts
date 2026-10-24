/**
 * useRecordContext Hook
 *
 * Fetches both notes and attachments for a record from separate JSON:API
 * endpoints (/api/notes and /api/attachments) using filter queries.
 */

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import { useCallback } from 'react'

/**
 * A note associated with a record
 */
export interface Note {
  id: string
  content: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

/**
 * An attachment associated with a record
 */
export interface Attachment {
  id: string
  fileName: string
  fileSize: number
  contentType: string
  uploadedBy: string
  uploadedAt: string
  downloadUrl?: string | null
}

/**
 * Combined record context response
 */
interface RecordContext {
  notes: Note[]
  attachments: Attachment[]
}

/**
 * Return type for the useRecordContext hook
 */
export interface UseRecordContextReturn {
  notes: Note[]
  attachments: Attachment[]
  isLoading: boolean
  error: Error | null
  invalidate: () => void
}

/**
 * Fetches combined notes and attachments for a record in a single API call.
 *
 * @param collectionId - the collection UUID
 * @param recordId - the record UUID
 * @returns combined notes and attachments data with loading/error state
 */
export function useRecordContext(
  collectionId: string | undefined,
  recordId: string | undefined
): UseRecordContextReturn {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const { data, isLoading, error } = useQuery({
    queryKey: ['record-context', collectionId, recordId],
    queryFn: async () => {
      try {
        // Fetch notes and attachments in parallel from JSON:API endpoints
        const [notes, attachments] = await Promise.all([
          apiClient
            .getList<Note>(
              `/api/notes?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}`
            )
            .catch(() => [] as Note[]),
          apiClient
            .getList<Attachment>(
              `/api/attachments?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}`
            )
            .catch(() => [] as Attachment[]),
        ])
        return { notes, attachments } as RecordContext
      } catch {
        // Return empty context if endpoints not available
        return { notes: [], attachments: [] } as RecordContext
      }
    },
    enabled: !!collectionId && !!recordId,
  })

  const invalidate = useCallback(() => {
    queryClient.invalidateQueries({
      queryKey: ['record-context', collectionId, recordId],
    })
  }, [queryClient, collectionId, recordId])

  return {
    notes: data?.notes ?? [],
    attachments: data?.attachments ?? [],
    isLoading,
    error: error as Error | null,
    invalidate,
  }
}
