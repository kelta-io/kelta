/**
 * CollectionStoreContext
 *
 * Centralized collection metadata store that loads ALL collections and their
 * fields in a single API call when the tenant app initializes. This eliminates
 * redundant per-collection and per-field API requests that were previously made
 * by individual components (ObjectDetailPage, ObjectListPage, RelatedList,
 * usePageLayout, etc.).
 *
 * The store provides:
 * - All collection schemas with full field definitions
 * - Lookup by collection name, collection ID, or field ID
 * - Pre-populated React Query cache for useCollectionSchema compatibility
 *
 * One API call replaces what was previously 5-10+ individual requests per page.
 */

import React, { createContext, useContext, useMemo, useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from './ApiContext'
import { useAuth } from './AuthContext'
import type { CollectionSchema, FieldDefinition } from '../hooks/useCollectionSchema'
import { normalizeFieldType } from '../utils/normalizeFieldType'

/** Extended field definition with parent collection reference */
export interface StoredField extends FieldDefinition {
  collectionId: string
  collectionName: string
}

export interface CollectionStoreValue {
  /** Whether the store is still loading */
  isLoading: boolean
  /** All collection schemas indexed by name */
  collections: CollectionSchema[]
  /** Look up a collection schema by name */
  getCollectionByName: (name: string) => CollectionSchema | undefined
  /** Look up a collection schema by ID */
  getCollectionById: (id: string) => CollectionSchema | undefined
  /** Look up a field definition by field ID */
  getFieldById: (id: string) => StoredField | undefined
}

const CollectionStoreContext = createContext<CollectionStoreValue | undefined>(undefined)

/** JSON:API resource shape */
interface RawResource {
  id: string
  type: string
  attributes?: Record<string, unknown>
  relationships?: Record<string, { data?: { id: string; type: string } | null }>
}

/**
 * A JSON column's value: unwraps the `{ type: "jsonb", value: "<json>" }` wrapper (parsing
 * the inner JSON text) and passes an already-parsed value through. `null` → `undefined`.
 */
function unwrapJsonColumn(raw: unknown): unknown {
  if (raw === null || raw === undefined) return undefined
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    const wrapper = raw as Record<string, unknown>
    if (wrapper.type === 'jsonb' && 'value' in wrapper) {
      if (wrapper.null === true || typeof wrapper.value !== 'string') return undefined
      try {
        return JSON.parse(wrapper.value)
      } catch {
        return wrapper.value
      }
    }
  }
  return raw
}

/**
 * Parse the bulk collections+fields JSON:API response into CollectionSchema[].
 *
 * The response shape is:
 * {
 *   data: [ { id, type: "collections", attributes: { name, displayName, ... } } ],
 *   included: [ { id, type: "fields", attributes: { name, type, collectionId, ... } } ]
 * }
 */
function parseCollectionsResponse(response: unknown): CollectionSchema[] {
  if (!response || typeof response !== 'object') return []

  const resp = response as { data?: RawResource[]; included?: RawResource[] }
  const dataArray = Array.isArray(resp.data) ? resp.data : []
  const includedArray = Array.isArray(resp.included) ? resp.included : []

  // Group included fields by collectionId.
  // In JSON:API, collectionId is a relationship (not an attribute):
  //   field.relationships.collectionId.data.id → parent collection UUID
  const fieldsByCollectionId = new Map<string, RawResource[]>()
  for (const resource of includedArray) {
    if (resource.type !== 'fields') continue
    const collectionId =
      resource.relationships?.collectionId?.data?.id ||
      (resource.attributes?.collectionId as string)
    if (collectionId) {
      const list = fieldsByCollectionId.get(collectionId) ?? []
      list.push(resource)
      fieldsByCollectionId.set(collectionId, list)
    }
  }

  // Build schemas
  return dataArray.map((collResource) => {
    const attrs = collResource.attributes ?? {}
    const fieldResources = fieldsByCollectionId.get(collResource.id) ?? []

    // Resolve displayFieldName from displayFieldId.
    // In JSON:API, displayFieldId is a relationship (not an attribute):
    //   collection.relationships.displayFieldId.data.id → display field UUID
    const displayFieldId =
      collResource.relationships?.displayFieldId?.data?.id ||
      (attrs.displayFieldId as string | undefined)
    let displayFieldName: string | undefined
    if (displayFieldId) {
      const displayFieldResource = fieldResources.find((f) => f.id === displayFieldId)
      if (displayFieldResource) {
        displayFieldName = displayFieldResource.attributes?.name as string
      }
    }

    // Parse and normalize fields
    const fields: FieldDefinition[] = fieldResources
      .filter((f) => f.attributes?.active !== false)
      .sort((a, b) => {
        const orderA = typeof a.attributes?.fieldOrder === 'number' ? a.attributes.fieldOrder : 999
        const orderB = typeof b.attributes?.fieldOrder === 'number' ? b.attributes.fieldOrder : 999
        return (orderA as number) - (orderB as number)
      })
      .map((f) => {
        const a = f.attributes ?? {}
        // referenceCollectionId may be a relationship or attribute
        const refCollId =
          f.relationships?.referenceCollectionId?.data?.id ||
          (a.referenceCollectionId as string) ||
          undefined

        // Unwrap fieldTypeConfig: JSONB columns may arrive as wrapper objects
        // { type: "jsonb", value: "<json-string>", null: false }
        let fieldTypeConfig: string | Record<string, unknown> | undefined
        const rawFtc = a.fieldTypeConfig
        if (rawFtc && typeof rawFtc === 'object' && !Array.isArray(rawFtc)) {
          const wrapper = rawFtc as Record<string, unknown>
          if (wrapper.type === 'jsonb' && 'value' in wrapper) {
            // JSONB wrapper — extract inner value string
            fieldTypeConfig = wrapper.null === true ? undefined : (wrapper.value as string)
          } else {
            // Already a plain object
            fieldTypeConfig = rawFtc as Record<string, unknown>
          }
        } else if (typeof rawFtc === 'string') {
          fieldTypeConfig = rawFtc
        }

        return {
          id: f.id,
          name: a.name as string,
          displayName: (a.displayName as string) || undefined,
          type: normalizeFieldType(a.type as string),
          required: !!a.required,
          referenceTarget: (a.referenceTarget as string) || undefined,
          referenceCollectionId: refCollId,
          fieldTypeConfig,
          // The record form applies these (create default, client validation); without
          // them here the preloaded schema silently drops what the per-collection fetch has.
          defaultValue: unwrapJsonColumn(a.defaultValue),
          constraints: unwrapJsonColumn(a.constraints) as FieldDefinition['constraints'],
        } as FieldDefinition
      })

    return {
      id: collResource.id,
      name: attrs.name as string,
      displayName: (attrs.displayName as string) || (attrs.name as string),
      displayFieldName,
      trackHistory: !!attrs.trackHistory,
      fields,
    }
  })
}

export interface CollectionStoreProviderProps {
  children: React.ReactNode
}

/**
 * Provider that loads all collections+fields in one API call and makes
 * them available via context and React Query cache.
 */
export function CollectionStoreProvider({
  children,
}: CollectionStoreProviderProps): React.ReactElement {
  const { apiClient } = useApi()
  const { isAuthenticated } = useAuth()
  const queryClient = useQueryClient()

  // Page through every collection + included fields. Earlier this was a
  // single page=1 size=1000 call, which silently truncated when total
  // collections × fields exceeded the cap and made newly-added fields
  // (e.g. a fresh rollup_summary) invisible to the cache. Backend list
  // responses include metadata.totalPages — keep fetching until exhausted.
  const { data: schemas = [], isLoading } = useQuery({
    queryKey: ['collection-store'],
    queryFn: async () => {
      const PAGE_SIZE = 500
      const allData: RawResource[] = []
      const allIncluded: RawResource[] = []
      let page = 1
      while (true) {
        const raw = (await apiClient.get(
          `/api/collections?page[size]=${PAGE_SIZE}&page[number]=${page}&include=fields`
        )) as {
          data?: RawResource[]
          included?: RawResource[]
          metadata?: { totalPages?: number }
        }
        if (Array.isArray(raw.data)) allData.push(...raw.data)
        if (Array.isArray(raw.included)) allIncluded.push(...raw.included)
        const totalPages = raw.metadata?.totalPages ?? 1
        if (page >= totalPages || !Array.isArray(raw.data) || raw.data.length < PAGE_SIZE) break
        page++
      }

      const parsed = parseCollectionsResponse({ data: allData, included: allIncluded })

      // Pre-populate the React Query cache with individual collection-schema entries
      // so useCollectionSchema(name) finds cached data without a second fetch.
      for (const schema of parsed) {
        queryClient.setQueryData(['collection-schema', schema.name], schema)
      }

      return parsed
    },
    staleTime: 5 * 60 * 1000, // 5 minutes — metadata rarely changes
    enabled: isAuthenticated,
  })

  // Build lookup maps
  const byName = useMemo(() => {
    const map = new Map<string, CollectionSchema>()
    for (const s of schemas) {
      map.set(s.name, s)
    }
    return map
  }, [schemas])

  const byId = useMemo(() => {
    const map = new Map<string, CollectionSchema>()
    for (const s of schemas) {
      map.set(s.id, s)
    }
    return map
  }, [schemas])

  const fieldsById = useMemo(() => {
    const map = new Map<string, StoredField>()
    for (const schema of schemas) {
      for (const field of schema.fields) {
        map.set(field.id, {
          ...field,
          collectionId: schema.id,
          collectionName: schema.name,
        })
      }
    }
    return map
  }, [schemas])

  const getCollectionByName = useCallback((name: string) => byName.get(name), [byName])

  const getCollectionById = useCallback((id: string) => byId.get(id), [byId])

  const getFieldById = useCallback((id: string) => fieldsById.get(id), [fieldsById])

  const value = useMemo<CollectionStoreValue>(
    () => ({
      isLoading,
      collections: schemas,
      getCollectionByName,
      getCollectionById,
      getFieldById,
    }),
    [isLoading, schemas, getCollectionByName, getCollectionById, getFieldById]
  )

  return <CollectionStoreContext.Provider value={value}>{children}</CollectionStoreContext.Provider>
}

/**
 * Hook to access the centralized collection store.
 *
 * @throws Error if used outside of CollectionStoreProvider
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useCollectionStore(): CollectionStoreValue {
  const context = useContext(CollectionStoreContext)
  if (context === undefined) {
    throw new Error('useCollectionStore must be used within a CollectionStoreProvider')
  }
  return context
}
