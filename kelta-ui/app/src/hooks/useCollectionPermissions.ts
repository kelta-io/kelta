/**
 * useCollectionPermissions Hook
 *
 * Returns both object-level CRUD permissions and field-level visibility
 * for a collection. Reads from the shared /api/me/permissions cache
 * populated by useSystemPermissions.
 *
 * It OBSERVES that cache entry (a disabled query on the same key — it never fetches) rather than
 * reading it once, so a caller that rendered before the shell's permissions fetch landed
 * re-renders when it does; a caller gating on `isLoading` (the record form) would otherwise
 * stay on its spinner until something else re-rendered it. A failed permissions fetch ends
 * the loading state with the permissive defaults — this is UX gating only, the worker
 * enforces every permission.
 */

import { useMemo } from 'react'
import { skipToken, useQuery } from '@tanstack/react-query'
import type { MyPermissionsResponse } from './useSystemPermissions'
import { MY_PERMISSIONS_QUERY_KEY } from './useSystemPermissions'

/**
 * Object-level CRUD permission flags.
 */
export interface ObjectPermissions {
  canCreate: boolean
  canRead: boolean
  canEdit: boolean
  canDelete: boolean
}

/**
 * Field visibility level.
 */
export type FieldVisibility = 'VISIBLE' | 'READ_ONLY' | 'HIDDEN'

export interface UseCollectionPermissionsReturn {
  /** Object-level CRUD permission flags */
  permissions: ObjectPermissions
  /** Map of fieldName → visibility. Fields not in the map default to VISIBLE. */
  fieldPermissions: Map<string, FieldVisibility>
  /** Get visibility for a specific field (defaults to VISIBLE if not in map). */
  getFieldVisibility: (fieldName: string) => FieldVisibility
  /** Check if a field should be shown (not HIDDEN). */
  isFieldVisible: (fieldName: string) => boolean
  /** Check if a field is editable (VISIBLE, not READ_ONLY or HIDDEN). */
  isFieldEditable: (fieldName: string) => boolean
  isLoading: boolean
  error: Error | null
}

/** Default permissive permissions (used when collection has no explicit permissions) */
const PERMISSIVE_DEFAULTS: ObjectPermissions = {
  canCreate: true,
  canRead: true,
  canEdit: true,
  canDelete: true,
}

/**
 * Hook to fetch combined object + field permissions for a collection.
 * Reads from the shared my-permissions cache.
 *
 * @param collectionName - The collection API name
 * @returns Combined permission data and helper functions
 */
export function useCollectionPermissions(
  collectionName: string | undefined
): UseCollectionPermissionsReturn {
  const { data: cached, isError } = useQuery<MyPermissionsResponse>({
    queryKey: MY_PERMISSIONS_QUERY_KEY,
    queryFn: skipToken,
  })

  const objectPerms =
    collectionName && cached?.objectPermissions?.[collectionName]
      ? cached.objectPermissions[collectionName]
      : PERMISSIVE_DEFAULTS

  const rawFieldPerms =
    collectionName && cached?.fieldPermissions?.[collectionName]
      ? cached.fieldPermissions[collectionName]
      : undefined

  const fieldPermissions = useMemo(() => {
    const map = new Map<string, FieldVisibility>()
    if (rawFieldPerms) {
      for (const [fieldName, visibility] of Object.entries(rawFieldPerms)) {
        map.set(fieldName, visibility as FieldVisibility)
      }
    }
    return map
  }, [rawFieldPerms])

  const getFieldVisibility = useMemo(() => {
    return (fieldName: string): FieldVisibility => {
      return fieldPermissions.get(fieldName) ?? 'VISIBLE'
    }
  }, [fieldPermissions])

  const isFieldVisible = useMemo(() => {
    return (fieldName: string): boolean => {
      return getFieldVisibility(fieldName) !== 'HIDDEN'
    }
  }, [getFieldVisibility])

  const isFieldEditable = useMemo(() => {
    return (fieldName: string): boolean => {
      return getFieldVisibility(fieldName) === 'VISIBLE'
    }
  }, [getFieldVisibility])

  return {
    permissions: objectPerms,
    fieldPermissions,
    getFieldVisibility,
    isFieldVisible,
    isFieldEditable,
    isLoading: !cached && !isError,
    error: null,
  }
}
