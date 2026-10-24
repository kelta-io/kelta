/**
 * useGlobalShortcuts Hook
 *
 * Registers all global keyboard shortcuts for the Kelta UI.
 * Should be called once at the App level.
 *
 * Shortcuts:
 * - ? → Show keyboard shortcuts help
 * - Ctrl+K / Cmd+K → Open global search
 * - / → Focus filter input
 * - g h → Go to Home
 * - g c → Go to Collections
 * - g r → Go to Resources
 * - n → New record (on a record list page — admin `/resources` or end-user `/app/o`)
 * - e → Edit record (on a record detail page — same stack as the page)
 * - Backspace → Go back
 * - Escape → Close help overlay
 */

import { useState, useCallback, useRef } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useKeyboardShortcuts } from './useKeyboardShortcuts'
import { getTenantSlug } from '../context/TenantContext'
import { parseRecordPath, recordRoutes } from '../utils/recordRoutes'

export function useGlobalShortcuts() {
  const navigate = useNavigate()
  const location = useLocation()
  const [helpOpen, setHelpOpen] = useState(false)

  // Track "g" prefix for two-key combos (g h, g c, g r)
  const gPrefixRef = useRef(false)
  const gTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // The record page being shown, if any, in either record stack. New / edit shortcuts stay
  // in that stack (and tenant).
  const recordPage = parseRecordPath(location.pathname)

  const clearGPrefix = useCallback(() => {
    gPrefixRef.current = false
    if (gTimeoutRef.current) {
      clearTimeout(gTimeoutRef.current)
      gTimeoutRef.current = null
    }
  }, [])

  useKeyboardShortcuts([
    // ? → Show help
    {
      key: '?',
      handler: () => setHelpOpen(true),
      description: 'Show keyboard shortcuts',
    },
    // Ctrl+K → Global search
    {
      key: 'k',
      modifiers: { ctrl: true },
      handler: () => {
        const searchInput = document.querySelector(
          '[data-testid="global-search-input"]'
        ) as HTMLInputElement
        if (searchInput) searchInput.focus()
      },
      description: 'Open global search',
      preventDefault: true,
    },
    // Meta+K → Global search (Mac)
    {
      key: 'k',
      modifiers: { meta: true },
      handler: () => {
        const searchInput = document.querySelector(
          '[data-testid="global-search-input"]'
        ) as HTMLInputElement
        if (searchInput) searchInput.focus()
      },
      description: 'Open global search',
      preventDefault: true,
    },
    // / → Focus filter input
    {
      key: '/',
      handler: () => {
        const filterInput = document.querySelector(
          '[data-testid="filter-input"]'
        ) as HTMLInputElement
        if (filterInput) filterInput.focus()
      },
      description: 'Focus filter input',
      preventDefault: true,
    },
    // g → Start two-key navigation combo
    {
      key: 'g',
      handler: () => {
        gPrefixRef.current = true
        // Reset after 1 second if no follow-up key
        if (gTimeoutRef.current) clearTimeout(gTimeoutRef.current)
        gTimeoutRef.current = setTimeout(() => {
          gPrefixRef.current = false
        }, 1000)
      },
      description: 'Navigation prefix',
    },
    // h → Go to Home (after g)
    {
      key: 'h',
      handler: () => {
        if (gPrefixRef.current) {
          clearGPrefix()
          navigate(`/${getTenantSlug()}`)
        }
      },
      description: 'Go to Home',
    },
    // c → Go to Collections (after g)
    {
      key: 'c',
      handler: () => {
        if (gPrefixRef.current) {
          clearGPrefix()
          navigate(`/${getTenantSlug()}/collections`)
        }
      },
      description: 'Go to Collections',
    },
    // r → Go to Resources (after g)
    {
      key: 'r',
      handler: () => {
        if (gPrefixRef.current) {
          clearGPrefix()
          navigate(`/${getTenantSlug()}/resources`)
        }
      },
      description: 'Go to Resources',
    },
    // n → New record (on list page)
    {
      key: 'n',
      handler: () => {
        if (recordPage?.kind === 'list') {
          const routes = recordRoutes(recordPage.variant, recordPage.tenantSlug)
          navigate(routes.new(recordPage.collection))
        }
      },
      description: 'New record',
    },
    // e → Edit record (on detail page)
    {
      key: 'e',
      handler: () => {
        if (recordPage?.kind === 'detail' && recordPage.id) {
          const routes = recordRoutes(recordPage.variant, recordPage.tenantSlug)
          navigate(routes.edit(recordPage.collection, recordPage.id))
        }
      },
      description: 'Edit record',
    },
    // Backspace → Go back
    {
      key: 'Backspace',
      handler: () => {
        navigate(-1)
      },
      description: 'Go back',
    },
    // Escape → close help
    {
      key: 'Escape',
      handler: () => setHelpOpen(false),
      allowInInput: true,
      description: 'Close dialog',
    },
  ])

  return { helpOpen, setHelpOpen }
}
