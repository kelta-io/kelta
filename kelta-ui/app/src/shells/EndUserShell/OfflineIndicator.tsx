/**
 * OfflineIndicator (Phase 4 slice 1)
 *
 * Surfaces the offline outbox in the end-user shell:
 *  - offline: amber banner with the queued-change count;
 *  - online with retained failed replays: amber banner with the failed count
 *    (failed ops no longer vanish silently — see SyncEngine.push);
 *  - a Details toggle expands a panel listing pending and failed ops with per-op
 *    Retry / Discard for failures.
 *
 * Renders nothing when online with an empty outbox — the common case costs one
 * hook subscription and no DOM.
 */

import React, { useState } from 'react'
import { WifiOff, AlertTriangle, RotateCcw, Trash2 } from 'lucide-react'
import { useI18n } from '@/context/I18nContext'
import { useOnlineStatus, useOfflineOutbox } from '@/offline'

export function OfflineIndicator(): React.ReactElement | null {
  const online = useOnlineStatus()
  const { t } = useI18n()
  const { pending, failed, pendingCount, failedCount, retry, discard } = useOfflineOutbox()
  const [expanded, setExpanded] = useState(false)

  if (online && failedCount === 0 && pendingCount === 0) return null

  const showDetailsToggle = pendingCount > 0 || failedCount > 0

  return (
    <div data-testid="offline-indicator">
      <div
        role="status"
        aria-live="polite"
        className="flex items-center justify-center gap-2 bg-amber-500/15 px-4 py-1.5 text-center text-xs font-medium text-amber-700 dark:text-amber-300"
      >
        {online ? (
          <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        ) : (
          <WifiOff className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        )}
        <span data-testid="offline-banner-text">
          {!online
            ? pendingCount > 0
              ? t('offline.offlineQueued', { count: pendingCount })
              : t(
                  'offline.offline',
                  "You're offline — showing saved data; changes sync when you reconnect."
                )
            : t('offline.failedBanner', { count: failedCount })}
        </span>
        {showDetailsToggle && (
          <button
            type="button"
            className="underline underline-offset-2 hover:opacity-80"
            onClick={() => setExpanded((v) => !v)}
            data-testid="offline-details-toggle"
          >
            {expanded ? t('offline.hideDetails', 'Hide') : t('offline.details', 'Details')}
          </button>
        )}
      </div>

      {expanded && (
        <div
          className="border-b border-border bg-card px-4 py-2 text-xs"
          data-testid="offline-outbox-panel"
        >
          {pending.map((op) => (
            <div
              key={op.id}
              className="flex items-center gap-3 py-1"
              data-testid={`outbox-pending-${op.id}`}
            >
              <span className="font-medium">{op.op}</span>
              <span className="text-muted-foreground">
                {op.collection}
                {op.recordId ? `/${op.recordId}` : ''}
              </span>
              <span className="ml-auto text-muted-foreground">
                {t('offline.pendingSync', 'pending sync')}
              </span>
            </div>
          ))}
          {failed.map((op) => (
            <div
              key={op.id}
              className="flex items-center gap-3 py-1"
              data-testid={`outbox-failed-${op.id}`}
            >
              <span className="font-medium">{op.op}</span>
              <span className="text-muted-foreground">
                {op.collection}
                {op.recordId ? `/${op.recordId}` : ''}
              </span>
              <span className="truncate text-destructive" title={op.error}>
                {op.status ? `${op.status} — ` : ''}
                {op.error}
              </span>
              <span className="ml-auto flex items-center gap-1">
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded border border-border px-1.5 py-0.5 hover:bg-accent"
                  onClick={() => void retry(op.id)}
                  data-testid={`outbox-retry-${op.id}`}
                >
                  <RotateCcw className="h-3 w-3" aria-hidden />
                  {t('offline.retry', 'Retry')}
                </button>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded border border-border px-1.5 py-0.5 text-destructive hover:bg-destructive/10"
                  onClick={() => void discard(op.id)}
                  data-testid={`outbox-discard-${op.id}`}
                >
                  <Trash2 className="h-3 w-3" aria-hidden />
                  {t('offline.discard', 'Discard')}
                </button>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
