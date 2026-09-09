# Codebase Concerns

Living catalog of known risk areas. Resolved items moved to "Resolved" section
at the bottom so reviewers can see what's already been addressed.

## Security Risks

**No malware scanning exists anywhere in the platform, and the support mailbox makes
strangers the upload source (support-mailbox slice 9).** Until inbound mail shipped, every
byte in object storage arrived from an authenticated user of a tenant. `support@` inverts
that: anyone with an SMTP client can now put a file into Garage and a filename into the
console. There is no ClamAV, no scanning sidecar, and no third-party scanning integration
in any repo — `mailbox_attachment.scan_status` exists and is written `UNKNOWN` for every
row that is not caught by SES's own verdict.

What actually protects the platform today, in order:
- **SES scans before we ever see it.** A `virus` verdict of `FAIL` means attachments are
  discarded at ingest and never stored (`MailboxIngestService`), and the thread is marked
  SPAM so no SLA clock starts. This is the only real scanning in the path, it is Amazon's,
  and it only covers mail arriving through SES.
- **Nothing is ever executed or rendered server-side.** Attachments are bytes we store and
  hand back; no thumbnailer, no text extractor, no preview generator opens them. Most
  scanning gaps become incidents through exactly that kind of processing, and there is none.
- **Every download is inert by construction.** `AttachmentContentType.serveAs` refuses to
  name an active type, `Content-Disposition: attachment` is unconditional, and
  `X-Content-Type-Options: nosniff` stops the browser rescuing what we declined to name.

What that leaves: a malicious file reaches an agent's own machine if they choose to save
and open it. Nothing above prevents that, and `scan_status = UNKNOWN` is honest about it.
`INFECTED` is already enforced on the download path, so wiring a real scanner means writing
that value — the refusal is built and waiting for it.

Worth doing before a mailbox is opened to a high-volume public address. Not worth blocking
on for a low-volume support address whose mail already passes SES's scanner.

**Public-traffic controls — what is enforced, and where (consumer-alerting slice 7).**
Rate limiting is now Redis-backed everywhere, so a budget is the fleet's rather than each
replica's. Three things are worth knowing before changing any of it:
- **`/portal/**` is limited in kelta-auth, not the gateway.** kelta-auth has its own ingress
  and that traffic never reaches the gateway, so a gateway `ip-paths` entry for
  `/portal/api/signup` would match nothing while *reading* like protection. Adding a portal
  path to the gateway map is a no-op; add it to `kelta.auth.rate-limit.ip-paths` instead.
- **The fixed-window TTL must never be refreshed per request.** Both implementations
  (`RedisRateLimiter` in the gateway, `PortalPublicRateLimitFilter` in kelta-auth) carry the
  same Lua guard. Refreshing it turns the window into an idle-expiry that never resets under
  sustained traffic — the 2026-07-11 lockout, see Known Bugs below. The duplication is
  deliberate: one side is reactive, the other servlet-blocking. **They must stay behaviourally
  identical**; each has a regression test asserting the guard.
- **Limiter keys never trust the leftmost `X-Forwarded-For` entry.** kelta-auth counts
  `trusted-proxy-count` hops back from the right. Honouring the leftmost value would let a
  caller mint a fresh bucket per request by varying one header, i.e. make the limiter
  decorative. Set the count to 0 when the service is directly exposed.

Everything fails **open** on a Redis outage except the bot challenge's replay claim, which
fails **closed** — a limiter failing open leaves the other controls standing, while a
challenge failing open is an open door on the endpoint it exists to protect.

**The e2e suite runs at ~84% of the tenant rate limit.** The tenant window is
`(apiCallsPerDay / 288) * 5` = **1735 requests per 5 minutes** at the 100k default; the
Playwright run drives roughly 1450 into it, as one admin in one tenant with no think-time. That
margin is thin enough that ordinary runner variance tips it into a tenant-wide 429 — it already
produced one incident-shaped failure (see Known Bugs) and it is why `docker-compose.yml` now
exempts the private compose network via `RATE_LIMIT_EXEMPT_CIDRS`. **That exemption is
local/CI only** — deployed environments use the homelab-argo manifests and never read that
file. The consequence to know: e2e no longer exercises either rate limiter at all, so limiter
regressions must be caught by unit tests.

**Sizing a per-user budget as a fraction of the tenant window is a trap worth remembering.**
The tenant window is derived from the tenant's *whole* governor budget, and one admin, bulk
import, or integration PAT legitimately is most of a tenant. A 0.5 `user-share` therefore
halved real single-user throughput — it looked conservative and was in fact a functional
regression, caught only because the e2e suite is a faithful single-user workload. The default
is 0.9: bound the runaway credential, do not attempt fair division.

`PublicSurfaceTest` pins the gateway's unauthenticated prefix allowlist against the shipped
`application.yml`. Widening that surface is one line of YAML and nothing else in the build
would notice, so the test fails on any change — update it deliberately, and confirm the new
endpoint carries its own trust anchor, since an unauthenticated path also bypasses
`TenantIpAllowlistFilter`.

**`TenantContext.runAsPlatform`/`callAsPlatform` is a silent-data-loss trap (2026-07-04).**
The javadoc claims a `platform_bypass` RLS policy grants access under the `__platform__`
sentinel — **that policy does not exist in any migration**. `TenantAwareDataSource` binds the
sentinel as a literal tenant id, so `tenant_isolation` matches nothing and `admin_bypass`
(empty-string) doesn't apply: every RLS-scoped read returns zero rows and writes are dropped.
The sandbox/promotion services deliberately use explicit `callWithTenant(<uuid>, …)` per hop
instead. Do not introduce new `runAsPlatform` callers until a real `platform_bypass` policy is
migrated onto every RLS table.

**Sandbox environments + metadata promotion are a destructive prod-config surface (V158).**
Guardrails that MUST stay intact:
- In-controller permission gates: `MANAGE_SANDBOXES` on `/api/environments/**` +
  `/api/promotions/**`, `CUSTOMIZE_APPLICATION` on `/api/packages/**`. The gateway static
  routes give these only blanket `API_ACCESS` — removing the controller checks exposes
  whole-tenant config export and destructive promotion to every Standard User.
- Approver ≠ creator + APPROVED-only execute (four-eyes on prod-config mutation).
- Security types (`ROLE`/`POLICY`/`ROUTE_POLICY`/`FIELD_POLICY`) are excluded from promotion —
  a compromised sandbox must not rewrite production authz.
- Sandbox provisioning copies the parent's IP allowlist onto the sandbox tenant and replaces
  the seeded admin's well-known default password with a one-time random secret — the
  IP-allowlist filter is fail-open on missing config, so skipping the copy exposes a clone of
  production config to any IP.
- `RemotePromotionClient` is an SSRF-adjacent surface (`remote_base_url` is admin-supplied):
  scheme allow-list http/https, no userinfo, redirects disabled, bounded timeouts/response,
  PAT only from the credential vault. Private-range IPs are allowed **deliberately**
  (in-cluster/homelab targets are the use case).
- Accepted v1 limitations: per-item import (no global tx — DDL + NATS can't roll back;
  re-runs converge via natural-key upsert); rollback re-imports the pre-promotion target
  snapshot but does not undo deletions/creations; remote targets get no local snapshot or
  rollback; large promotes emit one NATS config event per imported item (sequential — same
  profile as bulk schema authoring); sandbox tenants run the full Svix/Superset hook chain and
  count against platform quotas; ≤60s gateway slug-cache lag after sandbox create (masked by
  the CREATING status while cloning).

**`POST /api/operations` (atomic ops) now enforces per-collection authorization (closed 2026-07-05).**
`AtomicOperationsController` is a static gateway route (blanket `API_ACCESS` only), so the gateway's
per-collection Cerbos verb check never ran on it — previously any API user could batch-write
collections the normal dynamic route would deny. The controller now mirrors that gateway check:
before executing, it maps each operation to a Cerbos action (`add→create`, `update→edit`,
`remove→delete`), resolves the collection's UUID (`CollectionLifecycleManager.getCollectionIdByName`),
and calls the new worker-side `CerbosAuthorizationService.checkCollectionAccess(...)` — the
object-level (`collection` resource, keyed on **UUID**, same as the gateway) verb check. Any denied
operation fails the **whole batch** with 403 before anything executes (atomic); checks are deduped
per (collection, action). **Fail-closed**: a missing identity (no `X-User-Profile-Id`/`X-Cerbos-Scope`)
or a Cerbos circuit-open/error denies. The `IdentityCollectionGuardHook` (identity collections) still
runs underneath as defense-in-depth. **Pre-merge gate:** the harness Cerbos is allow-all, so real
*deny* can only be verified on a live stack (same caveat as the object-policy fix) — unit tests cover
allow/deny/dedup/no-identity with a mocked authz service. Guardrail: keep this check keyed on the
collection **UUID** (`getCollectionIdByName`), not the name — the `collection` policy CEL is
UUID-keyed (see architecture.md "Cerbos collectionId keying").

**Delegated administration is a privilege-boundary surface (V157).** Guardrails that MUST stay
intact: the `DelegatedAdminScopeValidationHook` rejection of privileged profiles/permsets at scope
save, the `DelegatedAdminService.effectiveScope` **request-time re-filter** (a profile granted a
privileged permission after being scoped must silently drop out — do not "optimize" it into a cache
that skips the re-check), the `DelegatedUserAdminController` field whitelist (email/managerId/mfa
immutable, self-edit blocked), and the `IdentityCollectionGuardHook` default-deny for identified
writes. `MANAGE_DELEGATED_ADMINS` rides the platform's "object perms == config perms" posture for
the scope collection's own generic route, same as every other setup collection. Security feature →
**not auto-merged**.

**Mass-email campaigns are a spam-capable, partly-public surface (V152).** Guardrails that
MUST stay intact:
- `CAMPAIGN_TRACKING_SECRET` **must be set to a strong per-deployment value.** It signs the HMAC
  tracking tokens for the public `/api/track/{open,click,unsubscribe}` endpoints. The dev default in
  `CampaignProperties` is a placeholder — if it ships to prod, an attacker can forge unsubscribes and
  poison open/click stats. Inject it via env/secret like `KELTA_ENCRYPTION_KEY`.
- `/api/track/**` is on the gateway `unauthenticated-paths` allowlist (all methods, no JWT). The
  token is the only authenticator — never widen this path or accept an unsigned recipient id.
- The click redirect (`CampaignTrackingController.isSafeUrl`) only forwards to `http(s)` URLs; keep
  that guard (open-redirect / `javascript:` protection).
- Sends are gated on `MANAGE_CAMPAIGNS`, the suppression list (checked per recipient), the
  `campaignEmailsPerDay` governor, and a `send-rate-per-second` throttle. Don't add a bulk-send path
  that bypasses the suppression check or the governor. Campaign writes deliberately do **not** go
  through the generic collection route (the collections are read-only) — keep it that way.

**Power controllers gated only by blanket `API_ACCESS` + RLS (2026-07-02 audit).**
`PackageController` does **not** enforce a specific system permission in-controller — it
relies on tenant-scoping (RLS) plus the gateway's blanket `API_ACCESS` check and the fact
that its UI sits behind `VIEW_SETUP` nav. A PAT holder with only `API_ACCESS` can call it
directly. `DataExportController` was the most exfiltration-sensitive (one call →
whole-tenant dump) and is gated on `VIEW_ALL_DATA` (2026-07-02); **`BulkOperationsController`
write endpoints (create/upload/abort) are now gated on `MANAGE_DATA`** (2026-07-04 — bulk
writes bypass per-record Cerbos advice, so the in-controller gate is the boundary; reads
keep `API_ACCESS`); **`ReportExecutionController` + `DashboardDataController` are now gated
on `VIEW_ANALYTICS` (or `MANAGE_REPORTS`)** (2026-07-08, app-surfacing slice 1 — closes the
reports/dashboards half of this item). Remaining follow-up:
`CUSTOMIZE_APPLICATION`/`VIEW_SETUP` for packages.

**Approval instances/steps are tenant-visible to every user (accepted for now, 2026-07-08).** *(Also applies to `user-ui-preferences` (2026-07-08): rows are tenant-readable via the generic route — writes are owner-guarded by `UserPreferenceGuardHook`, reads are not; saved-view filters may embed data values. Same row-level-read v2 fix.)*
`approval-instances` and `approval-step-instances` are ordinary system collections on the
generic JSON:API routes with no row-level read restriction beyond tenant RLS — any tenant
user can list every approval (including `comments`). This predates the approvals inbox
(`ActivityTimeline` always read these routes; the inbox added server-side filters but no new
exposure). Deferred fix: a row-level read policy (submitter/assignee/admin) needs a design
pass — likely a read-side hook or Cerbos record policy on these collections. Revisit before
approvals carry sensitive comments in anger.
**Realtime socket has no per-subscriber FLS (accepted, 2026-07-08).**
`RealtimeBridge` fans each `kelta.record.changed` event to every tenant subscriber —
collection name, record id, and (for non-masking collections) the record `data` — with no
per-subscriber field-level check. The frontend mitigates via the invalidation-only rule
(see conventions.md): pushed `data` is never rendered or cached. Residual exposure: a
subscriber learns change activity (ids + non-masked data on the wire) for collections they
subscribe to. Per-subscriber filtering server-side is v2.

**Record merge write path — field-level write-FLS not applied (accepted trade-off).**
`RecordMergeController` (`POST /api/collections/{name}/merge`) applies the master's
`fieldOverrides` and re-parents children by calling the `QueryEngine` **from the service
layer**, so the controller-advice `CerbosFieldWriteSecurityAdvice` (per-field write-FLS) does
**not** run — the same shape as `DuplicateDetectionService`'s read path. The security boundary
is the in-controller `MANAGE_DATA` gate (a bulk data-management authority) plus RLS + record
validation/before-save hooks (which *do* fire through the `QueryEngine`). A `MANAGE_DATA` holder
can therefore set an override on a field they'd be denied at the field level via the normal PATCH
route. Acceptable because merge is an all-or-nothing data-admin operation, but revisit if merge is
ever exposed to a lower-privilege role. It is deliberately **not** auto-invoked by any flow.

**Network-level defense-in-depth** (out of tree):
- Worker, auth, ai pods reachable only via gateway in production. Pod-to-pod
  bypass mitigated by K8s NetworkPolicy + service-mesh mTLS, not by app code.
  Verify on cluster: `kubectl get networkpolicy -n kelta`.

**Tenant IP allowlist — `X-Forwarded-For` trust (accepted trade-off):**
- `TenantIpAllowlistFilter` allows a request when **any** IP in the chain (socket +
  every `X-Forwarded-For` hop + `X-Real-IP`) matches an allowed CIDR. This is
  deliberately topology-resilient but means a non-admin could inject an allowed IP via
  `X-Forwarded-For` to bypass the restriction. Chosen by the tenant/operator; tighten to
  socket-only with `kelta.gateway.ip-allowlist.trust-forwarded-for=false` where the proxy
  hop count is known. The filter is **fail-open** by design (missing config, disabled, or
  `MANAGE_TENANTS` holder → allow), so it hardens access but is not a hard security
  boundary on its own — pair it with the network-level controls above.

**Data masking now covers JSON:API + the main egress paths; a few contexts stay v2
(accepted, documented).** Masking (`fieldTypeConfig.masking` + `MASKED` visibility →
`unmask` Cerbos deny) is enforced by `CerbosFieldSecurityAdvice` (strip→mask), the
`MaskedFieldPredicateInterceptor` filter/sort guard, and realtime `data` suppression
(`containsMaskedFields`). **Covered as of PR 2:**
- Data export (`VIEW_ALL_DATA`-gated) and report execution/CSV/PDF export — mask via
  `RecordMaskingService.maskRows` keyed to the requesting user (export threads the
  requester into the async job; reports resolve it in the controller). Reports also
  reject filter/sort/group-by on a field masked for the executor.
- Full-text `search_index` (`display_value` + tsvector) and semantic embeddings —
  masking-configured fields are excluded at index/embed time (field-config level, since
  the shared index has no per-viewer identity), and the collection is reindexed when a
  field's masking config toggles (`FieldConfigEventPublisher` → `rebuildCollectionIndexAsync`).
- Dashboards (`DashboardDataService`) — **now masked per-viewer**: the controller resolves a
  `MaskingPrincipal` from the gateway identity headers (`CerbosPermissionResolver`, same as
  reports). When the widget's target collection has any masking-configured field the shared
  widget cache is **bypassed** (it isn't keyed on the viewer, so a can-unmask user would poison
  it for a can't-unmask one), the table/recent row widgets `maskRows` keyed to the viewer, and
  chart group-by / table sort / any filter on a field masked for the viewer is rejected
  (`WidgetExecutionException`, mirroring reports). Collections with no masking config are
  unchanged (shared cache, no Cerbos). A `null`/system principal is not masked (FLS trust tier).
**Still v2 (emit plaintext / unmasked to their gated callers):**
- Scheduled report delivery + background/system exports run with a `null` principal
  (system trust tier) and are **not** masked — same contract as flows below.
- Flows/scripts/webhooks/NATS consumers — system trust tier, same contract as FLS today.
- Bulk ops / merge — write-side, same accepted trade-off as write-FLS above (their result
  payloads are status/id/counts only, not field values, so no read egress). **Duplicate
  detection is closed (2026-07-05)**: `POST /{collection}/duplicates` returns the match-field
  `values` verbatim, so a match on a field masked for the requester is rejected 400
  (`DuplicateDetectionService` via `MaskingPrincipal` + `RecordMaskingService`) — a grouping key
  can't be masked without collapsing distinct values, so reject (mirrors group-by).
- Stale pre-existing vectors when a source field's masking toggles — **closed (2026-07-05)**.
  `EmbeddingOnWriteHook` already skips embedding a masking-configured source on write (verified);
  the gap was rows embedded *before* masking was added. `FieldConfigEventPublisher` now, on a
  masking-presence toggle, purges the dependent VECTOR columns (`VectorMaintenanceService`
  → `StorageAdapter.clearVectorColumn`, a single `UPDATE … SET vec = NULL` per vector field via
  the adapter's schema/RLS-safe table ref) alongside the existing tsvector reindex, so no stale
  plaintext-derived vector survives to leak via semantic-search *ranking* (values in results are
  already masked by the read advice — the ranking was the inference oracle). Rows re-embed on
  their next write (masked source → stays NULL; unmasked → re-embeds). External adapters no-op.
Also: between storage-adapter decryption and the advice, plaintext exists in-process —
never log field values on that path. **`field_history` is now live** (writer:
`FieldHistoryHook`, wildcard, captures create/update/delete diffs of `trackHistory` fields;
read: `/api/field-history` system collection + gateway route). Its masked/hidden-field leak
risk is closed by `FieldHistorySecurityAdvice`, which resolves each row's *referenced*
collection+field and drops FLS-denied rows / redacts MASKED old+new values per requester
(the generic `CerbosFieldSecurityAdvice` can't, since it keys off the row's own
`field-history` type). Row-drop makes a page's returned count a lower bound — acceptable for
an audit feed. The same posture applies to **`record_version`** (collection-level snapshots,
V174) via `RecordVersionSecurityAdvice` — a snapshot carries *every* field value of a record,
so that advice is the single guard between full-record history and an under-privileged caller;
treat changes to it as security-sensitive.

**`record_version` growth + known limits** — with `collection.track_history` on, every
create/update/delete of every record in the collection writes a full jsonb snapshot row. That
is inherently high-growth (one full record copy per edit) and there is **no retention/pruning
policy yet** — revisit before production-scale tenants enable it broadly (options: per-tenant
version cap per record, age-based purge alongside `record_tombstone` archival). Two accepted
v1 limitations: the DELETED version's `changed_by` is the record's *last updater* (the router
passes no principal into the delete path — threading the deleter through `QueryEngine.delete`
is a cross-cutting signature change, deferred), and version detail renders historical lookup
ids raw when they're not in the live page's `lookupDisplayMap` (no write-time display capture).

- **Flow/job execution logs grew without bound — mitigated by `FlowLogRetentionSweep`
  (consumer-alerting slice 2), but DRY-RUN by default so it is not yet actually pruning.**
  `flow_execution`, `flow_step_log`, `flow_pending_resume` and `job_execution_log` had no
  pruning at all — `JdbcFlowStore`'s only DELETE is `deletePendingResume` — so a
  high-frequency flow makes them the largest tables in the database. The sweep prunes terminal
  executions older than `kelta.flow.retention.max-age-days` (default 60), cascading to step
  logs and pending-resume rows, plus `job_execution_log` by the same age. **It is DRY-RUN by
  default** (`kelta.flow.retention.dry-run:true`, matching the telehealth purge posture): it
  only LOGS what it WOULD delete until an operator sets `dry-run=false`, so **this remains a
  growth concern in any environment left in dry-run**. Guardrails when armed: only terminal
  statuses (`COMPLETED|FAILED|CANCELLED`) are eligible, so a `WAITING` flow parked for months
  is never collected out from under itself; `FOR UPDATE SKIP LOCKED` keeps concurrent pods on
  disjoint slices; and work is capped per cycle at `batch-size × max-batches` per table so a
  first arming against a large backlog drains gradually rather than in one enormous delete
  against a shared Postgres. Age is `COALESCE(completed_at, started_at)` because
  `completed_at` is nullable and a terminal row missing one would otherwise never age out.
  **Deletion is irreversible and purged runs vanish from the flow-run observability UI** — the
  60-day default keeps recent history; confirm that window suits the environment before
  arming, and watch `pg_stat_activity` on the first armed cycle. Related, still open: the
  `record_version` growth concern above, which this sweep does **not** address.

- **`analytics_event` is a new high-volume table — retention shipped with it (consumer-alerting
  slice 8), DRY-RUN by default.** The analytics-capture corpus (`analytics_event`, V183) is
  write-heavy by design (one row per search plus every client-emitted page-view / assistant
  question). It ships with `AnalyticsRetentionSweep` from day one — the same posture and
  guardrails as `FlowLogRetentionSweep` (@Scheduled hourly, `FOR UPDATE SKIP LOCKED`, batched,
  metric `kelta_worker_analytics_purged`) pruning rows older than
  `kelta.analytics.retention.max-age-days` (default 90). **It is DRY-RUN by default**
  (`kelta.analytics.retention.dry-run:true`) so, exactly like the flow-log sweep, it is not
  actually pruning until an operator arms it — **a growth concern in any environment left in
  dry-run**. If a single tenant's rate outgrows row-at-a-time inserts, batch the ingest writes
  and consider native partitioning by `occurred_at` month — deliberately deferred until real
  volume justifies it (no speculative partitioning). Privacy note: `member_id` deletion on
  account close must purge/anonymize these rows — owed with the later rollup/erasure slice.

## Known Bugs

(No open bugs from the original audit. See Resolved → Bugs.)

**FIXED (RouteRegistry authoritative member paths) — member-controller static routes were
shadowed by their backing collection's generic route, 403-ing portal members.** A member
resource served by a dedicated controller (e.g. `/api/watches` → `WatchController`, "API_ACCESS
only, controller owner-scopes") is registered as a `static-` route so the gateway skips
per-resource Cerbos (`RouteAuthorizationFilter` line ~153). But `watches`/`wins`/`devices`/
`billing` are ALSO system collections, and each auto-registers a generic route at the **same
path**. `RouteRegistry` keys by path (last-write-wins), and the collection route loads *after* the
static routes, so it overwrote `static-watches` with a UUID-id route → per-resource collection
Cerbos ran → a portal member (no collection role; base `resource_collection` policy is
`roles:["*"]`, matches nothing) got `403 "Insufficient permissions for read on watches"`, breaking
the whole consumer app. Never caught because the scenario tests exercise the DB/RLS layer, not the
gateway authz path. Fix: `RouteRegistry.AUTHORITATIVE_STATIC_PATHS` (`/api/watches|wins|devices|
billing/**`) — only a `static-` route may occupy those; a dynamic collection route for the same
path is ignored. Deliberately NOT a blanket "static wins": config collections (flows, reports, …)
are also `static-` bootstrap routes yet **rely** on their generic route's per-resource Cerbos and
must keep it. Any NEW member-controller route that shares a path with a system collection must be
added to that set.

**FIXED (V181__collection_fk_cascade) — collections became permanently undeletable once
used.** Deleting a collection is a generic record delete against the `collection` table
(`DynamicCollectionRouter` → `QueryEngine.delete`); `PhysicalTableStorageAdapter` maps
Postgres FK violation 23503 to a 409 `REFERENCED_RECORD`. 14 tables referenced
`collection(id)` with **no** `ON DELETE` action (approval_process, bulk_job, field_history,
file_attachment, layout_assignment, layout_related_list.related_collection_id, list_view,
note, page_layout, record_type, report.primary_collection_id, script_trigger,
validation_rule, email_template.related_collection_id). Several (field_history,
file_attachment, bulk_job, script_trigger) have no delete API, so once a collection had any
record written that emitted a `field_history` row it could never be deleted via API/CLI.
V181 sets `ON DELETE CASCADE` on the 13 that are *owned by* the collection (same semantics
as the V173 field-delete cascade), `ON DELETE SET NULL` on `email_template.related_collection_id`
(a reusable template only tagged against the collection; nullable), and cascades four
intermediate children on the delete tree so the recursive cascade completes
(approval_instance→approval_process, dashboard_component→report,
layout_assignment→{page_layout,record_type}). `field.reference_collection_id` (a lookup in
collection B pointing at A) already had `ON DELETE SET NULL` — left as-is (nulling the
target beats silently deleting B's field). Regression guard:
`CollectionDeletionScenarioTest` (kelta-test-harness) writes a record (→ field_history) +
a list view, then deletes the collection end to end and asserts the cascade.
**FOLLOW-UP (V182 + `CollectionDeletionGuardHook`) — V181 missed `record_version`, and the
cascade needed a guard.** Three things:

1. **`record_version` was still bare (V182).** V181's audit listed 14 direct FKs and fixed
   them, but `record_version` (V174, collection-level versioning) was not among them.
   `RecordVersionHook` writes a snapshot on **every** create/update/delete once
   `collection.track_history` is on, so a versioned collection was *still* undeletable after
   V181 with the identical 23503 → 409 symptom. This is the **third** occurrence of the
   class (V173 → `field(id)`, V181 → `collection(id)`, V182 → the one V181 missed), and V174
   introduced its bare FK one migration *after* V173 fixed the same class. Mechanical guard
   now: `CollectionDeletionGuardSchemaTest` parses the shipped migration DDL (in Flyway
   **version** order — lexicographic sorting puts `V181` before `V1__baseline`) and fails the
   build on any FK to `collection(id)` left with no `ON DELETE` action. A fourth cannot ship
   silently.
2. **S3 purge closed.** `file_attachment` **and** `bulk_job` both carry storage keys (the
   original note named only the former). The keys are collected and the objects deleted in
   **`beforeDelete`** — `afterDelete` is too late, because the DB cascade has already removed
   the rows and the keys are unrecoverable, the same constraint already documented on
   `AttachmentCleanupHook` (which covers the parent-*record* case and never sees a collection
   delete).
3. **Blast radius guarded.** The broken FK was accidentally a safety net; with the cascade in
   place one `DELETE /api/collections/{id}` destroys attachments, layouts, reports, rules,
   history and versions. The delete is now rejected 400 (a hook validation error) naming the
   counts unless the caller passes **`?force=true`** (surfaced as `--force` on
   `kelta collections delete`). Callers with no HTTP request context (flows, NATS, schedulers)
   cannot force and fail closed. Note the child tables are **not** uniformly shaped — `report`
   keys on `primary_collection_id`, `layout_related_list` on `related_collection_id`, and
   `script_trigger` has no `tenant_id` at all; the schema test pins each, because the hook's
   Mockito tests stub `JdbcTemplate` by SQL substring and pass either way.

**FIXED (physical table drop) — the last leak on the collection-delete path.** There was no
production `DROP TABLE` anywhere in `src/main`, and `CollectionLifecycleManager.teardownCollection`
only unregisters in-memory state, so every deleted collection left its tenant data table
behind. Latent until V181 (deletes never succeeded), real after it. Now:

- `StorageAdapter.dropCollection(definition)` is a **default no-op**. Storage the platform
  did not create is not ours to destroy — an `ExternalStorageAdapter` fronting a customer's
  database un-maps the collection and leaves the table alone. Only adapters that created the
  storage override this.
- `PhysicalTableStorageAdapter.dropCollection` issues `DROP TABLE IF EXISTS`, guarded by the
  same `systemCollection()` check `initializeCollection` uses to *skip* creation — dropping a
  Flyway-owned table would take out platform metadata for every tenant. Covered by
  `PhysicalTableStorageAdapterTest → DropCollectionTests`, including that refusal.
- `DispatchingStorageAdapter` must route it (it overrides), or the default no-op silently
  wins and nothing is ever dropped.
- `CollectionDeletionGuardHook` resolves the definition in `beforeDelete` and drops in
  `afterDelete`, because the `collection` row — hence the name, hence the table name — is gone
  once the delete commits. The stash carries the collection id so a stale `ThreadLocal` on a
  pooled thread can never drop the wrong table.

**Deliberately not `CASCADE`.** A master-detail child table holds a real FK to its parent's
table, so dropping a parent whose children still exist fails; the error is logged and the
table is left behind (the pre-existing behaviour) rather than `CASCADE` silently destroying
the child's constraint. Delete child collections first. The drop runs **after** the metadata
delete has committed, so it never throws — a failure degrades to a logged, actionable leak,
never a 500 on a delete that already happened.

**FIXED (fix/gateway-rate-limiter-fixed-window) — gateway rate limiter never reset its
window under continuous traffic → tenant-wide self-sustaining 429 lockout; root cause of
the "storage-ready" e2e flake (found 2026-07-11 from PR #1240's failing run).**
`RedisRateLimiter` refreshed the window key's TTL on EVERY request ("prevents keys from
persisting forever"), which turned the 5-minute fixed window into an idle-expiry: the
counter accumulated across the entire e2e run (~1700 requests ≈ the default 100k/day →
1735-per-window limit), tripped mid-suite, and then every rejected poll re-incremented the
counter and re-extended the TTL — permanent lockout until 5 full minutes of silence. In the
e2e logs this looked like a "worker wedge" (worker+postgres go quiet, tenant-scoped requests
vanish) but the worker was simply idle: the gateway was rejecting everything upstream, and
Playwright's serially-run late-alphabet journeys (rollup-summary, validation-rules) inherited
the exhausted window and burned their 30s readiness budgets on 429s. Same hazard existed in
production for any steadily-active tenant. Fix: atomic Lua INCR that sets the TTL only when
the window is new (or a prior EXPIRE was lost), plus Retry-After now reports the real
remaining window TTL instead of the full window. E2E `data-factory` also fails fast and loud
on 429 (no more silently sleeping through a multi-minute Retry-After inside a 30s budget).
Same investigation fixed three latent bugs it surfaced: `DefaultQueryEngine` passed the
literal `"default"` as tenantId to delete hooks (setup-audit rows for deletes always failed
their tenant FK — never persisted for real tenants); `FieldQuotaEnforcementHook` counted
`field WHERE tenant_id = ?` but `field` has no tenant_id column (query always threw, hook
fail-open → per-collection field quota was never enforced; now JOINs through `collection`);
and NATS consumers had no `maxDeliver`, so a poison message redelivered forever (now
maxDeliver=5 with a 2s delayed NAK).

**FIXED (fix/cerbos-record-check-shortcircuit) — per-record Cerbos batch checks ran (with
full record payloads) even for collections with no record-level rules; a read burst could
open the circuit breaker (found 2026-07-10, in production, after #1223).** The generated
`record` policy has only three rule kinds (`CerbosPolicyGenerator#generateRecordPolicy`):
per-collection CRUD mirrors of object permissions, VIEW_ALL/MODIFY_ALL overrides, and custom
rules — only custom rules can reference record data. Yet `CerbosRecordAuthorizationAdvice`
batch-checked every page with every record attribute (rich-text HTML included); on the
homelab PDP each 50-resource CheckResources took 0.5–1.4s, so 7 concurrent collection reads
(a static-site build) queued past the 2s timeout → chunks failed closed → 3 misses opened the
breaker → 10s of tenant-wide empty pages. Now: `RecordRuleIndex` (new, cached per tenant,
NATS policy-changed eviction alongside the field cache) reports whether a collection has ANY
enabled custom rule (conservative: CEL-bearing or not, unresolvable collection refs mark ALL
collections variant, lookup failure falls back to the batch); without variant rules the
advice replaces the batch with ONE `checkCollectionWideRecordAccess` sentinel check whose
ALLOW is cached per (tenant, profile, collection, action) — DENY is never cached because it
may be a fail-closed timeout/breaker artifact. Record-share widening unchanged. Caveat (same
as #1179): the harness Cerbos is allow-all, so real deny behavior is only verifiable on a
live stack — unit tests cover the path selection, caching, eviction and conservatism.
Deferred follow-ups: attribute slimming for the remaining batch path (send only CEL-referenced
attrs) and breaker tuning (client-load timeouts vs connectivity errors).

**FIXED (fix/unique-constraint-migration) — field `unique` flag toggles never reached the
physical schema, and unique-violation 409s named the wrong field (found 2026-07-10 operating
the student-incentives tenant).** (1) `SchemaMigrationEngine.migrateSchema` diffed added/
removed/type-changed fields but never the `unique` flag, so PATCHing `unique=false` updated
metadata while the Postgres constraint (default-named `<table>_<column>_key` from the inline
`CREATE TABLE ... UNIQUE`) kept rejecting inserts — cross-tenant-invisible and only fixable via
psql. Now a toggle resolves the actual single-column UNIQUE constraint name(s) from
`information_schema` (legacy default names included) and drops them (true→false) or adds a
hyphen-safe `uniq_<table>_<column>` constraint (false→true, idempotent under NATS event
redelivery, skipped when any constraint already covers the column). Composite `uniq_*`
*indexes* (CompositeUniqueConstraintService) and the EXTERNAL_ID unique index are untouched —
the catalog lookup matches single-column constraints only. History rows recorded as
`ALTER_UNIQUE`. (2) `detectUniqueViolationField` reported the *index name* for composite
violations and probed only fields still flagged `unique` (a stale physical index therefore
reported `unknown`); it now parses the Postgres error detail (`Key (country, slug)=(…)`) and
maps physical columns back to field names, falling back to the old name/probe paths for
drivers without that detail (H2). Tests: `SchemaMigrationEngineTest$UniqueFlagToggleTests`
(H2 toggle both ways, idempotence, composite-index survival) +
`PhysicalTableStorageAdapterTest$UniqueViolationColumnExtraction`.

**FIXED (fix/cerbos-batch-chunking) — list pages over 50 rows silently emptied by the Cerbos
batch limit (found 2026-07-10, in production).** The Cerbos server rejects CheckResources
requests over 50 resources (`INVALID_ARGUMENT: number of resources in batch (N) exceeds
configured limit (50)`). `CerbosAuthorizationService.batchCheckRecordAccess` sent the whole
page in one call, so any authenticated list read with `page[size] > 50` on a 51+-row collection
failed the check, fail-closed stripped **every** row, and the response went out as HTTP 200 with
`data: []` and a correct `totalCount` — indistinguishable from an empty collection. Three such
pages also tripped the Cerbos circuit breaker, denying ALL field+record access pod-wide for 10s
(one over-sized client query = tenant-wide authz brownout). Previously misdiagnosed as an MCP
`ResponseShaper` bug; MCP `query_collection` and REST both die on it because it lives in the
worker. `batchCheckFieldAccess` had the same unbounded batch (collections with >50 fields).
Both now split into ≤50-resource sequential Cerbos calls (`MAX_RESOURCES_PER_CHECK`): record
chunks fail closed independently (a bad chunk denies only its own records), field chunks deny
everything and skip the cache write on any failure (a partial allow-set under the full asked-set
would persist phantom denials). Regression tests in `CerbosAuthorizationServiceTest` (chunk
fan-out, partial-failure semantics, cache integrity).

**FIXED (fix/mcp-admin-tooling-gaps) — kelta-mcp admin tooling batch (7 defects found building a tenant end-to-end over MCP, 2026-07-10).**
(1) `FieldBodyBuilder.extractFirstId` substring-scanned for the first `"id"` after `"data"` and
returned `relationships.createdBy.data.id` — the acting *user's* UUID — because worker responses
serialize `relationships` before the record's `id`; every `add_field`-by-name and
`create_collection` inline field posted `collectionId=<user-uuid>` → 400 (collections silently
created with ZERO fields). Now Jackson-parsed in `AdminLookups`.
(2) `resolveNativeType` rejected most `FieldType` values (TEXT, CURRENCY, URL, EMAIL, PHONE, …);
now every enum name passes verbatim + friendly aliases added.
(3) MCP wrote `fieldTypeConfig.picklistSourceId` but the admin UI resolves
`fieldTypeConfig.globalPicklistId` (`usePicklistOptions`), and never set `referenceTarget` on
lookups — MCP-created fields showed no picklist binding / no target collection in the UI. The
canonical shape is the UI's; the builder now writes `globalPicklistId` + `referenceTarget`.
Fields created before that fix still carry the legacy dialect in `field.field_type_config`;
the UI readers (`resolveGlobalPicklistId` in `usePicklistOptions`, shared by `FieldEditor`,
`ObjectFormPage`, `ResourceFormPage`) now accept both shapes, so no data backfill is required.
(4)–(6) `create_validation_rule` / `create_listview` / `create_layout` posted camelCase paths
(`/api/validationRules`, `/api/listViews`, `/api/pageLayouts|layoutSections|layoutFields`) that
404 against the kebab-case system-collection routes, with attribute shapes the worker never
understood; `create_validation_rule` also documented INVERTED semantics (worker field is
`errorConditionFormula` — record rejected when TRUE; legacy `expression` args are negated).
`update_collection` gained `displayFieldName`→`displayFieldId` resolution.
(7) Worker: JSON-typed system-field defaults declared as strings (`list-views.filters "[]"`,
`dashboard-components.config "{}"`, `flow-executions.variables "{}"`) are injected verbatim on
create and fail their own type validation — all converted to JSON values, and
`SystemCollectionJsonDefaultsTest` now rejects any new string default on a JSON field. Validation
error envelopes rendered as `{"errors":[{}]}` (bean members dropped at serialization on the
deployed worker); `GlobalExceptionHandler` now emits plain maps (`JsonApiError.toMap()`), which
serialize reliably under any mapper config — never build error bodies from the bean directly.
*(Follow-up fix/mcp-kebab-leftovers, 2026-07-10: the batch missed three more camelCase paths —
`update_layout`/`delete_layout` (`/api/pageLayouts/{id}`) and `list_approvals`
(`/api/approvalInstances`) — all 404'd against the kebab routes; found when `delete_layout`
failed during tenant layout setup. New MCP tools MUST use kebab-case system-collection routes.)*

**FIXED (fix/hyphen-collection-refresh) — kebab-case collection names broke generated SQL identifier names.**
`PhysicalTableStorageAdapter.sanitizeIdentifier` (strict `[a-zA-Z0-9_]`) was fed raw
collection names when building generated index/constraint *names* (`idx_`/`hnsw_`/`fk_` in
`initializeCollection`, `uniq_` in `CompositeUniqueConstraintService.buildIndexName`) and the
FK *target table* reference. Collection names are conventionally kebab-case, so on any
hyphen-named collection: every NATS-triggered `CollectionLifecycleManager.refreshCollection`
threw `Invalid identifier: <name>` (multi-pod config refresh silently failing — surfaced once
the #1214–#1216 NATS reliability sweep made event delivery actually work), field DELETE
returned 500, and composite unique constraints could not be created at all. Fix:
`identifierPart()` maps hyphens→underscores for generated *names* only; table *references*
keep the raw name via `TableRef` quoting. Watch-out for future DDL: never pass a collection
name into `sanitizeIdentifier` directly — names go through `identifierPart`, references
through `TableRef`.

**FIXED (fix/runtime-core-forcing-jdbc-debug) — a library jar was setting production log levels.**
`runtime-core/src/main/resources/application.properties` is packaged *inside the jar*, so Spring
Boot loads it as application configuration for every service that depends on runtime-core —
**kelta-worker, kelta-ai, kelta-mcp, kelta-auth** (kelta-gateway does not depend on it). It
carried `logging.level.org.springframework.jdbc=DEBUG`, which put every prepared statement and
connection fetch into production logs: **493k lines/day on kelta-worker, ~92% of the platform's
entire log volume**, with full SQL text landing in Loki. It read as a worker problem for months
precisely because the setting isn't in kelta-worker — worker's own `logback-spring.xml` says
`<root level="INFO">` and its `application.yml` sets no `logging.*` at all. Gateway looked healthy
for the same reason: no runtime-core dependency. Guarded by
`RuntimeCoreDefaultPropertiesTest`. **Never put operational settings in that file** — it still
ships datasource credentials, Hikari pool sizes, Redis host and actuator exposure, every one of
which silently becomes a consuming service's value unless that service overrides it.

**FIXED (fix/schema-bootstrap-in-migrate-job) — three ways `initializeCollection` could abort a
collection permanently.** All three were found in Loki: seven couchpicks collections threw
`StorageException: Failed to initialize table` on *every* worker boot for months. Because the
throw happens before `reconcileSchema`, an affected collection never picks up later field
additions either — the physical table silently drifts from its metadata.
- **(a) Column identifiers were emitted unquoted.** A field named `user` (or `order`, `group`,
  `default`, …) produced `ERROR: syntax error at or near "user"` and killed the whole
  `CREATE TABLE`. Fix: `PhysicalTableStorageAdapter.quoteIdentifier` — used for column
  *references* in DDL **and** DML (and mirrored in `SchemaMigrationEngine` +
  `CompositeUniqueConstraintService`). Constraint/index *names* keep bare `sanitizeIdentifier`:
  they are matched against `pg_constraint.conname` as string literals. Column names are already
  lower-cased by `toSnakeCase`, so quoting changes nothing for non-reserved names.
- **(b) FK targets were assumed to live in the source's schema.** A tenant collection with a
  LOOKUP to a *system* collection resolved to `"<tenant>"."users"` → `relation does not exist`.
  Fix: `resolveTargetTable` probes the source schema then `public` via `to_regclass`, and a
  target found in neither is skipped with a warning instead of failing the collection.
- **(c) One orphaned row blocked the FK forever.** `ADD CONSTRAINT` validated existing rows, so a
  single dangling reference failed the statement on every boot — leaving the column with *no*
  integrity at all. Fix: add `NOT VALID` (enforced for all new writes immediately), then a
  separate `VALIDATE CONSTRAINT` that only warns, naming the table and the exact fix-up command.

**FIXED (fix/fk-existence-check-scoped-to-table) — FK existence check was global, so only the
first tenant with a given collection+field pair ever got its foreign key.** The idempotence guard
was `IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '<fk>')` — unqualified, so it
searched every schema. `conname` is unique per *table*, not per database, and generated FK names
derive from collection + field names, which repeat across tenants: every tenant with an `orders`
collection and a `customer` field generates `fk_orders_customer`. The first tenant to initialize
claimed the name; every tenant after it matched that row and silently skipped its own
`ADD CONSTRAINT`. Surfaced by the `VALIDATE CONSTRAINT` step added above, which then failed with
`constraint … does not exist` — a **25-constraint** gap across 5 tenants in the homelab at the
time of the fix. Fix: `AND conrelid = to_regclass('<qualified source table>')`. Missing FKs are
created automatically on the next migrate-Job run. The validate-failure warning also now only
blames orphaned rows when the cause is genuinely a `DataIntegrityViolationException`.

**FIXED (fix/fk-target-system-collection-table) — FK targets used the collection name as the table
name.** `ReferenceConfig` names the target *collection*; the adapter passed that straight through
as a table name. For tenant collections the two are always identical
(`CollectionLifecycleManager` builds `StorageConfig.physicalTable(collectionName)`), but a
**system** collection maps onto its Flyway table — `users` → `platform_user`, `page-layouts` →
`page_layout`, and ~40 more. So *any* LOOKUP at a system collection resolved to a table that has
never existed. Symptom: six couchpicks fields (`alerts.user`, `watchlists.user`,
`click-events.user`, `search-queries.user`, `editorial-lists.curator`, `title-matches.reviewer`)
logged `target table 'users' does not exist` on every migrate-Job run. Fix:
`SYSTEM_COLLECTION_TABLES`, built once from `SystemCollectionDefinitions.byName()`, resolves the
real table. **Candidate order matters and is tested** — tenant schema under the raw name is tried
*first*, so a tenant collection legitimately named `users` still wins in its own schema instead of
being redirected to `platform_user`.

**FIXED (fix/fail-fast-on-missing-hmac-secrets) — signing keys fell back to hardcoded defaults.**
`VisitTokenService` defaulted to `kelta-dev-visit-secret` and `CampaignProperties` to
`kelta-dev-campaign-tracking-secret-change-me`, each with a startup `WARN`. Both were unset in
production, so real links were signed with values published in this public repo — the visit one
seriously, since a visit token is exchangeable for a portal login. The warning fired on every pod
boot for months and was never actioned; **a `log.warn` is not a control**. Both now throw
`IllegalStateException` naming the env var. Local dev keeps working: `docker-compose.yml` sets
local-only literals (CI's compose inherits them) and `make gen-keys` writes random values into
`.env` for a bare `mvn spring-boot:run`. Precedent for the pattern already existed —
`KELTA_AUTH_BOT_CHALLENGE_HMAC_KEY` fails startup on purpose for the same reason. **Never give a
signing key a default**; the tuning knobs beside it in `CampaignProperties` still default freely,
and that contrast is the point.

**Test-harness note:** the runtime-core storage tests now build H2 with `DATABASE_TO_LOWER=TRUE`
(as `ExternalJdbcStorageAdapterTest` already did). H2 folds unquoted identifiers to *upper* case
while PostgreSQL folds to *lower*; without the flag, quoted-lowercase DDL wouldn't match the
column H2 creates unquoted — a test-only divergence that made the suite disagree with production.

**FIXED (PR #1174) — record-policy `collectionId` was UUID-keyed but checked by name.**
`CerbosPolicyGenerator.generateRecordPolicy` emitted `R.attr.collectionId == "<UUID>"` (from
`profile_object_permission.collection_id` + `collection.id`, both UUIDs), but
`CerbosRecordAuthorizationAdvice` sets `R.attr.collectionId` from the URL path **name**. With
`permissions-enabled=true` (default) and real Cerbos policies, per-collection record allow rules
never matched, so any profile lacking `VIEW_ALL_DATA` (e.g. the seeded **Standard User**) had
**every record filtered out** of reads and denied on writes — except explicitly record-shared
rows. The harness runs Cerbos in allow-all mode and e2e runs as admin, so neither caught it (same
blind spot as the field-permission bug, per `feedback_db-constraint-test-gap`). Fix: the record
policy CEL (and its custom-rule CEL) now key on the collection **name** via a `collectionIdToName`
map; the **collection** policy stays UUID-keyed because the gateway's `checkObjectPermission`
passes the UUID (`route.getId()`). See `architecture.md` → Cerbos `collectionId` keying.
**Pre-merge gate: verify on a live stack** (`make up`, log in as a non-admin profile, toggle
per-collection object permissions) — the allow-all harness Cerbos cannot exercise real deny.

## Tech Debt

**DB-design audit — incomplete legacy removals (2026-07-06).** A schema audit (159 migrations
cross-referenced against the live `192.168.0.5/emf_control_plane` DB: 117 public tables, 55 empty)
found dead references to tables dropped years earlier when the platform moved to profiles + Cerbos.
Staged cleanup:
- **PR1 (shipped #1186): permission sets fully removed** — the six V98-dropped permission-set tables
  were still registered as system collections and queried by delegated admin. Migration V160 dropped
  the `delegated_admin_scope.assignable_permission_set_ids` column.
- **PR2 (this change, #1187): package export/import + config-health.** `PackageService`/`PackageRepository`/
  `PackageImportService` were still exporting/importing `role`/`policy`/`route_policy`/`field_policy`
  (dropped V47) — package export threw on those item types; `OverpermissiveProfileRule` selected `FROM
  system_permission` (dropped V47) — the config-health scan failed. Removed the authz export/import
  path (methods, repo queries, natural-key remap machinery) and fixed the rule to read
  `profile_system_permission`.
- **PR3 (shipped #1188): orphan tables + dead endpoint.** Dropped `user_group_member` (V12 flat join,
  superseded by `group_membership` V45; the one stale row is discarded legacy data) and
  `flow_execution_dedup` (V71, unused) via `V161__drop_orphan_tables.sql`. Deleted
  `WorkflowMigrationController`/`WorkflowMigrationRepository` — they queried `workflow_rule`/
  `workflow_action` (dropped V72), so the endpoint was dead-broken, not "harmless" as previously noted.
- **PR4 (pending): Flyway history flatten** to a clean V1 baseline; rehearse the no-op on a
  `192.168.0.5` clone before rollout.

**Feature-flag audit outcomes (2026-07-02).** The audit reviewed every `@ConditionalOnProperty`
for removal ("inline the current value"). Findings:
- **Removed**: frontend `RENDER_TREE_V2` (was hardcoded `true`; legacy per-type renderer deleted — #1137).
- **RETAINED — not dead, do not inline**: `kelta.scheduler.enabled` + `kelta.bulk.processor.enabled`
  are set to `false` by `application-migrate.yml` so the K8s PreSync **migration pod** starts no
  background workers and exits after runners complete. Inlining them would hang the migrate job.
  `kelta.email.enabled=false` provides a no-op `EmailService` for envs without SMTP (dev/test).
  `kelta.flow.enabled` / `kelta.modules.runtime.enabled` likewise gate real subsystems.
- **RETAINED — presence-gated secrets/integrations** (removing the gate constructs a bean with a
  null secret → startup failure): `kelta.encryption.key`, `kelta.svix.auth-token`,
  `kelta.superset.url`, `kelta.sms.provider`, `kelta.push.provider`.
- **Security flags** `kelta.{worker,gateway,auth}.internal-auth.enabled` (default off): flipping ON
  + removing is a prod behavior + security change requiring the gateway client-credentials path
  verified first — a scoped follow-up, not a mechanical inline.

**Deliberately-retained code (audit judged deletion riskier than the cleanliness gain):**
- Email-template dual lookup (`findTemplateByKey` vs `findTemplateByName`, see below) — both axes
  are live on the transactional-email path (password reset/invite); consolidating risks that path
  for a cosmetic gain.

**Large files needing decomposition:**

| File | Lines | Proposed fix |
|------|------:|--------------|
| `SystemCollectionDefinitions.java` | 1,434 | Move to JSON/YAML config loaded at startup |
| `DynamicCollectionRouter.java` | 1,357 | Extract RouteResolutionHandler, InclusionHandler, FieldMapperHandler |
| `PhysicalTableStorageAdapter.java` | 1,091 | Extract SQL builder classes; separate concerns |

**Other:**

- **Related-list mass-edit ships via bulk-jobs (2026-07-04; formerly deferred).** `RelatedList` row selection + "Edit field…" submits ONE `POST /api/bulk-jobs` UPDATE job and polls it — no N-sequential-PATCH loop. Two boundaries to keep in mind: (1) the affordance is gated on `MANAGE_DATA` (`useSystemPermissions`) because bulk writes bypass per-record Cerbos advice — the in-controller `MANAGE_DATA` gate is the server boundary; (2) bulk UPDATE runs through `QueryEngine.update`, so validation/hooks fire but field-level write-FLS advice does not (same accepted trade-off as record merge). Deleting a `master_detail` child is a normal delete; deleting the parent cascades server-side (FK CASCADE).
- `PageBuilderPage.tsx` (`kelta-ui/app/src/pages/PageBuilderPage/`) is still large (~1.3k lines) but net-shrank in slices 2a/2b/2c: the per-type render switches, `AVAILABLE_COMPONENTS`/`ComponentPalette`, the `PropertyPanel` blocks, and (2c) the in-file native-HTML5-DnD `Canvas` + drag handlers were extracted to `widgets/*`, `palette/Palette.tsx`, `inspector/*`, and `canvas/*`. Remaining bulk is the page-list table, the page-config form modal, and the editor shell — candidates for extraction if it grows further; not currently blocking.
- **`@dnd-kit/{core,sortable,utilities}` (slice 2c)** is a new `kelta-ui/app` runtime dep, **scoped to the page canvas only** — `PageLayoutsPage`/`MenuBuilderPage`/`FlowDesignerPage` stay on native HTML5 DnD; do NOT mix the two libs in one tree. Watch items: ~30–40 kB gz bundle cost; pointer-DnD is flaky in jsdom (interaction tests drive dnd-kit via the **KeyboardSensor** and assert the resulting tree, not pixels — pointer fidelity is covered post-deploy by Playwright). The `vitest.setup.ts` `ResizeObserver` mock is now a real class because `DndContext` does `new ResizeObserver(...)`.
- **Page save-path silent-drop class (slice 2c — closed for `schemaVersion`).** `mergeConfig` only overlays a key when the `handleSavePage` CALL passes it; widening `mergeConfig`'s accepted keys alone silently drops them. 2c passes the full set `{ components, variables, dataSources, schemaVersion: 2 }`. The invariant — *every page-level sibling must be passed at the call site* — must hold for every future sibling (2d adds `variables`/`dataSources` values, 1h adds `access`). A test asserts the `updateMutation.mutate` payload carries `schemaVersion`.
- **`config.layout` is inert/deprecated legacy (slice 2c).** The create-form `layoutType` select (`page-layout-select`) still writes `config.layout.type` and it round-trips untouched, but the widget tree + per-child `span` now own layout — nothing in the canvas or runtime reads `config.layout`. Removal of the select is deferred (it is also the create-time form field; ripping it out touches the create flow + tests). A future slice may delete it once no page reads `config.layout`.
- Hardcoded `emf_control_plane` DB name in `SupersetDatabaseUserService.java` (line 78) — env-driven via `kelta.worker.superset.database-name` default; cleanup is cosmetic.
- Potential N+1 in `SearchIndexService.java` during reindex (per-collection queries) — harmless under normal load; matters at >1k collections.
- **Email templates have two parallel lookup axes** — `EmailRepository.findTemplateByKey` resolves by the stable `template_key` column (V133 seeded eight `user.*` defaults); `findTemplateByName` resolves by the human-friendly `name` column (V141 seeded `password_reset`, `user_invite`, `welcome`). Both implement the same tenant-override → `'system'`-sentinel fallback, so callers picking the "wrong" axis still work but may land on a different seed row than intended. Pick one canonical axis once the calling code settles, and drop the unused seed set.
- **Chat message-table growth (telehealth slice 2 → mitigated by slice 7).** `chat_message` lives in the shared public schema and accrues per-message rows across all tenants; indexes cover the hot paths (`(tenant_id, conversation_id, sent_at)`). The durable mitigation **shipped in slice 7 (V171)**: `RetentionPurgeSweep` deletes live `chat_message` rows `purgeLiveAfterDays` (default 90) after the encounter archive exists (transcript preserved in the immutable artifact; message attachments re-parented to the archive row so nothing referenced is lost). **The live-message purge is dry-run-gated exactly like the archive purge** (`kelta.telehealth.retention.purge-dry-run`, default `true`) — until an operator flips it to `false`, live rows are NOT deleted, so this remains a growth concern in any environment left in dry-run. Partition only if real volume demands it. Related accepted window: the gateway's chat-membership cache (30s, fail-closed) means a just-removed participant can receive **id-only** events for up to 30s — message bodies never ride the socket.

- **Retention purge is DESTRUCTIVE — verify backup posture before disabling dry-run (telehealth slice 7).** `RetentionPurgeSweep` permanently deletes PHI: expired-archive artifacts + linked recording S3 objects (stamping `purged_at` — the archive row is kept as a tombstone, never hard-deleted) and, separately, live `chat_message` rows after archival. It is **DRY-RUN by default** (`kelta.telehealth.retention.purge-dry-run:true`) — it only LOGS what it would purge and deletes nothing. Guardrails when armed: legal-hold rows are excluded in the claim SQL AND re-checked per row before any delete; the archive tombstone survives; `FOR UPDATE SKIP LOCKED` prevents double-purge. **Before setting `purge-dry-run=false` in any environment, confirm the operator's backup/restore posture** (there is no undo — deleted S3 objects and message rows are gone; WORM/object-lock is an operator infra option, documented not enforced). Recommended rollout: leave dry-run on for at least one full retention cycle, review the sweep's "would purge" logs, then arm per environment.
- **LiveKit SFU ops (telehealth slice 5) — TURN-TLS is the patient-network release gate.** The homelab-argo `livekit` app ships with TURN **disabled** (needs a TLS cert on 443/5349): patients on UDP-blocked networks (hospitals, corporate Wi-Fi) CANNOT connect until it's enabled and smoke-tested from a restricted network. Also accepted v1 trades: one shared SFU across tenants (room-scoped tokens isolate access; media isn't tenant-RLS'd by nature), single hostNetwork replica (`Recreate` strategy — brief video outage on redeploy), UDP 50000–50100 must be open on the router for WAN patients, dev key defaults in worker + compose warn at startup and must never reach prod (`KELTA_LIVEKIT_*`, `KELTA_TELEHEALTH_VISIT_SECRET`).

## Fragile Areas

### `DynamicCollectionRouter` shadows any `/**` mapping under `/api` (GET only)

`runtime-core/.../router/DynamicCollectionRouter` is `@RequestMapping("/api")` and declares
all-variable mappings up to four segments deep, including
`@GetMapping("/{parentName}/{parentId}/{childName}/{childId}")`.

Spring's `PathPattern` specificity comparator ranks **any pattern containing `**` last**, however
many literal segments it also has. A controller that maps a catch-all under `/api` therefore loses
every GET the router can match, and the router handles the request as a record read — 404, with the
only trace anywhere being an INFO line `Collection '<segment>' not in registry, attempting on-demand
load`. No WARN, no ERROR, nothing from the controller.

The router's POST mappings stop at **three** segments, so POST on the identical URL dispatches
correctly. That GET/POST asymmetry reads as a method-specific bug in the new controller and is what
makes this expensive: it sent a night of debugging at the module route registry, the manifest, the
signed JAR, the gateway and `@RequestBody`, all of which were fine.

Observed 2026-08-31 on module HTTP routes (`/api/modules/<id>/x/plans`). Fixed by spelling out the
depth-1..3 patterns on `ModuleHttpController` so its literal `modules` and `x` segments count toward
specificity, keeping `/**` only as a backstop for deeper paths where nothing competes.

**Rules that follow.** Never map `/**` for a worker endpoint under `/api`. A MockMvc test that
registers the controller **alone** cannot see this class of bug — the isolation test passed the
entire time — so any test asserting a mapping under `/api` must register a stand-in for the router's
nested GET mapping (`ModuleHttpControllerMvcTest` does).


- **`kelta-ui/app` types are only checked by the `Type check kelta-ui` CI step — and typechecking needs the `kelta-web` packages built first.** The prod image builds with bare `npx vite build` (`kelta-ui/Dockerfile`), which skips `tsc` entirely. Until that step was added nothing else validated those types either: CI's `test-frontend` job ran `npm run typecheck` with `working-directory: kelta-web`, so it checked the SDK workspace and never `kelta-ui/app`. 22 type errors accumulated silently on main. If that CI step is ever dropped, the same drift resumes invisibly — `vite build` will keep going green. Two gotchas when checking locally:
  - `kelta-ui/app` resolves `@kelta/{sdk,components,…}` through `file:` deps whose **types come from the built `dist/*.d.ts`**, not `src/`. Against a stale or missing `dist/`, `tsc` reports dozens of phantom `has no exported member` / `does not exist on type 'AdminClient'` errors that are pure build staleness. **Run `npm run build` in `kelta-web` before believing any of them** (`/verify` and CI both do this).
  - `@kelta/components` declares `@livekit/components-react` as an **optional** peer dep. Rebuilding it against a `node_modules` that lacks livekit emits a `__vite-optional-peer-dep:…:false` stub into `dist/video.js`, and the *app* build then fails with `"useTracks" is not exported by …`. That is a stale install, not a code bug — `npm install` in `kelta-web` and rebuild.
- **Hand-maintained narrow copies of shared types drift silently.** Those 22 errors were mostly this: an inline `{ path?: string; label?: string }` duck type in three nav-tree walkers that went stale when submenus added `children` (#1228); a local `UIMenu` in `MenuBuilderPage.tsx` that predates the apps/nav v2 fields (`icon`/`isDefault`/`active`, V164); and a local `CollectionSchema` in `ResourceDetailPage.tsx` missing `trackHistory`. Prefer the canonical type (`@/types/config`, `@kelta/sdk`, `hooks/useCollectionSchema`) over redeclaring a subset next to the consumer. The `ResourceDetailPage` one was a **live bug, not just a type gap** — its local fetcher also dropped `trackHistory` from the returned object, so the admin record History tab was permanently dead; adding only the type field would have hidden it. `ResourceDetailPage.tsx` still forks `CollectionSchema`/`fetchCollectionSchema` from `hooks/useCollectionSchema.ts` (which is a strict superset); collapsing the two is an open follow-up.
- **`useRecordMutation`'s `onSuccess` fires for EVERY mutation** (create/update/patch/remove/bulkDelete — `kelta-ui/app/src/hooks/useRecordMutation.ts`). Never put operation-specific side effects (navigate after delete, "Record deleted" announcements, clearing selection) in that shared callback: inline-edit PATCHes ride the same hook, so a field commit will fire them too. Real bug (2026-07-18): `ObjectDetailPage` navigated back to the list on every inline field commit. Put delete side effects in the delete confirm handler via `mutateAsync().then(...)` instead.
- **Destructive schema-migration execute** (`MigrationExecutionService` + `SchemaMigrationEngine.migrateSchemaDestructive`): the only path that physically `DROP COLUMN`s live tenant data. It applies DDL, then re-registers the local `CollectionRegistry` with the target def and broadcasts `kelta.config.collection.changed`. On the originating pod this is exact (direct `register(target)`, no re-migrate). **Other pods** react to the broadcast via `refreshCollection`, which reloads the target from the `field` table (correct) but *also* calls `updateCollectionSchema` → the non-destructive `migrateSchema`, which tries to **deprecate** the just-removed column via `COMMENT ON COLUMN`. Because the column is already dropped in the shared DB, that COMMENT fails — but `refreshCollection` swallows the error after it has already re-registered the target def, so all pods still converge. Net effect: correct state everywhere, with a harmless one-off `StorageException` logged on non-originating pods per removed field. Tightening this (make deprecate tolerant of a missing column, or route the drop through the refresh path) is a follow-up. Gated on `CUSTOMIZE_APPLICATION`; **security-sensitive → not auto-merged**.
- **FK constraint generation** (`PhysicalTableStorageAdapter.java` 177-185): String concatenation for FK names; collision risk with very long tenant slugs + collection names.
- **Tenant schema isolation** (`PhysicalTableStorageAdapter.java` 105-107): Assumes schema exists; silent failure on permission error. Surfaces as confused-state on tenant provisioning failure.

## Dependency Risks

- **`make up` needs ~24 GB allocated to Docker.** Compose builds `kelta-auth`, `kelta-worker` and
  `kelta-gateway` concurrently from their native `Dockerfile`s, and `native-image` sizes its heap to
  ~80% of whatever the cgroup reports — so on a 12 GB Docker Desktop three builders each claim ~9 GB
  and one dies with `ResourceExhausted: ... cannot allocate memory`. It reads like a build break but
  is purely a resource ceiling. Fix: `make up-jvm` (overlay `docker-compose.jvm.yml` swaps the three
  services to their `Dockerfile.jvm` variants; ports, container names, healthchecks and env are
  unchanged), or raise the Docker Desktop memory allocation. Use native locally only when debugging
  native-specific behavior — reachability metadata, `reflect-config.json` gaps, build-time init
  (see the entry below).
- **Gateway is a GraalVM native image — every DTO deserialized from `/internal/bootstrap` MUST be
  in `reflect-config.json`.** Root cause of the 2026-07-02 total-API outage: V148 added
  `ipAllowlists: Map<String,TenantIpConfig>` to `kelta-gateway/.../config/BootstrapConfig` + the
  `TenantIpConfig` DTO, but nobody added `TenantIpConfig` to
  `kelta-gateway/src/main/resources/META-INF/native-image/io.kelta/kelta-gateway/reflect-config.json`.
  In the JVM this is invisible (reflection is free); in the native image Jackson can't construct the
  class → the whole bootstrap fails to deserialize → the gateway loads ~2 routes instead of ~148 →
  every `/{tenant}/api/*` 404s (`No static resource`) → app + login down. It stayed latent until the
  first gateway rebuild after V148. **Rule: any class reachable from `BootstrapConfig` (or any
  reflectively-deserialized gateway type) gets a `reflect-config.json` entry in the same PR.** The
  route loader is now hardened (`RouteConfigService.refreshRoutes` registers the static routes
  *before/independent of* the bootstrap fetch), so a future missed DTO degrades (loses only dynamic
  collection routes) instead of taking the entire API offline — but the reflect-config entry is still
  required for the bootstrap to fully load.
- **The worker is ALSO a GraalVM native image — every `PlatformEvent` payload it serializes to NATS
  MUST be in `reflect-config.json`, exactly like the gateway bootstrap rule above.** Found 2026-07-11
  driving a live telehealth video call: the worker published `kelta.video.session.*` with `"payload":{}`
  (and would do the same for `kelta.chat.*` and `credential/domain/feature.changed`) because
  `VideoSessionPayload`, `ChatMessagePayload`, `ChatConversationPayload`, `CredentialChangedPayload`,
  `DomainChangedPayload`, `FeatureChangedPayload` were never added to
  `kelta-worker/.../META-INF/native-image/io.kelta/kelta-worker/reflect-config.json`. In the JVM/tests
  this is invisible (reflection is free); the native image can't introspect the getters → Jackson emits
  an empty bean, so consumers (e.g. NATS_TRIGGERED post-visit flows) get no data. `EventPayloadReflectConfigTest`
  now fails CI if any `io.kelta.runtime.event.*Payload` is unregistered. **Rule: a new event payload
  gets a reflect-config entry in BOTH `kelta-worker` and `kelta-gateway` in the same PR.**
- **Cerbos decision-cache keys MUST carry the request-origin geo country wherever geo can
  influence a decision.** Both principals (gateway + worker) expose `P.attr.geoCountry`, so all
  four decision caches (gateway system/object in `CerbosAuthorizationService`, worker
  field-access + collection-wide record-access) append `:geo:<country>` to their keys. Dropping
  the suffix from any of them reintroduces cross-location stale allows (a US-origin ALLOW
  answering a CN-origin request) the moment a tenant writes a geo-aware policy rule. Any NEW
  Cerbos decision cache added later must include it too.
- **GeoLite2 lookup models are native-reflective — same rule class as the bootstrap DTOs.** The
  `com.maxmind.db` Reader instantiates `io.kelta.gateway.geo.model.*` (`GeoCityData`, `GeoCountry`,
  `GeoSubdivision`, `GeoCity`, `GeoLocation`) via the `@MaxMindDbConstructor` annotation reflectively;
  all five have `reflect-config.json` entries. Adding a field/class to the geo lookup model without a
  reflect entry works on the JVM and every CI test but breaks lookups on the deployed native gateway
  only. Also geo-specific: the MaxMind license key rides the download URL query string —
  `GeoIpDatabaseManager.redact(...)` must wrap ANY log/exception message that could contain the URL
  (tested by `GeoIpDatabaseManagerTest.redactsLicenseKey`); never log the raw URL or
  `HttpRequest`/exception toString without it.
- **Script-engine JS builtins can require host reflection on the native worker.** Found 2026-07-12:
  any flow `INVOKE_SCRIPT` whose script called `new Date()` died on the native image with
  `MissingReflectionRegistrationError: java.util.Locale.getDefault(Locale$Category)` — GraalJS
  resolves the default locale reflectively. Works on the JVM and in every CI test; only the
  deployed native worker fails, and the flow execution just shows FAILED with the error in
  `flow_executions.error_message`. `java.util.Locale`/`Locale$Category`/`TimeZone.getDefault`
  are now registered in `kelta-worker/.../reflect-config.json`. If another JS builtin trips this
  (e.g. `Intl.*`, `toLocaleString` variants), the `MissingReflectionRegistrationError` message
  prints the exact JSON entry to add — copy it into the worker reflect-config in the fix PR.
  Until deployed, scripts can avoid `Date` entirely: pass `${$.record.data.updatedAt}` in
  `inputPayload` and do string/int calendar math (the F3/F6 billing flows show the pattern).
- **A native image freezes `@Conditional*` evaluation at BUILD time — config supplied only by a
  runtime env var is too late.** Found 2026-08-10: worker, gateway and auth exported **zero traces**
  in production, with no error anywhere. Spring Boot creates the OTLP span exporter only when
  `management.opentelemetry.tracing.export.otlp.endpoint` is set —
  `OtlpTracingConfigurations.ConnectionDetails` is `@ConditionalOnProperty` on it (no
  `matchIfMissing`) and `Exporters` is `@ConditionalOnBean` on the bean it creates. The endpoint
  was only ever set as a Kubernetes env var, so at AOT time the condition was false, the exporter
  bean was never registered, and the frozen bean factory could not gain it later no matter what the
  environment said. The JVM re-evaluates at boot, which is why `Dockerfile.jvm` and every CI test
  looked fine. The failure is silent and *looks like a collector problem*: spans are still created
  (log MDC carries real `traceId`/`spanId`) — only the export is missing. Fixed by declaring the
  endpoint in each service's `application.yml` as
  `${MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT:http://localhost:4318/v1/traces}`;
  guarded by `TracingExportPropertiesTest` in worker/gateway/auth. **Rule: any property that gates a
  bean via `@ConditionalOnProperty` must be declared in `application.yml` with an
  `${ENV_VAR:default}` placeholder — the env var may choose the value, never whether the property
  exists.** To check what AOT actually kept, read
  `<service>/target/spring-aot/main/sources/**/…__BeanFactoryRegistrations.java` after
  `mvn spring-boot:process-aot` — a missing `registerBeanDefinition("…")` line is the whole bug.
- **Every new `kelta.*` NATS subject namespace needs its own JetStream stream in `JetStreamInitializer`.**
  `NatsEventPublisher` always JetStream-publishes and awaits an ack; a subject matched by no stream never
  acks → `CancellationException: response not registered in time` and the event is dropped (publish is
  best-effort — the error is logged, never thrown). `ensureStream` is add-if-absent — it does **not**
  `updateStream`, so you cannot bolt a subject onto an existing stream; it needs its own `ensureStream(...)`
  call. Telehealth slices 2 (chat) + 5 (video) added subjects without streams → chat realtime was fully
  broken on prod (gateway push consumer looped `[SUB-90007] No matching streams`) and video lifecycle
  events failed to publish, until `KELTA_CHAT` + `KELTA_VIDEO_SESSION` were added (2026-07-11).
- **Changing any durable-consumer setting (maxDeliver, ackWait, deliverPolicy, …) is a rolling-upgrade
  hazard.** The durable consumers on the NATS server keep the old config; jnats then rejects every bind
  with `[SUB-90016] Existing consumer cannot be modified`, and the subscribe retry loop can never succeed.
  The 2026-07-12 worker rollout (maxDeliver=5 introduced by #1215-era code) took ALL six worker
  subscriptions — flows, NATS triggers, search index, Svix, Superset — down for ~30 min until the
  consumers were hand-edited. `NatsSubscriptionManager` now self-heals: on a drift rejection it
  `addOrUpdateConsumer`s the durable in place (delivery cursor preserved — never delete/recreate, that
  drops the backlog) and re-subscribes. If flows stop after a deploy anyway, grep worker logs for
  `SUB-90016` first.
- Multiple BOM version overrides in worker POM increase transitive conflict risk (`kelta-worker/pom.xml`). Audit on every Spring Boot bump.
- **pgvector required for VECTOR fields / semantic search.** `PhysicalTableStorageAdapter.initializeCollection` runs `CREATE EXTENSION IF NOT EXISTS vector` lazily — only when a collection actually has a VECTOR field — and throws an actionable `StorageException` if it can't. Local dev + CI use the `pgvector/pgvector:pg15` image (docker-compose + `KeltaStack` Testcontainers). **The standalone prod Postgres at `192.168.0.5` is plain `postgres:15` and must have the pgvector extension installed** (the `.so` available + the worker's DB role allowed to `CREATE EXTENSION`, or an admin pre-creates it) before any tenant defines a VECTOR field; otherwise that collection's table creation fails with the guidance above. Collections without VECTOR fields are unaffected.
- **Stale `spring-kafka` dependency retired** (Phase 0): `runtime-core/pom.xml` previously declared `spring-kafka`, `spring-kafka-test`, and `testcontainers-kafka` despite the platform using NATS JetStream exclusively. Removed; the misnamed `KafkaRecordEventPublisher` was renamed to `NatsRecordEventPublisher`. The stale event-bus comments and docs that mislabeled NATS as the previous broker have since been corrected to NATS across the codebase.

## Postgres max_connections sizing

**Current state (homelab):**
- Single Postgres at `192.168.0.5:5432`, default `max_connections = 100`.
- Per-pod HikariCP defaults after [#917](https://github.com/cklinker/emf/pull/917): worker max=30, auth max=15, ai max=15.
- Cerbos has its own pool against the same Postgres (`cerbos:cerbos` DB).

**Connection budget at scale:**

| Service | Pool max | Typical replicas | Connections |
|---------|---------:|-----------------:|------------:|
| kelta-worker | 30 | 2-3 | 60-90 |
| kelta-auth   | 15 | 1-2 | 15-30 |
| kelta-ai     | 15 | 1-2 | 15-30 |
| cerbos       | ~10 | 1 | ~10 |
| **Subtotal** | | | **100-160** |
| Migrations PreSync (transient) | 10 | 1 | 10 |
| Headroom for psql / admin | | | 10 |
| **Required** | | | **120-180** |

**Recommendations:**
1. **Immediate**: raise `max_connections` to `200` on `192.168.0.5` (`ALTER SYSTEM SET max_connections = 200; SELECT pg_reload_conf();`). Each connection costs ~10 MB shared memory; 200 × 10 MB = 2 GB. Verify Postgres host has the headroom.
2. **After PgBouncer ships ([homelab-argo#94](https://github.com/cklinker/homelab-argo/pull/94))**: services connect to PgBouncer (session pool). Real Postgres connections drop to PgBouncer's `default_pool_size = 60` × 1 connection per logical DB = 60. `max_connections = 100` then becomes enough again.
3. **Long-term**: move Postgres to a dedicated host or managed service with `max_connections = 500+` once tenant count grows past 1k.

**Followup**: alert on `pg_stat_database.numbackends / max_connections > 0.8` via the Prometheus rules in [homelab-argo#93](https://github.com/cklinker/homelab-argo/pull/93). Requires `postgres_exporter` to be deployed alongside Postgres — not yet in homelab-argo.

## Connection Pooler Compatibility

**kelta-worker RLS tenant variable is now transaction-scoped (PgBouncer-safe).** ✅ Resolved (Phase 0)

`TenantAwareDataSourceConfig.TenantAwareDataSource` scopes `app.current_tenant_id` per database operation:
- **Tenant-scoped connection** (non-empty tenant in `TenantContext`): if the borrowed connection is in autocommit mode, autocommit is turned off (begins a transaction), `SET LOCAL app.current_tenant_id = '<id>'` is issued, and a thin commit-on-close proxy commits + restores autocommit when the connection is released. If a Spring-managed transaction already owns the connection, only `SET LOCAL` is issued and the proxy defers commit/rollback to the owner (it watches the owner's `commit()`/`rollback()` to avoid a double-commit). Because the variable is set per transaction on the same backend that serves the query, it survives PgBouncer `pool_mode = transaction`. Connection-hold time is unchanged — the connection is released right after the operation, exactly like the previous autocommit model.
- **Admin/bypass connection** (no tenant — Flyway, internal, cross-tenant work): keeps the legacy session `SET app.current_tenant_id = ''`. Empty already maps to `admin_bypass`, so transaction scoping is irrelevant to isolation and these paths are unchanged.

Regression guard: `TenantAwareDataSourceTest` asserts tenant connections use transaction-local `SET LOCAL` (not session `SET`) and commit/restore correctly; the harness `TenantIsolationScenarioTest` exercises real-stack isolation over Postgres + RLS.

**Remaining caveat — kelta-ai:** `kelta-ai/src/main/resources/application.yml` still uses `hikari.connection-init-sql: SET app.current_tenant_id = ''` (a session-level set to the bypass value). kelta-ai runs in admin/bypass mode and filters tenants explicitly in SQL rather than relying on RLS tenant isolation, so it is not a leak vector — but if kelta-ai ever begins relying on RLS for tenant-scoped reads, it must adopt the same transaction-scoped `SET LOCAL` mechanism before being placed behind a transaction-pool. Tracked as a follow-up.

- **Compose healthchecks for kelta-worker and kelta-ai can never fail.** Both set
  `management.endpoint.health.show-details: always`, so `/actuator/health` returns every
  component in the body — and their healthcheck is
  `wget -qO- .../actuator/health | grep -q UP`, which matches a healthy *sub-component* even
  when the overall status is DOWN. In practice `docker compose up --wait` will happily proceed
  with a DOWN worker. `kelta-auth` is unaffected (`show-details: when-authorized`, so an
  unauthenticated probe sees only the top-level status). The gateway's check was fixed to use
  the exit code against `/actuator/health/readiness` (Spring maps DOWN to 503, and wget exits
  non-zero). **The same fix was deliberately NOT applied to worker/ai here**: their overall
  health aggregates every indicator, so making the check strict could newly block startup on an
  optional/degraded component, and that needs a verified full-stack run rather than a
  speculative one-liner. Fix it as its own change, with a compose run to prove it.

## Test Coverage Gaps

- **FIXED (2026-09-09) — `kelta-ui/app`'s 223 test files / ~2,700 tests never ran, and could not
  have.** Two independent failures stacked. (1) Neither workflow invoked `npm run test` for
  `kelta-ui/app`: `ci.yml`'s `test-frontend` ran the full lint/typecheck/format/coverage set in
  **`kelta-web`** (57 files) and then only `npm run typecheck` for `kelta-ui/app`, while the
  deploy workflow's copy of the job did not touch `kelta-ui/app` **at all** — so the admin/builder
  and end-user UI, the page builder, record shell, kanban/calendar/gallery, realtime client and
  saved views were validated nowhere on `main`. (2) Even had it been invoked, `NODE_VERSION` was
  pinned to **18**, where jsdom's `html-encoding-sniffer` `require()`s an ES module: all 223 files
  die with `ERR_REQUIRE_ESM` during environment setup, reporting `Test Files no tests / Errors 223`
  — zero tests executed. Verified by running the suite three ways: Node 18 → 223 errors, 0 tests;
  Node 20.20.2 and Node 22 → **223 files passed, 2,684 passed / 34 skipped**. The suite was healthy
  the whole time; nothing could run it.
  Fix: `NODE_VERSION` 18 → **20** (matching `kelta-ui/Dockerfile`'s `node:20-alpine`, which CI was
  *not* validating against), a `Test kelta-ui` step in both workflows, and `engines.node >= 20.19.0`
  on `kelta-ui/app` so the floor is declared rather than implied. `test-frontend` is already in
  `quality-gate` and `build-and-push`, so the new steps block on failure without extra wiring.
  **Note this invalidated a safety net other entries lean on**: the page-builder action-runtime
  note below cites Vitest coverage of `runtime/executeAction.ts` as the guard — those tests live in
  `kelta-ui/app` and had never executed.
  **Lesson, the second time this exact shape has appeared** (see the runtime-modules entry above):
  a suite existing, compiling, and typechecking is not coverage. Check that some job actually runs
  it, and that the runtime it runs under can start it.
  **One test had to be fixed before the suite could be a gate.**
  `ApprovalProcessesPage.test.tsx` failed 2 of 3 consecutive local runs — never Node-specific, just
  a race. Its wait was decorative:
  `waitFor(() => expect(screen.queryByLabelText(/loading/i)).not.toBeInTheDocument())` can never
  match, because `LoadingSpinner` renders its `label` as visible **text**, not an `aria-label`. The
  query returned null on the first tick, the assertion passed, and all four tests ran **while the
  page was still loading**, racing the mocked fetch — winning on a fast machine, losing under
  contention. Replaced with `await screen.findByTestId('add-approval-process-button')` (a wait that
  actually waits) plus `findBy*` for every element that only mounts after a click. 6/6 green after.
  The pattern was confined to this one file. **This is the "assertion that doesn't assert" family
  again** — same shape as the credential-audit tests that verified a value reached a mocked
  `JdbcTemplate` while Postgres rejected every row. A blocking gate has to be *reliably* green, not
  green once: enabling a flaky suite trades "never runs" for "main goes red at random", which is a
  deploy outage, not a test nuisance.

- **FIXED — the runtime modules' tests never ran in CI.** Both `ci.yml` and
  `build-and-publish-containers.yml` built `kelta-platform/runtime/*` with **`-DskipTests`** and
  then ran `mvn verify` against `kelta-<service>` only. So ~1,900 tests across the seven runtime
  modules — 89 test classes in `runtime-core` alone, covering the query engine, the storage
  adapters and their DDL/SQL generation, the flow engine, validation, `TenantContext` and the
  field-type model — were compiled on every run and **never executed**. This is not a
  hypothetical: #1281 flipped `FieldType.hasPhysicalColumn()` for FORMULA on 2026-07-29 and
  `FieldTypeTest.formulaHasNoPhysicalColumn` failed from that moment, while every `main` run
  reported green for eleven days. It surfaced only because someone ran `runtime-core` locally.
  A new `test-runtime` job now runs `mvn test` over all seven modules, gated on the `runtime`
  path filter, and is wired into **both** gates — `quality-gate` (PR-blocking) and
  `build-and-push`'s `result != 'failure'` (deploy-blocking) — so it blocks rather than informs.
  Lesson for any new module: being on the `-am` build list is **not** coverage; check it is in a
  job that actually runs its tests.
- **FIXED — Mockito audit tests passed against a table that rejected every row.** `CredentialResolverImpl` wrote the verb `CREDENTIAL_RESOLVE` on every credential access, but `setup_audit_trail.chk_audit_action` permitted only `CREATED/UPDATED/DELETED/ACTIVATED/DEACTIVATED`, so Postgres rejected each insert. `SetupAuditService` swallows write failures by design ("audit failures never disrupt normal operations"), so nothing failed — **there was simply no record of secret access, on any tenant, for any integration**, and the only symptom was one log line per resolve. `CredentialResolverImplTest` asserted `eq("CREDENTIAL_RESOLVE")` was *passed to a mocked* `JdbcTemplate`, and `SetupAuditServiceTest` asserted the SQL string — neither can see a constraint. Fixed by V188 (widen the constraint, don't drop it) — **confirmed writing on prod 2026-08-31**, where `CREDENTIAL_RESOLVE` rows now record `purpose` (`STRIPE_API:checkout`, `EMAIL_SEND`) and `cacheHit`; note the table now grows at the rate of credential *use*, not credential *change* — plus `SetupAuditActionScenarioTest`, a real-Postgres harness test that writes **every verb the codebase uses** and reads each row back, and asserts an unknown verb is still rejected. Found by reading worker logs during an unrelated live verification, not by any test. **Lesson: an assertion that a value was passed to a mock is not an assertion that the database accepts it** — any write whose shape a DB constraint governs needs a real-DB harness scenario. The audit-write failure path now logs at ERROR, not WARN.
- **FIXED — a rejected module reported success.** When a JAR failed signature, checksum or classloading, `RuntimeModuleManager.loadFromJar` caught the exception and called `loadWithStubs`, whose handlers returned `ActionResult.success({"status":"EXECUTED","mode":"stub"})` while `/api/modules` still reported `ACTIVE`. A cryptographically rejected module was therefore indistinguishable from a working one at every level an admin could see, and any flow calling its actions **passed** — for a module doing something like taking payment, the work was reported done and never happened. Compounding it, `ModulesPage`'s install form only ever called `/api/modules/install` (manifest JSON), never `/install-jar`, so **every module installed through the UI was stub-mode by construction**. Fixed 2026-08-31: load failure now quarantines (handlers throw `ModuleUnavailableException`, status `QUARANTINED`, reason persisted in `tenant_module.last_error`), stub mode survives only as the explicit `kelta.modules.stub-mode` dev opt-in, and the UI can install a signed JAR. **Lesson: a fallback that reports success is worse than no fallback** — it converts a loud failure into a silent one, and the more security-critical the check that failed, the worse the trade.
- **No worker Spring-context test — bean-wiring bugs reach production silently.** Nothing in the worker suite starts an application context, so a bean that cannot be constructed compiles, passes every unit test, and fails only at pod startup. This has now bitten **three** classes, all the same shape: a `@Component` with a **package-private test constructor alongside the production one** is multi-constructor, so Spring looks for a no-arg constructor and refuses to start. `StripeApiClient` (caught by a harness run that failed to boot the container); `FcmPushProvider` **and** `ApnsPushProvider` (caught 2026-08-03 by the new `PushProviderWiringTest` — both meant `kelta.push.provider=fcm|apns` was **unstartable**, undetected since those providers shipped). **Adding a second constructor to a `@Component` requires `@Autowired` on the production one.** Likewise, adding a second bean of an already-injected type breaks single-typed injection points (`WebPushProvider` vs `PushProvider` — fixed by `@Primary` on the mutually exclusive `kelta.push.provider` transports). `PushProviderWiringTest` is the pattern to copy: an `ApplicationContextRunner` over just the beans involved, no infrastructure. A whole-worker context test is still owed.
- **Rate-limit tests are mocked — no test proves two replicas actually share a counter.** The
  gateway and kelta-auth suites assert the Redis *key shape* and the TTL contract, which is
  what the multi-replica fix turns on, but nothing executes the Lua against a real Redis. The
  window guard is likewise a text assertion on the script source: it catches an added
  unconditional `EXPIRE`, not a semantically-equivalent rewrite using `SET … EX`. A
  Testcontainers-Redis scenario in `kelta-test-harness` would close both.
- Schema migration edge cases (`SchemaMigrationEngine` — ~880 lines) — the destructive `migrateSchemaDestructive` (ADD/DROP/ALTER) path now has engine unit tests + a real-DB `SchemaMigrationScenarioTest`; the older non-destructive `migrateSchema` (deprecate) branch is still lightly covered.
- SQL filter operator mappings in `PhysicalTableStorageAdapter` — no tests.
- Federated user provisioning (`FederatedUserMapper`) — happy path only; group→profile mapping permutations not covered.
- Password reset workflow — change/request/reset all unit-tested; full end-to-end (DB → email → reset link → new password) not.
- `CreateValidationRuleTool` (kelta-mcp) has no `CreateValidationRuleToolTest`, unlike its sibling admin tools — every MCP tool should assert its on-the-wire JSON:API body with WireMock JSON-path matchers.
- **Page-builder action runtime (slice 2e)** — `runtime/executeAction.ts` correctness (esp. the `runFlow` execute→poll loop with `data.id` read + chain-stop-on-reject, and the `assertSafeUrl` `javascript:`/`data:` scheme allow-list) is covered only by Vitest with a **mocked `apiClient`/router/toast** + fake timers. Mocks can hide endpoint-shape drift — exactly the class of bug behind the existing `executionId` vs `data.id` read in the admin flow pages. The real-path guard is the **2e-owned post-deploy Playwright spec** `e2e-tests/tests/page-builder-v2.spec.ts`, which asserts a **persisted record** from a button `onClick` `createRecord` (a real mutation, not just render) — mirroring the "DB-constraint test gap" lesson. It is `test.describe.skip`-gated (the v2 builder admin route is absent until deployed) and must be run against the deployed env before the feature is declared done.

---

## Resolved

### Security (all addressed)

- **Gateway route prefix shadowing across collections (2026-07-12)** — `RouteRegistry.matchesPath` matched `/**`/`/*` patterns with a raw `startsWith`, so `/api/inventory/**` (any tenant's `inventory` collection — routes register fleet-wide) shadowed `/api/inventory-items` and `/api/inventory-movements` for **every** tenant, iteration-order dependent. `RouteAuthorizationFilter` then authorized against the wrong route's collection: spurious 403s at best, and a grant on the short-named collection would have authorized its hyphenated siblings (cross-collection escalation) at worst. Fixed with a segment-boundary check (prefix must be followed by `/` or end the path) + regression tests in `RouteRegistryTest`. Spring Cloud Gateway's own forwarding predicates were never affected (PathPattern is segment-aware) — only this hand-rolled matcher.
- **SupersetDatabaseUserService SQL injection** — already hardened. `validateSlug`/`validateTenantId`/`validateDatabaseName` + `quoteIdent`/`quoteLiteral` defense-in-depth. See class javadoc at lines 24-42 of `SupersetDatabaseUserService.java`.
- **Gateway `permitAll()` actuator exposure** — `SecurityConfig.securityWebFilterChain` now requires JWT on `/actuator/**` except `/actuator/health` and `/actuator/info` (Kubernetes probes). Custom `GlobalFilter`s still handle API auth.
- **Missing auth on `/internal/**`** — `InternalEndpointSecurityConfig` (flag-gated by `kelta.worker.internal-auth.enabled`) enforces OAuth2 JWT with `scope=internal`. Gateway-side at `InternalServiceAuthConfig` attaches a client-credentials bearer token to outbound internal calls.
- **FlowConfig schema name sanitization** — `SchemaLifecycleModule` validates tenant slug against `^[a-z][a-z0-9-]{0,62}$` and escapes any internal double-quote before CREATE SCHEMA.

### Bugs (all addressed)

- **Gateway 404s a live tenant on every slug-cache refresh (2026-08-09, #1334)** — `GatewayCacheManager.refreshTenantSlugsFromWorker` did `invalidateAll()` then `putAll()`; a request landing in that sub-millisecond window missed and returned `TENANT_NOT_FOUND`. Compounding it, the lazy rescue lookup called `.block()` and its only caller is the reactive `TenantSlugExtractionFilter`, so on an `epoll` thread it always threw — the protection had never worked, and a brand-new tenant 404'd until the next refresh tick. Now the refresh writes the new entries before dropping departed ones (`replaceTenantSlugs`, never empty) and both slug and custom-domain resolution have non-blocking `…Reactive` variants that the filters use; the blocking variants keep a `Schedulers.isInNonBlockingThread()` guard. Custom-domain lookups negative-cache **only** a definitive worker 404, never a timeout/5xx. Surfaced as a `CampaignCreateScenarioTest` flake in CI.
- **`/api/operations` 500 on the native worker (2026-07-12)** — `AtomicOperationExecutor` maps request maps into the `io.kelta.jsonapi.AtomicOperation`/`AtomicResult` records reflectively, but none of the jsonapi records were in the worker `reflect-config.json`, so every atomic-operations call failed with `MissingReflectionRegistrationError` on the native image only (JVM/CI green — same invisible-on-CI class as the NATS payload gaps). All five records (`AtomicOperation` + `ResourceRef`/`ResourceData`, `AtomicResult` + `ResourceObject`) are now registered.
- **Federated users stuck PENDING_ACTIVATION** — `FederatedUserMapper.lookupProfileId` (line 199) calls `WorkerClient.findProfileByName` against `/internal/profile/by-name`.
- **Password reset sends no email** — `PasswordController.sendPasswordResetEmail` (line 212) calls `WorkerClient.sendTemplateEmail` with the `user.password_reset` template.
- **Unhandled EmptyResultDataAccessException** — `PasswordController.changePassword` (line 82) catches `EmptyResultDataAccessException` and returns the generic 400 to prevent username enumeration.
- **NPE on null `reset_token_expires_at`** — `PasswordController.resetPassword` (line 185) explicitly null-checks `expiresTs` before the `Timestamp.toInstant()` cast.

## Frontend suite starves under concurrent image builds (2026-08-06)

`Test Frontend` in Build-and-Deploy shares the runner with three concurrent GraalVM
native image builds. Under that contention the suite reported `environment 594s`
cumulative and a test **body** exceeded vitest's 5s default `testTimeout` —
`Chat.test.tsx > MessageComposer` died on a `userEvent.type` of ~15 chars (~15 React
renders). It failed 4 of 5 consecutive main runs and read like a deterministic break.

**Operational severity is the real point:** `build-and-push` is gated on
`test-frontend.result != 'failure'`, so a frontend flake silently stops every image
build, the deploy job then correctly refuses to bump tags, and **nothing ships** —
including urgent fixes. A red frontend suite on main is a deploy outage, not a test
nuisance.

Mitigations applied: `testTimeout`/`hookTimeout` raised to 20s (same rationale as the
existing `asyncUtilTimeout: 5000` in `vitest.setup.ts`), and the composer tests use
`fireEvent.change` + a committed-value assertion instead of per-keystroke typing.
Still open: the underlying starvation (consider giving the frontend job its own runner
or serialising it against the native builds).

## Collections become undeletable once used; e2e leaked litter into a product tenant (2026-08-06)

`PhysicalTableStorageAdapter.delete` issues a raw `DELETE` on the `collection`
row and maps Postgres FK violation 23503 to 409 `REFERENCED_RECORD`. **15 tables
reference `collection(id)` with NO `ON DELETE CASCADE`** — `field_history`,
`file_attachment`, `bulk_job`, `validation_rule`, `list_view`, `page_layout`,
`record_type`, `report`, `note`, `script_trigger`, `layout_assignment`,
`layout_related_list`, `email_template.related_collection_id`,
`approval_process`, `field.reference_collection_id`. Several (`field_history`,
`file_attachment`, `script_trigger`, `bulk_job`) have **no delete API**. So once
a collection has been used — any record write creates `field_history` rows — it
can no longer be deleted through the API/CLI at all. (`field.collection_id`,
`collection_version`, `search_index`, profile permissions DO cascade.)

Surfaced when the CLI e2e round-trip (`e2e-tests/tests/admin/cli-smoke.spec.ts`)
ran against the **couchpicks product tenant** and left 10 undeletable
`e2e_cli_*` / `e2e_test_*` collections (plus 5 `e2e_wizard_*` that were
cleanable). The page-builder wizard specs leak the same way.

Fixes applied: the cli-smoke round-trip now refuses to create schema unless the
tenant slug is disposable (`/e2e|test|sandbox|ci/`) or
`KELTA_E2E_ALLOW_SCHEMA_MUTATION=1` is set, and its teardown deletes data
records before the collection.
1. **DONE (V181 + V182).** All owning `collection(id)` FKs now `ON DELETE CASCADE`
   (V181 did 18; V182 added the one it missed, `record_version` — see the "FIXED (V181…)"
   entry earlier).
2. **DONE (force-delete teardown).** The deploy e2e still runs against `default` (a product
   tenant — a dedicated disposable tenant would need its own `E2E_API_TOKEN` secret), but the
   specs now clean up after themselves rather than leaking: `DataFactory.cleanup()` deletes
   collections with `?force=true` (so the `CollectionDeletionGuardHook` lets it through and the
   FK cascade removes the children) and surfaces failures via `console.warn` instead of
   swallowing them. UI-created collections (the wizard spec had **no** teardown, the source of
   the `e2e_wizard_*` litter) are registered with `DataFactory.trackCollectionByName(name)` so
   the same force-delete cleans them. A dedicated disposable e2e tenant is still the ideal
   (preserves the ability to run destructive specs without any product-tenant blast radius);
   deferred on the secret.
3. **DONE (2026-08-08).** The 11 stuck `e2e_*` collections (10 `e2e_test_*` + 1 `e2e_cli_*`,
   plus 2 more that leaked mid-cleanup) were force-deleted from couchpicks once V181+V182 and
   the guard were live; only the 14 real domain collections remain.
