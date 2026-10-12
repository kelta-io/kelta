/**
 * Duration formatting.
 *
 * Two scales are in use and they read differently on purpose:
 * - {@link formatDuration} — flow execution / step run times ("850ms", "1.5s", "2.3m")
 * - {@link formatLatency}  — request latencies with sub-millisecond precision
 *   ("450µs", "12.3ms", "1.25s")
 */

/**
 * Format a run time in milliseconds: whole ms under a second, one-decimal
 * seconds under a minute, one-decimal minutes after that.
 *
 * @param ms - Duration in milliseconds; `null`/`undefined` renders `empty`
 * @param empty - Placeholder for a missing duration (default `'-'`)
 */
export function formatDuration(ms: number | null | undefined, empty = '-'): string {
  if (ms == null) return empty
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}m`
}

/**
 * Format a latency in milliseconds: whole microseconds under 1ms, one-decimal
 * milliseconds under a second, two-decimal seconds after that.
 */
export function formatLatency(ms: number): string {
  if (ms < 1) return `${(ms * 1000).toFixed(0)}µs`
  if (ms < 1000) return `${ms.toFixed(1)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}
