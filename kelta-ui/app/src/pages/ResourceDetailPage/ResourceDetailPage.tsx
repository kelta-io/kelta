/**
 * ResourceDetailPage Component
 *
 * Displays a single resource record with all field values formatted
 * according to their field types. Provides edit and delete actions.
 *
 * Requirements:
 * - 11.7: Resource browser displays resource detail view
 * - 11.8: Resource browser allows viewing all field values
 * - 11.10: Resource browser allows deleting resources with confirmation
 */

import React, { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import { toast } from 'sonner'
import { useParams, useNavigate, useSearchParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useApi } from '../../context/ApiContext'
import { useAuth } from '../../context/AuthContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { useRecentRecords } from '../../hooks/useRecentRecords'
import { useFavorites } from '../../hooks/useFavorites'
import { RecordHeader } from '../../components/RecordHeader/RecordHeader'
import { RecordActionsBar } from '../../components/RecordActionsBar/RecordActionsBar'
import { ActivityTimeline } from '../../components/ActivityTimeline/ActivityTimeline'
import { useRecordContext } from '../../hooks/useRecordContext'
import { usePageLayout } from '../../hooks/usePageLayout'
import { useLookupDisplayMap } from '../../hooks/useLookupDisplayMap'
import { DetailTabBar, HISTORY_TAB } from './DetailTabBar'
import { scrollDetailTabBarIntoView } from './detailTabBarScroll'
import { RecordHistoryTab } from '../../components/RecordHistory/RecordHistoryTab'
import { RecordShell } from '../../components/record/RecordShell'
import { RecordDetailBody } from '../../components/record/RecordDetailBody'
import { RecordSectionNav } from '../../components/record/RecordSectionNav'
import { useInlineFieldCommit } from '../../components/record/useInlineFieldCommit'
import { resolveSectionNavItems } from '../../components/LayoutFieldSections/sectionNavItems'
import { useCollectionSchema } from '../../hooks/useCollectionSchema'
import { unwrapResource } from '../../utils/jsonapi'
import { recordRoutes } from '../../utils/recordRoutes'
import type { ApiClient } from '../../services/apiClient'
import type { FieldDefinition } from '../../hooks/useCollectionSchema'

/**
 * Resource record interface
 */
export interface Resource {
  id: string
  [key: string]: unknown
}

interface RecordShare {
  id: string
  tenantId: string
  collectionId: string
  recordId: string
  sharedWithId: string
  sharedWithType: string
  accessLevel: string
  reason: string
  createdBy: string
  createdAt: string
}

interface ShareFormProps {
  onSubmit: (data: { sharedWithId: string; sharedWithType: string; accessLevel: string }) => void
  onCancel: () => void
  isSubmitting: boolean
}

function ShareForm({ onSubmit, onCancel, isSubmitting }: ShareFormProps): React.ReactElement {
  const [sharedWithId, setSharedWithId] = useState('')
  const [sharedWithType, setSharedWithType] = useState('USER')
  const [accessLevel, setAccessLevel] = useState('READ')
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      if (sharedWithId.trim()) {
        onSubmit({ sharedWithId: sharedWithId.trim(), sharedWithType, accessLevel })
      }
    },
    [sharedWithId, sharedWithType, accessLevel, onSubmit]
  )

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={(e) => e.key === 'Escape' && onCancel()}
      role="presentation"
    >
      <div
        className="w-full max-w-[480px] rounded-md bg-card shadow-xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby="share-form-title"
      >
        <div className="flex items-center justify-between border-b border-border px-6 py-5">
          <h3 id="share-form-title" className="m-0 text-lg font-semibold text-foreground">
            Share Record
          </h3>
          <button
            type="button"
            className="rounded border-none bg-transparent p-1 text-2xl leading-none text-muted-foreground hover:text-foreground"
            onClick={onCancel}
            aria-label="Close"
          >
            &times;
          </button>
        </div>
        <form className="flex flex-col gap-4 p-6" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="share-with-id" className="text-sm font-medium text-foreground">
              Shared With ID
            </label>
            <input
              ref={inputRef}
              id="share-with-id"
              type="text"
              className="rounded border border-border bg-card px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
              value={sharedWithId}
              onChange={(e) => setSharedWithId(e.target.value)}
              placeholder="Enter user, group, or role ID"
              required
              disabled={isSubmitting}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="share-type" className="text-sm font-medium text-foreground">
              Type
            </label>
            <select
              id="share-type"
              className="rounded border border-border bg-card px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
              value={sharedWithType}
              onChange={(e) => setSharedWithType(e.target.value)}
              disabled={isSubmitting}
            >
              <option value="USER">User</option>
              <option value="GROUP">Group</option>
              <option value="ROLE">Role</option>
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="share-access" className="text-sm font-medium text-foreground">
              Access Level
            </label>
            <select
              id="share-access"
              className="rounded border border-border bg-card px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
              value={accessLevel}
              onChange={(e) => setAccessLevel(e.target.value)}
              disabled={isSubmitting}
            >
              <option value="READ">Read</option>
              <option value="READ_WRITE">Read/Write</option>
            </select>
          </div>
          <div className="flex justify-end gap-3 border-t border-border pt-2">
            <button
              type="button"
              className="cursor-pointer rounded-md border border-border bg-card px-4 py-2 text-sm font-medium text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
              onClick={onCancel}
              disabled={isSubmitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Sharing...' : 'Share'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

/** Record URLs of the admin stack (`/resources/...`) for the current tenant. */
function adminRoutes() {
  return recordRoutes('admin', getTenantSlug())
}

/**
 * Props for ResourceDetailPage component
 */
export interface ResourceDetailPageProps {
  /** Collection name from route params (optional, can be from useParams) */
  collectionName?: string
  /** Resource ID from route params (optional, can be from useParams) */
  resourceId?: string
  /** Optional test ID for testing */
  testId?: string
}

// API functions using apiClient
async function fetchResource(
  apiClient: ApiClient,
  collectionName: string,
  resourceId: string
): Promise<{ resource: Resource; etag?: string }> {
  // Capture the ETag for optimistic locking (slice 5); echoed as If-Match on write.
  const { data, etag } = await apiClient.getWithMeta(`/api/${collectionName}/${resourceId}`)
  return { resource: unwrapResource<Resource>(data), etag }
}

async function deleteResource(
  apiClient: ApiClient,
  collectionName: string,
  resourceId: string,
  ifMatch?: string
): Promise<void> {
  return apiClient.delete(`/api/${collectionName}/${resourceId}`, ifMatch)
}

/**
 * ResourceDetailPage Component
 *
 * Main page for viewing a single resource record.
 * Displays all field values with appropriate formatting and provides
 * edit and delete actions.
 */
export function ResourceDetailPage({
  collectionName: propCollectionName,
  resourceId: propResourceId,
  testId = 'resource-detail-page',
}: ResourceDetailPageProps): React.ReactElement {
  const { collection: routeCollection, id: routeResourceId } = useParams<{
    collection: string
    id: string
  }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { t, formatDate, formatNumber } = useI18n()
  const { apiClient } = useApi()
  const { user } = useAuth()
  const userId = user?.id ?? 'anonymous'
  const { addRecentRecord } = useRecentRecords(userId)
  const { isFavorite, addFavorite, removeFavorite } = useFavorites(userId)

  // Get collection name and resource ID from props or route params
  const collectionName = propCollectionName || routeCollection || ''
  const resourceId = propResourceId || routeResourceId || ''

  // Delete confirmation dialog state
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)

  // Tab deep-linking (?tab= / ?version=) — activity timeline → History tab.
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = searchParams.get('tab') ?? undefined
  const versionParam = searchParams.get('version')
  const historyVersion = versionParam != null ? Number(versionParam) : undefined
  const handleTabChange = useCallback(
    (tab: string) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.set('tab', tab)
          if (tab !== HISTORY_TAB) next.delete('version')
          return next
        },
        { replace: true }
      )
    },
    [setSearchParams]
  )
  const openHistoryAtVersion = useCallback(
    (versionNumber: number) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.set('tab', HISTORY_TAB)
          next.set('version', String(versionNumber))
          return next
        },
        { replace: true }
      )
      scrollDetailTabBarIntoView()
    },
    [setSearchParams]
  )

  // Share modal state
  const [shareModalOpen, setShareModalOpen] = useState(false)

  // Fetch collection schema (shared hook — canonical fetcher + cache key)
  const {
    schema,
    isLoading: schemaLoading,
    error: schemaError,
  } = useCollectionSchema(collectionName)

  // Resolve page layout for this collection (returns null if none configured)
  const { layout, isLoading: layoutLoading } = usePageLayout(schema?.id, user?.id)

  // Left section-nav entries (layout sections only — the admin field grid has no anchors)
  const sectionNavItems = useMemo(
    // eslint-disable-next-line react-hooks/preserve-manual-memoization -- compiler skips this memo; existing code, follow-up to PLT-854
    () =>
      layout?.sections && schema?.fields
        ? resolveSectionNavItems(layout.sections, schema.fields)
        : [],
    [layout?.sections, schema?.fields]
  )

  // Fetch resource data
  const {
    data: resourceEnvelope,
    isLoading: resourceLoading,
    error: resourceError,
    refetch: refetchResource,
  } = useQuery({
    queryKey: ['resource', collectionName, resourceId],
    queryFn: () => fetchResource(apiClient, collectionName, resourceId),
    enabled: !!collectionName && !!resourceId,
  })
  const resource = resourceEnvelope?.resource
  const etag = resourceEnvelope?.etag

  // Fetch notes and attachments in a single API call
  const {
    notes,
    attachments,
    invalidate: invalidateRecordContext,
  } = useRecordContext(schema?.id, resourceId)

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: () => deleteResource(apiClient, collectionName, resourceId),
    onSuccess: () => {
      // The admin list reads the shared `collection-records` cache (record-stack convergence P8).
      queryClient.invalidateQueries({ queryKey: ['collection-records', collectionName] })
      toast.success(t('success.deleted', { item: t('resources.record') }))
      navigate(adminRoutes().list(collectionName))
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // In-place inline field edit (unified record experience, slice 2): partial PATCH of one field.
  // Echoes the record's ETag as If-Match (slice 5): a concurrent edit → 409, so we reload the
  // latest instead of clobbering it. Commits run one at a time and each sends the version the
  // previous one left behind (shared with the end-user detail page).
  const writeField = useCallback(
    async (fieldName: string, value: unknown, ifMatch: string | undefined): Promise<void> => {
      await apiClient.patch(
        `/api/${collectionName}/${resourceId}`,
        {
          data: {
            type: collectionName,
            id: resourceId,
            attributes: { [fieldName]: value },
          },
        },
        ifMatch
      )
    },
    [apiClient, collectionName, resourceId]
  )
  const refreshResource = useCallback(
    async () => (await refetchResource()).data?.etag,
    [refetchResource]
  )
  const handleFieldCommit = useInlineFieldCommit({
    etag,
    write: writeField,
    refresh: refreshResource,
    onCommitted: invalidateRecordContext,
  })

  // Sharing: fetch shares for this record
  // The sharing endpoint may not be implemented yet — return empty array on error
  const { data: shares } = useQuery({
    queryKey: ['record-shares', schema?.id, resourceId],
    queryFn: async () => {
      try {
        return await apiClient.getList<RecordShare>(
          `/api/record-shares?filter[collectionId][eq]=${schema!.id}&filter[recordId][eq]=${resourceId}`
        )
      } catch {
        return [] as RecordShare[]
      }
    },
    enabled: !!schema?.id && !!resourceId,
  })

  const sharesList: RecordShare[] = Array.isArray(shares) ? shares : []

  // Sharing: create share
  const createShareMutation = useMutation({
    mutationFn: (data: { sharedWithId: string; sharedWithType: string; accessLevel: string }) =>
      apiClient.postResource(`/api/record-shares`, {
        collectionId: schema!.id,
        recordId: resourceId,
        ...data,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['record-shares', schema?.id, resourceId] })
      toast.success(t('sharing.shareCreated'))
      setShareModalOpen(false)
    },
    onError: (err: Error) => {
      toast.error(err.message || t('errors.generic'))
    },
  })

  // Sharing: delete share
  const deleteShareMutation = useMutation({
    mutationFn: (shareId: string) => apiClient.deleteResource(`/api/record-shares/${shareId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['record-shares', schema?.id, resourceId] })
      toast.success(t('sharing.shareDeleted'))
    },
    onError: (err: Error) => {
      toast.error(err.message || t('errors.generic'))
    },
  })

  // Resolve user display names for created_by / updated_by metadata fields
  const createdById = resource?.created_by ? String(resource.created_by) : undefined
  const updatedById = resource?.updated_by ? String(resource.updated_by) : undefined

  const { data: createdByUser } = useQuery({
    queryKey: ['user', createdById],
    queryFn: async () => {
      try {
        return await apiClient.getOne<{ id: string; firstName: string; lastName: string }>(
          `/api/users/${createdById}`
        )
      } catch {
        return null
      }
    },
    enabled: !!createdById,
    staleTime: 5 * 60 * 1000, // cache user lookups for 5 minutes
  })

  const { data: updatedByUser } = useQuery({
    queryKey: ['user', updatedById],
    queryFn: async () => {
      try {
        return await apiClient.getOne<{ id: string; firstName: string; lastName: string }>(
          `/api/users/${updatedById}`
        )
      } catch {
        return null
      }
    },
    enabled: !!updatedById && updatedById !== createdById,
    staleTime: 5 * 60 * 1000,
  })

  // Build user display name helper
  const getUserDisplay = useCallback(
    (userId: string): { name: string; linkTo: string } | null => {
      const userObj =
        createdByUser && createdByUser.id === userId
          ? createdByUser
          : updatedByUser && updatedByUser.id === userId
            ? updatedByUser
            : null
      if (!userObj) return null
      const name = [userObj.firstName, userObj.lastName].filter(Boolean).join(' ') || userId
      return { name, linkTo: `/${getTenantSlug()}/users/${userId}` }
    },
    [createdByUser, updatedByUser]
  )

  // Resolve display labels for reference/lookup/master_detail fields
  const { lookupDisplayMap, lookupTargetNameMap } = useLookupDisplayMap(
    schema?.fields,
    'lookup-display-detail'
  )

  // Sort fields by order
  const sortedFields = useMemo(() => {
    if (!schema?.fields) return []
    return [...schema.fields].sort((a, b) => {
      // Fields may not have order property, so default to 0
      const orderA = (a as FieldDefinition & { order?: number }).order ?? 0
      const orderB = (b as FieldDefinition & { order?: number }).order ?? 0
      return orderA - orderB
    })
  }, [schema])

  // Track recent record view
  useEffect(() => {
    if (resource && schema && collectionName && resourceId) {
      const firstStringField = schema.fields?.find((f) => f.type === 'string')
      const displayValue = firstStringField
        ? String(resource[firstStringField.name] ?? resource.id)
        : String(resource.id)
      addRecentRecord({
        id: resourceId,
        collectionName,
        collectionDisplayName: schema.displayName || collectionName,
        displayValue,
      })
    }
    // Only track once when resource loads
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resource?.id, schema?.id])

  const recordIsFavorite = isFavorite(resourceId, 'record')

  const handleToggleFavorite = useCallback(() => {
    if (recordIsFavorite) {
      removeFavorite(resourceId, 'record')
    } else {
      const firstStringField = schema?.fields?.find((f) => f.type === 'string')
      const displayValue = firstStringField
        ? String(resource?.[firstStringField.name] ?? resourceId)
        : String(resourceId)
      addFavorite({
        id: resourceId,
        type: 'record',
        collectionName,
        collectionDisplayName: schema?.displayName || collectionName,
        displayValue,
      })
    }
  }, [recordIsFavorite, resourceId, collectionName, schema, resource, addFavorite, removeFavorite])

  // Handle back navigation
  const handleBack = useCallback(() => {
    navigate(adminRoutes().list(collectionName))
  }, [navigate, collectionName])

  // Handle edit action
  const handleEdit = useCallback(() => {
    navigate(adminRoutes().edit(collectionName, resourceId))
  }, [navigate, collectionName, resourceId])

  // Handle delete action - open confirmation dialog
  const handleDeleteClick = useCallback(() => {
    setDeleteDialogOpen(true)
  }, [])

  // Handle delete confirmation
  const handleDeleteConfirm = useCallback(() => {
    deleteMutation.mutate()
  }, [deleteMutation])

  // Handle delete cancel
  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
  }, [])

  /**
   * Format field value based on field type
   * Requirement 11.8: Display all field values with appropriate formatting
   */
  const formatFieldValue = useCallback(
    (value: unknown, field: FieldDefinition): React.ReactNode => {
      if (value === null || value === undefined) {
        return <span className="italic text-muted-foreground/60">-</span>
      }

      switch (field.type) {
        case 'boolean':
          return (
            <span
              className={cn(
                'font-medium',
                value ? 'text-green-700 dark:text-green-300' : 'text-red-600 dark:text-red-300'
              )}
            >
              {value ? t('common.yes') : t('common.no')}
            </span>
          )

        case 'number':
          return <span className="font-mono">{formatNumber(value as number)}</span>

        case 'date':
          try {
            return formatDate(new Date(value as string), {
              year: 'numeric',
              month: 'long',
              day: 'numeric',
            })
          } catch {
            return String(value)
          }

        case 'datetime':
          try {
            return formatDate(new Date(value as string), {
              year: 'numeric',
              month: 'long',
              day: 'numeric',
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
            })
          } catch {
            return String(value)
          }

        case 'json':
          if (typeof value === 'object') {
            return (
              <pre className="m-0 max-h-[300px] overflow-x-auto overflow-y-auto rounded bg-muted p-4 text-sm">
                <code className="whitespace-pre font-mono">{JSON.stringify(value, null, 2)}</code>
              </pre>
            )
          }
          return String(value)

        case 'master_detail': {
          // Display the resolved display name with a deep link to the related record
          const recordId = String(value)
          const fieldDisplayMap = lookupDisplayMap?.[field.name]
          const displayLabel = fieldDisplayMap?.[recordId] ?? recordId
          // Get target collection from relationship data or lookup map
          const relData = resource?.[`_rel_${field.name}`] as
            { type: string; id: string } | undefined
          const targetCollection = relData?.type ?? lookupTargetNameMap?.[field.name]
          return (
            <span className="font-mono">
              {targetCollection ? (
                <Link
                  to={adminRoutes().detail(targetCollection, recordId)}
                  className="text-primary no-underline transition-colors duration-200 hover:text-primary/80 hover:underline focus:rounded focus:outline-2 focus:outline-offset-2 focus:outline-ring motion-reduce:transition-none"
                >
                  {displayLabel}
                </Link>
              ) : (
                displayLabel
              )}
            </span>
          )
        }

        case 'string':
        default: {
          const stringValue = String(value)
          if (stringValue.length > 500) {
            return (
              <div className="max-h-[200px] overflow-y-auto whitespace-pre-wrap rounded bg-muted p-2">
                {stringValue}
              </div>
            )
          }
          return stringValue
        }
      }
    },
    [t, formatDate, formatNumber]
  )

  /**
   * Get field type display label
   */
  const getFieldTypeLabel = useCallback(
    (type: FieldDefinition['type']): string => {
      return t(`fields.types.${type.toLowerCase()}`)
    },
    [t]
  )

  // Loading state
  const isLoading = schemaLoading || resourceLoading || layoutLoading

  if (isLoading) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <div className="flex min-h-[300px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Error state - schema error
  if (schemaError) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <ErrorMessage
          error={schemaError instanceof Error ? schemaError : new Error(t('errors.generic'))}
          onRetry={() =>
            queryClient.invalidateQueries({ queryKey: ['collection-schema', collectionName] })
          }
        />
      </div>
    )
  }

  // Error state - resource error
  if (resourceError) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <ErrorMessage
          error={resourceError instanceof Error ? resourceError : new Error(t('errors.generic'))}
          onRetry={() => refetchResource()}
        />
      </div>
    )
  }

  // Not found state
  if (!schema || !resource) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <ErrorMessage error={new Error(t('errors.notFound'))} />
      </div>
    )
  }

  // One ActivityTimeline element, mounted in two places: docked in the left
  // panel under the section nav (lg+), and in the main column on smaller
  // screens or when there is no section nav. React Query dedupes the fetches.
  const activityTimeline = (
    <ActivityTimeline
      collectionId={schema.id}
      collectionName={collectionName}
      recordId={resourceId}
      recordCreatedAt={(resource.created_at || resource.createdAt) as string | undefined}
      recordUpdatedAt={(resource.updated_at || resource.updatedAt) as string | undefined}
      apiClient={apiClient}
      historyEnabled={!!schema.trackHistory}
      schemaFields={schema.fields}
      getUserDisplay={getUserDisplay}
      onOpenHistory={openHistoryAtVersion}
    />
  )

  const hasSectionNav = sectionNavItems.length > 0

  return (
    <RecordShell
      variant="admin"
      collectionName={collectionName}
      data-testid={testId}
      className="p-6 max-lg:p-4 max-md:p-2"
      breadcrumb={
        <nav
          className="flex items-center gap-1 text-sm text-muted-foreground max-md:flex-wrap"
          aria-label="Breadcrumb"
        >
          <Link
            to={`/${getTenantSlug()}/resources`}
            className="text-primary no-underline transition-colors duration-200 hover:text-primary/80 hover:underline focus:rounded focus:outline-2 focus:outline-offset-2 focus:outline-ring motion-reduce:transition-none"
          >
            {t('resources.title')}
          </Link>
          <span className="mx-1 text-muted-foreground/60" aria-hidden="true">
            /
          </span>
          <Link
            to={adminRoutes().list(collectionName)}
            className="text-primary no-underline transition-colors duration-200 hover:text-primary/80 hover:underline focus:rounded focus:outline-2 focus:outline-offset-2 focus:outline-ring motion-reduce:transition-none"
          >
            {schema.displayName}
          </Link>
          <span className="mx-1 text-muted-foreground/60" aria-hidden="true">
            /
          </span>
          <span className="max-w-[200px] overflow-hidden text-ellipsis whitespace-nowrap font-medium text-foreground max-md:max-w-[150px]">
            {resource.id}
          </span>
        </nav>
      }
      header={
        <div className="space-y-6 max-md:space-y-4">
          {/* Record Header (T9) */}
          <RecordHeader
            record={
              resource as Record<string, unknown> & {
                id: string
                createdAt?: string
                updatedAt?: string
              }
            }
            schema={schema}
            collectionName={collectionName}
          />

          {/* Record Actions Bar (T8) */}
          <RecordActionsBar
            collectionName={collectionName}
            recordId={resourceId}
            onEdit={handleEdit}
            onDelete={handleDeleteClick}
            onBack={handleBack}
            isFavorite={recordIsFavorite}
            onToggleFavorite={handleToggleFavorite}
            apiClient={apiClient}
          />
        </div>
      }
      sectionNav={
        hasSectionNav ? (
          <div className="sticky top-6 max-h-[calc(100vh-3rem)] space-y-3 overflow-y-auto">
            <RecordSectionNav items={sectionNavItems} />
            <div className="border-t border-border pt-3" data-testid="section-nav-activity">
              {activityTimeline}
            </div>
          </div>
        ) : undefined
      }
      body={
        <div className="space-y-6 max-md:space-y-4">
          {/* Field Values — layout sections when available, else the admin grid */}
          <RecordDetailBody
            sections={layout?.sections}
            schemaFields={schema.fields}
            record={resource as Record<string, unknown> & { id: string }}
            tenantSlug={getTenantSlug()}
            lookupDisplayMap={lookupDisplayMap}
            editable
            onFieldCommit={handleFieldCommit}
            fallback={
              <section
                className="rounded-md border border-border bg-card p-6 max-md:p-4"
                aria-labelledby="fields-heading"
              >
                <h2 id="fields-heading" className="m-0 mb-4 text-lg font-semibold text-foreground">
                  {t('collections.fields')}
                </h2>

                {sortedFields.length === 0 ? (
                  <div
                    className="flex flex-col items-center justify-center rounded-md bg-muted p-8 text-center text-muted-foreground"
                    data-testid="no-fields"
                  >
                    <p className="m-0 text-base">{t('common.noData')}</p>
                  </div>
                ) : (
                  <div
                    className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-6 max-lg:grid-cols-[repeat(auto-fill,minmax(250px,1fr))] max-md:grid-cols-1"
                    data-testid="fields-grid"
                  >
                    {sortedFields.map((field, index) => (
                      <div
                        key={field.id}
                        className="flex flex-col gap-1"
                        data-testid={`field-item-${index}`}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-medium text-muted-foreground">
                            {field.displayName || field.name}
                          </span>
                          <span className="rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800 dark:bg-blue-900/40 dark:text-blue-300">
                            {getFieldTypeLabel(field.type)}
                          </span>
                        </div>
                        <div
                          className="min-h-[1.5em] break-words text-base text-foreground"
                          data-testid={`field-value-${field.name}`}
                        >
                          {formatFieldValue(resource[field.name], field)}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            }
          />

          {/* Sharing Section */}
          <section
            className="rounded-md border border-border bg-card p-6 max-md:p-4"
            aria-labelledby="sharing-heading"
          >
            <div className="mb-4 flex items-center justify-between">
              <h2 id="sharing-heading" className="m-0 text-lg font-semibold text-foreground">
                Sharing
              </h2>
              <button
                type="button"
                className="inline-flex cursor-pointer items-center rounded-md border border-primary bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-offset-2 focus:outline-ring motion-reduce:transition-none"
                onClick={() => setShareModalOpen(true)}
                data-testid="share-button"
              >
                {t('sharing.shareRecord')}
              </button>
            </div>
            {sharesList.length === 0 ? (
              <div
                className="flex flex-col items-center justify-center rounded-md bg-muted p-8 text-center text-muted-foreground"
                data-testid="no-shares"
              >
                <p className="m-0 text-base">{t('sharing.noShares')}</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table
                  className="w-full border-collapse text-sm"
                  role="grid"
                  aria-label="Record shares"
                >
                  <thead>
                    <tr>
                      <th className="whitespace-nowrap border-b border-border px-4 py-2 text-left font-semibold text-muted-foreground">
                        {t('sharing.sharedWith')}
                      </th>
                      <th className="whitespace-nowrap border-b border-border px-4 py-2 text-left font-semibold text-muted-foreground">
                        {t('sharing.sharedWithType')}
                      </th>
                      <th className="whitespace-nowrap border-b border-border px-4 py-2 text-left font-semibold text-muted-foreground">
                        {t('sharing.accessLevel')}
                      </th>
                      <th className="whitespace-nowrap border-b border-border px-4 py-2 text-left font-semibold text-muted-foreground">
                        Reason
                      </th>
                      <th className="whitespace-nowrap border-b border-border px-4 py-2 text-left font-semibold text-muted-foreground">
                        {t('common.actions')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {sharesList.map((share) => (
                      <tr key={share.id}>
                        <td className="border-b border-border px-4 py-2 font-mono text-sm">
                          {share.sharedWithId}
                        </td>
                        <td className="border-b border-border px-4 py-2">
                          <span className="inline-block rounded-full bg-muted px-2 py-0.5 text-xs font-semibold text-primary">
                            {share.sharedWithType}
                          </span>
                        </td>
                        <td className="border-b border-border px-4 py-2">
                          <span className="inline-block rounded-full bg-muted px-2 py-0.5 text-xs font-semibold text-primary">
                            {share.accessLevel}
                          </span>
                        </td>
                        <td className="border-b border-border px-4 py-2">{share.reason || '-'}</td>
                        <td className="border-b border-border px-4 py-2">
                          <button
                            type="button"
                            className="cursor-pointer rounded border border-red-200 bg-transparent px-2 py-1 text-xs font-medium text-red-600 transition-colors duration-200 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-red-900 dark:text-red-400 dark:hover:bg-red-950 motion-reduce:transition-none"
                            onClick={() => deleteShareMutation.mutate(share.id)}
                            disabled={deleteShareMutation.isPending}
                            aria-label={`Remove share for ${share.sharedWithId}`}
                          >
                            {t('sharing.removeShare')}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Activity docks in the left panel on lg+ when the section nav is
              shown; here it covers small screens and no-layout pages. */}
          <div className={cn(hasSectionNav && 'lg:hidden')}>{activityTimeline}</div>
        </div>
      }
      tabBar={
        <DetailTabBar
          relatedLists={layout?.relatedLists ?? []}
          recordId={resourceId}
          collectionId={schema.id}
          tenantSlug={getTenantSlug()}
          resource={resource as Record<string, unknown> & { id: string }}
          notes={notes}
          attachments={attachments}
          apiClient={apiClient}
          invalidateRecordContext={invalidateRecordContext}
          getUserDisplay={getUserDisplay}
          editable
          onRelatedChange={refetchResource}
          activeTab={activeTab}
          onTabChange={handleTabChange}
          historyContent={
            schema.trackHistory ? (
              <RecordHistoryTab
                collectionId={schema.id}
                recordId={resourceId}
                sections={layout?.sections}
                schemaFields={schema.fields}
                tenantSlug={getTenantSlug()}
                lookupDisplayMap={lookupDisplayMap}
                getUserDisplay={getUserDisplay}
                initialVersion={historyVersion}
              />
            ) : undefined
          }
        />
      }
      dialogs={
        <>
          {/* Share Modal */}
          {shareModalOpen && (
            <ShareForm
              onSubmit={(data) => createShareMutation.mutate(data)}
              onCancel={() => setShareModalOpen(false)}
              isSubmitting={createShareMutation.isPending}
            />
          )}

          {/* Delete Confirmation Dialog */}
          <ConfirmDialog
            open={deleteDialogOpen}
            title={t('resources.deleteRecord')}
            message={t('resources.confirmDelete')}
            confirmLabel={t('common.delete')}
            cancelLabel={t('common.cancel')}
            onConfirm={handleDeleteConfirm}
            onCancel={handleDeleteCancel}
            variant="danger"
          />
        </>
      }
    />
  )
}
