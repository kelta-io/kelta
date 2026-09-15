/**
 * useCollectionSchema Hook
 *
 * Fetches and caches a collection's schema (name, displayName, fields)
 * from the JSON:API endpoint. Uses `?include=fields` to fetch the
 * collection record and its field definitions in a single request.
 * Field types are normalized from backend canonical form (uppercase)
 * to UI form (lowercase).
 */

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'
import { unwrapResource, extractIncluded } from '../utils/jsonapi'
import type { ApiClient } from '../services/apiClient'

/**
 * Field type union covering all supported field types.
 */
export type FieldType =
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'json'
  | 'reference'
  | 'picklist'
  | 'multi_picklist'
  | 'currency'
  | 'percent'
  | 'auto_number'
  | 'phone'
  | 'email'
  | 'url'
  | 'rich_text'
  | 'encrypted'
  | 'external_id'
  | 'geolocation'
  | 'lookup'
  | 'master_detail'
  | 'formula'
  | 'rollup_summary'

/**
 * Field definition from the collection schema.
 */
export interface FieldDefinition {
  id: string
  name: string
  displayName?: string
  type: FieldType
  required: boolean
  referenceTarget?: string
  referenceCollectionId?: string
  /** Type-specific config (JSONB — may arrive as parsed object or JSON string) */
  fieldTypeConfig?: string | Record<string, unknown>
  /** Picklist enum values (populated at runtime by form pages) */
  enumValues?: string[]
  /**
   * Picklist values with their authored label/color (populated at runtime by form pages,
   * alongside `enumValues`) — `enumValues[i]` is `enumOptions[i].value`.
   */
  enumOptions?: Array<{ value: string; label: string; color?: string }>
  /** Lookup options for reference fields (populated at runtime by form pages) */
  lookupOptions?: Array<{ id: string; label: string }>
}

/**
 * Collection schema with fields.
 */
export interface CollectionSchema {
  id: string
  name: string
  displayName: string
  displayFieldName?: string
  /** Collection-level record versioning toggle — gates the record History tab */
  trackHistory?: boolean
  fields: FieldDefinition[]
}

/**
 * Reverse mapping from backend canonical types (uppercase) to UI types (lowercase).
 */
const BACKEND_TYPE_TO_UI: Record<string, FieldType> = {
  DOUBLE: 'number',
  INTEGER: 'number',
  LONG: 'number',
  JSON: 'json',
  ARRAY: 'json',
  REFERENCE: 'master_detail',
  LOOKUP: 'master_detail',
}

function normalizeFieldType(backendType: string): FieldType {
  const upper = backendType.toUpperCase()
  if (upper in BACKEND_TYPE_TO_UI) {
    return BACKEND_TYPE_TO_UI[upper]
  }
  return backendType.toLowerCase() as FieldType
}

/**
 * Fetch a collection schema by name from the JSON:API endpoint.
 *
 * Uses `?include=fields` to fetch the collection record and its field
 * definitions in a single request. The backend resolves by name or ID,
 * and the include resolver fetches all field records whose collectionId
 * matches the resolved collection.
 */
export async function fetchCollectionSchema(
  apiClient: ApiClient,
  collectionName: string
): Promise<CollectionSchema> {
  // Use raw get() to preserve the full JSON:API response including `included` array
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
  // Spread all properties to preserve constraints, validation, etc.
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
    displayFieldName,
    trackHistory: !!collection.trackHistory,
    fields,
  }
}

export interface UseCollectionSchemaReturn {
  schema: CollectionSchema | undefined
  fields: FieldDefinition[]
  isLoading: boolean
  error: Error | null
}

/**
 * Hook to fetch and cache a collection schema.
 *
 * @param collectionName - The collection name to fetch the schema for
 * @returns Collection schema, fields array, loading/error states
 */
export function useCollectionSchema(collectionName: string | undefined): UseCollectionSchemaReturn {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  // The CollectionStoreProvider bulk-loads all collections and pre-populates
  // this query's cache via setQueryData(['collection-schema', name]).
  // To avoid a redundant per-collection fetch, we only enable our own queryFn
  // if the store has finished loading AND didn't populate our cache entry
  // (e.g., the collection doesn't exist in the bulk response).
  const storeQuery = queryClient.getQueryState(['collection-store'])
  const storeIsLoading = storeQuery != null && storeQuery.status === 'pending'
  const cachedData = collectionName
    ? queryClient.getQueryData<CollectionSchema>(['collection-schema', collectionName])
    : undefined

  const {
    data: schema,
    isLoading: queryIsLoading,
    error,
  } = useQuery({
    queryKey: ['collection-schema', collectionName],
    queryFn: () => fetchCollectionSchema(apiClient, collectionName!),
    // Don't fetch if:
    // 1. No collection name provided, OR
    // 2. The store is still loading (it will populate our cache when done), OR
    // 3. Data is already in the cache (from the store's setQueryData)
    enabled: !!collectionName && !storeIsLoading && !cachedData,
    staleTime: 5 * 60 * 1000, // 5 minutes — metadata changes rarely
  })

  // Use cached data from the store if the query hasn't fetched its own
  const resolvedSchema = schema ?? cachedData
  const isLoading = !resolvedSchema && (queryIsLoading || storeIsLoading)

  return {
    schema: resolvedSchema,
    fields: resolvedSchema?.fields ?? [],
    isLoading,
    error: error as Error | null,
  }
}
