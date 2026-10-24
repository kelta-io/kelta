/**
 * ErrorBoundary Component Tests
 *
 * Tests for the ErrorBoundary component including error catching,
 * fallback UI display, and recovery options.
 *
 * Requirements tested:
 * - 18.7: Global error boundary to catch and display unexpected errors
 * - 18.8: User-friendly error page with recovery options
 * - 18.6: Log errors to console with sufficient detail for debugging
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ErrorBoundary, ErrorFallback } from './ErrorBoundary'

// Component that throws an error for testing
function ThrowError({ shouldThrow = true }: { shouldThrow?: boolean }) {
  if (shouldThrow) {
    throw new Error('Test error message')
  }
  return <div data-testid="child-content">Child content rendered successfully</div>
}

// Component that throws an error with a custom message
function ThrowCustomError({ message }: { message: string }): never {
  throw new Error(message)
}

describe('ErrorBoundary', () => {
  // Store original console.error to restore after tests
  const originalConsoleError = console.error

  beforeEach(() => {
    // Suppress console.error during tests to avoid noise
    // We'll verify it's called in specific tests
    console.error = vi.fn()
  })

  afterEach(() => {
    // Restore console.error
    console.error = originalConsoleError
    vi.restoreAllMocks()
  })

  describe('Normal Rendering', () => {
    it('should render children when no error occurs', () => {
      render(
        <ErrorBoundary>
          <div data-testid="child">Child content</div>
        </ErrorBoundary>
      )

      expect(screen.getByTestId('child')).toBeInTheDocument()
      expect(screen.getByTestId('child')).toHaveTextContent('Child content')
    })

    it('should render multiple children when no error occurs', () => {
      render(
        <ErrorBoundary>
          <div data-testid="child-1">First child</div>
          <div data-testid="child-2">Second child</div>
        </ErrorBoundary>
      )

      expect(screen.getByTestId('child-1')).toBeInTheDocument()
      expect(screen.getByTestId('child-2')).toBeInTheDocument()
    })

    it('should not show fallback UI when no error occurs', () => {
      render(
        <ErrorBoundary>
          <div>Normal content</div>
        </ErrorBoundary>
      )

      expect(screen.queryByTestId('error-boundary-fallback')).not.toBeInTheDocument()
    })
  })

  describe('Error Catching', () => {
    it('should catch errors and display fallback UI', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-boundary-fallback')).toBeInTheDocument()
      expect(screen.queryByTestId('child-content')).not.toBeInTheDocument()
    })

    it('should display the error message in fallback UI', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-message')).toHaveTextContent('Test error message')
    })

    it('should display custom error messages', () => {
      render(
        <ErrorBoundary>
          <ThrowCustomError message="Custom error occurred" />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-message')).toHaveTextContent('Custom error occurred')
    })

    it('should display error title', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-title')).toHaveTextContent('Something went wrong')
    })
  })

  describe('Error Logging', () => {
    it('should log error to console when error is caught', () => {
      const consoleSpy = vi.spyOn(console, 'error')

      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      // Verify console.error was called
      expect(consoleSpy).toHaveBeenCalled()

      // Check that the error details were logged
      const calls = consoleSpy.mock.calls
      const errorLogCall = calls.find(
        (call) => typeof call[0] === 'string' && call[0].includes('[ErrorBoundary]')
      )
      expect(errorLogCall).toBeDefined()
    })

    it('should log error with stack trace', () => {
      const consoleSpy = vi.spyOn(console, 'error')

      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      // Find the detailed error log call
      const calls = consoleSpy.mock.calls
      const detailedLogCall = calls.find(
        (call) =>
          typeof call[0] === 'string' && call[0].includes('[ErrorBoundary] An error was caught')
      )

      expect(detailedLogCall).toBeDefined()
      if (detailedLogCall) {
        const loggedData = detailedLogCall[1]
        expect(loggedData).toHaveProperty('error')
        expect(loggedData.error).toHaveProperty('message', 'Test error message')
        expect(loggedData.error).toHaveProperty('stack')
        expect(loggedData).toHaveProperty('timestamp')
      }
    })
  })

  describe('Custom Fallback', () => {
    it('should render custom fallback when provided', () => {
      render(
        <ErrorBoundary fallback={<div data-testid="custom-fallback">Custom error UI</div>}>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('custom-fallback')).toBeInTheDocument()
      expect(screen.getByTestId('custom-fallback')).toHaveTextContent('Custom error UI')
      expect(screen.queryByTestId('error-boundary-fallback')).not.toBeInTheDocument()
    })

    it('should render null fallback when explicitly provided', () => {
      const { container } = render(
        <ErrorBoundary fallback={null}>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.queryByTestId('error-boundary-fallback')).not.toBeInTheDocument()
      expect(container.firstChild).toBeNull()
    })
  })

  describe('Recovery Options', () => {
    it('should display reload button', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('reload-button')).toBeInTheDocument()
      expect(screen.getByTestId('reload-button')).toHaveTextContent('Reload Page')
    })

    it('should display go home button', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('go-home-button')).toBeInTheDocument()
      expect(screen.getByTestId('go-home-button')).toHaveTextContent('Go Home')
    })

    it('should display try again button', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('try-again-button')).toBeInTheDocument()
      expect(screen.getByTestId('try-again-button')).toHaveTextContent('Try Again')
    })

    it('should call window.location.reload when reload button is clicked', () => {
      // Mock window.location.reload
      const reloadMock = vi.fn()
      Object.defineProperty(window, 'location', {
        value: { reload: reloadMock, href: '/' },
        writable: true,
      })

      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      fireEvent.click(screen.getByTestId('reload-button'))
      expect(reloadMock).toHaveBeenCalledTimes(1)
    })

    it('should navigate to home when go home button is clicked', () => {
      // Mock window.location with pathname so handleGoHome can extract slug
      const locationMock = {
        reload: vi.fn(),
        href: '/default/some-page',
        pathname: '/default/some-page',
      }
      Object.defineProperty(window, 'location', {
        value: locationMock,
        writable: true,
      })

      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      fireEvent.click(screen.getByTestId('go-home-button'))
      expect(locationMock.href).toBe('/default')
    })

    it('should reset error state when try again button is clicked', () => {
      // Create a component that can toggle error state
      let shouldThrow = true
      function ToggleError() {
        if (shouldThrow) {
          throw new Error('Toggle error')
        }
        return <div data-testid="recovered-content">Recovered!</div>
      }

      const { rerender } = render(
        <ErrorBoundary>
          <ToggleError />
        </ErrorBoundary>
      )

      // Verify error state
      expect(screen.getByTestId('error-boundary-fallback')).toBeInTheDocument()

      // Fix the error condition
      shouldThrow = false

      // Click try again
      fireEvent.click(screen.getByTestId('try-again-button'))

      // Re-render to trigger the reset
      rerender(
        <ErrorBoundary>
          <ToggleError />
        </ErrorBoundary>
      )

      // Should show recovered content
      expect(screen.getByTestId('recovered-content')).toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    it('should have role="alert" on fallback container', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-boundary-fallback')).toHaveAttribute('role', 'alert')
    })

    it('should have aria-live="assertive" for screen reader announcement', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-boundary-fallback')).toHaveAttribute(
        'aria-live',
        'assertive'
      )
    })

    it('should have aria-atomic="true" for complete announcement', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(screen.getByTestId('error-boundary-fallback')).toHaveAttribute('aria-atomic', 'true')
    })

    it('should have accessible buttons', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      const reloadButton = screen.getByTestId('reload-button')
      const goHomeButton = screen.getByTestId('go-home-button')
      const tryAgainButton = screen.getByTestId('try-again-button')

      expect(reloadButton).toHaveAttribute('type', 'button')
      expect(goHomeButton).toHaveAttribute('type', 'button')
      expect(tryAgainButton).toHaveAttribute('type', 'button')
    })

    it('should be keyboard navigable', async () => {
      const user = userEvent.setup()

      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      // In development/test the stack-trace <summary> comes first in tab order. Browsers
      // focus <summary>, and user-event does too since 14.6.x, so step past it when it has
      // focus; the three action buttons follow in order either way.
      await user.tab()
      const summary = screen.queryByTestId('error-stack-trace')?.querySelector('summary')
      if (summary && document.activeElement === summary) {
        await user.tab()
      }
      expect(screen.getByTestId('try-again-button')).toHaveFocus()

      await user.tab()
      expect(screen.getByTestId('reload-button')).toHaveFocus()

      await user.tab()
      expect(screen.getByTestId('go-home-button')).toHaveFocus()
    })
  })

  describe('Stack Trace Display', () => {
    it('should show stack trace details element when error has stack', () => {
      // In development/test mode, errors have stack traces
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      // Stack trace should be visible in development mode (which is the test environment)
      expect(screen.getByTestId('error-stack-trace')).toBeInTheDocument()
    })

    it('should contain the error stack in the details element', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      const stackTraceElement = screen.getByTestId('error-stack-trace')
      expect(stackTraceElement).toBeInTheDocument()

      // The stack trace should contain the error message
      expect(stackTraceElement.textContent).toContain('Test error message')
    })

    it('should have expandable details for stack trace', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      const details = screen.getByTestId('error-stack-trace')
      expect(details.tagName.toLowerCase()).toBe('details')

      // Should have a summary element
      const summary = details.querySelector('summary')
      expect(summary).toBeInTheDocument()
      expect(summary).toHaveTextContent('View technical details')
    })
  })

  describe('Help Text', () => {
    it('should display help text for contacting support', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      )

      expect(
        screen.getByText(/If this problem persists, please contact support/i)
      ).toBeInTheDocument()
    })
  })
})

describe('ErrorFallback', () => {
  beforeEach(() => {
    // Suppress console.error during tests
    console.error = vi.fn()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should render with error message', () => {
    const error = new Error('Test error')

    render(<ErrorFallback error={error} />)

    expect(screen.getByTestId('error-message')).toHaveTextContent('Test error')
  })

  it('should render with default message when error is null', () => {
    render(<ErrorFallback error={null} />)

    expect(screen.getByTestId('error-message')).toHaveTextContent('An unexpected error occurred')
  })

  it('should call onReset when try again is clicked', () => {
    const onReset = vi.fn()
    const error = new Error('Test error')

    render(<ErrorFallback error={error} onReset={onReset} />)

    fireEvent.click(screen.getByTestId('try-again-button'))
    expect(onReset).toHaveBeenCalledTimes(1)
  })

  it('should not show try again button when onReset is not provided', () => {
    const error = new Error('Test error')

    render(<ErrorFallback error={error} />)

    expect(screen.queryByTestId('try-again-button')).not.toBeInTheDocument()
  })

  it('should show try again button when onReset is provided', () => {
    const error = new Error('Test error')
    const onReset = vi.fn()

    render(<ErrorFallback error={error} onReset={onReset} />)

    expect(screen.getByTestId('try-again-button')).toBeInTheDocument()
  })
})
