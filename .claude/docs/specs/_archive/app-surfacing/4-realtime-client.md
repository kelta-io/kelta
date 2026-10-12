# Slice 4 — Realtime Client

> Child spec of [App Surfacing (Phase 1)](./README.md), authored with the implementation
> (same PR). Conforms to the parent's
> [Realtime client protocol](./README.md#realtime-client-protocol-existing-server-contract--slice-4-consumes)
> and its **invalidation-only rule**. Frontend-only; not security-typed.

## 1. Goal & scope

First consumer of the gateway's `/ws/realtime` socket (zero FE references existed).
One socket per end-user session; `record.changed` events become React Query invalidations so
every list, record detail, related list, and approval surface refetches through the
authorized JSON:API path. **Not delivered:** applying pushed `data` to caches (forbidden —
no per-subscriber FLS server-side), admin-shell wiring, per-page subscription registration
(v1 subscribes to the nav collections + approval collections), realtime e2e (needs two live
sessions; manual smoke documented below).

## 2. UI samples

None — no visible UI. Observable behavior: a record edited in session B updates session A's
open list/detail within ~1s (invalidate → refetch) without a manual refresh; the approvals
bell count updates when a step instance changes.

## 3. Data & API contracts

Server contract consumed verbatim (parent §Shared contracts): connect
`wss://<gateway-origin>/ws/realtime?token=<jwt>` — gateway origin from `VITE_API_BASE_URL`,
page-origin fallback for local dev (in production the page origin serves only static assets
and cannot upgrade WebSockets) — (fresh token via `AuthContext.getAccessToken()` on
every (re)connect — covers close 4001), `{"action":"subscribe","collection"}` frames, max 50
subscriptions. Client modules:

- `src/realtime/RealtimeClient.ts` — dependency-free socket wrapper: subscription registry
  (resent on every open), exponential backoff reconnect (1s→30s cap), injectable
  `webSocketFactory` for tests, `close()` halts reconnection.
- `src/realtime/invalidation.ts` — pure `queryKeysForEvent(event)`: `['collection-records',
  c]`, `['related-records', c]`, `['record', c]`; approval collections additionally
  `['activity-approvals']`, `['my-approvals']`, `['record-approval-state']`. Key strings are
  deliberate literals (prefix invalidation; no imports from feature hooks — loose coupling
  to the slice-2 PR).
- `src/realtime/RealtimeProvider.tsx` — mounted inside `EndUserShell` (within
  `OfflineProvider`): subscribes to nav collections (`buildNavTabs`) + the two approval
  collections (capped 50); 250ms per-collection debounce; gated on `isAuthenticated` +
  `useOnlineStatus()` (offline → socket closed; the offline `SyncEngine` owns reconnect
  data sync).

## 4. DB migrations

None.

## 5. File-by-file code changes

`src/realtime/{RealtimeClient,invalidation,RealtimeProvider,index}.ts(x)` (new) + tests;
`EndUserShell.tsx` wraps the outlet in `RealtimeProvider`.

## 6. Test plan

Vitest: `RealtimeClient` (fake WebSocket — resubscribe-on-open, event delivery + non-JSON
frames ignored, fresh-URL reconnect with resubscribe, close() halts), `queryKeysForEvent`
matrix. **Manual smoke (post-deploy):** open one record in two sessions, edit in one,
observe the other refresh; watch the bell count move on an approval submit.

## 7. Docs to update (same PR)

`status.md` WebSocket realtime row (FE consumption ships), `conventions.md`
(invalidation-only rule), `concerns.md` (no per-subscriber FLS residual), parent README
slice table.

## 8. Risks & open questions

- Tenant-wide event visibility (collection names + record ids broadcast to all tenant
  subscribers) — pre-existing server design, documented in concerns.md; per-subscriber
  filtering is v2.
- Subscription set is nav-derived, not page-derived — collections reachable only outside
  the nav don't live-refresh (acceptable v1; page-level registration is a follow-up).
- Invalidation storms under heavy write bursts are bounded by the 250ms debounce +
  React Query dedup; watch worker load after deploy.
