/**
 * SkipLinks Component
 *
 * Provides skip navigation links for keyboard users to bypass repetitive content.
 * These links are visually hidden but become visible when focused.
 *
 * Requirements:
 * - 14.2: All interactive elements are keyboard accessible
 *
 * Features:
 * - Skip to main content
 * - Skip to navigation
 * - Visually hidden until focused
 * - Accessible to screen readers
 */

import React from 'react'
import { cn } from '@/lib/utils'

/**
 * Skip link target definition
 */
export interface SkipLinkTarget {
  /** Unique identifier for the target element (without #) */
  id: string
  /** Label for the skip link */
  label: string
}

/**
 * Props for the SkipLinks component
 */
export interface SkipLinksProps {
  /** Custom skip link targets (optional, uses defaults if not provided) */
  targets?: SkipLinkTarget[]
}

/**
 * Default skip link targets
 */
const DEFAULT_TARGETS: SkipLinkTarget[] = [
  { id: 'main-content', label: 'Skip to main content' },
  { id: 'main-navigation', label: 'Skip to navigation' },
]

/**
 * SkipLinks component provides accessible skip navigation for keyboard users.
 *
 * The links are visually hidden but appear when focused via keyboard navigation.
 * This allows keyboard users to quickly skip to important sections of the page
 * without having to tab through all navigation items.
 *
 * @example
 * ```tsx
 * // Using default targets
 * <SkipLinks />
 *
 * // Using custom targets
 * <SkipLinks
 *   targets={[
 *     { id: 'main-content', label: 'Skip to main content' },
 *     { id: 'search', label: 'Skip to search' },
 *   ]}
 * />
 * ```
 */
export function SkipLinks({ targets = DEFAULT_TARGETS }: SkipLinksProps): React.ReactElement {
  /**
   * Handle click on skip link
   * Focuses the target element after navigation
   */
  const handleClick = (_event: React.MouseEvent<HTMLAnchorElement>, targetId: string) => {
    const targetElement = document.getElementById(targetId)

    if (targetElement) {
      // Ensure the target is focusable
      if (!targetElement.hasAttribute('tabindex')) {
        targetElement.setAttribute('tabindex', '-1')
      }

      // Focus the target element
      targetElement.focus()

      // Scroll into view smoothly (if available)
      if (typeof targetElement.scrollIntoView === 'function') {
        targetElement.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }
    }
  }

  return (
    <nav
      className="absolute top-0 left-0 z-[10000] flex flex-col gap-1 p-1"
      aria-label="Skip links"
      data-testid="skip-links"
    >
      {targets.map((target) => (
        <a
          key={target.id}
          href={`#${target.id}`}
          className={cn(
            'sr-only focus:not-sr-only',
            'focus:static focus:w-auto focus:h-auto focus:py-3 focus:px-4 focus:m-0 focus:overflow-visible focus:whitespace-normal',
            'bg-primary text-primary-foreground no-underline text-sm font-medium rounded',
            'focus:outline-[3px] focus:outline-primary focus:outline-offset-2 focus:shadow-md',
            'focus:hover:bg-primary/80',
            'motion-reduce:transition-none'
          )}
          onClick={(e) => handleClick(e, target.id)}
          data-testid={`skip-link-${target.id}`}
        >
          {target.label}
        </a>
      ))}
    </nav>
  )
}
