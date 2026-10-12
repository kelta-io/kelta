import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useKeltaClient } from '../context/KeltaContext';

/**
 * Hook for fetching and mutating a single resource
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the hook into your app if you depend on it.
 */
export function useResource<T = unknown>(resourceName: string, recordId?: string) {
  const client = useKeltaClient();
  const queryClient = useQueryClient();
  const resource = client.resource<T>(resourceName);

  // Fetch single resource
  const query = useQuery({
    queryKey: ['resource', resourceName, recordId],
    queryFn: async () => {
      if (!recordId) return null;
      return resource.get(recordId);
    },
    enabled: !!recordId,
  });

  // Create mutation
  const createMutation = useMutation({
    mutationFn: async (data: Partial<T>) => {
      return resource.create(data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['resource', resourceName] });
    },
  });

  // Update mutation
  const updateMutation = useMutation({
    mutationFn: async ({ id, data }: { id: string; data: T }) => {
      return resource.update(id, data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['resource', resourceName] });
    },
  });

  // Patch mutation
  const patchMutation = useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Partial<T> }) => {
      return resource.patch(id, data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['resource', resourceName] });
    },
  });

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: async (id: string) => {
      return resource.delete(id);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['resource', resourceName] });
    },
  });

  return {
    data: query.data,
    isLoading: query.isLoading,
    error: query.error,
    refetch: query.refetch,
    create: createMutation.mutateAsync,
    update: updateMutation.mutateAsync,
    patch: patchMutation.mutateAsync,
    delete: deleteMutation.mutateAsync,
    isCreating: createMutation.isPending,
    isUpdating: updateMutation.isPending,
    isPatching: patchMutation.isPending,
    isDeleting: deleteMutation.isPending,
  };
}
