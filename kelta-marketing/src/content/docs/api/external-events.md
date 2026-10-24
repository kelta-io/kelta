---
title: External events from flows
description: What a flow's PUBLISH_EVENT step sends — the tenant-scoped NATS subject, the JetStream stream that holds it, every envelope field, and how a consumer subscribes.
section: api
order: 80
---

A flow's [`PUBLISH_EVENT`](/docs/automation/action-handlers/#publish_event) step publishes a JSON event to the
platform's NATS server for systems outside Kelta to consume. It is the push counterpart of
[NATS message triggers](/docs/automation/triggers/#nats-message-triggers), which carry messages *into* flows.

## Subject

```text
kelta.event.<tenantId>.<topic>
```

- `<tenantId>` is the id of the tenant whose flow ran. The platform inserts it — the flow only supplies `topic` —
  so a flow can publish only into its own tenant's namespace and a consumer can filter one tenant with
  `kelta.event.<tenantId>.>`.
- `<topic>` is the step's `topic`: one or more dot-separated tokens of letters, digits, `-` and `_`. Wildcards
  (`*`, `>`), empty tokens (`a..b`, a leading or trailing dot), spaces and other characters fail the step, so a
  topic cannot widen or leave the tenant's namespace.

A step in tenant `7f3c…` with `"topic": "order.approved"` publishes to `kelta.event.7f3c….order.approved`.

Nothing inside the platform consumes `kelta.event.>`. In particular these events never start flows — that is the
separate `kelta.trigger.<tenantId>.<topic>` namespace — so `PUBLISH_EVENT` cannot make a flow loop.

## Delivery

Events are published to **JetStream**, stream `KELTA_EVENTS` (subjects `kelta.event.>`, 24-hour retention). The step
waits for the stream's acknowledgement: if NATS is unreachable or the publish is not acknowledged, the step fails
with `ActionFailed` (catchable with `Retry`/`Catch`) instead of reporting an event that was never stored. A
deployment without a NATS connection fails every `PUBLISH_EVENT` step.

Each message carries these headers:

| Header | Value |
|---|---|
| `Nats-Msg-Id` | the envelope's `eventId` — JetStream drops a duplicate published within its 2-minute window |
| `X-Tenant-Id` | the envelope's `tenantId` |
| `Kelta-Message-Key` | `<tenantId>:<collectionId>` |

## Envelope

The message body is a JSON object:

```json
{
  "eventId": "0b6e7c1e-4f0a-4a59-9d55-2f0f3c3f8f61",
  "eventType": "order.approved",
  "tenantId": "7f3c…",
  "collectionId": null,
  "collectionName": null,
  "recordId": null,
  "workflowRuleId": null,
  "timestamp": "2026-10-24T09:15:02.481Z",
  "data": { "orderId": "o-1042", "total": 129.5 },
  "recordData": { "record": { "...": "..." }, "input": { "...": "..." } }
}
```

| Field | Type | Meaning |
|---|---|---|
| `eventId` | string (UUID) | unique per publish; use it to de-duplicate |
| `eventType` | string | the step's `eventType`; `workflow.custom.event` when omitted |
| `tenantId` | string | the publishing tenant — always equal to the `<tenantId>` subject token |
| `collectionId` | string \| null | collection id of the action context; `null` for steps in a flow |
| `collectionName` | string \| null | collection name of the action context; `null` for steps in a flow |
| `recordId` | string \| null | record id of the action context; `null` for steps in a flow — put the ids you need in `dataPayload` |
| `workflowRuleId` | string \| null | legacy workflow-rule id; `null` for steps in a flow |
| `timestamp` | string (ISO-8601 instant, UTC) | when the step built the event |
| `data` | object | the step's `dataPayload`, after `${…}` substitution; absent when the step has none |
| `recordData` | object | the step's input — the [state envelope](/docs/automation/flow-data/) after `InputPath` |

`recordData` can be large and contains whatever the flow carries in state; narrow it with the step's `InputPath`
and send the fields a consumer needs in `dataPayload`.

## Consuming

Subscribing needs a connection to the deployment's NATS server, which the operator provides — tenants have no
public NATS endpoint. A durable JetStream consumer on the stream, filtered to one tenant:

```bash
nats consumer add KELTA_EVENTS orders-sync \
  --filter 'kelta.event.<tenantId>.order.>' --deliver all --ack explicit --pull
nats consumer next KELTA_EVENTS orders-sync --count 10
```

Events older than 24 hours are discarded by the stream. To reach an HTTP endpoint instead, use
[`CALL_API` or `OUTBOUND_MESSAGE`](/docs/automation/action-handlers/#integration) or
[outbound webhooks](/docs/api/webhooks/).
