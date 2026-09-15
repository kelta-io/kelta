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

## Authoring with `apply_menu`

`apply_menu` (kelta-mcp, admin) upserts a whole menu tree in one idempotent
call, keyed on `name` for the menu and `(menu, parent, label)` for each
item — an item with a non-empty `children` array becomes a group, and
each child's `parentId` is resolved to the group's id automatically, so
callers never need to create the group first, read its id back, then
create each child by hand. `path` is validated against the grammar above
*before* anything is written; an invalid path fails the whole call with a
structured error rather than silently storing an item the nav renderer
will never surface. `prune:true` hard-deletes any existing item absent
from the call's tree; omitted or `false` reports those items as `stale`
and leaves them untouched. See the tool's own description for a worked
example.
