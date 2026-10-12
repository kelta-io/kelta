/**
 * Optimistic-concurrency guard for list inline edits (record-stack convergence P5).
 *
 * List responses carry no per-row `ETag` — the worker emits it only on a single-record GET — but
 * every row carries its `updatedAt` version, serialized exactly as on that GET. So before an
 * inline cell write the list fetches the record's fresh ETag (as the kanban drag does) and checks
 * the row it was showing is still that version:
 *
 * - the row's `updatedAt` no longer matches ⇒ someone saved the record after the list loaded:
 *   throw a client-side `409 STALE_WRITE` (nothing is written);
 * - it matches ⇒ return the fresh ETag for the write's `If-Match`, so a save landing between this
 *   read and the write is still rejected by the worker with `409 STALE_WRITE`.
 */

import { staleWriteError } from '@/services/apiClient'
import { unwrapResource } from './jsonapi'

/** The slice of `ApiClient` the check needs (a GET that returns the response `ETag`). */
export interface RowVersionClient {
  getWithMeta: (url: string) => Promise<{ data: unknown; etag?: string }>
}

/** A list row: any record object (only its `updatedAt` is read). */
export type VersionedRow = Record<string, unknown>

/**
 * Resolve the `If-Match` for an inline write to `recordId`, or throw a `409 STALE_WRITE`
 * (`isStaleWriteError`) when `row` is older than the stored record.
 */
export async function resolveRowIfMatch(
  apiClient: RowVersionClient,
  collectionName: string,
  recordId: string,
  row: VersionedRow | undefined,
  staleMessage: string
): Promise<string | undefined> {
  const { data, etag } = await apiClient.getWithMeta(`/api/${collectionName}/${recordId}`)
  const current = unwrapResource<VersionedRow>(data)
  const shown = row?.updatedAt
  const stored = current?.updatedAt
  if (shown != null && stored != null && String(shown) !== String(stored)) {
    throw staleWriteError(staleMessage)
  }
  return etag
}
