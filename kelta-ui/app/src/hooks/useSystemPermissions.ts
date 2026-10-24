import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../context'
import { useApi } from '../context/ApiContext'
import type { ObjectPermissions } from './useCollectionPermissions'

/**
 * System-level permission flags for the current user.
 */
type SystemPermissions = Record<string, boolean>

/**
 * Full permissions response from /api/me/permissions.
 */
export interface MyPermissionsResponse {
  systemPermissions: SystemPermissions
  objectPermissions: Record<string, ObjectPermissions>
  fieldPermissions: Record<string, Record<string, string>>
}

export interface UseSystemPermissionsReturn {
  permissions: SystemPermissions
  hasPermission: (permission: string) => boolean
  isLoading: boolean
  error: Error | null
}

/** Query key used by all permission hooks to share the same cache entry. */
export const MY_PERMISSIONS_QUERY_KEY = ['my-permissions'] as const

/**
 * Hook that fetches the current user's system-level permissions
 * from the `/api/me/permissions` endpoint.
 * Results are cached with a 5-minute stale time.
 */
export function useSystemPermissions(): UseSystemPermissionsReturn {
  const { user } = useAuth()
  const { keltaClient } = useApi()

  const { data, isLoading, error } = useQuery<MyPermissionsResponse>({
    queryKey: MY_PERMISSIONS_QUERY_KEY,
    queryFn: async () => {
      const response = await keltaClient.getAxiosInstance().get('/api/me/permissions')
      return {
        systemPermissions: (response.data?.systemPermissions ?? {}) as SystemPermissions,
        objectPermissions: (response.data?.objectPermissions ?? {}) as Record<
          string,
          ObjectPermissions
        >,
        fieldPermissions: (response.data?.fieldPermissions ?? {}) as Record<
          string,
          Record<string, string>
        >,
      }
    },
    enabled: !!user,
    staleTime: 5 * 60 * 1000,
    retry: 2,
    retryDelay: 1000,
  })

  const permissions: SystemPermissions = data?.systemPermissions ?? {}

  const hasPermission = (permission: string): boolean => {
    return permissions[permission] === true
  }

  return {
    permissions,
    hasPermission,
    isLoading,
    error: error as Error | null,
  }
}
