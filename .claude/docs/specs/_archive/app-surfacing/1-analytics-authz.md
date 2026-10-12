# Slice 1 — Analytics Authorization (`VIEW_ANALYTICS`)

> Child spec of [App Surfacing (Phase 1)](./README.md). Conforms to the parent's
> [Key architecture decisions](./README.md#key-architecture-decisions-verified-against-the-code),
> [Shared contracts → Analytics](./README.md#analytics-existing-endpoints--slice-1-gates-slice-3-consumes),
> and [Security](./README.md#security) sections.
>
> **Security-typed change — never auto-merged** (closes a live authorization gap).
> Source-verified 2026-07-08 (Flyway head file V1__baseline; next new migration V162 — deployed flyway_schema_history keeps pre-flatten numbering, never number below it).

## 1. Goal & scope

**Delivers:** an in-controller permission gate on the four analytics endpoints
(`ReportExecutionController.executeReport/exportReport`,
`DashboardDataController.executeDashboard/executeComponent`), a new **`VIEW_ANALYTICS`**
system permission (run/consume analytics) seeded for new tenants and backfilled for existing
ones, and its frontend permission-catalog entry. Callers holding **either** `VIEW_ANALYTICS`
**or** `MANAGE_REPORTS` (the authoring permission) pass; everyone else gets 403.

**Does not deliver:** any end-user UI (slice 3), per-report/per-dashboard `accessLevel` /
folder-sharing enforcement (explicitly deferred — v1 is permission-gated only), changes to
the admin `AnalyticsPage` (its users hold `MANAGE_REPORTS` and are unaffected), or changes to
scheduled report delivery (`ScheduledJobExecutorService` calls `ReportExecutionService`
directly at the service layer — the controller gate does not apply to it; unchanged
system-trust tier, same contract as data-masking's null-principal rule).

**Why now:** both controllers are reachable today by any `API_ACCESS` holder (e.g. a PAT)
with **no further check** — flagged in `concerns.md`. Slice 3 points end-user routes at these
endpoints, so the gate must land first (parent hard edge 1 → 3).

**Behavior change (intended):** an integration currently calling these endpoints with bare
`API_ACCESS` and no `MANAGE_REPORTS`/`VIEW_ANALYTICS` starts receiving 403 after deploy.

## 2. UI samples

N/A — backend-only. Sample payloads:

```
POST /{tenant}/api/reports/{reportId}/execute        (profile holds VIEW_ANALYTICS)
→ 200 { "reportId": "…", "columns": […], "rows": […], "page": {…} }   (shape unchanged)

POST /{tenant}/api/reports/{reportId}/execute        (profile holds neither permission)
→ 403 (Spring ResponseStatusException body)
   { "timestamp": "…", "status": 403, "error": "Forbidden",
     "message": "VIEW_ANALYTICS permission required", "path": "…" }

POST /{tenant}/api/dashboards/{id}/data              (no identity headers, e.g. stripped)
→ 403 "No identity"
```

Admin Setup → Profiles → System Permissions gains one checklist row under **Data &
Reporting**: "View Analytics — Run reports and view dashboards" (rendered by the existing
`SystemPermissionChecklist`; no new UI).

## 3. Data & API contracts

- **New permission name (string constant):** `VIEW_ANALYTICS`. Stored as
  `profile_system_permission.permission_name` rows (one per profile, `granted` boolean),
  same as all 23 existing permissions. **Not** added to `PrivilegedPermissions.SET`
  (`kelta-worker/.../service/delegated/PrivilegedPermissions.java`) — it must remain
  delegatable/grantable.
- **Gate semantics (authoritative):** `granted(VIEW_ANALYTICS) || granted(MANAGE_REPORTS)`
  → pass; missing/blank profile id → 403 `"No identity"` (fail-closed); else → 403
  `"VIEW_ANALYTICS permission required"`. Same `ResponseStatusException(HttpStatus.FORBIDDEN)`
  mechanism as `EnvironmentController.requirePermission` (the canonical `/api/admin/**`
  pattern — `CerbosPermissionResolver.getProfileId(request)` +
  `BootstrapRepository.findProfileSystemPermissions(profileId)` returning
  `List<Map<String,Object>>` rows `{permission_name, granted}`).
- **Endpoint shapes unchanged.** The gate runs before any service call;
  `MaskingPrincipal` resolution (`principalOf(request)`) is untouched.
- **Default grants matrix** (new tenants via `TenantProvisioningHook`, existing tenants via
  V162 — identical outcome):

  | Built-in profile | `VIEW_ANALYTICS` | Rationale |
  |------------------|------------------|-----------|
  | System Administrator | granted | holds `MANAGE_REPORTS` |
  | Solution Manager | granted | holds `MANAGE_REPORTS` |
  | Standard User | granted | parent-spec default: standard users run analytics |
  | Read Only | granted | profile description: "View all records and reports" |
  | Marketing User | granted | "Standard User plus …" |
  | Contract Manager | granted | "Standard User plus …" |
  | Minimum Access | **not granted** | login-only profile |
  | Custom (non-system) profiles | granted **iff** profile holds granted `MANAGE_REPORTS` | conservative default; admins opt in via the checklist |

- **No NATS subject, no cache invalidation.** The gate queries
  `profile_system_permission` per request through `BootstrapRepository` (verified: the
  reference implementation does an uncached `queryForList`); no in-memory registry holds
  these rows, so Critical Rule 1 does not apply. (Verify at implementation time that
  `findProfileSystemPermissions` is still uncached; if a cache has appeared, follow its
  existing invalidation path.)

## 4. DB migrations

**`kelta-worker/src/main/resources/db/migration/V162__seed_view_analytics_permission.sql`**
(numbering starts at V162 — deployed environments retain pre-flatten history entries up to V161, so anything lower is silently skipped by Flyway; verify the directory + deployed history before numbering):

```sql
-- Backfill VIEW_ANALYTICS for every existing profile, all tenants.
-- Runs under Flyway with app.current_tenant_id unset -> the admin_bypass RLS policy
-- (USING current_setting('app.current_tenant_id', true) = '') permits the cross-tenant write.
-- granted = profile already holds granted MANAGE_REPORTS, OR it is a built-in (is_system)
-- profile other than 'Minimum Access'. Idempotent via NOT EXISTS.
INSERT INTO profile_system_permission
    (id, tenant_id, profile_id, permission_name, granted, created_at, updated_at)
SELECT
    gen_random_uuid()::text,
    p.tenant_id,
    p.id,
    'VIEW_ANALYTICS',
    (EXISTS (SELECT 1 FROM profile_system_permission mr
             WHERE mr.profile_id = p.id
               AND mr.permission_name = 'MANAGE_REPORTS'
               AND mr.granted = true)
     OR (p.is_system = true AND p.name <> 'Minimum Access')),
    now(), now()
FROM profile p
WHERE NOT EXISTS (SELECT 1 FROM profile_system_permission x
                  WHERE x.profile_id = p.id
                    AND x.permission_name = 'VIEW_ANALYTICS');
```

Notes: `gen_random_uuid()` is core Postgres ≥13 (no pgcrypto needed); `created_by`/
`updated_by` stay NULL like the baseline seed rows; the `NOT EXISTS` guard makes re-runs and
mixed hook/migration ordering safe.

## 5. File-by-file code changes

| File | Change |
|------|--------|
| `kelta-worker/src/main/resources/db/migration/V162__seed_view_analytics_permission.sql` | **New** — SQL above. |
| `kelta-worker/src/main/java/io/kelta/worker/controller/ReportExecutionController.java` | Add `BootstrapRepository` constructor dep (5th). Add `private void requireAnalyticsAccess(HttpServletRequest request)` — the `EnvironmentController.requirePermission` pattern, accepting `VIEW_ANALYTICS` or `MANAGE_REPORTS` (one permissions fetch, one `anyMatch` over both names). Call it first in `executeReport(...)` (line ~70) and `exportReport(...)` (line ~134), before any service/`principalOf` work. |
| `kelta-worker/src/main/java/io/kelta/worker/controller/DashboardDataController.java` | Same: `BootstrapRepository` dep + `requireAnalyticsAccess` + call first in `executeDashboard(...)` (~63) and `executeComponent(...)` (~138). (Two private copies mirror the existing per-controller convention — `EnvironmentController`, `DelegatedAdminScopeController` each own theirs; do not invent a shared helper class in this slice.) |
| `kelta-worker/src/main/java/io/kelta/worker/listener/TenantProvisioningHook.java` | Append `"VIEW_ANALYTICS"` to `ALL_PERMISSIONS` (lines 35-43). Add it to `grantedPermissions` for System Administrator, Solution Manager, Standard User, Read Only, Marketing User, Contract Manager (lines 122-147). Minimum Access unchanged (empty set — the insert loop writes the row with `granted=false`). |
| `kelta-ui/app/src/components/SecurityEditor/SystemPermissionChecklist.tsx` | Add to the **Data & Reporting** category (lines ~104-132): `{ name: 'VIEW_ANALYTICS', label: 'View Analytics', description: 'Run reports and view dashboards' }`. `SYSTEM_PERMISSIONS` flatMap picks it up automatically. |
| `kelta-worker/src/test/java/io/kelta/worker/controller/ReportExecutionControllerTest.java` | Extend (or create, matching `EnvironmentControllerTest` conventions): mock `CerbosPermissionResolver` + `BootstrapRepository`; new cases below. Update constructor call sites for the new dep. |
| `kelta-worker/src/test/java/io/kelta/worker/controller/DashboardDataControllerTest.java` | Same. |
| `kelta-worker/src/test/java/io/kelta/worker/listener/TenantProvisioningHookTest.java` | Extend: `VIEW_ANALYTICS` present in `ALL_PERMISSIONS`; grants matrix assertion (granted for the six profiles, not Minimum Access). |
| `kelta-test-harness/src/test/java/io/kelta/testharness/scenarios/AnalyticsAuthzScenarioTest.java` | **New** — scenario below. |

No gateway change (static routes `reports`/`dashboards` exist; blanket `API_ACCESS` filter
unchanged). No SDK change. No i18n change (permission labels live in the checklist catalog,
which is currently English-only like its 23 siblings).

## 6. Test plan

**Worker unit (Mockito, per `EnvironmentControllerTest` pattern — mock resolver/repo, real
controller, `TenantContext.set/clear` around each test):** for each of the two controllers —
- no identity (`getProfileId` → null) → 403, `verifyNoInteractions(service)`;
- profile with neither permission (rows present, `granted=false`) → 403, no service call;
- `VIEW_ANALYTICS` granted → 200 path reaches the service;
- `MANAGE_REPORTS` granted only → 200 path reaches the service (fallback accepted);
- both endpoints of each controller covered (execute + export / dashboard + component).

**`TenantProvisioningHook` unit:** catalog contains `VIEW_ANALYTICS`; per-profile grant
matrix matches §3.

**kelta-test-harness (real Postgres + RLS + Flyway V162 — the migration itself is under
test):** `AnalyticsAuthzScenarioTest` extends `ScenarioBase`:
1. `auth.loginAsAdmin()`; create a minimal report via JSON:API `POST /api/reports` (admin
   holds `MANAGE_REPORTS` → passes the gate; proves fallback acceptance live).
2. Via `openDbConnection()`: `profileIdByName(db, tenantId, "Standard User")` and
   `"Minimum Access"`; `seedActiveUser(...)` one user per profile; `directLogin(...)` each.
3. Standard User calls `POST /{slug}/api/reports/{id}/execute` → **200** (V162 backfill
   granted it — asserts the migration's grant rule on real data).
4. Minimum Access user calls the same → **403**.
5. Same 200/403 pair against `POST /{slug}/api/dashboards/{id}/data` (create a dashboard row
   via JSON:API first; an empty-components dashboard is fine — the gate fires before data
   work).
6. DB assert: `profile_system_permission` has a `VIEW_ANALYTICS` row for every seeded
   profile (migration coverage), granted flags per §3.

**Playwright e2e:** N/A — no UI surface changes (admin `AnalyticsPage` users hold
`MANAGE_REPORTS`, behavior identical). The end-user-facing positive/negative e2e is owned by
slice 3's spec (post-deploy, per parent).

**`/verify` green before PR** (standard).

## 7. Docs to update (same PR)

- `.claude/docs/concerns.md` — **close** the "Report/Dashboard/Package controllers reachable
  with bare `API_ACCESS`" item for reports+dashboards (leave the `PackageController` part —
  already `CUSTOMIZE_APPLICATION`-gated per the sandbox work; verify and reword the residual).
- `.claude/docs/status.md` — Reports & dashboards row: note endpoints now
  `VIEW_ANALYTICS`/`MANAGE_REPORTS`-gated; add the App-surfacing slice-1 progress note.
- `.claude/docs/architecture.md` — "Authorizing a new endpoint" section: add the analytics
  row (in-controller `requireAnalyticsAccess`, either-permission semantics).
- `.claude/docs/conventions.md` — one line: `VIEW_ANALYTICS` = run/consume analytics,
  `MANAGE_REPORTS` = author/admin (and implies view).
- `.claude/docs/specs/_archive/app-surfacing/README.md` — mark slice 1 shipped in the slice table.
- Memory `project_app_ux_roadmap.md` — tick slice 1.

## 8. Risks & open questions

- **PAT/integration break (intended):** callers with bare `API_ACCESS` lose access at
  deploy. No known first-party caller besides the admin UI (holds `MANAGE_REPORTS`) and
  scheduled jobs (service-layer, ungated). Release note it.
- **Permission-read caching:** §3 assumes `findProfileSystemPermissions` stays an uncached
  per-request query. If a cache exists by implementation time, reuse its invalidation path —
  do not ship a stale-grant gate.
- **Ordering — hook vs migration:** new tenants provisioned *after* this deploy get the row
  from `TenantProvisioningHook`; V162's `NOT EXISTS` guard makes the overlap harmless either
  way.
- **`Read Only` grant is opinionated** (its seeded permission set is only `VIEW_ALL_DATA`,
  no `API_ACCESS` — such users may not be able to reach the API at all today; the grant is
  inert until they can). Flag in PR description for reviewer sign-off.
- **Per-resource sharing (`reports.accessLevel`, folders) is NOT enforced** by this slice —
  every `VIEW_ANALYTICS` holder can run every report/dashboard in the tenant. Documented
  deferral (parent §Security); revisit when folder sharing gets a real model.
- **Auto-merge must stay OFF** (security-typed).
