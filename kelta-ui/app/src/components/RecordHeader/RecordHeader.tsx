/**
 * RecordHeader Component
 *
 * A compact, information-dense header for the record detail page.
 * Displays the record name, collection badge, key field pills,
 * relative timestamps, and a copyable record ID.
 *
 * This is a pure display component — it makes no API calls.
 */

import React, { useState, useMemo, useCallback, useEffect } from 'react'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { getRelativeTimeBucket } from '@/utils/formatRelativeTime'

/**
 * Props for the RecordHeader component
 */
export interface RecordHeaderProps {
  /** The record data including id, created_at, updated_at, and field values */
  record: Record<string, unknown> & {
    id: string
    createdAt?: string
    updatedAt?: string
    created_at?: string
    updated_at?: string
  }
  /** The schema definition for the record's collection */
  schema: {
    name: string
    displayName: string
    fields: Array<{
      name: string
      displayName?: string
      type: string
      required: boolean
      referenceTarget?: string
    }>
  }
  /** The API name of the collection */
  collectionName: string
}

/**
 * Maximum number of key fields to display
 */
const MAX_KEY_FIELDS = 4

/**
 * Duration in milliseconds to show the "Copied!" tooltip
 */
const COPIED_TOOLTIP_DURATION = 2000

/**
 * Status-like field names that get priority in key field selection
 */
const STATUS_FIELD_NAMES = new Set(['status', 'state', 'stage'])

/**
 * Format a date string as a relative time (e.g., "3 hours ago", "just now").
 *
 * @param dateString - ISO 8601 date string
 * @param t - Translation function from i18n context
 * @returns A human-readable relative time string
 */
function formatRelativeTime(
  dateString: string,
  t: (key: string, params?: Record<string, string | number>) => string
): string {
  // Days keep counting (no "yesterday", no fallback to a date) in the header.
  const bucket = getRelativeTimeBucket(dateString, { yesterday: false, beyondWeek: 'days' })
  switch (bucket?.unit) {
    case 'now':
      return t('recordHeader.justNow')
    case 'minute':
      return t('recordHeader.minutesAgo', { count: bucket.count })
    case 'hour':
      return t('recordHeader.hoursAgo', { count: bucket.count })
    case 'day':
      return t('recordHeader.daysAgo', { count: bucket.count })
    default:
      // Unparseable timestamp (other buckets are unreachable with these options).
      return dateString
  }
}

/**
 * RecordHeader Component
 *
 * Renders a compact header at the top of the record detail page with:
 * - Record name (derived from the first string field or record ID)
 * - Collection badge
 * - Up to 4 key field pills
 * - Relative timestamps for creation and last update
 * - Copyable record ID with tooltip feedback
 *
 * @example
 * ```tsx
 * <RecordHeader
 *   record={recordData}
 *   schema={collectionSchema}
 *   collectionName="accounts"
 * />
 * ```
 */
export function RecordHeader({
  record,
  schema,
  collectionName,
}: RecordHeaderProps): React.ReactElement {
  const { t } = useI18n()
  const [copied, setCopied] = useState(false)

  // Auto-reset the copied state after the tooltip duration
  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => {
      setCopied(false)
    }, COPIED_TOOLTIP_DURATION)
    return () => clearTimeout(timer)
  }, [copied])

  /**
   * Determine the record's display name.
   * Uses the value of the first string-type field from the schema.
   * Falls back to the record ID if no suitable field is found.
   */
  const fields = useMemo(() => (Array.isArray(schema.fields) ? schema.fields : []), [schema.fields])

  const recordName = useMemo(() => {
    const firstStringField = fields.find((field) => field.type.toLowerCase() === 'string')

    if (firstStringField) {
      const value = record[firstStringField.name]
      if (value !== null && value !== undefined && String(value).trim() !== '') {
        return String(value)
      }
    }

    return record.id
  }, [fields, record])

  /**
   * Select up to 4 key fields to display as pills.
   *
   * Priority order:
   * 1. Picklist fields named "status", "state", or "stage"
   * 2. Reference fields
   * 3. Date or datetime fields
   * 4. Remaining non-name fields in schema order
   */
  const keyFields = useMemo(() => {
    // Find the name field so we can exclude it from key fields
    const nameField = fields.find((field) => field.type.toLowerCase() === 'string')
    const nameFieldName = nameField?.name

    // Filter out the name field and fields without values
    const candidateFields = fields.filter((field) => {
      if (field.name === nameFieldName) return false
      if (field.name === 'id') return false
      const value = record[field.name]
      return value !== null && value !== undefined && String(value).trim() !== ''
    })

    // Priority buckets
    const statusPicklists: typeof candidateFields = []
    const references: typeof candidateFields = []
    const dates: typeof candidateFields = []
    const others: typeof candidateFields = []

    for (const field of candidateFields) {
      const typeLower = field.type.toLowerCase()
      const nameLower = field.name.toLowerCase()

      if (typeLower.includes('picklist') && STATUS_FIELD_NAMES.has(nameLower)) {
        statusPicklists.push(field)
      } else if (typeLower === 'reference') {
        references.push(field)
      } else if (typeLower === 'date' || typeLower === 'datetime') {
        dates.push(field)
      } else {
        others.push(field)
      }
    }

    const prioritized = [...statusPicklists, ...references, ...dates, ...others]
    return prioritized.slice(0, MAX_KEY_FIELDS)
  }, [fields, record])

  /**
   * Copy the record ID to the clipboard and show a brief tooltip.
   */
  const handleCopyId = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(record.id)
      setCopied(true)
    } catch {
      // Clipboard API may not be available in all contexts
      console.warn('[RecordHeader] Failed to copy record ID to clipboard')
    }
  }, [record.id])

  /**
   * Format a field value for display in key field pills.
   */
  const formatFieldValue = useCallback(
    (field: (typeof schema.fields)[number]): string => {
      const value = record[field.name]
      if (value === null || value === undefined) return ''

      const typeLower = field.type.toLowerCase()

      if (typeLower === 'date' || typeLower === 'datetime') {
        try {
          const date = new Date(String(value))
          if (!isNaN(date.getTime())) {
            return typeLower === 'date' ? date.toLocaleDateString() : date.toLocaleString()
          }
        } catch {
          // Fall through to default
        }
      }

      return String(value)
    },
    [record, schema]
  )

  return (
    <div className="bg-muted/50 border-b px-8 py-6 max-md:px-4" data-testid="record-header">
      {/* Top row: name + collection badge */}
      <div className="flex items-center gap-4 flex-wrap max-md:flex-col max-md:items-start max-md:gap-2">
        <h1
          className="m-0 text-2xl font-bold text-foreground leading-tight max-md:text-xl"
          data-testid="record-header-name"
        >
          {recordName}
        </h1>
        <Badge variant="secondary" data-testid="record-header-collection">
          {schema.displayName || collectionName}
        </Badge>
      </div>

      {/* Key fields */}
      {keyFields.length > 0 && (
        <div className="flex flex-wrap gap-2 mt-4" data-testid="record-header-key-fields">
          {keyFields.map((field) => (
            <span
              key={field.name}
              className="inline-flex items-center gap-1 px-2 py-1 bg-background border border-border rounded text-sm leading-snug"
              data-testid={`record-header-field-${field.name}`}
            >
              <span className="text-muted-foreground text-xs font-medium whitespace-nowrap">
                {field.displayName || field.name}:
              </span>
              <span className="text-foreground font-medium whitespace-nowrap max-w-[200px] max-md:max-w-[150px] overflow-hidden text-ellipsis">
                {formatFieldValue(field)}
              </span>
            </span>
          ))}
        </div>
      )}

      {/* Bottom row: timestamps + record ID */}
      <div className="flex items-center justify-between flex-wrap gap-2 mt-4 max-md:flex-col max-md:items-start">
        <span
          className="text-xs text-muted-foreground leading-snug"
          data-testid="record-header-timestamps"
        >
          {(record.created_at || record.createdAt) && (
            <>
              {t('recordHeader.created')}{' '}
              {formatRelativeTime((record.created_at || record.createdAt)!, t)}
            </>
          )}
          {(record.created_at || record.createdAt) &&
            (record.updated_at || record.updatedAt) &&
            ' \u00B7 '}
          {(record.updated_at || record.updatedAt) && (
            <>
              {t('recordHeader.updated')}{' '}
              {formatRelativeTime((record.updated_at || record.updatedAt)!, t)}
            </>
          )}
        </span>

        <button
          type="button"
          className={cn(
            'relative inline-flex items-center gap-1 font-mono text-xs text-muted-foreground',
            'cursor-pointer px-1 py-0.5 rounded border border-transparent bg-transparent',
            'transition-all motion-reduce:transition-none',
            'hover:bg-background hover:border-border hover:text-muted-foreground/80',
            'focus:outline-2 focus:outline-primary focus:outline-offset-2'
          )}
          onClick={handleCopyId}
          title={t('recordHeader.copyId')}
          aria-label={t('recordHeader.copyId')}
          data-testid="record-header-id"
        >
          {record.id}
          {copied && (
            <span
              className={cn(
                'absolute bottom-[calc(100%+4px)] left-1/2 -translate-x-1/2',
                'px-2 py-0.5 text-xs font-medium text-primary-foreground bg-foreground',
                'rounded whitespace-nowrap pointer-events-none',
                'animate-in fade-in slide-in-from-bottom-1 motion-reduce:animate-none'
              )}
              role="status"
              aria-live="polite"
            >
              {t('recordHeader.copied')}
            </span>
          )}
        </button>
      </div>
    </div>
  )
}
