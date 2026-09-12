/*
 * Push handlers for the Kelta console service worker.
 *
 * NOT a service worker of its own. vite-plugin-pwa generates /sw.js (Workbox precache, auto
 * update) and imports this file into it via workbox.importScripts — a hand-written public/sw.js
 * is silently overwritten by the build, which is how the first attempt shipped a worker with no
 * push handler at all. No install/activate listeners here: the generated worker owns lifecycle.
 *
 * This exists for one reason: without a push handler there is no Web Push, and without Web Push
 * a support-mailbox escalation can only reach an agent by email — which Gmail files under
 * "Updates" and nobody sees for nine days.
 */

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
