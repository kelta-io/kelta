import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  MAX_SUBSCRIPTIONS,
  RealtimeClient,
  type ChatEvent,
  type PresenceUser,
  type RecordChangedEvent,
} from './RealtimeClient'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static OPEN = 1
  readyState = 0
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null

  url: string

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(data: string) {
    this.sent.push(data)
  }

  close() {
    this.readyState = 3
    this.onclose?.()
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }
}

function makeClient(events: RecordChangedEvent[]) {
  return new RealtimeClient({
    urlFactory: vi.fn(async () => `wss://x/ws/realtime?token=t${FakeWebSocket.instances.length}`),
    onEvent: (e) => events.push(e),
    webSocketFactory: (url) => new FakeWebSocket(url) as unknown as WebSocket,
    baseReconnectDelayMs: 10,
  })
}

beforeEach(() => {
  FakeWebSocket.instances = []
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

describe('RealtimeClient', () => {
  it('resubscribes every registered collection on open', async () => {
    const client = makeClient([])
    client.subscribe('orders')
    client.subscribe('customers')
    await client.connect()
    const ws = FakeWebSocket.instances[0]
    ws.open()

    expect(ws.sent.map((s) => JSON.parse(s))).toEqual([
      { action: 'subscribe', collection: 'orders' },
      { action: 'subscribe', collection: 'customers' },
    ])
    client.close()
  })

  it('delivers record.changed events and ignores other frames', async () => {
    const events: RecordChangedEvent[] = []
    const client = makeClient(events)
    client.subscribe('orders')
    await client.connect()
    const ws = FakeWebSocket.instances[0]
    ws.open()

    ws.onmessage?.({ data: JSON.stringify({ action: 'subscribed', collection: 'orders' }) })
    ws.onmessage?.({ data: 'not-json' })
    ws.onmessage?.({
      data: JSON.stringify({
        event: 'record.changed',
        collection: 'orders',
        changeType: 'UPDATE',
        recordId: 'r1',
      }),
    })

    expect(events).toHaveLength(1)
    expect(events[0].collection).toBe('orders')
    client.close()
  })

  it('reconnects with a fresh URL after a close and resubscribes', async () => {
    const client = makeClient([])
    client.subscribe('orders')
    await client.connect()
    FakeWebSocket.instances[0].open()
    FakeWebSocket.instances[0].close()

    await vi.advanceTimersByTimeAsync(50)
    expect(FakeWebSocket.instances).toHaveLength(2)
    // urlFactory ran again → different token per connect
    expect(FakeWebSocket.instances[1].url).not.toBe(FakeWebSocket.instances[0].url)
    FakeWebSocket.instances[1].open()
    expect(FakeWebSocket.instances[1].sent.map((s) => JSON.parse(s))).toEqual([
      { action: 'subscribe', collection: 'orders' },
    ])
    client.close()
  })

  it('stops reconnecting after close()', async () => {
    const client = makeClient([])
    await client.connect()
    client.close()
    await vi.advanceTimersByTimeAsync(1000)
    expect(FakeWebSocket.instances).toHaveLength(1)
  })

  it('ref-counts collection subscriptions: one subscribe, unsubscribe on the last release', async () => {
    const client = makeClient([])
    await client.connect()
    const ws = FakeWebSocket.instances[0]
    ws.open()
    const actions = () => ws.sent.map((s) => JSON.parse(s))

    client.subscribe('orders') // the nav's ref
    client.subscribe('orders') // a page's ref
    expect(actions()).toEqual([{ action: 'subscribe', collection: 'orders' }])

    client.unsubscribe('orders') // the page leaves — the nav still holds it
    expect(actions()).toHaveLength(1)

    client.unsubscribe('orders')
    expect(actions()).toEqual([
      { action: 'subscribe', collection: 'orders' },
      { action: 'unsubscribe', collection: 'orders' },
    ])
    client.unsubscribe('orders') // over-release is a no-op
    expect(actions()).toHaveLength(2)
    client.close()
  })

  it('ignores subscriptions past MAX_SUBSCRIPTIONS distinct collections', async () => {
    const client = makeClient([])
    await client.connect()
    const ws = FakeWebSocket.instances[0]
    ws.open()

    for (let i = 0; i <= MAX_SUBSCRIPTIONS; i += 1) client.subscribe(`c${i}`)
    client.unsubscribe(`c${MAX_SUBSCRIPTIONS}`)
    const sent = ws.sent.map((s) => JSON.parse(s))
    expect(sent.filter((m) => m.action === 'subscribe')).toHaveLength(MAX_SUBSCRIPTIONS)
    expect(sent.filter((m) => m.action === 'unsubscribe')).toHaveLength(0)
    client.close()
  })

  describe('presence (app-intelligence slice 3)', () => {
    it('joins on the first listener, dispatches snapshots, leaves on the last', async () => {
      const client = makeClient([])
      await client.connect()
      const ws = FakeWebSocket.instances[0]
      ws.open()

      const seen: PresenceUser[][] = []
      const listener = (users: PresenceUser[]) => seen.push(users)
      client.joinPresence('record:orders/1', listener)
      expect(ws.sent.map((m) => JSON.parse(m))).toContainEqual({
        action: 'presence.join',
        resource: 'record:orders/1',
      })

      ws.onmessage?.({
        data: JSON.stringify({
          event: 'presence.changed',
          resource: 'record:orders/1',
          users: [{ id: 'u1', email: 'a@x.com' }],
        }),
      })
      expect(seen).toEqual([[{ id: 'u1', email: 'a@x.com' }]])

      client.leavePresence('record:orders/1', listener)
      expect(ws.sent.map((m) => JSON.parse(m))).toContainEqual({
        action: 'presence.leave',
        resource: 'record:orders/1',
      })
    })

    it('re-joins presence resources on reconnect', async () => {
      const client = makeClient([])
      client.joinPresence('record:orders/1', () => {})
      await client.connect()
      const first = FakeWebSocket.instances[0]
      first.open()
      expect(first.sent.map((m) => JSON.parse(m))).toContainEqual({
        action: 'presence.join',
        resource: 'record:orders/1',
      })

      first.close() // triggers reconnect
      await vi.advanceTimersByTimeAsync(50)
      const second = FakeWebSocket.instances[1]
      second.open()
      expect(second.sent.map((m) => JSON.parse(m))).toContainEqual({
        action: 'presence.join',
        resource: 'record:orders/1',
      })
    })

    it('snapshots for other resources do not reach a listener', async () => {
      const client = makeClient([])
      await client.connect()
      const ws = FakeWebSocket.instances[0]
      ws.open()
      const seen: PresenceUser[][] = []
      client.joinPresence('record:orders/1', (u) => seen.push(u))
      ws.onmessage?.({
        data: JSON.stringify({
          event: 'presence.changed',
          resource: 'record:orders/2',
          users: [{ id: 'u9' }],
        }),
      })
      expect(seen).toEqual([])
    })
  })

  describe('chat conversations (telehealth slice 3)', () => {
    function makeChatClient(chatEvents: ChatEvent[]) {
      return new RealtimeClient({
        urlFactory: vi.fn(async () => 'wss://x/ws/realtime?token=t'),
        onEvent: () => {},
        onChatEvent: (e) => chatEvents.push(e),
        webSocketFactory: (url) => new FakeWebSocket(url) as unknown as WebSocket,
        baseReconnectDelayMs: 10,
      })
    }

    it('joins once per conversation (ref-counted) and leaves on the last ref', async () => {
      const client = makeChatClient([])
      await client.connect()
      const ws = FakeWebSocket.instances[0]
      ws.open()

      client.joinConversation('conv-1')
      client.joinConversation('conv-1')
      expect(
        ws.sent.map((s) => JSON.parse(s)).filter((m) => m.action === 'chat.join')
      ).toHaveLength(1)

      client.leaveConversation('conv-1')
      expect(
        ws.sent.map((s) => JSON.parse(s)).filter((m) => m.action === 'chat.leave')
      ).toHaveLength(0)
      client.leaveConversation('conv-1')
      expect(
        ws.sent.map((s) => JSON.parse(s)).filter((m) => m.action === 'chat.leave')
      ).toHaveLength(1)
      client.close()
    })

    it('re-joins conversations on reconnect and dispatches chat events', async () => {
      const chatEvents: ChatEvent[] = []
      const client = makeChatClient(chatEvents)
      await client.connect()
      const first = FakeWebSocket.instances[0]
      first.open()
      client.joinConversation('conv-9')

      first.onmessage?.({
        data: JSON.stringify({
          event: 'chat.message',
          conversationId: 'conv-9',
          messageId: 'm1',
          kind: 'TEXT',
        }),
      })
      expect(chatEvents).toHaveLength(1)
      expect(chatEvents[0].conversationId).toBe('conv-9')

      first.close() // triggers reconnect
      await vi.advanceTimersByTimeAsync(50)
      const second = FakeWebSocket.instances[1]
      second.open()
      expect(second.sent.map((m) => JSON.parse(m))).toContainEqual({
        action: 'chat.join',
        conversationId: 'conv-9',
      })
      client.close()
    })
  })
})
