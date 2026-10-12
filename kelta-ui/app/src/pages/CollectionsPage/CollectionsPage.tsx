/**
 * CollectionsPage Component
 *
 * Displays a paginated list of collections with filtering, sorting, and CRUD actions.
 *
 * Requirements:
 * - 3.1: Display a paginated list of all collections
 * - 3.2: Support filtering collections by name and status
 * - 3.3: Support sorting collections by name, creation date, and modification date
 * - 3.4: Create collection action
 * - 3.10: Display confirmation dialog before deletion
 * - 3.11: Soft-delete collection and remove from list
 */

import React, { useState, useCallback, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useApi } from '../../context/ApiContext'
import { useToast, ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

/**
 * Collection interface matching the API response
 */
export interface Collection {
  id: string
  name: string
  displayName: string
  description?: string
  active: boolean
  systemCollection?: boolean
  currentVersion: number
  createdAt: string
  updatedAt: string
}

/**
 * Filter state for collections
 */
interface CollectionFilters {
  name: string
  status: 'all' | 'active' | 'inactive'
}

/**
 * Sort state for collections
 */
interface CollectionSort {
  field: 'name' | 'createdAt' | 'updatedAt'
  direction: 'asc' | 'desc'
}

/**
 * Props for CollectionsPage component
 */
export interface CollectionsPageProps {
  /** Optional test ID for testing */
  testId?: string
}

/**
 * CollectionsPage Component
 *
 * Main page for managing collections in the Kelta Admin UI.
 * Provides listing, filtering, sorting, and CRUD operations.
 */
export function CollectionsPage({
  testId = 'collections-page',
}: CollectionsPageProps): React.ReactElement {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()
  const { showToast } = useToast()

  // Show system collections toggle
  const [showSystem, setShowSystem] = useState(false)

  // Filter state
  const [filters, setFilters] = useState<CollectionFilters>({
    name: '',
    status: 'all',
  })

  // Sort state
  const [sort, setSort] = useState<CollectionSort>({
    field: 'name',
    direction: 'asc',
  })

  // Pagination state
  const [page, setPage] = useState(1)
  const pageSize = 10

  // Delete confirmation dialog state
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [collectionToDelete, setCollectionToDelete] = useState<Collection | null>(null)

  // Fetch collections query
  const {
    data: allCollections,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['collections'],
    queryFn: () => keltaClient.admin.collections.list(),
  })

  // Extract collections, filtering system collections client-side
  const collections = useMemo(() => {
    const items = allCollections || []
    if (showSystem) return items as unknown as Collection[]
    return (items as unknown as Collection[]).filter((c) => !c.systemCollection)
  }, [allCollections, showSystem])

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.collections.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] })
      showToast(t('success.deleted', { item: t('collections.title') }), 'success')
      setDeleteDialogOpen(false)
      setCollectionToDelete(null)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Filter collections
  const filteredCollections = useMemo(() => {
    return collections.filter((collection) => {
      // Filter by name
      if (
        filters.name &&
        !collection.name.toLowerCase().includes(filters.name.toLowerCase()) &&
        !collection.displayName.toLowerCase().includes(filters.name.toLowerCase())
      ) {
        return false
      }

      // Filter by status
      if (filters.status === 'active' && !collection.active) {
        return false
      }
      if (filters.status === 'inactive' && collection.active) {
        return false
      }

      return true
    })
  }, [collections, filters])

  // Sort collections
  const sortedCollections = useMemo(() => {
    return [...filteredCollections].sort((a, b) => {
      let comparison = 0

      switch (sort.field) {
        case 'name':
          comparison = a.name.localeCompare(b.name)
          break
        case 'createdAt':
          comparison = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
          break
        case 'updatedAt':
          comparison = new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime()
          break
      }

      return sort.direction === 'asc' ? comparison : -comparison
    })
  }, [filteredCollections, sort])

  // Paginate collections
  const paginatedCollections = useMemo(() => {
    const startIndex = (page - 1) * pageSize
    return sortedCollections.slice(startIndex, startIndex + pageSize)
  }, [sortedCollections, page, pageSize])

  // Calculate total pages
  const totalPages = Math.ceil(sortedCollections.length / pageSize)

  // Handle filter change
  const handleNameFilterChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    setFilters((prev) => ({ ...prev, name: event.target.value }))
    setPage(1)
  }, [])

  const handleStatusFilterChange = useCallback((event: React.ChangeEvent<HTMLSelectElement>) => {
    setFilters((prev) => ({ ...prev, status: event.target.value as CollectionFilters['status'] }))
    setPage(1)
  }, [])

  // Handle show system collections toggle
  const handleShowSystemChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    setShowSystem(event.target.checked)
    setPage(1)
  }, [])

  // Handle sort change
  const handleSortChange = useCallback((field: CollectionSort['field']) => {
    setSort((prev) => ({
      field,
      direction: prev.field === field && prev.direction === 'asc' ? 'desc' : 'asc',
    }))
  }, [])

  // Handle create action
  const handleCreate = useCallback(() => {
    navigate(`/${getTenantSlug()}/collections/new`)
  }, [navigate])

  // Handle edit action
  const handleEdit = useCallback(
    (collection: Collection) => {
      navigate(`/${getTenantSlug()}/collections/${collection.id}/edit`)
    },
    [navigate]
  )

  // Handle view/detail action
  const handleView = useCallback(
    (collection: Collection) => {
      navigate(`/${getTenantSlug()}/collections/${collection.id}`)
    },
    [navigate]
  )

  // Handle delete action - open confirmation dialog
  const handleDeleteClick = useCallback((collection: Collection) => {
    setCollectionToDelete(collection)
    setDeleteDialogOpen(true)
  }, [])

  // Handle delete confirmation
  const handleDeleteConfirm = useCallback(() => {
    if (collectionToDelete) {
      deleteMutation.mutate(collectionToDelete.id)
    }
  }, [collectionToDelete, deleteMutation])

  // Handle delete cancel
  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
    setCollectionToDelete(null)
  }, [])

  // Handle page change
  const handlePageChange = useCallback((newPage: number) => {
    setPage(newPage)
  }, [])

  // Get sort indicator
  const getSortIndicator = useCallback(
    (field: CollectionSort['field']) => {
      if (sort.field !== field) return null
      return sort.direction === 'asc' ? ' \u2191' : ' \u2193'
    },
    [sort]
  )

  // Get aria-sort value
  const getAriaSort = useCallback(
    (field: CollectionSort['field']): 'ascending' | 'descending' | 'none' => {
      if (sort.field !== field) return 'none'
      return sort.direction === 'asc' ? 'ascending' : 'descending'
    },
    [sort]
  )

  // Render loading state
  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Render error state
  if (error) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error(t('errors.generic'))}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      {/* Page Header */}
      <header className="flex items-center justify-between">
        <h1 className="m-0 text-2xl font-semibold text-foreground">{t('collections.title')}</h1>
        <button
          type="button"
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={handleCreate}
          aria-label={t('collections.createCollection')}
          data-testid="create-collection-button"
        >
          {t('collections.createCollection')}
        </button>
      </header>

      {/* Filters */}
      <div
        className="flex flex-wrap items-end gap-4 rounded-lg border border-border bg-card p-4"
        role="search"
        aria-label={t('common.filter')}
      >
        <div className="flex flex-col gap-1">
          <label htmlFor="name-filter" className="text-sm font-medium text-muted-foreground">
            {t('collections.collectionName')}
          </label>
          <input
            id="name-filter"
            type="text"
            className="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
            placeholder={t('common.search')}
            value={filters.name}
            onChange={handleNameFilterChange}
            aria-label={t('collections.collectionName')}
            data-testid="name-filter"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="status-filter" className="text-sm font-medium text-muted-foreground">
            {t('collections.status')}
          </label>
          <select
            id="status-filter"
            className="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
            value={filters.status}
            onChange={handleStatusFilterChange}
            aria-label={t('collections.status')}
            data-testid="status-filter"
          >
            <option value="all">{t('common.selectAll')}</option>
            <option value="active">{t('collections.active')}</option>
            <option value="inactive">{t('collections.inactive')}</option>
          </select>
        </div>
        <div className="flex items-center">
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={showSystem}
              onChange={handleShowSystemChange}
              className="h-4 w-4 accent-primary"
              data-testid="show-system-toggle"
            />
            <span>{t('collections.showSystem')}</span>
          </label>
        </div>
      </div>

      {/* Collections Table */}
      {paginatedCollections.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card p-16 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          <p>{t('common.noResults')}</p>
        </div>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table
              className="w-full border-collapse text-sm"
              role="grid"
              aria-label={t('collections.title')}
              data-testid="collections-table"
            >
              <thead>
                <tr role="row" className="bg-muted">
                  <th
                    role="columnheader"
                    scope="col"
                    className="cursor-pointer select-none border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
                    onClick={() => handleSortChange('name')}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        handleSortChange('name')
                      }
                    }}
                    tabIndex={0}
                    aria-sort={getAriaSort('name')}
                    data-testid="header-name"
                  >
                    {t('collections.collectionName')}
                    <span className="text-xs opacity-40" aria-hidden="true">
                      {getSortIndicator('name')}
                    </span>
                  </th>
                  <th
                    role="columnheader"
                    scope="col"
                    className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                  >
                    {t('collections.displayName')}
                  </th>
                  <th
                    role="columnheader"
                    scope="col"
                    className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                  >
                    {t('collections.status')}
                  </th>
                  <th
                    role="columnheader"
                    scope="col"
                    className="cursor-pointer select-none border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
                    onClick={() => handleSortChange('createdAt')}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        handleSortChange('createdAt')
                      }
                    }}
                    tabIndex={0}
                    aria-sort={getAriaSort('createdAt')}
                    data-testid="header-created"
                  >
                    {t('common.create')}d
                    <span className="text-xs opacity-40" aria-hidden="true">
                      {getSortIndicator('createdAt')}
                    </span>
                  </th>
                  <th
                    role="columnheader"
                    scope="col"
                    className="cursor-pointer select-none border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
                    onClick={() => handleSortChange('updatedAt')}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        handleSortChange('updatedAt')
                      }
                    }}
                    tabIndex={0}
                    aria-sort={getAriaSort('updatedAt')}
                    data-testid="header-updated"
                  >
                    {t('common.edit')}ed
                    <span className="text-xs opacity-40" aria-hidden="true">
                      {getSortIndicator('updatedAt')}
                    </span>
                  </th>
                  <th
                    role="columnheader"
                    scope="col"
                    className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                  >
                    {t('common.actions')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {paginatedCollections.map((collection, index) => (
                  <tr
                    key={collection.id}
                    role="row"
                    className="cursor-pointer border-b border-border transition-colors last:border-b-0 hover:bg-muted/50"
                    onClick={() => handleView(collection)}
                    data-testid={`collection-row-${index}`}
                  >
                    <td role="gridcell" className="px-4 py-3 font-medium text-foreground">
                      {collection.name}
                    </td>
                    <td role="gridcell" className="px-4 py-3 text-foreground">
                      {collection.displayName}
                    </td>
                    <td role="gridcell" className="px-4 py-3">
                      <span
                        className={cn(
                          'inline-block rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wider',
                          collection.active
                            ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                            : 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
                        )}
                        data-testid={`status-badge-${index}`}
                      >
                        {collection.active ? t('collections.active') : t('collections.inactive')}
                      </span>
                    </td>
                    <td role="gridcell" className="px-4 py-3 text-foreground">
                      {formatDate(new Date(collection.createdAt), {
                        year: 'numeric',
                        month: 'short',
                        day: 'numeric',
                      })}
                    </td>
                    <td role="gridcell" className="px-4 py-3 text-foreground">
                      {formatDate(new Date(collection.updatedAt), {
                        year: 'numeric',
                        month: 'short',
                        day: 'numeric',
                      })}
                    </td>
                    <td role="gridcell" className="px-4 py-3 text-right">
                      <div
                        className="flex justify-end gap-2"
                        onClick={(e) => e.stopPropagation()}
                        onKeyDown={(e) => e.stopPropagation()}
                        role="toolbar"
                      >
                        <button
                          type="button"
                          className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-primary hover:border-primary hover:bg-muted"
                          onClick={() => handleEdit(collection)}
                          aria-label={`${t('common.edit')} ${collection.name}`}
                          data-testid={`edit-button-${index}`}
                        >
                          {t('common.edit')}
                        </button>
                        <button
                          type="button"
                          className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-destructive hover:border-destructive hover:bg-destructive/10"
                          onClick={() => handleDeleteClick(collection)}
                          aria-label={`${t('common.delete')} ${collection.name}`}
                          data-testid={`delete-button-${index}`}
                        >
                          {t('common.delete')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <nav
              className="flex items-center justify-center gap-4"
              role="navigation"
              aria-label="Table pagination"
              data-testid="pagination"
            >
              <button
                type="button"
                className="rounded-md border border-border bg-card px-3 py-1.5 text-sm text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                disabled={page <= 1}
                onClick={() => handlePageChange(page - 1)}
                aria-label={t('common.previous')}
              >
                {t('common.previous')}
              </button>
              <span className="text-sm text-muted-foreground" aria-live="polite">
                Page {page} of {totalPages}
                <span className="ml-1 text-muted-foreground">
                  ({sortedCollections.length} total)
                </span>
              </span>
              <button
                type="button"
                className="rounded-md border border-border bg-card px-3 py-1.5 text-sm text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                disabled={page >= totalPages}
                onClick={() => handlePageChange(page + 1)}
                aria-label={t('common.next')}
              >
                {t('common.next')}
              </button>
            </nav>
          )}
        </>
      )}

      {/* Delete Confirmation Dialog */}
      <ConfirmDialog
        open={deleteDialogOpen}
        title={t('collections.deleteCollection')}
        message={t('collections.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
        variant="danger"
      />
    </div>
  )
}

export default CollectionsPage
