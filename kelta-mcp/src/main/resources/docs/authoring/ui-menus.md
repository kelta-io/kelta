# Authoring UI Menus

A UI menu is a top-level "app" in the end-user shell's nav switcher; its
items are the tabs shown across the top. Stored on `ui-menus` (the app)
plus `ui-menu-items` (the tabs/groups within it).

## Menus (apps)

`ui-menus`: `name`, `description`, `displayOrder` (default 0), `icon`,
`isDefault` (default false), `active` (default true). The active menu for
a user resolves as: their stored preferred menu, else the menu with
`isDefault`, else the first menu — so, as elsewhere, `isDefault` is a
first-match convention rather than an enforced single-row constraint;
author at most one default menu.

## Menu items and groups via `parentId`

`ui-menu-items`: `menuId` (which menu this item belongs to), `parentId`
(another `ui-menu-items` row, for nesting), `label` (required), `path`
(optional — omit it for a group header, which organizes children but
doesn't navigate anywhere itself), `icon`, `displayOrder` (default 0),
`active` (default true).

Nesting is **one level deep**: an item with children (other items whose
`parentId` points at it) renders as a dropdown group in the top nav; items
already inside a group cannot themselves have children. Deleting a parent
item sets its children's `parentId` to null (they float back to top
level) rather than deleting them.

## Path grammar

`path` must start with `/`. Only a fixed set of shapes are recognized by
the end-user shell's nav renderer — anything else is accepted by the API
but silently never appears in the top nav, so match one of these exactly:

| Path shape | Resolves to |
|---|---|
| `/resources/<collection>` | that collection's list, under `/app/o/<collection>` |
| `/p/<slug>` or `/app/p/<slug>` | the custom page with that `slug` |
| `/dashboards/<id>` or `/app/dashboards/<id>` | that dashboard |
| `/reports/<id>` or `/app/reports/<id>` | that report |
| `/chat` or `/app/chat` | the chat surface |

If a group's children all fail to resolve to one of these shapes, the
whole group is dropped from the rendered nav rather than shown empty.
