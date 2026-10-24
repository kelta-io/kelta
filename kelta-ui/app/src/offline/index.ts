/**
 * Offline data sync (Rec 2B-2) — public surface.
 *
 * Wiring sketch (consumed by the PWA end-user shell once deployed):
 *   const store = new IndexedDbOfflineStore(`kelta-offline-${tenantId}`)
 *   const engine = new SyncEngine(store, apiClient)
 *   // read cached rows offline: store.getAll(collection)
 *   // on reconnect (useOnlineStatus → true): engine.sync([collection])
 */
export type { FailedOp, OutboxOp, ReplicaRecord } from './types'

export { InMemoryOfflineStore } from './store'

export { useOnlineStatus } from './useOnlineStatus'
export { OfflineProvider, useOffline, type OfflineContextValue } from './OfflineProvider'
export { useOfflineOutbox } from './useOfflineOutbox'
