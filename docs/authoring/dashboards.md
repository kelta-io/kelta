# Authoring Dashboards

A dashboard is a `columnCount`-wide grid of components (metric tiles,
charts, tables), each backed by a `reportId` or a direct collection query.
Stored on `dashboards` plus child collection `dashboard-components`.

## Dashboard

- `name`, `description`, `folderId`, `accessLevel`.
- `dynamic` — boolean; when true, widgets execute as `runningUserId`
  instead of the viewer (a "run as" dashboard).
- `columnCount` — the grid width in columns; author-chosen, no default.

## Grid is 1-based

`dashboard-components` rows: `dashboardId`, `reportId`, `componentType`,
`title`, `columnPosition`, `rowPosition`, `columnSpan` (default `1`),
`rowSpan` (default `1`), `config` (JSON, default `{}`), `sortOrder`
(required).

**`columnPosition` and `rowPosition` are 1-based and required — there is
no default.** They map directly onto CSS Grid line numbers (`grid-column:
<columnPosition> / span <columnSpan>`), and CSS Grid lines start at `1`.
The first cell of the grid is `columnPosition: 1, rowPosition: 1`, not
`0, 0`. This is the opposite convention from page-layout `columnNumber`,
which is 0-based (see `kelta docs page-layouts`).

## Widget catalogue (`componentType`)

Exactly four types are supported server-side; anything else fails widget
execution:

- `metric` — a single aggregate value. `config` selects the aggregate
  function: `SUM`, `AVG`, `MIN`, `MAX`, or `COUNT` (case-insensitive).
- `chart` — `config.chartStyle` of `pie` or (default) `bar`.
- `table` — a record grid.
- `recent` — a record grid, styled as a "recent activity" feed. Shares
  its data path with `table`.

## Operator vocabulary

Widget filters reuse the platform's canonical filter operators (see
`kelta docs jsonapi`): `eq neq gt gte lt lte isnull starts ends icontains
istarts iends ieq in`, with one dashboard-specific override — the literal
`contains` in a widget filter resolves to case-insensitive `icontains`,
not the generic (case-sensitive) `contains` used elsewhere. If you need
case-sensitive containment in a dashboard filter, there is no way to
express it; use `icontains` semantics knowingly.

Time-range filters additionally accept the literals `TODAY`, `7D` /
`LAST_7_DAYS`, `30D` / `LAST_30_DAYS`, `90D` / `LAST_90_DAYS`, and `1Y` /
`LAST_YEAR`.

## Per-widget time-range override

The dashboard's selected time range (the viewer's `TODAY`/`7D`/`30D`/`90D`/
`1Y`/`ALL` picker) applies to every widget by default — but a "state right
now" metric (e.g. tasks currently in progress, pending approvals) is a
count, not an event stream, and silently loses rows under a narrow window.
Two `config` keys opt a widget out, in this precedence order:

1. `config.ignoreTimeRange: true` — no time filter is built at all, for
   any page range (including explicit `startDate`/`endDate`). The widget
   always reflects the full data set.
2. `config.fixedTimeRange` — one of `TODAY`, `7D`, `30D`, `90D`, `1Y`;
   filters on that fixed window regardless of the page's selected range.

Below that: the page's selected range (runtime `timeRange`) wins if set,
else `config.timeRange` is the widget's own default. A widget with neither
key behaves exactly as before. The end-user viewer (`DashboardViewPage`)
renders a small "All time" / fixed-range chip on the widget frame of any
component that opts out, so the reader knows the page range doesn't apply
to it.

## `reportId`

`dashboard-components.reportId` links a widget to a saved report; the
widget's target collection resolves in this order: `config.collectionName`
→ `config.collectionId` → the linked report's primary collection. So
`reportId` can be omitted if `config.collectionName` or
`config.collectionId` is set directly — useful for ad hoc widgets that
don't need a standalone saved report.
