# Slice 3 — Presence

> Child spec of [App Intelligence (Phase 4)](./README.md), authored with the
> implementation (same PR). Gateway + FE; **not security-typed** (presence exposes
> coarse "who is viewing" activity to users who already share tenant + record access;
> no field data crosses the channel). Ephemeral only — nothing persists.

## 1. Goal & scope

Users viewing the same record see each other: `/ws/realtime` gains
`presence.join` / `presence.leave` actions with a resource key
(`record:<collection>/<id>`), the gateway tracks per-resource user sets fleet-wide
(NATS-bridged deltas + heartbeats), and pushes `presence.changed` snapshots to
co-present sessions. FE: `usePresence(resource)` + a `PresenceAvatars` stack on the
end-user record detail header. **Deviation from the parent:** builder-editor presence
is deferred — the admin shell mounts no realtime client today; adding one is its own
slice. **Not delivered:** typing/editing indicators, cursors, per-field presence,
admin-shell surfaces.

## 2. UI samples

Record header right side: overlapping initial circles `[AK][BR] +2` with email
tooltips — only OTHER viewers (self filtered out). Empty when alone (renders nothing).

## 3. Data & API contracts

- **Socket protocol** (all Map-shaped — **no new typed DTOs, so no new
  reflect-config entries**; verified: the handler parses with generic `Map`/Jackson
  and the gateway has never needed per-message DTO reflection):
  - client→server `{"action":"presence.join"|"presence.leave","resource":"record:orders/123"}`
  - server→client `{"event":"presence.changed","resource","users":[{"id","email"?}],"timestamp"}`
  - The handler's message guard becomes per-action (subscribe/unsubscribe require
    `collection`; presence actions require `resource`).
- **Fleet-wide state** (`PresenceService`, gateway): per `tenant:resource` a map of
  users fed by NATS deltas on **`kelta.presence.<tenantId>`** (new `KELTA_PRESENCE`
  JetStream stream, 1-minute retention — the shortest-lived stream; presence is
  ephemeral) published directly via `NatsConnectionManager` (plain Map payload
  `{tenantId, type: join|leave|heartbeat, resource, user:{id,email}}`).
  Every pod consumes the subject as a **broadcast** subscription and rebroadcasts
  snapshots to its local joined sessions. A 30s heartbeat re-announces local
  sessions; entries expire after 90s (covers pod/session death without
  coordination).
- **Identity**: `id` = JWT subject, `email` = the `email` claim when present (the
  Phase-1 caveat — subject may be email or UUID depending on login flow — is why
  both travel; the FE filters self by either).
- **Limits**: ≤10 presence resources per session (join rejected with an error
  message), snapshots truncated at 20 users.
- **FE**: `RealtimeClient` gains `joinPresence`/`leavePresence` (re-joined on every
  reconnect, listeners per resource); `RealtimeProvider` exposes the client via
  context (`useRealtimeClient`); `usePresence(resource)` returns live users;
  `PresenceAvatars` renders others-only initials on `ObjectDetailPage`.

## 4. DB migrations

None.

## 5. File-by-file code changes

Gateway: `websocket/PresenceService.java` (new, +tests) ·
`websocket/RealtimeWebSocketHandler.java` (email claim, presence actions, cleanup) ·
`config/NatsSubscriptionConfig.java` (broadcast `gateway-presence`).
Runtime: `JetStreamInitializer` (+`KELTA_PRESENCE`).
FE: `realtime/RealtimeClient.ts` (+tests) · `realtime/RealtimeProvider.tsx` (context)
· `realtime/usePresence.ts` (new, +test) · `components/PresenceAvatars/` (new,
+test) · `ObjectDetailPage` mount · `en.json`. Docs: CLAUDE.md messaging table ·
integrations.md · status.md · parent README.

## 6. Test plan

Gateway unit: join/snapshot to co-present sessions, cross-pod delta merge via
`onPresenceEvent`, leave + disconnect cleanup publish, heartbeat refresh + 90s expiry
sweep, per-session cap, snapshot truncation. FE Vitest: client join/leave/re-join-on-
reconnect + presence.changed dispatch, usePresence lifecycle, PresenceAvatars
(others-only filter, overflow, empty renders nothing). Post-deploy: two sessions on
one record see each other; one leaves, the stack updates. `/verify` green.

## 7. Docs to update (same PR)

CLAUDE.md Messaging table (`kelta.presence.<tenantId>`) · integrations.md · status.md
· parent README (slice row + Phase 4 complete) · memory.

## 8. Risks & open questions

- Presence traffic is per-join/leave + 30s heartbeats per session-resource — trivial
  at current limits; the 1-minute stream retention keeps JetStream storage nil.
- The gateway publishes to NATS for the first time (it only consumed before) — the
  publisher bean was already auto-configured; publish uses plain Map serialization
  (no new native reflection). A native smoke of the socket flow post-deploy is in
  the follow-ups.
- Found en route (separate task chip): the `gateway-realtime` record-event
  subscription is a queue group — multi-pod gateways starve; presence deliberately
  uses broadcast.
