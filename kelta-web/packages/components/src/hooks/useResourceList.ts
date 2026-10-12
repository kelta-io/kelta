import { useQuery } from '@tanstack/react-query';
import type { ListOptions, PaginationMeta } from '@kelta/sdk';
import { useKeltaClient } from '../context/KeltaContext';

/**
 * Return type for useResourceList hook
 */
interface UseResourceListResult<T> {
  data: T[];
  pagination: PaginationMeta | undefined;
  isLoading: boolean;
  error: Error | null;
  refetch: () => void;
}

/**
 * Hook for fetching a list of resources
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the hook into your app if you depend on it.
 */
export function useResourceList<T = unknown>(
  resourceName: string,
  options?: ListOptions
): UseResourceListResult<T> {
  const client = useKeltaClient();
  const resource = client.resource<T>(resourceName);

  const query = useQuery({
    queryKey: ['resource', resourceName, 'list', options],
    queryFn: async () => {
      return resource.list(options);
    },
  });

  return {
    data: query.data?.data ?? [],
    pagination: query.data?.pagination,
    isLoading: query.isLoading,
    error: query.error,
    refetch: () => void query.refetch(),
  };
}
