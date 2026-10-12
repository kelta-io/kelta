# App Surfacing (Phase 1) — Parent Spec

> **Status:** parent planning spec. Authoritative shared contract for **surfacing already-built
> backends in the end-user app** (`/:tenant/app/*`): approvals, native reports/dashboards,
> WebSocket realtime, and saved list views. Each slice in the [Slice plan](#slice-plan) is
> expanded into its own child spec in this directory and **extends, never contradicts** the
> [Reuse Map](#reuse-map), [shared contracts](#shared-contracts), and [Security](#security)
> sections below.
>
> Source-verified against the codebase on 2026-07-07 (Flyway head **V1__baseline** after the
> #1189 flatten — next new migration is **V162**: deployed flyway_schema_history keeps pre-flatten numbering (entries up to V161), so lower numbers are silently skipped — check the directory + deployed history before numbering). If code
> and this doc disagree, trust the code and fix this doc.

## How to use this document

This parent defines the cross-cutting architecture once. Child specs each cover one PR-sized
slice with acceptance criteria, UI samples, exact contracts, DB migrations (or "none"),
file-by-file changes, and a test plan — per the [Child-spec template](#child-spec-template).
Read this parent first; every child references it.

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — Analytics authorization (`VIEW_ANALYTICS`) | `1-analytics-authz.md` — **SHIPPED 2026-07-08** | **backend, security** |
| 2 — Approvals inbox + record actions | `2-approvals-inbox.md` — **SHIPPED 2026-07-08** (timeline inline actions deferred; `GET /api/me/identity` added — the JWT `sub` proved unreliable; the `/api/approvals/**` gateway static route was missing and is registered in this slice) | **workflow UX (FE+BE hardening, security)** |
| 3 — End-user analytics viewer | `3-analytics-viewer.md` — **SHIPPED 2026-07-08** | **dashboards/reporting UX (FE)** |
| 4 — Realtime client | `4-realtime-client.md` — **SHIPPED 2026-07-08** (invalidation-only; nav-derived subscriptions) | **liveness (FE)** |
| 5 — Saved views in the app | `5-saved-views.md` — **SHIPPED 2026-07-08** (personal localStorage + shared read-only `list-views`; view columns drive the grid) | **data-entry UX (FE)** |

**Dependency order (hard edges): 1 → 3.** Slices 2, 4, 5 are independent of each other and of
1/3. Slice 4 enhances 2 and 3 (live refresh) but neither depends on it.

## Context

The 2026-07-07 app-UX evaluation found the platform's biggest end-user gaps are **not missing
backends — they are missing frontends.** Four subsystems are complete and tested server-side
but unreachable or unconsumed from the end-user app:

1. **Approvals** — `ApprovalController` ships submit/approve/reject/recall/status/history +
   record locking; the end-user app renders approvals **read-only** in `ActivityTimeline`.
   No inbox, no action buttons.
2. **Native reports + dashboards** — `ReportExecutionController` (execute, CSV/PDF export)
   and `DashboardDataController` (653-line service, widget data, per-viewer masking) are
   gateway-routed and working; **no UI consumes `DashboardDataController` at all** (the old
   native ReportsPage/DashboardsPage were removed when admin `/analytics` became a Superset
   embed). End users have no analytics surface.
3. **WebSocket realtime** — gateway `/ws/realtime` (`RealtimeWebSocketHandler`,
   `RealtimeBridge` ← `kelta.record.changed`) is built; **zero frontend references** to
   WebSocket exist in `kelta-ui/app/src`. Everything polls (React Query staleTime 5 min).
4. **Saved views** — `useSavedViews` + `ViewSelector` exist and work, but are wired only into
   the admin `ResourceListPage`; the end-user `ObjectListPage` manages state purely via URL
   params with a hardcoded first-6-fields column set.

**Intended outcome:** each persona gets a first-class surface from existing machinery —
workflow users an actionable approvals inbox, dashboard/reporting users a native viewer with
drill-through, data-entry users saved views, everyone live data — with the one real backend
gap (analytics endpoints having **no authorization beyond gateway `API_ACCESS`**) closed
first as a security slice.

## Key architecture decisions (verified against the code)

- **Authorization before exposure (slice 1 gates slice 3).** `ReportExecutionController` and
  `DashboardDataController` perform **no in-controller permission checks** (verified; also
  flagged in `concerns.md`). They currently hide behind admin-only nav. Before any end-user
  route points at them, they get the standard in-controller check (`requirePermission`
  pattern — `CerbosPermissionResolver.getProfileId(request)` +
  `BootstrapRepository.findProfileSystemPermissions`, the same mechanism `/api/admin/**`
  uses) against a **new `VIEW_ANALYTICS` system permission**. `MANAGE_REPORTS` (already
  seeded in V1 baseline, already the FE gate on admin `/analytics`) stays the authoring/admin
  permission; `VIEW_ANALYTICS` is the run/consume permission.
- **"My approvals" needs no new backend.** `approval-instances` and
  `approval-step-instances` are system collections served by the generic JSON:API. Inbox
  queries are plain filters: pending-on-me =
  `GET /api/approval-step-instances?filter[assignedTo][EQ]=<userId>&filter[status][EQ]=PENDING`;
  my-submissions = `GET /api/approval-instances?filter[submittedBy][EQ]=<userId>`. Act via the
  existing `POST /api/approvals/{instanceId}/approve|reject|recall`.
- **Approval actor identity must be hardened (slice 2, security).** Today
  `approve()`/`reject()`/`recall()` read the acting `userId` from the **request body**
  (optional field). Authorization is only implicit (step-instance lookup by `assigned_to`) —
  a caller who knows an assignee's id can act as them. Slice 2 changes the controller to
  resolve the actor from the gateway-forwarded identity (`X-User-Id`, the header the worker
  already trusts elsewhere) and **ignore any body-supplied `userId`**. Same for
  `submittedBy` on submit. Back-compat: body field tolerated but overridden.
- **Native dashboards, not Superset, for end users.** The end-user viewer renders
  `dashboard-components` (`componentType ∈ metric|chart|table|recent`, grid position/span
  fields, `config` JSON) via `POST /api/dashboards/{id}/data` and
  `/components/{componentId}/data`. Rationale: per-viewer masking is already enforced there
  (2026-07-05 work), drill-through into `ObjectListPage` is trivial, no guest-token plumbing
  per end user, and it composes with page-builder `chart`/`metric` widgets later. Superset
  stays the admin/BI power tool.
- **Realtime events are an invalidation signal, not a data channel (v1).** `RealtimeBridge`
  pushes record `data` to **every tenant subscriber with no per-subscriber FLS/Cerbos check**
  (masking-configured collections already suppress `data` via `containsMaskedFields`). The
  client therefore **never applies pushed `data` to caches** — on `record.changed` it
  invalidates the matching React Query keys and refetches over the authorized JSON:API path,
  where Cerbos/FLS/masking apply per viewer. This is an architectural rule for every consumer
  of the socket, not an implementation detail. (Server-side per-subscriber FLS is a possible
  v2; add to `concerns.md`.)
- **Saved views stay client-side in v1, fed by two sources.** Personal views remain the
  existing `localStorage` mechanism (`kelta_views_<collection>`); admin-authored
  `ui-list-views` rows (already configured via `ListViewsPage`, `MANAGE_LISTVIEWS`) surface
  read-only as **shared views** in the same `ViewSelector`. A view applies onto
  `ObjectListPage`'s existing URL state (`?filter/sort/page/pageSize`), so deep links keep
  working. Server-persisted per-user views (cross-device) are explicitly deferred.
- **Navigation extends `navTabs.ts`, nothing else.** `menuItemToTab` today recognizes
  `/resources/<collection>` and `/p|/app/p/<slug>` and ignores everything else. Slice 3 adds
  two path kinds — `/dashboards/<id>` and `/reports/<id>` — mapping to `kind:'dashboard'` /
  `kind:'report'` tabs routed to `/:tenant/app/dashboards/<id>` / `/app/reports/<id>`. Menu
  items stay the single per-tenant nav config (`ui-menus`/`ui-menu-items`, per-item
  `policies` already filter by profile).
- **Reuse-first.** No new table grid, filter bar, export plumbing, chart lib, or shell — see
  the [Reuse Map](#reuse-map).

## Reuse Map

Use these — do not rebuild.

| Need | Reuse | Path |
|------|-------|------|
| List/record page frames (slots) | `ListShell`, `RecordShell` | `kelta-ui/app/src/components/record/` |
| Data grid (virtual scroll, selection, row click) | `ObjectDataTable` | `kelta-ui/app/src/components/ObjectDataTable/` |
| Filter chips / active-filter UI | `FilterBar` | `kelta-ui/app/src/components/FilterBar/` |
| List URL state (canonical param format) | `parseListViewParams` / `updateParams` | `kelta-ui/app/src/pages/app/ObjectListPage/ObjectListPage.tsx` (extract to `listUrlState.ts` in slice 3/5 — first slice to land owns the extraction) |
| Saved-view model + persistence | `useSavedViews` (`SavedView`, `FilterCondition`) | `kelta-ui/app/src/hooks/useSavedViews.ts` |
| View picker UI | `ViewSelector` | `kelta-ui/app/src/components/ViewSelector/` |
| Record timeline (approval events already render) | `ActivityTimeline` | `kelta-ui/app/src/components/ActivityTimeline/` |
| HTTP + JSON:API + blob download | `apiClient` (`getPage`, `getBlob`, `postResource`, `getWithMeta`) | `kelta-ui/app/src/services/apiClient.ts` |
| Permission gating (FE) | `RequirePermission`, `useSystemPermissions` (`GET /api/me/permissions`) | `kelta-ui/app/src/` |
| Permission checks (BE, in-controller) | `requirePermission` pattern (`CerbosPermissionResolver` + `BootstrapRepository.findProfileSystemPermissions`) | e.g. `kelta-worker/.../controller/EnvironmentController.java` |
| Permission catalog (FE checklist) | `SYSTEM_PERMISSIONS` catalog | `kelta-ui/app/src/components/SecurityEditor/SystemPermissionChecklist.tsx` |
| Charts | `recharts` (already a dep; page-builder `chart` widget as reference) | `kelta-ui/app/src/pages/PageBuilderPage/widgets/builtins/chart.tsx` |
| Report/dashboard defs | `reports`, `dashboards`, `dashboard-components` system collections | `runtime-core/.../SystemCollectionDefinitions.java` |
| Approval data | `approval-instances`, `approval-step-instances` system collections + `ApprovalController` | `kelta-worker/.../controller/ApprovalController.java` |
| Realtime server | `RealtimeWebSocketHandler`, `SubscriptionManager`, `RealtimeBridge` | `kelta-gateway/.../websocket/` |
| Toasts | `sonner` | existing |
| i18n | `useI18n`/`t()`, JSON per locale | `kelta-ui/app/src/i18n/translations/*.json` |

## Shared contracts

### Approvals (existing endpoints — slice 2 consumes, hardens identity)

```
POST /api/approvals/submit                {collectionId, recordId, processId?}      → {success, instanceId, status, message}
POST /api/approvals/{instanceId}/approve  {comments?}                               → {success, instanceId, status, message}
POST /api/approvals/{instanceId}/reject   {comments?}                               → {success, instanceId, status, message}
POST /api/approvals/{instanceId}/recall   {}                                        → {success, instanceId, status, message}
GET  /api/approvals/status?collectionId&recordId                                    → {hasActiveApproval, ...}
GET  /api/approvals/lock-status?collectionId&recordId                               → {locked, ...}
```
Actor identity: resolved server-side from the gateway-forwarded user header; body
`userId`/`submittedBy` ignored (slice 2). Step instance shape (JSON:API
`approval-step-instances`): `{approvalInstanceId, stepId, assignedTo, status: PENDING|
APPROVED|REJECTED|REASSIGNED, comments, actedAt}`.

### Analytics (existing endpoints — slice 1 gates, slice 3 consumes)

```
POST /api/reports/{reportId}/execute?page[number]=&page[size]=   (size ≤ 2000)
GET  /api/reports/{reportId}/export?format=csv|pdf               (blob)
POST /api/dashboards/{dashboardId}/data                          {timeRange?, startDate?, endDate?}
POST /api/dashboards/{dashboardId}/components/{componentId}/data
```
All four gain `requirePermission(VIEW_ANALYTICS)` in slice 1 (403 on missing grant;
`MANAGE_REPORTS` holders implicitly qualify — the check accepts either). Per-viewer masking
(`MaskingPrincipal`) already threads through both controllers — do not disturb it.

### Realtime client protocol (existing server contract — slice 4 consumes)

```
connect   wss://…/ws/realtime?token=<jwt>          (close 4000 = policy violation, 4001 = token expired)
send      {"action":"subscribe","collection":"<name>"}    (max 50 subs/session, 100 conns/tenant)
send      {"action":"unsubscribe","collection":"<name>"}
recv      {"action":"subscribed","collection":"<name>"} | {"action":"error","message":"…"}
recv      {"event":"record.changed","collection":"…","changeType":"CREATE|UPDATE|DELETE",
           "recordId":"…","data":{…}|null,"timestamp":"…"}
```
**Client rule (authoritative):** on `record.changed`, invalidate matching query keys
(`['collection-records', collection]`, `['related-records', collection, *]`, record detail,
approvals/timeline keys) and let React Query refetch. **Never write `data` into a cache.**
Reconnect: exponential backoff + resubscribe from a subscription registry; token refresh on
4001; do nothing while `useOnlineStatus` reports offline (the offline `SyncEngine` already
owns reconnect sync).

### Saved view (existing model — slice 5 wires; shared views added)

`SavedView { id, name, collectionName, filters: FilterCondition[], sortField, sortDirection,
visibleColumns, pageSize, isDefault }` (localStorage `kelta_views_<collection>`). Shared
views: `ui-list-views` rows mapped read-only into the same shape (`source:'shared'`),
non-deletable/renamable from the app. Applying a view = writing `ObjectListPage` URL params
(`?filter=<JSON FilterCondition[]>&sort=[-]field&pageSize=n`) + `visibleColumns` state.

### Drill-through URL (slice 3 → `ObjectListPage`)

`/:tenant/app/o/<collection>?filter=[{"id":"f1","field":"status","operator":"equals","value":"active"}]`
— the exact `parseListViewParams` format. v1 drill-through emits **equals** conditions only
(dashboard group-by value, table row field); anything unmappable navigates unfiltered. The
first landing slice (3 or 5) extracts a shared `buildListUrl(collection, filters, sort?)`
helper into `pages/app/ObjectListPage/listUrlState.ts` and both slices use it.

## Security

- **Slice 1 and slice 2 are security-typed PRs — never auto-merged** (per `SECURITY.md` +
  standing rule). Slice 1 closes the `concerns.md` "reports/dashboards reachable with bare
  `API_ACCESS`" item; update that row in the same PR.
- **New permission `VIEW_ANALYTICS`** follows the V157 `MANAGE_DELEGATED_ADMINS` pattern
  (now folded into V1 baseline): **V162 migration** backfills a grant per existing profile
  (granted where the profile already holds `MANAGE_REPORTS`; plus granted to the built-in
  Standard User profile — opinionated default: standard users may *run* analytics, Minimum
  Access may not); `TenantProvisioningHook` seeds it for new tenants; FE
  `SystemPermissionChecklist` catalog gains the entry. It is **not** added to
  `PrivilegedPermissions.SET` (it must stay delegatable/grantable).
- **Approval identity**: actor from forwarded identity header only (slice 2). Add a worker
  unit test proving a body-supplied `userId` is ignored, and a `kelta-test-harness` scenario
  (real Postgres) proving a non-assignee acting on a step is rejected.
- **Realtime**: no per-subscriber FLS server-side — hence the invalidation-only client rule
  above. Document the residual server-side exposure (subscriber sees collection names +
  record ids + non-masked `data` broadcast tenant-wide) in `concerns.md`; per-subscriber
  filtering is v2.
- **Masking**: report/dashboard group-by/filter/sort on viewer-masked fields already reject
  server-side; the viewer surfaces that error state verbatim (no client workaround).
- JWT rides a query param on the WS upgrade (existing design); never log the URL with token.

## Slice plan

- **Slice 0 — Parent spec + doc wiring** (this file). Author parent + child specs; add the
  specs row to `CLAUDE.md`; fix the stale Flyway-head lines in `CLAUDE.md` (code head is
  **V1__baseline**, doc said V159). No code.
- **Slice 1 — Analytics authorization** (backend, security). V162 migration seeding
  `VIEW_ANALYTICS`; `TenantProvisioningHook` + FE checklist catalog entries;
  `requirePermission` gates on the four analytics endpoints (accepting
  `VIEW_ANALYTICS` or `MANAGE_REPORTS`); worker unit tests (grant/deny/either-permission) +
  harness scenario (real RLS: granted profile 200, denied 403). Admin `AnalyticsPage`
  behavior unchanged (`MANAGE_REPORTS` implies access).
- **Slice 2 — Approvals inbox + record actions** (FE + BE hardening, security).
  `/app/approvals` (`ApprovalsInboxPage`): tabs *Pending on me* / *My submissions* over the
  two JSON:API filters; approve/reject dialog with comments; recall on my pending
  submissions. Record surfaces: submit-for-approval button on `RecordShell` header (shown
  when `GET /api/approvals/status` reports no active approval and a process exists for the
  collection), approve/reject inline on the timeline's pending approval entry, lock badge
  from `lock-status`. `TopNavBar` bell wired to pending-on-me count (its first real feed;
  click → `/app/approvals`). BE: actor-identity hardening (above). Nav: fixed inbox entry in
  the user menu + optional `/approvals` menu-item path kind. i18n keys `approvals.*`.
- **Slice 3 — End-user analytics viewer** (FE; depends on 1). Routes `/app/analytics`
  (hub: dashboards + reports the caller can run), `/app/dashboards/:id`
  (`DashboardViewPage`: CSS-grid from `dashboard-components` position/span; widget renderers
  `metric`/`chart` (recharts)/`table`/`recent`; per-widget loading/error/masked states;
  time-range control posting `{timeRange}`), `/app/reports/:id` (`ReportViewPage`: run,
  paginate, export CSV/PDF via `getBlob` — reuse the `AnalyticsPage` download pattern).
  Drill-through via `buildListUrl` (chart segment click, table row click). Routes
  `RequirePermission(VIEW_ANALYTICS)`-gated (mirror of the BE gate). `navTabs.ts` gains
  `/dashboards/<id>` + `/reports/<id>` path kinds (+ `TopNavBar` routing + mobile sheet
  group). All new pages `React.lazy` per-module (bundle-size rule), i18n `analytics.*`.
- **Slice 4 — Realtime client** (FE). `src/realtime/`: `RealtimeClient` (connect w/ token,
  subscribe registry, backoff reconnect + resubscribe, 4001 token refresh, offline gating) +
  `RealtimeProvider` mounted in `EndUserShell` (admin shell later) + `useRealtimeInvalidation`
  wiring `record.changed` → query invalidation for the visible collection(s), record detail,
  and approvals keys. Invalidation-only rule enforced by design (no cache writes). Debounce
  bursts (e.g. 250 ms per collection). Vitest with a fake WebSocket; document the manual
  smoke path (Playwright can't hold two sessions cheaply — e2e is a stretch goal).
- **Slice 5 — Saved views in the app** (FE). `ViewSelector` into `ObjectListPage`'s
  `ListShell` toolbar slot; `useSavedViews` for personal views; shared views mapped from
  `ui-list-views` (read-only entries); default view auto-applied on first visit (explicit
  URL params always win); `visibleColumns` finally drives the column set (replacing the
  hardcoded first-6 rule — falls back to it when a view has no columns). Owns the
  `listUrlState.ts` extraction if slice 3 hasn't landed it.

Every FE slice: Vitest in-PR; post-deploy Playwright spec (skip-gated
`test.describe.skip` until the route is live, per `page-builder-v2.spec.ts` precedent) under
`e2e-tests/tests/end-user/`; i18n keys for every new string (other locales fall back).

## Child-spec template

Each `<slice>.md` must include every section (sections that don't apply state "N/A —
<reason>", never omit silently):

1. **Goal & scope** — delivers / does not / parent sections it conforms to.
2. **UI samples** — wireframes, before/after; sample payloads for BE-only.
3. **Data & API contracts** — exact TS interfaces / JSON shapes / endpoint signatures;
   versioning + back-compat.
4. **DB migrations** — exact Flyway `V<n>__*.sql` or "None". Verify head before numbering
   (baseline file **V1__baseline**, next **V162** — deployed history keeps pre-flatten numbering).
5. **File-by-file code changes** — every file, functions/components, registration/wiring.
6. **Test plan** — Vitest (FE), worker unit w/ mocked JdbcTemplate/QueryEngine (BE),
   kelta-test-harness scenario (DB/RLS-sensitive), post-deploy Playwright.
7. **Docs to update** — specific rows per CLAUDE.md Rule 6.
8. **Risks & open questions** — fragile files (`concerns.md`), sequencing, user decisions.

## Docs to update (per CLAUDE.md Rule 6)

- `.claude/docs/status.md` — approvals row: "actionable end-user inbox" out of gaps;
  reports/dashboards row: end-user viewer + `VIEW_ANALYTICS`; realtime row: FE consumption;
  new "App surfacing" row while in flight.
- `.claude/docs/concerns.md` — close the reports/dashboards bare-`API_ACCESS` item (slice 1);
  add realtime per-subscriber-FLS residual + invalidation-only client rule (slice 4).
- `.claude/docs/architecture.md` — analytics endpoint authorization row; realtime client
  contract note.
- `.claude/docs/conventions.md` — invalidation-only realtime rule; `buildListUrl` as the
  canonical drill-through helper; `VIEW_ANALYTICS` vs `MANAGE_REPORTS` semantics.
- `CLAUDE.md` — reference-doc `specs/` row (this parent); Flyway head lines (V1 baseline).
- Memory `project_app_ux_roadmap.md` — mark Phase 1 slices as they ship.
