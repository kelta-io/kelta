// StatusBadge.tsx — five-state status pill with leading dot
import * as React from 'react'
import { cn } from '@/lib/utils'

type StatusVariant = 'active' | 'pending' | 'inactive' | 'failed' | 'paid'

const variantClass: Record<StatusVariant, string> = {
  active: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/55',
  paid: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/55',
  pending: 'bg-amber-500/15  text-amber-700  dark:text-amber-300  border-amber-500/55',
  inactive: 'bg-slate-500/15  text-slate-700  dark:text-slate-300  border-slate-500/45',
  failed: 'bg-destructive/15 text-destructive border-destructive/55',
}

export interface StatusBadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant: StatusVariant
  label: string
}

/**
 * Status pill with a leading dot. Color is never the sole signal — the dot
 * + text label make the state legible in monochrome too.
 *
 * Variants are fixed at five. If you need a sixth, talk to design first.
 *
 * @example
 *   <StatusBadge variant="active" label="Active" />
 */
export const StatusBadge = React.forwardRef<HTMLSpanElement, StatusBadgeProps>(
  ({ variant, label, className, ...props }, ref) => (
    <span
      ref={ref}
      className={cn(
        'inline-flex items-center gap-1.5 h-[22px] px-2.5 rounded text-[11px] font-semibold border',
        variantClass[variant],
        className
      )}
      {...props}
    >
      <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />
      {label}
    </span>
  )
)

StatusBadge.displayName = 'StatusBadge'
