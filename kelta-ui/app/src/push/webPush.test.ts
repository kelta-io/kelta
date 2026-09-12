import { describe, it, expect, vi } from 'vitest'
import { enableWebPush, webPushStatus, type WebPushApi } from './webPush'

/**
 * Builds a fake `window` with exactly the push surface the module reads. Everything is opt-in so
 * each test states which capability it is removing.
 */
function fakeWindow(opts: {
  permission?: NotificationPermission
  requestResult?: NotificationPermission
  userAgent?: string
  standalone?: boolean
  noServiceWorker?: boolean
  noPushManager?: boolean
  existingSubscription?: boolean
}) {
  const subscription = {
    toJSON: () => ({ endpoint: 'https://push.example/abc', keys: { p256dh: 'k', auth: 'a' } }),
  }
  const pushManager = {
    getSubscription: vi.fn(async () => (opts.existingSubscription ? subscription : null)),
    subscribe: vi.fn(async () => subscription),
  }
  const registration = { pushManager }
  const serviceWorker = {
    register: vi.fn(async () => registration),
    ready: Promise.resolve(registration),
  }
  const Notification = {
    permission: opts.permission ?? 'default',
    requestPermission: vi.fn(async () => opts.requestResult ?? 'granted'),
  }
  const navigator: Record<string, unknown> = {
    userAgent: opts.userAgent ?? 'Mozilla/5.0 (Macintosh) Chrome/120',
    platform: 'MacIntel',
    maxTouchPoints: 0,
    standalone: opts.standalone,
  }
  if (!opts.noServiceWorker) navigator.serviceWorker = serviceWorker
  const w: Record<string, unknown> = {
    navigator,
    Notification,
    matchMedia: () => ({ matches: !!opts.standalone }),
  }
  if (!opts.noPushManager) w.PushManager = function PushManager() {}
  return { w: w as unknown as Window, pushManager, serviceWorker, Notification }
}

type FakeApi = WebPushApi & { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn> }

function fakeApi(vapid: string | null = 'BAbc-def_ghi'): FakeApi {
  const get = vi.fn(async () =>
    vapid ? { data: { publicKey: vapid } } : Promise.reject(new Error('404'))
  )
  const post = vi.fn(async () => ({}))
  return { get, post } as unknown as FakeApi
}

describe('webPushStatus', () => {
  it('is unsupported without a service worker or PushManager', () => {
    expect(webPushStatus(fakeWindow({ noServiceWorker: true }).w)).toBe('unsupported')
    expect(webPushStatus(fakeWindow({ noPushManager: true }).w)).toBe('unsupported')
  })

  it('reports needs-install on iOS in a browser tab, and not once installed', () => {
    // The case that started this: Chrome on an iPhone, never prompted, and nothing said why.
    const ios = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) CriOS/120'
    expect(webPushStatus(fakeWindow({ userAgent: ios }).w)).toBe('needs-install')
    expect(webPushStatus(fakeWindow({ userAgent: ios, standalone: true }).w)).toBe('default')
  })

  it('reflects an existing permission decision', () => {
    expect(webPushStatus(fakeWindow({ permission: 'denied' }).w)).toBe('denied')
    expect(webPushStatus(fakeWindow({ permission: 'granted' }).w)).toBe('granted')
  })
})

describe('enableWebPush', () => {
  it('registers the worker, asks, subscribes, and posts the subscription as a JSON string', async () => {
    const { w, pushManager, serviceWorker, Notification } = fakeWindow({})
    const api = fakeApi()

    await expect(enableWebPush(api, w)).resolves.toBe('subscribed')

    expect(serviceWorker.register).toHaveBeenCalledWith('/sw.js')
    expect(Notification.requestPermission).toHaveBeenCalled()
    expect(pushManager.subscribe).toHaveBeenCalledWith(
      expect.objectContaining({ userVisibleOnly: true })
    )
    // The platform endpoint takes Map<String,String>: the subscription must arrive as a string,
    // not a nested object, or deserialisation fails before the handler runs.
    const body = api.post.mock.calls[0][1] as { platform: string; subscription: string }
    expect(api.post.mock.calls[0][0]).toBe('/api/devices')
    expect(body.platform).toBe('web')
    expect(typeof body.subscription).toBe('string')
    expect(JSON.parse(body.subscription).endpoint).toBe('https://push.example/abc')
  })

  it('reuses an existing subscription rather than creating a second', async () => {
    const { w, pushManager } = fakeWindow({ existingSubscription: true, permission: 'granted' })
    const api = fakeApi()

    await expect(enableWebPush(api, w)).resolves.toBe('subscribed')

    expect(pushManager.subscribe).not.toHaveBeenCalled()
    expect(api.post).toHaveBeenCalledTimes(1)
  })

  it('stops before prompting when the platform has no VAPID key', async () => {
    const { w, Notification } = fakeWindow({})

    await expect(enableWebPush(fakeApi(null), w)).resolves.toBe('not-configured')

    // Asking for permission we could never use burns the one prompt the browser allows.
    expect(Notification.requestPermission).not.toHaveBeenCalled()
  })

  it('reports denied when the user refuses, and posts nothing', async () => {
    const { w } = fakeWindow({ requestResult: 'denied' })
    const api = fakeApi()

    await expect(enableWebPush(api, w)).resolves.toBe('denied')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('returns the blocking status without touching the network', async () => {
    const api = fakeApi()
    const ios = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1'

    await expect(enableWebPush(api, fakeWindow({ userAgent: ios }).w)).resolves.toBe(
      'needs-install'
    )
    await expect(enableWebPush(api, fakeWindow({ noPushManager: true }).w)).resolves.toBe(
      'unsupported'
    )
    expect(api.get).not.toHaveBeenCalled()
  })
})
