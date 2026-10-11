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
which is 0-based (see `kelta docs page-layouts`). Page layouts also have
a whole-layout apply-in-one-call tree endpoint (see `kelta docs
page-layouts` → "One call: the layout tree").

## Widget catalogue (`componentType`)

Exactly four types are supported server-side (`metric`, `chart`,
`table`, `recent`, case-insensitive); the component hook rejects any
other value on save.

- `metric` — a single aggregate value.
- `chart` — grouped aggregates, drawn as `config.chartStyle` `pie` or
  (default) `bar`.
- `table` — a paged record grid.
- `recent` — the newest records (sorted by `createdAt` descending),
  styled as a "recent activity" feed.

## Widget `config` keys

`config` is free JSON, but on save the component hook checks
`collectionName` and every field it names (`fields`, `groupByField`,
`aggregateField`, `timeField`, `filters[].field`) against the target
collection, and each `filters[].operator` against the operator
vocabulary below. `POST /api/dashboards/{id}/validate` runs the same
checks as a dry run.

Keys every type accepts:

| Key | Type | Notes |
|-----|------|-------|
| `collectionName` | string | Target collection. Optional when `collectionId` or `reportId` resolves it (see `reportId` below). |
| `collectionId` | string | Target collection by id; used only when `collectionName` is unset. |
| `filters` | array | `[{"field", "operator", "value"}]`, ANDed. A missing `operator` means `eq`. |
| `timeField` | string | `DATE`/`DATETIME` field the time range filters on. Default `createdAt`. |
| `timeRange` | string | Default time range when the page supplies none: `TODAY`, `7D`, `30D`, `90D`, `1Y` (or the `LAST_*` forms). |
| `fixedTimeRange` | string | Same values; overrides the page's time range. |
| `ignoreTimeRange` | boolean | `true` ignores every time range (for "current state" widgets). Default `false`. |

Per type:

| `componentType` | Key | Type | Required | Notes |
|-----------------|-----|------|----------|-------|
| `metric` | `aggregateFunction` | string | no | `COUNT` (default), `SUM`, `AVG`, `MIN`, `MAX`; case-insensitive. |
| `metric` | `aggregateField` | string | yes, unless `COUNT` | Numeric field to aggregate. Not a `ROLLUP_SUMMARY` or `FORMULA` field. |
| `metric` | `label` | string | no | Caption; defaults to the function name. |
| `chart` | `groupByField` | string | **yes** | One bar/slice per distinct value. Not a `ROLLUP_SUMMARY` or `FORMULA` field; a lookup groups by the referenced record's display value. |
| `chart` | `aggregateFunction` | string | no | As for `metric`; default `COUNT` (records per group). |
| `chart` | `aggregateField` | string | yes, unless `COUNT` | Numeric field aggregated per group. |
| `chart` | `maxGroups` | integer | no | Default `20`. |
| `chart` | `chartStyle` | string | no | `bar` (default) or `pie`. |
| `chart` | `aggregateOver` | string | no | Only `groupSizes`: report how many records each group holds, without the group values (see "Group-size distribution" below). `aggregateFunction` must then be `COUNT` or unset. |
| `chart` | `bucketBounds` | integer array | no | With `aggregateOver`. Histogram bucket lower bounds, strictly ascending positive integers. Default `[1, 2, 5, 10, 25]`. |
| `chart` | `percentiles` | integer array | no | With `aggregateOver`. Strictly ascending integers in `1`–`100`. Default `[50, 90, 99]`. |
| `chart` | `sizeThresholds` | integer array | no | With `aggregateOver`. Strictly ascending positive integers. Default `[10, 25]`. |
| `table` | `fields` | string array | no | Columns, in order; all fields when omitted. A comma-separated string is also accepted. |
| `table` | `sortBy` | string or array | no | `"-amount,name"`, or `[{"field": "amount", "direction": "DESC"}]` (`direction` defaults to `ASC`). |
| `recent` | `fields` | string array | no | As for `table`. |
| `recent` | `limit` | integer | no | Default `10`, maximum `100`. |

`metric` and `chart` aggregate over at most the first 1,000 matching
records (a `COUNT` metric reports the true total). A `chart` with
`aggregateOver: "groupSizes"` is the exception: it pages through every
matching record, up to 50,000 (see below). `table` pages at 25 rows by
default (maximum 500).

## Group-size distribution (`aggregateOver: "groupSizes"`)

Use this for questions like "how many tickets does each agent hold" when
the chart must not reveal who the agents are. The widget groups by
`groupByField` as usual, then discards the group values and reports only
how big the groups are. No group value (raw id or resolved lookup label)
appears anywhere in the response, a lookup `groupByField` is never
resolved to its display value, and segments are not drill-through
targets. Records with no value in `groupByField` form one group, as in an
ordinary chart.

```json
{ "collectionName": "tickets", "groupByField": "assignee",
  "aggregateOver": "groupSizes", "chartStyle": "bar" }
```

The `data` payload:

```json
{
  "groupByField": "assignee",
  "aggregateFunction": "COUNT",
  "groupSizes": true,
  "series": [
    { "label": "1",     "count": 1, "value": 1 },
    { "label": "2–4",   "count": 1, "value": 1 },
    { "label": "5–9",   "count": 0, "value": 0 },
    { "label": "10–24", "count": 1, "value": 1 },
    { "label": "25+",   "count": 0, "value": 0 }
  ],
  "summary": {
    "groups": 3,
    "records": 17,
    "percentiles": { "50": 4, "90": 12, "99": 12 },
    "max": 12,
    "countGroupsAtLeast": { "10": 1, "25": 0 }
  },
  "totalRecords": 17,
  "truncated": false
}
```

- `series` has one point per bucket, in `bucketBounds` order, including
  empty buckets. Bucket `i` holds groups whose size is at least
  `bucketBounds[i]` and below `bucketBounds[i+1]`; the last bucket is
  open-ended (`25+`). A bucket covering a single size is labelled with
  that size (`1`), otherwise with an en-dash range (`2–4`). `value` and
  `count` are both the number of groups in the bucket. No point carries a
  `key`. Groups smaller than the first bound fall in no bucket but still
  count in `summary`.
- `summary.groups` is the number of groups and `summary.records` the
  number of records counted.
- `summary.percentiles` maps each entry of `percentiles` to the
  nearest-rank group size: sort the sizes ascending and take the
  `ceil(p / 100 × groups)`-th (1-based). With no groups every percentile
  and `max` is `0`.
- `summary.max` is the largest group size.
- `summary.countGroupsAtLeast` maps each entry of `sizeThresholds` to the
  number of groups holding at least that many records.
- `totalRecords` is the number of records matching the widget's filters.
- `truncated` is `true` when more than 50,000 records matched. The widget
  reads matching records 1,000 at a time and stops at 50,000; the
  distribution and `summary` then describe those 50,000 records only.

Examples, one per family (the `config` value of a `dashboard-components`
row):

```json
{ "collectionName": "deals", "aggregateFunction": "SUM", "aggregateField": "amount",
  "label": "Open pipeline", "filters": [ { "field": "stage", "operator": "neq", "value": "CLOSED_LOST" } ] }
```

```json
{ "collectionName": "deals", "groupByField": "stage", "aggregateFunction": "COUNT",
  "chartStyle": "pie", "ignoreTimeRange": true }
```

```json
{ "collectionName": "deals", "fields": ["name", "stage", "amount"], "sortBy": "-amount",
  "filters": [ { "field": "stage", "operator": "eq", "value": "NEGOTIATION" } ] }
```

```json
{ "collectionName": "activities", "fields": ["subject", "activityType"], "limit": 5 }
```

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

## `reportId`

`dashboard-components.reportId` links a widget to a saved report; the
widget's target collection resolves in this order: `config.collectionName`
→ `config.collectionId` → the linked report's primary collection. So
`reportId` can be omitted if `config.collectionName` or
`config.collectionId` is set directly — useful for ad hoc widgets that
don't need a standalone saved report.
