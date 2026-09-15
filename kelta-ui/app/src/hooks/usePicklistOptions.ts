/**
 * `usePicklistOptions` (slice 2f) — extracts the `ObjectFormPage` picklist-source resolution
 * (FIELD vs GLOBAL via `fieldTypeConfig.globalPicklistId`) + the `/api/picklist-values?filter[…]`
 * fetch into one shared hook so `DropdownInput`, `MultiPicklistInput`, and the `form` field-renderer
 * registry all share a single implementation (no duplication across the three sites).
 *
 * `fieldTypeConfig` may arrive as a parsed object OR a JSON string; `globalPicklistId` (or the
 * legacy pre-#1222 `picklistSourceId` + `picklistSourceType: 'GLOBAL'` dialect) → `GLOBAL` source,
 * else the field id → `FIELD`. Active values are kept and sorted by `sortOrder`. Errors fall back
 * to an empty list. `ObjectFormPage`/`ResourceFormPage` share `resolvePicklistSource` directly.
 */
import { useMemo } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import type { ApiClient } from '@/services/apiClient'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

/** Picklist value returned from the API (field names match the backend schema). */
interface PicklistValueDto {
  value: string
  label: string
  isDefault: boolean
  isActive: boolean
  sortOrder: number
  color?: string
  description?: string
}

/** One active picklist value, in display order, with its authored label/color/description. */
export interface PicklistOptionEntry {
  value: string
  label: string
  color?: string
  description?: string
}

/**
 * Shape of `fieldTypeConfig` for picklist-typed fields. Modern writes use
 * `globalPicklistId`; fields written by the MCP admin tooling before #1222 carry
 * `picklistSourceId` + `picklistSourceType: 'GLOBAL'` instead — both dialects
 * must resolve or the field renders unbound.
 */
interface PicklistFieldTypeConfig {
  globalPicklistId?: string
  picklistSourceId?: string
  picklistSourceType?: string
}

/**
 * Extract the global-picklist id from a picklist field's `fieldTypeConfig`,
 * accepting the legacy `picklistSourceId`/`picklistSourceType` dialect.
 * The config may arrive as a parsed object (JSONB column) or a JSON string.
 */
export function resolveGlobalPicklistId(rawConfig: unknown): string | undefined {
  let config: PicklistFieldTypeConfig | null = null
  if (typeof rawConfig === 'string') {
    try {
      config = JSON.parse(rawConfig) as PicklistFieldTypeConfig
    } catch {
      /* ignore malformed config */
    }
  } else if (rawConfig && typeof rawConfig === 'object') {
    config = rawConfig as PicklistFieldTypeConfig
  }
  return (
    config?.globalPicklistId ??
    (config?.picklistSourceType === 'GLOBAL' ? config?.picklistSourceId : undefined)
  )
}

/** Resolve the `{ sourceId, sourceType }` for a picklist/multi_picklist field (FIELD vs GLOBAL). */
export function resolvePicklistSource(field: Pick<FieldDefinition, 'id' | 'fieldTypeConfig'>): {
  sourceId: string
  sourceType: 'FIELD' | 'GLOBAL'
} {
  const globalPicklistId = resolveGlobalPicklistId(field.fieldTypeConfig)
  if (globalPicklistId) {
    return { sourceId: globalPicklistId, sourceType: 'GLOBAL' }
  }
  return { sourceId: field.id, sourceType: 'FIELD' }
}

/** Fetch + shape the active, sorted picklist entries for one field. Empty array on error. */
async function fetchPicklistEntries(
  apiClient: Pick<ApiClient, 'getList'>,
  field: Pick<FieldDefinition, 'id' | 'fieldTypeConfig'> | undefined
): Promise<PicklistOptionEntry[]> {
  if (!field) return []
  try {
    const { sourceId, sourceType } = resolvePicklistSource(field)
    const values = await apiClient.getList<PicklistValueDto>(
      `/api/picklist-values?filter[picklistSourceId][eq]=${encodeURIComponent(sourceId)}&filter[picklistSourceType][eq]=${sourceType}&page[size]=200`
    )
    return values
      .filter((v) => v.isActive)
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((v) => ({ value: v.value, label: v.label, color: v.color, description: v.description }))
  } catch {
    return []
  }
}

export interface UsePicklistOptionsResult {
  options: string[]
  isLoading: boolean
}

/**
 * Fetch the active, sorted picklist `value[]` for one picklist/multi_picklist field.
 * Disabled when no field is supplied or when `enabled` is false (e.g. editor mode).
 */
export function usePicklistOptions(
  field: Pick<FieldDefinition, 'id' | 'fieldTypeConfig'> | undefined,
  enabled = true
): UsePicklistOptionsResult {
  const { apiClient } = useApi()
  const { data, isLoading } = useQuery({
    queryKey: ['page-input-picklist', field?.id, field?.fieldTypeConfig],
    queryFn: () =>
      fetchPicklistEntries(apiClient, field).then((entries) => entries.map((e) => e.value)),
    enabled: enabled && !!field,
    staleTime: 5 * 60 * 1000,
  })
  return { options: data ?? [], isLoading }
}

/** Raw value → authored `{label, color, description}` for one picklist/multi_picklist field. */
export type PicklistDisplayMap = Map<
  string,
  { label: string; color?: string; description?: string }
>

export interface UsePicklistDisplayMapResult {
  displayMap: PicklistDisplayMap
  isLoading: boolean
}

/**
 * Memoised value→`{label, color}` lookup for read-only surfaces (badges, kanban lane headers,
 * filter chips) that render a picklist's stored value but want the authored label/color instead.
 * Disabled when no field is supplied or when `enabled` is false.
 */
export function usePicklistDisplayMap(
  field: Pick<FieldDefinition, 'id' | 'fieldTypeConfig'> | undefined,
  enabled = true
): UsePicklistDisplayMapResult {
  const { apiClient } = useApi()
  const { data, isLoading } = useQuery({
    queryKey: ['picklist-display-map', field?.id, field?.fieldTypeConfig],
    queryFn: () => fetchPicklistEntries(apiClient, field),
    enabled: enabled && !!field,
    staleTime: 5 * 60 * 1000,
  })
  const displayMap = useMemo(() => {
    const map: PicklistDisplayMap = new Map()
    for (const entry of data ?? []) {
      map.set(entry.value, {
        label: entry.label,
        color: entry.color,
        description: entry.description,
      })
    }
    return map
  }, [data])
  return { displayMap, isLoading }
}

/** Field name → `PicklistDisplayMap`, for surfaces rendering many columns/filters at once. */
export type PicklistDisplayMaps = Record<string, PicklistDisplayMap>

const PICKLIST_DISPLAY_TYPES = new Set(['picklist', 'multi_picklist'])

/**
 * Bulk form of `usePicklistDisplayMap`: fetches every picklist/multi_picklist field in `fields`
 * with one `useQueries` fan-out (`FieldDefinition[]` is dynamic length, so a plain loop of
 * `usePicklistDisplayMap` calls would violate the Rules of Hooks) and returns a
 * field-name-keyed lookup for list/detail/filter surfaces that render many columns at once.
 */
export function usePicklistDisplayMaps(
  fields: Pick<FieldDefinition, 'id' | 'name' | 'type' | 'fieldTypeConfig'>[],
  enabled = true
): { displayMaps: PicklistDisplayMaps; isLoading: boolean } {
  const { apiClient } = useApi()
  const picklistFields = useMemo(
    () => fields.filter((f) => PICKLIST_DISPLAY_TYPES.has(f.type)),
    [fields]
  )
  const results = useQueries({
    queries: picklistFields.map((field) => ({
      queryKey: ['picklist-display-map', field.id, field.fieldTypeConfig],
      queryFn: () => fetchPicklistEntries(apiClient, field),
      enabled,
      staleTime: 5 * 60 * 1000,
    })),
  })
  const out: PicklistDisplayMaps = {}
  picklistFields.forEach((field, i) => {
    const map: PicklistDisplayMap = new Map()
    for (const entry of results[i]?.data ?? []) {
      map.set(entry.value, {
        label: entry.label,
        color: entry.color,
        description: entry.description,
      })
    }
    out[field.name] = map
  })
  const isLoading = results.some((r) => r.isLoading)
  return { displayMaps: out, isLoading }
}
