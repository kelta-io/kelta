# Slice 6 — Calendar View

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR as slices 5+7). Frontend-only; not security-typed. **No new dependency** — a
> month grid is CSS grid + `Intl`/native `Date` (parent rule; a calendar library would
> need explicit justification).

## 1. Goal & scope

`viewType='calendar'` renderer on `ObjectListPage`: month grid bound to one
date/datetime field (`typeConfig.calendar.dateField`), records appear as chips in their
day cell (overflow `+N more`), click-through to the record, prev/today/next month pager.
The visible month is queried through the standard fetch by merging
`gte/lte` range conditions on the date field into the existing filter set (verified
server grammar — `FILTER_OPERATOR_MAP` already maps them). **Not delivered:** week/day
views, drag-to-reschedule, multi-day span rendering (`endDateField` reserved in the
model), timezone controls (browser-local rendering).

## 2. UI samples

Toolbar adds `[Date: Due date ▾]` (date/datetime fields only) when calendar is active.
Grid: 7-column month, weekday header, today ringed, ≤3 chips per day then `+N more`;
header `July 2026` with `← Today →` pager.

## 3. Data & API contracts

- Fetch = the table's query + two appended `FilterCondition`s
  (`greater_than_or_equal` / `less_than_or_equal` on the date field, `YYYY-MM-DD`
  bounds of the visible month). URL `filter=` param untouched (the range is view
  state, not a user chip — `FilterBar` shows only user filters).
- Calendar fetch uses pageSize 200 (the HTTP clamp) so a month isn't truncated at the
  table's page size; when `total > 200` a caption says only the first 200 are shown.
  Pagination is hidden in calendar view.
- Day bucketing: `String(value).slice(0, 10)` — ISO date and datetime both bucket by
  their date part, browser-local month arithmetic in `monthRange`/`addMonths` helpers
  (exported for tests + the page).
- Date field: `typeConfig.calendar.dateField`, default = first accessible
  date/datetime field; none ⇒ inline empty-state.
- Month state is page-local (not in the URL, not saved in the view — a saved calendar
  view reopens on the current month).

## 4. DB migrations

None.

## 5. File-by-file code changes

`components/CalendarMonthView/` (new, +tests: helpers + grid) · `ObjectListPage.tsx`
(date picker, effective-filters merge, renderer branch, month state) · `en.json`
(`altViews.*`).

## 6. Test plan

Vitest: `monthRange`/`addMonths`/`currentMonthKey` math (incl. year wrap), records land
in the right day cells, overflow `+N more`, pager fires `onMonthChange`, empty-state
without a date field. Playwright post-deploy: switch to calendar, page a month, open a
record. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md · memory.

## 8. Risks & open questions

- Datetime bucketing is by ISO date part (UTC date for `Z` timestamps), not the
  viewer's local calendar day — off-by-one possible near midnight for non-UTC viewers.
  Accepted for v1 (matches how the table renders raw dates); revisit with timezone
  handling.
- 200-row clamp per month is honest-but-bounded; a busy collection should filter first.
