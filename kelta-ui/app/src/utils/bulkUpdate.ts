/**
 * Bulk-update submit/poll flow against the bulk-jobs backend, shared by the
 * RelatedList mass edit and the ObjectListPage mass edit (app-data-entry
 * slice 4): POST /api/bulk-jobs (operation UPDATE, partial by record id), then
 * poll the job to a terminal status. Emits no toasts — callers own messaging.
 */

/** Interval between bulk-job status polls. */
const BULK_POLL_INTERVAL_MS = 2000

/** Maximum number of status polls (~60s at 2s intervals). */
const BULK_MAX_POLL_ATTEMPTS = 30

/** Bulk-job statuses that end polling. */
const TERMINAL_JOB_STATUSES = new Set(['COMPLETED', 'FAILED', 'ABORTED'])

/** Minimal API surface needed (structural subset of services/apiClient.ApiClient). */
export interface BulkJobApi {
  post<T>(path: string, body?: unknown): Promise<T>
  get<T>(path: string): Promise<T>
}

/** JSON:API attributes returned by GET /api/bulk-jobs/{id}. */
interface BulkJobPollAttributes {
  status?: string
  processedRecords?: number
  successRecords?: number
  errorRecords?: number
}

export interface BulkUpdateResult {
  jobId: string
  /** Terminal status, or undefined when the job outlived the poll ceiling. */
  status: string | undefined
  successRecords: number
  errorRecords: number
}

/**
 * Submit a partial-update bulk job and poll it to a terminal status.
 * Throws only when the job cannot be created; a FAILED/ABORTED/timed-out job
 * is reported through `status` so callers can shape their own message.
 */
export async function runBulkUpdate(
  api: BulkJobApi,
  collectionId: string,
  records: Array<Record<string, unknown>>,
  options?: { pollIntervalMs?: number; maxPollAttempts?: number }
): Promise<BulkUpdateResult> {
  const pollIntervalMs = options?.pollIntervalMs ?? BULK_POLL_INTERVAL_MS
  const maxPollAttempts = options?.maxPollAttempts ?? BULK_MAX_POLL_ATTEMPTS

  const created = await api.post<{ data?: { id?: string } }>('/api/bulk-jobs', {
    collectionId,
    operation: 'UPDATE',
    records,
  })
  const jobId = created?.data?.id
  if (!jobId) throw new Error('Bulk job was not created')

  for (let attempt = 0; attempt < maxPollAttempts; attempt++) {
    const poll = await api.get<{ data?: { attributes?: BulkJobPollAttributes } }>(
      `/api/bulk-jobs/${jobId}`
    )
    const attrs = poll?.data?.attributes
    if (attrs?.status && TERMINAL_JOB_STATUSES.has(attrs.status)) {
      return {
        jobId,
        status: attrs.status,
        successRecords: attrs.successRecords ?? 0,
        errorRecords: attrs.errorRecords ?? 0,
      }
    }
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs))
  }

  return { jobId, status: undefined, successRecords: 0, errorRecords: 0 }
}
