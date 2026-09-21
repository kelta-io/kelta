---
title: Apps and navigation menus
description: An app is a menu; its items are the tabs — the path grammar the shell understands, groups, the default app, and authoring menus from the console, CLI or MCP.
section: console
order: 60
---

The end-user app's navigation is metadata. A **menu** (`ui-menus`) is an app in the shell's app switcher; its
**items** (`ui-menu-items`) are the tabs across the top. The full row shapes and grammar are in the
[authoring reference](/docs/reference/ui-menus/).

## Model

| | |
|---|---|
| Menu | `name`, `description`, `icon`, `displayOrder`, `isDefault`, `active` |
| Item | `label`, `path` (omit for a group header), `icon`, `displayOrder`, `active`, `parentId` for nesting |

Nesting is one level: an item with children renders as a dropdown group. The active app for a user is their
saved preference, else the menu marked `isDefault`, else the first by `displayOrder` — mark at most one default.

## Path grammar

Only these shapes are rendered; anything else is stored but silently omitted from the nav:

| Path | Resolves to |
|---|---|
| `/resources/<collection>` | the collection's list at `/app/o/<collection>` |
| `/p/<slug>` | the custom page |
| `/dashboards/<id>` | a dashboard |
| `/reports/<id>` | a report |
| `/chat` | the chat surface (when enabled) |

A group whose children all fail to resolve is dropped entirely.

## Authoring

**Console** — Setup → UI customization → Menus: create the app, add items and groups, drag to order, mark the
default.

**CLI** — a tree file, applied in one call:

```bash
kelta menus get Finance --tree > finance.json
kelta menus apply --file finance.json --name Finance
```

```json
{ "name": "Finance", "isDefault": true, "items": [
  { "label": "Invoices", "path": "/resources/invoices" },
  { "label": "Reports", "children": [ { "label": "Aging", "path": "/reports/<id>" } ] } ] }
```

**MCP** — `apply_menu` upserts the whole tree keyed on the menu name and (menu, parent, label) per item,
validates every path against the grammar before writing, and reports each item as `created`, `updated`,
`unchanged` or `stale` (`prune: true` deletes stale items).

Menu changes are broadcast to every pod and appear for users on their next navigation.
