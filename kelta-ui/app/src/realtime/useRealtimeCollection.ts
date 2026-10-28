/**
 * useRealtimeCollection: keeps the realtime socket subscribed to a collection while the
 * calling view is mounted, so its record.changed events invalidate that view's queries even
 * when the collection is not in the nav menu. Ref-counted in RealtimeClient, so leaving the
 * view never drops a subscription the provider holds for the nav. No-op when no realtime
 * client is mounted (admin shell) or the collection is empty.
 */
import { useEffect } from 'react'
import { useRealtimeClient } from './RealtimeProvider'

export function useRealtimeCollection(collection: string | null | undefined): void {
  const client = useRealtimeClient()

  useEffect(() => {
    if (!client || !collection) return
    client.subscribe(collection)
    return () => client.unsubscribe(collection)
  }, [client, collection])
}
