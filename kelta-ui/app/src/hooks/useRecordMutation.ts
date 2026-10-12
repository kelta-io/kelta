/**
 * useRecordMutation Hook
 *
 * Provides create, update, patch, and delete mutations for collection records.
 * All mutations use JSON:API request wrapping and invalidate the collection's
 * record caches (end-user and admin stacks) on success.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'
import { useOffline } from '../offline'
import { parseAxiosError } from '../services/apiClient'
import { wrapResource, unwrapResource } from '../utils/jsonapi'
import type { CollectionRecord } from './useCollectionRecords'

export interface UseRecordMutationOptions {
  /** Collection name (URL slug) */
  collectionName: string
  /**
   * Callback on successful mutation. Create / update / patch pass the saved record
   * (create: the new record, so a caller can navigate to it); delete passes nothing.
   */
  onSuccess?: (record?: CollectionRecord) => void
  /** Callback on mutation error */
  onError?: (error: Error) => void
}

export interface UseRecordMutationReturn {
  /** Create a new record */
  create: {
    mutate: (data: Record<string, unknown>) => void
    mutateAsync: (data: Record<string, unknown>) => Promise<CollectionRecord>
    isPending: boolean
  }
  /** Full update (PUT) of a record. Pass `ifMatch` (record ETag) for optimistic locking. */
  update: {
    mutate: (params: { id: string; data: Record<string, unknown>; ifMatch?: string }) => void
    mutateAsync: (params: {
      id: string
      data: Record<string, unknown>
      ifMatch?: string
    }) => Promise<CollectionRecord>
    isPending: boolean
  }
  /** Partial update (PATCH) of a record. Pass `ifMatch` (record ETag) for optimistic locking. */
  patch: {
    mutate: (params: { id: string; data: Record<string, unknown>; ifMatch?: string }) => void
    mutateAsync: (params: {
      id: string
      data: Record<string, unknown>
      ifMatch?: string
    }) => Promise<CollectionRecord>
    isPending: boolean
  }
  /** Delete a record by ID (or `{id, ifMatch}` for optimistic locking). */
  remove: {
    mutate: (idOrParams: string | { id: string; ifMatch?: string }) => void
    mutateAsync: (idOrParams: string | { id: string; ifMatch?: string }) => Promise<void>
    isPending: boolean
  }
  /** Bulk delete records by IDs */
  bulkDelete: {
    mutate: (ids: string[]) => void
    mutateAsync: (ids: string[]) => Promise<void>
    isPending: boolean
  }
}

/**
 * Hook providing CRUD mutations for collection records.
 *
 * All write operations use JSON:API request/response wrapping.
 * On success, the collection records query cache is invalidated.
 */
export function useRecordMutation(options: UseRecordMutationOptions): UseRecordMutationReturn {
  const { collectionName, onSuccess, onError } = options
  const { apiClient } = useApi()
  const offline = useOffline()
  const queryClient = useQueryClient()

  /** True when an offline replica is mounted and connectivity is down. */
  const isOffline = (): boolean => !!offline && offline.online === false

  // Both record stacks show the same records, so a write refreshes both stacks' caches: the
  // end-user list/detail (`collection-records` / `record`) and the admin list/detail
  // (`resources` / `resource`) — the app's 5-minute staleTime would otherwise serve the
  // pre-save record on the page a save lands on.
  const invalidateRecords = () => {
    queryClient.invalidateQueries({ queryKey: ['collection-records', collectionName] })
    queryClient.invalidateQueries({ queryKey: ['record', collectionName] })
    queryClient.invalidateQueries({ queryKey: ['resources', collectionName] })
    queryClient.invalidateQueries({ queryKey: ['resource', collectionName] })
  }

  /** Convert raw Axios errors into structured ApiError with field-level details. */
  const handleError = (error: Error) => {
    const apiError = parseAxiosError(error)
    onError?.(apiError)
  }

  const createMutation = useMutation({
    mutationFn: async (data: Record<string, unknown>) => {
      // Offline: queue to the outbox; the temp record surfaces optimistically.
      if (offline && isOffline()) {
        const op = await offline.engine.queue(collectionName, 'create', { payload: data })
        return { id: op.recordId!, ...data } as CollectionRecord
      }
      const body = wrapResource(collectionName, data)
      const response = await apiClient.post(`/api/${collectionName}`, body)
      return unwrapResource<CollectionRecord>(response)
    },
    onSuccess: (record: CollectionRecord) => {
      invalidateRecords()
      onSuccess?.(record)
    },
    onError: handleError,
  })

  const updateMutation = useMutation({
    mutationFn: async ({
      id,
      data,
      ifMatch,
    }: {
      id: string
      data: Record<string, unknown>
      ifMatch?: string
    }) => {
      if (offline && isOffline()) {
        await offline.engine.queue(collectionName, 'update', { recordId: id, payload: data })
        return { id, ...data } as CollectionRecord
      }
      const body = wrapResource(collectionName, data, id)
      const response = await apiClient.put(`/api/${collectionName}/${id}`, body, ifMatch)
      return unwrapResource<CollectionRecord>(response)
    },
    onSuccess: (record: CollectionRecord) => {
      invalidateRecords()
      onSuccess?.(record)
    },
    onError: handleError,
  })

  const patchMutation = useMutation({
    mutationFn: async ({
      id,
      data,
      ifMatch,
    }: {
      id: string
      data: Record<string, unknown>
      ifMatch?: string
    }) => {
      // Offline: a partial edit is queued as an 'update' op; push replays it as a
      // full PUT (queued PATCH promoted to PUT on flush — acceptable for v1).
      if (offline && isOffline()) {
        await offline.engine.queue(collectionName, 'update', { recordId: id, payload: data })
        return { id, ...data } as CollectionRecord
      }
      const body = wrapResource(collectionName, data, id)
      const response = await apiClient.patch(`/api/${collectionName}/${id}`, body, ifMatch)
      return unwrapResource<CollectionRecord>(response)
    },
    onSuccess: (record: CollectionRecord) => {
      invalidateRecords()
      onSuccess?.(record)
    },
    onError: handleError,
  })

  const removeMutation = useMutation({
    mutationFn: async (idOrParams: string | { id: string; ifMatch?: string }) => {
      const { id, ifMatch } =
        typeof idOrParams === 'string' ? { id: idOrParams, ifMatch: undefined } : idOrParams
      if (offline && isOffline()) {
        await offline.engine.queue(collectionName, 'delete', { recordId: id })
        return
      }
      await apiClient.delete(`/api/${collectionName}/${id}`, ifMatch)
    },
    onSuccess: () => {
      invalidateRecords()
      onSuccess?.()
    },
    onError: handleError,
  })

  const bulkDeleteMutation = useMutation({
    mutationFn: async (ids: string[]) => {
      await apiClient.post(`/api/${collectionName}/bulk-delete`, { ids })
    },
    onSuccess: () => {
      invalidateRecords()
      onSuccess?.()
    },
    onError: handleError,
  })

  return {
    create: {
      mutate: createMutation.mutate,
      mutateAsync: createMutation.mutateAsync,
      isPending: createMutation.isPending,
    },
    update: {
      mutate: updateMutation.mutate,
      mutateAsync: updateMutation.mutateAsync,
      isPending: updateMutation.isPending,
    },
    patch: {
      mutate: patchMutation.mutate,
      mutateAsync: patchMutation.mutateAsync,
      isPending: patchMutation.isPending,
    },
    remove: {
      mutate: removeMutation.mutate,
      mutateAsync: removeMutation.mutateAsync,
      isPending: removeMutation.isPending,
    },
    bulkDelete: {
      mutate: bulkDeleteMutation.mutate,
      mutateAsync: bulkDeleteMutation.mutateAsync,
      isPending: bulkDeleteMutation.isPending,
    },
  }
}
