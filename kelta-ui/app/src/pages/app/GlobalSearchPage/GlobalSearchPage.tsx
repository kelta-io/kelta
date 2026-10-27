/**
 * GlobalSearchPage
 *
 * Full-page search results view. Searches across all configured collections
 * and displays results grouped by collection type.
 *
 * Features:
 * - Multi-collection parallel search
 * - Results grouped by collection with record counts
 * - Clickable results navigate to record detail
 * - Search input with debounced query
 * - Empty states and loading indicators
 * - Breadcrumb navigation
 */

import React, { useState, useMemo, useCallback, useEffect } from 'react'
import { useNavigate, useParams, useSearchParams, Link } from 'react-router-dom'
import { Search, Loader2, FileText, ArrowRight, Database } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import type { MenuConfig, MenuItemConfig } from '@/types/config'
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb'
import { useApi } from '@/context/ApiContext'
import { useConfig } from '@/context/ConfigContext'
import { recordRoutes } from '@/utils/recordRoutes'

interface SearchResult {
  id: string
  collectionName: string
  collectionLabel: string
  displayValue: string
  subtitle?: string
}

/**
 * Extract collection names from menu config.
 */
function getCollectionNames(
  config: { menus?: MenuConfig[] } | null
): Array<{ name: string; label: string }> {
  if (!config?.menus) return []
  const collections: Array<{ name: string; label: string }> = []
  const visit = (items: MenuItemConfig[] | undefined) => {
    for (const item of items ?? []) {
      if (item.path?.startsWith('/resources/')) {
        const name = item.path.replace('/resources/', '').split('/')[0]
        if (name) {
          collections.push({
            name,
            label: item.label || name.charAt(0).toUpperCase() + name.slice(1),
          })
        }
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

export function GlobalSearchPage(): React.ReactElement {
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { apiClient } = useApi()
  const { config } = useConfig()

  const initialQuery = searchParams.get('q') || ''
  const [query, setQuery] = useState(initialQuery)
  const debouncedQuery = useDebounced(query, 300)
  const hasQuery = debouncedQuery.trim().length >= 3
  const basePath = `/${tenantSlug}/app`
  const routes = recordRoutes('enduser', tenantSlug ?? '')

  const collections = useMemo(() => getCollectionNames(config), [config])
  const collectionLabelMap = useMemo(() => {
    const map: Record<string, string> = {}
    for (const col of collections) {
      map[col.name] = col.label
    }
    return map
  }, [collections])

  // Update URL params when debounced query changes
  const updateSearchParams = useCallback(
    (q: string) => {
      if (q.trim()) {
        setSearchParams({ q: q.trim() }, { replace: true })
      } else {
        setSearchParams({}, { replace: true })
      }
    },
    [setSearchParams]
  )

  // Sync URL on debounced query change
  useEffect(() => {
    updateSearchParams(debouncedQuery)
  }, [debouncedQuery, updateSearchParams])

  // Search using centralized full-text search endpoint
  const {
    data: searchResults = [],
    isLoading,
    isFetching,
  } = useQuery({
    queryKey: ['page-search', debouncedQuery],
    queryFn: async () => {
      if (!hasQuery) return []

      try {
        const response = await apiClient.get(
          `/api/_search?q=${encodeURIComponent(debouncedQuery)}&limit=50`
        )
        const data = (response as { data?: Array<Record<string, unknown>> })?.data
        if (!Array.isArray(data)) return []

        return data.map((item): SearchResult => ({
          id: String(item.id),
          collectionName: String(item.collectionName),
          collectionLabel:
            collectionLabelMap[String(item.collectionName)] || String(item.collectionName),
          displayValue: String(item.displayValue || item.id),
        }))
      } catch {
        return []
      }
    },
    enabled: hasQuery,
    staleTime: 10 * 1000,
  })

  // Group results by collection
  const groupedResults = useMemo(() => {
    const groups = new Map<string, SearchResult[]>()
    for (const result of searchResults) {
      const existing = groups.get(result.collectionName) || []
      existing.push(result)
      groups.set(result.collectionName, existing)
    }
    return groups
  }, [searchResults])

  const totalResults = searchResults.length

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      {/* Breadcrumb */}
      <Breadcrumb>
        <BreadcrumbList>
          <BreadcrumbItem>
            <BreadcrumbLink asChild>
              <Link to={`${basePath}/home`}>Home</Link>
            </BreadcrumbLink>
          </BreadcrumbItem>
          <BreadcrumbSeparator />
          <BreadcrumbItem>
            <BreadcrumbPage>Search</BreadcrumbPage>
          </BreadcrumbItem>
        </BreadcrumbList>
      </Breadcrumb>

      {/* Search input */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          placeholder="Search across all collections..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="pl-10 text-base"
          autoFocus
        />
        {isFetching && (
          <Loader2 className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-muted-foreground" />
        )}
      </div>

      {/* Results header */}
      {hasQuery && !isLoading && (
        <p className="text-sm text-muted-foreground">
          {totalResults === 0
            ? `No results for "${debouncedQuery}"`
            : `${totalResults} result${totalResults !== 1 ? 's' : ''} for "${debouncedQuery}"`}
          {groupedResults.size > 0 &&
            ` across ${groupedResults.size} collection${groupedResults.size !== 1 ? 's' : ''}`}
        </p>
      )}

      {/* Loading state */}
      {isLoading && hasQuery && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      )}

      {/* No query state */}
      {!hasQuery && (
        <Card>
          <CardContent className="py-12">
            <div className="flex flex-col items-center justify-center text-center">
              <Search className="mb-4 h-12 w-12 text-muted-foreground/30" />
              <p className="text-sm text-muted-foreground">
                Enter at least 3 characters to search across all collections.
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      {/* No results state */}
      {hasQuery && !isLoading && totalResults === 0 && (
        <Card>
          <CardContent className="py-12">
            <div className="flex flex-col items-center justify-center text-center">
              <Search className="mb-4 h-12 w-12 text-muted-foreground/30" />
              <p className="text-sm font-medium text-foreground">No results found</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Try adjusting your search terms or check for typos.
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Results grouped by collection */}
      {Array.from(groupedResults.entries()).map(([collectionName, results]) => (
        <Card key={collectionName}>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-base">
              <Database className="h-4 w-4 text-muted-foreground" />
              {results[0]?.collectionLabel || collectionName}
              <Badge variant="secondary" className="text-xs">
                {results.length}
              </Badge>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-1">
              {results.map((result) => (
                <button
                  key={`${result.collectionName}-${result.id}`}
                  onClick={() => navigate(routes.detail(result.collectionName, result.id))}
                  className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-left text-sm transition-colors hover:bg-accent"
                >
                  <FileText className="h-4 w-4 flex-shrink-0 text-muted-foreground" />
                  <div className="flex-1 overflow-hidden">
                    <span className="block truncate font-medium text-foreground">
                      {result.displayValue}
                    </span>
                    {result.subtitle && (
                      <span className="block truncate text-xs text-muted-foreground">
                        {result.subtitle}
                      </span>
                    )}
                  </div>
                  <ArrowRight className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" />
                </button>
              ))}
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
