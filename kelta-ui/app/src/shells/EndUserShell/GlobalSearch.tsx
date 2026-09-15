/**
 * GlobalSearch Component
 *
 * A command palette (Cmd+K) for searching across all collections.
 * Uses shadcn's Command dialog with grouped search results.
 *
 * Features:
 * - Debounced search across configured collections
 * - Results grouped by collection type
 * - Recent items shown when no query
 * - Navigation shortcuts (Home, Setup)
 * - Full-page search link for deeper exploration
 */

import React, { useState, useCallback, useMemo, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Search, FileText, ArrowRight, Clock, Database, Settings, Home } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '@/components/ui/command'
import { Badge } from '@/components/ui/badge'
import { useApi } from '@/context/ApiContext'
import { useConfig } from '@/context/ConfigContext'
import { useAppContext } from '@/context/AppContext'
import { parseResourcePath } from './navTabs'
import type { MenuConfig, MenuItemConfig } from '@/types/config'

interface SearchResult {
  id: string
  collectionName: string
  collectionLabel: string
  displayValue: string
}

/**
 * Extract collection tabs from menu config.
 */
function getCollectionNames(
  config: { menus?: MenuConfig[] } | null
): Array<{ name: string; label: string }> {
  if (!config?.menus) return []
  const collections: Array<{ name: string; label: string }> = []
  const visit = (items: MenuItemConfig[] | undefined) => {
    for (const item of items ?? []) {
      const parsed = item.path ? parseResourcePath(item.path) : null
      if (parsed) {
        const name = parsed.collectionName
        collections.push({
          name,
          label: item.label || name.charAt(0).toUpperCase() + name.slice(1),
        })
      }
      // Submenu groups: collections may nest one level down.
      if (item.children?.length) visit(item.children)
    }
  }
  for (const menu of config.menus) {
    visit(menu.items)
  }
  return collections
}

/**
 * Format a timestamp into relative time.
 */
function formatRelativeTime(timestamp: number): string {
  const diffMs = Date.now() - timestamp
  const diffMinutes = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMinutes / 60)
  const diffDays = Math.floor(diffHours / 24)

  if (diffMinutes < 1) return 'just now'
  if (diffMinutes < 60) return `${diffMinutes}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays === 1) return 'yesterday'
  return `${diffDays}d ago`
}

/**
 * Simple debounce hook.
 */
function useDebounced(value: string, delay: number): string {
  const [debouncedValue, setDebouncedValue] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value)
    }, delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debouncedValue
}

interface GlobalSearchProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function GlobalSearch({ open, onOpenChange }: GlobalSearchProps): React.ReactElement {
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const navigate = useNavigate()
  const { apiClient } = useApi()
  const { config } = useConfig()
  const { recentItems } = useAppContext()
  const [query, setQuery] = useState('')

  const collections = useMemo(() => getCollectionNames(config), [config])
  const collectionLabelMap = useMemo(() => {
    const map: Record<string, string> = {}
    for (const col of collections) {
      map[col.name] = col.label
    }
    return map
  }, [collections])
  const debouncedQuery = useDebounced(query, 300)
  const hasQuery = debouncedQuery.trim().length >= 3

  // Search using centralized full-text search endpoint
  const { data: searchResults = [], isLoading } = useQuery({
    queryKey: ['global-search', debouncedQuery],
    queryFn: async () => {
      if (!hasQuery) return []

      try {
        const response = await apiClient.get(
          `/api/_search?q=${encodeURIComponent(debouncedQuery)}&limit=20`
        )
        const data = (response as { data?: Array<Record<string, unknown>> })?.data
        if (!Array.isArray(data)) return []

        return data.map(
          (item): SearchResult => ({
            id: String(item.id),
            collectionName: String(item.collectionName),
            collectionLabel:
              collectionLabelMap[String(item.collectionName)] || String(item.collectionName),
            displayValue: String(item.displayValue || item.id),
          })
        )
      } catch {
        return []
      }
    },
    enabled: open && hasQuery,
    staleTime: 10 * 1000,
  })

  // Group search results by collection
  const groupedResults = useMemo(() => {
    const groups = new Map<string, SearchResult[]>()
    for (const result of searchResults) {
      const existing = groups.get(result.collectionName) || []
      existing.push(result)
      groups.set(result.collectionName, existing)
    }
    return groups
  }, [searchResults])

  const handleSelect = useCallback(
    (path: string) => {
      onOpenChange(false)
      setQuery('')
      navigate(path)
    },
    [navigate, onOpenChange]
  )

  const handleOpenChange = useCallback(
    (newOpen: boolean) => {
      if (!newOpen) {
        setQuery('')
      }
      onOpenChange(newOpen)
    },
    [onOpenChange]
  )

  const basePath = `/${tenantSlug}/app`

  return (
    <CommandDialog open={open} onOpenChange={handleOpenChange} shouldFilter={false}>
      <CommandInput
        placeholder="Search records, collections, pages..."
        value={query}
        onValueChange={setQuery}
      />
      <CommandList>
        <CommandEmpty>
          <div className="flex flex-col items-center gap-2 py-6 text-center">
            <Search className="h-8 w-8 text-muted-foreground/50" />
            <p className="text-sm text-muted-foreground">
              {hasQuery && !isLoading
                ? 'No results found. Try a different query.'
                : 'Type at least 3 characters to search.'}
            </p>
          </div>
        </CommandEmpty>

        {/* Search results grouped by collection */}
        {hasQuery &&
          Array.from(groupedResults.entries()).map(([collectionName, results]) => (
            <CommandGroup
              key={collectionName}
              heading={results[0]?.collectionLabel || collectionName}
            >
              {results.map((result) => (
                <CommandItem
                  key={`${result.collectionName}-${result.id}`}
                  onSelect={() =>
                    handleSelect(`${basePath}/o/${result.collectionName}/${result.id}`)
                  }
                >
                  <FileText className="mr-2 h-4 w-4 text-muted-foreground" />
                  <span className="flex-1 truncate">{result.displayValue}</span>
                  <Badge variant="secondary" className="ml-2 text-[10px]">
                    {result.collectionLabel}
                  </Badge>
                </CommandItem>
              ))}
            </CommandGroup>
          ))}

        {/* Full search page link */}
        {hasQuery && searchResults.length > 0 && (
          <>
            <CommandSeparator />
            <CommandGroup>
              <CommandItem
                onSelect={() =>
                  handleSelect(`${basePath}/search?q=${encodeURIComponent(debouncedQuery)}`)
                }
              >
                <Search className="mr-2 h-4 w-4" />
                <span>View all results for &ldquo;{debouncedQuery}&rdquo;</span>
                <ArrowRight className="ml-auto h-4 w-4 text-muted-foreground" />
              </CommandItem>
            </CommandGroup>
          </>
        )}

        {/* Recent items (when no query) */}
        {!hasQuery && recentItems.length > 0 && (
          <CommandGroup heading="Recent">
            {recentItems.slice(0, 5).map((item) => (
              <CommandItem
                key={`recent-${item.collectionName}-${item.id}`}
                onSelect={() => handleSelect(`${basePath}/o/${item.collectionName}/${item.id}`)}
              >
                <Clock className="mr-2 h-4 w-4 text-muted-foreground" />
                <span className="flex-1 truncate">{item.label}</span>
                <span className="text-xs text-muted-foreground">
                  {formatRelativeTime(item.timestamp)}
                </span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}

        {/* Collections (when no query) */}
        {!hasQuery && collections.length > 0 && (
          <>
            <CommandSeparator />
            <CommandGroup heading="Collections">
              {collections.map((col) => (
                <CommandItem
                  key={`col-${col.name}`}
                  onSelect={() => handleSelect(`${basePath}/o/${col.name}`)}
                >
                  <Database className="mr-2 h-4 w-4 text-muted-foreground" />
                  <span>{col.label}</span>
                  <ArrowRight className="ml-auto h-4 w-4 text-muted-foreground" />
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        )}

        {/* Quick navigation */}
        {!hasQuery && (
          <>
            <CommandSeparator />
            <CommandGroup heading="Navigation">
              <CommandItem onSelect={() => handleSelect(`${basePath}/home`)}>
                <Home className="mr-2 h-4 w-4" />
                <span>Go to Home</span>
                <ArrowRight className="ml-auto h-4 w-4 text-muted-foreground" />
              </CommandItem>
              <CommandItem onSelect={() => handleSelect(`/${tenantSlug}/setup`)}>
                <Settings className="mr-2 h-4 w-4" />
                <span>Switch to Setup</span>
                <ArrowRight className="ml-auto h-4 w-4 text-muted-foreground" />
              </CommandItem>
            </CommandGroup>
          </>
        )}
      </CommandList>
    </CommandDialog>
  )
}
