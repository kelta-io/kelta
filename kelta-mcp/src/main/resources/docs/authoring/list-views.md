# Authoring List Views

A list view is a saved query against one collection: filters, sort, visible
columns, and a row limit. Stored on the `list-views` system collection
(child rows reference it by `collectionId`). Page layouts have an
equivalent whole-layout apply-in-one-call endpoint (see `kelta docs
page-layouts` → "One call: the layout tree").

## Fields

- `collectionId` — the collection this view queries (master-detail).
- `name` — display name.
- `visibility` — `PRIVATE` (default) or `PUBLIC`. Only `PUBLIC` views are
  offered to other users as shared views.
- `isDefault` — boolean. See "Default views" below.
- `columns` — required JSON array of field names to display, in order.
- `filters` — JSON array of `{ field, operator, value }` filter clauses.
- `filterLogic` — optional custom AND/OR logic string over filter indexes;
  omit for a plain AND of all filters.
- `sortField` / `sortDirection` (`ASC` default) — single-column sort.
- `rowLimit` — integer page size. See "Row limit" below.
- `chartConfig` — optional JSON chart overlay for the view.
- `viewType` — `TABLE` (default), `KANBAN`, `CALENDAR` or `GALLERY`. See
  "Renderer" below.
- `typeConfig` — optional JSON settings for the chosen renderer.

## Renderer

A view carries the renderer it opens as, so a board can be published
rather than left for every user to configure. `viewType` is one of:

```
TABLE  KANBAN  CALENDAR  GALLERY
```

`typeConfig` holds that renderer's field references, keyed by the
lowercased view type:

```json
{
  "kanban":   { "laneField": "status", "cardFields": ["title", "owner"] },
  "calendar": { "dateField": "dueAt", "endDateField": "closedAt" },
  "gallery":  { "imageField": "coverUrl", "titleField": "name",
                "cardFields": ["stage"] }
}
```

Only the section matching `viewType` is read. Each is optional — a
`KANBAN` view with no `kanban.laneField` falls back to the collection's
first picklist field, and the same rule applies to the calendar's date
field. `laneField` must name a `picklist` field: lanes are its values.

Anything the end-user list cannot make sense of degrades to a table
rather than erroring, so an unrecognized `viewType` (or a `typeConfig`
that does not match the shapes above) renders as today.

A published renderer is a starting point, not a lock: a user who
switches the renderer in the list toolbar gets that choice stored
against their own account for that view, and it wins on their next
visit. The shared row is unchanged, and other users still see what was
published.

## Filter grammar

Filters use the platform's canonical operator set (same one used by
`filter[field][op]=value` on any collection endpoint — see
`kelta docs jsonapi`):

```
eq neq gt gte lt lte isnull contains starts ends icontains istarts iends ieq in
```

`in` takes an array of values (matches any). `isnull` takes `true`/`false`.
The end-user-facing UI also accepts long-form aliases (`equals`,
`not_equals`, `greater_than`, `greater_than_or_equal`, `less_than`,
`less_than_or_equal`, `starts_with`, `ends_with`, and `any` for `in`) —
author with the short canonical names above; both resolve to the same
operator.

## Row limit

`rowLimit` is stored as a plain integer — the API does not reject other
values. In practice only these four are meaningful, because the end-user
list UI (and the `pageSize` URL param) only recognize this set and fall
back to `25` for anything else:

```
{10, 25, 50, 100}
```

Author `rowLimit` from this set; a different value will silently render as
25 rows per page.

## Default views

`isDefault: true` marks a view as the one to open automatically for a
collection. This is a **convention, not an enforced constraint** — nothing
stops two views (or two shared views) from both being marked default.
When more than one applies, the personal (per-user, locally saved) default
wins over any shared admin-authored default, and among ties the first
match returned by the query wins. Author at most one default view per
collection to keep this predictable.

## Deep-linking to a shared view

The end-user list page reads view state from the URL:

```
?view=shared:<listViewId>&filter=<json>&sort=<field>,-<other>&page=<n>&pageSize=<n>
```

- `view` — a personal view id as-is, or a shared view id prefixed
  `shared:`. Only `visibility: PUBLIC` views are resolvable this way.
- `filter` — JSON array of `{ id, field, operator, value }`, same grammar
  as above.
- `sort` — comma-separated field list; a leading `-` means descending.
- `pageSize` — clamped to `{10, 25, 50, 100}` as above.

This lets a flow, email, or another page link straight into a filtered,
sorted list without the user re-entering the filter by hand.
