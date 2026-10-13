import React, { useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import type { SearchIndexStats, SearchIndexCollectionStats } from '@kelta/sdk'

export interface SearchSettingsPageProps {
  testId?: string
}

export function SearchSettingsPage({
  testId = 'search-settings-page',
}: SearchSettingsPageProps): React.ReactElement {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const queryClient = useQueryClient()
  const [reindexingCollection, setReindexingCollection] = useState<string | null>(null)

  const {
    data: stats,
    isLoading,
    error,
  } = useQuery<SearchIndexStats>({
    queryKey: ['search-index-stats'],
    queryFn: () => keltaClient.admin.searchReindex.getStats(),
    refetchInterval: reindexingCollection !== null ? 5000 : false,
  })

  const reindexMutation = useMutation({
    mutationFn: (collectionName?: string) =>
      keltaClient.admin.searchReindex.reindex(collectionName),
    onSuccess: (data) => {
      const result = data as { message?: string; collection?: string }
      toast.success(result.message || t('searchSettings.reindexStarted'))
      setReindexingCollection(result.collection ?? null)
      // Poll for updated stats
      setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: ['search-index-stats'] })
        setReindexingCollection(null)
      }, 10000)
    },
    onError: () => {
      toast.error(t('searchSettings.reindexError'))
    },
  })

  const handleReindexAll = () => {
    setReindexingCollection('ALL')
    reindexMutation.mutate(undefined)
  }

  const handleReindexCollection = (collectionName: string) => {
    setReindexingCollection(collectionName)
    reindexMutation.mutate(collectionName)
  }

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('searchSettings.loadError')} />

  return (
    <div className="mx-auto max-w-[1000px] p-8 max-[767px]:p-4" data-testid={testId}>
      {/* Header */}
      <div className="mb-6 flex items-center justify-between gap-4 max-[767px]:flex-col max-[767px]:items-stretch">
        <div>
          <h1 className="m-0 text-[1.75rem] font-bold text-foreground max-[767px]:text-[1.375rem]">
            {t('searchSettings.title')}
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">{t('searchSettings.description')}</p>
        </div>
        <button
          data-testid="reindex-all-btn"
          className={cn(
            'shrink-0 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground',
            'hover:bg-primary/90 disabled:opacity-50'
          )}
          onClick={handleReindexAll}
          disabled={reindexMutation.isPending}
        >
          {reindexMutation.isPending && reindexingCollection === 'ALL'
            ? t('searchSettings.reindexing')
            : t('searchSettings.reindexAll')}
        </button>
      </div>

      {/* Summary Card */}
      <div className="mb-6 rounded-lg border border-border bg-card p-6">
        <div className="flex items-center gap-8">
          <div className="flex flex-col items-center gap-1">
            <span className="text-[1.75rem] font-bold leading-tight text-primary">
              {stats?.totalIndexed?.toLocaleString() ?? 0}
            </span>
            <span className="text-center text-[0.8125rem] font-medium text-muted-foreground">
              {t('searchSettings.totalIndexed')}
            </span>
          </div>
          <div className="flex flex-col items-center gap-1">
            <span className="text-[1.75rem] font-bold leading-tight text-primary">
              {stats?.collections?.length ?? 0}
            </span>
            <span className="text-center text-[0.8125rem] font-medium text-muted-foreground">
              {t('searchSettings.collectionsCount')}
            </span>
          </div>
        </div>
        {reindexingCollection && (
          <div className="mt-4 flex items-center gap-2 text-sm text-muted-foreground">
            <div className="h-3 w-3 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            {reindexingCollection === 'ALL'
              ? t('searchSettings.reindexingAll')
              : t('searchSettings.reindexingCollection', { name: reindexingCollection })}
          </div>
        )}
      </div>

      {/* Collections Table */}
      <div className="overflow-hidden rounded-lg border border-border bg-card">
        <div className="border-b border-border bg-muted px-5 py-3">
          <h2 className="m-0 text-[0.9375rem] font-semibold text-foreground">
            {t('searchSettings.collectionsTitle')}
          </h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left">
                <th className="px-5 py-3 font-medium text-muted-foreground">
                  {t('searchSettings.collectionName')}
                </th>
                <th className="px-5 py-3 text-right font-medium text-muted-foreground">
                  {t('searchSettings.indexedRecords')}
                </th>
                <th className="px-5 py-3 text-right font-medium text-muted-foreground">
                  {t('common.actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {stats?.collections && stats.collections.length > 0 ? (
                stats.collections.map((col: SearchIndexCollectionStats) => (
                  <tr key={col.collectionId} className="border-b border-border last:border-b-0">
                    <td className="px-5 py-3 font-medium text-foreground">{col.collectionName}</td>
                    <td className="px-5 py-3 text-right text-foreground tabular-nums">
                      {col.indexedRecords.toLocaleString()}
                    </td>
                    <td className="px-5 py-3 text-right">
                      <button
                        data-testid={`reindex-${col.collectionName}`}
                        className={cn(
                          'rounded-md border border-border bg-background px-3 py-1 text-xs font-medium text-foreground',
                          'hover:bg-muted disabled:opacity-50'
                        )}
                        onClick={() => handleReindexCollection(col.collectionName)}
                        disabled={reindexMutation.isPending}
                      >
                        {reindexMutation.isPending && reindexingCollection === col.collectionName
                          ? t('searchSettings.reindexing')
                          : t('searchSettings.reindex')}
                      </button>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={3} className="px-5 py-8 text-center text-sm text-muted-foreground">
                    {t('searchSettings.noCollections')}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default SearchSettingsPage
