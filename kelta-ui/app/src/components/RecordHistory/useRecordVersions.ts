/**
 * useRecordVersions Hook
 *
 * Fetches the record_version snapshots for a single record from the
 * `record-versions` read-only system collection (written by the worker's
 * RecordVersionHook when the collection has trackHistory enabled).
 * Newest version first.
 */

import { useQuery } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

type RecordVersionChangeType = 'CREATED' | 'UPDATED' | 'DELETED'

export interface RecordVersion {
  id: string
  collectionId: string
  recordId: string
  versionNumber: number
  changeType: RecordVersionChangeType
  /** Full field map of the record as of this version. */
  snapshot: Record<string, unknown>
  /** Field names changed in this version (empty for DELETED). */
  changedFields: string[]
  changedBy: string
  changedAt: string
  changeSource: string
}

/** JSONB values may arrive as native objects or as JSON strings — normalize defensively. */
function parseJson<T>(value: unknown, fallback: T): T {
  if (value == null) return fallback
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as T
    } catch {
      return fallback
    }
  }
  return value as T
}

interface RawVersionRow {
  id: string
  collectionId?: string
  recordId?: string
  versionNumber?: number
  changeType?: string
  snapshot?: unknown
  changedFields?: unknown
  changedBy?: string
  changedAt?: string
  changeSource?: string
}

export function parseRecordVersion(row: RawVersionRow): RecordVersion {
  const parsedSnapshot = parseJson<unknown>(row.snapshot, {})
  const parsedChangedFields = parseJson<unknown>(row.changedFields, [])
  return {
    id: row.id,
    collectionId: row.collectionId ?? '',
    recordId: row.recordId ?? '',
    versionNumber: typeof row.versionNumber === 'number' ? row.versionNumber : 0,
    changeType: (row.changeType as RecordVersionChangeType) ?? 'UPDATED',
    snapshot:
      parsedSnapshot != null && typeof parsedSnapshot === 'object' && !Array.isArray(parsedSnapshot)
        ? (parsedSnapshot as Record<string, unknown>)
        : {},
    changedFields: Array.isArray(parsedChangedFields)
      ? parsedChangedFields.filter((f): f is string => typeof f === 'string')
      : [],
    changedBy: row.changedBy ?? '',
    changedAt: row.changedAt ?? '',
    changeSource: row.changeSource ?? '',
  }
}

/** Shapes a version's snapshot into the flat record object the detail body renders. */
export function snapshotToRecord(version: RecordVersion): CollectionRecord {
  const snapshot = version.snapshot
  const id = typeof snapshot.id === 'string' ? snapshot.id : version.recordId
  return { ...snapshot, id }
}

export interface UseRecordVersionsReturn {
  versions: RecordVersion[]
  isLoading: boolean
  error: Error | null
}

export function useRecordVersions(
  collectionId: string | undefined,
  recordId: string | undefined,
  enabled = true
): UseRecordVersionsReturn {
  const { apiClient } = useApi()

  const { data, isLoading, error } = useQuery({
    queryKey: ['record-versions', collectionId, recordId],
    queryFn: async () => {
      const rows = await apiClient.getList<RawVersionRow>(
        `/api/record-versions?filter[collectionId][eq]=${encodeURIComponent(
          collectionId!
        )}&filter[recordId][eq]=${encodeURIComponent(recordId!)}&page[size]=200`
      )
      return rows.map(parseRecordVersion).sort((a, b) => b.versionNumber - a.versionNumber)
    },
    enabled: enabled && !!collectionId && !!recordId,
  })

  return {
    versions: data ?? [],
    isLoading,
    error: error as Error | null,
  }
}
