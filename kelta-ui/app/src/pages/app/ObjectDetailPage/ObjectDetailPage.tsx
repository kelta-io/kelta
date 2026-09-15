/**
 * ObjectDetailPage
 *
 * Displays the detail view of a single record with:
 * - Breadcrumb navigation
 * - Record header with name, collection badge, and action buttons
 * - Quick actions menu for server-side scripts and custom operations
 * - Highlights panel showing key fields
 * - Detail sections organized in a 2-column field grid
 * - Related lists for master-detail child records
 * - Delete confirmation dialog
 */

import React, { useState, useCallback, useMemo, useEffect } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  Loader2,
  AlertCircle,
  Pencil,
  Copy,
  Trash2,
  Mail,
  Phone,
  MapPin,
  Lock,
  SendHorizonal,
} from 'lucide-react'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { useI18n } from '@/context/I18nContext'
import { PresenceAvatars } from '@/components/PresenceAvatars'
import { useRecordApprovalState } from '@/hooks/useRecordApprovalState'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
// DropdownMenuItem/Separator come from @kelta/components (via the detail
// barrel) so the moreMenu slot shares RecordHeader's DropdownMenu radix
// context — see kelta-ui/app/src/components/detail/index.ts.
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import { useCollectionStore } from '@/context/CollectionStoreContext'
import { useRecord } from '@/hooks/useRecord'
import { useRecordMutation } from '@/hooks/useRecordMutation'
import { useCollectionPermissions } from '@/hooks/useCollectionPermissions'
import { buildIncludedDisplayMap } from '@/utils/jsonapi'
import { FieldRenderer } from '@/components/FieldRenderer'
import { InsufficientPrivileges } from '@/components/InsufficientPrivileges'
import { DetailTabBar, HISTORY_TAB } from '@/pages/ResourceDetailPage/DetailTabBar'
import { scrollDetailTabBarIntoView } from '@/pages/ResourceDetailPage/detailTabBarScroll'
import { RecordHistoryTab } from '@/components/RecordHistory/RecordHistoryTab'
import { RecordShell } from '@/components/record/RecordShell'
import { RecordNotFoundState } from './RecordNotFoundState'
import { classifyRecordLoad } from './recordLoadOutcome'
import { RecordDetailBody } from '@/components/record/RecordDetailBody'
import { RecordSectionNav } from '@/components/record/RecordSectionNav'
import { resolveSectionNavItems } from '@/components/LayoutFieldSections/sectionNavItems'
import { ActivityTimeline } from '@/components/ActivityTimeline/ActivityTimeline'
import {
  RecordHeader,
  FieldSection,
  Crumb,
  MetadataCard,
  RailRenderer,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@/components/detail'
import type {
  RecordHeaderAction,
  RecordHeaderMetaField,
  MetadataRow,
  FieldSectionRenderContext,
} from '@/components/detail'
import type { LayoutRelatedListDto } from '@/hooks/usePageLayout'
import { useInspectMode } from '@/hooks/useInspectMode'
import { cn } from '@/lib/utils'
import { QuickActionsMenu } from '@/components/QuickActions'
import { useAnnounce } from '@/components/LiveRegion'
import { useAppContext } from '@/context/AppContext'
import { useAuth } from '@/context/AuthContext'
import { useApi } from '@/context/ApiContext'
import { usePageLayout } from '@/hooks/usePageLayout'
import { useRecordContext } from '@/hooks/useRecordContext'
import { usePicklistDisplayMaps } from '@/hooks/usePicklistOptions'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { QuickActionExecutionContext } from '@/types/quickActions'

/**
 * Represents a relationship where another collection has a field
 * that references this collection (reverse relationship).
 */
interface RelatedCollection {
  /** Related collection name */
  collectionName: string
  /** Field on the related collection that points to this record */
  foreignKeyField: string
}

/** System fields to exclude from detail sections */
const SYSTEM_FIELDS = new Set([
  'createdAt',
  'updatedAt',
  'createdBy',
  'updatedBy',
  'created_at',
  'updated_at',
  'created_by',
  'updated_by',
])

/** Max fields to show in the highlights panel */
const MAX_HIGHLIGHT_FIELDS = 4

/** Reference field types that indicate a foreign key to another collection */
const REFERENCE_FIELD_TYPES = new Set(['master_detail', 'lookup', 'reference'])

/**
 * Determine the record's display name from its fields.
 */
function getRecordDisplayName(
  record: Record<string, unknown>,
  fields: FieldDefinition[],
  displayFieldName?: string
): string {
  // Try explicit display field
  if (displayFieldName && record[displayFieldName]) {
    return String(record[displayFieldName])
  }
  // Try "name" field
  const nameField = fields.find((f) => f.name.toLowerCase() === 'name')
  if (nameField && record[nameField.name]) {
    return String(record[nameField.name])
  }
  // Fall back to first string field
  const firstString = fields.find((f) => f.type === 'string')
  if (firstString && record[firstString.name]) {
    return String(record[firstString.name])
  }
  // Fall back to ID
  return String(record.id || 'Untitled')
}

/**
 * Map a field name (case-insensitive substring) to a Lucide icon for the
 * record header meta row. Returns null when no icon should be shown.
 */
function metaIconFor(fieldName: string): React.ReactNode {
  const n = fieldName.toLowerCase()
  if (n.includes('email')) return <Mail className="h-3.5 w-3.5" aria-hidden="true" />
  if (n.includes('phone') || n === 'mobile') {
    return <Phone className="h-3.5 w-3.5" aria-hidden="true" />
  }
  if (n.includes('city') || n.includes('country') || n.includes('region')) {
    return <MapPin className="h-3.5 w-3.5" aria-hidden="true" />
  }
  return null
}

/**
 * Auto-derive a small RecordHeader meta row from common contact-ish fields.
 * Used when no layout-driven `RecordHeaderConfig.metaFields` is supplied.
 * Cap at 4 items so the row doesn't wrap on common viewports.
 */
function deriveMetaFields(
  fields: FieldDefinition[],
  record: Record<string, unknown>
): RecordHeaderMetaField[] {
  const candidates: Array<{ key: string; prefix?: string; rank: number }> = []
  const seen = new Set<string>()
  const push = (key: string, prefix: string | undefined, rank: number): void => {
    if (seen.has(key)) return
    if (record[key] === null || record[key] === undefined || record[key] === '') return
    seen.add(key)
    candidates.push({ key, prefix, rank })
  }

  for (const f of fields) {
    const n = f.name.toLowerCase()
    if (f.type === 'email') push(f.name, undefined, 1)
    else if (f.type === 'phone') push(f.name, undefined, 2)
    else if (n.includes('city')) push(f.name, undefined, 3)
    else if (n.includes('country')) push(f.name, undefined, 4)
  }

  // Joined-at: prefer a domain field like joinedAt/signedUpAt, else fall back to createdAt.
  const joinedField = fields.find((f) =>
    /(^|_)joined|signedup|registered|memberSince/i.test(f.name)
  )
  if (joinedField && record[joinedField.name]) {
    push(joinedField.name, 'Joined ', 5)
  } else if (record.createdAt || record.created_at) {
    const createdKey = record.createdAt ? 'createdAt' : 'created_at'
    push(createdKey, 'Joined ', 5)
  }

  return candidates
    .sort((a, b) => a.rank - b.rank)
    .slice(0, 4)
    .map(({ key, prefix }) => ({ key, prefix, icon: metaIconFor(key) }))
}

/**
 * Auto-derive a system-metadata MetadataCard for the right rail. Shows the
 * record id (monospaced), created/updated timestamps, and resolved author
 * names when the include map has them.
 */
function deriveSystemRows(
  record: Record<string, unknown>,
  getUserDisplay: (userId: string) => { name: string; linkTo: string } | null
): MetadataRow[] {
  const rows: MetadataRow[] = []
  rows.push({ label: 'ID', value: String(record.id), mono: true })

  const formatStamp = (raw: unknown): string => {
    if (!raw) return ''
    try {
      return new Intl.DateTimeFormat(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date(String(raw)))
    } catch {
      return String(raw)
    }
  }

  const createdAt = record.createdAt ?? record.created_at
  const updatedAt = record.updatedAt ?? record.updated_at
  const createdBy = record.createdBy ?? record.created_by
  const updatedBy = record.updatedBy ?? record.updated_by

  // Audit-user rows render even when absent (system/legacy writes) so the
  // empty value is visible rather than the row vanishing.
  const userRowValue = (userId: unknown): string => {
    if (userId == null) return '—'
    const display = getUserDisplay(String(userId))
    return display?.name ?? String(userId)
  }

  if (createdAt) rows.push({ label: 'Created', value: formatStamp(createdAt) })
  rows.push({ label: 'Created by', value: userRowValue(createdBy) })
  if (updatedAt) rows.push({ label: 'Updated', value: formatStamp(updatedAt) })
  rows.push({ label: 'Updated by', value: userRowValue(updatedBy) })
  return rows
}

export function ObjectDetailPage(): React.ReactElement {
  const {
    tenantSlug,
    collection: collectionName,
    id: recordId,
  } = useParams<{
    tenantSlug: string
    collection: string
    id: string
  }>()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { addRecentItem } = useAppContext()
  const { user } = useAuth()
  const { apiClient } = useApi()
  const { t } = useI18n()
  const collectionStore = useCollectionStore()
  const basePath = `/${tenantSlug}/app`

  // Delete confirmation state
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)

  // Tab deep-linking (?tab= / ?version=) — lets the activity timeline jump to
  // a specific record version on the History tab.
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

  // Fetch collection schema
  const {
    schema,
    fields,
    isLoading: schemaLoading,
    error: schemaError,
  } = useCollectionSchema(collectionName)

  // Identify reference fields that need included resources for display labels
  const referenceFields = useMemo(
    () => fields.filter((f) => REFERENCE_FIELD_TYPES.has(f.type) && f.referenceTarget),
    [fields]
  )

  // Authored label/color for picklist/multi_picklist fields (badges in the detail body).
  const { displayMaps: picklistDisplayMaps } = usePicklistDisplayMaps(fields)

  // Build unified include param combining:
  // 1. Forward includes — reference fields on this collection (e.g., customers, discount_codes)
  // 2. Reverse includes — collections with FK fields pointing to this collection (e.g., order_items, payments)
  // 3. Nested includes — reference targets of reverse collections (e.g., products via order_items)
  // The worker resolves all three types in a single request. The gateway's IncludeResolutionFilter
  // only handles forward includes but preserves the worker's reverse/transitive includes.
  const includeParam = useMemo(() => {
    const includes = new Set<string>()

    // Forward includes from reference fields on this collection — by FIELD name (the worker resolves
    // forward `include` by field name, not target collection name; reverse/nested below use names).
    for (const f of referenceFields) {
      includes.add(f.name)
    }

    // Always include users for createdBy/updatedBy display name resolution
    includes.add('users')

    // Reverse includes + nested: collections referencing this collection
    if (collectionName && !collectionStore.isLoading) {
      for (const coll of collectionStore.collections) {
        if (coll.name === collectionName) continue
        for (const field of coll.fields) {
          if (
            (field.type === 'master_detail' || field.type === 'lookup') &&
            field.referenceTarget === collectionName
          ) {
            // Add the reverse collection itself (e.g., order_items)
            includes.add(coll.name)
            // Add nested reference targets so the worker resolves them transitively
            // (e.g., order_items.product → products)
            for (const nestedField of coll.fields) {
              if (
                REFERENCE_FIELD_TYPES.has(nestedField.type) &&
                nestedField.referenceTarget &&
                nestedField.referenceTarget !== collectionName
              ) {
                includes.add(nestedField.referenceTarget)
              }
            }
          }
        }
      }
    }

    return includes.size > 0 ? [...includes].join(',') : undefined
  }, [referenceFields, collectionName, collectionStore.collections, collectionStore.isLoading])

  // Fetch record with all includes — wait for both schema and collection store
  // so the include param is fully computed before the query fires.
  const {
    record,
    isLoading: recordLoading,
    error: recordError,
    rawResponse,
    etag,
    refetch: refetchRecord,
  } = useRecord({
    collectionName,
    recordId,
    include: includeParam,
    enabled: !schemaLoading && !collectionStore.isLoading,
  })

  // Resolve page layout for this collection (returns null if none configured)
  // Pass record's recordTypeId for type-specific layout resolution
  const recordTypeId = record?.recordTypeId ? String(record.recordTypeId) : undefined
  const { layout, isLoading: layoutLoading } = usePageLayout(schema?.id, user?.id, recordTypeId)

  // Left section-nav entries (layout sections only — the fallback body has no anchors)
  const sectionNavItems = useMemo(
    () => (layout?.sections ? resolveSectionNavItems(layout.sections, fields) : []),
    [layout?.sections, fields]
  )

  // Build lookup display map from included resources using centralized collection store
  const lookupDisplayMap = useMemo(() => {
    if (!rawResponse) return undefined

    const map: Record<string, Record<string, string>> = {}

    referenceFields.forEach((field) => {
      const targetType = field.referenceTarget!
      const refSchema = collectionStore.getCollectionByName(targetType)
      const displayField = refSchema?.displayFieldName || 'name'

      const fieldMap = buildIncludedDisplayMap(rawResponse, targetType, displayField)
      if (Object.keys(fieldMap).length > 0) {
        map[field.name] = fieldMap
      }
    })

    // Build display map for system audit fields (created_by, updated_by → users)
    // Both camelCase (system collections) and snake_case (tenant collections) keys
    // are populated so the map works regardless of which naming convention the
    // backend uses for a given collection.
    const usersDisplayField =
      collectionStore.getCollectionByName('users')?.displayFieldName || 'email'
    const usersMap = buildIncludedDisplayMap(rawResponse, 'users', usersDisplayField)
    if (Object.keys(usersMap).length > 0) {
      map['createdBy'] = usersMap
      map['updatedBy'] = usersMap
      map['created_by'] = usersMap
      map['updated_by'] = usersMap
    }

    return Object.keys(map).length > 0 ? map : undefined
  }, [rawResponse, referenceFields, collectionStore])

  // Fetch permissions (combined object + field in one call)
  const {
    permissions,
    isFieldVisible,
    isLoading: permissionsLoading,
  } = useCollectionPermissions(collectionName)

  // Fetch notes and attachments in a single API call
  const {
    notes,
    attachments,
    invalidate: invalidateRecordContext,
  } = useRecordContext(schema?.id, recordId)

  // Screen reader announcements
  const { announce } = useAnnounce()

  // Mutations. No shared onSuccess: the hook fires it for EVERY mutation (patch included),
  // so delete side effects (navigate back to the list) live in handleDeleteConfirm — otherwise
  // an inline field commit would bounce the user off the record page.
  const mutations = useRecordMutation({
    collectionName: collectionName || '',
  })

  // In-place inline field edit (unified record experience, slice 2): partial PATCH of one field.
  // Sends the record's ETag as If-Match (slice 5) so a concurrent edit → 409, surfaced as a
  // "changed elsewhere" message + a reload rather than silently overwriting the other change.
  const handleFieldCommit = useCallback(
    async (fieldName: string, value: unknown): Promise<void> => {
      if (!recordId) return
      try {
        await mutations.patch.mutateAsync({
          id: recordId,
          data: { [fieldName]: value },
          ifMatch: etag,
        })
      } catch (err) {
        if ((err as { status?: number })?.status === 409) {
          announce('This record was changed elsewhere; reloading the latest version')
          refetchRecord()
        }
        throw err
      }
      invalidateRecordContext()
      announce(`${fieldName} updated`)
    },
    [recordId, mutations.patch, etag, refetchRecord, invalidateRecordContext, announce]
  )

  // Collection label
  const collectionLabel =
    schema?.displayName ||
    (collectionName ? collectionName.charAt(0).toUpperCase() + collectionName.slice(1) : 'Object')

  // Display name for the record
  const recordTitle = useMemo(() => {
    if (!record || !fields.length) return recordId || 'Loading...'
    return getRecordDisplayName(record, fields, schema?.displayFieldName)
  }, [record, fields, recordId, schema?.displayFieldName])

  // Track recently viewed record
  useEffect(() => {
    if (record && recordId && collectionName && recordTitle !== 'Loading...') {
      addRecentItem({
        id: recordId,
        collectionName,
        label: recordTitle,
      })
    }
  }, [record, recordId, collectionName, recordTitle, addRecentItem])

  // Announce when record finishes loading for screen readers
  useEffect(() => {
    if (record && recordTitle && recordTitle !== 'Loading...') {
      announce(`Viewing ${collectionLabel} record: ${recordTitle}`)
    }
    // Only announce on initial load, not re-renders
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record?.id])

  // Split fields into highlights and detail sections (filtered by field permissions)
  const userFields = useMemo(() => {
    return fields
      .filter((f) => !SYSTEM_FIELDS.has(f.name) && f.name !== 'id')
      .filter((f) => isFieldVisible(f.name))
  }, [fields, isFieldVisible])

  const highlightFields = useMemo(() => {
    return userFields.slice(0, MAX_HIGHLIGHT_FIELDS)
  }, [userFields])

  const detailFields = useMemo(() => {
    return userFields.slice(MAX_HIGHLIGHT_FIELDS)
  }, [userFields])

  // Discover reverse relationships (fallback when no layout is configured).
  // Uses the centralized collection store to find collections with master_detail
  // fields pointing to the current collection (e.g., Order Items on Orders).
  const hasLayoutRelatedLists = !!(layout && layout.relatedLists.length > 0)

  const relatedCollections = useMemo<RelatedCollection[]>(() => {
    if (hasLayoutRelatedLists || !collectionName || collectionStore.isLoading) return []

    const related: RelatedCollection[] = []
    for (const coll of collectionStore.collections) {
      if (coll.name === collectionName) continue
      for (const field of coll.fields) {
        if (
          (field.type === 'master_detail' || field.type === 'lookup') &&
          field.referenceTarget === collectionName
        ) {
          related.push({
            collectionName: coll.name,
            foreignKeyField: field.name,
          })
        }
      }
    }
    return related
  }, [
    hasLayoutRelatedLists,
    collectionStore.collections,
    collectionStore.isLoading,
    collectionName,
  ])

  // Resolve a user id to display + link using the lookup map, falling back to null.
  const getUserDisplay = useCallback(
    (userId: string) => {
      const name =
        lookupDisplayMap?.['createdBy']?.[userId] ??
        lookupDisplayMap?.['updatedBy']?.[userId] ??
        lookupDisplayMap?.['created_by']?.[userId] ??
        lookupDisplayMap?.['updated_by']?.[userId]
      if (!name) return null
      return { name, linkTo: `${basePath}/o/users/${userId}` }
    },
    [lookupDisplayMap, basePath]
  )

  // Tab-bar related lists: prefer layout configuration; otherwise synthesize
  // entries from auto-discovered reverse relationships so each one becomes a tab.
  const tabBarRelatedLists = useMemo<LayoutRelatedListDto[]>(() => {
    if (hasLayoutRelatedLists) return layout!.relatedLists
    return relatedCollections.map((rel, index) => ({
      id: `auto-${rel.collectionName}-${rel.foreignKeyField}`,
      relatedCollectionId: '',
      relatedCollectionName: rel.collectionName,
      relationshipFieldId: '',
      relationshipFieldName: rel.foreignKeyField,
      displayColumns: '',
      sortField: null,
      sortDirection: 'asc',
      rowLimit: 5,
      sortOrder: index,
    }))
  }, [hasLayoutRelatedLists, layout, relatedCollections])

  // Handlers
  const handleEdit = useCallback(() => {
    navigate(`${basePath}/o/${collectionName}/${recordId}/edit`)
  }, [navigate, basePath, collectionName, recordId])

  const handleDelete = useCallback(() => {
    setShowDeleteDialog(true)
  }, [])

  const handleDeleteConfirm = useCallback(() => {
    if (recordId) {
      mutations.remove
        .mutateAsync(recordId)
        .then(() => {
          setShowDeleteDialog(false)
          announce('Record deleted successfully')
          navigate(`${basePath}/o/${collectionName}`)
        })
        .catch(() => {
          // Error state is surfaced by the mutation; keep the dialog open.
        })
    }
  }, [recordId, mutations.remove, announce, navigate, basePath, collectionName])

  const handleClone = useCallback(() => {
    // Navigate to the new form — clone will be handled by pre-populating from URL
    navigate(`${basePath}/o/${collectionName}/new`)
  }, [navigate, basePath, collectionName])

  // Quick action execution context
  const quickActionContext = useMemo<QuickActionExecutionContext>(
    () => ({
      collectionName: collectionName || '',
      collectionId: schema?.id,
      recordId,
      record: record || undefined,
      tenantSlug: tenantSlug || '',
    }),
    [collectionName, schema?.id, recordId, record, tenantSlug]
  )

  const isLoading = schemaLoading || recordLoading || permissionsLoading || layoutLoading

  const inspect = useInspectMode()

  const approval = useRecordApprovalState(schema?.id, recordId)

  const submitApprovalMutation = useMutation({
    mutationFn: async () =>
      apiClient.post('/api/approvals/submit', {
        collectionId: schema?.id,
        recordId,
      }),
    onSuccess: () => {
      toast.success(t('recordActions.approvalSubmitted', 'Submitted for approval successfully'))
      approval.invalidate()
    },
    onError: () => {
      toast.error(t('recordActions.approvalError', 'Failed to submit for approval'))
      approval.invalidate()
    },
  })

  const headerActions = useMemo<RecordHeaderAction[]>(() => {
    const list: RecordHeaderAction[] = []
    if (permissions.canEdit) {
      list.push({
        label: 'Edit',
        icon: <Pencil className="h-3.5 w-3.5" aria-hidden="true" />,
        onClick: handleEdit,
        variant: 'ghost',
        testId: 'edit-button',
      })
    }
    if (permissions.canEdit && approval.hasProcess && !approval.hasActiveApproval) {
      list.push({
        label: t('recordActions.submitForApproval', 'Submit for Approval'),
        icon: <SendHorizonal className="h-3.5 w-3.5" aria-hidden="true" />,
        onClick: () => submitApprovalMutation.mutate(),
        variant: 'ghost',
        testId: 'submit-approval-button',
      })
    }
    return list
  }, [
    permissions.canEdit,
    handleEdit,
    approval.hasProcess,
    approval.hasActiveApproval,
    submitApprovalMutation,
    t,
  ])

  // Prefer layout-driven header config; fall back to auto-derived meta row
  // when admins haven't configured one. metaFields on the layout reference
  // record fields by name, so we attach the same icon-derivation logic that
  // powers the auto-derive path.
  const headerMeta = useMemo<RecordHeaderMetaField[]>(() => {
    if (!record) return []
    const fromLayout = layout?.headerConfig?.metaFields
    if (fromLayout && fromLayout.length > 0) {
      return fromLayout
        .filter((m) => {
          const v = record[m.key]
          return v !== null && v !== undefined && v !== ''
        })
        .map((m) => ({ key: m.key, prefix: m.prefix, icon: metaIconFor(m.key) }))
    }
    return deriveMetaFields(fields, record)
  }, [fields, record, layout?.headerConfig])

  const headerTitleFields = layout?.headerConfig?.titleFields
  const headerAvatarFrom = layout?.headerConfig?.avatarFrom

  const systemRows = useMemo(
    () => (record ? deriveSystemRows(record, getUserDisplay) : []),
    [record, getUserDisplay]
  )

  const railBlocks = layout?.railBlocks ?? null

  // Status branch (permission gate / error / not-found), rendered by the shell
  // in place of the record frame. Order matches the legacy early-returns.
  // A 404 on either the collection or the record is a terminal "not in this
  // workspace" state (typically a recents/favorites link from another tenant).
  const loadOutcome = classifyRecordLoad({ schemaError, recordError, hasRecord: !!record })
  const statusSlot: React.ReactNode = !permissions.canRead ? (
    <InsufficientPrivileges
      action="view"
      resource={`this ${collectionLabel} record`}
      backPath={`${basePath}/o/${collectionName}`}
    />
  ) : loadOutcome === 'error' ? (
    <div className="space-y-4 p-6">
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>Error</AlertTitle>
        <AlertDescription>
          {schemaError?.message || recordError?.message || 'Failed to load record.'}
        </AlertDescription>
      </Alert>
      <Button variant="outline" onClick={() => navigate(`${basePath}/o/${collectionName}`)}>
        Back to list
      </Button>
    </div>
  ) : loadOutcome !== 'loaded' ? (
    <RecordNotFoundState
      outcome={loadOutcome}
      collectionName={collectionName ?? ''}
      tenantSlug={tenantSlug ?? ''}
      basePath={basePath}
      onNavigate={navigate}
    />
  ) : null

  // One ActivityTimeline element, mounted in two places: docked in the left
  // panel under the section nav (lg+), and in the main column on smaller
  // screens or when there is no section nav. React Query dedupes the fetches.
  const activityTimeline =
    record && schema && recordId ? (
      <ActivityTimeline
        collectionId={schema.id}
        collectionName={collectionName ?? ''}
        recordId={recordId}
        recordCreatedAt={(record.createdAt ?? record.created_at) as string | undefined}
        recordUpdatedAt={(record.updatedAt ?? record.updated_at) as string | undefined}
        apiClient={apiClient}
        historyEnabled={!!schema.trackHistory}
        schemaFields={fields}
        getUserDisplay={getUserDisplay}
        onOpenHistory={openHistoryAtVersion}
      />
    ) : null

  const hasSectionNav = !!record && sectionNavItems.length > 0

  return (
    <RecordShell
      variant="enduser"
      isLoading={isLoading}
      statusSlot={statusSlot}
      className={cn('space-y-6 p-6', inspect.enabled && 'kelta-inspect')}
      breadcrumb={
        record ? (
          <>
            <Crumb
              trail={[
                { label: 'Home', to: `${basePath}/home` },
                { label: collectionLabel, to: `${basePath}/o/${collectionName}` },
                { label: recordTitle },
              ]}
            />
            {inspect.enabled && (
              <div
                role="status"
                className="-mt-2 inline-flex items-center gap-2 rounded-md border border-blue-400/30 bg-blue-400/10 px-2 py-1 text-[11px] font-mono text-blue-300"
              >
                <span>Inspect mode</span>
                <button
                  type="button"
                  onClick={() => inspect.set(false)}
                  className="rounded px-1.5 text-blue-200 hover:bg-blue-400/20"
                  aria-label="Disable inspect mode"
                >
                  ×
                </button>
                <span className="text-[10px] text-blue-300/60">Toggle: ⌘⇧L</span>
              </div>
            )}
          </>
        ) : undefined
      }
      header={
        record ? (
          <div className="flex items-start gap-2">
            {/* Who else is viewing this record (app-intelligence slice 3) */}
            <PresenceAvatars
              resource={collectionName && recordId ? `record:${collectionName}/${recordId}` : null}
            />
            <div className="flex-1 min-w-0">
              <RecordHeader
                record={record}
                recordId={recordId || ''}
                collectionLabel={collectionLabel}
                fallbackTitle={recordTitle}
                config={{
                  titleFields: headerTitleFields,
                  avatarFrom: headerAvatarFrom,
                  metaFields: headerMeta,
                }}
                actions={headerActions}
                moreMenu={
                  <>
                    {permissions.canCreate && (
                      <DropdownMenuItem onClick={handleClone}>
                        <Copy className="mr-2 h-4 w-4" aria-hidden="true" />
                        Clone
                      </DropdownMenuItem>
                    )}
                    {permissions.canCreate && permissions.canDelete && <DropdownMenuSeparator />}
                    {permissions.canDelete && (
                      <DropdownMenuItem
                        className="text-destructive focus:text-destructive"
                        onClick={handleDelete}
                        data-testid="delete-button"
                      >
                        <Trash2 className="mr-2 h-4 w-4" aria-hidden="true" />
                        Delete
                      </DropdownMenuItem>
                    )}
                  </>
                }
              />
              {approval.hasActiveApproval && (
                <span
                  className="mt-2 inline-flex items-center gap-1.5 h-[22px] px-2.5 rounded text-[11px] font-semibold border bg-amber-500/15 text-amber-600 dark:text-amber-300 border-amber-500/55"
                  data-testid="pending-approval-badge"
                >
                  {approval.locked && <Lock className="h-3 w-3" aria-hidden="true" />}
                  {t('recordActions.pendingApproval', 'Pending Approval')}
                </span>
              )}
              {mutations.remove.isPending && (
                <div className="mt-2 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                  Deleting…
                </div>
              )}
            </div>
            <div className="flex-shrink-0 pt-1">
              <QuickActionsMenu
                collectionName={collectionName || ''}
                context="record"
                executionContext={quickActionContext}
              />
            </div>
          </div>
        ) : undefined
      }
      sectionNav={
        hasSectionNav ? (
          <div className="sticky top-6 max-h-[calc(100vh-3rem)] space-y-3 overflow-y-auto">
            <RecordSectionNav items={sectionNavItems} />
            {activityTimeline && (
              <div className="border-t border-border pt-3" data-testid="section-nav-activity">
                {activityTimeline}
              </div>
            )}
          </div>
        ) : undefined
      }
      body={
        record ? (
          <div data-testid="field-values" className="space-y-4">
            <RecordDetailBody
              sections={layout?.sections}
              schemaFields={fields}
              record={record}
              tenantSlug={tenantSlug}
              lookupDisplayMap={lookupDisplayMap}
              picklistDisplayMaps={picklistDisplayMaps}
              persistKeyPrefix={collectionName}
              editable={permissions.canEdit}
              onFieldCommit={handleFieldCommit}
              fallback={
                <>
                  {highlightFields.length > 0 && (
                    <Card className="overflow-hidden rounded-xl border border-border bg-card">
                      <CardContent className="py-5">
                        <div className="grid grid-cols-2 gap-x-10 gap-y-5 md:grid-cols-4">
                          {highlightFields.map((field) => {
                            const value = record[field.name]
                            const isRef =
                              field.type === 'master_detail' ||
                              field.type === 'lookup' ||
                              field.type === 'reference'
                            const displayLabel =
                              isRef && lookupDisplayMap?.[field.name]
                                ? lookupDisplayMap[field.name][String(value)] || undefined
                                : undefined

                            return (
                              <div key={field.name} className="min-w-0 space-y-1">
                                <dt className="kelta-field-label">
                                  {field.displayName || field.name}
                                </dt>
                                <dd className="text-sm font-medium">
                                  <FieldRenderer
                                    type={field.type}
                                    value={value}
                                    fieldName={field.name}
                                    displayName={field.displayName || field.name}
                                    tenantSlug={tenantSlug}
                                    targetCollection={field.referenceTarget}
                                    displayLabel={displayLabel}
                                    picklistDisplayMap={picklistDisplayMaps[field.name]}
                                    truncate
                                  />
                                </dd>
                              </div>
                            )
                          })}
                        </div>
                      </CardContent>
                    </Card>
                  )}

                  {detailFields.length > 0 && (
                    <FieldSection<FieldDefinition>
                      title="Details"
                      fields={detailFields}
                      record={record}
                      lookupDisplayMap={lookupDisplayMap}
                      persistKey={collectionName ? `${collectionName}.details` : undefined}
                      renderField={({
                        field,
                        value,
                        displayLabel,
                      }: FieldSectionRenderContext<FieldDefinition>) => (
                        <FieldRenderer
                          type={field.type}
                          value={value}
                          fieldName={field.name}
                          displayName={field.displayName || field.name}
                          tenantSlug={tenantSlug}
                          targetCollection={field.referenceTarget}
                          displayLabel={displayLabel}
                          picklistDisplayMap={picklistDisplayMaps[field.name]}
                          truncate={false}
                        />
                      )}
                    />
                  )}
                </>
              }
            />
            {/* Activity docks in the left panel on lg+ when the section nav is
                shown; here it covers small screens and no-layout pages. */}
            {activityTimeline && (
              <div className={cn(hasSectionNav && 'lg:hidden')}>{activityTimeline}</div>
            )}
          </div>
        ) : null
      }
      rail={
        record ? (
          railBlocks && railBlocks.length > 0 ? (
            <RailRenderer blocks={railBlocks} />
          ) : (
            <MetadataCard config={{ title: 'System information', rows: systemRows }} />
          )
        ) : undefined
      }
      tabBar={
        record && schema && recordId ? (
          <DetailTabBar
            relatedLists={tabBarRelatedLists}
            recordId={recordId}
            collectionId={schema.id}
            tenantSlug={tenantSlug || ''}
            includedData={rawResponse}
            resource={record}
            notes={notes}
            attachments={attachments}
            apiClient={apiClient}
            invalidateRecordContext={invalidateRecordContext}
            getUserDisplay={getUserDisplay}
            editable={permissions.canEdit}
            onRelatedChange={refetchRecord}
            activeTab={activeTab}
            onTabChange={handleTabChange}
            historyContent={
              schema.trackHistory ? (
                <RecordHistoryTab
                  collectionId={schema.id}
                  recordId={recordId}
                  sections={layout?.sections}
                  schemaFields={fields}
                  tenantSlug={tenantSlug}
                  lookupDisplayMap={lookupDisplayMap}
                  getUserDisplay={getUserDisplay}
                  initialVersion={historyVersion}
                />
              ) : undefined
            }
          />
        ) : undefined
      }
      dialogs={
        <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete {recordTitle}?</AlertDialogTitle>
              <AlertDialogDescription>
                This will permanently delete this record. This action cannot be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={handleDeleteConfirm}
                className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              >
                {mutations.remove.isPending ? (
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                ) : null}
                Delete
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      }
    />
  )
}
