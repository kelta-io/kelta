/**
 * LiveRegion Component
 *
 * Provides an ARIA live region for announcing dynamic content changes to screen readers.
 * This component is essential for accessibility compliance when content updates dynamically.
 *
 * Requirements:
 * - 14.3: Provide appropriate ARIA labels and roles for all components
 * - 14.5: Announce dynamic content changes to screen readers
 *
 * Features:
 * - Visually hidden but accessible to screen readers
 * - Supports 'polite' and 'assertive' announcement modes
 * - Automatically clears announcements after a delay
 * - Can be used as a singleton via context or as individual instances
 */

import React, {
  createContext,
  useContext,
  useState,
  useCallback,
  useMemo,
  useEffect,
  useRef,
} from 'react'

/**
 * Politeness level for announcements
 * - 'polite': Waits for user to finish current task before announcing
 * - 'assertive': Interrupts user immediately (use sparingly)
 */
type LiveRegionPoliteness = 'polite' | 'assertive'

/**
 * Props for the LiveRegion component
 */
export interface LiveRegionProps {
  /** The message to announce */
  message: string
  /** Politeness level - 'polite' (default) or 'assertive' */
  politeness?: LiveRegionPoliteness
  /** Whether to clear the message after announcing (default: true) */
  clearAfterAnnounce?: boolean
  /** Delay in ms before clearing the message (default: 1000) */
  clearDelay?: number
  /** Optional test ID */
  'data-testid'?: string
}

/**
 * Context value for the LiveRegion provider
 */
export interface LiveRegionContextValue {
  /** Announce a message to screen readers */
  announce: (message: string, politeness?: LiveRegionPoliteness) => void
  /** Clear the current announcement */
  clear: () => void
}

/**
 * Props for the LiveRegionProvider
 */
export interface LiveRegionProviderProps {
  /** Child components */
  children: React.ReactNode
  /** Default politeness level (default: 'polite') */
  defaultPoliteness?: LiveRegionPoliteness
  /** Delay before clearing announcements (default: 1000ms) */
  clearDelay?: number
}

// Visually hidden styles for screen reader only content
const visuallyHiddenStyles: React.CSSProperties = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
}

// Create context
const LiveRegionContext = createContext<LiveRegionContextValue | undefined>(undefined)

/**
 * LiveRegion Component
 *
 * A standalone live region for announcing content to screen readers.
 * Use this when you need a dedicated live region for a specific component.
 *
 * @example
 * ```tsx
 * <LiveRegion message={statusMessage} politeness="polite" />
 * ```
 */
export function LiveRegion({
  message,
  politeness = 'polite',
  clearAfterAnnounce = true,
  clearDelay = 1000,
  'data-testid': testId = 'live-region',
}: LiveRegionProps): React.ReactElement {
  const [clearedMessage, setClearedMessage] = useState<string | null>(null)
  const clearTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const isCleared = clearedMessage === message

  useEffect(() => {
    if (clearAfterAnnounce && message) {
      if (clearTimeoutRef.current) {
        clearTimeout(clearTimeoutRef.current)
      }
      clearTimeoutRef.current = setTimeout(() => {
        setClearedMessage(message)
      }, clearDelay)
    }

    return () => {
      if (clearTimeoutRef.current) {
        clearTimeout(clearTimeoutRef.current)
      }
    }
  }, [message, clearAfterAnnounce, clearDelay])

  return (
    <div
      role="status"
      aria-live={politeness}
      aria-atomic="true"
      style={visuallyHiddenStyles}
      data-testid={testId}
    >
      {isCleared ? '' : message}
    </div>
  )
}

/**
 * LiveRegionProvider Component
 *
 * Provides a global live region that can be used throughout the application.
 * Use the useAnnounce hook to make announcements from any component.
 *
 * @example
 * ```tsx
 * <LiveRegionProvider>
 *   <App />
 * </LiveRegionProvider>
 * ```
 */
export function LiveRegionProvider({
  children,
  defaultPoliteness = 'polite',
  clearDelay = 1000,
}: LiveRegionProviderProps): React.ReactElement {
  const [politeMessage, setPoliteMessage] = useState('')
  const [assertiveMessage, setAssertiveMessage] = useState('')
  const clearTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  /**
   * Announce a message to screen readers
   */
  const announce = useCallback(
    (message: string, politeness: LiveRegionPoliteness = defaultPoliteness) => {
      // Clear any pending timeout
      if (clearTimeoutRef.current) {
        clearTimeout(clearTimeoutRef.current)
      }

      // Set the appropriate message
      if (politeness === 'assertive') {
        setAssertiveMessage(message)
        setPoliteMessage('')
      } else {
        setPoliteMessage(message)
        setAssertiveMessage('')
      }

      // Clear after delay
      clearTimeoutRef.current = setTimeout(() => {
        setPoliteMessage('')
        setAssertiveMessage('')
      }, clearDelay)
    },
    [defaultPoliteness, clearDelay]
  )

  /**
   * Clear the current announcement
   */
  const clear = useCallback(() => {
    if (clearTimeoutRef.current) {
      clearTimeout(clearTimeoutRef.current)
    }
    setPoliteMessage('')
    setAssertiveMessage('')
  }, [])

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (clearTimeoutRef.current) {
        clearTimeout(clearTimeoutRef.current)
      }
    }
  }, [])

  // Memoize context value
  const contextValue = useMemo<LiveRegionContextValue>(
    () => ({
      announce,
      clear,
    }),
    [announce, clear]
  )

  return (
    <LiveRegionContext.Provider value={contextValue}>
      {children}
      {/* Polite live region */}
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        style={visuallyHiddenStyles}
        data-testid="live-region-polite"
      >
        {politeMessage}
      </div>
      {/* Assertive live region */}
      <div
        role="alert"
        aria-live="assertive"
        aria-atomic="true"
        style={visuallyHiddenStyles}
        data-testid="live-region-assertive"
      >
        {assertiveMessage}
      </div>
    </LiveRegionContext.Provider>
  )
}

/**
 * Hook to access the LiveRegion context for making announcements
 *
 * @throws Error if used outside of LiveRegionProvider
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { announce } = useAnnounce();
 *
 *   const handleSave = async () => {
 *     await saveData();
 *     announce('Data saved successfully');
 *   };
 *
 *   return <button onClick={handleSave}>Save</button>;
 * }
 * ```
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useAnnounce(): LiveRegionContextValue {
  const context = useContext(LiveRegionContext)
  if (context === undefined) {
    throw new Error('useAnnounce must be used within a LiveRegionProvider')
  }
  return context
}
