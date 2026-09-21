---
title: Relationships
description: Lookup vs master-detail, foreign-key behaviour, reading and writing relationships, includes, and how to model many-to-many.
section: data-model
order: 30
---

## Two relationship types

| | `LOOKUP` | `MASTER_DETAIL` |
|---|---|---|
| Value | optional | required (`NOT NULL`) |
| On parent delete | `ON DELETE SET NULL` | `ON DELETE CASCADE` — children are deleted |
| Ownership | loose association | the child belongs to the parent |
| Roll-ups | — | parents can define `ROLLUP_SUMMARY` fields over children |

Both store the target record's UUID in a `VARCHAR(36)` column with a real foreign key to the target table. The
target is set with `referenceTarget` / `referenceCollectionId` on the field (`--reference <collection>` in the
CLI, `referenceTarget` in MCP `add_field`); `relationshipName` is the human label and `cascadeDelete` mirrors the
FK action.

Create them like any field:

```bash
kelta fields add invoices --name customer --type lookup --reference customers --required
kelta fields add invoice-lines --name invoice --type master_detail --reference invoices
```

## Reading

A relationship field's id appears in **both** `attributes` and `relationships` of every resource — primary data
and `included` alike:

```json
{
  "type": "invoices", "id": "…",
  "attributes": { "number": "INV-1", "customer": "9d2c…" },
  "relationships": { "customer": { "data": { "type": "customers", "id": "9d2c…" } } }
}
```

Reading and PATCHing `attributes` straight back therefore produces no diff and no spurious history row. The CLI
flattens both into one `customer` key.

## Writing

Either shape is accepted — the id in `attributes.customer` or a `relationships.customer.data` object; when both are
present the relationship wins. A value that does not resolve to an existing target record fails validation with
code `reference` and `meta.field`, `meta.targetCollection` and `meta.value`.

## Includes

`include` embeds related records in `included[]` in one round trip:

- **To-one**: name the lookup field — `GET /api/invoices?include=customer`.
- **Has-many**: name the child collection — `GET /api/customers?include=invoices` returns every invoice whose
  lookup points at each customer.
- **Grandchildren**: list the grandchild collection alongside its parent — `GET /api/customers?include=invoices,invoice-lines`
  resolves `invoice-lines` through the already-included `invoices`. (Dot syntax is not used.)
- Included resources respect field-level security like primary data.

## Many-to-many

There is no native many-to-many type. Model it with a junction collection carrying two master-detail (or lookup)
fields:

```bash
kelta collections create --name project-members
kelta fields add project-members --name project --type master_detail --reference projects
kelta fields add project-members --name member  --type master_detail --reference users
```

Then `GET /api/projects?include=project-members` lists the memberships, and each membership carries its
`member` id for a follow-up include or fetch.

## Deleting parents

Deleting a record with lookups pointing at it nulls those lookups; deleting a record with master-detail children
deletes the children (and, recursively, theirs). Deleting a **collection** that is the target of a master-detail
field is blocked until the child field is removed — see [Collections](/docs/data-model/collections/#deleting).
