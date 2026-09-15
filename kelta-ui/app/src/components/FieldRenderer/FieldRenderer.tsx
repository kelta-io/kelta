/**
 * FieldRenderer Component
 *
 * A unified component that renders any field type in view mode.
 * Supports all 21+ field types with appropriate formatting, icons,
 * and accessibility attributes.
 *
 * View mode renders read-only field values with type-specific formatting.
 * Edit mode will be added in Phase 4.
 */

import React from 'react'
import { Link } from 'react-router-dom'
import {
  Mail,
  Phone,
  ExternalLink,
  MapPin,
  Lock,
  Hash,
  Calculator,
  Check,
  X,
  Copy,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { EmptyValue } from '@/components/kelta'
import { cn } from '@/lib/utils'
import { componentRegistry } from '@/services/componentRegistry'
import { PluginErrorBoundary } from '@/components/PluginErrorBoundary'
import type { FieldType } from '@/hooks/useCollectionSchema'

export interface FieldRendererProps {
  /** Field type */
  type: FieldType
  /** Field value */
  value: unknown
  /** Field name (for accessibility) */
  fieldName?: string
  /** Display name (for accessibility) */
  displayName?: string
  /** For lookup/reference fields: tenant slug for building links */
  tenantSlug?: string
  /** For lookup/reference fields: target collection name */
  targetCollection?: string
  /** For lookup/reference fields: resolved display label */
  displayLabel?: string
  /** Additional CSS class */
  className?: string
  /** Whether to truncate long text values */
  truncate?: boolean
  /**
   * For picklist/multi_picklist: raw stored value → authored `{label, color}` (see
   * `usePicklistDisplayMap`). A value with no entry, or an entry with no color, renders exactly
   * as before (raw value, neutral badge) — this is presentation-only, the stored value never
   * changes.
   */
  picklistDisplayMap?: Map<string, { label: string; color?: string }>
}

/** Parse `#rgb`/`#rrggbb` into 0-255 channels, or null if not a recognizable hex color. */
function parseHexColor(hex: string): [number, number, number] | null {
  const short = /^#?([a-f\d])([a-f\d])([a-f\d])$/i.exec(hex.trim())
  if (short) {
    return [
      parseInt(short[1] + short[1], 16),
      parseInt(short[2] + short[2], 16),
      parseInt(short[3] + short[3], 16),
    ]
  }
  const long = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex.trim())
  if (!long) return null
  return [parseInt(long[1], 16), parseInt(long[2], 16), parseInt(long[3], 16)]
}

/** WCAG relative luminance of an sRGB color (0-255 channels). */
function relativeLuminance([r, g, b]: [number, number, number]): number {
  const [rl, gl, bl] = [r, g, b].map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
}

/** WCAG contrast ratio between two relative luminances. */
function contrastRatio(l1: number, l2: number): number {
  const lighter = Math.max(l1, l2)
  const darker = Math.min(l1, l2)
  return (lighter + 0.05) / (darker + 0.05)
}

/** Pick whichever of black/white gives the better WCAG AA contrast against a hex background. */
function contrastingTextColor(bgHex: string): string {
  const rgb = parseHexColor(bgHex)
  if (!rgb) return '#000000'
  const bgLuminance = relativeLuminance(rgb)
  const whiteContrast = contrastRatio(bgLuminance, 1)
  const blackContrast = contrastRatio(bgLuminance, 0)
  return whiteContrast >= blackContrast ? '#ffffff' : '#000000'
}

/**
 * Format a number with locale-aware formatting.
 */
function formatNumber(value: unknown): string {
  if (typeof value === 'number') {
    return new Intl.NumberFormat().format(value)
  }
  return String(value ?? '')
}

/**
 * Format a currency value.
 */
function formatCurrency(value: unknown): string {
  if (typeof value === 'number') {
    return value.toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
  }
  return String(value ?? '')
}

/**
 * Format a percentage value.
 */
function formatPercent(value: unknown): string {
  if (typeof value === 'number') {
    return `${value.toFixed(2)}%`
  }
  return String(value ?? '')
}

/**
 * Format a date value.
 */
function formatDate(value: unknown): string {
  if (!value) return ''
  try {
    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    }).format(new Date(value as string))
  } catch {
    return String(value)
  }
}

/**
 * Format a datetime value.
 */
function formatDatetime(value: unknown): string {
  if (!value) return ''
  try {
    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(value as string))
  } catch {
    return String(value)
  }
}

/**
 * Get a relative time string (e.g., "2h ago").
 */
function getRelativeTime(value: unknown): string {
  if (!value) return ''
  try {
    const date = new Date(value as string)
    const now = new Date()
    const diffMs = now.getTime() - date.getTime()
    const diffMins = Math.floor(diffMs / 60000)
    const diffHours = Math.floor(diffMins / 60)
    const diffDays = Math.floor(diffHours / 24)

    if (diffMins < 1) return 'just now'
    if (diffMins < 60) return `${diffMins}m ago`
    if (diffHours < 24) return `${diffHours}h ago`
    if (diffDays < 7) return `${diffDays}d ago`
    return ''
  } catch {
    return ''
  }
}

/**
 * Strip HTML tags from rich text.
 */
function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, '').substring(0, 200)
}

/**
 * Renders a field value based on its type.
 */
export function FieldRenderer({
  type,
  value,
  fieldName,
  displayName,
  tenantSlug,
  targetCollection,
  displayLabel,
  className,
  truncate = true,
  picklistDisplayMap,
}: FieldRendererProps): React.ReactElement {
  // Null/undefined values
  if (value === null || value === undefined) {
    return <EmptyValue className={className} aria-label={`${displayName || fieldName}: empty`} />
  }

  // Check for plugin-provided custom field renderer
  const CustomRenderer = componentRegistry.getFieldRenderer(type)
  if (CustomRenderer) {
    return (
      <PluginErrorBoundary compact componentType="field renderer">
        {React.createElement(CustomRenderer, {
          value,
          fieldName: fieldName || '',
          displayName: displayName || fieldName || '',
          fieldType: type,
          truncate,
          tenantSlug,
        })}
      </PluginErrorBoundary>
    )
  }

  switch (type) {
    case 'string':
    case 'external_id': {
      const str = String(value)
      if (truncate && str.length > 100) {
        return (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className={cn('truncate', className)}>{str.substring(0, 100)}...</span>
              </TooltipTrigger>
              <TooltipContent className="max-w-sm">
                <p className="break-words">{str}</p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )
      }
      return <span className={className}>{str}</span>
    }

    case 'number': {
      return <span className={cn('font-mono tabular-nums', className)}>{formatNumber(value)}</span>
    }

    case 'boolean': {
      const boolValue = Boolean(value)
      return (
        <span className={cn('inline-flex items-center', className)}>
          {boolValue ? (
            <Check className="h-4 w-4 text-emerald-600" aria-hidden="true" />
          ) : (
            <X className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          )}
          <span className="sr-only">{boolValue ? 'Yes' : 'No'}</span>
        </span>
      )
    }

    case 'date': {
      return <span className={className}>{formatDate(value)}</span>
    }

    case 'datetime': {
      const relativeTime = getRelativeTime(value)
      const formattedDatetime = formatDatetime(value)
      if (relativeTime) {
        return (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className={className}>{formattedDatetime}</span>
              </TooltipTrigger>
              <TooltipContent>
                <p>{relativeTime}</p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )
      }
      return <span className={className}>{formattedDatetime}</span>
    }

    case 'currency': {
      return (
        <span className={cn('font-mono tabular-nums', className)}>{formatCurrency(value)}</span>
      )
    }

    case 'percent': {
      return <span className={cn('font-mono tabular-nums', className)}>{formatPercent(value)}</span>
    }

    case 'email': {
      const emailStr = String(value)
      return (
        <a
          href={`mailto:${emailStr}`}
          className={cn('inline-flex items-center gap-1 text-primary hover:underline', className)}
          onClick={(e) => e.stopPropagation()}
        >
          <Mail className="h-3.5 w-3.5" aria-hidden="true" />
          {emailStr}
        </a>
      )
    }

    case 'phone': {
      const phoneStr = String(value)
      return (
        <a
          href={`tel:${phoneStr}`}
          className={cn('inline-flex items-center gap-1 text-primary hover:underline', className)}
          onClick={(e) => e.stopPropagation()}
        >
          <Phone className="h-3.5 w-3.5" aria-hidden="true" />
          {phoneStr}
        </a>
      )
    }

    case 'url': {
      const urlStr = String(value)
      return (
        <a
          href={urlStr.startsWith('http') ? urlStr : `https://${urlStr}`}
          target="_blank"
          rel="noopener noreferrer"
          className={cn('inline-flex items-center gap-1 text-primary hover:underline', className)}
          onClick={(e) => e.stopPropagation()}
        >
          <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
          {urlStr}
        </a>
      )
    }

    case 'picklist': {
      const strValue = String(value)
      const entry = picklistDisplayMap?.get(strValue)
      if (entry?.color) {
        return (
          <Badge
            className={className}
            style={{ backgroundColor: entry.color, color: contrastingTextColor(entry.color) }}
          >
            {entry.label || strValue}
          </Badge>
        )
      }
      return (
        <Badge variant="secondary" className={className}>
          {entry?.label || strValue}
        </Badge>
      )
    }

    case 'multi_picklist': {
      const items = Array.isArray(value) ? value : [value]
      return (
        <div className={cn('flex flex-wrap gap-1', className)}>
          {items.map((item, idx) => {
            const strItem = String(item)
            const entry = picklistDisplayMap?.get(strItem)
            if (entry?.color) {
              return (
                <Badge
                  key={idx}
                  style={{ backgroundColor: entry.color, color: contrastingTextColor(entry.color) }}
                >
                  {entry.label || strItem}
                </Badge>
              )
            }
            return (
              <Badge key={idx} variant="secondary">
                {entry?.label || strItem}
              </Badge>
            )
          })}
        </div>
      )
    }

    case 'reference':
    case 'lookup':
    case 'master_detail': {
      const label = displayLabel || String(value)
      if (tenantSlug && targetCollection) {
        return (
          <Link
            to={`/${tenantSlug}/app/o/${targetCollection}/${String(value)}`}
            className={cn('text-primary hover:underline', className)}
            onClick={(e) => e.stopPropagation()}
          >
            {label}
          </Link>
        )
      }
      return <span className={className}>{label}</span>
    }

    case 'rich_text': {
      const stripped = stripHtml(String(value))
      if (truncate && stripped.length > 100) {
        return (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className={cn('truncate', className)}>{stripped.substring(0, 100)}...</span>
              </TooltipTrigger>
              <TooltipContent className="max-w-sm">
                <p className="break-words">{stripped}</p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )
      }
      return <span className={className}>{stripped}</span>
    }

    case 'json': {
      const jsonStr = typeof value === 'object' ? JSON.stringify(value) : String(value)
      const preview = jsonStr.length > 50 ? jsonStr.substring(0, 50) + '...' : jsonStr
      return (
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger asChild>
              <code className={cn('rounded bg-muted px-1.5 py-0.5 font-mono text-xs', className)}>
                {preview}
              </code>
            </TooltipTrigger>
            <TooltipContent className="max-w-md">
              <pre className="whitespace-pre-wrap break-words font-mono text-xs">{jsonStr}</pre>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      )
    }

    case 'auto_number': {
      return (
        <span className={cn('inline-flex items-center gap-1 font-mono text-sm', className)}>
          <Hash className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
          {String(value)}
        </span>
      )
    }

    case 'formula': {
      return (
        <span className={cn('inline-flex items-center gap-1', className)}>
          <Calculator className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
          {String(value)}
        </span>
      )
    }

    case 'rollup_summary': {
      return <span className={cn('font-mono tabular-nums', className)}>{formatNumber(value)}</span>
    }

    case 'geolocation': {
      if (typeof value === 'object' && value !== null) {
        const geo = value as Record<string, unknown>
        const lat = geo.latitude ?? geo.lat ?? '-'
        const lng = geo.longitude ?? geo.lng ?? geo.lon ?? '-'
        return (
          <span className={cn('inline-flex items-center gap-1', className)}>
            <MapPin className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
            {String(lat)}, {String(lng)}
          </span>
        )
      }
      return (
        <span className={cn('inline-flex items-center gap-1', className)}>
          <MapPin className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
          {String(value)}
        </span>
      )
    }

    case 'encrypted': {
      return (
        <span className={cn('inline-flex items-center gap-1 text-muted-foreground', className)}>
          <Lock className="h-3.5 w-3.5" aria-hidden="true" />
          {'••••••••'}
        </span>
      )
    }

    default: {
      return <span className={className}>{String(value)}</span>
    }
  }
}

// Re-export for convenience
export { Copy }
