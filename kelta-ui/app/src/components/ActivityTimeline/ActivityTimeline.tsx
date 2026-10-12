/**
 * ActivityTimeline Component
 *
 * Displays a chronological activity feed on the record detail page.
 * Merges multiple data sources (record lifecycle, approvals, sharing)
 * into a single reverse-chronological timeline.
 *
 * Features:
 * - Color-coded timeline entries by activity type
 * - Relative time formatting for timestamps
 * - Collapsible with "Show More" / "Show Less" toggle
 * - Graceful handling of missing or erroring API endpoints
 * - Accessible with ARIA attributes
 */

import React, { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'
import { parseRecordVersion } from '@/components/RecordHistory/useRecordVersions'

import type { ApiClient } from '../../services/apiClient'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import { formatRelativeTime } from '@/utils/formatRelativeTime'

/**
 * Approval step instance from the control plane
 */
interface ApprovalStepInstance {
  id: string
  status: string
  assignedTo: string
  comments: string | null
  actedAt: string | null
}

/**
 * Approval instance from the control plane
 */
interface ApprovalInstance {
  id: string
  approvalProcessId: string
  collectionId: string
  recordId: string
  submittedBy: string
  status: string
  submittedAt: string
  completedAt: string | null
  stepInstances: ApprovalStepInstance[]
}

/**
 * Record share from the control plane
 */
interface RecordShare {
  id: string
  sharedWithId: string
  sharedWithType: string
  accessLevel: string
  reason: string
  createdAt: string
}

/**
 * Timeline entry types with visual indicators
 */
type TimelineEntryType =
  | 'CREATED'
  | 'UPDATED'
  | 'FIELD_CHANGE'
  | 'VERSION'
  | 'NOTE'
  | 'ATTACHMENT'
  | 'APPROVAL_SUBMITTED'
  | 'APPROVAL_APPROVED'
  | 'APPROVAL_REJECTED'
  | 'APPROVAL_RECALLED'
  | 'SHARED'
  | 'FLOW_RUN'
  | 'EMAIL'

/**
 * Note on the record (from the notes system collection)
 */
interface ActivityNote {
  id: string
  content: string
  createdAt: string
}

/**
 * Attachment on the record (from the attachments system collection)
 */
interface ActivityAttachment {
  id: string
  fileName: string
  uploadedAt: string
}

/**
 * Flow execution triggered by this record (from /api/flows/record-executions)
 */
interface ActivityFlowRun {
  id: string
  flowId: string
  flowName: string | null
  status: string
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  errorMessage: string | null
}

/**
 * Email log entry sourced from this record (from /api/email/logs)
 */
interface ActivityEmailLog {
  id: string
  recipientEmail: string
  subject: string
  status: string
  sentAt: string | null
  createdAt: string
}

/**
 * A tracked field value change on this record (from the field-history system collection).
 * {@code oldValue}/{@code newValue} arrive already redacted by the backend when the field is
 * masked/FLS-restricted for the caller, so they can be rendered verbatim.
 */
interface ActivityFieldChange {
  id: string
  fieldName: string
  oldValue: unknown
  newValue: unknown
  changedBy: string
  changedAt: string
  changeSource: string
}

/**
 * Internal timeline entry for rendering
 */
interface TimelineEntry {
  id: string
  type: TimelineEntryType
  description: string
  timestamp: string
  /** Set on VERSION entries — click-through target for the History tab. */
  versionNumber?: number
  /** Set on VERSION entries — author display name, rendered on its own line. */
  user?: string
}

/**
 * Props for the ActivityTimeline component
 */
export interface ActivityTimelineProps {
  /** UUID of the collection */
  collectionId: string
  /** Name of the collection */
  collectionName: string
  /** UUID of the record */
  recordId: string
  /** ISO timestamp when the record was created */
  recordCreatedAt?: string
  /** ISO timestamp when the record was last updated */
  recordUpdatedAt?: string
  /** Authenticated API client instance */
  apiClient: ApiClient
  /**
   * Collection-level record versioning is on — fetch record versions and render
   * one entry per version (suppressing the synthesized created/updated entries,
   * which the version feed covers with more detail + author).
   */
  historyEnabled?: boolean
  /** Collection schema fields, for changed-field display names on version entries. */
  schemaFields?: FieldDefinition[]
  /** Resolve a userId to a display name (version entry author). */
  getUserDisplay?: (userId: string) => { name: string } | null
  /** Open the History tab at a version (version entry click-through). */
  onOpenHistory?: (versionNumber: number) => void
}

/**
 * Default number of entries to show before requiring "Show More"
 */
const DEFAULT_VISIBLE_COUNT = 5

/**
 * Get Tailwind classes for a timeline entry type icon
 */
function getIconClasses(type: TimelineEntryType): string {
  const classMap: Record<TimelineEntryType, string> = {
    CREATED: 'bg-green-50 text-green-700 border-2 border-green-700',
    UPDATED: 'bg-blue-50 text-blue-700 border-2 border-blue-700',
    FIELD_CHANGE: 'bg-blue-50 text-blue-700 border-2 border-blue-700',
    VERSION: 'bg-blue-50 text-blue-700 border-2 border-blue-700',
    NOTE: 'bg-purple-50 text-purple-700 border-2 border-purple-700',
    ATTACHMENT: 'bg-teal-50 text-teal-700 border-2 border-teal-700',
    APPROVAL_SUBMITTED: 'bg-amber-50 text-amber-700 border-2 border-amber-700',
    APPROVAL_APPROVED: 'bg-green-50 text-green-700 border-2 border-green-700',
    APPROVAL_REJECTED: 'bg-red-50 text-red-700 border-2 border-red-700',
    APPROVAL_RECALLED: 'bg-muted text-muted-foreground border-2 border-muted-foreground',
    SHARED: 'bg-blue-50 text-blue-700 border-2 border-blue-700',
    FLOW_RUN: 'bg-indigo-50 text-indigo-700 border-2 border-indigo-700',
    EMAIL: 'bg-sky-50 text-sky-700 border-2 border-sky-700',
  }
  return classMap[type] || ''
}

/**
 * Get the icon symbol for a timeline entry type
 */
function getIconSymbol(type: TimelineEntryType): string {
  const symbolMap: Record<TimelineEntryType, string> = {
    CREATED: '+',
    UPDATED: '~',
    FIELD_CHANGE: '~',
    VERSION: '~',
    NOTE: '✎',
    ATTACHMENT: '📎',
    APPROVAL_SUBMITTED: '!',
    APPROVAL_APPROVED: '\u2713',
    APPROVAL_REJECTED: '\u2717',
    APPROVAL_RECALLED: '\u21A9',
    SHARED: '\u21C4',
    FLOW_RUN: '\u25B6',
    EMAIL: '\u2709',
  }
  return symbolMap[type] || '\u2022'
}

/**
 * Map an approval instance status to the corresponding timeline entry type
 */
function approvalStatusToEntryType(status: string): TimelineEntryType {
  const statusUpper = status.toUpperCase()
  if (statusUpper === 'APPROVED') return 'APPROVAL_APPROVED'
  if (statusUpper === 'REJECTED') return 'APPROVAL_REJECTED'
  if (statusUpper === 'RECALLED') return 'APPROVAL_RECALLED'
  return 'APPROVAL_SUBMITTED'
}

/**
 * Render a field-history value for the feed. Null/empty renders as an em dash; objects/arrays are
 * JSON-stringified; everything else uses its string form. Long values are truncated.
 */
function formatFieldValue(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const text = typeof value === 'object' ? JSON.stringify(value) : String(value)
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}

/**
 * ActivityTimeline Component
 *
 * Displays a merged, reverse-chronological activity feed combining
 * record lifecycle events, approval events, and sharing changes.
 *
 * @example
 * ```tsx
 * <ActivityTimeline
 *   collectionId="abc-123"
 *   collectionName="accounts"
 *   recordId="def-456"
 *   recordCreatedAt="2025-01-15T10:00:00Z"
 *   recordUpdatedAt="2025-01-16T14:30:00Z"
 *   apiClient={apiClient}
 * />
 * ```
 */
export function ActivityTimeline({
  collectionId,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  collectionName: _collectionName,
  recordId,
  recordCreatedAt,
  recordUpdatedAt,
  apiClient,
  historyEnabled = false,
  schemaFields,
  getUserDisplay,
  onOpenHistory,
}: ActivityTimelineProps): React.ReactElement {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)

  // Fetch approval instances for the record
  const { data: approvalInstances } = useQuery({
    queryKey: ['activity-approvals', collectionId, recordId],
    queryFn: async () => {
      try {
        // Server-side filter (matches every sibling fetch below) — the previous
        // list-everything + client-filter shape pulled the whole tenant's instances.
        const instances = await apiClient.getList<ApprovalInstance>(
          `/api/approval-instances?filter[collectionId][eq]=${encodeURIComponent(collectionId)}` +
            `&filter[recordId][eq]=${encodeURIComponent(recordId)}`
        )
        return instances || []
      } catch {
        // Gracefully handle 404 or other errors
        return []
      }
    },
    enabled: !!collectionId && !!recordId,
  })

  // Fetch sharing records for this record
  const { data: shares } = useQuery({
    queryKey: ['activity-shares', collectionId, recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<RecordShare>(
          `/api/record-shares?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}`
        )
        return result || []
      } catch {
        // Gracefully handle 404 or other errors
        return []
      }
    },
    enabled: !!collectionId && !!recordId,
  })

  // Fetch notes for this record (the notes system collection)
  const { data: notes } = useQuery({
    queryKey: ['activity-notes', collectionId, recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<ActivityNote>(
          `/api/notes?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}`
        )
        return result || []
      } catch {
        return []
      }
    },
    enabled: !!collectionId && !!recordId,
  })

  // Fetch attachments for this record (the attachments system collection)
  const { data: attachments } = useQuery({
    queryKey: ['activity-attachments', collectionId, recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<ActivityAttachment>(
          `/api/attachments?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}`
        )
        return result || []
      } catch {
        return []
      }
    },
    enabled: !!collectionId && !!recordId,
  })

  // Fetch flow executions triggered by this record
  const { data: flowRuns } = useQuery({
    queryKey: ['activity-flow-runs', recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<ActivityFlowRun>(
          `/api/flows/record-executions?recordId=${recordId}&limit=20`
        )
        return result || []
      } catch {
        return []
      }
    },
    enabled: !!recordId,
  })

  // Fetch email logs sourced from this record
  const { data: emailLogs } = useQuery({
    queryKey: ['activity-email-logs', recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<ActivityEmailLog>(
          `/api/email/logs?recordId=${recordId}&limit=20`
        )
        return result || []
      } catch {
        return []
      }
    },
    enabled: !!recordId,
  })

  // Fetch record versions (collection-level tracking). Snapshots arrive already
  // FLS-filtered by the backend guard; only summary data is rendered here.
  const { data: recordVersions } = useQuery({
    queryKey: ['activity-record-versions', collectionId, recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<Parameters<typeof parseRecordVersion>[0]>(
          `/api/record-versions?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}&page[size]=50`
        )
        return (result || []).map(parseRecordVersion)
      } catch {
        return []
      }
    },
    enabled: historyEnabled && !!collectionId && !!recordId,
  })

  // Fetch tracked field-value changes for this record. Values arrive already
  // redacted by the backend guard when a field is masked/FLS-restricted.
  const { data: fieldChanges } = useQuery({
    queryKey: ['activity-field-history', collectionId, recordId],
    queryFn: async () => {
      try {
        const result = await apiClient.getList<ActivityFieldChange>(
          `/api/field-history?filter[collectionId][eq]=${collectionId}&filter[recordId][eq]=${recordId}&page[size]=50`
        )
        return result || []
      } catch {
        return []
      }
    },
    enabled: !!collectionId && !!recordId,
  })

  // Merge all data sources into a single timeline
  const entries: TimelineEntry[] = useMemo(() => {
    const allEntries: TimelineEntry[] = []

    // Version entries carry created/updated (plus author + changed fields), so
    // the client-synthesized entries would be duplicates.
    const suppressSynthesized = historyEnabled && (recordVersions?.length ?? 0) > 0

    // Record creation event
    if (recordCreatedAt && !suppressSynthesized) {
      allEntries.push({
        id: 'record-created',
        type: 'CREATED',
        description: t('activity.recordCreated'),
        timestamp: recordCreatedAt,
      })
    }

    // Record update event (only if different from created)
    if (
      recordUpdatedAt &&
      recordCreatedAt &&
      recordUpdatedAt !== recordCreatedAt &&
      !suppressSynthesized
    ) {
      allEntries.push({
        id: 'record-updated',
        type: 'UPDATED',
        description: t('activity.recordUpdated'),
        timestamp: recordUpdatedAt,
      })
    }

    // Record versions — one entry per version, including the author
    if (recordVersions) {
      const displayNames = new Map<string, string>()
      for (const field of schemaFields ?? []) {
        displayNames.set(field.name, field.displayName || field.name)
      }
      for (const version of recordVersions) {
        const user = getUserDisplay?.(version.changedBy)?.name ?? version.changedBy
        let description: string
        if (version.changeType === 'CREATED') {
          description = t('activity.versionCreated')
        } else if (version.changeType === 'DELETED') {
          description = t('activity.versionDeleted')
        } else {
          const fields = version.changedFields.map((f) => displayNames.get(f) ?? f).join(', ')
          description =
            version.changedFields.length === 1
              ? t('activity.versionUpdatedOne', { fields })
              : t('activity.versionUpdated', { count: version.changedFields.length, fields })
        }
        allEntries.push({
          id: `version-${version.id}`,
          type: 'VERSION',
          description,
          timestamp: version.changedAt,
          versionNumber: version.versionNumber,
          user,
        })
      }
    }

    // Approval events
    if (approvalInstances) {
      for (const instance of approvalInstances) {
        // Submitted event
        allEntries.push({
          id: `approval-submitted-${instance.id}`,
          type: 'APPROVAL_SUBMITTED',
          description: t('activity.approvalSubmitted'),
          timestamp: instance.submittedAt,
        })

        // Completed event (approved, rejected, recalled)
        if (
          instance.completedAt &&
          instance.status.toUpperCase() !== 'PENDING' &&
          instance.status.toUpperCase() !== 'SUBMITTED'
        ) {
          const entryType = approvalStatusToEntryType(instance.status)
          const descriptionKey =
            entryType === 'APPROVAL_APPROVED'
              ? 'activity.approvalApproved'
              : entryType === 'APPROVAL_REJECTED'
                ? 'activity.approvalRejected'
                : 'activity.approvalRecalled'

          allEntries.push({
            id: `approval-${instance.status.toLowerCase()}-${instance.id}`,
            type: entryType,
            description: t(descriptionKey),
            timestamp: instance.completedAt,
          })
        }
      }
    }

    // Tracked field-value changes
    if (fieldChanges) {
      for (const change of fieldChanges) {
        const oldEmpty = change.oldValue === null || change.oldValue === undefined
        const newEmpty = change.newValue === null || change.newValue === undefined
        let description: string
        if (oldEmpty && !newEmpty) {
          description = t('activity.fieldSet', {
            field: change.fieldName,
            value: formatFieldValue(change.newValue),
          })
        } else if (!oldEmpty && newEmpty) {
          description = t('activity.fieldCleared', {
            field: change.fieldName,
            value: formatFieldValue(change.oldValue),
          })
        } else {
          description = t('activity.fieldChanged', {
            field: change.fieldName,
            from: formatFieldValue(change.oldValue),
            to: formatFieldValue(change.newValue),
          })
        }
        allEntries.push({
          id: `field-change-${change.id}`,
          type: 'FIELD_CHANGE',
          description,
          timestamp: change.changedAt,
        })
      }
    }

    // Note events (content is user text; truncate for the feed)
    if (notes) {
      for (const note of notes) {
        const content = note.content ?? ''
        const preview = content.length > 140 ? `${content.slice(0, 140)}…` : content
        allEntries.push({
          id: `note-${note.id}`,
          type: 'NOTE',
          description: preview || t('activity.noteAdded'),
          timestamp: note.createdAt,
        })
      }
    }

    // Attachment events
    if (attachments) {
      for (const attachment of attachments) {
        allEntries.push({
          id: `attachment-${attachment.id}`,
          type: 'ATTACHMENT',
          description: `${t('activity.attachmentAdded')}: ${attachment.fileName}`,
          timestamp: attachment.uploadedAt,
        })
      }
    }

    // Flow run events
    if (flowRuns) {
      for (const run of flowRuns) {
        allEntries.push({
          id: `flow-run-${run.id}`,
          type: 'FLOW_RUN',
          description: t('activity.flowRun', {
            name: run.flowName || run.flowId,
            status: run.status,
          }),
          timestamp: run.startedAt,
        })
      }
    }

    // Email events
    if (emailLogs) {
      for (const email of emailLogs) {
        allEntries.push({
          id: `email-${email.id}`,
          type: 'EMAIL',
          description: t('activity.emailSent', {
            subject: email.subject,
            recipient: email.recipientEmail,
            status: email.status,
          }),
          timestamp: email.sentAt ?? email.createdAt,
        })
      }
    }

    // Sharing events
    if (shares) {
      for (const share of shares) {
        allEntries.push({
          id: `share-${share.id}`,
          type: 'SHARED',
          description: t('activity.sharedWith', {
            id: share.sharedWithId,
            accessLevel: share.accessLevel,
          }),
          timestamp: share.createdAt,
        })
      }
    }

    // Sort reverse chronological (newest first)
    allEntries.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())

    return allEntries
  }, [
    recordCreatedAt,
    recordUpdatedAt,
    approvalInstances,
    shares,
    notes,
    attachments,
    flowRuns,
    emailLogs,
    fieldChanges,
    recordVersions,
    historyEnabled,
    schemaFields,
    getUserDisplay,
    t,
  ])

  const visibleEntries = expanded ? entries : entries.slice(0, DEFAULT_VISIBLE_COUNT)
  const hasMore = entries.length > DEFAULT_VISIBLE_COUNT
  const totalCount = entries.length

  return (
    <section
      className="bg-background border border-border rounded-lg overflow-hidden"
      aria-labelledby="activity-timeline-heading"
      data-testid="activity-timeline"
    >
      {/* Header */}
      <div className="flex justify-between items-center p-4 border-b border-border bg-muted/50 max-md:flex-col max-md:items-start max-md:gap-1">
        <h3 id="activity-timeline-heading" className="m-0 text-lg font-semibold text-foreground">
          {t('activity.title')}
        </h3>
        <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium rounded bg-background text-muted-foreground">
          {totalCount}
        </span>
      </div>

      {/* Timeline or Empty State */}
      {entries.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center px-6 py-8 text-center"
          data-testid="activity-timeline-empty"
        >
          <p className="m-0 text-sm text-muted-foreground">{t('activity.empty')}</p>
        </div>
      ) : (
        <div className="relative p-4" role="list" aria-label={t('activity.title')}>
          {visibleEntries.map((entry, index) => (
            <div
              key={entry.id}
              className={cn(
                'flex items-start gap-4 relative max-md:gap-2',
                index < visibleEntries.length - 1 ? 'pb-4' : 'pb-0'
              )}
              role="listitem"
              data-testid={`activity-entry-${entry.id}`}
            >
              {/* Icon */}
              <div
                className={cn(
                  'relative flex items-center justify-center w-8 h-8 rounded-full shrink-0 text-sm z-[1]',
                  'max-md:w-7 max-md:h-7 max-md:text-xs',
                  getIconClasses(entry.type)
                )}
                aria-hidden="true"
              >
                {getIconSymbol(entry.type)}
              </div>

              {/* Connector line */}
              {index < visibleEntries.length - 1 && (
                <div
                  className="absolute left-[15px] top-8 bottom-0 w-0.5 bg-border max-md:left-[13px] max-md:top-7"
                  aria-hidden="true"
                />
              )}

              {/* Content */}
              <div className="flex flex-col gap-1 pt-1 min-w-0 flex-1">
                {entry.type === 'VERSION' && entry.versionNumber != null && onOpenHistory ? (
                  <button
                    type="button"
                    className="m-0 cursor-pointer border-0 bg-transparent p-0 text-left text-sm font-medium leading-snug text-foreground hover:text-primary hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    onClick={() => onOpenHistory(entry.versionNumber!)}
                    data-testid="activity-version-link"
                  >
                    {entry.description}
                  </button>
                ) : (
                  <p className="m-0 text-sm font-medium text-foreground leading-snug">
                    {entry.description}
                  </p>
                )}
                <p className="m-0 text-xs text-muted-foreground leading-snug">
                  <time dateTime={entry.timestamp}>
                    {formatRelativeTime(entry.timestamp, { style: 'long', beyondWeek: 'units' })}
                  </time>
                </p>
                {entry.user && (
                  <p
                    className="m-0 text-xs text-muted-foreground leading-snug break-all"
                    data-testid="activity-entry-user"
                  >
                    {entry.user}
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Show More / Show Less toggle */}
      {hasMore && (
        <button
          type="button"
          className={cn(
            'flex items-center justify-center w-full px-4 py-2 m-0',
            'bg-transparent border-0 border-t border-border',
            'text-sm font-medium text-primary cursor-pointer',
            'transition-colors motion-reduce:transition-none',
            'hover:bg-muted/50',
            'focus:outline-2 focus:outline-primary focus:-outline-offset-2'
          )}
          onClick={() => setExpanded((prev) => !prev)}
          aria-expanded={expanded}
          data-testid="activity-timeline-toggle"
        >
          {expanded
            ? t('activity.showLess')
            : t('activity.showMore', {
                count: String(entries.length - DEFAULT_VISIBLE_COUNT),
              })}
        </button>
      )}
    </section>
  )
}

export default ActivityTimeline
