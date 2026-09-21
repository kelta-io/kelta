---
title: Realtime, presence and offline
description: Live updates over WebSocket, who-is-viewing presence, the installable PWA with offline reads and a write outbox, and the native shells.
section: console
order: 100
status: partial
---

## Realtime

The app opens one WebSocket to the API origin (`/ws/realtime`) and subscribes to the collections in the current
navigation and to approvals. When a record changes anywhere in the fleet, the app receives an event and
**refetches** the affected queries through the API — pushed data never enters the cache, so field-level security
and masking are always the server's. Reconnection uses a fresh token with backoff and resubscribes automatically.
Protocol details: [Realtime WebSocket](/docs/api/realtime/).

## Presence

Open a record and the header shows the avatars of everyone else viewing it, updated live as people arrive and
leave (30-second heartbeat, 90-second expiry). Presence is fleet-wide across gateway pods.

## Installable PWA

The app is a Progressive Web App: install it from the browser, and the shell is precached so it starts offline.
API calls are never cached by the service worker.

## Offline data

In the end-user app a per-tenant IndexedDB replica keeps recently used records:

- **Reads** of cached lists and records work offline (list filtering and sorting run client-side over the cached
  rows, so results are best-effort).
- **Writes** queue in an outbox and replay in order on reconnect. Creates get a temporary id that is swapped for
  the server id; a `4xx` rejection is kept in a *failed* list with retry/discard controls; a `5xx` or network error
  stops the queue and retries.
- **Sync** uses the server's `_changes` cursor for deletions and `updatedAt` for upserts; conflicts resolve
  server-wins, client-wins or last-write-wins per collection policy.
- Published custom pages are cached for cold-offline viewing.

An offline banner appears in the shell when connectivity drops. Admin pages are online-only.

## Native shells

The same bundle can be wrapped in a Capacitor iOS/Android shell; native push registration reuses the platform's
push providers. See the repository's `kelta-ui/app/NATIVE.md` and [Messaging](/docs/platform/messaging/).

## Status

Realtime, presence and the PWA shell are complete. Offline data sync is functional but its end-to-end tests run
against a live environment after deployment, and list reads offline remain best-effort — treat offline as
resilience for brief disconnections rather than a full offline-first mode.
