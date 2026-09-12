/*
 * Kelta console service worker.
 *
 * Deliberately does NOT precache or intercept fetches. An admin console that serves a stale
 * shell after a deploy is worse than one with no offline mode, and nothing here needs offline.
 * This worker exists for exactly one reason: without a service worker there is no Web Push,
 * and without Web Push a support-mailbox escalation can only reach an agent by email — which
 * Gmail files under "Updates" and nobody sees for nine days.
 *
 * Served from /sw.js so its scope is the whole origin and covers every /{tenantSlug}/ route.
 */

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('push', (event) => {
  let title = 'Kelta'
  let body = ''
  let data = {}
  if (event.data) {
    try {
      const payload = event.data.json()
      title = payload.title || title
      body = payload.body || body
      data = payload.data || {}
    } catch {
      body = event.data.text() || body
    }
  }
  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      icon: '/pwa-192x192.png',
      badge: '/pwa-192x192.png',
      // One notification per thread: a WARN followed by a BREACH replaces rather than stacks.
      tag: data.threadId ? 'thread:' + data.threadId : undefined,
      renotify: !!data.threadId,
      data,
    })
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = event.notification.data && event.notification.data.url
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      // Prefer navigating an open console tab; otherwise open one. A page at 3am has no tab.
      for (const client of clients) {
        if ('focus' in client) {
          if (url && 'navigate' in client) return client.navigate(url).then((c) => c && c.focus())
          return client.focus()
        }
      }
      return self.clients.openWindow(url || '/')
    })
  )
})
