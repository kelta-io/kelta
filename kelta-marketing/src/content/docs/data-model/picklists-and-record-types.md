---
title: Picklists and record types
description: Field-level and global picklists, values and their lifecycle, dependent picklists, and record types that narrow values per subtype.
section: data-model
order: 40
---

## Field picklists vs global picklists

A `PICKLIST` or `MULTI_PICKLIST` field draws its values from one of two sources:

- **Field picklist** — values owned by that one field (`picklistSourceType: FIELD`).
- **Global picklist** — a named, reusable list (`global-picklists`) that any number of fields reference
  (`picklistSourceType: GLOBAL`). Change the list once, every field follows.

Prefer global picklists for anything shared across collections (statuses, countries, priorities).

```bash
kelta picklists create --name priority --values LOW,MEDIUM,HIGH
kelta fields add tickets --name priority --type picklist --picklist priority
```

## Values

Each value is a `picklist-values` row:

| Attribute | Meaning |
|---|---|
| `value` | What is stored on the record. |
| `label` | What the UI shows. |
| `color` | Hex colour for the badge rendering. |
| `sortOrder` | Position; `sorted: true` on the picklist orders alphabetically instead. |
| `isDefault` | Pre-selected on new records (convention: mark one). |
| `isActive` | Inactive values are never offered for new picks but existing records keep them. |

Global picklist options: `restricted` (default `true` — values outside the list are rejected; turn it off to
allow free text) and `sorted`.

### Deactivate, never delete

Values are **deactivated** (`isActive: false`), not deleted, so historical records stay valid:

```bash
kelta picklists value-deactivate <valueId>
```

MCP `apply_picklist` with `prune: true` deactivates values missing from the call; without it they are reported as
`stale` and left alone.

## Dependent picklists

A `picklist-dependencies` row makes one picklist field filter another: `controllingFieldId`,
`dependentFieldId` and a `mapping` JSON object from each controlling value to the dependent values it allows
(`{"EU": ["DE", "FR", "PT"], "NA": ["US", "CA"]}`). The console and the app apply the filter as the controlling
value changes.

## Record types

A **record type** is a named subtype of a collection (`record-types`: `name`, `description`, `isActive`,
`isDefault`). A record carries `recordTypeId`. Record types let one collection serve several business shapes —
*Support ticket* vs *Feature request* — without separate tables.

Per record type you can narrow picklists (`record-type-picklists`): for a given `fieldId`, `availableValues` is
the subset offered and `defaultValue` the preselected one. This is enforced at runtime, not just in the UI: a write
with a value outside the record type's set is rejected, an invalid `recordTypeId` is rejected, and type-specific
defaults are applied on create.

Page layouts can be assigned per record type (Setup → Data model → Page layouts → *Assignments*). The
association is resolved by the client when it renders a record; the server enforces field access, not which layout
is shown.

Edit record types under the collection's **Record types** tab in Setup → Data model → Collections.
