/**
 * PageLoader Component
 *
 * Provides consistent full-page loading states and skeleton loading states
 * for content areas across the application.
 *
 * Requirements:
 * - 18.4: Display loading indicators during async operations
 * - 14.7: Support reduced motion preferences from the operating system
 */

import React from 'react'
import { LoadingSpinner } from '../LoadingSpinner'
import { cn } from '@/lib/utils'

/**
 * Skeleton variant types
 */
export type SkeletonVariant = 'text' | 'card' | 'table' | 'form' | 'header' | 'list'

/**
 * Props for the PageLoader component
 */
export interface PageLoaderProps {
  /** Loading message to display */
  message?: string
  /** Size of the spinner */
  size?: 'small' | 'medium' | 'large'
  /** Whether to show as full page overlay */
  fullPage?: boolean
  /** Optional custom class name */
  className?: string
  /** Optional test ID for testing purposes */
  'data-testid'?: string
}

/**
 * Props for the Skeleton component
 */
export interface SkeletonProps {
  /** Type of skeleton to display */
  variant?: SkeletonVariant
  /** Number of skeleton items to display (for list/table variants) */
  count?: number
  /** Width of the skeleton (CSS value) */
  width?: string
  /** Height of the skeleton (CSS value) */
  height?: string
  /** Optional custom class name */
  className?: string
  /** Optional test ID for testing purposes */
  'data-testid'?: string
}

/**
 * Props for the ContentLoader component
 */
export interface ContentLoaderProps {
  /** Whether content is loading */
  isLoading: boolean
  /** Content to display when not loading */
  children: React.ReactNode
  /** Skeleton variant to show while loading */
  skeleton?: SkeletonVariant
  /** Number of skeleton items */
  skeletonCount?: number
  /** Loading message for screen readers */
  loadingMessage?: string
  /** Optional custom class name */
  className?: string
  /** Optional test ID for testing purposes */
  'data-testid'?: string
}

const shimmerClasses =
  'bg-muted bg-[linear-gradient(90deg,hsl(var(--muted))_0%,hsl(var(--accent))_50%,hsl(var(--muted))_100%)] bg-[length:200%_100%] animate-[shimmer_1.5s_ease-in-out_infinite] motion-reduce:animate-none motion-reduce:bg-none rounded'

/**
 * PageLoader Component
 *
 * Displays a full-page or inline loading state with a spinner.
 */
export function PageLoader({
  message = 'Loading...',
  size = 'large',
  fullPage = false,
  className,
  'data-testid': testId = 'page-loader',
}: PageLoaderProps): React.ReactElement {
  return (
    <div
      className={cn(
        'flex items-center justify-center',
        fullPage && 'fixed inset-0 z-[1000] bg-background/95 dark:bg-background/95',
        !fullPage && 'min-h-[200px] w-full',
        'print:hidden',
        className
      )}
      role="status"
      aria-live="polite"
      aria-busy="true"
      data-testid={testId}
    >
      <div className="flex flex-col items-center justify-center gap-4">
        <LoadingSpinner size={size} label={message} data-testid={`${testId}-spinner`} />
      </div>
    </div>
  )
}

/**
 * Skeleton Component
 *
 * Displays a skeleton placeholder for content that is loading.
 */
export function Skeleton({
  variant = 'text',
  count = 1,
  width,
  height,
  className,
  'data-testid': testId = 'skeleton',
}: SkeletonProps): React.ReactElement {
  const style: React.CSSProperties = {
    ...(width && { width }),
    ...(height && { height }),
  }

  const renderSkeletonItem = (index: number) => {
    const itemTestId = count > 1 ? `${testId}-item-${index}` : testId

    switch (variant) {
      case 'text':
        return (
          <div
            key={index}
            className={cn(shimmerClasses, 'h-4 w-full mb-2 last:w-[70%]')}
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          />
        )

      case 'card':
        return (
          <div
            key={index}
            className={cn(shimmerClasses, 'p-4 rounded-lg mb-4 min-h-[150px]')}
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          >
            <div className={cn(shimmerClasses, 'h-5 w-[60%] mb-4')} />
            <div className="flex flex-col gap-2">
              <div className={cn(shimmerClasses, 'h-3.5 w-[80%]')} />
              <div className={cn(shimmerClasses, 'h-3.5 w-[60%]')} />
              <div className={cn(shimmerClasses, 'h-3.5 w-[70%]')} />
            </div>
          </div>
        )

      case 'table':
        return (
          <div
            key={index}
            className={cn(
              shimmerClasses,
              'flex gap-4 py-3 border-b border-border last:border-b-0 bg-transparent bg-none animate-none'
            )}
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          >
            <div className={cn(shimmerClasses, 'h-4')} style={{ width: '20%' }} />
            <div className={cn(shimmerClasses, 'h-4')} style={{ width: '30%' }} />
            <div className={cn(shimmerClasses, 'h-4')} style={{ width: '25%' }} />
            <div className={cn(shimmerClasses, 'h-4')} style={{ width: '15%' }} />
          </div>
        )

      case 'form':
        return (
          <div
            key={index}
            className="mb-6"
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          >
            <div className={cn(shimmerClasses, 'h-3.5 w-[30%] mb-2')} />
            <div className={cn(shimmerClasses, 'h-10 w-full')} />
          </div>
        )

      case 'header':
        return (
          <div
            key={index}
            className="mb-6"
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          >
            <div className={cn(shimmerClasses, 'h-8 w-1/2 mb-3')} />
            <div className={cn(shimmerClasses, 'h-4 w-[35%]')} />
          </div>
        )

      case 'list':
        return (
          <div
            key={index}
            className="flex items-center gap-4 py-3 border-b border-border last:border-b-0"
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          >
            <div className={cn(shimmerClasses, 'size-10 rounded-full shrink-0')} />
            <div className="flex-1 flex flex-col gap-2">
              <div className={cn(shimmerClasses, 'h-3.5 w-[70%]')} />
              <div className={cn(shimmerClasses, 'h-3.5 w-1/2')} />
            </div>
          </div>
        )

      default:
        return (
          <div
            key={index}
            className={cn(shimmerClasses, 'h-4 w-full mb-2 last:w-[70%]')}
            style={style}
            data-testid={itemTestId}
            aria-hidden="true"
          />
        )
    }
  }

  return (
    <div
      className={cn('w-full print:hidden', className)}
      role="status"
      aria-label="Loading content"
      data-testid={`${testId}-container`}
    >
      <span className="sr-only">Loading...</span>
      {Array.from({ length: count }, (_, index) => renderSkeletonItem(index))}
    </div>
  )
}

/**
 * ContentLoader Component
 *
 * Wrapper component that shows skeleton loading state while content is loading,
 * then displays the actual content when ready.
 */
export function ContentLoader({
  isLoading,
  children,
  skeleton = 'text',
  skeletonCount = 3,
  loadingMessage = 'Loading content...',
  className,
  'data-testid': testId = 'content-loader',
}: ContentLoaderProps): React.ReactElement {
  if (isLoading) {
    return (
      <div className={className} data-testid={testId} aria-busy="true">
        <span className="sr-only">{loadingMessage}</span>
        <Skeleton variant={skeleton} count={skeletonCount} data-testid={`${testId}-skeleton`} />
      </div>
    )
  }

  return (
    <div className={className} data-testid={testId} aria-busy="false">
      {children}
    </div>
  )
}
