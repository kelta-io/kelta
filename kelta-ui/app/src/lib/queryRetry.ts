/**
 * Retry policy for React Query.
 *
 * A 4xx is a definitive answer (missing collection, missing record, forbidden,
 * bad request) — retrying it only delays the terminal UI state. The detail
 * page used to spin through 3 back-off retries on the collection lookup and
 * 3 more on the record before showing anything. 408/429 are transient and
 * keep the default retries; so do network errors and 5xx.
 */

import { ApiError } from '../services/apiClient'

export const MAX_QUERY_RETRIES = 3

const RETRYABLE_CLIENT_STATUSES = new Set([408, 429])

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES) return false
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return RETRYABLE_CLIENT_STATUSES.has(error.status)
  }
  return true
}
