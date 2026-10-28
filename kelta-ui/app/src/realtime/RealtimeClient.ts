/**
 * Minimal client for the gateway's /ws/realtime socket (app-surfacing slice 4).
 *
 * Server contract (RealtimeWebSocketHandler):
 * - connect wss://…/ws/realtime?token=<jwt> (close 4000 policy violation, 4001 token expired)
 * - send {"action":"subscribe"|"unsubscribe","collection":"<name>"} (max 50 subs/session)
 * - recv {"event":"record.changed","collection","changeType","recordId","data"|null,"timestamp"}
 *
 * INVALIDATION-ONLY RULE (authoritative, see conventions.md): pushed `data` is never
 * applied to caches — the server does no per-subscriber FLS, so consumers only invalidate
 * queries and refetch through the authorized JSON:API path.
 */

export interface RecordChangedEvent {
  event: 'record.changed'
  collection: string
  changeType: 'CREATE' | 'UPDATE' | 'DELETE'
  recordId: string
  timestamp?: string
}

/** Chat events (telehealth slice 3) — ids/state only, never bodies. */
interface ChatMessageEvent {
  event: 'chat.message'
  conversationId: string
  messageId?: string
  senderId?: string
  senderType?: string
  kind?: string
  timestamp?: string
}

interface ChatConversationEvent {
  event: 'chat.conversation'
  conversationId: string
  status?: string
  assignedTo?: string
  queueId?: string
  timestamp?: string
}

export type ChatEvent = ChatMessageEvent | ChatConversationEvent

/** One user present on a resource (app-intelligence slice 3). */
export interface PresenceUser {
  id: string
  email?: string
}

export type PresenceListener = (users: PresenceUser[]) => void

export interface RealtimeClientOptions {
  /** Returns a fresh connect URL (fetches a current token) — called on every (re)connect. */
  urlFactory: () => Promise<string>
  onEvent: (event: RecordChangedEvent) => void
  /** Chat events for joined conversations (invalidation signals — no bodies). */
  onChatEvent?: (event: ChatEvent) => void
  /** Injectable for tests. */
  webSocketFactory?: (url: string) => WebSocket
  /** Base reconnect delay in ms (doubles per attempt, capped at 30s). */
  baseReconnectDelayMs?: number
}

const MAX_RECONNECT_DELAY_MS = 30_000
export const MAX_SUBSCRIPTIONS = 50
/** Server cap (SubscriptionManager.MAX_CONVERSATIONS_PER_SESSION). */
const MAX_JOINED_CONVERSATIONS = 20

export class RealtimeClient {
  private readonly options: Required<Pick<RealtimeClientOptions, 'urlFactory' | 'onEvent'>> &
    RealtimeClientOptions
  private socket: WebSocket | null = null
  // collection → refcount; subscribed collections re-subscribe on every (re)connect.
  private subscriptions = new Map<string, number>()
  // resource → listeners; joined resources re-join on every (re)connect.
  private presenceListeners = new Map<string, Set<PresenceListener>>()
  // conversationId → refcount; joined conversations re-join on every (re)connect.
  private conversations = new Map<string, number>()
  private reconnectAttempts = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private closed = false

  constructor(options: RealtimeClientOptions) {
    this.options = options
  }

  /**
   * Subscribe to a collection's record.changed events. Ref-counted so the provider's nav
   * subscriptions and a page's own (useRealtimeCollection) share one server subscription:
   * the first ref sends subscribe, and a page leaving cannot drop a nav collection. Past
   * MAX_SUBSCRIPTIONS distinct collections the call is ignored (and so is its unsubscribe).
   */
  subscribe(collection: string): void {
    const count = this.subscriptions.get(collection) ?? 0
    if (count === 0) {
      if (this.subscriptions.size >= MAX_SUBSCRIPTIONS) return
      this.send({ action: 'subscribe', collection })
    }
    this.subscriptions.set(collection, count + 1)
  }

  /** Release one ref; the last one sends unsubscribe. */
  unsubscribe(collection: string): void {
    const count = this.subscriptions.get(collection) ?? 0
    if (count === 0) return
    if (count === 1) {
      this.subscriptions.delete(collection)
      this.send({ action: 'unsubscribe', collection })
    } else {
      this.subscriptions.set(collection, count - 1)
    }
  }

  /**
   * Join presence on a resource (slice 3). The first listener sends presence.join;
   * joined resources re-join automatically on every reconnect.
   */
  joinPresence(resource: string, listener: PresenceListener): void {
    let listeners = this.presenceListeners.get(resource)
    if (!listeners) {
      listeners = new Set()
      this.presenceListeners.set(resource, listeners)
      this.send({ action: 'presence.join', resource })
    }
    listeners.add(listener)
  }

  /**
   * Join a chat conversation channel (telehealth slice 3). Ref-counted so the
   * thread view and a widget can share one join; the first ref sends
   * chat.join, joined conversations re-join automatically on reconnect. The
   * server verifies membership — a denied join simply produces an error frame
   * and no events.
   */
  joinConversation(conversationId: string): void {
    const count = this.conversations.get(conversationId) ?? 0
    if (count === 0) {
      if (this.conversations.size >= MAX_JOINED_CONVERSATIONS) return
      this.send({ action: 'chat.join', conversationId })
    }
    this.conversations.set(conversationId, count + 1)
  }

  leaveConversation(conversationId: string): void {
    const count = this.conversations.get(conversationId) ?? 0
    if (count <= 1) {
      this.conversations.delete(conversationId)
      this.send({ action: 'chat.leave', conversationId })
    } else {
      this.conversations.set(conversationId, count - 1)
    }
  }

  /** Remove a listener; the last one sends presence.leave. */
  leavePresence(resource: string, listener: PresenceListener): void {
    const listeners = this.presenceListeners.get(resource)
    if (!listeners) return
    listeners.delete(listener)
    if (listeners.size === 0) {
      this.presenceListeners.delete(resource)
      this.send({ action: 'presence.leave', resource })
    }
  }

  async connect(): Promise<void> {
    if (this.closed || this.socket) return
    let url: string
    try {
      url = await this.options.urlFactory()
    } catch {
      this.scheduleReconnect()
      return
    }
    const factory = this.options.webSocketFactory ?? ((u: string) => new WebSocket(u))
    let socket: WebSocket
    try {
      socket = factory(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket = socket

    socket.onopen = () => {
      this.reconnectAttempts = 0
      // Re-establish every registered subscription on each (re)connect.
      for (const collection of this.subscriptions.keys()) {
        socket.send(JSON.stringify({ action: 'subscribe', collection }))
      }
      for (const resource of this.presenceListeners.keys()) {
        socket.send(JSON.stringify({ action: 'presence.join', resource }))
      }
      for (const conversationId of this.conversations.keys()) {
        socket.send(JSON.stringify({ action: 'chat.join', conversationId }))
      }
    }
    socket.onmessage = (message: MessageEvent) => {
      try {
        const parsed = JSON.parse(String(message.data))
        if (parsed?.event === 'record.changed' && typeof parsed.collection === 'string') {
          this.options.onEvent(parsed as RecordChangedEvent)
        }
        if (parsed?.event === 'presence.changed' && typeof parsed.resource === 'string') {
          const listeners = this.presenceListeners.get(parsed.resource)
          if (listeners) {
            const users = Array.isArray(parsed.users) ? (parsed.users as PresenceUser[]) : []
            for (const listener of listeners) listener(users)
          }
        }
        if (
          (parsed?.event === 'chat.message' || parsed?.event === 'chat.conversation') &&
          typeof parsed.conversationId === 'string'
        ) {
          this.options.onChatEvent?.(parsed as ChatEvent)
        }
      } catch {
        // Non-JSON or unknown frame — ignore.
      }
    }
    socket.onclose = () => {
      this.socket = null
      // 4001 (token expired) needs no special casing: urlFactory fetches a fresh
      // token on every reconnect.
      this.scheduleReconnect()
    }
    socket.onerror = () => {
      // onclose follows; nothing to do here.
    }
  }

  close(): void {
    this.closed = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.socket?.close()
    this.socket = null
  }

  private send(payload: Record<string, string>): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(payload))
    }
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer) return
    const base = this.options.baseReconnectDelayMs ?? 1000
    const delay = Math.min(base * 2 ** this.reconnectAttempts, MAX_RECONNECT_DELAY_MS)
    this.reconnectAttempts += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      void this.connect()
    }, delay)
  }
}
