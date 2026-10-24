import React, { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

export interface SetupAuditTrailPageProps {
  className?: string
}

interface AuditEntry {
  id: string
  userId: string | null
  action: string
  section: string
  entityType: string
  entityId?: string
  entityName?: string
  oldValue?: string
  newValue?: string
  timestamp: string
}

export function SetupAuditTrailPage({ className }: SetupAuditTrailPageProps): React.ReactElement {
  const { t } = useI18n()
  const { keltaClient } = useApi()

  const [section, setSection] = useState<string>('')
  const [entityType, setEntityType] = useState<string>('')
  const [page, setPage] = useState(0)
  const pageSize = 50
  const [expandedRows, setExpandedRows] = useState<Set<string>>(new Set())

  const {
    data: auditData,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['audit-trail', section, entityType, page],
    queryFn: () =>
      keltaClient.admin.audit.list({
        section: section || undefined,
        entityType: entityType || undefined,
        page,
        size: pageSize,
      }),
  })

  const toggleRow = useCallback((id: string) => {
    setExpandedRows((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }, [])

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('audit.loadError')} />

  const entries: AuditEntry[] = auditData?.content || []
  const totalPages = auditData?.totalPages || 0

  return (
    <div className={cn('mx-auto max-w-[1200px] p-6', className)}>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="m-0 text-2xl font-semibold text-foreground">{t('audit.title')}</h1>
      </div>

      <div className="mb-6 flex flex-wrap gap-4">
        <div className="flex flex-col gap-1">
          <label htmlFor="section-filter" className="text-sm font-medium text-muted-foreground">
            {t('audit.section')}
          </label>
          <select
            id="section-filter"
            value={section}
            onChange={(e) => {
              setSection(e.target.value)
              setPage(0)
            }}
            className="min-w-[200px] rounded-md border border-border bg-background p-2 text-sm text-foreground"
          >
            <option value="">{t('audit.allSections')}</option>
            <option value="Collections">{t('audit.sectionCollections')}</option>
            <option value="Fields">{t('audit.sectionFields')}</option>
            <option value="Profiles">{t('audit.sectionProfiles')}</option>
            <option value="OIDC">{t('audit.sectionOIDC')}</option>
            <option value="Tenants">{t('audit.sectionTenants')}</option>
            <option value="Sharing">{t('audit.sectionSharing')}</option>
          </select>
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="entity-filter" className="text-sm font-medium text-muted-foreground">
            {t('audit.entityType')}
          </label>
          <input
            id="entity-filter"
            type="text"
            value={entityType}
            onChange={(e) => {
              setEntityType(e.target.value)
              setPage(0)
            }}
            placeholder={t('audit.entityTypePlaceholder')}
            className="min-w-[200px] rounded-md border border-border bg-background p-2 text-sm text-foreground placeholder:text-muted-foreground"
          />
        </div>
      </div>

      {entries.length === 0 ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('audit.noEntries')}</p>
        </div>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="bg-muted">
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('audit.timestamp')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('audit.action')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('audit.section')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('audit.entityType')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('audit.entityName')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground">
                    {t('audit.user')}
                  </th>
                  <th className="border-b border-border p-3 text-left font-semibold text-muted-foreground"></th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <React.Fragment key={entry.id}>
                    <tr
                      className={cn(
                        'cursor-pointer hover:bg-accent',
                        expandedRows.has(entry.id) && 'bg-muted'
                      )}
                      onClick={() => toggleRow(entry.id)}
                    >
                      <td className="border-b border-border p-3">
                        {new Date(entry.timestamp).toLocaleString()}
                      </td>
                      <td className="border-b border-border p-3">
                        <span
                          className={cn(
                            'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
                            entry.action === 'CREATED' &&
                              'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
                            entry.action === 'UPDATED' &&
                              'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300',
                            entry.action === 'DELETED' &&
                              'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
                            entry.action === 'ACTIVATED' &&
                              'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
                            entry.action === 'DEACTIVATED' &&
                              'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                          )}
                        >
                          {entry.action}
                        </span>
                      </td>
                      <td className="border-b border-border p-3">{entry.section}</td>
                      <td className="border-b border-border p-3">{entry.entityType}</td>
                      <td className="border-b border-border p-3">
                        {entry.entityName || entry.entityId || '-'}
                      </td>
                      <td className="border-b border-border p-3">{entry.userId || '-'}</td>
                      <td className="border-b border-border p-3">
                        <span className="text-xs text-muted-foreground">
                          {expandedRows.has(entry.id) ? '\u25BC' : '\u25B6'}
                        </span>
                      </td>
                    </tr>
                    {expandedRows.has(entry.id) && (
                      <tr className="bg-muted">
                        <td colSpan={7} className="!p-0">
                          <div className="grid grid-cols-2 gap-4 p-4">
                            <div>
                              <h4 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">
                                {t('audit.oldValue')}
                              </h4>
                              <pre className="m-0 max-h-[300px] overflow-x-auto whitespace-pre-wrap break-all rounded border border-border bg-card p-3 text-xs">
                                {formatJson(entry.oldValue)}
                              </pre>
                            </div>
                            <div>
                              <h4 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">
                                {t('audit.newValue')}
                              </h4>
                              <pre className="m-0 max-h-[300px] overflow-x-auto whitespace-pre-wrap break-all rounded border border-border bg-card p-3 text-xs">
                                {formatJson(entry.newValue)}
                              </pre>
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-center gap-4 text-sm">
              <button
                className="cursor-pointer rounded-md border border-border bg-card px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
              >
                {t('common.previous')}
              </button>
              <span>
                {t('audit.pageInfo', { current: String(page + 1), total: String(totalPages) })}
              </span>
              <button
                className="cursor-pointer rounded-md border border-border bg-card px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                {t('common.next')}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function formatJson(value?: string): string {
  if (!value) return '-'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}
