import { useQuery } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'
import type { SavedView } from './useSavedViews'
import { mapSharedListView, type ListViewRow } from '../components/record/list/listViewMapping'

/**
 * Admin-authored shared list views for a collection: `list-views` rows with
 * visibility PUBLIC, mapped read-only into the SavedView shape (`shared:` id prefix).
 * `collectionId` is the collection UUID (schema.id) — the row's master-detail key.
 */
export function useSharedListViews(
  collectionName: string | undefined,
  collectionId: string | undefined
): { sharedViews: SavedView[] } {
  const { apiClient } = useApi()
  const { data } = useQuery({
    queryKey: ['shared-list-views', collectionId],
    enabled: !!collectionId && !!collectionName,
    staleTime: 5 * 60 * 1000,
    queryFn: async () => {
      try {
        const rows = await apiClient.getList<ListViewRow>(
          `/api/list-views?filter[collectionId][eq]=${encodeURIComponent(collectionId!)}` +
            `&filter[visibility][eq]=PUBLIC&page[size]=50`
        )
        return (rows ?? []).map((row) => mapSharedListView(row, collectionName!))
      } catch {
        return []
      }
    },
  })
  return { sharedViews: data ?? [] }
}
