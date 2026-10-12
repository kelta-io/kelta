/**
 * Relative-time formatting ("5m ago", "3 hours ago", "Yesterday").
 *
 * One threshold ladder shared by every relative timestamp in the UI. The surfaces
 * that use it historically rendered slightly different wordings, so the output
 * is shaped by options rather than by forks of this function:
 *
 * - `style`        — `'short'` ("5m ago") or `'long'` ("5 minutes ago")
 * - `lowercase`    — short style only: "just now"/"yesterday" instead of "Just now"/"Yesterday"
 * - `beyondWeek`   — what to render once a timestamp is a week or more old
 *
 * Callers that need translated output (e.g. RecordHeader) use
 * {@link getRelativeTimeBucket} and map the bucket to their own i18n keys.
 */

export type RelativeTimeInput = string | number | Date

/**
 * What to render for timestamps seven or more days old:
 * - `'date'`  — the locale date (`toLocaleDateString()`)
 * - `'days'`  — keep counting days ("12d ago")
 * - `'weeks'` — whole weeks under 30 days ("2w ago"), the locale date after that
 * - `'units'` — weeks under 5, then months (30-day) under 12, then years (365-day)
 */
export type RelativeTimeBeyondWeek = 'date' | 'days' | 'weeks' | 'units'

export interface RelativeTimeBucketOptions {
  /** Report exactly one day ago as `yesterday` rather than `day` × 1. Default `true`. */
  yesterday?: boolean
  /** See {@link RelativeTimeBeyondWeek}. Default `'date'`. */
  beyondWeek?: RelativeTimeBeyondWeek
  /** Reference "now" in epoch ms. Default `Date.now()`. */
  now?: number
}

export type RelativeTimeBucket =
  | { unit: 'now' }
  | { unit: 'yesterday' }
  | { unit: 'minute' | 'hour' | 'day' | 'week' | 'month' | 'year'; count: number }
  | { unit: 'date'; date: Date }

const MINUTE_MS = 60_000
const HOUR_MS = 3_600_000
const DAY_MS = 86_400_000

function toDate(input: RelativeTimeInput): Date {
  return input instanceof Date ? input : new Date(input)
}

/**
 * Classify a timestamp into a relative-time bucket. Returns `null` for an
 * unparseable input. Future timestamps (clock skew) count as `now`.
 */
export function getRelativeTimeBucket(
  input: RelativeTimeInput,
  options: RelativeTimeBucketOptions = {}
): RelativeTimeBucket | null {
  const { yesterday = true, beyondWeek = 'date', now = Date.now() } = options
  const date = toDate(input)
  const then = date.getTime()
  if (isNaN(then)) return null

  const diffMs = now - then
  if (diffMs < MINUTE_MS) return { unit: 'now' }

  const minutes = Math.floor(diffMs / MINUTE_MS)
  if (minutes < 60) return { unit: 'minute', count: minutes }

  const hours = Math.floor(diffMs / HOUR_MS)
  if (hours < 24) return { unit: 'hour', count: hours }

  const days = Math.floor(diffMs / DAY_MS)
  if (yesterday && days === 1) return { unit: 'yesterday' }
  if (days < 7 || beyondWeek === 'days') return { unit: 'day', count: days }

  const weeks = Math.floor(days / 7)
  if (beyondWeek === 'weeks') {
    return days < 30 ? { unit: 'week', count: weeks } : { unit: 'date', date }
  }
  if (beyondWeek === 'units') {
    if (weeks < 5) return { unit: 'week', count: weeks }
    const months = Math.floor(days / 30)
    if (months < 12) return { unit: 'month', count: months }
    return { unit: 'year', count: Math.floor(days / 365) }
  }
  return { unit: 'date', date }
}

export interface RelativeTimeOptions {
  /** `'short'` → "5m ago"; `'long'` → "5 minutes ago". Default `'short'`. */
  style?: 'short' | 'long'
  /**
   * Short style only: render "just now" / "yesterday" in lowercase. Long style is
   * always lowercase and never says "yesterday" (it renders "1 day ago").
   */
  lowercase?: boolean
  /** See {@link RelativeTimeBeyondWeek}. Default `'date'`. */
  beyondWeek?: RelativeTimeBeyondWeek
  /** Reference "now" in epoch ms. Default `Date.now()`. */
  now?: number
}

const SHORT_SUFFIX = { minute: 'm', hour: 'h', day: 'd', week: 'w', month: 'mo', year: 'y' }

/**
 * Format a date as a relative time string.
 *
 * Defaults (short, capitalized, locale date after a week): "Just now", "5m ago",
 * "2h ago", "Yesterday", "3d ago", then `toLocaleDateString()`. An unparseable
 * input is returned as-is (stringified).
 *
 * @param date - ISO string, epoch milliseconds, or Date
 */
export function formatRelativeTime(
  date: RelativeTimeInput,
  options: RelativeTimeOptions = {}
): string {
  const { style = 'short', lowercase = false, beyondWeek = 'date', now } = options
  const long = style === 'long'
  const bucket = getRelativeTimeBucket(date, { yesterday: !long, beyondWeek, now })
  if (!bucket) return String(date)

  switch (bucket.unit) {
    case 'now':
      return long || lowercase ? 'just now' : 'Just now'
    case 'yesterday':
      return lowercase ? 'yesterday' : 'Yesterday'
    case 'date':
      return bucket.date.toLocaleDateString()
    default:
      return long
        ? `${bucket.count} ${bucket.unit}${bucket.count === 1 ? '' : 's'} ago`
        : `${bucket.count}${SHORT_SUFFIX[bucket.unit]} ago`
  }
}
