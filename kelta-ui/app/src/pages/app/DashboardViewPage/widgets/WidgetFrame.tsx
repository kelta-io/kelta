import React from 'react'
import { AlertTriangle } from 'lucide-react'
import { Badge } from '../../../../components/ui/badge'
import { useI18n } from '../../../../context/I18nContext'

export interface WidgetFrameProps {
  title: string | null
  isLoading?: boolean
  error?: string
  /** Label shown as a chip when the widget opts out of the page's time range. */
  timeRangeChip?: string
  children?: React.ReactNode
}

/** Card frame every dashboard widget renders inside: title band, loading, error state. */
export function WidgetFrame({
  title,
  isLoading = false,
  error,
  timeRangeChip,
  children,
}: WidgetFrameProps) {
  const { t } = useI18n()
  return (
    <div className="flex h-full flex-col rounded-[10px] border border-border bg-card overflow-hidden">
      {(title || timeRangeChip) && (
        <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-2">
          {title && (
            <span className="truncate text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
              {title}
            </span>
          )}
          {timeRangeChip && (
            <Badge
              variant="outline"
              className="shrink-0 text-[10px]"
              data-testid="widget-time-range-chip"
            >
              {timeRangeChip}
            </Badge>
          )}
        </div>
      )}
      <div className="flex-1 p-4 overflow-auto">
        {isLoading ? (
          <div
            className="h-full min-h-16 animate-pulse rounded bg-muted/40"
            data-testid="widget-loading"
          />
        ) : error ? (
          <div
            className="flex items-start gap-2 text-sm text-muted-foreground"
            data-testid="widget-error"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" aria-hidden />
            <div>
              <div className="font-medium text-foreground">
                {t('analytics.widgetUnavailable', 'This widget is unavailable')}
              </div>
              <div className="mt-0.5">{error}</div>
            </div>
          </div>
        ) : (
          children
        )}
      </div>
    </div>
  )
}
