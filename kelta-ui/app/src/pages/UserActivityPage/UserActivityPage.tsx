import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner } from '../../components'
import { cn } from '@/lib/utils'
import { useNavigate } from 'react-router-dom'

export interface UserActivityPageProps {
  className?: string
}

const TIME_RANGES = [
  { label: '1h', seconds: 3600 },
  { label: '6h', seconds: 21600 },
  { label: '24h', seconds: 86400 },
  { label: '7d', seconds: 604800 },
  { label: '30d', seconds: 2592000 },
]

export function UserActivityPage({ className }: UserActivityPageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const navigate = useNavigate()

  const [timeRange, setTimeRange] = useState(604800)
  const [userId, setUserId] = useState('')
  const [page, setPage] = useState(0)
  const pageSize = 50

  const now = new Date()
  const start = new Date(now.getTime() - timeRange * 1000).toISOString()
  const end = now.toISOString()

  // Fetch request logs for user
  const { data: requestData, isLoading: requestLoading } = useQuery({
    queryKey: ['user-activity-requests', userId, timeRange, page],
    queryFn: () =>
      keltaClient.admin.observability.searchRequestLogs({
        userId: userId || undefined,
        start,
        end,
        page,
        size: pageSize,
      }),
    enabled: !!userId,
  })

  // Fetch audit events for user
  const { data: auditData, isLoading: auditLoading } = useQuery({
    queryKey: ['user-activity-audit', userId, timeRange],
    queryFn: () =>
      keltaClient.admin.observability.searchAudit({
        userId: userId || undefined,
        start,
        end,
        page: 0,
        size: pageSize,
      }),
    enabled: !!userId,
  })

  // Fetch users list
  const { data: usersData } = useQuery({
    queryKey: ['users-list'],
    queryFn: () => keltaClient.admin.users.list(undefined, undefined, 0, 100),
  })

  const isLoading = requestLoading || auditLoading

  // Merge and sort activity entries by time
  const activities: ActivityEntry[] = []

  if (requestData?.hits) {
    for (const hit of requestData.hits) {
      const tags = hit.tagMap || {}
      activities.push({
        type: 'request',
        timestamp: hit.startTimeMillis
          ? hit.startTimeMillis
          : hit.startTime
            ? hit.startTime / 1000
            : 0,
        description: `${tags['http.request.method'] || ''} ${tags['http.route'] || tags['http.url.path'] || hit.operationName || ''}`,
        status: String(tags['http.response.status_code'] || ''),
        traceId: hit.traceID,
      })
    }
  }

  if (auditData?.hits) {
    for (const audit of auditData.hits) {
      activities.push({
        type: 'audit',
        timestamp: audit.timestamp ? new Date(audit.timestamp as string).getTime() : 0,
        description: `${String(audit.action || '')} ${String(audit.entity_type || '')} ${String(audit.entity_name || '')}`,
        status: String(audit.action || ''),
        traceId: audit.traceId as string | undefined,
      })
    }
  }

  activities.sort((a, b) => b.timestamp - a.timestamp)

  return (
    <div className={cn('mx-auto max-w-[1400px]', className)} data-testid="user-activity-page">
      <div className="mb-4">
        <h2 className="m-0 text-lg font-semibold text-foreground">{t('userActivity.title')}</h2>
      </div>

      {/* Time range selector */}
      <div
        className="mb-4 flex gap-1 rounded-lg border border-border bg-card p-1"
        data-testid="user-activity-date-range"
      >
        {TIME_RANGES.map(({ label, seconds }) => (
          <button
            key={label}
            onClick={() => {
              setTimeRange(seconds)
              setPage(0)
            }}
            className={cn(
              'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
              timeRange === seconds
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted'
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {/* User selector */}
      <div className="mb-6 flex flex-wrap gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-sm font-medium text-muted-foreground">
            {t('userActivity.selectUser')}
          </label>
          <select
            data-testid="user-activity-user-select"
            className="min-w-[250px] rounded-md border border-border bg-background p-2 text-sm text-foreground"
            value={userId}
            onChange={(e) => {
              setUserId(e.target.value)
              setPage(0)
            }}
          >
            <option value="">{t('userActivity.chooseUser')}</option>
            {(usersData?.content || []).map(
              (user: { id: string; email?: string; displayName?: string }) => (
                <option key={user.id} value={user.email || user.id}>
                  {user.email || user.displayName || user.id}
                </option>
              )
            )}
          </select>
        </div>
      </div>

      {!userId ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('userActivity.selectPrompt')}</p>
        </div>
      ) : isLoading ? (
        <LoadingSpinner />
      ) : activities.length === 0 ? (
        <div className="p-12 text-center text-muted-foreground">
          <p>{t('userActivity.noActivity')}</p>
        </div>
      ) : (
        <div className="space-y-3" data-testid="user-activity-timeline">
          {activities.map((entry, idx) => (
            <button
              type="button"
              key={idx}
              data-testid={`user-activity-entry-${idx}`}
              className="flex w-full cursor-pointer items-center gap-4 rounded-lg border border-border bg-card p-4 text-left hover:bg-accent"
              onClick={() => {
                if (entry.traceId) {
                  navigate(`../requests/${entry.traceId}`)
                }
              }}
            >
              <span
                className={cn(
                  'rounded-full px-2 py-0.5 text-xs font-medium',
                  entry.type === 'request'
                    ? 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
                    : 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                )}
              >
                {entry.type === 'request' ? 'REQUEST' : 'AUDIT'}
              </span>
              <span className="text-xs text-muted-foreground whitespace-nowrap">
                {entry.timestamp ? new Date(entry.timestamp).toLocaleString() : ''}
              </span>
              <span className="flex-1 font-mono text-xs">{entry.description}</span>
              {entry.type === 'request' && entry.status && (
                <span
                  className={cn(
                    'rounded-full px-2 py-0.5 text-xs font-medium',
                    entry.status.startsWith('2')
                      ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                      : entry.status.startsWith('4')
                        ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                        : entry.status.startsWith('5')
                          ? 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
                          : 'bg-muted text-muted-foreground'
                  )}
                >
                  {entry.status}
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

interface ActivityEntry {
  type: 'request' | 'audit'
  timestamp: number
  description: string
  status: string
  traceId?: string
}
