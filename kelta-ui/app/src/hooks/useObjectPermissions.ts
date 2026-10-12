/**
 * useObjectPermissions Hook
 *
 * Returns CRUD permission flags for a given collection — the object-level subset of
 * {@link useCollectionPermissions}, which it delegates to. Reads the shared /api/me/permissions
 * cache populated by useSystemPermissions, OBSERVING the entry (not reading it once), so a caller
 * that rendered before the shell's permissions fetch landed re-renders when it does.
 *
 * Collections not present in the response default to all-permitted so the UI
 * degrades gracefully (the gateway still enforces at the API level).
 */

import { useCollectionPermissions } from './useCollectionPermissions'

/**
 * Object-level CRUD permission flags.
 */
export interface ObjectPermissions {
  canCreate: boolean
  canRead: boolean
  canEdit: boolean
  canDelete: boolean
}

export interface UseObjectPermissionsReturn {
  permissions: ObjectPermissions
  isLoading: boolean
  error: Error | null
}

/**
 * Hook to fetch effective object permissions for a collection.
 * Reads from the shared my-permissions cache.
 *
 * @param collectionName - The collection API name
 * @returns Permission flags, loading state, and error
 */
export function useObjectPermissions(
  collectionName: string | undefined
): UseObjectPermissionsReturn {
  const { permissions, isLoading, error } = useCollectionPermissions(collectionName)
  return { permissions, isLoading, error }
}
