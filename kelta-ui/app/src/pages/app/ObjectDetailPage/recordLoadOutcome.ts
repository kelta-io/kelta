/**
 * Classifies the object detail page's load result so the page can pick a
 * terminal state: a 404 on the collection or the record is "not found in this
 * workspace" (typically a recents/favorites link from another tenant), not a
 * generic error.
 */

import { ApiError } from '@/services/apiClient'

/** True when an error is an HTTP 404 from the API. */
export function isNotFoundError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

export type RecordLoadOutcome = 'loaded' | 'collection-missing' | 'record-missing' | 'error'

/**
 * Classify the detail page's load result. Order matters: a missing collection
 * wins over a missing record (the record query is only meaningful when the
 * collection exists), and any non-404 error wins over "no record".
 */
export function classifyRecordLoad(args: {
  schemaError: Error | null
  recordError: Error | null
  hasRecord: boolean
}): RecordLoadOutcome {
  const { schemaError, recordError, hasRecord } = args
  if (isNotFoundError(schemaError)) return 'collection-missing'
  if (schemaError) return 'error'
  if (isNotFoundError(recordError)) return 'record-missing'
  if (recordError) return 'error'
  return hasRecord ? 'loaded' : 'record-missing'
}
