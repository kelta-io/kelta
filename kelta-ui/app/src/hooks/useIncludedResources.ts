/**
 * useIncludedResources
 *
 * Reads related resources from a JSON:API response's `included` array (the sideloaded
 * resources of a `?include=` request), so a page renders related data from one request
 * instead of one request per reference.
 */

import { useMemo } from 'react'
import { extractIncluded } from '../utils/jsonapi'

/**
 * Extract all included resources of a given type as flat records.
 *
 * ```tsx
 * const profiles = useExtractIncluded<ProfileRecord>(response, 'profiles')
 * // profiles = [{ id: "p1", name: "Admin", ... }, { id: "p2", name: "User", ... }]
 * ```
 */
export function useExtractIncluded<T extends object = Record<string, unknown>>(
  response: unknown,
  type: string
): T[] {
  return useMemo(() => extractIncluded<T>(response, type), [response, type])
}
