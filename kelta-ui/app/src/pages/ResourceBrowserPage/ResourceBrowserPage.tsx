/**
 * ResourceBrowserPage Component
 *
 * Displays a list of available collections for browsing data.
 * Users can select a collection to navigate to its data view.
 *
 * Requirements:
 * - 11.1: Resource browser allows selecting a collection
 */

import React, { useState, useCallback, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { recordRoutes } from '../../utils/recordRoutes'
import { useCollectionSummaries, type CollectionSummary } from '../../hooks/useCollectionSummaries'
import { LoadingSpinner, ErrorMessage } from '../../components'

/**
 * Props for ResourceBrowserPage component
 */
export interface ResourceBrowserPageProps {
  /** Optional test ID for testing */
  testId?: string
}

/**
 * ResourceBrowserPage Component
 *
 * Main page for browsing resources in collections.
 * Displays a list of available collections that users can select
 * to view and manage data records.
 */
export function ResourceBrowserPage({
  testId = 'resource-browser-page',
}: ResourceBrowserPageProps): React.ReactElement {
  const navigate = useNavigate()
  const { t } = useI18n()

  // Search filter state
  const [searchQuery, setSearchQuery] = useState('')

  // Fetch collections via shared hook
  const { summaries: collections, isLoading, error } = useCollectionSummaries()

  // The summary endpoint returns only active collections, so no filtering needed
  const activeCollections = collections

  // Filter collections by search query
  const filteredCollections = useMemo(() => {
    if (!searchQuery.trim()) {
      return activeCollections
    }
    const query = searchQuery.toLowerCase()
    return activeCollections.filter(
      (collection) =>
        collection?.name?.toLowerCase().includes(query) ||
        collection?.displayName?.toLowerCase().includes(query)
    )
  }, [activeCollections, searchQuery])

  // Handle search input change
  const handleSearchChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(event.target.value)
  }, [])

  // Handle collection selection - navigate to collection data view
  const handleCollectionSelect = useCallback(
    (collection: CollectionSummary) => {
      navigate(recordRoutes('admin', getTenantSlug()).list(collection.name))
    },
    [navigate]
  )

  // Handle keyboard navigation on collection cards
  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent, collection: CollectionSummary) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault()
        handleCollectionSelect(collection)
      }
    },
    [handleCollectionSelect]
  )

  // Render loading state
  if (isLoading) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Render error state
  if (error) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <ErrorMessage
          error={error instanceof Error ? error : new Error(t('errors.generic'))}
          onRetry={() => window.location.reload()}
        />
      </div>
    )
  }

  return (
    <div
      className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
      data-testid={testId}
    >
      {/* Page Header */}
      <header className="flex flex-col gap-1">
        <h1 className="m-0 text-2xl font-semibold text-foreground max-md:text-xl">
          {t('resources.title')}
        </h1>
        <p className="m-0 text-base text-muted-foreground">{t('resources.selectCollection')}</p>
      </header>

      {/* Search Filter */}
      <div
        className="relative max-w-[400px] max-md:max-w-full"
        role="search"
        aria-label={t('common.search')}
      >
        <input
          type="text"
          className="w-full rounded-md border border-border bg-card px-4 py-2 pr-10 text-sm text-foreground placeholder:text-muted-foreground/60 focus:border-ring focus:outline-none focus:ring-[3px] focus:ring-ring/20 motion-reduce:transition-none"
          placeholder={t('common.search')}
          value={searchQuery}
          onChange={handleSearchChange}
          aria-label={t('common.search')}
          data-testid="collection-search"
        />
        {searchQuery && (
          <button
            type="button"
            className="absolute right-2 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full border-none bg-transparent p-0 text-lg text-muted-foreground hover:bg-muted hover:text-foreground focus:outline-2 focus:outline-offset-2 focus:outline-ring motion-reduce:transition-none"
            onClick={() => setSearchQuery('')}
            aria-label={t('common.clear')}
            data-testid="clear-search"
          >
            &times;
          </button>
        )}
      </div>

      {/* Collections Grid */}
      {filteredCollections.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center rounded-md bg-muted p-12 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          {activeCollections.length === 0 ? (
            <p className="m-0 text-base">{t('common.noData')}</p>
          ) : (
            <p className="m-0 text-base">{t('common.noResults')}</p>
          )}
        </div>
      ) : (
        <div
          className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-4 max-lg:grid-cols-[repeat(auto-fill,minmax(250px,1fr))] max-md:grid-cols-1"
          role="list"
          aria-label={t('resources.title')}
          data-testid="collections-grid"
        >
          {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
          {filteredCollections.map((collection, index) => (
            <div
              key={collection.id}
              className="flex cursor-pointer flex-col rounded-lg border border-border bg-card p-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-muted-foreground/40 hover:shadow-md focus:border-ring focus:outline-none focus:ring-[3px] focus:ring-ring/20 active:translate-y-0 motion-reduce:transition-none motion-reduce:hover:translate-y-0 max-md:p-2"
              role="listitem"
              tabIndex={0}
              onClick={() => handleCollectionSelect(collection)}
              onKeyDown={(e) => handleKeyDown(e, collection)}
              aria-label={collection.displayName}
              data-testid={`collection-card-${index}`}
            >
              <div className="mb-2 flex flex-col gap-1">
                <h2 className="m-0 text-lg font-semibold leading-snug text-foreground">
                  {collection.displayName}
                </h2>
                <span className="font-mono text-xs text-muted-foreground/60">
                  {collection.name}
                </span>
              </div>
            </div>
          ))}
          {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
        </div>
      )}

      {/* Results count */}
      {filteredCollections.length > 0 && (
        <div
          className="text-center text-sm text-muted-foreground/60"
          aria-live="polite"
          data-testid="results-count"
        >
          {filteredCollections.length}{' '}
          {filteredCollections.length === 1 ? 'collection' : 'collections'}
          {searchQuery && ` matching "${searchQuery}"`}
        </div>
      )}
    </div>
  )
}
