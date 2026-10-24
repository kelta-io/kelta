/**
 * ErrorBoundary Component
 *
 * A React error boundary that catches JavaScript errors in child components,
 * logs them, and displays a fallback UI with recovery options.
 *
 * Requirements:
 * - 18.7: Provide a global error boundary to catch and display unexpected errors
 * - 18.8: Display a user-friendly error page with recovery options
 * - 18.6: Log errors to the console with sufficient detail for debugging
 */

import { Component, type ErrorInfo, type ReactNode, type ReactElement } from 'react'
import { cn } from '@/lib/utils'

/**
 * Props for the ErrorBoundary component
 */
export interface ErrorBoundaryProps {
  /** Child components to render */
  children: ReactNode
  /** Optional custom fallback UI to display when an error occurs */
  fallback?: ReactNode
}

/**
 * State for the ErrorBoundary component
 */
export interface ErrorBoundaryState {
  /** Whether an error has been caught */
  hasError: boolean
  /** The error that was caught, if any */
  error: Error | null
}

/**
 * Props for the ErrorFallback component
 */
export interface ErrorFallbackProps {
  /** The error that was caught */
  error: Error | null
  /** Callback to reset the error state */
  onReset?: () => void
}

/**
 * Check if we're in development mode
 */
const isDevelopment = (): boolean => {
  return process.env.NODE_ENV === 'development' || import.meta.env?.DEV === true
}

/**
 * ErrorFallback Component
 *
 * Default fallback UI displayed when an error is caught.
 * Provides error information and recovery options.
 */
export function ErrorFallback({ error, onReset }: ErrorFallbackProps): ReactElement {
  /**
   * Handle reload page action
   */
  const handleReload = (): void => {
    window.location.reload()
  }

  /**
   * Handle navigate home action
   */
  const handleGoHome = (): void => {
    const slug = window.location.pathname.split('/')[1] || ''
    window.location.href = slug ? `/${slug}` : '/'
  }

  /**
   * Handle try again action (reset error boundary)
   */
  const handleTryAgain = (): void => {
    if (onReset) {
      onReset()
    }
  }

  const showStackTrace = isDevelopment() && error?.stack

  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center min-h-screen p-8 bg-muted text-foreground',
        'max-md:p-4',
        'print:min-h-0 print:p-0 print:bg-transparent'
      )}
      role="alert"
      aria-live="assertive"
      aria-atomic="true"
      data-testid="error-boundary-fallback"
    >
      <div
        className={cn(
          'flex flex-col items-center max-w-[600px] w-full p-8 bg-card rounded-lg shadow-[0_4px_12px_rgba(0,0,0,0.1)] text-center',
          'forced-colors:border-2 forced-colors:border-current',
          'max-md:p-6 max-md:px-4',
          'min-[768px]:max-[1023px]:max-w-[500px]',
          'print:shadow-none print:border print:border-black'
        )}
      >
        {/* Error Icon */}
        <div
          className={cn(
            'flex items-center justify-center w-20 h-20 mb-6 rounded-full bg-red-50 text-destructive dark:bg-red-950',
            'forced-colors:border-2 forced-colors:border-current',
            'max-md:w-16 max-md:h-16 max-md:mb-4'
          )}
          aria-hidden="true"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="w-12 h-12 max-md:w-9 max-md:h-9"
          >
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
        </div>

        {/* Error Title */}
        <h1
          className="m-0 mb-4 text-2xl font-semibold text-foreground leading-[1.3] max-md:text-xl"
          data-testid="error-title"
        >
          Something went wrong
        </h1>

        {/* Error Message */}
        <p
          className="m-0 mb-6 text-base text-muted-foreground leading-normal max-md:text-sm"
          data-testid="error-message"
        >
          {error?.message || 'An unexpected error occurred. Please try again.'}
        </p>

        {/* Stack Trace (Development Only) */}
        {showStackTrace && (
          <details className="w-full mb-6 text-left print:hidden" data-testid="error-stack-trace">
            <summary
              className={cn(
                'py-2 px-4 text-sm font-medium text-muted-foreground bg-muted rounded cursor-pointer transition-colors duration-150',
                'hover:bg-accent',
                'focus:outline-2 focus:outline-ring focus:outline-offset-2',
                'focus-visible:outline-2 focus-visible:outline-ring focus-visible:outline-offset-2',
                'focus:[&:not(:focus-visible)]:outline-none',
                'motion-reduce:transition-none'
              )}
            >
              View technical details
            </summary>
            <pre
              className={cn(
                'mt-2 p-4 font-mono text-xs leading-normal text-foreground bg-muted border border-border rounded overflow-x-auto whitespace-pre-wrap break-words max-h-[300px] overflow-y-auto',
                'forced-colors:border-2 forced-colors:border-current',
                'max-md:text-[0.625rem] max-md:max-h-[200px]'
              )}
            >
              {error.stack}
            </pre>
          </details>
        )}

        {/* Recovery Actions */}
        <div
          className={cn(
            'flex flex-wrap gap-2 justify-center mb-6',
            'max-md:flex-col max-md:w-full',
            'print:hidden'
          )}
          data-testid="error-actions"
        >
          {onReset && (
            <button
              type="button"
              className={cn(
                'inline-flex items-center justify-center py-2 px-6 text-sm font-medium text-primary-foreground bg-primary border-none rounded cursor-pointer transition-[background-color,transform] duration-150 min-h-[40px]',
                'hover:bg-primary/90',
                'focus:outline-2 focus:outline-ring focus:outline-offset-2',
                'focus-visible:outline-2 focus-visible:outline-ring focus-visible:outline-offset-2',
                'focus:[&:not(:focus-visible)]:outline-none',
                'active:scale-[0.98]',
                'forced-colors:border-2 forced-colors:border-current',
                'motion-reduce:transition-none motion-reduce:active:transform-none',
                'max-md:w-full'
              )}
              onClick={handleTryAgain}
              data-testid="try-again-button"
            >
              Try Again
            </button>
          )}
          <button
            type="button"
            className={cn(
              'inline-flex items-center justify-center py-2 px-6 text-sm font-medium text-primary-foreground bg-primary border-none rounded cursor-pointer transition-[background-color,transform] duration-150 min-h-[40px]',
              'hover:bg-primary/90',
              'focus:outline-2 focus:outline-ring focus:outline-offset-2',
              'focus-visible:outline-2 focus-visible:outline-ring focus-visible:outline-offset-2',
              'focus:[&:not(:focus-visible)]:outline-none',
              'active:scale-[0.98]',
              'forced-colors:border-2 forced-colors:border-current',
              'motion-reduce:transition-none motion-reduce:active:transform-none',
              'max-md:w-full'
            )}
            onClick={handleReload}
            data-testid="reload-button"
          >
            Reload Page
          </button>
          <button
            type="button"
            className={cn(
              'inline-flex items-center justify-center py-2 px-6 text-sm font-medium text-foreground bg-transparent border border-border rounded cursor-pointer transition-[background-color,border-color,transform] duration-150 min-h-[40px]',
              'hover:bg-accent hover:border-input',
              'focus:outline-2 focus:outline-ring focus:outline-offset-2',
              'focus-visible:outline-2 focus-visible:outline-ring focus-visible:outline-offset-2',
              'focus:[&:not(:focus-visible)]:outline-none',
              'active:scale-[0.98]',
              'forced-colors:border-2 forced-colors:border-current',
              'motion-reduce:transition-none motion-reduce:active:transform-none',
              'max-md:w-full'
            )}
            onClick={handleGoHome}
            data-testid="go-home-button"
          >
            Go Home
          </button>
        </div>

        {/* Help Text */}
        <p className="m-0 text-sm text-muted-foreground/70">
          If this problem persists, please contact support.
        </p>
      </div>
    </div>
  )
}

/**
 * ErrorBoundary Component
 *
 * A React error boundary that catches JavaScript errors anywhere in the child
 * component tree, logs those errors, and displays a fallback UI.
 *
 * Features:
 * - Catches errors in child components during rendering, lifecycle methods, and constructors
 * - Logs errors to console with stack trace for debugging
 * - Displays customizable fallback UI with recovery options
 * - Provides "Reload Page" and "Go Home" recovery actions
 * - Accessible with proper ARIA attributes for screen readers
 *
 * @example
 * ```tsx
 * // Basic usage
 * <ErrorBoundary>
 *   <App />
 * </ErrorBoundary>
 *
 * // With custom fallback
 * <ErrorBoundary fallback={<CustomErrorPage />}>
 *   <App />
 * </ErrorBoundary>
 * ```
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = {
      hasError: false,
      error: null,
    }
  }

  /**
   * Update state when an error is caught
   * This lifecycle method is called during the "render" phase
   *
   * @param error - The error that was thrown
   * @returns Updated state with error information
   */
  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return {
      hasError: true,
      error,
    }
  }

  /**
   * Log error information for debugging
   * This lifecycle method is called during the "commit" phase
   *
   * Requirement 18.6: Log errors to the console with sufficient detail for debugging
   *
   * @param error - The error that was thrown
   * @param errorInfo - Additional information about the error including component stack
   */
  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    // Log error with full details for debugging
    console.error('[ErrorBoundary] An error was caught:', {
      error: {
        name: error.name,
        message: error.message,
        stack: error.stack,
      },
      componentStack: errorInfo.componentStack,
      timestamp: new Date().toISOString(),
    })

    // Also log the raw error for easier debugging in console
    console.error('[ErrorBoundary] Error:', error)
    console.error('[ErrorBoundary] Component Stack:', errorInfo.componentStack)
  }

  /**
   * Reset the error state to allow recovery
   */
  resetError = (): void => {
    this.setState({
      hasError: false,
      error: null,
    })
  }

  render(): ReactNode {
    const { hasError, error } = this.state
    const { children, fallback } = this.props

    if (hasError) {
      // If a custom fallback is provided, use it
      if (fallback !== undefined) {
        return fallback
      }

      // Otherwise, use the default ErrorFallback component
      return <ErrorFallback error={error} onReset={this.resetError} />
    }

    return children
  }
}
