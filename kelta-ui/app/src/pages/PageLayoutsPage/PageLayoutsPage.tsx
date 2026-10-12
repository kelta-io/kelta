/**
 * PageLayoutsPage Component
 *
 * Visual page layout editor for creating and editing collection layouts.
 * Provides a list of all layouts with CRUD, and a WYSIWYG drag-and-drop
 * editor for composing layout sections, field placements, and related lists.
 *
 * Modes:
 * - list: Table of existing layouts with create/edit/delete (shared AdminCrud block)
 * - editor: Three-panel drag-and-drop layout builder
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useToast, ConfirmDialog, LoadingSpinner } from '../../components'
import { useCollectionSummaries, type CollectionSummary } from '../../hooks/useCollectionSummaries'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { RulesEditor } from './RulesEditor'
import { AssignmentRulesEditor } from './components/AssignmentRulesEditor'
import { LayoutEditorList } from './components/LayoutEditorList'
import type { FilterExpression } from '@/components/FilterBuilder'
import { AdminDataTable, type AdminColumn } from '@/components/AdminDataTable'
import type { RailBlockDto, RecordHeaderConfigDto } from '@/hooks/usePageLayout'

import {
  LayoutEditorProvider,
  useLayoutEditor,
  LayoutToolbar,
  FieldPalette,
  PropertyPanel,
  MobilePreview,
  RelatedListPanel,
  DetailExtrasPanel,
} from './components'
import type {
  EditorSection,
  EditorFieldPlacement,
  EditorRelatedList,
  AvailableField,
} from './components/LayoutEditorContext'

// ---------------------------------------------------------------------------
// Shared types
// ---------------------------------------------------------------------------

interface CollectionDetail {
  id: string
  name: string
  displayName: string
  fields: CollectionField[]
}

interface CollectionField {
  id: string
  name: string
  displayName: string
  type: string
  required: boolean
}

interface PageLayoutSummary {
  id: string
  name: string
  description: string | null
  layoutType: string
  collectionId: string
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

interface PageLayoutDetail {
  id: string
  name: string
  description: string | null
  layoutType: string
  collectionId: string
  isDefault: boolean
  sections: ApiSection[]
  relatedLists: ApiRelatedList[]
  defaultFilter?: FilterExpression | null
  defaultSortField?: string | null
  defaultSortDirection?: 'ASC' | 'DESC' | null
  defaultRowLimit?: number | null
  headerConfig?: RecordHeaderConfigDto | null
  railBlocks?: RailBlockDto[] | null
  createdAt: string
  updatedAt: string
}

interface ApiSection {
  id: string
  heading: string
  columns: number
  sortOrder: number
  collapsed: boolean
  style: string
  sectionType: string
  tabGroup?: string
  tabLabel?: string
  visibilityRule?: string
  fields: ApiFieldPlacement[]
}

interface ApiFieldPlacement {
  id: string
  fieldId: string
  fieldName?: string
  fieldType?: string
  fieldDisplayName?: string
  columnNumber: number
  columnSpan?: number
  sortOrder: number
  // Backend stores these on the system collection as `isRequiredOnLayout` /
  // `isReadOnlyOnLayout` (matches the `is_*` DB columns). The JSON:API
  // serializer surfaces them with the same `is`-prefixed keys.
  isRequiredOnLayout: boolean
  isReadOnlyOnLayout: boolean
  labelOverride?: string
  helpTextOverride?: string
  visibilityRule?: string
}

interface ApiRelatedList {
  id: string
  relatedCollectionId: string
  relationshipFieldId: string
  displayColumns: string
  sortField?: string
  sortDirection: string
  rowLimit: number
  sortOrder: number
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

export interface PageLayoutsPageProps {
  testId?: string
}

const LAYOUT_TYPES = ['DETAIL', 'EDIT', 'MINI', 'LIST'] as const

function pageLayoutColumns(
  formatDate: (d: Date, opts?: Intl.DateTimeFormatOptions) => string,
  getCollectionName: (id: string) => string
): AdminColumn<PageLayoutSummary>[] {
  return [
    { id: 'name', header: 'Name', accessor: (r) => r.name },
    {
      id: 'collection',
      header: 'Collection',
      accessor: (r) => getCollectionName(r.collectionId),
    },
    {
      id: 'layoutType',
      header: 'Type',
      accessor: (r) => r.layoutType,
      cell: (r) => (
        <span className="inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold uppercase tracking-wider text-primary">
          {r.layoutType}
        </span>
      ),
    },
    {
      id: 'isDefault',
      header: 'Default',
      accessor: (r) => r.isDefault,
      cell: (r) => (
        <span
          className={cn(
            'inline-block rounded-full px-3 py-1 text-xs font-semibold',
            r.isDefault
              ? 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200'
              : 'bg-muted text-muted-foreground'
          )}
        >
          {r.isDefault ? 'Yes' : 'No'}
        </span>
      ),
    },
    {
      id: 'createdAt',
      header: 'Created',
      accessor: (r) => r.createdAt,
      cell: (r) =>
        formatDate(new Date(r.createdAt), { year: 'numeric', month: 'short', day: 'numeric' }),
    },
  ]
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

const pageLayoutSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
  description: z.string().max(500, 'Description must be 500 characters or fewer'),
  layoutType: z.string(),
  collectionId: z.string().refine((v) => v !== '', 'Collection is required'),
  isDefault: z.boolean(),
})

type PageLayoutFormData = z.infer<typeof pageLayoutSchema>

const LAYOUT_TYPE_OPTIONS = LAYOUT_TYPES.map((type) => ({ value: type, label: type }))

// ---------------------------------------------------------------------------
// Mapping helpers: API → Editor state
// ---------------------------------------------------------------------------

function apiSectionToEditor(s: ApiSection): EditorSection {
  return {
    id: s.id,
    heading: s.heading,
    columns: s.columns,
    sortOrder: s.sortOrder,
    collapsed: s.collapsed,
    style: s.style?.toLowerCase() ?? 'default',
    sectionType: s.sectionType?.toLowerCase() ?? 'fields',
    tabGroup: s.tabGroup,
    tabLabel: s.tabLabel,
    visibilityRule: s.visibilityRule,
    fields: (s.fields ?? []).map(apiFieldToEditor),
  }
}

function apiFieldToEditor(f: ApiFieldPlacement): EditorFieldPlacement {
  return {
    id: f.id,
    fieldId: f.fieldId,
    fieldName: f.fieldName,
    fieldType: f.fieldType,
    fieldDisplayName: f.fieldDisplayName,
    columnNumber: f.columnNumber,
    columnSpan: f.columnSpan,
    sortOrder: f.sortOrder,
    requiredOnLayout: f.isRequiredOnLayout,
    readOnlyOnLayout: f.isReadOnlyOnLayout,
    labelOverride: f.labelOverride,
    helpTextOverride: f.helpTextOverride,
    visibilityRule: f.visibilityRule,
  }
}

function apiRelatedListToEditor(r: ApiRelatedList): EditorRelatedList {
  return {
    id: r.id,
    relatedCollectionId: r.relatedCollectionId,
    relationshipFieldId: r.relationshipFieldId,
    displayColumns: r.displayColumns,
    sortField: r.sortField,
    sortDirection: r.sortDirection,
    rowLimit: r.rowLimit,
    sortOrder: r.sortOrder,
  }
}

// ---------------------------------------------------------------------------
// LayoutEditorView (editor mode, wrapped in LayoutEditorProvider)
// ---------------------------------------------------------------------------

interface LayoutEditorViewProps {
  layoutId: string
  onBack: () => void
}

function LayoutEditorViewInner({ layoutId, onBack }: LayoutEditorViewProps): React.ReactElement {
  const { apiClient } = useApi()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const { state, setLayout, setAvailableFields, markSaved } = useLayoutEditor()

  // Fetch layout detail with all children in a single request using
  // ?include=layout-sections,layout-fields,layout-related-lists.
  // The backend resolves layout-fields transitively via layout-sections.
  const { data: layoutDetail, isLoading: isLoadingLayout } = useQuery({
    queryKey: ['pageLayout', layoutId],
    queryFn: async () => {
      type JsonApiResource = {
        type: string
        id: string
        attributes: Record<string, unknown>
        relationships?: Record<string, { data?: { type: string; id: string } | null }>
      }
      const raw = await apiClient.get<{
        data: JsonApiResource
        included?: JsonApiResource[]
      }>(`/api/page-layouts/${layoutId}?include=layout-sections,layout-fields,layout-related-lists`)

      // Helper: flatten a JSON:API resource into a flat object by merging
      // attributes and extracting relationship IDs (rel.data.id → key).
      const flatten = (r: JsonApiResource): Record<string, unknown> => {
        const obj: Record<string, unknown> = { id: r.id, ...r.attributes }
        if (r.relationships) {
          for (const [key, rel] of Object.entries(r.relationships)) {
            obj[key] = rel?.data?.id ?? null
          }
        }
        return obj
      }

      // Build the layout from the primary data
      const flatLayout = flatten(raw.data)
      const layout: PageLayoutDetail = {
        ...(flatLayout as unknown as Omit<PageLayoutDetail, 'sections' | 'relatedLists'>),
        sections: [],
        relatedLists: [],
      }

      if (!raw.included || raw.included.length === 0) {
        return layout
      }

      // Extract sections, field placements, and related lists from included
      const sectionResources = raw.included
        .filter((r) => r.type === 'layout-sections')
        .map((r) => flatten(r) as unknown as ApiSection & { sortOrder: number })
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))

      const fieldResources = raw.included
        .filter((r) => r.type === 'layout-fields')
        .map(
          (r) =>
            flatten(r) as unknown as ApiFieldPlacement & {
              sectionId: string
              sortOrder: number
            }
        )
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))

      const relatedListResources = raw.included
        .filter((r) => r.type === 'layout-related-lists')
        .map((r) => flatten(r) as unknown as ApiRelatedList & { sortOrder: number })
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))

      // Group field placements by sectionId
      const fieldsBySection = new Map<string, ApiFieldPlacement[]>()
      for (const fp of fieldResources) {
        const list = fieldsBySection.get(fp.sectionId) ?? []
        list.push(fp)
        fieldsBySection.set(fp.sectionId, list)
      }

      // Assemble sections with their fields
      layout.sections = sectionResources.map((s) => ({
        ...s,
        fields: fieldsBySection.get(s.id) ?? [],
      }))
      layout.relatedLists = relatedListResources

      return layout
    },
  })

  // Fetch collection fields when we know the collectionId.
  // Fields are separate records fetched via ?include=fields.
  const collectionId = layoutDetail?.collectionId ?? null
  const { data: collectionDetail } = useQuery({
    queryKey: ['collection-detail', collectionId],
    queryFn: async () => {
      type JsonApiResource = {
        type: string
        id: string
        attributes: Record<string, unknown>
        relationships?: Record<string, { data?: { type: string; id: string } | null }>
      }
      const raw = await apiClient.get<{
        data: JsonApiResource
        included?: JsonApiResource[]
      }>(`/api/collections/${collectionId}?include=fields`)

      const flattenRes = (r: JsonApiResource): Record<string, unknown> => {
        const obj: Record<string, unknown> = { id: r.id, ...r.attributes }
        if (r.relationships) {
          for (const [key, rel] of Object.entries(r.relationships)) {
            obj[key] = rel?.data?.id ?? null
          }
        }
        return obj
      }

      const flat = flattenRes(raw.data)
      const result: CollectionDetail = {
        id: flat.id as string,
        name: flat.name as string,
        displayName: flat.displayName as string,
        fields: [],
      }

      if (raw.included && raw.included.length > 0) {
        result.fields = raw.included
          .filter((r) => r.type === 'fields')
          .map((r) => flattenRes(r) as unknown as CollectionField)
      }

      return result
    },
    enabled: !!collectionId,
  })

  // Populate editor state when both layout and collection fields are loaded.
  // We need the collection fields to resolve fieldId UUIDs on each field
  // placement into human-readable fieldName / fieldDisplayName / fieldType.
  useEffect(() => {
    if (layoutDetail && collectionDetail?.fields) {
      // Build a lookup map: fieldId → CollectionField
      const fieldMap = new Map<string, CollectionField>()
      for (const f of collectionDetail.fields) {
        fieldMap.set(f.id, f)
      }

      // Map sections and resolve field names on each placement
      const sections = (layoutDetail.sections ?? []).map((s) => {
        const editorSection = apiSectionToEditor(s)
        editorSection.fields = editorSection.fields.map((fp) => {
          const field = fieldMap.get(fp.fieldId)
          if (field) {
            return {
              ...fp,
              fieldName: field.name,
              fieldDisplayName: field.displayName || field.name,
              fieldType: field.type,
            }
          }
          return fp
        })
        return editorSection
      })

      const relatedLists = (layoutDetail.relatedLists ?? []).map(apiRelatedListToEditor)
      setLayout(
        layoutDetail.collectionId,
        sections,
        relatedLists,
        layoutDetail.headerConfig ?? null,
        layoutDetail.railBlocks ?? null
      )

      // Also populate the available fields palette
      const fields: AvailableField[] = collectionDetail.fields.map((f) => ({
        id: f.id,
        name: f.name,
        displayName: f.displayName || f.name,
        type: f.type,
        required: f.required,
      }))
      setAvailableFields(fields)
    }
  }, [layoutDetail, collectionDetail, setLayout, setAvailableFields])

  // Save mutation — decomposes hierarchical editor state into individual
  // sub-resource API calls (POST/PUT/DELETE) because the backend
  // DynamicCollectionRouter only handles flat attribute updates on a
  // single resource and cannot process nested child arrays.
  const saveMutation = useMutation({
    mutationFn: async () => {
      const originalSections = layoutDetail?.sections ?? []
      const originalRelatedLists = layoutDetail?.relatedLists ?? []

      // Build sets of original IDs for diffing against desired state
      const originalSectionIds = new Set(originalSections.map((s) => s.id))
      const originalFieldIds = new Set<string>()
      for (const s of originalSections) {
        for (const f of s.fields ?? []) {
          originalFieldIds.add(f.id)
        }
      }
      const originalRelatedListIds = new Set(originalRelatedLists.map((r) => r.id))

      // Desired state from the editor
      const desiredSections = state.sections
      const desiredRelatedLists = state.relatedLists
      const desiredSectionIds = new Set(desiredSections.map((s) => s.id))
      const desiredFieldIds = new Set<string>()
      for (const s of desiredSections) {
        for (const f of s.fields) {
          desiredFieldIds.add(f.id)
        }
      }
      const desiredRelatedListIds = new Set(desiredRelatedLists.map((r) => r.id))

      // Step 1: PUT parent layout only when its scalar attributes actually
      // changed. The editor doesn't currently expose layout-level attributes
      // for editing, so this is a near-perfect skip in practice — but the
      // diff guards future edits, too.
      // Stable JSON serialization for change-detection on JSONB attributes.
      // `headerConfig` + `railBlocks` are deep objects edited in-place by the
      // DetailExtrasPanel; comparing stringified JSON is good enough because
      // the editor always produces canonical shapes (no key reordering).
      const headerConfigJson = state.headerConfig ? JSON.stringify(state.headerConfig) : null
      const originalHeaderConfigJson = layoutDetail?.headerConfig
        ? JSON.stringify(layoutDetail.headerConfig)
        : null
      const railBlocksJson = state.railBlocks ? JSON.stringify(state.railBlocks) : null
      const originalRailBlocksJson = layoutDetail?.railBlocks
        ? JSON.stringify(layoutDetail.railBlocks)
        : null

      const layoutPayload = {
        name: layoutDetail!.name,
        description: layoutDetail!.description ?? '',
        layoutType: layoutDetail!.layoutType,
        isDefault: layoutDetail!.isDefault,
        headerConfig: state.headerConfig,
        railBlocks: state.railBlocks,
      }
      const layoutChanged =
        !layoutDetail ||
        layoutPayload.name !== layoutDetail.name ||
        layoutPayload.description !== (layoutDetail.description ?? '') ||
        layoutPayload.layoutType !== layoutDetail.layoutType ||
        layoutPayload.isDefault !== layoutDetail.isDefault ||
        headerConfigJson !== originalHeaderConfigJson ||
        railBlocksJson !== originalRailBlocksJson
      if (layoutChanged) {
        await apiClient.putResource(`/api/page-layouts/${layoutId}`, layoutPayload)
      }

      // Step 2: Delete removed fields first (before sections, to handle moves)
      const fieldIdsToDelete = [...originalFieldIds].filter((id) => !desiredFieldIds.has(id))
      if (fieldIdsToDelete.length > 0) {
        await Promise.all(
          fieldIdsToDelete.map((id) => apiClient.deleteResource(`/api/layout-fields/${id}`))
        )
      }

      // Step 3: Delete removed sections
      const sectionIdsToDelete = [...originalSectionIds].filter((id) => !desiredSectionIds.has(id))
      if (sectionIdsToDelete.length > 0) {
        await Promise.all(
          sectionIdsToDelete.map((id) => apiClient.deleteResource(`/api/layout-sections/${id}`))
        )
      }

      // Step 4: Delete removed related lists
      const relatedListIdsToDelete = [...originalRelatedListIds].filter(
        (id) => !desiredRelatedListIds.has(id)
      )
      if (relatedListIdsToDelete.length > 0) {
        await Promise.all(
          relatedListIdsToDelete.map((id) =>
            apiClient.deleteResource(`/api/layout-related-lists/${id}`)
          )
        )
      }

      // Step 5: Create new sections and map editor UUIDs → server IDs
      const sectionIdMap = new Map<string, string>()
      const sectionsToCreate = desiredSections.filter((s) => !originalSectionIds.has(s.id))
      for (const s of sectionsToCreate) {
        const created = await apiClient.postResource<{ id: string }>('/api/layout-sections', {
          layoutId,
          heading: s.heading,
          columns: s.columns,
          sortOrder: s.sortOrder,
          collapsed: s.collapsed,
          style: (s.style ?? 'default').toUpperCase(),
          sectionType: (s.sectionType ?? 'fields').toUpperCase(),
          tabGroup: s.tabGroup || null,
          tabLabel: s.tabLabel || null,
          visibilityRule: s.visibilityRule || null,
        })
        sectionIdMap.set(s.id, created.id)
      }

      // Step 6: Update existing sections (scalar attributes only)
      // Skip PUTs for sections whose tracked attributes are unchanged.
      const originalSectionsById = new Map(originalSections.map((s) => [s.id, s]))
      const sectionsToUpdate = desiredSections.filter((s) => originalSectionIds.has(s.id))
      const sectionUpdatePromises: Promise<unknown>[] = []
      for (const s of sectionsToUpdate) {
        const original = originalSectionsById.get(s.id)
        const payload = {
          heading: s.heading,
          columns: s.columns,
          sortOrder: s.sortOrder,
          collapsed: s.collapsed,
          style: (s.style ?? 'default').toUpperCase(),
          sectionType: (s.sectionType ?? 'fields').toUpperCase(),
          tabGroup: s.tabGroup || null,
          tabLabel: s.tabLabel || null,
          visibilityRule: s.visibilityRule || null,
        }
        const unchanged =
          original &&
          original.heading === payload.heading &&
          original.columns === payload.columns &&
          original.sortOrder === payload.sortOrder &&
          !!original.collapsed === payload.collapsed &&
          (original.style ?? 'DEFAULT').toUpperCase() === payload.style &&
          (original.sectionType ?? 'FIELDS').toUpperCase() === payload.sectionType &&
          (original.tabGroup || null) === payload.tabGroup &&
          (original.tabLabel || null) === payload.tabLabel &&
          (original.visibilityRule || null) === payload.visibilityRule
        if (unchanged) continue
        sectionUpdatePromises.push(apiClient.putResource(`/api/layout-sections/${s.id}`, payload))
      }
      if (sectionUpdatePromises.length > 0) {
        await Promise.all(sectionUpdatePromises)
      }

      // Step 7: Create/update field placements
      // Build a lookup of the original field state by id so we can diff and skip
      // PUTs for placements that haven't actually changed. Avoids hitting the
      // backend with N unchanged fields every time the user tweaks one toggle.
      const originalFieldsById = new Map<string, ApiFieldPlacement & { sectionId: string }>()
      for (const s of originalSections) {
        for (const f of s.fields ?? []) {
          originalFieldsById.set(f.id, { ...f, sectionId: s.id })
        }
      }
      const fieldPromises: Promise<unknown>[] = []
      for (const section of desiredSections) {
        const serverSectionId = sectionIdMap.get(section.id) ?? section.id
        for (const f of section.fields) {
          // Backend stores these as `is*`-prefixed boolean columns; using the
          // unprefixed names here would silently no-op the update.
          const fieldPayload = {
            fieldId: f.fieldId,
            columnNumber: f.columnNumber,
            columnSpan: f.columnSpan && f.columnSpan > 1 ? f.columnSpan : null,
            sortOrder: f.sortOrder,
            isRequiredOnLayout: !!f.requiredOnLayout,
            isReadOnlyOnLayout: !!f.readOnlyOnLayout,
            labelOverride: f.labelOverride || null,
            helpTextOverride: f.helpTextOverride || null,
            visibilityRule: f.visibilityRule || null,
          }
          if (originalFieldIds.has(f.id)) {
            const original = originalFieldsById.get(f.id)
            const unchanged =
              original &&
              original.sectionId === serverSectionId &&
              original.fieldId === fieldPayload.fieldId &&
              original.columnNumber === fieldPayload.columnNumber &&
              (original.columnSpan ?? null) === fieldPayload.columnSpan &&
              original.sortOrder === fieldPayload.sortOrder &&
              !!original.isRequiredOnLayout === fieldPayload.isRequiredOnLayout &&
              !!original.isReadOnlyOnLayout === fieldPayload.isReadOnlyOnLayout &&
              (original.labelOverride ?? null) === fieldPayload.labelOverride &&
              (original.helpTextOverride ?? null) === fieldPayload.helpTextOverride &&
              (original.visibilityRule ?? null) === fieldPayload.visibilityRule
            if (unchanged) continue
            fieldPromises.push(
              apiClient.putResource(`/api/layout-fields/${f.id}`, {
                ...fieldPayload,
                sectionId: serverSectionId,
              })
            )
          } else {
            fieldPromises.push(
              apiClient.postResource('/api/layout-fields', {
                ...fieldPayload,
                sectionId: serverSectionId,
              })
            )
          }
        }
      }
      if (fieldPromises.length > 0) {
        await Promise.all(fieldPromises)
      }

      // Step 8: Create/update related lists
      const relatedListPromises: Promise<unknown>[] = []
      for (const rl of desiredRelatedLists) {
        // Normalize displayColumns: may be a parsed array (JSONB) or JSON string
        let normalizedDisplayColumns: unknown = rl.displayColumns
        if (typeof normalizedDisplayColumns === 'string') {
          try {
            normalizedDisplayColumns = JSON.parse(normalizedDisplayColumns)
          } catch {
            normalizedDisplayColumns = (normalizedDisplayColumns as string)
              .split(',')
              .map((s: string) => s.trim())
              .filter(Boolean)
          }
        }
        if (!Array.isArray(normalizedDisplayColumns)) {
          normalizedDisplayColumns = []
        }

        const rlPayload = {
          relatedCollectionId: rl.relatedCollectionId,
          relationshipFieldId: rl.relationshipFieldId,
          displayColumns: normalizedDisplayColumns,
          sortField: rl.sortField || null,
          sortDirection: rl.sortDirection,
          rowLimit: rl.rowLimit,
          sortOrder: rl.sortOrder,
        }
        if (originalRelatedListIds.has(rl.id)) {
          relatedListPromises.push(
            apiClient.putResource(`/api/layout-related-lists/${rl.id}`, rlPayload)
          )
        } else {
          relatedListPromises.push(
            apiClient.postResource('/api/layout-related-lists', {
              ...rlPayload,
              layoutId,
            })
          )
        }
      }
      if (relatedListPromises.length > 0) {
        await Promise.all(relatedListPromises)
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pageLayouts'] })
      queryClient.invalidateQueries({ queryKey: ['pageLayout', layoutId] })
      queryClient.invalidateQueries({ queryKey: ['page-layout-resolve'] })
      markSaved()
      showToast('Layout saved successfully', 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to save layout', 'error')
    },
  })

  const handleSave = useCallback(() => {
    saveMutation.mutate()
  }, [saveMutation])

  const handleBack = useCallback(() => {
    if (state.isDirty) {
      const confirmed = window.confirm('You have unsaved changes. Are you sure you want to leave?')
      if (!confirmed) return
    }
    onBack()
  }, [state.isDirty, onBack])

  if (isLoadingLayout) {
    return (
      <div className="flex h-screen items-center justify-center">
        <LoadingSpinner size="large" label="Loading layout..." />
      </div>
    )
  }

  if (layoutDetail?.layoutType === 'LIST' && !collectionDetail) {
    return (
      <div className="flex h-screen items-center justify-center">
        <LoadingSpinner size="large" label="Loading layout..." />
      </div>
    )
  }

  if (layoutDetail?.layoutType === 'LIST' && collectionDetail) {
    const firstSection = layoutDetail.sections[0]
    const fieldMap = new Map(collectionDetail.fields.map((f) => [f.id, f]))
    const initialColumns = (firstSection?.fields ?? [])
      .slice()
      .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
      .map((fp) => {
        const field = fieldMap.get(fp.fieldId)
        return {
          id: fp.id,
          serverId: fp.id,
          fieldId: fp.fieldId,
          fieldName: field?.name,
          fieldDisplayName: field?.displayName ?? field?.name,
          fieldType: field?.type,
          columnSpan: fp.columnSpan ?? 1,
          labelOverride: fp.labelOverride,
        }
      })

    const availableFieldOptions = collectionDetail.fields.map((f) => ({
      id: f.id,
      name: f.name,
      displayName: f.displayName || f.name,
    }))

    return (
      <LayoutEditorList
        initialData={{
          layoutId: layoutDetail.id,
          collectionId: layoutDetail.collectionId,
          layoutName: layoutDetail.name,
          sectionId: firstSection?.id,
          initialColumns,
          defaultFilter: layoutDetail.defaultFilter ?? null,
          defaultSortField: layoutDetail.defaultSortField ?? undefined,
          defaultSortDirection: layoutDetail.defaultSortDirection ?? 'ASC',
          defaultRowLimit: layoutDetail.defaultRowLimit ?? 50,
        }}
        availableFields={availableFieldOptions}
        onBack={handleBack}
      />
    )
  }

  return (
    <div className="flex h-screen flex-col overflow-hidden" data-testid="layout-editor">
      <LayoutToolbar
        onBack={handleBack}
        layoutName={layoutDetail?.name ?? 'Layout'}
        onSave={handleSave}
        isSaving={saveMutation.isPending}
      />
      <div className="flex flex-1 min-h-0 overflow-hidden">
        <FieldPalette />
        <MobilePreview />
        <div className="flex w-[300px] flex-col overflow-y-auto border-l border-border bg-background max-md:w-full max-md:max-h-[300px] max-md:border-l-0 max-md:border-t">
          <PropertyPanel />
          <RelatedListPanel />
          {layoutDetail?.layoutType === 'DETAIL' && <DetailExtrasPanel />}
        </div>
      </div>
    </div>
  )
}

function LayoutEditorView({ layoutId, onBack }: LayoutEditorViewProps): React.ReactElement {
  return (
    <LayoutEditorProvider>
      <LayoutEditorViewInner layoutId={layoutId} onBack={onBack} />
    </LayoutEditorProvider>
  )
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

export function PageLayoutsPage({
  testId = 'page-layouts-page',
}: PageLayoutsPageProps): React.ReactElement {
  const { formatDate } = useI18n()
  const { apiClient } = useApi()

  // View mode: list or editor
  const [viewMode, setViewMode] = useState<'list' | 'editor'>('list')
  const [editingLayoutId, setEditingLayoutId] = useState<string | null>(null)

  const [rulesEditorLayout, setRulesEditorLayout] = useState<PageLayoutSummary | null>(null)
  const [assignmentEditorLayout, setAssignmentEditorLayout] = useState<PageLayoutSummary | null>(
    null
  )

  const {
    data: layouts,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['pageLayouts'],
    queryFn: () => apiClient.getList<PageLayoutSummary>(`/api/page-layouts`),
    enabled: viewMode === 'list',
  })

  const { summaries: collections } = useCollectionSummaries()

  const collectionMap = useMemo(() => {
    const map = new Map<string, CollectionSummary>()
    for (const col of collections) {
      map.set(col.id, col)
    }
    return map
  }, [collections])

  const collectionOptions = useMemo(
    () => collections.map((col) => ({ value: col.id, label: col.displayName || col.name })),
    [collections]
  )

  const layoutList = layouts ?? []

  const crud = useAdminCrud<PageLayoutSummary, PageLayoutFormData>({
    queryKey: ['pageLayouts'],
    create: (data) =>
      apiClient.postResource<PageLayoutSummary>(
        `/api/page-layouts?collectionId=${encodeURIComponent(data.collectionId)}`,
        data
      ),
    update: (layout, data) =>
      apiClient.putResource<PageLayoutSummary>(`/api/page-layouts/${layout.id}`, data),
    remove: async (layout) => {
      await apiClient.delete(`/api/page-layouts/${layout.id}`)
      if (editingLayoutId === layout.id) {
        setViewMode('list')
        setEditingLayoutId(null)
      }
    },
    messages: {
      created: 'Layout created successfully',
      updated: 'Layout updated successfully',
      deleted: 'Layout deleted successfully',
      error: 'An error occurred',
    },
    // Open the editor for the new layout
    onCreated: (newLayout) => {
      setEditingLayoutId((newLayout as PageLayoutSummary).id)
      setViewMode('editor')
    },
  })

  const handleDesign = useCallback((layout: PageLayoutSummary) => {
    setEditingLayoutId(layout.id)
    setViewMode('editor')
  }, [])

  const handleBackFromEditor = useCallback(() => {
    setViewMode('list')
    setEditingLayoutId(null)
  }, [])

  const getCollectionName = useCallback(
    (collectionId: string): string => {
      const col = collectionMap.get(collectionId)
      return col ? col.displayName || col.name : collectionId
    },
    [collectionMap]
  )

  const columns = useMemo(
    () => pageLayoutColumns(formatDate, getCollectionName),
    [formatDate, getCollectionName]
  )

  // Editor mode
  if (viewMode === 'editor' && editingLayoutId) {
    return <LayoutEditorView layoutId={editingLayoutId} onBack={handleBackFromEditor} />
  }

  const editing = crud.editing

  // List mode
  return (
    <AdminCrudPage
      testId={testId}
      title="Page Layouts"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="Create Layout"
          data-testid="add-layout-button"
        >
          Create Layout
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={layoutList.length === 0}
      emptyMessage="No page layouts found. Create one to get started."
      list={
        <AdminDataTable
          tableId="page-layouts"
          testId="page-layouts-table"
          ariaLabel="Page Layouts"
          columns={columns}
          rows={layoutList}
          rowKey={(layout) => layout.id}
          renderActions={(layout, index) => (
            <div className="flex justify-end gap-2 max-md:flex-col">
              <Button
                type="button"
                size="sm"
                onClick={() => handleDesign(layout)}
                aria-label={`Design ${layout.name}`}
                data-testid={`design-button-${index}`}
              >
                Design
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(layout)}
                aria-label={`Edit ${layout.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setRulesEditorLayout(layout)}
                aria-label={`Rules for ${layout.name}`}
                data-testid={`rules-button-${index}`}
              >
                Rules
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setAssignmentEditorLayout(layout)}
                aria-label={`Assignments for ${layout.name}`}
                data-testid={`assignments-button-${index}`}
              >
                Assignments
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(layout)}
                aria-label={`Delete ${layout.name}`}
                data-testid={`delete-button-${index}`}
              >
                Delete
              </Button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<PageLayoutFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit Layout' : 'Create Layout'}
        schema={pageLayoutSchema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
          layoutType: editing?.layoutType ?? 'DETAIL',
          collectionId: editing?.collectionId ?? '',
          isDefault: editing?.isDefault ?? false,
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="layout"
        className="sm:max-w-[600px]"
      >
        <CrudTextField<PageLayoutFormData>
          name="name"
          id="layout-name"
          label="Name"
          required
          placeholder="Enter layout name"
          testId="layout-name-input"
        />
        <CrudTextField<PageLayoutFormData>
          name="description"
          id="layout-description"
          label="Description"
          multiline
          placeholder="Enter layout description"
          testId="layout-description-input"
        />
        <CrudSelectField<PageLayoutFormData>
          name="layoutType"
          id="layout-type"
          label="Layout Type"
          required
          options={LAYOUT_TYPE_OPTIONS}
          testId="layout-type-select"
        />
        <CrudSelectField<PageLayoutFormData>
          name="collectionId"
          id="layout-collection-id"
          label="Collection"
          required
          placeholder="Select a collection"
          options={collectionOptions}
          disabled={!!editing}
          testId="layout-collection-id-input"
        />
        <CrudCheckboxField<PageLayoutFormData>
          name="isDefault"
          id="layout-is-default"
          label="Default Layout"
          testId="layout-is-default-input"
        />
      </CrudFormDialog>

      {rulesEditorLayout && (
        <RulesEditor
          layoutId={rulesEditorLayout.id}
          layoutName={rulesEditorLayout.name}
          fieldNames={[]}
          onClose={() => setRulesEditorLayout(null)}
        />
      )}

      {assignmentEditorLayout && (
        <AssignmentRulesEditor
          open
          onClose={() => setAssignmentEditorLayout(null)}
          layoutId={assignmentEditorLayout.id}
          layoutName={assignmentEditorLayout.name}
          collectionId={assignmentEditorLayout.collectionId}
        />
      )}

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Layout"
        message="Are you sure you want to delete this layout? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}

export default PageLayoutsPage
