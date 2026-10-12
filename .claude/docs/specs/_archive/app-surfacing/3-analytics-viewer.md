# Slice 3 — End-User Analytics Viewer

> Child spec of [App Surfacing (Phase 1)](./README.md). Conforms to the parent's
> [Key architecture decisions](./README.md#key-architecture-decisions-verified-against-the-code),
> [Shared contracts → Analytics](./README.md#analytics-existing-endpoints--slice-1-gates-slice-3-consumes),
> and [Drill-through URL](./README.md#drill-through-url-slice-3--objectlistpage) sections.
>
> **Depends on slice 1** (`VIEW_ANALYTICS` gate + permission — PR #1194): do not merge this
> slice before it. Frontend-only; not security-typed (the BE gate already exists), normal
> merge rules. Source-verified 2026-07-08.

## 1. Goal & scope

**Delivers:** the first end-user analytics surface, and the first UI consumer of
`DashboardDataController`:
- **`/app/analytics`** (`AnalyticsHubPage`) — dashboards + reports the caller can run, from
  the generic JSON:API lists (`GET /api/dashboards`, `GET /api/reports`).
- **`/app/dashboards/:id`** (`DashboardViewPage`) — CSS-grid layout from
  `dashboard-components` rows (position/span/sortOrder), widget renderers for
  `metric`/`chart`/`table`/`recent`, per-widget error state (incl. masked-field
  rejections), dashboard-level time-range control re-posting `{timeRange}`.
- **`/app/reports/:id`** (`ReportViewPage`) — run, paginate, export CSV/PDF.
- **Drill-through** into `ObjectListPage` via a shared `buildListUrl` helper (chart segment
  click → equals-filtered list; table/recent row click → record detail). This slice **owns
  the `listUrlState.ts` extraction** (per parent; slice 5 reuses it).
- **Navigation**: `navTabs.ts` gains `/dashboards/<id>` and `/reports/<id>` menu-item path
  kinds; `TopNavBar` routes the new kinds and the mobile sheet gains an Analytics group; the
  hub gets a fixed entry point (user menu or menu item).
- FE routes `RequirePermission("VIEW_ANALYTICS")`-gated (UX mirror of the slice-1 BE gate).

**Does not deliver:** dashboard/report authoring (admin stays as-is), custom
start/end-date pickers (preset `timeRange` only in v1 — `TODAY|7D|30D|90D|1Y`), per-widget
time ranges, `accessLevel`/folder sharing enforcement (parent deferral — every
`VIEW_ANALYTICS` holder sees every dashboard/report), report `groups`/matrix rendering
beyond a flat grouped list, Superset anything, realtime refresh (slice 4 layers
invalidation later), backend changes of any kind.

## 2. UI samples

**Hub** (`/app/analytics`):

```
Analytics
DASHBOARDS
┌────────────────────┐ ┌────────────────────┐
│ Sales Overview     │ │ Support KPIs       │
│ 6 widgets          │ │ 4 widgets          │
└────────────────────┘ └────────────────────┘
REPORTS
│ Pipeline by Stage      TABULAR   [Run] │
│ Stale Opportunities    SUMMARY   [Run] │
Empty state: "No dashboards or reports yet."
```

**Dashboard** (`/app/dashboards/:id`):

```
← Analytics   Sales Overview                    [Time range: Last 30 days ▾]
┌──────────────┐ ┌──────────────┐ ┌──────────────────────────────┐
│ OPEN DEALS   │ │ REVENUE MTD  │ │  Deals by stage   (bar)      │
│ 42           │ │ $1.2M        │ │  ▇ ▅ ▂ ▃                     │
└──────────────┘ └──────────────┘ └──────────────────────────────┘
┌───────────────────────────────┐ ┌──────────────────────────────┐
│ Recent orders (table, paged)  │ │ ⚠ This widget is unavailable │
│ …rows… row click → record     │ │ Cannot filter on a masked    │
└───────────────────────────────┘ │ field: ssn                   │
                                  └──────────────────────────────┘
```
Grid: CSS grid, `grid-column: <columnPosition> / span <columnSpan>`, row from
`rowPosition`/`rowSpan`; widget order by `sortOrder`. Chart segment click → drill-through.

**Report** (`/app/reports/:id`):

```
← Analytics   Pipeline by Stage        [Export CSV] [Export PDF]
table per `columns` (fieldName/label/type), rows = `records`
meta strip: 150 records · page 1 of 1        [‹ Prev] [Next ›]
```

Masked-field rejection (400 `"Cannot group on a masked field: …"`) renders as the page/widget
error state verbatim — no client workaround (parent §Security).

## 3. Data & API contracts (all existing — read-only consumption)

```
Hub lists:   GET /api/dashboards?sort=name&page[size]=100
             GET /api/reports?sort=name&page[size]=100
Layout:      GET /api/dashboard-components?filter[dashboardId][eq]=<id>&sort=sortOrder&page[size]=100
Data:        POST /api/dashboards/{id}/data                    {timeRange?}
             POST /api/dashboards/{id}/components/{cid}/data   {timeRange?, page?, pageSize?}   (table repage)
Report:      POST /api/reports/{id}/execute?page[number]=&page[size]=
Export:      GET  /api/reports/{id}/export?format=csv|pdf      (csv via apiClient.get<string>, pdf via getBlob — AnalyticsPage download pattern)
```

**Dashboard data response** (verified): JSON:API single `dashboard-data`; attributes
`{dashboardId, dashboardName, widgets: {<componentId>: {type,data,pagination?} | {error}}}`;
meta `{widgetCount, errorCount}`. **No layout metadata in the response** — the viewer joins
`widgets` onto the `dashboard-components` rows by component id (title, componentType,
positions, spans, `config`).

**Widget `data` shapes** (verified in `DashboardDataService`):

| type | data |
|------|------|
| `metric` | `{value, aggregateFunction, aggregateField, label, totalRecords}` |
| `chart` | `{groupByField, aggregateFunction, series: [{label, count, value}], totalRecords}` |
| `table` | `{records: Map[], fields: string[]?}` + `pagination {totalCount,currentPage,pageSize,totalPages}` |
| `recent` | `{records: Map[], totalCount}` |

`timeRange` presets consumed server-side: `TODAY|7D|30D|90D|1Y` (+`LAST_7_DAYS`/`LAST_YEAR`
aliases); table `pageSize` clamps at 500. Per-widget failures arrive as `{error: "Cannot
filter on a masked field: ssn"}` — render, never retry-around.

**Report execute response** (verified): attributes `{reportId, reportName, reportType,
columns: [{fieldName,label,type}], records[], groups?, groupAggregations?}`, meta
`{totalCount, currentPage, pageSize, totalPages}`. `groups` present ⇒ render records grouped
under group headers with the `groupAggregations` row (flat list; matrix rendering deferred).

**Drill-through:** `buildListUrl(collectionName, [{field, operator:'equals', value}])` →
`/:tenant/app/o/<collection>?filter=<JSON FilterCondition[]>` (the exact
`parseListViewParams` format). The widget `config` JSON carries the target collection
reference; resolve id→name through the bootstrap collection store
(`CollectionStoreContext`) and **degrade to non-clickable** when unresolvable. Chart
segment → `[{field: groupByField, operator:'equals', value: label}]`; skip when label is
`"(empty)"`. Table/recent row click → `/app/o/<collection>/<recordId>` when the row has an
id.

### New FE modules

```
src/pages/app/AnalyticsHubPage/AnalyticsHubPage.tsx        (route /app/analytics)
src/pages/app/DashboardViewPage/DashboardViewPage.tsx      (route /app/dashboards/:id)
src/pages/app/DashboardViewPage/widgets/{MetricWidget,ChartWidget,TableWidget,RecentWidget,WidgetFrame}.tsx
src/pages/app/ReportViewPage/ReportViewPage.tsx            (route /app/reports/:id)
src/hooks/useDashboardData.ts    // components join + POST data; queryKey ['dashboard-data', id, timeRange]
src/hooks/useReportExecution.ts  // execute + pagination; queryKey ['report-execution', id, page, pageSize]
src/pages/app/ObjectListPage/listUrlState.ts   // EXTRACTED: parseListViewParams + updateParams + NEW buildListUrl
```

`ChartWidget` renders `series` directly with recharts (`ResponsiveContainer` +
`BarChart`/`PieChart`; the page-builder `chart` builtin at
`PageBuilderPage/widgets/builtins/chart.tsx` is the idiom reference — its props contract
differs, do not force reuse). `WidgetFrame` owns title band, loading skeleton, and the
error state. Table cells render via `FieldRenderer` when field metadata is resolvable, else
plain text.

## 4. DB migrations

None — frontend-only. (Numbering reminder if that changes: check the migration directory;
next is V163 after slice 1's V162.)

## 5. File-by-file code changes

| File | Change |
|------|--------|
| `src/pages/app/ObjectListPage/listUrlState.ts` | **New** — move `parseListViewParams`/`updateParams` helpers out of `ObjectListPage.tsx` verbatim; add `buildListUrl(tenantSlug, collection, filters, sort?)`. `ObjectListPage.tsx` imports from it (no behavior change; its tests keep passing untouched). |
| `src/pages/app/AnalyticsHubPage/` (+tests, index) | **New** — two JSON:API list queries, dashboard cards + report rows, empty/loading/error states, nav to viewers. |
| `src/pages/app/DashboardViewPage/` (+tests, index) | **New** — `useDashboardData` join (components list + data POST), CSS-grid from positions/spans, widget renderers, time-range `Select` (presets; re-runs the data query), per-widget `WidgetFrame` error/loading, drill-through wiring. Table widget repages via the single-component endpoint. |
| `src/pages/app/ReportViewPage/` (+tests, index) | **New** — `useReportExecution`, columns/records table (grouped rendering when `groups` present), pagination controls, Export CSV/PDF (AnalyticsPage `getBlob` + link-click pattern), 400 error state (masked-field message verbatim). |
| `src/hooks/useDashboardData.ts`, `src/hooks/useReportExecution.ts` | **New** — shapes per §3; `useApi()` client; staleTime 60 s. |
| `src/App.tsx` | Three lazy imports + routes under the EndUserShell outlet, each wrapped `RequirePermission("VIEW_ANALYTICS")` (fallback = its default denied message). |
| `src/shells/EndUserShell/navTabs.ts` | `NavTab.kind` union gains `'dashboard' \| 'report'`; `menuItemToTab` recognizes `/dashboards/<id>` and `/reports/<id>` (and `/app/`-prefixed forms), `target` = id. Unit tests extended. |
| `src/shells/EndUserShell/TopNavBar.tsx` | Desktop tab click routes new kinds (`…/app/dashboards/<id>`, `…/app/reports/<id>`); mobile sheet gains an **Analytics** group (Separator + uppercase header + entries, mirroring the Collections/Pages sections at lines ~126-161); hub entry link. |
| `src/components/UserMenu/UserMenu.tsx` | "Analytics" item → `/app/analytics`, rendered only when `hasPermission('VIEW_ANALYTICS')` (`useSystemPermissions` already imported there). |
| `src/i18n/translations/en.json` | `analytics.*` keys: hub title/sections/empty, widget error prefix ("This widget is unavailable"), time-range labels, export labels, report pagination strings. Other locales fall back. |
| `e2e-tests/tests/end-user/analytics-viewer.spec.ts` | **New, post-deploy, skip-gated**: hub lists a dashboard → open → metric renders a value → chart segment click lands on a filtered `ObjectListPage` (URL contains `filter=`) → report runs → CSV export downloads. Negative: user without `VIEW_ANALYTICS` sees the denied fallback. |

No gateway change (routes exist), no worker change, no SDK change.

## 6. Test plan

- **Vitest**: `listUrlState` (extraction parity: parse⇄build round-trip, defaults stripped,
  `buildListUrl` encoding); `navTabs` new kinds (+ ignored-path unchanged); hub (two lists,
  empty state, nav targets); `DashboardViewPage` (grid style from positions/spans, one
  renderer test per widget type from canned §3 payloads, `{error}` widget → error frame,
  time-range change re-queries with the preset); `ChartWidget` drill-through (segment click
  → navigate with equals filter; `(empty)` label inert); `ReportViewPage` (columns/rows,
  pagination params, export calls `get` vs `getBlob`, 400 masked message shown verbatim);
  `UserMenu` gating on the permission.
- **Worker/harness**: N/A — no backend change (slice 1's `AnalyticsAuthzScenarioTest`
  already guards the gate this UI sits behind).
- **Playwright**: post-deploy spec per §5 (`test.describe.skip`-gated until the routes
  deploy — live-env constraint, per precedent).
- `/verify` green before PR (frontend lint/typecheck/format/coverage; Java untouched but
  runs anyway).

## 7. Docs to update (same PR)

- `.claude/docs/status.md` — Reports & dashboards row: end-user viewer ships
  (`DashboardDataController` gains its first UI consumer); note preset-only time ranges +
  accessLevel deferral in the gap column.
- `.claude/docs/specs/_archive/app-surfacing/README.md` — slice 3 marked shipped.
- `.claude/docs/conventions.md` — `buildListUrl`/`listUrlState.ts` as the canonical
  drill-through helper (parent promised this row).
- `.claude/docs/architecture.md` — one line on the viewer consuming the pass-through
  dashboard-data contract (widgets keyed by componentId; layout joined client-side).
- Memory `project_app_ux_roadmap.md` — tick slice 3.

## 8. Risks & open questions

- **Slice-1 merge order (hard dependency):** without the `VIEW_ANALYTICS` permission this
  UI's `RequirePermission` gate denies everyone but `MANAGE_REPORTS` holders never — the
  permission name simply doesn't exist in `/api/me/permissions`. Merge #1194 first.
- **Widget `config` collection reference shape** (id vs name) is not pinned here — the
  drill-through resolver must handle both via the collection store and degrade to
  non-clickable. Verify against a real authored dashboard during implementation.
- **No native dashboard authoring UI exists** (admin analytics = Superset embed; the old
  native pages were deleted). Until Phase 3's builder work, dashboards/components are
  authored via the generic admin resource browser — acceptable for v1, but demoing this
  slice requires seeding a dashboard by hand. Consider a harness/e2e fixture dashboard.
- **`RequirePermission` renders children while permissions load** (verified behavior) — a
  brief flash of the page shell before data 403s is possible; the BE gate is the boundary.
  Acceptable; the page-level error state handles the 403.
- **Grid collisions/overlaps** in hand-authored component positions render as CSS grid
  auto-placement quirks — v1 renders what the rows say; the future builder owns validation.
- **Bundle size**: recharts is already in the bundle (page-builder chart), but the three new
  pages must be `React.lazy` per-module like every other end-user page.
