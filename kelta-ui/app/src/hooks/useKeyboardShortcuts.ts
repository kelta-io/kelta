/**
 * useKeyboardShortcuts Hook
 *
 * Provides keyboard shortcut handling for the application.
 * Supports registering global keyboard shortcuts with modifiers.
 *
 * Requirements:
 * - 14.2: All interactive elements are keyboard accessible
 *
 * Features:
 * - Register/unregister keyboard shortcuts
 * - Support for modifier keys (Ctrl, Alt, Shift, Meta)
 * - Prevents shortcuts when typing in input fields
 * - Accessible shortcut descriptions for screen readers
 */

import { useEffect, useCallback, useRef } from 'react'

/**
 * Modifier keys that can be combined with shortcuts
 */
export interface KeyboardModifiers {
  /** Ctrl key (Cmd on Mac) */
  ctrl?: boolean
  /** Alt key (Option on Mac) */
  alt?: boolean
  /** Shift key */
  shift?: boolean
  /** Meta key (Cmd on Mac, Windows key on Windows) */
  meta?: boolean
}

/**
 * Keyboard shortcut definition
 */
export interface KeyboardShortcut {
  /** The key to listen for (e.g., 'k', 'Escape', 'Enter') */
  key: string
  /** Modifier keys required */
  modifiers?: KeyboardModifiers
  /** Handler function called when shortcut is triggered */
  handler: (event: KeyboardEvent) => void
  /** Description of what the shortcut does (for accessibility) */
  description?: string
  /** Whether to prevent default browser behavior */
  preventDefault?: boolean
  /** Whether to allow the shortcut when focused on input elements */
  allowInInput?: boolean
}

/**
 * Options for the useKeyboardShortcuts hook
 */
export interface UseKeyboardShortcutsOptions {
  /** Whether shortcuts are enabled (default: true) */
  enabled?: boolean
  /** Element to attach listeners to (default: document) */
  target?: HTMLElement | Document | null
}

/**
 * Check if the event target is an input element
 */
function isInputElement(target: EventTarget | null): boolean {
  if (!target || !(target instanceof HTMLElement)) {
    return false
  }

  const tagName = target.tagName.toLowerCase()
  const isInput = tagName === 'input' || tagName === 'textarea' || tagName === 'select'
  const isContentEditable = target.isContentEditable

  return isInput || isContentEditable
}

/**
 * Check if modifiers match the event
 */
function modifiersMatch(event: KeyboardEvent, modifiers?: KeyboardModifiers): boolean {
  const ctrl = modifiers?.ctrl ?? false
  const alt = modifiers?.alt ?? false
  const shift = modifiers?.shift ?? false
  const meta = modifiers?.meta ?? false

  return (
    event.ctrlKey === ctrl &&
    event.altKey === alt &&
    event.shiftKey === shift &&
    event.metaKey === meta
  )
}

/**
 * Format a shortcut for display (e.g., "Ctrl+K")
 */
export function formatShortcut(key: string, modifiers?: KeyboardModifiers): string {
  const parts: string[] = []

  if (modifiers?.ctrl) parts.push('Ctrl')
  if (modifiers?.alt) parts.push('Alt')
  if (modifiers?.shift) parts.push('Shift')
  if (modifiers?.meta) parts.push('⌘')

  // Format special keys
  const keyDisplay = key.length === 1 ? key.toUpperCase() : key
  parts.push(keyDisplay)

  return parts.join('+')
}

/**
 * Hook for handling keyboard shortcuts
 *
 * @param shortcuts - Array of keyboard shortcuts to register
 * @param options - Configuration options
 *
 * @example
 * ```tsx
 * useKeyboardShortcuts([
 *   {
 *     key: 'k',
 *     modifiers: { ctrl: true },
 *     handler: () => openSearch(),
 *     description: 'Open search',
 *     preventDefault: true,
 *   },
 *   {
 *     key: 'Escape',
 *     handler: () => closeModal(),
 *     description: 'Close modal',
 *   },
 * ]);
 * ```
 */
export function useKeyboardShortcuts(
  shortcuts: KeyboardShortcut[],
  options: UseKeyboardShortcutsOptions = {}
): void {
  const { enabled = true, target } = options
  const shortcutsRef = useRef(shortcuts)

  // Update ref when shortcuts change
  useEffect(() => {
    shortcutsRef.current = shortcuts
  }, [shortcuts])

  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      if (!enabled) return

      for (const shortcut of shortcutsRef.current) {
        // Check if key matches (case-insensitive for single characters)
        const keyMatches =
          event.key.toLowerCase() === shortcut.key.toLowerCase() || event.key === shortcut.key

        if (!keyMatches) continue

        // Check modifiers
        if (!modifiersMatch(event, shortcut.modifiers)) continue

        // Check if we should skip input elements
        if (!shortcut.allowInInput && isInputElement(event.target)) continue

        // Prevent default if requested
        if (shortcut.preventDefault) {
          event.preventDefault()
        }

        // Call handler
        shortcut.handler(event)

        // Only handle first matching shortcut
        break
      }
    },
    [enabled]
  )

  useEffect(() => {
    const targetElement = target ?? document

    targetElement.addEventListener('keydown', handleKeyDown as EventListener)

    return () => {
      targetElement.removeEventListener('keydown', handleKeyDown as EventListener)
    }
  }, [target, handleKeyDown])
}

/**
 * Hook for a single Escape key handler
 * Commonly used for closing modals, dialogs, and menus
 *
 * @param handler - Function to call when Escape is pressed
 * @param enabled - Whether the handler is active (default: true)
 *
 * @example
 * ```tsx
 * useEscapeKey(() => setIsOpen(false), isOpen);
 * ```
 */
export function useEscapeKey(handler: () => void, enabled: boolean = true): void {
  useKeyboardShortcuts(
    [
      {
        key: 'Escape',
        handler,
        allowInInput: true,
      },
    ],
    { enabled }
  )
}
