---
title: Dashboards, reports and analytics
description: Report definitions with CSV and PDF export and scheduled delivery, dashboards with metric, chart, table and recent widgets, the end-user analytics viewer, and optional embedded BI.
section: console
order: 70
---

## Permissions

| Permission | Allows |
|---|---|
| `VIEW_ANALYTICS` | run reports, view dashboards (`/app/analytics`, `/app/dashboards/<id>`, `/app/reports/<id>`) |
| `MANAGE_REPORTS` | author reports and dashboards |

Every built-in profile except *Minimum Access* has `VIEW_ANALYTICS`.

## Reports

A report (`reports`) is a saved query over a collection: `reportType` (`TABULAR`, `SUMMARY`, `MATRIX`), columns,
`filters`, `rowGroupings`, `columnGroupings`, `sortOrder`, and `relatedJoins` for lookups. Running it executes a
dynamic query with a 30-second timeout and pages the result.

| Action | Endpoint |
|---|---|
| Run | `POST /api/reports/{id}/execute` — paged rows |
| Export | `GET /api/reports/{id}/export?format=csv` or `format=pdf` (landscape A4, repeated header, page numbers) |
| Schedule delivery | a scheduled job of type `REPORT_EXPORT` with `config.recipients`; the CSV is attached (over 10 MB the email links instead) |

Masking applies to the person running or exporting a report; filtering, sorting or grouping on a field masked
for that person is rejected. Scheduled deliveries run at system trust and are not masked — choose recipients
accordingly.

## Dashboards

A dashboard (`dashboards`) is a `columnCount`-wide grid of components (`dashboard-components`), each positioned
with **1-based** `columnPosition` / `rowPosition` and spans. Four component types exist:

| Type | Shows | `config` |
|---|---|---|
| `metric` | one aggregate — `SUM`, `AVG`, `MIN`, `MAX`, `COUNT` | function, field |
| `chart` | bar (default) or pie | `chartStyle`, `groupByField` (lookups are resolved to display names) |
| `table` | a record grid | columns, filters, sort |
| `recent` | a record grid styled as a feed | same as table |

A dashboard has a page-level time range; a component can opt out (`ignoreTimeRange: true`) or pin its own
(`fixedTimeRange: TODAY | 7D | 30D | 90D | 1Y`). Chart segments drill through to the filtered list. `dynamic`
dashboards run as a fixed `runningUserId` instead of the viewer.

The grid model and every row attribute are in the [authoring reference](/docs/reference/dashboards/).

## Authoring

- **Console** — Setup → Analytics: report builder; dashboards are authored as records (a visual dashboard
  builder is planned).
- **CLI** — `kelta reports list|get`, `kelta dashboards list|get --components|apply`.
- **MCP** — `apply_dashboard` upserts a dashboard and its components in one call, keyed on the dashboard name and
  each component's title.

## Embedded BI

When the deployment configures Apache Superset (`SUPERSET_*` variables), the Analytics hub embeds Superset
dashboards with guest tokens and synchronises collections as datasets. See
[Configuration reference](/docs/deploy/configuration/).
