---
title: Resources and idempotent apply semantics
description: The kelta:// resources an agent can read, and the contract of the apply_* tools — natural keys, the action/changed result, prune vs stale, and which keys are compared.
section: mcp
order: 40
---

## Resources

| URI | Endpoint | Content |
|---|---|---|
| `kelta://collections` | both | the collection list |
| `kelta://collections/{name}` | both | one collection's schema |
| `kelta://openapi.json` | user | the tenant's OpenAPI document |
| `kelta://docs/jsonapi`, `kelta://docs/page-layouts`, `kelta://docs/list-views`, `kelta://docs/dashboards`, `kelta://docs/ui-pages`, `kelta://docs/ui-menus` | both | the authoring reference — byte-identical to the [Authoring reference](/docs/reference/jsonapi/) pages and to `kelta docs <topic>` |

Read the relevant `kelta://docs/*` resource before writing filters, layouts, pages, menus or dashboards by hand.

## The `apply_*` contract

`apply_layout`, `apply_listview`, `apply_picklist`, `apply_menu`, `apply_dashboard` and `apply_page` are
**create-or-update keyed on a natural key**, so a setup plan can be re-run until it converges:

| Tool | Natural key |
|---|---|
| `apply_layout` | collection + layout name (or `layoutId`) |
| `apply_listview` | collection + view name |
| `apply_picklist` | picklist name; each value by `(picklist, value)` |
| `apply_menu` | menu name; each item by `(menu, parent label, label)` |
| `apply_dashboard` | dashboard name; each component by title |
| `apply_page` | `path` (falling back to `slug` on a route rename) |

Each call reports:

```json
{ "action": "created" | "updated" | "unchanged", "id": "…", "changed": ["columns", "sort"] }
```

Tools that manage children (`apply_picklist`, `apply_menu`, `apply_dashboard`) nest one such result per value,
item or component, and add `pruned` when `prune: true` removed extras — values are **deactivated**, menu items
and dashboard components **deleted**. Without `prune`, absent children are reported as `stale` and left alone.

### Which keys are compared

Only keys you supply are compared and written; an omitted key keeps its current value (or the default on
create), so a partial call never blanks fields it did not mention. The exceptions are the object's core shape —
`columns`/`filters` for a list view, `sections` for a layout — which are always written in full, so omitting
them means "empty", not "leave alone". `apply_page` validates the config before writing and rejects the whole
call on an `error`-severity problem.

### Validation before write

`apply_menu` validates every path against the nav grammar and `apply_page` runs the page validator, so an
invalid document fails with a structured error and writes nothing — an agent can fix and retry without
half-applied state.
