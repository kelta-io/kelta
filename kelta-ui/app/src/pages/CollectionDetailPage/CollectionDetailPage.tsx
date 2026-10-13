/**
 * CollectionDetailPage Component
 *
 * Displays detailed information about a single collection including:
 * - Collection metadata (name, displayName, description, status)
 * - Fields list with field details
 * - Version history
 *
 * Requirements:
 * - 3.7: Navigate to collection detail page when clicking on a collection
 * - 3.8: Display collection metadata and list of fields
 * - 3.12: Display collection version history with ability to view previous versions
 */

import React, { useState, useCallback, useMemo, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useApi } from '../../context/ApiContext'
import {
  useToast,
  ConfirmDialog,
  LoadingSpinner,
  ErrorMessage,
  DataSourceSettings,
} from '../../components'
import {
  FieldEditor,
  type FieldDefinition as FieldEditorDefinition,
  type RollupChildField,
} from '../../components/FieldEditor'
import { ValidationRuleEditor } from '../../components/ValidationRuleEditor'
import { RecordTypeEditor } from '../../components/RecordTypeEditor'
import { RecordTypePicklistEditor } from '../../components/RecordTypePicklistEditor'
import { PicklistDependencyEditor } from '../../components/PicklistDependencyEditor'
import { useCollectionSummaries } from '../../hooks/useCollectionSummaries'
import { cn } from '@/lib/utils'
import type {
  Collection,
  FieldDefinition,
  CollectionVersion,
  CollectionValidationRule,
  RecordType,
  PicklistDependency,
  SetupAuditTrailEntry,
} from '../../types/collections'
import { normalizeFieldType } from '../../utils/normalizeFieldType'

/**
 * The designer shows legacy REFERENCE / LOOKUP fields as the type they are stored
 * as, rather than collapsing them to master_detail like the record pages do.
 */
const DESIGNER_TYPE_OPTIONS = { collapseRelationships: false } as const

/**
 * Props for CollectionDetailPage component
 */
export interface CollectionDetailPageProps {
  /** Collection ID/name from route params (optional, can be from useParams) */
  collectionId?: string
  /** Optional test ID for testing */
  testId?: string
}

/**
 * Get display text for field type
 */
function getFieldTypeDisplay(type: FieldDefinition['type'], t: (key: string) => string): string {
  return t(`fields.types.${type.toLowerCase()}`)
}

/** Helper to get action badge color classes */
function getActionBadgeClasses(action: string): string {
  switch (action) {
    case 'CREATED':
    case 'ACTIVATED':
      return 'text-emerald-800 bg-emerald-100 dark:text-emerald-300 dark:bg-emerald-900'
    case 'DELETED':
      return 'text-red-800 bg-red-100 dark:text-red-300 dark:bg-red-900'
    case 'UPDATED':
    case 'MODIFIED':
      return 'text-amber-800 bg-amber-100 dark:text-amber-300 dark:bg-amber-900'
    case 'DEACTIVATED':
      return 'text-gray-500 bg-gray-100 dark:text-gray-400 dark:bg-gray-800'
    default:
      return 'text-blue-800 bg-blue-100 dark:text-blue-300 dark:bg-blue-900'
  }
}

/**
 * CollectionDetailPage Component
 *
 * Main page for viewing and managing a single collection.
 * Provides metadata display, fields list, authorization config, and version history.
 */
export function CollectionDetailPage({
  collectionId: propCollectionId,
  testId = 'collection-detail-page',
}: CollectionDetailPageProps): React.ReactElement {
  const params = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { showToast } = useToast()
  const { apiClient } = useApi()

  // Get collection ID from props or route params
  const collectionId = propCollectionId || params.id || ''

  // Delete confirmation dialog state
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)

  // Field editor modal state
  const [fieldEditorOpen, setFieldEditorOpen] = useState(false)
  const [editingField, setEditingField] = useState<FieldDefinition | undefined>(undefined)

  // Validation rule editor state
  const [validationRuleEditorOpen, setValidationRuleEditorOpen] = useState(false)
  const [editingValidationRule, setEditingValidationRule] = useState<
    CollectionValidationRule | undefined
  >(undefined)
  const [deleteValidationRuleDialogOpen, setDeleteValidationRuleDialogOpen] = useState(false)
  const [validationRuleToDelete, setValidationRuleToDelete] = useState<
    CollectionValidationRule | undefined
  >(undefined)

  // Record type editor state
  const [recordTypeEditorOpen, setRecordTypeEditorOpen] = useState(false)
  const [editingRecordType, setEditingRecordType] = useState<RecordType | undefined>(undefined)
  const [deleteRecordTypeDialogOpen, setDeleteRecordTypeDialogOpen] = useState(false)
  const [recordTypeToDelete, setRecordTypeToDelete] = useState<RecordType | undefined>(undefined)
  const [picklistOverrideRecordType, setPicklistOverrideRecordType] = useState<
    RecordType | undefined
  >(undefined)

  // Picklist dependency editor state
  const [dependencyEditorOpen, setDependencyEditorOpen] = useState(false)
  const [editingDependency, setEditingDependency] = useState<PicklistDependency | undefined>(
    undefined
  )
  const [deleteDependencyDialogOpen, setDeleteDependencyDialogOpen] = useState(false)
  const [dependencyToDelete, setDependencyToDelete] = useState<PicklistDependency | undefined>(
    undefined
  )

  // Active tab state for sections
  const [activeTab, setActiveTab] = useState<
    | 'fields'
    | 'validationRules'
    | 'recordTypes'
    | 'picklistDependencies'
    | 'changeHistory'
    | 'dataSource'
  >('fields')

  // Fetch collection data with included fields.
  // Fields are stored as separate records in the "fields" collection with a
  // master-detail relationship to "collections". Using ?include=fields tells
  // the DynamicCollectionRouter to resolve the inverse relationship and return
  // field records in the JSON:API "included" array.
  const {
    data: collection,
    isLoading: isLoadingCollection,
    error: collectionError,
    refetch: refetchCollection,
  } = useQuery({
    queryKey: ['collection', collectionId],
    queryFn: async () => {
      const raw = await apiClient.get<{
        data: { type: string; id: string; attributes: Record<string, unknown> }
        included?: { type: string; id: string; attributes: Record<string, unknown> }[]
      }>(`/api/collections/${collectionId}?include=fields`)

      // Flatten the primary resource (collection)
      const result: Collection = {
        id: raw.data.id,
        ...(raw.data.attributes as Omit<Collection, 'id' | 'fields'>),
      } as Collection

      // Merge included "fields" resources into collection.fields
      if (raw.included && raw.included.length > 0) {
        result.fields = raw.included
          .filter((r) => r.type === 'fields')
          .map((r) => ({ id: r.id, ...r.attributes }) as unknown as FieldDefinition)
      }

      // Normalize field types from backend canonical form to UI form.
      // The backend stores types as uppercase enums (e.g., "STRING", "DOUBLE", "PICKLIST")
      // but the UI uses lowercase aliases (e.g., "string", "number", "picklist").
      if (result.fields) {
        result.fields = result.fields.map((f) => ({
          ...f,
          type: normalizeFieldType(f.type, DESIGNER_TYPE_OPTIONS),
        }))
      }
      return result
    },
    enabled: !!collectionId,
  })

  // Fetch version history
  const {
    data: versions = [],
    isLoading: isLoadingVersions,
    error: versionsError,
  } = useQuery({
    queryKey: ['collection-versions', collectionId],
    queryFn: () =>
      apiClient.getList<CollectionVersion>(
        `/api/collection-versions?filter[collectionId][eq]=${collectionId}`
      ),
    enabled: !!collectionId && activeTab === 'changeHistory',
  })

  // Fetch validation rules
  const { data: validationRules = [], isLoading: isLoadingRules } = useQuery({
    queryKey: ['validation-rules', collectionId],
    queryFn: () =>
      apiClient.getList<CollectionValidationRule>(
        `/api/validation-rules?filter[collectionId][eq]=${collectionId}`
      ),
    enabled: !!collectionId && activeTab === 'validationRules',
  })

  // Fetch record types
  const { data: recordTypes = [], isLoading: isLoadingRecordTypes } = useQuery({
    queryKey: ['record-types', collectionId],
    queryFn: () =>
      apiClient.getList<RecordType>(`/api/record-types?filter[collectionId][eq]=${collectionId}`),
    enabled: !!collectionId && activeTab === 'recordTypes',
  })

  // Fetch picklist dependencies (one query per picklist field, combined)
  const picklistFieldIds = useMemo(() => {
    if (!collection?.fields) return []
    return collection.fields
      .filter((f) => f.type === 'picklist' || f.type === 'multi_picklist')
      .map((f) => f.id)
  }, [collection])

  const { data: allDependencies = [], isLoading: isLoadingDependencies } = useQuery({
    queryKey: ['picklist-dependencies', collectionId, picklistFieldIds],
    queryFn: async () => {
      if (picklistFieldIds.length === 0) return []
      const results = await Promise.all(
        picklistFieldIds.map((fieldId) =>
          apiClient
            .getList<PicklistDependency>(
              `/api/picklist-dependencies?filter[controllingFieldId][eq]=${fieldId}`
            )
            .catch(() => [] as PicklistDependency[])
        )
      )
      // Deduplicate by id
      const seen = new Set<string>()
      const deduped: PicklistDependency[] = []
      for (const deps of results) {
        for (const dep of deps) {
          if (!seen.has(dep.id)) {
            seen.add(dep.id)
            deduped.push(dep)
          }
        }
      }
      return deduped
    },
    enabled: !!collectionId && activeTab === 'picklistDependencies' && picklistFieldIds.length > 0,
  })

  // Fetch setup audit trail (filtered to this collection)
  const { data: setupAuditPage, isLoading: isLoadingAudit } = useQuery({
    queryKey: ['setup-audit', collectionId],
    queryFn: () =>
      apiClient.getPage<SetupAuditTrailEntry>(
        `/api/setup-audit-entries?filter[entityType][eq]=Collection&filter[entityId][eq]=${collectionId}&page[size]=50`
      ),
    enabled: activeTab === 'changeHistory' && !!collectionId,
  })

  // Fetch all collections for reference field dropdown
  const { summaries: allCollectionSummaries } = useCollectionSummaries()

  // Loader for child collection fields, used by the rollup_summary config UI
  // in the FieldEditor. Fetches fresh on each invocation; cheap enough since
  // it's only called when the user actively configures a rollup field.
  const fetchChildCollectionFields = useCallback(
    async (childCollectionName: string): Promise<RollupChildField[]> => {
      const raw = await apiClient.get<{
        included?: { type: string; id: string; attributes: Record<string, unknown> }[]
      }>(`/api/collections/${encodeURIComponent(childCollectionName)}?include=fields`)
      if (!raw.included) return []
      return raw.included
        .filter((r) => r.type === 'fields')
        .map((r) => {
          const attrs = r.attributes as Record<string, unknown>
          return {
            name: attrs.name as string,
            displayName: (attrs.displayName as string | undefined) ?? undefined,
            type: normalizeFieldType((attrs.type as string) ?? 'string', DESIGNER_TYPE_OPTIONS),
            referenceTarget: (attrs.referenceTarget as string | undefined) ?? undefined,
          }
        })
    },
    [apiClient]
  )

  // Fetch global picklists for picklist field dropdown
  const {
    data: globalPicklists = [],
    isLoading: globalPicklistsLoading,
    isError: globalPicklistsError,
    refetch: refetchGlobalPicklists,
  } = useQuery({
    queryKey: ['global-picklists'],
    queryFn: async () => {
      // Default page size is 20 — an unpaginated fetch silently dropped any
      // picklist past the first page, so a field bound to one rendered as unset.
      const response = await apiClient.getList<{ id: string; name: string }>(
        '/api/global-picklists?page[size]=200'
      )
      return response
    },
    enabled: fieldEditorOpen,
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: async () => {
      await apiClient.deleteResource(`/api/collections/${collectionId}`)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] })
      showToast(t('success.deleted', { item: t('collections.title') }), 'success')
      navigate(`/${getTenantSlug()}/collections`)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Invalidate every cache that holds a snapshot of this collection's fields:
  // the admin page query, the bulk store that primes useCollectionSchema, and
  // the per-collection schema entry that end-user pages consume. Without all
  // three, end-user sessions silently render stale layouts when an admin adds
  // a field — the layout placement falls through to a missing schema field
  // and is dropped without warning.
  const invalidateCollectionCaches = () => {
    queryClient.invalidateQueries({ queryKey: ['collection', collectionId] })
    queryClient.invalidateQueries({ queryKey: ['collection-store'] })
    if (collection?.name) {
      queryClient.invalidateQueries({ queryKey: ['collection-schema', collection.name] })
    }
  }

  // Add field mutation
  const addFieldMutation = useMutation({
    mutationFn: async (fieldData: Omit<FieldEditorDefinition, 'id' | 'order'>) => {
      await apiClient.postResource(`/api/fields`, { ...fieldData, collectionId })
    },
    onSuccess: () => {
      invalidateCollectionCaches()
      showToast(t('success.created', { item: t('collections.field') }), 'success')
      setFieldEditorOpen(false)
      setEditingField(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Update field mutation
  const updateFieldMutation = useMutation({
    mutationFn: async ({
      fieldId,
      fieldData,
    }: {
      fieldId: string
      fieldData: Partial<FieldEditorDefinition>
    }) => {
      await apiClient.putResource(`/api/fields/${fieldId}`, fieldData)
    },
    onSuccess: () => {
      invalidateCollectionCaches()
      showToast(t('success.updated', { item: t('collections.field') }), 'success')
      setFieldEditorOpen(false)
      setEditingField(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // --- Validation Rule mutations ---
  const createValidationRuleMutation = useMutation({
    mutationFn: async (data: Record<string, unknown>) => {
      await apiClient.postResource(`/api/validation-rules`, { ...data, collectionId })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['validation-rules', collectionId] })
      showToast(t('success.created', { item: t('collections.validationRules') }), 'success')
      setValidationRuleEditorOpen(false)
      setEditingValidationRule(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const updateValidationRuleMutation = useMutation({
    mutationFn: async ({ ruleId, data }: { ruleId: string; data: Record<string, unknown> }) => {
      await apiClient.putResource(`/api/validation-rules/${ruleId}`, data)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['validation-rules', collectionId] })
      showToast(t('success.updated', { item: t('collections.validationRules') }), 'success')
      setValidationRuleEditorOpen(false)
      setEditingValidationRule(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const deleteValidationRuleMutation = useMutation({
    mutationFn: async (ruleId: string) => {
      await apiClient.deleteResource(`/api/validation-rules/${ruleId}`)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['validation-rules', collectionId] })
      showToast(t('success.deleted', { item: t('collections.validationRules') }), 'success')
      setDeleteValidationRuleDialogOpen(false)
      setValidationRuleToDelete(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const activateValidationRuleMutation = useMutation({
    mutationFn: async (ruleId: string) => {
      await apiClient.patchResource(`/api/validation-rules/${ruleId}`, { active: true })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['validation-rules', collectionId] })
      showToast(t('success.activated'), 'success')
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const deactivateValidationRuleMutation = useMutation({
    mutationFn: async (ruleId: string) => {
      await apiClient.patchResource(`/api/validation-rules/${ruleId}`, { active: false })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['validation-rules', collectionId] })
      showToast(t('success.deactivated'), 'success')
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // --- Record Type mutations ---
  const createRecordTypeMutation = useMutation({
    mutationFn: async (data: Record<string, unknown>) => {
      await apiClient.postResource(`/api/record-types`, { ...data, collectionId })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['record-types', collectionId] })
      showToast(t('success.created', { item: t('collections.recordTypes') }), 'success')
      setRecordTypeEditorOpen(false)
      setEditingRecordType(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const updateRecordTypeMutation = useMutation({
    mutationFn: async ({ rtId, data }: { rtId: string; data: Record<string, unknown> }) => {
      await apiClient.putResource(`/api/record-types/${rtId}`, data)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['record-types', collectionId] })
      showToast(t('success.updated', { item: t('collections.recordTypes') }), 'success')
      setRecordTypeEditorOpen(false)
      setEditingRecordType(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const deleteRecordTypeMutation = useMutation({
    mutationFn: async (rtId: string) => {
      await apiClient.deleteResource(`/api/record-types/${rtId}`)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['record-types', collectionId] })
      showToast(t('success.deleted', { item: t('collections.recordTypes') }), 'success')
      setDeleteRecordTypeDialogOpen(false)
      setRecordTypeToDelete(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // --- Picklist Dependency mutations ---
  const saveDependencyMutation = useMutation({
    mutationFn: async (data: {
      controllingFieldId: string
      dependentFieldId: string
      mapping: Record<string, string[]>
    }) => {
      await apiClient.putResource('/api/picklist-dependencies', data)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['picklist-dependencies', collectionId],
      })
      showToast(t('picklistDependencies.saved'), 'success')
      setDependencyEditorOpen(false)
      setEditingDependency(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  const deleteDependencyMutation = useMutation({
    mutationFn: async (dep: PicklistDependency) => {
      await apiClient.deleteResource(
        `/api/picklist-dependencies?filter[controllingFieldId][eq]=${dep.controllingFieldId}&filter[dependentFieldId][eq]=${dep.dependentFieldId}`
      )
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['picklist-dependencies', collectionId],
      })
      showToast(t('picklistDependencies.deleted'), 'success')
      setDeleteDependencyDialogOpen(false)
      setDependencyToDelete(undefined)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Handle edit action
  const handleEdit = useCallback(() => {
    navigate(`/${getTenantSlug()}/collections/${collectionId}/edit`)
  }, [navigate, collectionId])

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

  // Handle back navigation
  const handleBack = useCallback(() => {
    navigate(`/${getTenantSlug()}/collections`)
  }, [navigate])

  // Handle tab change
  const handleTabChange = useCallback(
    (
      tab:
        | 'fields'
        | 'validationRules'
        | 'recordTypes'
        | 'picklistDependencies'
        | 'changeHistory'
        | 'dataSource'
    ) => {
      setActiveTab(tab)
    },
    []
  )

  // Handle add field action
  const handleAddField = useCallback(() => {
    setEditingField(undefined)
    setFieldEditorOpen(true)
  }, [])

  // Handle edit field action
  const handleEditField = useCallback((field: FieldDefinition) => {
    setEditingField(field)
    setFieldEditorOpen(true)
  }, [])

  // Handle field editor save
  const handleFieldSave = useCallback(
    async (fieldData: FieldEditorDefinition) => {
      if (editingField) {
        // Update existing field
        await updateFieldMutation.mutateAsync({
          fieldId: editingField.id,
          fieldData: {
            name: fieldData.name,
            displayName: fieldData.displayName,
            type: fieldData.type,
            required: fieldData.required,
            unique: fieldData.unique,
            indexed: fieldData.indexed,
            defaultValue: fieldData.defaultValue,
            referenceTarget: fieldData.referenceTarget,
            fieldTypeConfig: fieldData.fieldTypeConfig,
            validation: fieldData.validation,
            description: fieldData.description,
            trackHistory: fieldData.trackHistory,
            searchable: fieldData.searchable,
            constraints: fieldData.constraints,
          },
        })
      } else {
        // Create new field
        await addFieldMutation.mutateAsync({
          name: fieldData.name,
          displayName: fieldData.displayName,
          type: fieldData.type,
          required: fieldData.required,
          unique: fieldData.unique,
          indexed: fieldData.indexed,
          defaultValue: fieldData.defaultValue,
          referenceTarget: fieldData.referenceTarget,
          fieldTypeConfig: fieldData.fieldTypeConfig,
          validation: fieldData.validation,
          description: fieldData.description,
          trackHistory: fieldData.trackHistory,
          searchable: fieldData.searchable,
          constraints: fieldData.constraints,
        })
      }
    },
    [editingField, addFieldMutation, updateFieldMutation]
  )

  // Handle field editor cancel
  const handleFieldCancel = useCallback(() => {
    setFieldEditorOpen(false)
    setEditingField(undefined)
  }, [])

  // Handle view version action
  const handleViewVersion = useCallback(
    (version: CollectionVersion) => {
      navigate(`/${getTenantSlug()}/collections/${collectionId}/versions/${version.version}`)
    },
    [navigate, collectionId]
  )

  // --- Validation Rule handlers ---
  const handleAddValidationRule = useCallback(() => {
    setEditingValidationRule(undefined)
    setValidationRuleEditorOpen(true)
  }, [])

  const handleEditValidationRule = useCallback((rule: CollectionValidationRule) => {
    setEditingValidationRule(rule)
    setValidationRuleEditorOpen(true)
  }, [])

  const handleValidationRuleSave = useCallback(
    async (data: Record<string, unknown>) => {
      if (editingValidationRule) {
        await updateValidationRuleMutation.mutateAsync({
          ruleId: editingValidationRule.id,
          data,
        })
      } else {
        await createValidationRuleMutation.mutateAsync(data)
      }
    },
    [editingValidationRule, createValidationRuleMutation, updateValidationRuleMutation]
  )

  const handleValidationRuleCancel = useCallback(() => {
    setValidationRuleEditorOpen(false)
    setEditingValidationRule(undefined)
  }, [])

  const handleDeleteValidationRuleClick = useCallback((rule: CollectionValidationRule) => {
    setValidationRuleToDelete(rule)
    setDeleteValidationRuleDialogOpen(true)
  }, [])

  const handleDeleteValidationRuleConfirm = useCallback(() => {
    if (validationRuleToDelete) {
      deleteValidationRuleMutation.mutate(validationRuleToDelete.id)
    }
  }, [validationRuleToDelete, deleteValidationRuleMutation])

  const handleDeleteValidationRuleCancel = useCallback(() => {
    setDeleteValidationRuleDialogOpen(false)
    setValidationRuleToDelete(undefined)
  }, [])

  const handleActivateValidationRule = useCallback(
    (ruleId: string) => {
      activateValidationRuleMutation.mutate(ruleId)
    },
    [activateValidationRuleMutation]
  )

  const handleDeactivateValidationRule = useCallback(
    (ruleId: string) => {
      deactivateValidationRuleMutation.mutate(ruleId)
    },
    [deactivateValidationRuleMutation]
  )

  // --- Record Type handlers ---
  const handleAddRecordType = useCallback(() => {
    setEditingRecordType(undefined)
    setRecordTypeEditorOpen(true)
  }, [])

  const handleEditRecordType = useCallback((rt: RecordType) => {
    setEditingRecordType(rt)
    setRecordTypeEditorOpen(true)
  }, [])

  const handleRecordTypeSave = useCallback(
    async (data: Record<string, unknown>) => {
      if (editingRecordType) {
        await updateRecordTypeMutation.mutateAsync({ rtId: editingRecordType.id, data })
      } else {
        await createRecordTypeMutation.mutateAsync(data)
      }
    },
    [editingRecordType, createRecordTypeMutation, updateRecordTypeMutation]
  )

  const handleRecordTypeCancel = useCallback(() => {
    setRecordTypeEditorOpen(false)
    setEditingRecordType(undefined)
  }, [])

  const handleDeleteRecordTypeClick = useCallback((rt: RecordType) => {
    setRecordTypeToDelete(rt)
    setDeleteRecordTypeDialogOpen(true)
  }, [])

  const handleDeleteRecordTypeConfirm = useCallback(() => {
    if (recordTypeToDelete) {
      deleteRecordTypeMutation.mutate(recordTypeToDelete.id)
    }
  }, [recordTypeToDelete, deleteRecordTypeMutation])

  const handleDeleteRecordTypeCancel = useCallback(() => {
    setDeleteRecordTypeDialogOpen(false)
    setRecordTypeToDelete(undefined)
  }, [])

  const handlePicklistOverrides = useCallback((rt: RecordType) => {
    setPicklistOverrideRecordType(rt)
  }, [])

  // --- Picklist Dependency handlers ---
  const handleAddDependency = useCallback(() => {
    setEditingDependency(undefined)
    setDependencyEditorOpen(true)
  }, [])

  const handleEditDependency = useCallback((dep: PicklistDependency) => {
    setEditingDependency(dep)
    setDependencyEditorOpen(true)
  }, [])

  const handleDependencySave = useCallback(
    async (data: {
      controllingFieldId: string
      dependentFieldId: string
      mapping: Record<string, string[]>
    }) => {
      await saveDependencyMutation.mutateAsync(data)
    },
    [saveDependencyMutation]
  )

  const handleDependencyCancel = useCallback(() => {
    setDependencyEditorOpen(false)
    setEditingDependency(undefined)
  }, [])

  const handleDeleteDependencyClick = useCallback((dep: PicklistDependency) => {
    setDependencyToDelete(dep)
    setDeleteDependencyDialogOpen(true)
  }, [])

  const handleDeleteDependencyConfirm = useCallback(() => {
    if (dependencyToDelete) {
      deleteDependencyMutation.mutate(dependencyToDelete)
    }
  }, [dependencyToDelete, deleteDependencyMutation])

  const handleDeleteDependencyCancel = useCallback(() => {
    setDeleteDependencyDialogOpen(false)
    setDependencyToDelete(undefined)
  }, [])

  // Helper to resolve field name from ID
  const getFieldName = useCallback(
    (fieldIdOrName: string): string => {
      const field =
        collection?.fields?.find((f) => f.id === fieldIdOrName) ||
        collection?.fields?.find((f) => f.name === fieldIdOrName)
      return field?.displayName || field?.name || fieldIdOrName
    },
    [collection]
  )

  // Sort fields by order
  const sortedFields = useMemo(() => {
    if (!collection?.fields) return []
    return [...collection.fields].sort((a, b) => a.order - b.order)
  }, [collection])

  // Drag-and-drop field reordering state
  const dragIndexRef = useRef<number | null>(null)
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null)
  const [localFieldOrder, setLocalFieldOrder] = useState<FieldDefinition[] | null>(null)

  // The display list: use optimistic local order while dragging, otherwise sorted from server
  const displayFields = localFieldOrder ?? sortedFields

  const reorderFieldsMutation = useMutation({
    mutationFn: async (fieldIds: string[]) => {
      await apiClient.putResource(`/api/collections/${collectionId}/fields/reorder`, {
        fieldIds,
      })
    },
    onSuccess: () => {
      refetchCollection()
      invalidateCollectionCaches()
      setLocalFieldOrder(null)
    },
    onError: () => {
      showToast(t('errors.generic'), 'error')
      setLocalFieldOrder(null)
    },
  })

  const handleDragStart = useCallback((index: number) => {
    dragIndexRef.current = index
  }, [])

  const handleDragOver = useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault()
    if (dragIndexRef.current === null || dragIndexRef.current === index) return
    setDragOverIndex(index)
  }, [])

  const handleDragEnd = useCallback(() => {
    dragIndexRef.current = null
    setDragOverIndex(null)
  }, [])

  const handleDrop = useCallback(
    (e: React.DragEvent, dropIndex: number) => {
      e.preventDefault()
      const fromIndex = dragIndexRef.current
      if (fromIndex === null || fromIndex === dropIndex) {
        handleDragEnd()
        return
      }

      // Compute new order optimistically
      const newOrder = [...displayFields]
      const [moved] = newOrder.splice(fromIndex, 1)
      newOrder.splice(dropIndex, 0, moved)

      setLocalFieldOrder(newOrder)
      handleDragEnd()

      // Persist to backend
      reorderFieldsMutation.mutate(newOrder.map((f) => f.id))
    },
    [displayFields, handleDragEnd, reorderFieldsMutation]
  )

  // Render loading state
  if (isLoadingCollection) {
    return (
      <div
        className="flex flex-col gap-6 p-6 w-full max-md:p-4 max-sm:gap-4 max-sm:p-2"
        data-testid={testId}
      >
        <div className="flex justify-center items-center min-h-[200px]">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Render error state
  if (collectionError) {
    return (
      <div
        className="flex flex-col gap-6 p-6 w-full max-md:p-4 max-sm:gap-4 max-sm:p-2"
        data-testid={testId}
      >
        <ErrorMessage
          error={
            collectionError instanceof Error ? collectionError : new Error(t('errors.generic'))
          }
          onRetry={() => refetchCollection()}
        />
      </div>
    )
  }

  // Render not found state
  if (!collection) {
    return (
      <div
        className="flex flex-col gap-6 p-6 w-full max-md:p-4 max-sm:gap-4 max-sm:p-2"
        data-testid={testId}
      >
        <ErrorMessage error={new Error(t('errors.notFound'))} type="notFound" />
      </div>
    )
  }

  return (
    <div
      className="flex flex-col gap-6 p-6 w-full max-md:p-4 max-sm:gap-4 max-sm:p-2"
      data-testid={testId}
    >
      {/* Page Header */}
      <header className="flex justify-between items-start flex-wrap gap-4 max-sm:flex-col max-sm:items-stretch">
        <div className="flex flex-col gap-2">
          <button
            type="button"
            className="inline-flex items-center py-1 text-sm font-medium text-primary bg-transparent border-none cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:text-primary/80 hover:underline focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={handleBack}
            aria-label={t('common.back')}
            data-testid="back-button"
          >
            ← {t('common.back')}
          </button>
          <div className="flex items-center gap-4 flex-wrap">
            <h1
              className="m-0 text-2xl font-semibold text-foreground max-sm:text-xl"
              data-testid="collection-title"
            >
              {collection.displayName || collection.name}
            </h1>
            <span
              className={cn(
                'inline-flex items-center px-2 py-1 text-xs font-medium rounded-full capitalize',
                collection.active
                  ? 'text-green-800 bg-green-100 dark:text-green-300 dark:bg-green-900'
                  : 'text-yellow-800 bg-yellow-100 dark:text-yellow-300 dark:bg-yellow-900'
              )}
              data-testid="collection-status"
            >
              {collection.active ? t('collections.active') : t('collections.inactive')}
            </span>
          </div>
        </div>
        <div className="flex gap-2 max-sm:flex-col">
          <button
            type="button"
            className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-sm:w-full"
            onClick={handleEdit}
            aria-label={t('collections.editCollection')}
            data-testid="edit-button"
          >
            {t('common.edit')}
          </button>
          <button
            type="button"
            className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-destructive bg-background border border-destructive/30 rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-destructive/5 hover:border-destructive focus:outline-2 focus:outline-destructive focus:outline-offset-2 max-sm:w-full"
            onClick={handleDeleteClick}
            aria-label={t('collections.deleteCollection')}
            data-testid="delete-button"
          >
            {t('common.delete')}
          </button>
        </div>
      </header>

      {/* Collection Metadata */}
      <section className="p-6 bg-muted rounded-md" aria-labelledby="metadata-heading">
        <h2 id="metadata-heading" className="m-0 mb-4 text-lg font-semibold text-foreground">
          {t('collections.collectionName')}
        </h2>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] gap-4 max-lg:grid-cols-2 max-sm:grid-cols-1">
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-muted-foreground">
              {t('collections.collectionName')}
            </span>
            <span className="text-base text-foreground break-words" data-testid="collection-name">
              {collection.name}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-muted-foreground">
              {t('collections.displayName')}
            </span>
            <span
              className="text-base text-foreground break-words"
              data-testid="collection-display-name"
            >
              {collection.displayName || '-'}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-muted-foreground">
              {t('collections.status')}
            </span>
            <span
              className="text-base text-foreground break-words"
              data-testid="collection-status-value"
            >
              {collection.active ? t('collections.active') : t('collections.inactive')}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-muted-foreground">Version</span>
            <span
              className="text-base text-foreground break-words"
              data-testid="collection-version"
            >
              {collection.currentVersion}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-muted-foreground">Created</span>
            <span
              className="text-base text-foreground break-words"
              data-testid="collection-created"
            >
              {formatDate(new Date(collection.createdAt), {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-muted-foreground">Updated</span>
            <span
              className="text-base text-foreground break-words"
              data-testid="collection-updated"
            >
              {formatDate(new Date(collection.updatedAt), {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
          </div>
          {collection.description && (
            <div className="flex flex-col gap-1 col-span-full">
              <span className="text-sm font-medium text-muted-foreground">
                {t('collections.description')}
              </span>
              <span
                className="text-base text-foreground break-words"
                data-testid="collection-description"
              >
                {collection.description}
              </span>
            </div>
          )}
        </div>
      </section>

      {/* Tabs Navigation */}
      <div
        className="flex gap-1 border-b-2 border-border max-sm:overflow-x-auto max-sm:[-webkit-overflow-scrolling:touch]"
        role="tablist"
        aria-label="Collection sections"
      >
        {(
          [
            {
              key: 'fields',
              label: `${t('collections.fields')} (${sortedFields.length})`,
              id: 'fields',
            },
            {
              key: 'validationRules',
              label: t('collections.validationRules'),
              id: 'validation-rules',
            },
            { key: 'recordTypes', label: t('collections.recordTypes'), id: 'record-types' },
            {
              key: 'picklistDependencies',
              label: t('picklistDependencies.title'),
              id: 'picklist-dependencies',
            },
            { key: 'changeHistory', label: t('collections.changeHistory'), id: 'change-history' },
            { key: 'dataSource', label: t('collections.dataSource'), id: 'data-source' },
          ] as const
        )
          // Data Source (external connectors) only applies to user collections.
          .filter(
            (tab) =>
              tab.key !== 'dataSource' ||
              !(collection as { systemCollection?: boolean } | undefined)?.systemCollection
          )
          .map((tab) => (
            <button
              key={tab.key}
              type="button"
              role="tab"
              className={cn(
                'px-4 py-2 text-sm font-medium text-muted-foreground bg-transparent border-none border-b-2 border-transparent -mb-[2px] cursor-pointer transition-colors duration-200 motion-reduce:transition-none whitespace-nowrap',
                'hover:text-foreground focus:outline-2 focus:outline-primary focus:-outline-offset-2',
                activeTab === tab.key && 'text-primary border-b-primary'
              )}
              onClick={() => handleTabChange(tab.key)}
              aria-selected={activeTab === tab.key}
              aria-controls={`${tab.id}-panel`}
              id={`${tab.id}-tab`}
              data-testid={`${tab.id}-tab`}
            >
              {tab.label}
            </button>
          ))}
      </div>

      {/* Fields Panel */}
      {activeTab === 'fields' && (
        <section
          id="fields-panel"
          role="tabpanel"
          aria-labelledby="fields-tab"
          className="py-6"
          data-testid="fields-panel"
        >
          <div className="flex justify-between items-center mb-4 max-sm:flex-col max-sm:items-stretch max-sm:gap-2">
            <h2 className="m-0 text-lg font-semibold text-foreground">{t('collections.fields')}</h2>
            <button
              type="button"
              className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-sm:w-full"
              onClick={handleAddField}
              aria-label={t('collections.addField')}
              data-testid="add-field-button"
            >
              {t('collections.addField')}
            </button>
          </div>
          {displayFields.length === 0 ? (
            <div
              className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="fields-empty-state"
            >
              <p className="m-0 text-base">{t('common.noData')}</p>
              <button
                type="button"
                className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                onClick={handleAddField}
              >
                {t('collections.addField')}
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto border border-border rounded-md bg-card">
              <table
                className="w-full border-collapse text-sm"
                aria-label={t('collections.fields')}
                data-testid="fields-table"
              >
                <thead className="bg-muted">
                  <tr>
                    <th scope="col" className="w-8 !p-0"></th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.fieldName')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.displayName')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.fieldType')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.description')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('fields.validation.required')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('fields.validation.unique')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('fields.validation.indexed')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('fieldEditor.trackHistory')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('fields.relationship')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.actions')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {displayFields.map((field, index) => (
                    <tr
                      key={field.id}
                      className={cn(
                        'transition-colors duration-150 motion-reduce:transition-none hover:bg-muted/50',
                        dragOverIndex === index && 'border-t-2 border-t-primary bg-primary/5'
                      )}
                      data-testid={`field-row-${index}`}
                      draggable
                      onDragStart={() => handleDragStart(index)}
                      onDragOver={(e) => handleDragOver(e, index)}
                      onDragEnd={handleDragEnd}
                      onDrop={(e) => handleDrop(e, index)}
                    >
                      <td className="w-8 !p-0 !px-1 text-center cursor-grab active:cursor-grabbing">
                        <span
                          className="text-base text-muted-foreground select-none leading-none group-hover:text-foreground"
                          aria-label="Drag to reorder"
                        >
                          &#x283F;
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 font-medium font-mono max-sm:p-2">
                        {field.name}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        {field.displayName || '-'}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        <span className="inline-flex items-center px-2 py-1 text-xs font-medium text-blue-800 bg-blue-100 rounded dark:text-blue-300 dark:bg-blue-900">
                          {getFieldTypeDisplay(field.type, t)}
                        </span>
                      </td>
                      <td className="p-4 border-b border-border/50 max-w-[200px] text-xs text-muted-foreground max-sm:p-2">
                        {field.description ? (
                          <span title={field.description}>
                            {field.description.length > 50
                              ? `${field.description.substring(0, 50)}...`
                              : field.description}
                          </span>
                        ) : (
                          '-'
                        )}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        <span
                          className={
                            field.required
                              ? 'text-green-800 font-bold dark:text-green-400'
                              : 'text-muted-foreground'
                          }
                        >
                          {field.required ? '\u2713' : '\u2715'}
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        <span
                          className={
                            field.unique
                              ? 'text-green-800 font-bold dark:text-green-400'
                              : 'text-muted-foreground'
                          }
                        >
                          {field.unique ? '\u2713' : '\u2715'}
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        <span
                          className={
                            field.indexed
                              ? 'text-green-800 font-bold dark:text-green-400'
                              : 'text-muted-foreground'
                          }
                        >
                          {field.indexed ? '\u2713' : '\u2715'}
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        <span
                          className={
                            field.trackHistory
                              ? 'text-green-800 font-bold dark:text-green-400'
                              : 'text-muted-foreground'
                          }
                        >
                          {field.trackHistory ? '\u2713' : '\u2715'}
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 max-sm:p-2">
                        {field.relationshipType ? (
                          <span
                            className="inline-flex items-center px-2 py-1 text-xs font-medium text-purple-600 bg-purple-100 rounded whitespace-nowrap dark:text-purple-300 dark:bg-purple-900"
                            title={field.referenceTarget || ''}
                          >
                            {field.relationshipType === 'MASTER_DETAIL'
                              ? 'Master-Detail'
                              : 'Lookup'}
                            {field.relationshipName ? ` \u2192 ${field.relationshipName}` : ''}
                          </span>
                        ) : (
                          '-'
                        )}
                      </td>
                      <td className="p-4 border-b border-border/50 w-[1%] whitespace-nowrap max-sm:p-2">
                        <button
                          type="button"
                          className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                          onClick={() => handleEditField(field)}
                          aria-label={`${t('common.edit')} ${field.name}`}
                          data-testid={`edit-field-button-${index}`}
                        >
                          {t('common.edit')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {/* Validation Rules Panel */}
      {activeTab === 'validationRules' && (
        <section
          id="validation-rules-panel"
          role="tabpanel"
          aria-labelledby="validation-rules-tab"
          className="py-6"
          data-testid="validation-rules-panel"
        >
          <div className="flex justify-between items-center mb-4 max-sm:flex-col max-sm:items-stretch max-sm:gap-2">
            <h2 className="m-0 text-lg font-semibold text-foreground">
              {t('collections.validationRules')}
            </h2>
            <button
              type="button"
              className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-sm:w-full"
              onClick={handleAddValidationRule}
              aria-label={t('validationRules.addRule')}
              data-testid="add-validation-rule-button"
            >
              {t('validationRules.addRule')}
            </button>
          </div>
          {isLoadingRules ? (
            <div className="flex justify-center items-center min-h-[200px]">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : validationRules.length === 0 ? (
            <div
              className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="validation-rules-empty"
            >
              <p className="m-0 text-base">{t('common.noData')}</p>
              <button
                type="button"
                className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                onClick={handleAddValidationRule}
              >
                {t('validationRules.addRule')}
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto border border-border rounded-md bg-card">
              <table
                className="w-full border-collapse text-sm"
                aria-label={t('collections.validationRules')}
                data-testid="validation-rules-table"
              >
                <thead className="bg-muted">
                  <tr>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.name')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('validationRules.formula')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('validationRules.errorMessage')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('validationRules.evaluateOn')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.status')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.actions')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {validationRules.map((rule, index) => (
                    <tr key={rule.id} data-testid={`validation-rule-row-${index}`}>
                      <td className="p-4 text-foreground border-b border-border/50 font-medium font-mono">
                        {rule.name}
                      </td>
                      <td className="p-4 border-b border-border/50">
                        <code className="inline-block max-w-[300px] px-2 py-1 font-mono text-xs text-foreground bg-muted rounded overflow-hidden text-ellipsis whitespace-nowrap">
                          {rule.errorConditionFormula}
                        </code>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {rule.errorMessage}
                      </td>
                      <td className="p-4 border-b border-border/50">
                        <span className="inline-flex items-center px-2 py-1 text-xs font-medium text-amber-800 bg-amber-100 rounded dark:text-amber-300 dark:bg-amber-900">
                          {rule.evaluateOn}
                        </span>
                      </td>
                      <td className="p-4 border-b border-border/50">
                        <span
                          className={cn(
                            'inline-flex items-center px-2 py-1 text-xs font-medium rounded-full capitalize',
                            rule.active
                              ? 'text-green-800 bg-green-100 dark:text-green-300 dark:bg-green-900'
                              : 'text-yellow-800 bg-yellow-100 dark:text-yellow-300 dark:bg-yellow-900'
                          )}
                        >
                          {rule.active ? t('collections.active') : t('collections.inactive')}
                        </span>
                      </td>
                      <td className="p-4 border-b border-border/50 w-[1%] whitespace-nowrap">
                        <div className="flex gap-1 flex-nowrap">
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                            onClick={() => handleEditValidationRule(rule)}
                            aria-label={`${t('common.edit')} ${rule.name}`}
                            data-testid={`edit-validation-rule-${index}`}
                          >
                            {t('common.edit')}
                          </button>
                          {rule.active ? (
                            <button
                              type="button"
                              className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                              onClick={() => handleDeactivateValidationRule(rule.id)}
                              aria-label={`${t('common.deactivate')} ${rule.name}`}
                              data-testid={`deactivate-validation-rule-${index}`}
                            >
                              {t('common.deactivate')}
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                              onClick={() => handleActivateValidationRule(rule.id)}
                              aria-label={`${t('common.activate')} ${rule.name}`}
                              data-testid={`activate-validation-rule-${index}`}
                            >
                              {t('common.activate')}
                            </button>
                          )}
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-destructive bg-background border border-destructive/30 rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-destructive/5 hover:border-destructive focus:outline-2 focus:outline-destructive focus:outline-offset-2"
                            onClick={() => handleDeleteValidationRuleClick(rule)}
                            aria-label={`${t('common.delete')} ${rule.name}`}
                            data-testid={`delete-validation-rule-${index}`}
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
          )}
        </section>
      )}

      {/* Record Types Panel */}
      {activeTab === 'recordTypes' && (
        <section
          id="record-types-panel"
          role="tabpanel"
          aria-labelledby="record-types-tab"
          className="py-6"
          data-testid="record-types-panel"
        >
          <div className="flex justify-between items-center mb-4 max-sm:flex-col max-sm:items-stretch max-sm:gap-2">
            <h2 className="m-0 text-lg font-semibold text-foreground">
              {t('collections.recordTypes')}
            </h2>
            <button
              type="button"
              className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-sm:w-full"
              onClick={handleAddRecordType}
              aria-label={t('recordTypes.addRecordType')}
              data-testid="add-record-type-button"
            >
              {t('recordTypes.addRecordType')}
            </button>
          </div>
          {isLoadingRecordTypes ? (
            <div className="flex justify-center items-center min-h-[200px]">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : recordTypes.length === 0 ? (
            <div
              className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="record-types-empty"
            >
              <p className="m-0 text-base">{t('common.noData')}</p>
              <button
                type="button"
                className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                onClick={handleAddRecordType}
              >
                {t('recordTypes.addRecordType')}
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto border border-border rounded-md bg-card">
              <table
                className="w-full border-collapse text-sm"
                aria-label={t('collections.recordTypes')}
                data-testid="record-types-table"
              >
                <thead className="bg-muted">
                  <tr>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.name')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.description')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('recordTypes.default')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('collections.status')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.actions')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {recordTypes.map((rt, index) => (
                    <tr key={rt.id} data-testid={`record-type-row-${index}`}>
                      <td className="p-4 text-foreground border-b border-border/50 font-medium font-mono">
                        {rt.name}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {rt.description || '-'}
                      </td>
                      <td className="p-4 border-b border-border/50">
                        {rt.isDefault && (
                          <span className="inline-flex items-center px-2 py-1 text-xs font-medium text-emerald-800 bg-emerald-100 rounded dark:text-emerald-300 dark:bg-emerald-900">
                            {t('recordTypes.default')}
                          </span>
                        )}
                      </td>
                      <td className="p-4 border-b border-border/50">
                        <span
                          className={cn(
                            'inline-flex items-center px-2 py-1 text-xs font-medium rounded-full capitalize',
                            rt.isActive
                              ? 'text-green-800 bg-green-100 dark:text-green-300 dark:bg-green-900'
                              : 'text-yellow-800 bg-yellow-100 dark:text-yellow-300 dark:bg-yellow-900'
                          )}
                        >
                          {rt.isActive ? t('collections.active') : t('collections.inactive')}
                        </span>
                      </td>
                      <td className="p-4 border-b border-border/50 w-[1%] whitespace-nowrap">
                        <div className="flex gap-1 flex-nowrap">
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                            onClick={() => handleEditRecordType(rt)}
                            aria-label={`${t('common.edit')} ${rt.name}`}
                            data-testid={`edit-record-type-${index}`}
                          >
                            {t('common.edit')}
                          </button>
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                            onClick={() => handlePicklistOverrides(rt)}
                            aria-label={`${t('recordTypes.picklists')} ${rt.name}`}
                            data-testid={`picklist-overrides-${index}`}
                          >
                            {t('recordTypes.picklists')}
                          </button>
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-destructive bg-background border border-destructive/30 rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-destructive/5 hover:border-destructive focus:outline-2 focus:outline-destructive focus:outline-offset-2"
                            onClick={() => handleDeleteRecordTypeClick(rt)}
                            aria-label={`${t('common.delete')} ${rt.name}`}
                            data-testid={`delete-record-type-${index}`}
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
          )}
        </section>
      )}

      {/* Picklist Dependencies Panel */}
      {activeTab === 'picklistDependencies' && (
        <section
          id="picklist-dependencies-panel"
          role="tabpanel"
          aria-labelledby="picklist-dependencies-tab"
          className="py-6"
          data-testid="picklist-dependencies-panel"
        >
          <div className="flex justify-between items-center mb-4 max-sm:flex-col max-sm:items-stretch max-sm:gap-2">
            <h2 className="m-0 text-lg font-semibold text-foreground">
              {t('picklistDependencies.title')}
            </h2>
            {picklistFieldIds.length >= 2 && (
              <button
                type="button"
                className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-sm:w-full"
                onClick={handleAddDependency}
                aria-label={t('picklistDependencies.addDependency')}
                data-testid="add-dependency-button"
              >
                {t('picklistDependencies.addDependency')}
              </button>
            )}
          </div>
          {picklistFieldIds.length < 2 ? (
            <div
              className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="dependencies-no-fields"
            >
              <p className="m-0 text-base">
                {picklistFieldIds.length === 0
                  ? t('picklistDependencies.noPicklistFields')
                  : t('picklistDependencies.needTwoPicklistFields')}
              </p>
            </div>
          ) : isLoadingDependencies ? (
            <div className="flex justify-center items-center min-h-[200px]">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : allDependencies.length === 0 ? (
            <div
              className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="dependencies-empty"
            >
              <p className="m-0 text-base">{t('picklistDependencies.noDependencies')}</p>
              <button
                type="button"
                className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 motion-reduce:transition-none hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                onClick={handleAddDependency}
              >
                {t('picklistDependencies.addDependency')}
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto border border-border rounded-md bg-card">
              <table
                className="w-full border-collapse text-sm"
                aria-label={t('picklistDependencies.title')}
                data-testid="dependencies-table"
              >
                <thead className="bg-muted">
                  <tr>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('picklistDependencies.controllingField')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('picklistDependencies.dependentField')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('picklistDependencies.mapping')}
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.actions')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {allDependencies.map((dep, index) => (
                    <tr key={dep.id} data-testid={`dependency-row-${index}`}>
                      <td className="p-4 text-foreground border-b border-border/50 font-medium font-mono">
                        {getFieldName(dep.controllingFieldId)}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50 font-medium font-mono">
                        {getFieldName(dep.dependentFieldId)}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {Object.keys(dep.mapping).length} mapping
                        {Object.keys(dep.mapping).length !== 1 ? 's' : ''}
                      </td>
                      <td className="p-4 border-b border-border/50 w-[1%] whitespace-nowrap">
                        <div className="flex gap-1 flex-nowrap">
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                            onClick={() => handleEditDependency(dep)}
                            aria-label={`${t('common.edit')} dependency`}
                            data-testid={`edit-dependency-${index}`}
                          >
                            {t('common.edit')}
                          </button>
                          <button
                            type="button"
                            className="px-2 py-1 text-xs font-medium text-destructive bg-background border border-destructive/30 rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-destructive/5 hover:border-destructive focus:outline-2 focus:outline-destructive focus:outline-offset-2"
                            onClick={() => handleDeleteDependencyClick(dep)}
                            aria-label={`${t('common.delete')} dependency`}
                            data-testid={`delete-dependency-${index}`}
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
          )}
        </section>
      )}

      {/* Change History Panel: schema admin changes + version snapshots.
          Record-level field-value history lives on the record (App UI timeline),
          not here — this tab is about how the collection's schema evolved. */}
      {activeTab === 'changeHistory' && (
        <section
          id="change-history-panel"
          role="tabpanel"
          aria-labelledby="change-history-tab"
          className="py-6"
          data-testid="change-history-panel"
        >
          <div className="flex justify-between items-center mb-4">
            <h2 className="m-0 text-lg font-semibold text-foreground">
              {t('collections.changeHistory')}
            </h2>
          </div>

          {/* Schema changes (setup audit trail) */}
          <h3 className="text-base font-semibold text-foreground mb-1">{t('setupAudit.title')}</h3>
          <p className="m-0 mb-4 text-sm text-muted-foreground">{t('setupAudit.description')}</p>
          {isLoadingAudit ? (
            <LoadingSpinner />
          ) : setupAuditPage?.content && setupAuditPage.content.length > 0 ? (
            <div className="overflow-x-auto border border-border rounded-md bg-card">
              <table
                className="w-full border-collapse text-sm"
                aria-label={t('collections.setupAudit')}
              >
                <thead className="bg-muted">
                  <tr>
                    <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
                      {t('setupAudit.action')}
                    </th>
                    <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
                      {t('setupAudit.section')}
                    </th>
                    <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
                      {t('setupAudit.entityType')}
                    </th>
                    <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
                      {t('setupAudit.entityName')}
                    </th>
                    <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
                      {t('setupAudit.performedAt')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {setupAuditPage.content.map((entry: SetupAuditTrailEntry) => (
                    <tr key={entry.id}>
                      <td className="p-4 border-b border-border/50">
                        <span
                          className={cn(
                            'inline-flex items-center px-2 py-1 text-xs font-medium rounded',
                            getActionBadgeClasses(entry.action)
                          )}
                          data-action={entry.action}
                        >
                          {entry.action}
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {entry.section}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {entry.entityType}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {entry.entityName || '-'}
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {new Date(entry.timestamp).toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md">
              <p className="m-0 text-base">{t('setupAudit.empty')}</p>
            </div>
          )}

          {/* Version snapshots */}
          <h3 className="text-base font-semibold text-foreground mt-8 mb-1">
            {t('collections.versionHistory')}
          </h3>
          <p className="m-0 mb-4 text-sm text-muted-foreground">
            {t('collections.versionHistoryDescription')}
          </p>
          {isLoadingVersions ? (
            <div className="flex justify-center items-center min-h-[200px]">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : versionsError ? (
            <ErrorMessage
              error={
                versionsError instanceof Error ? versionsError : new Error(t('errors.generic'))
              }
              variant="compact"
            />
          ) : versions.length === 0 ? (
            <div
              className="flex flex-col items-center justify-center gap-4 p-12 text-center text-muted-foreground bg-muted rounded-md"
              data-testid="versions-empty-state"
            >
              <p className="m-0 text-base">{t('common.noData')}</p>
            </div>
          ) : (
            <div className="overflow-x-auto border border-border rounded-md bg-card">
              <table
                className="w-full border-collapse text-sm"
                aria-label={t('collections.versionHistory')}
                data-testid="versions-table"
              >
                <thead className="bg-muted">
                  <tr>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      Version
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      Created
                    </th>
                    <th
                      scope="col"
                      className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                    >
                      {t('common.actions')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {versions.map((version, index) => (
                    <tr
                      key={version.id}
                      className={cn(
                        'transition-colors duration-150 motion-reduce:transition-none hover:bg-muted/50',
                        version.version === collection.currentVersion &&
                          'bg-blue-50 dark:bg-blue-950'
                      )}
                      data-testid={`version-row-${index}`}
                    >
                      <td className="p-4 text-foreground border-b border-border/50">
                        <span className="inline-flex items-center gap-2 font-medium">
                          v{version.version}
                          {version.version === collection.currentVersion && (
                            <span className="text-xs font-normal text-green-800 dark:text-green-400">
                              (Current)
                            </span>
                          )}
                        </span>
                      </td>
                      <td className="p-4 text-foreground border-b border-border/50">
                        {formatDate(new Date(version.createdAt), {
                          year: 'numeric',
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                        })}
                      </td>
                      <td className="p-4 border-b border-border/50 w-[1%] whitespace-nowrap">
                        <button
                          type="button"
                          className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-muted hover:border-border/80 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                          onClick={() => handleViewVersion(version)}
                          aria-label={`View version ${version.version}`}
                          data-testid={`view-version-button-${index}`}
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {/* Data Source Panel (external connectors) */}
      {activeTab === 'dataSource' && collectionId && (
        <DataSourceSettings collectionId={collectionId} />
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

      {/* Validation Rule Delete Confirmation */}
      <ConfirmDialog
        open={deleteValidationRuleDialogOpen}
        title={t('validationRules.deleteRule')}
        message={t('validationRules.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteValidationRuleConfirm}
        onCancel={handleDeleteValidationRuleCancel}
        variant="danger"
      />

      {/* Record Type Delete Confirmation */}
      <ConfirmDialog
        open={deleteRecordTypeDialogOpen}
        title={t('recordTypes.deleteRecordType')}
        message={t('recordTypes.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteRecordTypeConfirm}
        onCancel={handleDeleteRecordTypeCancel}
        variant="danger"
      />

      {/* Field Editor Modal */}
      {fieldEditorOpen && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4 overflow-y-auto max-sm:p-0 max-sm:items-end"
          role="presentation"
          onMouseDown={handleFieldCancel}
        >
          {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions */}
          <div
            className="bg-card rounded-lg shadow-xl max-w-[600px] w-full max-h-[90vh] overflow-y-auto p-6 dark:shadow-2xl max-sm:max-w-full max-sm:max-h-[95vh] max-sm:rounded-b-none"
            onClick={(e) => e.stopPropagation()}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <FieldEditor
              collectionId={collectionId}
              collectionName={collection?.name}
              field={editingField}
              collections={allCollectionSummaries.map((c) => ({
                id: c.id,
                name: c.name,
                displayName: c.displayName || c.name,
              }))}
              picklists={globalPicklists.map((p) => ({
                id: p.id,
                name: p.name,
              }))}
              picklistsLoading={globalPicklistsLoading}
              picklistsError={globalPicklistsError}
              onRetryPicklists={() => {
                void refetchGlobalPicklists()
              }}
              fetchCollectionFields={fetchChildCollectionFields}
              onSave={handleFieldSave}
              onCancel={handleFieldCancel}
              isSubmitting={addFieldMutation.isPending || updateFieldMutation.isPending}
            />
          </div>
        </div>
      )}

      {/* Validation Rule Editor Modal */}
      {validationRuleEditorOpen && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4 overflow-y-auto max-sm:p-0 max-sm:items-end"
          role="presentation"
          onMouseDown={handleValidationRuleCancel}
        >
          {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions */}
          <div
            className="bg-card rounded-lg shadow-xl max-w-[600px] w-full max-h-[90vh] overflow-y-auto p-6 dark:shadow-2xl max-sm:max-w-full max-sm:max-h-[95vh] max-sm:rounded-b-none"
            onClick={(e) => e.stopPropagation()}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ValidationRuleEditor
              collectionId={collectionId}
              rule={editingValidationRule}
              onSave={handleValidationRuleSave}
              onCancel={handleValidationRuleCancel}
              isSubmitting={
                createValidationRuleMutation.isPending || updateValidationRuleMutation.isPending
              }
            />
          </div>
        </div>
      )}

      {/* Picklist Dependency Delete Confirmation */}
      <ConfirmDialog
        open={deleteDependencyDialogOpen}
        title={t('picklistDependencies.deleteDependency')}
        message={t('picklistDependencies.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteDependencyConfirm}
        onCancel={handleDeleteDependencyCancel}
        variant="danger"
      />

      {/* Picklist Dependency Editor Modal */}
      {dependencyEditorOpen && (
        <PicklistDependencyEditor
          collectionId={collectionId}
          fields={sortedFields}
          dependency={editingDependency}
          onSave={handleDependencySave}
          onCancel={handleDependencyCancel}
          isSubmitting={saveDependencyMutation.isPending}
        />
      )}

      {/* Record Type Editor Modal */}
      {recordTypeEditorOpen && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4 overflow-y-auto max-sm:p-0 max-sm:items-end"
          role="presentation"
          onMouseDown={handleRecordTypeCancel}
        >
          {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions */}
          <div
            className="bg-card rounded-lg shadow-xl max-w-[600px] w-full max-h-[90vh] overflow-y-auto p-6 dark:shadow-2xl max-sm:max-w-full max-sm:max-h-[95vh] max-sm:rounded-b-none"
            onClick={(e) => e.stopPropagation()}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <RecordTypeEditor
              collectionId={collectionId}
              recordType={editingRecordType}
              onSave={handleRecordTypeSave}
              onCancel={handleRecordTypeCancel}
              isSubmitting={
                createRecordTypeMutation.isPending || updateRecordTypeMutation.isPending
              }
            />
          </div>
        </div>
      )}

      {/* Record Type Picklist Override Editor Modal */}
      {picklistOverrideRecordType && (
        <RecordTypePicklistEditor
          recordType={picklistOverrideRecordType}
          fields={sortedFields}
          onClose={() => setPicklistOverrideRecordType(undefined)}
          onSaved={() => {
            queryClient.invalidateQueries({ queryKey: ['record-types', collectionId] })
            setPicklistOverrideRecordType(undefined)
          }}
        />
      )}
    </div>
  )
}

export default CollectionDetailPage
