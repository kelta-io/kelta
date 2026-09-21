---
title: Collections
description: Create, describe, update and delete collections; naming rules; what happens under the hood; the schema endpoint.
section: data-model
order: 10
---

A collection is a table. Creating one makes a physical PostgreSQL table, a `/api/<name>` JSON:API endpoint and a
gateway route — on every running pod — within seconds. Every user collection also gets the audit columns `id`,
`createdAt`, `updatedAt`, `createdBy`, `updatedBy` (and `createdGeo` / `updatedGeo` when geo capture is on).

## Naming

| Property | Rule |
|---|---|
| `name` (API name) | `^[a-z][a-z0-9_-]*$` — starts with a lowercase letter; lowercase letters, digits, `_` and `-`. Forms the path `/api/<name>` and, by default, the table name. |
| `displayName` | Free text shown in the UI. |
| `description` | Free text, surfaced by the schema endpoint and the OpenAPI document. |

Collection names are per tenant. System collections (`collections`, `fields`, `flows`, `profiles`, `users`, …)
occupy their names in every tenant.

## Creating a collection

**Console.** Setup → Data model → Collections → *Create collection*. The wizard runs Basics → Fields →
Authorization → Review; the Authorization step sets create/read/edit/delete per profile so the collection is
usable the moment it exists.

**API.** Collections are records in the `collections` system collection:

```http
POST /api/collections
Content-Type: application/vnd.api+json

{ "data": { "type": "collections", "attributes": { "name": "invoices", "displayName": "Invoices" } } }
```

Fields are then records in `fields` with a `collectionId` relationship (see [Field types](/docs/data-model/field-types/)).

**CLI.** `kelta collections create --name invoices --display-name Invoices` then `kelta fields add invoices ...`.

**MCP.** `create_collection` accepts an inline initial field set so an agent can create a usable collection in one
call.

### What happens under the hood

Saving the `collections` record runs the schema lifecycle hooks: the name is validated, `currentVersion` starts at
`1`, `path` defaults to `/api/<name>`, and the physical table is created (or reconciled). A
`kelta.config.collection.changed` event is published on NATS JetStream; every worker pod refreshes its registry and
every gateway pod adds the route. Reads on another pod are consistent as soon as that pod has consumed the event —
in practice well under a second, but a script that creates a collection and immediately queries it through a load
balancer may see one `404` first.

## Describing a collection

`GET /api/collections/{name}/schema` is the canonical description of a collection's attributes — it works for
system collections too, whose fields have no rows in `fields`:

```json
{
  "name": "invoices", "displayName": "Invoices", "systemCollection": false,
  "fields": [
    { "name": "status", "type": "PICKLIST", "required": false, "isRelationship": false,
      "description": "Billing state", "default": "DRAFT", "enum": ["DRAFT", "SENT", "PAID", "OVERDUE"] },
    { "name": "customer", "type": "LOOKUP", "required": true, "isRelationship": true,
      "reference": { "target": "customers", "targetField": "id",
                     "relationshipType": "LOOKUP", "relationshipName": "Customer" } }
  ]
}
```

`default`, `enum` and `reference` are present only when declared. An unknown name is `404 COLLECTION_NOT_FOUND`.
The same information is in the generated OpenAPI document at `GET /api/docs/openapi.json`
([REST API overview](/docs/api/overview/)), `kelta collections describe <name>` and the MCP
`get_collection_schema` tool.

## Collection options

| Attribute | Effect |
|---|---|
| `displayFieldId` | Field used as the record's display label in lookups, search results and headers. |
| `trackHistory` | Capture a full snapshot of every create/update/delete in `record-versions`. See [Record history](/docs/data-model/record-history/). |
| `captureGeo` | Stamp the request's resolved geolocation onto `createdGeo` / `updatedGeo`. |
| `adapterConfig` | Storage adapter settings — for external collections, see [External collections](/docs/data-model/external-collections/). |
| `active` | Inactive collections are ignored by the runtime. |

## Updating

`PATCH /api/collections/{id}` (or `kelta collections update`, MCP `update_collection`) changes display name,
description and options. Renaming the API name of a collection that already has data is not supported through the
generic update; create the new collection and migrate.

Adding, changing and removing fields alters the table in place — see [Field types](/docs/data-model/field-types/).
Each change increments `currentVersion`; `collection-versions` keeps a snapshot per version.

## Deleting

Deletes are **hard deletes**. `DELETE /api/collections/{id}` drops the table and cascades to everything that
depends on the collection: attachments (including their stored objects), layouts, list views, reports, validation
rules, field history, record versions.

Because of that, the request is refused with `400` and a message naming the dependent counts ("would permanently
destroy 5 attachments, 2 reports…") unless you pass `?force=true` (`kelta collections delete <name> --force`).
Flows and other non-interactive callers cannot force a delete.

A master-detail child whose foreign key still points at the collection blocks the table drop; delete or re-point
the child first.

## Record deletes are hard too

`DELETE /api/<collection>/{id}` removes the row. There is no soft delete; what survives is the audit trail: a
tombstone for offline sync (`GET /api/<collection>/_changes?since=`), the last snapshot in `record-versions` when
history is on, and the field-history rows for tracked fields.
