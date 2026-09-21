---
title: Outbound webhooks
description: Have Kelta call your endpoint on record and collection events — event types, payload, endpoint management through the Svix portal, signatures and retries.
section: api
order: 70
---

Outbound webhooks deliver record and schema events to HTTP endpoints you register. Delivery is handled by an
embedded [Svix](https://www.svix.com) service, which provides signing, retries and a self-service portal. Webhooks
are available when the deployment has Svix configured — Setup → Integration → Webhooks tells you.

## Event types

| Event | When |
|---|---|
| `record.created`, `record.updated`, `record.deleted` | any record in a user collection changes |
| `collection.created`, `collection.updated` | a collection is created or its schema changes |

## Payload

```json
{
  "eventType": "record.updated",
  "payload": {
    "recordId": "9d2c…",
    "collectionName": "invoices",
    "changeType": "UPDATED",
    "data": { "number": "INV-1", "status": "PAID", … },
    "previousData": { "status": "SENT", … },
    "changedFields": ["status"],
    "timestamp": "2026-09-21T10:00:00Z"
  }
}
```

`previousData` and `changedFields` are present on updates. Payloads carry the record as the platform stores it
(the system trust tier — masking does not apply); restrict which endpoints receive which collections
accordingly.

## Managing endpoints

`GET /api/svix/portal` returns a short-lived URL to the tenant's Svix application portal, where you add endpoints,
choose event types, view delivery attempts and replay failures. The console embeds it under Setup → Integration →
Webhooks (`MANAGE_CONNECTED_APPS`).

## Verifying deliveries

Every delivery carries the standard Svix headers (`svix-id`, `svix-timestamp`, `svix-signature`). Verify them with
a Svix client library and the endpoint's signing secret from the portal before trusting a payload.

## Retries

Failed deliveries are retried by Svix on an exponential schedule; the portal shows each attempt and lets you
replay.

## Inbound

For the other direction — an external system starting a flow — see
[Triggers → inbound webhooks](/docs/automation/triggers/#inbound-webhooks).
