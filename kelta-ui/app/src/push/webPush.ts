/**
 * Web Push registration for the console.
 *
 * The native path (`deviceRegistration.ts`) registers an APNs/FCM token through Capacitor and is
 * a no-op in a browser — so until this existed, nothing in the console could ever ask a browser
 * for notification permission, and a support-mailbox escalation could reach an agent only by
 * email. This is the browser half: service worker → VAPID key → permission → subscribe → register.
 *
 * Mirrors the working flow in spotopened-web's `push.ts` against the same platform endpoints
 * (`GET /api/devices/vapid-public-key`, `POST /api/devices`), which already deliver alert pushes.
 *
 * Every step here must be reached from a user gesture: browsers refuse `requestPermission()`
 * outside one, and iOS additionally refuses it unless the site is installed to the Home Screen.
 */

/** Minimal slice of `ApiClient` this module needs (structurally satisfied by `ApiClient`). */
export interface WebPushApi {
  get<T = unknown>(url: string): Promise<T>
  post<T = unknown>(url: string, data?: unknown): Promise<T>
}

/** Where this browser stands, before anything is attempted. */
export type WebPushStatus =
  /** No service worker or PushManager — nothing can be done. */
  | 'unsupported'
  /** iOS in a normal browser tab: push exists only for Home Screen web apps. */
  | 'needs-install'
  /** The user has already refused; the browser will not ask again. */
  | 'denied'
  /** Permission granted (a subscription may or may not exist yet). */
  | 'granted'
  /** Never asked. */
  | 'default'

export type EnableResult =
  | 'subscribed'
  | 'denied'
  | 'unsupported'
  | 'needs-install'
  | 'not-configured'

const SERVICE_WORKER_URL = '/sw.js'

/** `Window` with the Notification constructor, which lib.dom types as a global not a property. */
type PushWindow = Window & { Notification: typeof Notification }

/** Reads the current state without prompting. Safe to call on render. */
export function webPushStatus(w: Window = window): WebPushStatus {
  const nav = w.navigator
  if (!('serviceWorker' in nav) || !('PushManager' in w) || !('Notification' in w)) {
    return 'unsupported'
  }
  if (isIos(nav) && !isStandalone(w)) {
    return 'needs-install'
  }
  const permission = (w as PushWindow).Notification.permission
  if (permission === 'denied') return 'denied'
  if (permission === 'granted') return 'granted'
  return 'default'
}

/**
 * Subscribes this browser and registers the subscription with the platform.
 *
 * Idempotent: an existing subscription is reused and re-registered, which is also how a device
 * whose registration was lost server-side heals itself.
 */
export async function enableWebPush(api: WebPushApi, w: Window = window): Promise<EnableResult> {
  const status = webPushStatus(w)
  if (status === 'unsupported' || status === 'needs-install' || status === 'denied') {
    return status
  }

  const key = await vapidPublicKey(api)
  if (!key) return 'not-configured'

  // Registration must precede the permission prompt on some browsers, and `ready` waits for
  // activation so `pushManager` is usable.
  await w.navigator.serviceWorker.register(SERVICE_WORKER_URL)
  const registration = await w.navigator.serviceWorker.ready

  const permission = await (w as PushWindow).Notification.requestPermission()
  if (permission !== 'granted') return 'denied'

  const existing = await registration.pushManager.getSubscription()
  const subscription =
    existing ??
    (await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(key),
    }))

  // The platform stores the subscription as a JSON string and keys the device by a hash of its
  // endpoint, so a re-registration of the same browser updates rather than duplicates.
  await api.post('/api/devices', {
    platform: 'web',
    subscription: JSON.stringify(subscription.toJSON()),
    deviceName: deviceName(nav(w)),
  })
  return 'subscribed'
}

async function vapidPublicKey(api: WebPushApi): Promise<string | null> {
  try {
    const body = await api.get<{ data?: { publicKey?: string } }>('/api/devices/vapid-public-key')
    return body?.data?.publicKey ?? null
  } catch {
    // 404 means the platform has no VAPID keys configured; anything else is equally "not now".
    return null
  }
}

/** VAPID keys are base64url; PushManager wants the raw bytes. */
function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4)
  const b64 = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(b64)
  const out = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

function nav(w: Window): Navigator {
  return w.navigator
}

function isIos(n: Navigator): boolean {
  // iPadOS reports as Macintosh with touch points; the UA alone misses it.
  return /iPhone|iPad|iPod/.test(n.userAgent) || (n.platform === 'MacIntel' && n.maxTouchPoints > 1)
}

function isStandalone(w: Window): boolean {
  const legacy = (w.navigator as Navigator & { standalone?: boolean }).standalone === true
  return (
    legacy ||
    (typeof w.matchMedia === 'function' && w.matchMedia('(display-mode: standalone)').matches)
  )
}

function deviceName(n: Navigator): string {
  const ua = n.userAgent
  const os = /iPhone/.test(ua)
    ? 'iPhone'
    : /iPad/.test(ua)
      ? 'iPad'
      : /Android/.test(ua)
        ? 'Android'
        : /Mac/.test(ua)
          ? 'Mac'
          : /Windows/.test(ua)
            ? 'Windows'
            : 'Browser'
  return `${os} (web)`
}
