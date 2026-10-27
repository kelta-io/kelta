/**
 * SearchModal Component
 *
 * Command-palette style global search overlay (Cmd+K / Ctrl+K).
 * Searches across records, pages, and recent items.
 * Built on shadcn Dialog + Command (cmdk) with Tailwind CSS styling.
 */

import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search, Clock, FileText, ClipboardList } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useCollectionSummaries } from '../../hooks/useCollectionSummaries'
import { useAuth } from '../../context/AuthContext'
import { useRecentRecords } from '../../hooks/useRecentRecords'
import { formatRelativeTime } from '../../utils/formatRelativeTime'
import { getTenantSlug } from '../../context/TenantContext'
import { recordRoutes } from '../../utils/recordRoutes'
import { cn } from '@/lib/utils'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'

export interface SearchModalProps {
  open: boolean
  onClose: () => void
}

interface SearchResult {
  id: string
  type: 'record' | 'page' | 'recent'
  title: string
  subtitle: string
  path: string
}

const NAV_PAGES: SearchResult[] = [
  { id: 'nav-home', type: 'page', title: 'Home', subtitle: 'Home page', path: '/' },
  {
    id: 'nav-collections',
    type: 'page',
    title: 'Collections',
    subtitle: 'Manage collections',
    path: '/collections',
  },
  {
    id: 'nav-resources',
    type: 'page',
    title: 'Resource Browser',
    subtitle: 'Browse all resources',
    path: '/resources',
  },
  { id: 'nav-roles', type: 'page', title: 'Roles', subtitle: 'Manage roles', path: '/roles' },
  {
    id: 'nav-policies',
    type: 'page',
    title: 'Policies',
    subtitle: 'Manage policies',
    path: '/policies',
  },
  {
    id: 'nav-users',
    type: 'page',
    title: 'Users',
    subtitle: 'Manage users',
    path: '/users',
  },
  {
    id: 'nav-picklists',
    type: 'page',
    title: 'Picklists',
    subtitle: 'Manage picklists',
    path: '/picklists',
  },
  {
    id: 'nav-flows',
    type: 'page',
    title: 'Flows',
    subtitle: 'Flow engine',
    path: '/flows',
  },
  {
    id: 'nav-approvals',
    type: 'page',
    title: 'Approval Processes',
    subtitle: 'Manage approvals',
    path: '/approvals',
  },
  {
    id: 'nav-workflows',
    type: 'page',
    title: 'Workflow Rules',
    subtitle: 'Manage workflow rules',
    path: '/workflow-rules',
  },
  {
    id: 'nav-system-health',
    type: 'page',
    title: 'System Health',
    subtitle: 'System health dashboard',
    path: '/system-health',
  },
  {
    id: 'nav-audit',
    type: 'page',
    title: 'Audit Trail',
    subtitle: 'Setup audit trail',
    path: '/audit-trail',
  },
  {
    id: 'nav-sharing',
    type: 'page',
    title: 'Sharing Settings',
    subtitle: 'Record sharing configuration',
    path: '/sharing',
  },
  {
    id: 'nav-profiles',
    type: 'page',
    title: 'Profiles',
    subtitle: 'Permission profiles',
    path: '/profiles',
  },
  {
    id: 'nav-environments',
    type: 'page',
    title: 'Environments',
    subtitle: 'Sandboxes and metadata promotion',
    path: '/environments',
  },
]

const SEARCH_HISTORY_KEY = 'kelta_search_history'
const MAX_HISTORY = 10

function loadSearchHistory(): string[] {
  try {
    const raw = localStorage.getItem(SEARCH_HISTORY_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function saveSearchHistory(history: string[]): void {
  try {
    localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(history.slice(0, MAX_HISTORY)))
  } catch {
    // ignore
  }
}

export function SearchModal({ open, onClose }: SearchModalProps): React.ReactElement | null {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { user } = useAuth()
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const [searchHistory, setSearchHistory] = useState<string[]>(loadSearchHistory)
  const [recordResults, setRecordResults] = useState<SearchResult[]>([])
  const [searching, setSearching] = useState(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const tenantSlug = getTenantSlug()
  // This palette lives in the admin header, so records open in the admin stack (`/resources`).
  const routes = useMemo(() => recordRoutes('admin', tenantSlug), [tenantSlug])
  const userId = user?.id ?? 'anonymous'
  const { recentRecords } = useRecentRecords(userId)
  const { summaries: collectionSummaries } = useCollectionSummaries()

  // Focus input when opened
  useEffect(() => {
    if (open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- existing sync-from-prop/storage effect; refactor tracked as follow-up to PLT-854
      setQuery('')
      setActiveIndex(0)
      setRecordResults([])
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [open])

  // Debounced record search using centralized search endpoint
  useEffect(() => {
    if (!query.trim() || query.trim().length < 3) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- existing sync-from-prop/storage effect; refactor tracked as follow-up to PLT-854
      setRecordResults([])
      setSearching(false)
      return
    }

    setSearching(true)
    if (debounceRef.current) clearTimeout(debounceRef.current)

    debounceRef.current = setTimeout(async () => {
      try {
        const response = await apiClient.get<{ data?: Array<Record<string, unknown>> }>(
          `/api/_search?q=${encodeURIComponent(query)}&limit=15`
        )
        const data = response?.data
        if (!Array.isArray(data)) {
          setRecordResults([])
          return
        }

        // Build a label map from collection summaries
        const labelMap: Record<string, string> = {}
        for (const col of collectionSummaries) {
          labelMap[col.name] = col.displayName || col.name
        }

        const results: SearchResult[] = data.map((item) => {
          const collectionName = String(item.collectionName)
          const id = String(item.id ?? '')
          return {
            id: `rec-${collectionName}-${id}`,
            type: 'record' as const,
            title: String(item.displayValue || id),
            subtitle: labelMap[collectionName] || collectionName,
            path: routes.detail(collectionName, id),
          }
        })
        setRecordResults(results)
      } catch {
        setRecordResults([])
      } finally {
        setSearching(false)
      }
    }, 300)

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [query, apiClient, collectionSummaries, routes])

  // Build results list
  const results = useMemo((): SearchResult[] => {
    const q = query.trim().toLowerCase()

    if (!q) {
      // Show recent records when no query
      return recentRecords.slice(0, 8).map((r) => ({
        id: `recent-${r.collectionName}-${r.id}`,
        type: 'recent' as const,
        title: r.displayValue,
        subtitle: `${r.collectionDisplayName || r.collectionName} · ${formatRelativeTime(r.viewedAt)}`,
        path: routes.detail(r.collectionName, r.id),
      }))
    }

    // Filter pages
    const pageResults = NAV_PAGES.filter(
      (p) => p.title.toLowerCase().includes(q) || p.subtitle.toLowerCase().includes(q)
    )

    // Filter recent records
    const recentResults = recentRecords
      .filter(
        (r) =>
          r.displayValue.toLowerCase().includes(q) ||
          r.collectionName.toLowerCase().includes(q) ||
          (r.collectionDisplayName && r.collectionDisplayName.toLowerCase().includes(q))
      )
      .slice(0, 5)
      .map((r) => ({
        id: `recent-${r.collectionName}-${r.id}`,
        type: 'recent' as const,
        title: r.displayValue,
        subtitle: r.collectionDisplayName || r.collectionName,
        path: routes.detail(r.collectionName, r.id),
      }))

    return [...recordResults, ...pageResults, ...recentResults]
  }, [query, recordResults, recentRecords, routes])

  // Reset active index when results change
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- existing sync-from-prop/storage effect; refactor tracked as follow-up to PLT-854
    setActiveIndex(0)
  }, [results.length])

  const handleSelect = useCallback(
    (result: SearchResult) => {
      // Save to search history
      if (query.trim()) {
        const newHistory = [query.trim(), ...searchHistory.filter((h) => h !== query.trim())]
        setSearchHistory(newHistory)
        saveSearchHistory(newHistory)
      }
      onClose()
      navigate(result.path)
    },
    [navigate, onClose, query, searchHistory]
  )

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      } else if (e.key === 'ArrowDown') {
        e.preventDefault()
        setActiveIndex((prev) => Math.min(prev + 1, results.length - 1))
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setActiveIndex((prev) => Math.max(prev - 1, 0))
      } else if (e.key === 'Enter' && results[activeIndex]) {
        e.preventDefault()
        handleSelect(results[activeIndex])
      }
    },
    [results, activeIndex, handleSelect, onClose]
  )

  const clearHistory = useCallback(() => {
    setSearchHistory([])
    saveSearchHistory([])
  }, [])

  if (!open) return null

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent
        className="overflow-hidden p-0 sm:max-w-xl top-[15vh] translate-y-0"
        showCloseButton={false}
        onInteractOutside={() => onClose()}
        data-testid="search-modal"
      >
        <DialogHeader className="sr-only">
          <DialogTitle>{t('search.label')}</DialogTitle>
          <DialogDescription>{t('search.placeholder')}</DialogDescription>
        </DialogHeader>

        {/* Search input */}
        <div className="flex items-center gap-2 border-b px-4 py-3">
          <span className="text-muted-foreground shrink-0" aria-hidden="true">
            <Search size={18} />
          </span>
          <input
            ref={inputRef}
            type="text"
            className="flex-1 border-none bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
            placeholder={t('search.placeholder')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            aria-label={t('search.label')}
            aria-autocomplete="list"
            aria-controls="search-results"
            aria-activedescendant={
              results[activeIndex] ? `search-result-${activeIndex}` : undefined
            }
            data-testid="search-input"
          />
          <kbd className="inline-flex items-center rounded border bg-muted px-1.5 text-[0.6875rem] font-sans text-muted-foreground shrink-0">
            ESC
          </kbd>
        </div>

        {/* Results list */}
        <ul
          id="search-results"
          className="list-none m-0 p-1 max-h-[400px] overflow-y-auto"
          role="listbox"
        >
          {/* Search history when no query */}
          {!query.trim() && searchHistory.length > 0 && (
            <>
              <li
                className="flex items-center justify-between px-4 pt-2 pb-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-muted-foreground"
                role="presentation"
              >
                <span>{t('search.recentSearches')}</span>
                <button
                  className="bg-transparent border-none p-0 text-[0.6875rem] text-primary cursor-pointer normal-case tracking-normal font-medium hover:underline"
                  onClick={clearHistory}
                  type="button"
                >
                  {t('common.clear')}
                </button>
              </li>
              {searchHistory.map((term) => (
                <li key={`history-${term}`}>
                  <button
                    className="flex items-center gap-2 w-full px-4 py-2 bg-transparent border-none cursor-pointer text-sm text-foreground text-left hover:bg-accent"
                    onClick={() => setQuery(term)}
                    type="button"
                  >
                    <span className="text-sm opacity-50" aria-hidden="true">
                      <Clock size={14} />
                    </span>
                    {term}
                  </button>
                </li>
              ))}
            </>
          )}

          {/* Results */}
          {!query.trim() && results.length > 0 && (
            <li
              className="flex items-center justify-between px-4 pt-2 pb-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-muted-foreground"
              role="presentation"
            >
              {t('search.recentlyViewed')}
            </li>
          )}
          {query.trim() && results.length > 0 && (
            <li
              className="flex items-center justify-between px-4 pt-2 pb-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-muted-foreground"
              role="presentation"
            >
              {t('search.results')}
              {searching && (
                <span className="font-normal normal-case tracking-normal">
                  {t('common.loading')}
                </span>
              )}
            </li>
          )}
          {results.map((result, idx) => (
            <li
              key={result.id}
              id={`search-result-${idx}`}
              role="option"
              aria-selected={idx === activeIndex}
              className={cn(
                'flex items-center gap-2 px-4 py-2 cursor-pointer transition-colors duration-100',
                idx === activeIndex ? 'bg-accent' : 'hover:bg-accent'
              )}
              onClick={() => handleSelect(result)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSelect(result)
              }}
              onMouseEnter={() => setActiveIndex(idx)}
            >
              <span
                className="text-base shrink-0 w-6 text-center text-muted-foreground"
                aria-hidden="true"
              >
                {result.type === 'page' ? (
                  <FileText size={16} />
                ) : result.type === 'recent' ? (
                  <Clock size={16} />
                ) : (
                  <ClipboardList size={16} />
                )}
              </span>
              <div className="flex flex-col gap-px flex-1 min-w-0">
                <span className="text-sm font-medium text-foreground truncate">{result.title}</span>
                <span className="text-xs text-muted-foreground truncate">{result.subtitle}</span>
              </div>
              <span className="text-[0.6875rem] text-muted-foreground whitespace-nowrap shrink-0">
                {result.type === 'page'
                  ? t('search.typePage')
                  : result.type === 'recent'
                    ? t('search.typeRecent')
                    : t('search.typeRecord')}
              </span>
            </li>
          ))}

          {query.trim() && results.length === 0 && !searching && (
            <li className="p-6 text-center text-sm text-muted-foreground">
              {t('search.noResults')}
            </li>
          )}
          {query.trim() && searching && results.length === 0 && (
            <li className="p-6 text-center text-sm text-muted-foreground">{t('common.loading')}</li>
          )}
        </ul>
      </DialogContent>
    </Dialog>
  )
}
