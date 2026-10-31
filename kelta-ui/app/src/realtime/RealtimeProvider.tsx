import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/context/AuthContext'
import { useConfig } from '@/context/ConfigContext'
import { useOnlineStatus } from '@/offline/useOnlineStatus'
import { buildNavTabs, navCollections } from '@/shells/EndUserShell/navTabs'
import {
  MAX_SUBSCRIPTIONS,
  RealtimeClient,
  type ChatEvent,
  type RecordChangedEvent,
} from './RealtimeClient'
import { chatQueryKeysForEvent, queryKeysForEvent } from './invalidation'

const DEBOUNCE_MS = 250
/** Subscriptions kept free for the open page's own collection (useRealtimeCollection). */
const PAGE_SUBSCRIPTION_HEADROOM = 5
const APPROVAL_COLLECTIONS = ['approval-instances', 'approval-step-instances']

/**
 * Builds the wss:// connect URL against the gateway origin with a fresh token.
 * The socket lives on the gateway (VITE_API_BASE_URL in production) — the app origin
 * serves only static assets and cannot upgrade WebSockets. Falls back to the page
 * origin for local dev, mirroring resolveMcpBaseUrl.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function buildSocketUrl(token: string): string {
  const apiBase = import.meta.env.VITE_API_BASE_URL || ''
  const url = new URL('/ws/realtime', apiBase || window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.searchParams.set('token', token)
  return url.toString()
}

/**
 * Mounts one realtime socket for the end-user shell and turns record.changed events into
 * React Query invalidations (INVALIDATION-ONLY — pushed data never enters caches; the
 * server does no per-subscriber FLS). Subscribes to the approval collections plus every nav
 * collection (grouped ones included); pages add their own collection via
 * useRealtimeCollection. Bursts are debounced per collection. Offline → socket closed; the
 * offline SyncEngine owns reconnect data sync, this provider just reconnects the socket.
 */
/** Exposes the live socket client so hooks (usePresence) can join resources. */
const RealtimeContext = createContext<{ client: RealtimeClient | null }>({ client: null })

// eslint-disable-next-line react-refresh/only-export-components
export function useRealtimeClient(): RealtimeClient | null {
  return useContext(RealtimeContext).client
}

export function RealtimeProvider({ children }: { children: React.ReactNode }) {
  const { getAccessToken, isAuthenticated } = useAuth()
  const { config } = useConfig()
  const online = useOnlineStatus()
  const queryClient = useQueryClient()
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map())
  const [activeClient, setActiveClient] = useState<RealtimeClient | null>(null)

  const collections = useMemo(() => {
    const set = new Set<string>([
      ...APPROVAL_COLLECTIONS,
      ...navCollections(buildNavTabs(config?.menus)),
    ])
    return [...set].slice(0, MAX_SUBSCRIPTIONS - PAGE_SUBSCRIPTION_HEADROOM)
  }, [config?.menus])

  useEffect(() => {
    if (!isAuthenticated || !online || collections.length === 0) return

    const timers = timersRef.current
    const handleEvent = (event: RecordChangedEvent) => {
      // Debounce per collection: one invalidation sweep per burst.
      const existing = timers.get(event.collection)
      if (existing) clearTimeout(existing)
      timers.set(
        event.collection,
        setTimeout(() => {
          timers.delete(event.collection)
          for (const key of queryKeysForEvent(event)) {
            void queryClient.invalidateQueries({ queryKey: key })
          }
        }, DEBOUNCE_MS)
      )
    }

    const handleChatEvent = (event: ChatEvent) => {
      // Debounce per conversation, mirroring the per-collection debounce.
      const debounceKey = `chat:${event.conversationId}`
      const existing = timers.get(debounceKey)
      if (existing) clearTimeout(existing)
      timers.set(
        debounceKey,
        setTimeout(() => {
          timers.delete(debounceKey)
          for (const key of chatQueryKeysForEvent(event)) {
            void queryClient.invalidateQueries({ queryKey: key })
          }
        }, DEBOUNCE_MS)
      )
    }

    const client = new RealtimeClient({
      urlFactory: async () => buildSocketUrl(await getAccessToken()),
      onEvent: handleEvent,
      onChatEvent: handleChatEvent,
    })
    for (const collection of collections) {
      client.subscribe(collection)
    }
    void client.connect()
    // Deferred so no setState runs synchronously inside the effect (compiler rule).
    const exposeTimer = setTimeout(() => setActiveClient(client), 0)

    return () => {
      clearTimeout(exposeTimer)
      setActiveClient(null)
      client.close()
      for (const timer of timers.values()) clearTimeout(timer)
      timers.clear()
    }
  }, [isAuthenticated, online, collections, getAccessToken, queryClient])

  const contextValue = useMemo(() => ({ client: activeClient }), [activeClient])

  return <RealtimeContext.Provider value={contextValue}>{children}</RealtimeContext.Provider>
}
