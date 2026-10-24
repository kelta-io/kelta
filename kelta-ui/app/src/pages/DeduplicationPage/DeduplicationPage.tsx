import React, { useMemo, useState, useCallback } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { useCollectionSchema } from '../../hooks/useCollectionSchema'
import type { CollectionDefinition } from '@kelta/sdk'
import { cn } from '@/lib/utils'

/** A group of records sharing the same values on the chosen match fields. */
interface DuplicateGroup {
  values: Record<string, unknown>
  count: number
  recordIds: string[]
}

interface DuplicateResponse {
  collectionName: string
  matchFields: string[]
  scanned: number
  truncated: boolean
  groups: DuplicateGroup[]
}

interface MergeResponse {
  masterId: string
  deletedIds: string[]
  reparentedRecords: number
  reparented: { collection: string; field: string; count: number }[]
}

export interface DeduplicationPageProps {
  testId?: string
}

/**
 * Admin duplicate-resolution workbench: pick a collection + match fields, scan for duplicate
 * groups (`POST /api/collections/{name}/duplicates`), then resolve a group by choosing a master
 * and merging the rest into it (`POST /api/collections/{name}/merge`). The merge re-parents
 * inbound FKs and deletes the duplicates server-side — a destructive action, so it is confirmed.
 */
export function DeduplicationPage({
  testId = 'deduplication-page',
}: DeduplicationPageProps): React.ReactElement {
  const { apiClient, keltaClient } = useApi()

  const [collectionName, setCollectionName] = useState('')
  const [matchFields, setMatchFields] = useState<string[]>([])
  const [result, setResult] = useState<DuplicateResponse | null>(null)
  // masterId chosen per group, keyed by the group's first record id (a stable group key).
  const [masterByGroup, setMasterByGroup] = useState<Record<string, string>>({})
  const [pendingMerge, setPendingMerge] = useState<{
    groupKey: string
    group: DuplicateGroup
  } | null>(null)

  const {
    data: collections,
    isLoading: collectionsLoading,
    error: collectionsError,
    refetch: refetchCollections,
  } = useQuery<CollectionDefinition[]>({
    queryKey: ['dedup-collections'],
    queryFn: () => keltaClient.admin.collections.list(),
  })

  // The collections *list* endpoint doesn't embed fields — load the selected collection's schema
  // (`?include=fields`) to populate the match-field picker.
  const { fields, isLoading: fieldsLoading } = useCollectionSchema(collectionName || undefined)

  const availableFields = useMemo(() => fields.filter((f) => f.name !== 'id'), [fields])

  const scanMutation = useMutation({
    mutationFn: () =>
      apiClient.post<DuplicateResponse>(`/api/collections/${collectionName}/duplicates`, {
        matchFields,
        limit: 100,
      }),
    onSuccess: (data) => {
      setResult(data)
      setMasterByGroup({})
      if (data.groups.length === 0) {
        toast.success('No duplicates found')
      }
    },
    onError: (err: Error) => toast.error(err.message || 'Scan failed'),
  })

  const mergeMutation = useMutation({
    mutationFn: ({ masterId, duplicateIds }: { masterId: string; duplicateIds: string[] }) =>
      apiClient.post<MergeResponse>(`/api/collections/${collectionName}/merge`, {
        masterId,
        duplicateIds,
      }),
    onSuccess: (data) => {
      toast.success(
        `Merged ${data.deletedIds.length} record(s); re-parented ${data.reparentedRecords} related record(s)`
      )
      setPendingMerge(null)
      // Re-scan so the resolved group drops out of the list.
      scanMutation.mutate()
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Merge failed')
      setPendingMerge(null)
    },
  })

  const toggleMatchField = useCallback((field: string) => {
    setMatchFields((prev) =>
      prev.includes(field) ? prev.filter((f) => f !== field) : [...prev, field]
    )
  }, [])

  const handleScan = useCallback(() => {
    if (!collectionName || matchFields.length === 0) return
    scanMutation.mutate()
  }, [collectionName, matchFields, scanMutation])

  const handleMergeClick = useCallback(
    (groupKey: string, group: DuplicateGroup) => {
      const masterId = masterByGroup[groupKey]
      if (!masterId) {
        toast.error('Choose a master record first')
        return
      }
      setPendingMerge({ groupKey, group })
    },
    [masterByGroup]
  )

  const handleMergeConfirm = useCallback(() => {
    if (!pendingMerge) return
    const masterId = masterByGroup[pendingMerge.groupKey]
    const duplicateIds = pendingMerge.group.recordIds.filter((id) => id !== masterId)
    if (!masterId || duplicateIds.length === 0) {
      setPendingMerge(null)
      return
    }
    mergeMutation.mutate({ masterId, duplicateIds })
  }, [pendingMerge, masterByGroup, mergeMutation])

  if (collectionsLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading collections..." />
        </div>
      </div>
    )
  }

  if (collectionsError) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={
            collectionsError instanceof Error ? collectionsError : new Error('An error occurred')
          }
          onRetry={() => refetchCollections()}
        />
      </div>
    )
  }

  const canScan = !!collectionName && matchFields.length > 0 && !scanMutation.isPending

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      <header>
        <h1 className="m-0 text-2xl font-semibold text-foreground">Deduplicate Records</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Scan a collection for duplicate records, then merge each group into a chosen master.
          Merging re-parents related records and deletes the duplicates.
        </p>
      </header>

      {/* Scan controls */}
      <div className="space-y-4 rounded-lg border border-border bg-card p-6">
        <div className="flex flex-col gap-2">
          <label htmlFor="dedup-collection" className="text-sm font-medium text-foreground">
            Collection
          </label>
          <select
            id="dedup-collection"
            className="w-full max-w-md rounded-md border border-border bg-background px-3 py-2.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
            value={collectionName}
            onChange={(e) => {
              setCollectionName(e.target.value)
              setMatchFields([])
              setResult(null)
            }}
            data-testid="dedup-collection-select"
          >
            <option value="">Select a collection…</option>
            {(collections ?? []).map((c) => (
              <option key={c.name} value={c.name}>
                {c.displayName || c.name}
              </option>
            ))}
          </select>
        </div>

        {collectionName && (
          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium text-foreground">Match fields</span>
            <p className="text-xs text-muted-foreground">
              Records with equal values on every selected field are grouped as duplicates.
            </p>
            <div className="flex flex-wrap gap-2" data-testid="dedup-match-fields">
              {fieldsLoading && availableFields.length === 0 && (
                <span className="text-xs text-muted-foreground">Loading fields…</span>
              )}
              {!fieldsLoading && availableFields.length === 0 && (
                <span className="text-xs text-muted-foreground">
                  This collection has no matchable fields.
                </span>
              )}
              {availableFields.map((f) => {
                const active = matchFields.includes(f.name)
                return (
                  <button
                    key={f.name}
                    type="button"
                    className={cn(
                      'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                      active
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-border bg-background text-foreground hover:bg-muted'
                    )}
                    onClick={() => toggleMatchField(f.name)}
                    aria-pressed={active}
                    data-testid={`dedup-field-${f.name}`}
                  >
                    {f.displayName || f.name}
                  </button>
                )
              })}
            </div>
          </div>
        )}

        <button
          type="button"
          className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          onClick={handleScan}
          disabled={!canScan}
          data-testid="dedup-scan-button"
        >
          {scanMutation.isPending ? 'Scanning…' : 'Find duplicates'}
        </button>
      </div>

      {/* Results */}
      {result && (
        <div className="space-y-4" data-testid="dedup-results">
          <p className="text-sm text-muted-foreground">
            Scanned {result.scanned} record(s){result.truncated ? ' (truncated)' : ''} —{' '}
            {result.groups.length} duplicate group(s).
          </p>

          {result.groups.length === 0 ? (
            <div
              className="rounded-lg border border-border bg-card p-16 text-center text-muted-foreground"
              data-testid="dedup-empty"
            >
              <p>No duplicate groups found for the selected fields.</p>
            </div>
          ) : (
            result.groups.map((group) => {
              const groupKey = group.recordIds[0]
              const masterId = masterByGroup[groupKey]
              return (
                <div
                  key={groupKey}
                  className="space-y-3 rounded-lg border border-border bg-card p-5"
                  data-testid={`dedup-group-${groupKey}`}
                >
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
                    <span className="font-semibold text-foreground">{group.count} duplicates</span>
                    {result.matchFields.map((mf) => (
                      <span key={mf} className="text-muted-foreground">
                        {mf}: <span className="text-foreground">{String(group.values[mf])}</span>
                      </span>
                    ))}
                  </div>

                  <div className="divide-y divide-border rounded-md border border-border">
                    {group.recordIds.map((id) => (
                      <label
                        key={id}
                        className="flex cursor-pointer items-center gap-3 px-3 py-2 text-sm hover:bg-muted/50"
                      >
                        <input
                          type="radio"
                          name={`master-${groupKey}`}
                          value={id}
                          checked={masterId === id}
                          onChange={() => setMasterByGroup((prev) => ({ ...prev, [groupKey]: id }))}
                          data-testid={`dedup-master-${id}`}
                        />
                        <span className="font-mono text-xs text-foreground">{id}</span>
                        {masterId === id && (
                          <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-semibold uppercase text-emerald-800 dark:bg-emerald-900 dark:text-emerald-300">
                            Master
                          </span>
                        )}
                      </label>
                    ))}
                  </div>

                  <div className="flex justify-end">
                    <button
                      type="button"
                      className="rounded-md border border-destructive px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive hover:text-destructive-foreground disabled:opacity-50"
                      onClick={() => handleMergeClick(groupKey, group)}
                      disabled={!masterId || mergeMutation.isPending}
                      data-testid={`dedup-merge-${groupKey}`}
                    >
                      Merge into master
                    </button>
                  </div>
                </div>
              )
            })
          )}
        </div>
      )}

      <ConfirmDialog
        open={pendingMerge !== null}
        title="Merge duplicate records"
        message={
          pendingMerge
            ? `Merge ${pendingMerge.group.recordIds.length - 1} record(s) into the master? ` +
              'Related records will be re-parented to the master and the duplicates permanently deleted. This cannot be undone.'
            : ''
        }
        confirmLabel="Merge"
        cancelLabel="Cancel"
        onConfirm={handleMergeConfirm}
        onCancel={() => setPendingMerge(null)}
        variant="danger"
      />
    </div>
  )
}
