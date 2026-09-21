---
title: Realtime WebSocket
description: Subscribe to record changes and presence over a WebSocket — connection, message protocol, the invalidation-only model, and limits.
section: api
order: 60
---

## Connect

```
wss://api.example.com/ws/realtime?token=<JWT>
```

The JWT (not a PAT) is passed as a query parameter on the upgrade; the socket is closed with code `4001` when the
token expires — reconnect with a fresh one. The tenant comes from the token.

## Client messages

```json
{ "action": "subscribe",   "collection": "invoices" }
{ "action": "unsubscribe", "collection": "invoices" }
{ "action": "presence.join",  "resource": "record:invoices/9d2c…" }
{ "action": "presence.leave", "resource": "record:invoices/9d2c…" }
{ "action": "chat.join",  "conversationId": "…" }
{ "action": "chat.leave", "conversationId": "…" }
```

## Server messages

```json
{ "action": "subscribed", "collection": "invoices" }
{ "action": "error", "message": "Subscription limit reached" }

{ "event": "record.changed", "collection": "invoices", "changeType": "UPDATED",
  "recordId": "9d2c…", "data": { … }, "timestamp": "2026-09-21T10:00:00Z" }

{ "event": "presence.changed", "resource": "record:invoices/9d2c…", "users": [ { "id": "…", "email": "…" } ], "timestamp": "…" }
```

## Invalidation, not replication

Treat `record.changed` as a signal to **refetch through the API**, which applies your permissions and field-level
security. `data` is included as a convenience but is **omitted** whenever the record has masked fields, and it is
not filtered per subscriber. The app's data layer works this way: an event invalidates the cached query, which
refetches.

Events are fanned out from the platform's NATS stream to every gateway pod, so a subscriber receives each change
once regardless of which pod it is connected to.

## Presence

Join a record resource to appear in its viewer list and receive `presence.changed` as others join, leave or
time out (30-second heartbeat, 90-second expiry). At most 10 presence resources per socket; snapshots list up to
20 viewers.

## Limits

50 subscriptions per socket, 100 concurrent connections per tenant, 20 chat conversations per socket. Exceeding
one answers an `error` message and ignores the request.

## Outbound alternatives

For server-to-server delivery use [outbound webhooks](/docs/api/webhooks/) or a `NATS_TRIGGERED` flow.
