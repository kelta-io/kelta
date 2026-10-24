/**
 * Header Component
 *
 * Top navigation bar with branding, user menu, and global actions.
 * Displays logo and application name from config, and user menu with logout option.
 * Includes global search trigger (Cmd+K), recent items dropdown, and shared UserMenu.
 *
 * Requirements:
 * - 1.5: Apply branding including logo, application name, and favicon
 * - 2.6: Clear tokens and redirect on logout (via onLogout callback)
 */

import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search, ArrowLeft } from 'lucide-react'
import type { BrandingConfig } from '../../types/config'
import type { User } from '../../types/auth'
import { SearchModal } from '../SearchModal'
import { RecentItemsDropdown } from '../RecentItemsDropdown'
import { UserMenu } from '../UserMenu'
import { getTenantSlug } from '../../context/TenantContext'

const MOBILE_BREAKPOINT_PX = 768

/**
 * Props for the Header component
 */
export interface HeaderProps {
  /** Branding configuration with logo, app name */
  branding: BrandingConfig
  /** Current authenticated user, null if not authenticated */
  user: User | null
  /** Callback when user clicks logout */
  onLogout: () => void
}

/**
 * Header component provides the top navigation bar with branding and user menu.
 */
export function Header({ branding, user, onLogout }: HeaderProps): React.ReactElement {
  const navigate = useNavigate()
  const tenantSlug = getTenantSlug()
  const [searchOpen, setSearchOpen] = useState(false)
  const [isMobile, setIsMobile] = useState(() =>
    typeof window === 'undefined' ? false : window.innerWidth < MOBILE_BREAKPOINT_PX
  )

  useEffect(() => {
    if (typeof window === 'undefined') return
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT_PX - 1}px)`)
    const handler = (e: MediaQueryListEvent) => setIsMobile(e.matches)
    // Initial sync — useState initializer reads window.innerWidth, but the
    // media query may classify differently. Subscribe-via-callback pattern.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setIsMobile(mql.matches)
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  }, [])

  // Global Cmd+K / Ctrl+K shortcut
  useEffect(() => {
    const handleGlobalKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    document.addEventListener('keydown', handleGlobalKeyDown)
    return () => document.removeEventListener('keydown', handleGlobalKeyDown)
  }, [])

  return (
    <>
      <header
        className="flex items-center w-full h-full px-6 gap-4 md:max-lg:px-4 max-md:px-4 print:border-b print:border-black"
        data-testid="header"
        role="banner"
      >
        {/* Branding section */}
        <div className="flex items-center gap-2 shrink-0">
          {branding.logoUrl && (
            <img
              src={branding.logoUrl}
              alt={`${branding.applicationName} logo`}
              className="h-9 w-auto object-contain max-md:h-7"
              data-testid="header-logo"
            />
          )}
          {!isMobile && (
            <h1
              className="m-0 max-w-[300px] overflow-hidden text-ellipsis whitespace-nowrap text-xl font-semibold text-foreground md:max-lg:max-w-[200px]"
              data-testid="header-app-name"
            >
              {branding.applicationName}
            </h1>
          )}
          <button
            type="button"
            className="flex cursor-pointer items-center gap-1 whitespace-nowrap rounded border border-border bg-transparent px-2 py-1 text-[0.8125rem] font-medium text-muted-foreground transition-colors duration-150 ease-in-out hover:border-muted-foreground hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            onClick={() => navigate(`/${tenantSlug}/app`)}
            aria-label="Back to application"
            data-testid="back-to-app-button"
          >
            <ArrowLeft size={14} aria-hidden="true" />
            {!isMobile && <span>Back to app</span>}
          </button>
        </div>

        {/* Search trigger */}
        <button
          type="button"
          className="flex min-w-[200px] cursor-pointer items-center gap-2 rounded border border-border bg-muted/40 px-4 py-1 text-sm text-muted-foreground transition-colors duration-150 ease-in-out hover:border-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 motion-reduce:transition-none max-md:min-w-0 max-md:px-2 max-md:py-1"
          onClick={() => setSearchOpen(true)}
          aria-label="Search (Cmd+K)"
          data-testid="search-trigger"
        >
          <span className="text-sm opacity-60" aria-hidden="true">
            <Search size={16} />
          </span>
          {!isMobile && (
            <span className="flex flex-1 items-center gap-4">
              Search...
              <kbd className="ml-auto inline-flex items-center rounded-[3px] border border-border bg-background px-[5px] py-px text-[0.6875rem] font-[inherit] text-muted-foreground">
                &#x2318;K
              </kbd>
            </span>
          )}
        </button>

        {/* Spacer to push items to the right */}
        <div className="flex-1" aria-hidden="true" />

        {/* Recent items + User menu */}
        {user && (
          <div className="flex items-center gap-2">
            <RecentItemsDropdown />
            <UserMenu user={user} onLogout={onLogout} variant="admin" compact={isMobile} />
          </div>
        )}
      </header>

      {/* Global Search Modal */}
      <SearchModal open={searchOpen} onClose={() => setSearchOpen(false)} />
    </>
  )
}
