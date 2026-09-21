---
title: List views and saved views
description: The list toolbar, the four renderers, grouping, mass edit and CSV import, and the difference between shared list views and personal saved views.
section: console
order: 40
---

Every collection list in the app (`/app/o/<collection>`) is driven by a **view**: columns, filters, sort, and a
renderer. Views come in two kinds:

| | Shared list view | Personal saved view |
|---|---|---|
| Stored as | `list-views` row (`visibility` `PUBLIC`, `GROUP` or `PRIVATE`) | the user's `user-ui-preferences` |
| Authored by | admins in Setup → Data model → List views, CLI, MCP | the user from the list toolbar |
| Follows | everyone who can see it | the user across browsers and devices |
| Renderer | published `viewType` + `typeConfig` | the user's own choice |

A user can switch the renderer of a shared view for themselves; that override is stored as a preference and
never changes the shared row. `?view=<id>` deep-links a view; the default view applies on a clean URL.

## The toolbar

- **Filter bar / builder** — any field, any operator from the [filter grammar](/docs/api/querying/#filtering).
- **Multi-sort** — shift-click column headers; the URL carries `sort=a,-b`.
- **Column chooser** — visibility and order (at least one column).
- **Density** and **sticky first column**.
- **Group by** — client-side grouping over the current page with collapsible headers, counts and sums for
  number, currency and percent columns.
- **View selector** — shared and personal views; save the current state as a new personal view.
- **Renderer switch** — table, kanban, calendar, gallery.

## Renderers

| Renderer | Config | Notes |
|---|---|---|
| Table | columns | virtualised, inline editing where the layout allows |
| Kanban | `laneField` (a picklist field), `cardFields` | one lane per picklist value plus *Unassigned*; drag between lanes issues a `PATCH` with `If-Match`, so a concurrent edit is detected and reverted with a toast |
| Calendar | a date or datetime field | month grid; the visible month is merged into the query as `gte`/`lte` |
| Gallery | an image URL field plus up to four body fields | responsive cards with an initial-letter fallback |

## Selection actions

Select rows to **mass-edit** one field across them (runs as a bulk job, needs edit permission and `MANAGE_DATA`;
progress under Setup → Platform → Bulk jobs) or **import a CSV** into the collection.

## Publishing a shared view

Setup → Data model → List views → *New*: name, columns, filters, sort, row limit, visibility, and — optionally — a
renderer with its configuration. Or:

```bash
kelta list-views apply invoices --name "By status" \
  --columns number,customer,amount,due_date \
  --filter status.neq=PAID --sort -due_date \
  --view-type KANBAN --lane-field status --card-fields number,amount \
  --visibility PUBLIC
```

MCP: `apply_listview` (create-or-update keyed on collection + name). Filter grammar, row-limit values and the
deep-link contract are in the [authoring reference](/docs/reference/list-views/).

## Related lists

Related lists on a record page are configured on the [page layout](/docs/console/page-layouts/) and use the same
table renderer with their own columns, sort and row limit.
