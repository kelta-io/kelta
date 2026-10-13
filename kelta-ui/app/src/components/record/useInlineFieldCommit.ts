/**
 * useInlineFieldCommit — the record detail page's in-place field save, shared by both record
 * stacks (`ResourceDetailPage` and `ObjectDetailPage`).
 *
 * Each commit is a partial write of one field that echoes the record's ETag as `If-Match`, so a
 * concurrent edit elsewhere is rejected (`409 STALE_WRITE`) instead of silently overwritten. The
 * write moves the record to a new version, so a second commit sent with the version the page last
 * rendered would be rejected by the first one. Commits therefore run one at a time, and each takes
 * the version the previous one left behind: after a write (or a conflict) the record is refetched
 * and its fresh ETag adopted before the next commit is sent.
 */

import { useCallback, useEffect, useRef } from 'react'
import { isStaleWriteError } from '@/services/apiClient'

export interface UseInlineFieldCommitOptions {
  /** The record's ETag as last rendered. */
  etag: string | undefined
  /** Write one field, sending `ifMatch` as `If-Match`. */
  write: (fieldName: string, value: unknown, ifMatch: string | undefined) => Promise<void>
  /** Refetch the record; resolves to its fresh ETag (undefined when unknown). */
  refresh: () => Promise<string | undefined>
  /** After a successful write and refresh. */
  onCommitted?: (fieldName: string) => void
  /** The write was rejected as stale (`isStaleWriteError`); the record is being reloaded. */
  onConflict?: () => void
}

/** Returns the commit handler: resolves once saved, rejects (with the write's error) on failure. */
export function useInlineFieldCommit({
  etag,
  write,
  refresh,
  onCommitted,
  onConflict,
}: UseInlineFieldCommitOptions): (fieldName: string, value: unknown) => Promise<void> {
  // The version the next commit sends: the rendered ETag, or a fresher one a refresh returned
  // before the page re-rendered with it.
  const latestEtag = useRef(etag)
  useEffect(() => {
    latestEtag.current = etag
  }, [etag])

  const queue = useRef<Promise<void>>(Promise.resolve())

  const adopt = useCallback(async () => {
    const fresh = await refresh()
    if (fresh !== undefined) latestEtag.current = fresh
  }, [refresh])

  return useCallback(
    (fieldName: string, value: unknown): Promise<void> => {
      const run = async (): Promise<void> => {
        try {
          await write(fieldName, value, latestEtag.current)
        } catch (err) {
          if (isStaleWriteError(err)) {
            onConflict?.()
            await adopt().catch(() => undefined)
          }
          throw err
        }
        await adopt()
        onCommitted?.(fieldName)
      }
      const result = queue.current.then(run)
      // The next commit waits for this one, whatever its outcome.
      queue.current = result.catch(() => undefined)
      return result
    },
    [write, adopt, onCommitted, onConflict]
  )
}
