import { useQuery } from '@tanstack/react-query';
import { useKeltaClient } from '../context/KeltaContext';

/**
 * Hook for discovering available resources
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the hook into your app if you depend on it.
 */
export function useDiscovery() {
  const client = useKeltaClient();

  const query = useQuery({
    queryKey: ['discovery'],
    queryFn: async () => {
      return client.discover();
    },
  });

  return {
    resources: query.data ?? [],
    isLoading: query.isLoading,
    error: query.error,
    refetch: query.refetch,
  };
}
