/**
 * RecordHistoryTab
 *
 * Content of the record detail "History" tab (collections with trackHistory
 * enabled): lists every record version newest-first; selecting a version
 * renders its snapshot through the current layout with changed-field badges
 * (RecordVersionDetail).
 */

import React, { useEffect, useMemo, useState } from 'react'
import { ArrowLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useI18n } from '@/context/I18nContext'
import type { LayoutSectionDto } from '@/hooks/usePageLayout'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import { useRecordVersions } from './useRecordVersions'
import type { RecordVersion } from './useRecordVersions'
import { RecordVersionDetail } from './RecordVersionDetail'
import { cn } from '@/lib/utils'

export interface RecordHistoryTabProps {
  collectionId: string
  recordId: string
  /** Resolved page-layout sections of the current layout (may be empty). */
  sections: LayoutSectionDto[] | undefined
  /** Collection schema fields. */
  schemaFields: FieldDefinition[]
  tenantSlug?: string
  /** Reference/lookup display map from the live record page. */
  lookupDisplayMap?: Record<string, Record<string, string>>
  /** Resolve a userId to a display name, or null when not yet resolved. */
  getUserDisplay?: (userId: string) => { name: string } | null
  /** Version number to preselect (deep link from the activity timeline). */
  initialVersion?: number
}

function changeTypeLabel(
  changeType: RecordVersion['changeType'],
  t: (key: string, params?: Record<string, string | number>) => string
): string {
  switch (changeType) {
    case 'CREATED':
      return t('history.recordCreated')
    case 'DELETED':
      return t('history.recordDeleted')
    default:
      return t('history.recordUpdated')
  }
}

export function RecordHistoryTab({
  collectionId,
  recordId,
  sections,
  schemaFields,
  tenantSlug,
  lookupDisplayMap,
  getUserDisplay,
  initialVersion,
}: RecordHistoryTabProps): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { versions, isLoading, error } = useRecordVersions(collectionId, recordId)
  const [selectedVersion, setSelectedVersion] = useState<number | null>(initialVersion ?? null)

  // Follow a late-arriving deep link (?version=N set after mount).
  useEffect(() => {
    if (initialVersion != null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelectedVersion(initialVersion)
    }
  }, [initialVersion])

  const displayNameByField = useMemo(() => {
    const map = new Map<string, string>()
    for (const field of schemaFields) {
      map.set(field.name, field.displayName || field.name)
    }
    return map
  }, [schemaFields])

  const summarize = (version: RecordVersion): string => {
    if (version.changeType !== 'UPDATED' || version.changedFields.length === 0) {
      return changeTypeLabel(version.changeType, t)
    }
    const names = version.changedFields.map((f) => displayNameByField.get(f) ?? f)
    return t('history.fieldsChangedSummary', {
      count: version.changedFields.length,
      fields: names.join(', '),
    })
  }

  const authorName = (userId: string): string => getUserDisplay?.(userId)?.name ?? userId

  const selected = useMemo(
    () => versions.find((v) => v.versionNumber === selectedVersion),
    [versions, selectedVersion]
  )

  if (isLoading) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground" data-testid="history-loading">
        {t('common.loading')}
      </div>
    )
  }

  if (error) {
    return (
      <div className="py-8 text-center text-sm text-destructive" data-testid="history-error">
        {t('history.loadError')}
      </div>
    )
  }

  if (versions.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground" data-testid="history-empty">
        {t('history.empty')}
      </div>
    )
  }

  if (selected) {
    return (
      <div className="flex flex-col gap-4">
        <div className="flex items-center justify-between gap-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setSelectedVersion(null)}
            data-testid="history-back-button"
          >
            <ArrowLeft size={14} aria-hidden="true" />
            {t('history.backToList')}
          </Button>
          <span className="text-sm text-muted-foreground">
            {t('history.versionLabel', { version: selected.versionNumber })}
            {' · '}
            {selected.changedAt &&
              formatDate(new Date(selected.changedAt), {
                year: 'numeric',
                month: 'long',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })}
            {' · '}
            {t('history.byUser', { user: authorName(selected.changedBy) })}
          </span>
        </div>
        <RecordVersionDetail
          version={selected}
          sections={sections}
          schemaFields={schemaFields}
          tenantSlug={tenantSlug}
          lookupDisplayMap={lookupDisplayMap}
        />
      </div>
    )
  }

  return (
    <ol className="flex flex-col divide-y divide-border" data-testid="record-version-list">
      {versions.map((version) => (
        <li key={version.id}>
          <button
            type="button"
            onClick={() => setSelectedVersion(version.versionNumber)}
            className={cn(
              'flex w-full items-center gap-4 px-2 py-3 text-left',
              'rounded-sm transition-colors duration-150 motion-reduce:transition-none',
              'hover:bg-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-ring'
            )}
            data-testid="record-version-row"
            data-version={version.versionNumber}
          >
            <span className="inline-flex h-7 min-w-10 items-center justify-center rounded-full bg-muted px-2 text-xs font-semibold tabular-nums text-muted-foreground">
              v{version.versionNumber}
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className="truncate text-sm text-foreground">{summarize(version)}</span>
              <span className="text-xs text-muted-foreground">
                {version.changedAt &&
                  formatDate(new Date(version.changedAt), {
                    year: 'numeric',
                    month: 'short',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                {' · '}
                {t('history.byUser', { user: authorName(version.changedBy) })}
              </span>
            </span>
            <ChevronRight size={16} className="shrink-0 text-muted-foreground" aria-hidden="true" />
          </button>
        </li>
      ))}
    </ol>
  )
}
