# Slice 3 — Page Grouping + Aggregates

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR as slice 4). Frontend-only; not security-typed. Consumes SavedView v2's
> `groupBy` (owned by [slice 2](./2-list-power.md)).

## 1. Goal & scope

Table-view grouping on `ObjectListPage`: pick a field, rows bucket under collapsible
group-header rows showing the group value, a row count, and sums for every visible
numeric column (`number` / `currency` / `percent`). Grouping is **client-side over the
fetched page only** — an explicit "Groups reflect this page only" caption says so; real
cross-page aggregates stay the report engine's job. The group field is prepended to the
server sort so groups arrive contiguous. `groupBy` is captured into saved views and
restored by `view=` deep links. **Not delivered:** cross-page aggregates, multi-level
grouping, group-by on the admin `ResourceListPage` (prop stays unset there).

## 2. UI samples

Toolbar row grows a picker: `[Views ▾] [Columns] [Normal] [Group by ▾]`. With grouping
active the table shows `▾ Status: Active (12) · Sum of Amount: 4,210.50` header rows;
clicking the chevron collapses that bucket. Empty values group under "—".

## 3. Data & API contracts

- Server: unchanged. The page silently prepends `{groupBy, asc}` to the `sort=` param it
  already sends (grammar verified in slice 2) so buckets are contiguous; the user's own
  sort levels order rows *within* groups. The URL `sort=` param is not rewritten.
- `ObjectDataTable` opt-in prop: `groupBy?: string`. Absent = today's rendering (admin
  grid untouched). Present ⇒ non-virtual path (page ≤ 200 rows; header rows break the
  fixed-height row estimate), grouped body, keyboard nav over *visible* (non-collapsed)
  rows, select-all still spans the whole page including collapsed rows.
- Collapse state is component-local, keyed `<groupField>:<groupKey>` so switching the
  group field never leaks collapsed buckets across fields (no reset effect needed).
- Group label resolves through `lookupDisplayMap` for reference fields, `String(value)`
  otherwise, `—` for null/empty.
- SavedView v2 `groupBy` round-trips through save/apply/deep-link (model shipped in
  slice 2; this slice makes it live).

## 4. DB migrations

None.

## 5. File-by-file code changes

`ObjectDataTable.tsx` (`groupBy` prop, group bucketing + header rows + collapse, visible-
row keyboard nav, virtualization opt-out, +`ObjectDataTable.grouping.test.tsx`) ·
`ObjectListPage.tsx` (groupBy state, Group-by picker, effective-sort prepend, caption,
save/apply/deep-link capture) · `en.json` (`listPower.groupBy`, `noGrouping`,
`groupsPageOnly`, `groupSum`).

## 6. Test plan

Vitest: buckets render in server order with counts; numeric sums per visible numeric
column (skips null); empty-value bucket labeled `—`; collapse hides rows and select-all
still selects them; `groupBy` unset renders identically to before (existing suites
green). Playwright: post-deploy — group by a picklist field, assert header rows + a
collapse. `/verify` green before PR.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md row · memory.

## 8. Risks & open questions

- Grouping turns off virtualization; a 200-row page renders fully. Fine today (page
  clamp), revisit if the HTTP clamp is ever raised.
- Sums use `Number(value)` on the raw record value — formula/rollup fields that serve
  strings sum only when numeric-parsable; non-parsable values are skipped, not errors.
