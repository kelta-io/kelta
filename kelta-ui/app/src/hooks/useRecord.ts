/**
 * useRecord Hook
 *
 * Fetches a single record by ID from a collection via JSON:API endpoint.
 * Automatically unwraps JSON:API response format to a flat object.
 * Supports ?include= for fetching related resources in a single request.
 */

import { useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'
import { useOffline } from '../offline'
import type { ReplicaRecord } from '../offline'
import { unwrapResource } from '../utils/jsonapi'
import type { CollectionRecord } from './useCollectionRecords'

export interface UseRecordOptions {
  /** Collection name (URL slug) */
  collectionName: string | undefined
  /** Record ID */
  recordId: string | undefined
  /** Whether the query is enabled */
  enabled?: boolean
  /** Comma-separated list of relationship names to include via JSON:API ?include= */
  include?: string
}

export interface UseRecordReturn {
  record: CollectionRecord | undefined
  isLoading: boolean
  error: Error | null
  /**
   * Refetch the record; resolves once the fresh record is in the cache, to its ETag (undefined
   * when the response carried none) — a caller about to write again can send it before the
   * page has re-rendered with it.
   */
  refetch: () => Promise<string | undefined>
  /** Raw JSON:API response for building display maps from included resources */
  rawResponse: unknown
  /** Optimistic-locking version token from the GET `ETag` (slice 5); echo as `If-Match` on write. */
  etag: string | undefined
}

/**
 * Hook to fetch a single record from a collection.
 *
 * @param options - Collection name, record ID, and enabled flag
 * @returns Record data, loading/error states, refetch function
 */
export function useRecord(options: UseRecordOptions): UseRecordReturn {
  const { apiClient } = useApi()
  const offline = useOffline()
  const { collectionName, recordId, enabled = true, include } = options

  // undefined offline context (admin pages) ⇒ always treat as online.
  const isOnline = offline?.online !== false

  const { data, isLoading, error, refetch } = useQuery({
    // `isOnline` is part of the key so a reconnect naturally re-runs the online path.
    queryKey: ['record', collectionName, recordId, include, isOnline],
    queryFn: async () => {
      // Offline: serve the record from the local replica (no ETag when cached).
      if (offline && !isOnline) {
        const cached = await offline.store.get(collectionName!, recordId!)
        return {
          record: cached as CollectionRecord | undefined,
          rawResponse: undefined,
          etag: undefined,
        }
      }
      const url = include
        ? `/api/${collectionName}/${recordId}?include=${encodeURIComponent(include)}`
        : `/api/${collectionName}/${recordId}`
      const { data: response, etag } = await apiClient.getWithMeta(url)
      const record = unwrapResource<CollectionRecord>(response)
      // Write-through into the replica for later offline reads.
      if (offline) {
        offline.registerCollection(collectionName!)
        await offline.store.putRecords(collectionName!, [record as ReplicaRecord])
      }
      return { record, rawResponse: response, etag }
    },
    enabled: !!collectionName && !!recordId && enabled,
    staleTime: 30 * 1000, // 30 seconds
  })

  const refetchEtag = useCallback(async () => (await refetch()).data?.etag, [refetch])

  return {
    record: data?.record,
    isLoading,
    error: error as Error | null,
    refetch: refetchEtag,
    rawResponse: data?.rawResponse,
    etag: data?.etag,
  }
}
