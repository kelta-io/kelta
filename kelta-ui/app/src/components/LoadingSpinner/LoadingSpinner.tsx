/**
 * LoadingSpinner Component
 *
 * Displays an animated loading spinner with optional label text.
 * Supports multiple size variants and accessibility features.
 *
 * Requirements:
 * - 18.4: Display loading indicators during async operations
 *
 * Features:
 * - Animated spinner (CSS animation)
 * - Size variants: small (16px), medium (24px), large (48px)
 * - Optional visible label text
 * - Accessible with role="status" and aria-live="polite"
 * - Screen reader text for loading state
 * - Reduced motion support (static indicator instead of animation)
 */

import React from 'react'
import { cn } from '@/lib/utils'

/**
 * Size variants for the spinner
 */
export type SpinnerSize = 'small' | 'medium' | 'large'

/**
 * Props for the LoadingSpinner component
 */
export interface LoadingSpinnerProps {
  /** Size of the spinner: 'small' (16px), 'medium' (24px), or 'large' (48px) */
  size?: SpinnerSize
  /** Optional visible label text displayed below the spinner */
  label?: string
  /** Optional custom class name for additional styling */
  className?: string
  /** Optional test ID for testing purposes */
  'data-testid'?: string
}

/**
 * Default loading text for screen readers when no label is provided
 */
const DEFAULT_SR_TEXT = 'Loading...'

const SIZE_CLASSES: Record<SpinnerSize, string> = {
  small: 'size-4',
  medium: 'size-6',
  large: 'size-12',
}

const LABEL_SIZE_CLASSES: Record<SpinnerSize, string> = {
  small: 'text-xs',
  medium: 'text-sm',
  large: 'text-base',
}

/**
 * LoadingSpinner Component
 *
 * Displays an animated loading indicator with accessibility support.
 * The spinner uses CSS animations that respect reduced motion preferences.
 *
 * @example
 * ```tsx
 * // Basic usage
 * <LoadingSpinner />
 *
 * // With size variant
 * <LoadingSpinner size="large" />
 *
 * // With visible label
 * <LoadingSpinner size="medium" label="Loading data..." />
 * ```
 */
export function LoadingSpinner({
  size = 'medium',
  label,
  className,
  'data-testid': testId = 'loading-spinner',
}: LoadingSpinnerProps): React.ReactElement {
  return (
    <div
      className={cn('inline-flex flex-col items-center justify-center gap-2', className)}
      role="status"
      aria-live="polite"
      aria-busy="true"
      data-testid={testId}
    >
      {/* Spinner element */}
      <div
        className={cn('flex items-center justify-center shrink-0', SIZE_CLASSES[size])}
        aria-hidden="true"
        data-testid={`${testId}-icon`}
      >
        <svg
          className="size-full animate-spin motion-reduce:animate-none"
          viewBox="0 0 24 24"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Background circle (track) */}
          <circle
            className="stroke-current opacity-10 dark:opacity-10"
            cx="12"
            cy="12"
            r="10"
            strokeWidth="3"
          />
          {/* Animated arc */}
          <circle
            className="stroke-primary motion-reduce:animate-pulse"
            cx="12"
            cy="12"
            r="10"
            strokeWidth="3"
            strokeLinecap="round"
            strokeDasharray="45 200"
            strokeDashoffset="0"
            style={{ transformOrigin: 'center' }}
          />
        </svg>
      </div>

      {/* Visible label (if provided) */}
      {label && (
        <span
          className={cn('text-muted-foreground text-center leading-snug', LABEL_SIZE_CLASSES[size])}
          data-testid={`${testId}-label`}
        >
          {label}
        </span>
      )}

      {/* Screen reader text (always present for accessibility) */}
      <span className="sr-only" data-testid={`${testId}-sr-text`}>
        {label || DEFAULT_SR_TEXT}
      </span>
    </div>
  )
}
