/**
 * RecentItemsDropdown Component
 *
 * Dropdown in the header showing recently viewed records.
 * Appears when clicking the clock icon button.
 */

import React, { useState, useCallback, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Clock } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useAuth } from '../../context/AuthContext'
import { getTenantSlug } from '../../context/TenantContext'
import { recordRoutes } from '../../utils/recordRoutes'
import { useRecentRecords } from '../../hooks/useRecentRecords'
import { formatRelativeTime } from '../../utils/formatRelativeTime'

export interface RecentItemsDropdownProps {
  testId?: string
}

export function RecentItemsDropdown({
  testId = 'recent-items-dropdown',
}: RecentItemsDropdownProps): React.ReactElement {
  const { t } = useI18n()
  const { user } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)

  const userId = user?.id ?? 'anonymous'
  const { recentRecords, clearRecentRecords } = useRecentRecords(userId)
  const displayRecords = recentRecords.slice(0, 15)

  const toggleOpen = useCallback(() => {
    setOpen((prev) => !prev)
  }, [])

  const close = useCallback(() => {
    setOpen(false)
  }, [])

  // Close on outside click
  useEffect(() => {
    if (!open) return

    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        close()
      }
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close()
        buttonRef.current?.focus()
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, close])

  const handleNavigate = useCallback(
    (path: string) => {
      close()
      navigate(path)
    },
    [close, navigate]
  )

  return (
    <div className="relative flex items-center" ref={containerRef} data-testid={testId}>
      <button
        ref={buttonRef}
        type="button"
        className={cn(
          'flex items-center justify-center w-9 h-9 bg-transparent border border-transparent rounded cursor-pointer',
          'text-lg text-foreground',
          'transition-colors duration-150 motion-reduce:transition-none',
          'hover:bg-accent hover:border-input',
          'focus:outline-2 focus:outline-primary focus:outline-offset-2',
          'focus:not(:focus-visible):outline-none'
        )}
        onClick={toggleOpen}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={t('recent.title')}
        title={t('recent.title')}
      >
        <span aria-hidden="true">
          <Clock size={16} />
        </span>
      </button>

      {open && (
        <div
          className="absolute top-[calc(100%+4px)] right-0 w-80 max-h-[480px] bg-background border border-input rounded shadow-lg z-[1000] overflow-hidden flex flex-col max-md:w-[calc(100vw-32px)] max-md:-right-2"
          role="menu"
          aria-label={t('recent.title')}
        >
          <div className="flex items-center justify-between px-4 py-2 border-b border-border">
            <span className="text-[0.8125rem] font-semibold text-foreground">
              {t('recent.title')}
            </span>
            {displayRecords.length > 0 && (
              <button
                type="button"
                className="bg-transparent border-none p-0 text-xs text-primary cursor-pointer font-medium hover:underline"
                onClick={() => {
                  clearRecentRecords()
                  close()
                }}
              >
                {t('common.clear')}
              </button>
            )}
          </div>

          {displayRecords.length === 0 ? (
            <div className="px-6 py-6 text-center text-[0.8125rem] text-muted-foreground">
              {t('recent.noRecent')}
            </div>
          ) : (
            <ul className="list-none m-0 p-0 overflow-y-auto flex-1">
              {displayRecords.map((record, idx) => (
                <li key={`${record.collectionName}-${record.id}-${idx}`}>
                  <button
                    type="button"
                    className={cn(
                      'flex items-center justify-between w-full px-4 py-2 bg-transparent border-none border-b border-border cursor-pointer text-left',
                      'transition-colors duration-100 motion-reduce:transition-none',
                      'hover:bg-accent',
                      'focus:outline-2 focus:outline-primary focus:-outline-offset-2',
                      'focus:not(:focus-visible):outline-none',
                      'last:border-b-0'
                    )}
                    onClick={() =>
                      // Rendered in the admin header, so recent records open in the admin stack.
                      handleNavigate(
                        recordRoutes('admin', getTenantSlug()).detail(
                          record.collectionName,
                          record.id
                        )
                      )
                    }
                    role="menuitem"
                  >
                    <div className="flex flex-col gap-px min-w-0 flex-1">
                      <span className="text-[0.8125rem] font-medium text-foreground whitespace-nowrap overflow-hidden text-ellipsis">
                        {record.displayValue}
                      </span>
                      <span className="text-[0.6875rem] text-muted-foreground">
                        {record.collectionDisplayName || record.collectionName}
                      </span>
                    </div>
                    <span className="text-[0.6875rem] text-muted-foreground whitespace-nowrap shrink-0 ml-2">
                      {formatRelativeTime(record.viewedAt)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
