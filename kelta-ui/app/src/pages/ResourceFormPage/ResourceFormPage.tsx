/**
 * ResourceFormPage Component
 *
 * Generates a form from collection schema for creating and editing resources.
 * Supports different field types and handles form validation.
 * Integrates with the plugin system to use custom field renderers when available.
 *
 * Requirements:
 * - 11.6: Resource browser allows creating new resources
 * - 11.9: Resource browser allows editing existing resources
 * - 12.4: Use custom field renderers when registered, fall back to defaults
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { useParams, useNavigate, useSearchParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { usePlugins } from '../../context/PluginContext'
import { useApi } from '../../context/ApiContext'
import { useToast, LoadingSpinner, ErrorMessage, LookupSelect } from '../../components'
import type { LookupOption } from '../../components'
import { useAuth } from '../../context/AuthContext'
import { usePageLayout } from '../../hooks/usePageLayout'
import { resolvePicklistSource } from '../../hooks/usePicklistOptions'
import { LayoutFormSections } from '../../components/LayoutFormSections/LayoutFormSections'
import type { LayoutFormFieldDefinition } from '../../components/LayoutFormSections/LayoutFormSections'
import { useLayoutRules } from '@kelta/components'
import { dtosToLayoutRules } from '../../utils/layoutRules'
import { unwrapResource, extractIncluded, wrapResource } from '../../utils/jsonapi'
import { normalizeFieldType } from '../../utils/normalizeFieldType'
import type { FieldDefinition as SchemaFieldDefinition } from '../../hooks/useCollectionSchema'
import { ApiError } from '../../services/apiClient'
import type { ApiClient } from '../../services/apiClient'

/**
 * Field definition for the resource form: the shared runtime-schema shape plus
 * the form-only metadata (constraints, default, ordering, validation) this page reads.
 */
export interface FieldDefinition extends SchemaFieldDefinition {
  unique?: boolean
  indexed?: boolean
  defaultValue?: unknown
  order?: number
  validation?: ValidationRule[]
}

/**
 * Validation rule interface
 */
export interface ValidationRule {
  type: 'min' | 'max' | 'pattern' | 'email' | 'url' | 'custom'
  value?: unknown
  message?: string
}

/**
 * Collection schema interface
 */
export interface CollectionSchema {
  id: string
  name: string
  displayName: string
  description?: string
  displayFieldId?: string
  displayFieldName?: string
  fields: FieldDefinition[]
}

/**
 * Resource record interface
 */
export interface Resource {
  id: string
  [key: string]: unknown
}

/**
 * Form data type
 */
export type FormData = Record<string, unknown>

/**
 * Form errors type
 */
export type FormErrors = Record<string, string>

/**
 * Props for ResourceFormPage component
 */
export interface ResourceFormPageProps {
  /** Collection name from route params (optional, can be from useParams) */
  collectionName?: string
  /** Resource ID from route params (optional, for edit mode) */
  resourceId?: string
  /** Optional test ID for testing */
  testId?: string
}

/** Picklist value returned from the API (field names match backend schema) */
interface PicklistValueDto {
  value: string
  label: string
  isDefault: boolean
  isActive: boolean
  sortOrder: number
}

// API functions using apiClient
async function fetchCollectionSchema(
  apiClient: ApiClient,
  collectionName: string
): Promise<CollectionSchema> {
  // Fetch the collection record with included field definitions in a single request.
  const response = await apiClient.get(
    `/api/collections/${encodeURIComponent(collectionName)}?include=fields`
  )

  // Extract the collection record from the response envelope
  const collection = unwrapResource<Record<string, unknown>>(response)

  // Extract included field records from the JSON:API `included` array
  const fieldRecords = extractIncluded<Record<string, unknown>>(response, 'fields')

  // Resolve displayFieldName from the displayFieldId relationship
  let displayFieldName: string | undefined
  if (collection.displayFieldId) {
    const displayField = fieldRecords.find((f) => f.id === collection.displayFieldId)
    if (displayField) {
      displayFieldName = displayField.name as string
    }
  }

  // Sort by fieldOrder and filter to active fields, then normalize types.
  // Spread all properties to preserve validation rules, constraints, etc.
  const fields: FieldDefinition[] = fieldRecords
    .filter((f) => f.active !== false)
    .sort((a, b) => {
      const orderA = typeof a.fieldOrder === 'number' ? a.fieldOrder : 999
      const orderB = typeof b.fieldOrder === 'number' ? b.fieldOrder : 999
      return orderA - orderB
    })
    .map((f) => ({
      ...(f as unknown as FieldDefinition),
      type: normalizeFieldType(f.type as string),
      required: !!f.required,
      displayName: (f.displayName as string) || undefined,
      referenceTarget: (f.referenceTarget as string) || undefined,
      referenceCollectionId: (f.referenceCollectionId as string) || undefined,
    }))

  return {
    id: collection.id as string,
    name: collection.name as string,
    displayName: (collection.displayName as string) || (collection.name as string),
    description: (collection.description as string) || undefined,
    displayFieldId: (collection.displayFieldId as string) || undefined,
    displayFieldName,
    fields,
  }
}

async function fetchResource(
  apiClient: ApiClient,
  collectionName: string,
  resourceId: string
): Promise<Resource> {
  const response = await apiClient.get(`/api/${collectionName}/${resourceId}`)
  return unwrapResource<Resource>(response)
}

async function createResource(
  apiClient: ApiClient,
  collectionName: string,
  data: FormData,
  relationshipFields?: Record<string, string>
): Promise<Resource> {
  const body = wrapResource(collectionName, data, undefined, relationshipFields)
  const response = await apiClient.post(`/api/${collectionName}`, body)
  return unwrapResource<Resource>(response)
}

async function updateResource(
  apiClient: ApiClient,
  collectionName: string,
  resourceId: string,
  data: FormData,
  relationshipFields?: Record<string, string>
): Promise<Resource> {
  const body = wrapResource(collectionName, data, resourceId, relationshipFields)
  const response = await apiClient.patch(`/api/${collectionName}/${resourceId}`, body)
  return unwrapResource<Resource>(response)
}

/**
 * Get default value for a field based on its type
 */
function getDefaultValueForType(field: FieldDefinition): unknown {
  if (field.defaultValue !== undefined) {
    return field.defaultValue
  }

  switch (field.type) {
    case 'string':
      return ''
    case 'number':
      return ''
    case 'boolean':
      return false
    case 'date':
    case 'datetime':
      return ''
    case 'json':
      return ''
    case 'master_detail':
      return ''
    default:
      return ''
  }
}

/**
 * Initialize form data from schema with default values
 */
function initializeFormData(schema: CollectionSchema): FormData {
  const data: FormData = {}
  const fields = Array.isArray(schema.fields) ? schema.fields : []
  fields.forEach((field) => {
    data[field.name] = getDefaultValueForType(field)
  })
  return data
}

/**
 * Populate form data from existing resource
 */
function populateFormData(schema: CollectionSchema, resource: Resource): FormData {
  const data: FormData = {}
  const fields = Array.isArray(schema.fields) ? schema.fields : []
  fields.forEach((field) => {
    const value = resource[field.name]
    if (value !== undefined && value !== null) {
      // Format dates for input fields
      if (field.type === 'date' && typeof value === 'string') {
        data[field.name] = value.split('T')[0]
      } else if (field.type === 'datetime' && typeof value === 'string') {
        // Format datetime for datetime-local input
        data[field.name] = value.slice(0, 16)
      } else if (field.type === 'json' && typeof value === 'object') {
        data[field.name] = JSON.stringify(value, null, 2)
      } else {
        data[field.name] = value
      }
    } else {
      data[field.name] = getDefaultValueForType(field)
    }
  })
  return data
}

/**
 * ResourceFormPage Component
 *
 * Main page for creating and editing resources.
 * Generates form fields based on collection schema.
 */
export function ResourceFormPage({
  collectionName: propCollectionName,
  resourceId: propResourceId,
  testId = 'resource-form-page',
}: ResourceFormPageProps): React.ReactElement {
  const { collection: routeCollection, id: routeResourceId } = useParams<{
    collection: string
    id?: string
  }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { showToast } = useToast()

  // Get plugin system for custom field renderers
  // Requirement 12.4: Use registered field renderers when rendering fields
  const { getFieldRenderer } = usePlugins()
  const { user } = useAuth()

  // Get collection name and resource ID from props or route params
  const collectionName = propCollectionName || routeCollection || ''
  const resourceId = propResourceId || routeResourceId
  const isEditMode = !!resourceId

  // Clone mode: detect clone source from URL search params
  const [searchParams] = useSearchParams()
  const cloneSourceId = searchParams.get('clone')
  const isCloneMode = !!cloneSourceId && !!collectionName && !isEditMode

  // Fetch clone source record when cloning
  const { data: cloneSource } = useQuery({
    queryKey: ['clone-source', collectionName, cloneSourceId],
    queryFn: async () => {
      const response = await apiClient.get(`/api/${collectionName}/${cloneSourceId}`)
      return unwrapResource<Resource>(response)
    },
    enabled: isCloneMode,
  })

  // Fetch collection schema
  const {
    data: schema,
    isLoading: schemaLoading,
    error: schemaError,
  } = useQuery({
    queryKey: ['collection-schema', collectionName],
    queryFn: () => fetchCollectionSchema(apiClient, collectionName),
    enabled: !!collectionName,
  })

  // Resolve page layout for this collection (returns null if none configured)
  const { layout, isLoading: layoutLoading } = usePageLayout(schema?.id, user?.id)

  // Convert raw layout-rules DTOs into the typed LayoutRule[] the engine
  // consumes. Memoized on the rules array so the engine memo inside
  // useLayoutRules is stable and doesn't rebuild every render.
  const layoutRules = useMemo(() => dtosToLayoutRules(layout?.rules, ''), [layout?.rules])

  // Mirror collection-wide validation rules with enforce_on_client = true so
  // the form can show inline errors live and block submission for ERROR
  // severity. Server-side enforcement is authoritative — this is a UX layer,
  // not a security boundary. Only `active` rules with `enforceOnClient` are
  // returned by the filter.
  const { data: clientValidationRules = [] } = useQuery({
    queryKey: ['client-validation-rules', schema?.id],
    queryFn: async () => {
      if (!schema?.id) return []
      try {
        return await apiClient.getList<import('@kelta/sdk').CollectionValidationRule>(
          `/api/validation-rules?filter[collectionId][eq]=${schema.id}&filter[active][eq]=true&filter[enforceOnClient][eq]=true&page[size]=100`
        )
      } catch {
        return []
      }
    },
    enabled: !!schema?.id,
    staleTime: 5 * 60 * 1000,
  })

  // Identify picklist fields so we can fetch their values
  const picklistFields = useMemo(() => {
    if (!schema?.fields) return []
    return schema.fields.filter((f) => f.type === 'picklist' || f.type === 'multi_picklist')
  }, [schema])

  // Fetch picklist values for all picklist/multi_picklist fields.
  // We use the field's fieldTypeConfig.globalPicklistId to resolve values,
  // falling back to the field-level picklist values endpoint.
  const { data: picklistValuesMap } = useQuery({
    queryKey: ['picklist-values-for-form', collectionName, picklistFields.map((f) => f.id)],
    queryFn: async () => {
      const map: Record<string, string[]> = {}
      await Promise.all(
        picklistFields.map(async (field) => {
          try {
            // Resolve the picklist source (GLOBAL via fieldTypeConfig — including the
            // legacy pre-#1222 dialect — else field-level values). Shared helper.
            const { sourceId, sourceType } = resolvePicklistSource(field)
            const values = await apiClient.getList<PicklistValueDto>(
              `/api/picklist-values?filter[picklistSourceId][eq]=${encodeURIComponent(sourceId)}&filter[picklistSourceType][eq]=${sourceType}`
            )
            map[field.id] = values
              .filter((v) => v.isActive)
              .sort((a, b) => a.sortOrder - b.sortOrder)
              .map((v) => v.value)
          } catch {
            map[field.id] = []
          }
        })
      )
      return map
    },
    enabled: picklistFields.length > 0,
  })

  // Identify master_detail fields that need dropdown options.
  // Accept fields with either referenceCollectionId (UUID) or referenceTarget (name).
  const lookupFields = useMemo(() => {
    if (!schema?.fields) return []
    return schema.fields.filter(
      (f) => f.type === 'master_detail' && (f.referenceCollectionId || f.referenceTarget)
    )
  }, [schema])

  // Fetch lookup options for all master_detail fields.
  // For each target collection, fetch its schema (to find display field),
  // then fetch records to build the options list.
  // Also builds a mapping from field name -> target collection name for JSON:API relationships.
  const { data: lookupData } = useQuery({
    queryKey: ['lookup-options-for-form', collectionName, lookupFields.map((f) => f.id)],
    queryFn: async () => {
      const optionsMap: Record<string, LookupOption[]> = {}
      const relFieldsMap: Record<string, string> = {}
      // Group fields by target collection name to minimize API calls.
      // Prefer referenceTarget (collection name) for grouping since we need the
      // name for both schema and records fetches. Fall back to referenceCollectionId.
      const targetMap = new Map<
        string,
        { fields: FieldDefinition[]; targetName?: string; targetId?: string }
      >()
      for (const field of lookupFields) {
        const groupKey = field.referenceTarget || field.referenceCollectionId!
        if (!targetMap.has(groupKey)) {
          targetMap.set(groupKey, {
            fields: [],
            targetName: field.referenceTarget || undefined,
            targetId: field.referenceCollectionId || undefined,
          })
        }
        targetMap.get(groupKey)!.fields.push(field)
      }

      await Promise.all(
        Array.from(targetMap.entries()).map(
          async ([, { fields, targetName: knownName, targetId }]) => {
            try {
              let targetName: string
              let displayFieldName = 'id'

              if (knownName) {
                // We have the collection name — use fetchCollectionSchema for proper
                // display field resolution (resolves displayFieldId → displayFieldName)
                targetName = knownName
                try {
                  const targetSchema = await fetchCollectionSchema(apiClient, knownName)
                  if (targetSchema.displayFieldName) {
                    displayFieldName = targetSchema.displayFieldName
                  } else if (targetSchema.fields?.length) {
                    const nameField = targetSchema.fields.find(
                      (f) => f.name.toLowerCase() === 'name'
                    )
                    if (nameField) {
                      displayFieldName = nameField.name
                    } else {
                      const firstStringField = targetSchema.fields.find((f) => f.type === 'string')
                      if (firstStringField) {
                        displayFieldName = firstStringField.name
                      }
                    }
                  }
                } catch {
                  displayFieldName = 'name'
                }
              } else {
                // Only have UUID — fetch collection record to get its name, then schema
                const collection = await apiClient.getOne<{ name: string }>(
                  `/api/collections/${targetId}`
                )
                targetName = collection.name
                try {
                  const targetSchema = await fetchCollectionSchema(apiClient, targetName)
                  if (targetSchema.displayFieldName) {
                    displayFieldName = targetSchema.displayFieldName
                  } else if (targetSchema.fields?.length) {
                    const nameField = targetSchema.fields.find(
                      (f) => f.name.toLowerCase() === 'name'
                    )
                    if (nameField) {
                      displayFieldName = nameField.name
                    } else {
                      const firstStringField = targetSchema.fields.find((f) => f.type === 'string')
                      if (firstStringField) {
                        displayFieldName = firstStringField.name
                      }
                    }
                  }
                } catch {
                  displayFieldName = 'name'
                }
              }

              // Record the field name -> target collection name mapping
              for (const field of fields) {
                relFieldsMap[field.name] = targetName
              }

              // Fetch records from the target collection
              const recordsResponse = await apiClient.get<Record<string, unknown>>(
                `/api/${targetName}?page[size]=200`
              )

              // Extract records from JSON:API response
              const data = recordsResponse?.data
              const records: Array<Record<string, unknown>> = Array.isArray(data) ? data : []

              // Build options from records
              const options: LookupOption[] = records.map((record: Record<string, unknown>) => {
                const attrs = (record.attributes || record) as Record<string, unknown>
                const id = String(record.id || attrs.id || '')
                const label = attrs[displayFieldName] ? String(attrs[displayFieldName]) : id
                return { id, label }
              })

              // Assign to all fields targeting this collection
              for (const field of fields) {
                optionsMap[field.id] = options
              }
            } catch {
              // If we fail to fetch options, leave the fields empty (graceful degradation)
              for (const field of fields) {
                optionsMap[field.id] = []
              }
            }
          }
        )
      )
      return { optionsMap, relFieldsMap }
    },
    enabled: lookupFields.length > 0,
  })

  const lookupOptionsMap = lookupData?.optionsMap
  // Map from field name -> target collection name for JSON:API relationship serialization
  const relationshipFieldsMap = lookupData?.relFieldsMap

  // Merge picklist values and lookup options into the schema fields
  const schemaWithPicklistValues = useMemo<CollectionSchema | undefined>(() => {
    if (!schema) return undefined
    const hasPicklists = picklistValuesMap && picklistFields.length > 0
    const hasLookups = lookupOptionsMap && lookupFields.length > 0
    if (!hasPicklists && !hasLookups) return schema
    return {
      ...schema,
      fields: schema.fields.map((f) => {
        let updated = f
        if ((f.type === 'picklist' || f.type === 'multi_picklist') && picklistValuesMap?.[f.id]) {
          updated = { ...updated, enumValues: picklistValuesMap[f.id] }
        }
        if (f.type === 'master_detail' && lookupOptionsMap?.[f.id]) {
          updated = { ...updated, lookupOptions: lookupOptionsMap[f.id] }
        }
        return updated
      }),
    }
  }, [schema, picklistValuesMap, picklistFields, lookupOptionsMap, lookupFields])

  // Fetch resource data for edit mode
  const {
    data: resource,
    isLoading: resourceLoading,
    error: resourceError,
  } = useQuery({
    queryKey: ['resource', collectionName, resourceId],
    queryFn: () => fetchResource(apiClient, collectionName, resourceId!),
    enabled: !!collectionName && !!resourceId,
  })

  // Form state
  const [formData, setFormData] = useState<FormData>({})
  const [formErrors, setFormErrors] = useState<FormErrors>({})
  const [isSubmitting, setIsSubmitting] = useState(false)

  // Track the key for which form was initialized
  const currentResourceKey = isEditMode
    ? resourceId
    : isCloneMode
      ? `clone-${cloneSourceId}`
      : 'new'
  const [initializedKey, setInitializedKey] = useState<string | null>(null)

  // Compute initial form data based on schema and resource (or clone source)
  // In edit/clone mode, wait for the resource data before initializing to avoid
  // populating with empty defaults that then block re-initialization.
  // Use schemaWithPicklistValues so picklist defaults are resolved correctly.
  const effectiveSchema = schemaWithPicklistValues
  const computedInitialData = useMemo(() => {
    if (!effectiveSchema) return null
    if (isEditMode) {
      // Wait for resource to load before initializing form
      if (!resource) return null
      return populateFormData(effectiveSchema, resource)
    }
    if (isCloneMode) {
      // Wait for clone source to load before initializing form
      if (!cloneSource) return null
      const cloneData = Object.fromEntries(
        Object.entries(cloneSource).filter(
          ([key]) => key !== 'id' && key !== 'createdAt' && key !== 'updatedAt'
        )
      ) as Resource
      return populateFormData(effectiveSchema, cloneData)
    }
    return initializeFormData(effectiveSchema)
  }, [effectiveSchema, resource, isEditMode, isCloneMode, cloneSource])

  // Initialize form data when data becomes available or resource changes
  if (computedInitialData && initializedKey !== currentResourceKey) {
    setInitializedKey(currentResourceKey ?? null)
    setFormData(computedInitialData)
    setFormErrors({})
  }

  // Sort fields by order
  const sortedFields = useMemo(() => {
    if (!effectiveSchema?.fields) return []
    return [...effectiveSchema.fields].sort((a, b) => {
      const orderA = a.order ?? 0
      const orderB = b.order ?? 0
      return orderA - orderB
    })
  }, [effectiveSchema])

  // Create mutation
  const createMutation = useMutation({
    mutationFn: (data: FormData) =>
      createResource(apiClient, collectionName, data, relationshipFieldsMap),
    onSuccess: (newResource) => {
      queryClient.invalidateQueries({ queryKey: ['resources', collectionName] })
      showToast(t('success.created', { item: t('resources.record') }), 'success')
      navigate(`/${getTenantSlug()}/resources/${collectionName}/${newResource.id}`)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        const serverErrors: FormErrors = {}
        for (const fe of error.fieldErrors) {
          serverErrors[fe.field] = fe.message
        }
        setFormErrors((prev) => ({ ...prev, ...serverErrors }))
      }
      setIsSubmitting(false)
    },
  })

  // Update mutation
  const updateMutation = useMutation({
    mutationFn: (data: FormData) =>
      updateResource(apiClient, collectionName, resourceId!, data, relationshipFieldsMap),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['resources', collectionName] })
      queryClient.invalidateQueries({ queryKey: ['resource', collectionName, resourceId] })
      showToast(t('success.updated', { item: t('resources.record') }), 'success')
      navigate(`/${getTenantSlug()}/resources/${collectionName}/${resourceId}`)
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        const serverErrors: FormErrors = {}
        for (const fe of error.fieldErrors) {
          serverErrors[fe.field] = fe.message
        }
        setFormErrors((prev) => ({ ...prev, ...serverErrors }))
      }
      setIsSubmitting(false)
    },
  })

  /**
   * Validate a single field value
   */
  const validateField = useCallback(
    (field: FieldDefinition, value: unknown): string | null => {
      // Required validation
      if (field.required) {
        if (value === undefined || value === null || value === '') {
          return t('common.required')
        }
      }

      // Skip further validation if value is empty and not required
      if (value === undefined || value === null || value === '') {
        return null
      }

      // Type-specific validation
      switch (field.type) {
        case 'number': {
          const numValue = Number(value)
          if (isNaN(numValue)) {
            return t('resourceForm.validation.invalidNumber')
          }
          break
        }
        case 'json': {
          if (typeof value === 'string' && value.trim()) {
            try {
              JSON.parse(value)
            } catch {
              return t('resourceForm.validation.invalidJson')
            }
          }
          break
        }
        case 'date':
        case 'datetime': {
          if (typeof value === 'string' && value.trim()) {
            const date = new Date(value)
            if (isNaN(date.getTime())) {
              return t('resourceForm.validation.invalidDate')
            }
          }
          break
        }
        case 'phone': {
          if (typeof value === 'string' && value.trim()) {
            const phoneRegex = /^[+]?[\d\s\-().]*$/
            if (!phoneRegex.test(value)) {
              return t('resourceForm.validation.invalidPhone', 'Invalid phone number format')
            }
          }
          break
        }
        case 'email': {
          if (typeof value === 'string' && value.trim()) {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
            if (!emailRegex.test(value)) {
              return t('resourceForm.validation.email', 'Invalid email address')
            }
          }
          break
        }
        case 'url': {
          if (typeof value === 'string' && value.trim()) {
            try {
              new URL(value)
            } catch {
              return t('resourceForm.validation.url', 'Invalid URL format')
            }
          }
          break
        }
        case 'geolocation': {
          if (typeof value === 'object' && value !== null) {
            const geo = value as Record<string, unknown>
            if (geo.latitude !== null && geo.latitude !== undefined) {
              const lat = Number(geo.latitude)
              if (isNaN(lat) || lat < -90 || lat > 90) {
                return t(
                  'resourceForm.validation.invalidLatitude',
                  'Latitude must be between -90 and 90'
                )
              }
            }
            if (geo.longitude !== null && geo.longitude !== undefined) {
              const lng = Number(geo.longitude)
              if (isNaN(lng) || lng < -180 || lng > 180) {
                return t(
                  'resourceForm.validation.invalidLongitude',
                  'Longitude must be between -180 and 180'
                )
              }
            }
          }
          break
        }
        case 'currency':
        case 'percent': {
          if (value !== '' && value !== undefined && value !== null) {
            const numValue = Number(value)
            if (isNaN(numValue)) {
              return t('resourceForm.validation.invalidNumber')
            }
          }
          break
        }
      }

      // Custom validation rules
      if (field.validation) {
        for (const rule of field.validation) {
          switch (rule.type) {
            case 'min': {
              if (field.type === 'number') {
                const numValue = Number(value)
                if (numValue < Number(rule.value)) {
                  return (
                    rule.message ||
                    t('resourceForm.validation.minValue', { min: String(rule.value) })
                  )
                }
              } else if (field.type === 'string') {
                const strValue = String(value)
                if (strValue.length < Number(rule.value)) {
                  return (
                    rule.message ||
                    t('resourceForm.validation.minLength', { min: String(rule.value) })
                  )
                }
              }
              break
            }
            case 'max': {
              if (field.type === 'number') {
                const numValue = Number(value)
                if (numValue > Number(rule.value)) {
                  return (
                    rule.message ||
                    t('resourceForm.validation.maxValue', { max: String(rule.value) })
                  )
                }
              } else if (field.type === 'string') {
                const strValue = String(value)
                if (strValue.length > Number(rule.value)) {
                  return (
                    rule.message ||
                    t('resourceForm.validation.maxLength', { max: String(rule.value) })
                  )
                }
              }
              break
            }
            case 'pattern': {
              if (typeof value === 'string' && rule.value) {
                const regex = new RegExp(String(rule.value))
                if (!regex.test(value)) {
                  return rule.message || t('resourceForm.validation.pattern')
                }
              }
              break
            }
            case 'email': {
              if (typeof value === 'string') {
                const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
                if (!emailRegex.test(value)) {
                  return rule.message || t('resourceForm.validation.email')
                }
              }
              break
            }
            case 'url': {
              if (typeof value === 'string') {
                try {
                  new URL(value)
                } catch {
                  return rule.message || t('resourceForm.validation.url')
                }
              }
              break
            }
          }
        }
      }

      return null
    },
    [t]
  )

  /**
   * Validate all form fields
   */
  const validateForm = useCallback((): boolean => {
    const errors: FormErrors = {}
    let isValid = true

    sortedFields.forEach((field) => {
      const error = validateField(field, formData[field.name])
      if (error) {
        errors[field.name] = error
        isValid = false
      }
    })

    setFormErrors(errors)
    return isValid
  }, [sortedFields, formData, validateField])

  // Adapter: useLayoutRules speaks a generic FormBinding — wire it to the
  // useState-based form data and error map this page already owns.
  const setFieldValue = useCallback((fieldName: string, value: unknown) => {
    setFormData((prev) => ({ ...prev, [fieldName]: value }))
  }, [])
  const setFieldError = useCallback((fieldName: string, message: string) => {
    setFormErrors((prev) => ({ ...prev, [fieldName]: message }))
  }, [])
  const clearFieldError = useCallback((fieldName: string) => {
    setFormErrors((prev) => {
      if (!(fieldName in prev)) return prev
      const next = { ...prev }
      delete next[fieldName]
      return next
    })
  }, [])

  const ruleEngine = useLayoutRules({
    rules: layoutRules,
    validationRules: clientValidationRules,
    values: formData,
    setFieldValue,
    setFieldError,
    clearFieldError,
  })

  /**
   * Handle field value change
   */
  // Mirror formData in a ref so handleFieldChange can compute the post-mutation
  // values map and pass it straight into the engine, bypassing the
  // useState/useRef async-update race that would otherwise have the engine
  // evaluate against stale values. Updated in an effect (not during render)
  // to satisfy react-hooks/refs.
  const formDataRef = useRef(formData)
  useEffect(() => {
    formDataRef.current = formData
  }, [formData])

  const handleFieldChange = useCallback(
    (fieldName: string, value: unknown) => {
      const next = { ...formDataRef.current, [fieldName]: value }
      formDataRef.current = next
      setFormData(next)

      // Clear error when field is modified
      if (formErrors[fieldName]) {
        setFormErrors((prev) => {
          const errNext = { ...prev }
          delete errNext[fieldName]
          return errNext
        })
      }

      // Drive the layout-rules engine with the freshly computed values map.
      ruleEngine.onFieldChange(fieldName, next)
    },
    [formErrors, ruleEngine]
  )

  const handleFieldBlur = useCallback(
    (fieldName: string) => {
      ruleEngine.onFieldBlur(fieldName, formDataRef.current)
    },
    [ruleEngine]
  )

  /**
   * Handle form submission
   */
  const handleSubmit = useCallback(
    (event: React.FormEvent) => {
      event.preventDefault()

      if (!validateForm()) {
        showToast(t('errors.validation'), 'error')
        return
      }

      // Layout-rule beforeSave gate: collect violations from the engine and
      // block the submission for ERROR severity. Warnings are logged but
      // allow the submit to proceed.
      const ruleResult = ruleEngine.runBeforeSave()
      if (ruleResult.blocked) {
        showToast(ruleResult.violations[0]?.message ?? t('errors.validation'), 'error')
        return
      }

      setIsSubmitting(true)

      // Prepare data for submission
      const submitData: FormData = {}
      sortedFields.forEach((field) => {
        let value = formData[field.name]

        // Convert values based on field type
        switch (field.type) {
          case 'number':
            if (value !== '' && value !== undefined && value !== null) {
              value = Number(value)
            } else {
              value = null
            }
            break
          case 'boolean':
            value = Boolean(value)
            break
          case 'json':
            if (typeof value === 'string' && value.trim()) {
              try {
                value = JSON.parse(value)
              } catch {
                // Keep as string if invalid JSON
              }
            } else {
              value = null
            }
            break
          case 'date':
          case 'datetime':
            if (value === '') {
              value = null
            }
            break
          case 'master_detail':
            if (value === '') {
              value = null
            }
            break
          case 'currency':
          case 'percent':
            if (value !== '' && value !== undefined && value !== null) {
              value = Number(value)
            } else {
              value = null
            }
            break
          case 'auto_number':
            // Skip auto_number fields - they are auto-generated
            return
          case 'multi_picklist':
            if (!Array.isArray(value)) {
              value = value ? [value] : []
            }
            break
          case 'geolocation':
            // Ensure geolocation stays as an object
            if (typeof value !== 'object' || value === null) {
              value = null
            }
            break
        }

        submitData[field.name] = value
      })

      if (isEditMode) {
        updateMutation.mutate(submitData)
      } else {
        createMutation.mutate(submitData)
      }
    },
    [
      validateForm,
      sortedFields,
      formData,
      isEditMode,
      updateMutation,
      createMutation,
      showToast,
      t,
      ruleEngine,
    ]
  )

  /**
   * Handle cancel action
   */
  const handleCancel = useCallback(() => {
    if (isEditMode) {
      navigate(`/${getTenantSlug()}/resources/${collectionName}/${resourceId}`)
    } else {
      navigate(`/${getTenantSlug()}/resources/${collectionName}`)
    }
  }, [navigate, collectionName, resourceId, isEditMode])

  // Common input class
  const inputBaseClass =
    'w-full rounded-md border border-border bg-card px-4 py-2 font-inherit text-base text-foreground placeholder:text-muted-foreground/60 transition-[border-color,box-shadow] duration-200 hover:border-muted-foreground/40 focus:border-ring focus:outline-none focus:ring-[3px] focus:ring-ring/20 motion-reduce:transition-none'
  const inputErrorClass = 'border-destructive focus:border-destructive focus:ring-destructive/20'

  // Common textarea class
  const textareaBaseClass =
    'w-full resize-y rounded-md border border-border bg-card px-4 py-2 font-inherit text-base text-foreground transition-[border-color,box-shadow] duration-200 hover:border-muted-foreground/40 focus:border-ring focus:outline-none focus:ring-[3px] focus:ring-ring/20 motion-reduce:transition-none'

  /**
   * Render form field based on field type
   * Requirement 11.6: Generate form from collection schema
   * Requirement 12.4: Use custom field renderers when registered, fall back to defaults
   */
  const renderField = useCallback(
    (field: FieldDefinition, index: number): React.ReactNode => {
      const value = formData[field.name]
      const error = formErrors[field.name]
      const fieldId = `field-${field.name}`
      const errorId = `${fieldId}-error`

      // Check for custom renderer from plugin system
      // Requirement 12.4: Use registered field renderers when rendering fields
      const CustomRenderer = getFieldRenderer(field.type)

      if (CustomRenderer) {
        // Use custom renderer from plugin
        return (
          <div key={field.id} className="flex flex-col gap-1" data-testid={`field-group-${index}`}>
            <label htmlFor={fieldId} className="text-sm font-medium text-foreground">
              {field.displayName || field.name}
              {field.required && <span className="ml-1 text-destructive">*</span>}
            </label>
            <div className="mb-1 text-xs text-muted-foreground/60">
              {t(`fields.types.${field.type.toLowerCase()}`)}
            </div>
            <div data-testid={`custom-renderer-${field.name}`}>
              <CustomRenderer
                name={field.name}
                value={value}
                onChange={(newValue) => handleFieldChange(field.name, newValue)}
                error={error}
                metadata={{
                  fieldId,
                  type: field.type,
                  required: field.required,
                  referenceTarget: field.referenceTarget,
                  validation: field.validation,
                  displayName: field.displayName,
                }}
              />
            </div>
            {error && (
              <div
                id={errorId}
                className="mt-1 text-sm text-destructive"
                role="alert"
                data-testid={`error-${field.name}`}
              >
                {error}
              </div>
            )}
          </div>
        )
      }

      // Fall back to default renderers for unregistered types
      const commonProps = {
        id: fieldId,
        name: field.name,
        'aria-invalid': !!error,
        'aria-describedby': error ? errorId : undefined,
        'data-testid': `field-${field.name}`,
      }

      let input: React.ReactNode

      switch (field.type) {
        case 'string':
          input = (
            <input
              {...commonProps}
              type="text"
              className={cn(inputBaseClass, error && inputErrorClass)}
              value={String(value ?? '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              placeholder={field.displayName || field.name}
            />
          )
          break

        case 'number':
          input = (
            <input
              {...commonProps}
              type="number"
              className={cn(inputBaseClass, error && inputErrorClass)}
              value={value === '' || value === null || value === undefined ? '' : String(value)}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              placeholder={field.displayName || field.name}
              step="any"
            />
          )
          break

        case 'boolean':
          input = (
            <div className="flex items-center gap-2 py-2">
              <input
                {...commonProps}
                type="checkbox"
                className="h-5 w-5 cursor-pointer accent-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
                checked={Boolean(value)}
                onChange={(e) => handleFieldChange(field.name, e.target.checked)}
              />
              <span className="text-base text-foreground">
                {value ? t('common.yes') : t('common.no')}
              </span>
            </div>
          )
          break

        case 'date':
          input = (
            <input
              {...commonProps}
              type="date"
              className={cn(inputBaseClass, error && inputErrorClass)}
              value={String(value ?? '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
            />
          )
          break

        case 'datetime':
          input = (
            <input
              {...commonProps}
              type="datetime-local"
              className={cn(inputBaseClass, error && inputErrorClass)}
              value={String(value ?? '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
            />
          )
          break

        case 'json':
          input = (
            <textarea
              {...commonProps}
              className={cn(
                textareaBaseClass,
                'min-h-[100px] font-mono text-sm',
                error && inputErrorClass
              )}
              value={String(value ?? '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              placeholder='{"key": "value"}'
              rows={5}
            />
          )
          break

        case 'picklist':
          input = (
            <select
              id={fieldId}
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={inputBaseClass}
            >
              <option value="">{t('common.select')}</option>
              {(field.enumValues || []).map((val: string) => (
                <option key={val} value={val}>
                  {val}
                </option>
              ))}
            </select>
          )
          break

        case 'multi_picklist':
          input = (
            <div className="flex flex-col gap-1">
              {(field.enumValues || []).map((val: string) => (
                <label key={val} className="flex items-center gap-2 text-base text-foreground">
                  <input
                    type="checkbox"
                    checked={Array.isArray(value) && value.includes(val)}
                    onChange={(e) => {
                      const current = Array.isArray(value) ? [...value] : []
                      if (e.target.checked) {
                        current.push(val)
                      } else {
                        const idx = current.indexOf(val)
                        if (idx >= 0) current.splice(idx, 1)
                      }
                      handleFieldChange(field.name, current)
                    }}
                  />
                  {val}
                </label>
              ))}
              {(!field.enumValues || field.enumValues.length === 0) && (
                <input
                  type="text"
                  id={fieldId}
                  value={String(value || '')}
                  onChange={(e) => handleFieldChange(field.name, e.target.value)}
                  className={inputBaseClass}
                  placeholder={t('fields.types.multi_picklist')}
                />
              )}
            </div>
          )
          break

        case 'currency':
          input = (
            <input
              {...commonProps}
              type="number"
              step="0.01"
              value={value !== null && value !== undefined ? String(value) : ''}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(inputBaseClass, error && inputErrorClass)}
              placeholder="0.00"
            />
          )
          break

        case 'percent':
          input = (
            <div className="flex items-center gap-2">
              <input
                {...commonProps}
                type="number"
                step="0.01"
                value={value !== null && value !== undefined ? String(value) : ''}
                onChange={(e) => handleFieldChange(field.name, e.target.value)}
                className={cn(inputBaseClass, error && inputErrorClass)}
                placeholder="0.00"
              />
              <span className="text-sm text-muted-foreground">%</span>
            </div>
          )
          break

        case 'auto_number':
          input = (
            <input
              {...commonProps}
              type="text"
              value={String(value || '')}
              className={cn(inputBaseClass, 'opacity-50')}
              disabled
              placeholder={t('fields.autoGenerated', 'Auto-generated')}
            />
          )
          break

        case 'phone':
          input = (
            <input
              {...commonProps}
              type="tel"
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(inputBaseClass, error && inputErrorClass)}
              placeholder="+1 (555) 123-4567"
            />
          )
          break

        case 'email':
          input = (
            <input
              {...commonProps}
              type="email"
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(inputBaseClass, error && inputErrorClass)}
              placeholder="user@example.com"
            />
          )
          break

        case 'url':
          input = (
            <input
              {...commonProps}
              type="url"
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(inputBaseClass, error && inputErrorClass)}
              placeholder="https://example.com"
            />
          )
          break

        case 'rich_text':
          input = (
            <textarea
              {...commonProps}
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(textareaBaseClass, 'min-h-[100px]', error && inputErrorClass)}
              rows={8}
              placeholder={t('fields.types.rich_text')}
            />
          )
          break

        case 'encrypted':
          input = (
            <input
              {...commonProps}
              type="password"
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(inputBaseClass, error && inputErrorClass)}
              placeholder="••••••••"
            />
          )
          break

        case 'external_id':
          input = (
            <input
              {...commonProps}
              type="text"
              value={String(value || '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
              className={cn(inputBaseClass, error && inputErrorClass)}
              placeholder={t('fields.types.external_id')}
            />
          )
          break

        case 'geolocation': {
          const geoValue =
            typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {}
          input = (
            <div className="flex items-center gap-2">
              <input
                type="number"
                step="0.000001"
                min="-90"
                max="90"
                value={geoValue.latitude !== undefined ? String(geoValue.latitude) : ''}
                onChange={(e) =>
                  handleFieldChange(field.name, {
                    ...geoValue,
                    latitude: e.target.value ? Number(e.target.value) : null,
                  })
                }
                className={cn(inputBaseClass, error && inputErrorClass)}
                placeholder={t('fields.config.latitude', 'Latitude')}
              />
              <input
                type="number"
                step="0.000001"
                min="-180"
                max="180"
                value={geoValue.longitude !== undefined ? String(geoValue.longitude) : ''}
                onChange={(e) =>
                  handleFieldChange(field.name, {
                    ...geoValue,
                    longitude: e.target.value ? Number(e.target.value) : null,
                  })
                }
                className={cn(inputBaseClass, error && inputErrorClass)}
                placeholder={t('fields.config.longitude', 'Longitude')}
              />
            </div>
          )
          break
        }

        case 'master_detail':
          if (field.lookupOptions && field.lookupOptions.length > 0) {
            input = (
              <LookupSelect
                id={fieldId}
                name={field.name}
                value={String(value ?? '')}
                options={field.lookupOptions}
                onChange={(v) => handleFieldChange(field.name, v)}
                placeholder={t('common.select')}
                required={field.required}
                disabled={false}
                error={!!error}
                data-testid={`field-${field.name}`}
              />
            )
          } else {
            input = (
              <input
                {...commonProps}
                type="text"
                value={String(value || '')}
                onChange={(e) => handleFieldChange(field.name, e.target.value)}
                className={cn(inputBaseClass, error && inputErrorClass)}
                placeholder={t('fields.types.master_detail')}
              />
            )
          }
          break

        default:
          input = (
            <input
              {...commonProps}
              type="text"
              className={cn(inputBaseClass, error && inputErrorClass)}
              value={String(value ?? '')}
              onChange={(e) => handleFieldChange(field.name, e.target.value)}
            />
          )
      }

      return (
        <div key={field.id} className="flex flex-col gap-1" data-testid={`field-group-${index}`}>
          <label htmlFor={fieldId} className="text-sm font-medium text-foreground">
            {field.displayName || field.name}
            {field.required && <span className="ml-1 text-destructive">*</span>}
          </label>
          <div className="mb-1 text-xs text-muted-foreground/60">
            {t(`fields.types.${field.type.toLowerCase()}`)}
          </div>
          {input}
          {error && (
            <div
              id={errorId}
              className="mt-1 text-sm text-destructive"
              role="alert"
              data-testid={`error-${field.name}`}
            >
              {error}
            </div>
          )}
        </div>
      )
    },
    [formData, formErrors, handleFieldChange, t, getFieldRenderer]
  )

  // Loading state
  const isLoading =
    schemaLoading ||
    layoutLoading ||
    (isEditMode && resourceLoading) ||
    (isCloneMode && !cloneSource)

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

  // Error state - resource error (edit mode)
  if (isEditMode && resourceError) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <ErrorMessage
          error={resourceError instanceof Error ? resourceError : new Error(t('errors.generic'))}
          onRetry={() =>
            queryClient.invalidateQueries({ queryKey: ['resource', collectionName, resourceId] })
          }
        />
      </div>
    )
  }

  // Not found state
  if (!effectiveSchema) {
    return (
      <div
        className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
        data-testid={testId}
      >
        <ErrorMessage error={new Error(t('errors.notFound'))} />
      </div>
    )
  }

  return (
    <div
      className="flex w-full flex-col gap-6 p-6 max-lg:p-4 max-md:gap-4 max-md:p-2"
      data-testid={testId}
    >
      {/* Page Header */}
      <header className="flex flex-col gap-2">
        <div className="flex flex-col gap-1">
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
              to={`/${getTenantSlug()}/resources/${collectionName}`}
              className="text-primary no-underline transition-colors duration-200 hover:text-primary/80 hover:underline focus:rounded focus:outline-2 focus:outline-offset-2 focus:outline-ring motion-reduce:transition-none"
            >
              {effectiveSchema.displayName}
            </Link>
            <span className="mx-1 text-muted-foreground/60" aria-hidden="true">
              /
            </span>
            <span className="font-medium text-foreground">
              {isEditMode
                ? t('resources.editRecord')
                : isCloneMode
                  ? t('resources.cloneRecord', { collection: effectiveSchema.displayName })
                  : t('resources.createRecord')}
            </span>
          </nav>
          <h1
            className="m-0 text-2xl font-semibold text-foreground max-md:text-xl"
            data-testid="page-title"
          >
            {isEditMode
              ? t('resources.editRecord')
              : isCloneMode
                ? t('resources.cloneRecord', { collection: effectiveSchema.displayName })
                : t('resources.createRecord')}
          </h1>
          {isEditMode && resourceId && (
            <p className="m-0 font-mono text-sm text-muted-foreground" data-testid="resource-id">
              ID: {resourceId}
            </p>
          )}
          {isCloneMode && cloneSourceId && (
            <div
              className="mt-1 flex items-center gap-2 rounded-md border border-blue-200 bg-blue-50 px-4 py-2 text-sm text-blue-800 dark:border-blue-800 dark:bg-blue-900/30 dark:text-blue-300"
              data-testid="clone-banner"
            >
              <span>
                {t('resources.cloningFrom')} {cloneSourceId}
              </span>
              <Link
                to={`/${getTenantSlug()}/resources/${collectionName}/${cloneSourceId}`}
                className="font-medium text-primary no-underline hover:underline"
              >
                {t('resources.viewSource')}
              </Link>
            </div>
          )}
        </div>
      </header>

      {/* Form */}
      <form
        className="flex flex-col gap-6"
        onSubmit={handleSubmit}
        onBlur={(event) => {
          // Bubble field blur to the layout-rules engine. We use the input's
          // `name` attribute (set by every renderField branch via field.name)
          // to identify the field. Inputs without a name are ignored.
          const name = (event.target as { name?: string } | null)?.name
          if (name) handleFieldBlur(name)
        }}
        noValidate
        data-testid="resource-form"
      >
        {/* Form Fields — use page layout sections when available */}
        {layout && layout.sections.length > 0 ? (
          <LayoutFormSections
            sections={layout.sections}
            schemaFields={sortedFields as unknown as LayoutFormFieldDefinition[]}
            renderField={
              renderField as unknown as (
                field: LayoutFormFieldDefinition,
                index: number
              ) => React.ReactNode
            }
            record={formData}
            isComputed={ruleEngine.isComputed}
          />
        ) : (
          <div className="flex flex-col gap-6 rounded-md border border-border bg-card p-6 max-md:p-4">
            {sortedFields.length === 0 ? (
              <div
                className="flex flex-col items-center justify-center rounded-md bg-muted p-8 text-center text-muted-foreground"
                data-testid="no-fields"
              >
                <p className="m-0 text-base">{t('resourceForm.noFields')}</p>
              </div>
            ) : (
              sortedFields.map((field, index) => renderField(field, index))
            )}
          </div>
        )}

        {/* Form Actions */}
        <div className="flex justify-end gap-4 border-t border-border pt-4 max-md:flex-col-reverse">
          <button
            type="button"
            className="inline-flex cursor-pointer items-center justify-center gap-2 rounded-md border border-border bg-transparent px-6 py-2 text-base font-medium text-muted-foreground transition-[background-color,border-color] duration-200 hover:bg-muted hover:text-foreground focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:cursor-not-allowed disabled:opacity-50 max-md:w-full motion-reduce:transition-none"
            onClick={handleCancel}
            disabled={isSubmitting}
            data-testid="cancel-button"
          >
            {t('common.cancel')}
          </button>
          <button
            type="submit"
            className="inline-flex cursor-pointer items-center justify-center gap-2 rounded-md border border-primary bg-primary px-6 py-2 text-base font-medium text-primary-foreground transition-[background-color,border-color] duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:cursor-not-allowed disabled:opacity-50 max-md:w-full motion-reduce:transition-none"
            disabled={isSubmitting || sortedFields.length === 0}
            data-testid="submit-button"
          >
            {isSubmitting ? (
              <>
                <LoadingSpinner size="small" />
                {t('common.saving')}
              </>
            ) : (
              t('common.save')
            )}
          </button>
        </div>
      </form>
    </div>
  )
}

export default ResourceFormPage
