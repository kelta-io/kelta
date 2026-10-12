# Codebase Concerns

Living catalog of **open** risks, accepted trade-offs, fragile areas and the guardrails that keep
fixed bugs fixed. Fixed items are condensed to one line each in **Resolved (archive)** at the
bottom; their full write-ups are in git history (`git log -p -- .claude/docs/concerns.md`).
When you close an item, move it there in the same PR.

## Security Risks

### Tenant scoping of system collections

Tenant scoping is enforced once, in `PhysicalTableStorageAdapter.query()` (PLT-260): it
**appends** `tenant_id = ?` — or `tenant_id IN (?, SYSTEM_TENANT_ID)` for the collections
`SystemCollectionTenancy.sharesSystemRows` names (`collections`, `fields`) — so every direct
`queryEngine.executeQuery` caller (dashboards, reports, page render, export, bulk, campaigns) is
scoped without knowing it. `aggregate()`, `semanticSearch()` and the `COUNT(*)` behind
`totalCount` get the same predicate. Self-scoped `tenants` gets `id = <bound tenant>` on every
read path including `getById` (PLT-330). `DynamicCollectionRouter` keeps its own injection as a
second layer; both read the shared-rows list from `SystemCollectionTenancy`.

**Still open:**
- **By-id and write paths remain router-only** for `tenant_id`-scoped system collections:
  `getById`, `update` and `delete` take no tenant predicate, so `visibleToTenant` (`GET /:id`)
  and `injectTenantId` (writes) are the only application-layer check. A direct caller can load a
  single foreign system record by guessing its id. Closing it needs a pass over the
  cross-tenant write paths (provisioning, promotion, the create read-back) that legitimately
  touch another tenant.
- **Eleven tenant-scoped system collections have no `tenant_id` column** (`package-items`,
  `record-type-picklists`, `script-triggers`, `approval-steps`, `approval-step-instances`,
  `connected-app-tokens`, `job-execution-logs`, `bulk-job-results`, `collection-versions`,
  `field-versions`, `migration-steps`) and are isolated through the parent FK. The adapter
  introspects `information_schema.columns` once per table and scopes anyway when inconclusive;
  `DynamicCollectionRouter`'s own injection has always produced a SQL error for these eleven on
  the JSON:API list path (pre-existing, untouched).
- **Unbound reads are unfiltered** by design (Flyway, bootstrap, scheduler sweeps, the RLS
  `admin_bypass`). The adapter WARNs when a tenant-scoped system collection is read with no
  tenant (DEBUG on `scheduler-`/`scheduling-` threads and on a `MANAGE_TENANTS` listing of
  `tenants`), so a WARN means "possible leak". An **anonymous** no-tenant read of a tenant- or
  self-scoped collection becomes `WHERE FALSE` (PLT-354, `refusesAnonymousRead`); an
  authenticated no-tenant read still runs unscoped.
- The in-code predicate is the first line of defence; RLS is the backstop.

### RLS invariants

RLS is enforced in production (`NOBYPASSRLS` app role, V200–V202, V205). Rules that keep it working:
- **Bind a tenant only with a `ScopedValue` helper** — `runWithTenant`/`callWithTenant(id, slug, …)`
  to act for a given tenant (shadows any outer binding), or `runWithTenantIfUnbound`/
  `callWithTenantIfUnbound` in shared code that must let an outer request binding win. There
  are no ThreadLocal setters; worker tests bind with `@WithTenant` (`testing.md`).
- **Never bind a sentinel tenant.** `admin_bypass` matches only the empty setting, issued when
  no tenant is bound; `runAsPlatform`/`callAsPlatform` bound a sentinel, matched no policy and
  silently returned zero rows — they were removed. Don't reintroduce a sentinel helper without a
  policy that matches it.
- **Shared rows under a non-tenant `tenant_id` need their own `system_rows_read` policy**
  (`collection`/`field` V200, `email_template` `'system'` rows V205). The catalog sweep checks
  the policy pair, not that shared rows stay readable.
- **Child tables:** `everyTenantDerivedTableIsCovered` walks the catalog (a `tenant_id` column
  or an `ON DELETE CASCADE` FK to one) — a new child table cannot ship without a policy.
  `system_rows_read` exempts `collection`/`field` but **not** `collection_version` /
  `field_version`, whose policies key strictly on the parent's `tenant_id`.
- **Superset logins** are granted table by table on RLS-enforced `tenant_id` tables only
  (`supersetRoleIsGrantedOnlyTenantScopedTables`); pinned roles cannot hop tenants via the GUC
  (V201).
- **Hand-rolled JDBC must write `AND tenant_id = ?` on the table that owns the row** — RLS is
  not a substitute (see `PageLayoutTreeService` in the archive) — and test with a second tenant.

**OPEN — the harness's *service containers* still bypass RLS.** `KeltaStack` provisions a
`NOBYPASSRLS` role (`ScenarioBase.openAppDbConnection()`) and some scenarios assert through it,
but kelta-worker and kelta-auth still connect as the image's bootstrap superuser, so the ~40
scenario tests exercise the gateway/JWT boundary, not the database one. It is a false-green
risk: `SandboxAdminLoginScenarioTest` passed while the code was broken in production (PLT-423).
Two attempts under PLT-264 failed CI because any policy violation during `KeltaStack.start()`
aborts the whole harness. What the next attempt needs:
- Flyway as the app role works only if the role owns schema `public` (the baseline's
  `COMMENT ON SCHEMA public` needs `pg_database_owner` since PG 15), and extensions (`pg_trgm`,
  `vector`) are pre-installed by provisioning — `CREATE EXTENSION` is superuser-only.
- Already ruled out (replayed clean as `NOBYPASSRLS` on PG 15): migrations, provisioning hook
  SQL, Superset grant narrowing, `kelta_migrations`/`collection_version`/`field_version` writes.
  Likeliest suspects: services that copy rows **between** tenants (`SandboxProvisioningService`,
  `MetadataPromotionService`) and anything reading a system-collection child row under a tenant
  binding (`collection_version`/`field_version` above).
- `KeltaStack.startService` prints a failed container's log tail and `KeltaStackExtension`
  remembers a failed start instead of retrying it per scenario class.

Until this lands, `RowLevelSecurityIntegrationTest` (migrates as `NOBYPASSRLS`, drives queries
through `TenantAwareDataSource`) and per-feature `*RlsIntegrationTest`s are the coverage that
actually gates the policies.

**Proposed, not shipped — RLS on `tenant` (PLT-330 assessment).** A `tenant_isolation` policy on
`id` plus `admin_bypass` would be a sound second layer, but raw SQL that reads *another* tenant's
row while a tenant is bound would silently see nothing. Audit first: `SandboxProvisioningService`
(slug uniqueness check + sandbox slug lookup under the parent's context), `EmailRepository`'s
inbound routing by `email_from_address`, and kelta-auth slug→id lookups (`TenantContextFilter`,
`KeltaUserDetailsService`, `PortalLoginService`) if any run after a tenant is bound.

### Dependency vulnerability scanning

- **npm** is gated by `dependency-audit` in `quality-gate`, diffing against
  `ci/npm-audit-baseline.json`. **The baseline is debt, not an allowlist**: 26 entries
  (8 `kelta-web`, 18 `kelta-ui/app`) — `axios` / `react-router` / `form-data` / `fast-uri`
  highs. Refresh this count whenever the baseline moves.
- **Java dependencies are still unscanned.** OWASP dependency-check is blocked on the
  `NVD_API_KEY` secret (`NvdApiException: Invalid API Key`). Follow-up: regenerate the key, then
  land the job (dependency-check 12.2.2, `-DnvdMaxRetryCount=10 -DnvdApiDelay=4000`), or use
  `-DnvdDatafeedUrl` to drop the API-key dependency. `dependency-check-suppressions.xml` is still
  the untouched template.
- **Rule** (third occurrence of a configured-but-never-run tool): when adding any checker, verify
  a job runs it *and* that its failure exit code reaches a gate, in both directions.

### No malware scanning (support mailbox makes strangers the upload source)

There is no ClamAV, scanning sidecar or third-party scanner. `mailbox_attachment.scan_status` is
written `UNKNOWN` unless SES's own verdict caught it. What protects the platform today: SES scans
mail arriving through it (a `FAIL` verdict discards attachments and marks the thread SPAM);
nothing is ever executed or rendered server-side; every download is inert
(`AttachmentContentType.serveAs` refuses active types, `Content-Disposition: attachment`,
`nosniff`). A malicious file still reaches an agent who saves and opens it. `INFECTED` is already
enforced on download, so wiring a scanner means writing that value. Do it before opening a
mailbox to a high-volume public address.

### Public-traffic controls

- **`/portal/**` is limited in kelta-auth, not the gateway** — it has its own ingress; a gateway
  `ip-paths` entry for a portal path matches nothing. Use `kelta.auth.rate-limit.ip-paths`.
- **The fixed-window TTL must never be refreshed per request.** `RedisRateLimiter` (gateway,
  reactive) and `PortalPublicRateLimitFilter` (kelta-auth, servlet) carry the same Lua guard and
  must stay behaviourally identical; each has a regression test. Refreshing turned the window
  into an idle-expiry → tenant-wide self-sustaining 429 lockout (2026-07-11).
- **Limiter keys never trust the leftmost `X-Forwarded-For`** — kelta-auth counts
  `trusted-proxy-count` hops from the right (set 0 when directly exposed).
- Everything fails **open** on a Redis outage except the bot challenge's replay claim (fails
  **closed**).
- **e2e runs at ~84% of the tenant window** (1735 req / 5 min at the 100k default), so
  `docker-compose.yml` exempts the compose network via `RATE_LIMIT_EXEMPT_CIDRS` (local/CI
  only). Consequence: **e2e no longer exercises either limiter** — limiter regressions must be
  caught by unit tests.
- **Per-user budget = 0.9 of the tenant window, on purpose.** One admin or integration PAT
  legitimately is most of a tenant; 0.5 halved real single-user throughput. Bound the runaway
  credential, don't attempt fair division.
- `PublicSurfaceTest` pins the gateway's unauthenticated prefix allowlist against the shipped
  `application.yml`; widening it is one YAML line, so update the test deliberately and make sure
  the new endpoint carries its own trust anchor (unauthenticated paths also bypass
  `TenantIpAllowlistFilter`).

### Guardrails on privileged surfaces (must stay intact)

- **Sandbox environments + promotion (V158):** in-controller gates (`MANAGE_SANDBOXES` on
  `/api/environments/**` + `/api/promotions/**`, `CUSTOMIZE_APPLICATION` on `/api/packages/**` —
  static routes give only `API_ACCESS`); approver ≠ creator + APPROVED-only execute; `ROLE`/
  `POLICY`/`ROUTE_POLICY`/`FIELD_POLICY` never promoted; provisioning copies the parent's IP
  allowlist and replaces the seeded admin's password (the allowlist filter fails open);
  `RemotePromotionClient` (admin-supplied URL): http/https only, no userinfo, no redirects,
  bounded timeouts/response, PAT only from the vault, private ranges allowed deliberately.
  Accepted v1 limits: per-item import (no global tx); rollback doesn't undo creations/deletions;
  no local snapshot/rollback for remote targets; one NATS config event per imported item;
  sandboxes count against platform quotas; ≤60 s gateway slug-cache lag after create.
- **`POST /api/operations`:** `AtomicOperationsController` mirrors the gateway verb check via
  `CerbosAuthorizationService.checkCollectionAccess`, keyed on the collection **UUID**
  (`getCollectionIdByName`) — keep it UUID-keyed; any denial fails the whole batch; missing
  identity or Cerbos error denies. It stamps `createdBy`/`updatedBy` (KLT-500) but, unlike the
  router, never stamps `createdGeo`/`updatedGeo` and doesn't inject `tenantId` for tenant-scoped
  system collections when the client omits it.
- **Delegated administration (V157):** `DelegatedAdminScopeValidationHook` rejection of
  privileged profiles; `DelegatedAdminService.effectiveScope` **request-time re-filter** (don't
  cache it away); `DelegatedUserAdminController` field whitelist (email/managerId/mfa immutable,
  self-edit blocked); `IdentityCollectionGuardHook` default-deny for identified writes.
- **Member self-profile (KLT-379):** caller id only from `CallerContext` (none → 404); the
  allow-list `MyProfileController.EDITABLE_FIELDS` is a code constant — widening it (above all to
  `email`, `status`, `profileId`, `userType`, `managerId`, `mfaEnabled`, `settings`) is a
  reviewed security change; `SelfProfileWriteContext` admits only a `users` update of the
  grant's own id with allow-listed fields (not a blanket pass).
- **Mass-email campaigns (V152):** `CAMPAIGN_TRACKING_SECRET` must be a strong per-deployment
  value (startup fails without it); `/api/track/**` is unauthenticated — the HMAC token is the
  only authenticator, never accept an unsigned recipient id; click redirect stays `http(s)`-only
  (`isSafeUrl`); sends stay gated on `MANAGE_CAMPAIGNS` + suppression + `campaignEmailsPerDay` +
  throttle; campaign collections stay read-only on the generic route.
- **`MANAGE_TENANTS` can act as any non-platform tenant's admin for up to 24 h** (accepted,
  PLT-342) via `POST /api/tenants/{id}/bootstrap-token` — grant it like a superuser. Bounds (any
  change is a security change): default 1 h / max 24 h (`MAX_LIFETIME`), Redis `pat:<hash>`
  cached for exactly the remaining lifetime; the PAT is rejected on any other tenant's URL; the
  platform tenant (and non-UUID ids) → 404; user lookup tenant-filtered; every attempt logged as
  `TENANT_BOOTSTRAP_TOKEN_ISSUED` on `security.audit` (log-only, not a table row). Residual: the
  token is never auto-revoked when the tenant is claimed; expired tokens no longer count against
  the 10-live cap.
- **Server-only fields:** secret material on system collections (`credentials.dataEnc`,
  `credential-oauth-tokens.accessTokenEnc`/`refreshTokenEnc`, `oidc-providers.clientSecretEnc`,
  `connected-apps.clientSecretHash`, `connected-app-tokens.tokenHash`) is declared
  `FieldDefinition.withServerOnly(true)`. `DefaultQueryEngine` strips it from every read, the
  create/update return and `record.changed` payloads, rejects it as a filter/sort/aggregate key
  (400) and drops client-supplied values before hooks run; the router serializer, schema/OpenAPI,
  search index, field history, record versions and setup audit exclude it too. Real use reads the
  column over JDBC. Any new secret, encrypted or credential-hash field gets the flag and joins the
  list pinned by `SystemCollectionDefinitionsTest.secretMaterialIsServerOnly`
  (`ServerOnlyFieldScenarioTest` covers the read shapes). Residuals: tenant-authored fields cannot
  set the flag (code-declared, not stored on the `field` row); the gateway's in-memory
  `SystemCollectionResponseCacheFilter` (10 min) can serve a pre-deploy response until it expires
  or the pod restarts.

### Authorization gaps still open

- **Static routes over system collections stop at `API_ACCESS` for generic CRUD** — PLT-331 added
  a collection check for `users` (all methods), `profiles` and `collections` (writes) via
  `RouteConfigService.COLLECTION_CHECKED_STATIC_ROUTES`, but `page-layouts`, `list-views`,
  `ui-menus`, `ui-pages`, `global-picklists`, `oidc-providers`, `email-templates`, `scripts`,
  `scheduled-jobs`, `webhooks`, `connected-apps`, `flows`, `approval-processes`, `reports`,
  `dashboards`, … do not. Each needs an audit of which reads ordinary profiles rely on before it
  gets a row.
- `CsvImportController` (`POST /api/collections/{name}/import/csv`) creates records with no
  create-permission check on the target collection.
- `GET /api/profiles/{id}?include=profile-system-permissions,…` returns grant rows at
  `API_ACCESS`; the `profile-*-permissions` routes' own object check does not apply to an include.
- **Approval instances/steps are tenant-visible to every user** (accepted 2026-07-08).
  `approval-instances`/`approval-step-instances` (incl. `comments`) have no row-level read
  restriction beyond tenant RLS. Owner scoping doesn't fit (submitter **or** assignee); needs a
  read-side hook or Cerbos record policy. Revisit before approvals carry sensitive comments.
- **Realtime socket has no per-subscriber FLS** (accepted). `RealtimeBridge` fans each event —
  collection, id and, for non-masking collections, `data` — to every tenant subscriber; the
  client is invalidation-only. Residual: subscribers learn change activity. v2.
- **Record merge / bulk update skip write-FLS** (accepted). They call `QueryEngine` from the
  service layer, so `CerbosFieldWriteSecurityAdvice` doesn't run; the boundary is `MANAGE_DATA` +
  RLS + validation/hooks. Revisit if either is exposed to a lower-privilege role. Merge is never
  auto-invoked by a flow.
- **Tenant IP allowlist / client IP trust — open until the deploy sets
  `KELTA_SECURITY_TRUSTED_PROXIES` (PLT-341).** With `kelta.security.trusted-proxies` unset,
  `TenantIpAllowlistFilter` allows a request when **any** IP in the chain matches, so a non-admin
  bypasses the allowlist with `X-Forwarded-For: <allowed IP>`, and audit/geo/rate limits/
  `login_history.source_ip` use the client-controlled leftmost hop. Fix: set the list to the
  ingress CIDR (and kelta-auth's), then flip the default to strict once quickstart/compose set it.
  The worker trusts the gateway-stamped `X-Kelta-Client-Ip` like `X-User-Id`; the filter is
  fail-open by design — pair it with network controls.
- **Network-level defense-in-depth (out of tree):** worker/auth/ai must be reachable only via the
  gateway — enforced by NetworkPolicy + mTLS in the cluster, not by app code. No NetworkPolicy in
  this repo; verify on the cluster.
- **Client `X-Tenant-ID` / `X-Tenant-Slug` are a claim, not a tenant** (PLT-355, decided). The
  URL tenant wins; without one the header is a claim the JWT `tenant_id` / PAT `tenantId` must
  match; anonymous requests never get a header-claimed tenant; client headers are always stripped
  and `HeaderTransformationFilter` re-adds the settled values (`architecture.md` → Tenant
  resolution rules; pinned by `TenantHeaderTrustTest`). No in-repo client relies on the header to
  reach a tenant. Residual: on a URL tenant a JWT with no `tenant_id` claim is accepted
  (`DynamicReactiveJwtDecoder` ignores its `tenantId` argument); a PAT on a slug-less URL with no
  header runs with no forwarded tenant; kelta-auth's `SessionController`/`SmsAuthController` read
  `X-Tenant-ID` themselves outside this chain.
- **Own-record rules and Guest (KLT-377/378):** the worker fails closed (401
  `CALLER_UNRESOLVED`) when an email `X-User-Id` resolves to no user; a non-email, non-UUID
  identity binds no `CallerContext`; Guest binds the nil UUID as INTERNAL, so under `ALL` it sees
  only nil-UUID-owned rows shared by every Guest. **Don't grant Guest create on an `ALL`-scoped
  collection.**

### Owner-scoped collections — paths outside the storage adapter (KLT-378, accepted)

Owner scoping is enforced in `PhysicalTableStorageAdapter` (reads) and `OwnerScopeGuardHook`
(writes via `QueryEngine`). Anything else is **not** narrowed:
- **Raw-JDBC member-data paths** — `WatchRepository`/`WatchTargetRepository`,
  `AlertRepository`/`AlertDeliveryRepository`, `PushRepository`, `WinController`, `ChatService`,
  `AppointmentService` — keep their in-code scoping
  (`SelfScopedController`, in-controller caller checks).
- **Connected-app machine identities** bind no `CallerContext`, so the guard admits their writes
  as the internal tier. Their reach is bounded by Cerbos grants; don't grant one write on an
  owner-scoped collection it shouldn't edit freely.
- **Support mode on `WatchController`** writes as the named member only after the `MANAGE_DATA`
  check; any new support write path must keep that order.
- **Full-text search** — `SearchIndexService` queries `search_index` with a tenant-only predicate,
  so `/api/_search` can return a foreign record's id + `display_value`. Semantic search *is*
  scoped. Keep sensitive data out of an owner-scoped collection's display field.
- **Realtime** — invalidation-only, but every subscriber still learns collection + id.
- **Performance** — index the owner field on large collections (no generic index migration).
- **Cross-pod window on an ownership change** — other pods keep the previous definition until they
  consume `kelta.config.collection.changed`. Enable ownership before members write, or accept the lag.
- **Same-pod refresh ordering** — `CollectionLifecycleManager` holds a per-collection monitor across
  read → register (`CollectionLifecycleManagerConcurrencyTest`). Don't add a registry write
  outside that monitor.

### Credentials and sessions

- **PAT `lastUsedAt` is never written.** No path updates `personal_access_token.last_used_at`;
  `requestCount` is the only liveness signal. Cheapest fix: a throttled write from the
  fire-and-forget path that bumps `pat-usage:<hash>`. Any "revoke unused keys" job must not key
  off `lastUsedAt`.
- **Deactivating a user does not invalidate its PATs.** The gateway builds the principal from the
  Redis-cached `pat:<hash>`; the `status = 'ACTIVE'` check lives only in the worker's
  `/validate/{hash}` fallback on a miss. There is no admin revoke endpoint
  (`AdminPersonalAccessTokenController` only mints). Fix either way: on status change delete or
  revoke every `pat:<hash>` for the user, and add `DELETE /api/admin/users/{id}/tokens/{tokenId}`.
  Immediate kill switch until then: remove `API_ACCESS` from the profile.
- **PAT grace cache** (`PatAuthenticationFilter`, 5 min, serves validated PATs during worker
  outages; revocation is always checked first) is an unbounded map aged by TTL. If distinct PAT
  volume grows, switch to a size-bounded Caffeine cache.
- **Platform admin first boot (PLT-343).** `BaselineAdminPasswordInitializer` swaps the baseline
  hash for `KELTA_BOOTSTRAP_ADMIN_PASSWORD` or a generated one with forced change. Residual: a
  generated password is in the first boot's kelta-auth log (shipped to Loki) until changed —
  production should set the env var; HTTP sessions are not revoked (non-indexed Redis session
  store) and direct-login JWTs run to expiry; the `oauth2_authorization` cleanup also signs out a
  same-named admin of another tenant once; `ForcePasswordChangeController` updates every ACTIVE
  user with the submitted email, with only the session's RLS binding limiting it to one tenant.
  There is no test-only opt-out of the forced change (`testing.md`).
- **SPA refresh token in `localStorage` for up to 30 idle days** (accepted). XSS or a malicious
  plugin bundle can read it. Mitigations: rotation on every use, 30-day idle TTL, logout clears
  the key and ends the auth session. Not done: server-side revoke on logout (public client has no
  client auth to `/oauth2/revoke`), refresh-token family revocation on reuse, DPoP binding.

### Data masking — v2 contexts (accepted)

Masking is enforced on JSON:API (`CerbosFieldSecurityAdvice` strip → mask), filter/sort probes
(`MaskedFieldPredicateInterceptor`), realtime `data` suppression, data export, reports (incl.
CSV/PDF; filter/sort/group-by on a masked field rejected), dashboards (shared widget cache bypassed
for masking collections), duplicate detection (masked match rejected), the full-text index and
embeddings (excluded at index time; vectors purged when masking toggles). **Still emit plaintext
to their gated callers (system trust tier, same contract as FLS):** scheduled report delivery and
background exports (`null` principal), flows/scripts/webhooks/NATS consumers, bulk/merge write
side. Plaintext exists in-process between decryption and the advice — never log field values on
that path. `FieldHistorySecurityAdvice` and `RecordVersionSecurityAdvice` are the single guards
between history rows and an under-privileged caller; treat changes to either as security-sensitive.

### Data growth

- **`record_version` has no retention.** With `collection.track_history` on, every
  create/update/delete writes a full jsonb snapshot. Revisit before production-scale tenants
  enable it broadly (per-record cap, age-based purge). v1 limits: DELETED version's `changed_by`
  is the last updater (no principal reaches `QueryEngine.delete`); historical lookup ids render
  raw.
- **Flow/job logs — `FlowLogRetentionSweep` is DRY-RUN by default**
  (`kelta.flow.retention.dry-run:true`), so in any environment left in dry-run `flow_execution`,
  `flow_step_log`, `flow_pending_resume` and `job_execution_log` still grow without bound. When
  armed: terminal statuses only (`COMPLETED|FAILED|CANCELLED`), age `COALESCE(completed_at,
  started_at)`, `SKIP LOCKED`, `batch-size × max-batches` per cycle. Deletion is irreversible and
  purged runs vanish from the flow-run UI — confirm the 60-day window, watch `pg_stat_activity` on
  the first armed cycle.
- **`analytics_event` — `AnalyticsRetentionSweep` is DRY-RUN by default**
  (`kelta.analytics.retention.dry-run`, 90 days). Same posture as the flow sweep. Batch ingest /
  partition by `occurred_at` only when volume justifies it. `member_id` erasure on account close is
  owed with the rollup slice.
- **`chat_message`** lives in the shared public schema; `RetentionPurgeSweep` deletes live rows
  `purgeLiveAfterDays` (90) after archival — **dry-run by default**
  (`kelta.telehealth.retention.purge-dry-run`). The gateway chat-membership cache (30 s,
  fail-closed) lets a just-removed participant receive id-only events for up to 30 s.
- **Retention purge is DESTRUCTIVE (telehealth).** Armed, `RetentionPurgeSweep` permanently deletes
  archive artifacts + recording S3 objects (the archive row stays as a tombstone) and live chat
  rows; legal-hold rows are excluded in SQL and re-checked per row. **Confirm the operator's
  backup/restore posture before setting `purge-dry-run=false`**; leave dry-run on for one full
  cycle and review the "would purge" logs first. WORM/object-lock is documented, not enforced.

### Telehealth video (LiveKit) ops

**TURN-TLS is the patient-network release gate.** The LiveKit app ships with TURN disabled (needs
a TLS cert on 443/5349), so patients on UDP-blocked networks cannot connect until it's enabled and
tested from a restricted network. Accepted v1 trades: one shared SFU across tenants (room-scoped
tokens isolate access), single hostNetwork replica (`Recreate` → brief outage on redeploy), UDP
50000–50100 must be open for WAN patients, dev key defaults must never reach prod
(`KELTA_LIVEKIT_*`, `KELTA_TELEHEALTH_VISIT_SECRET`).

## Tech Debt

**Feature flags that look dead but are not (2026-07-02 audit) — do not inline:**
- `kelta.scheduler.enabled`, `kelta.bulk.processor.enabled` are `false` in
  `application-migrate.yml` so the PreSync migrate pod starts no background workers and exits.
  `kelta.email.enabled=false` gives a no-op `EmailService`; `kelta.flow.enabled` /
  `kelta.modules.runtime.enabled` gate real subsystems.
- Presence-gated secrets/integrations (removing the gate constructs a bean with a null secret):
  `kelta.encryption.key`, `kelta.svix.auth-token`, `kelta.superset.url`, `kelta.sms.provider`,
  `kelta.push.provider`.
- `kelta.{worker,gateway,auth}.internal-auth.enabled` (default off): flipping on + removing is a
  security change needing the gateway client-credentials path verified first.

**Large files needing decomposition** (line counts measured 2026-10-11):

| File | Lines | Proposed fix |
|------|------:|--------------|
| `PhysicalTableStorageAdapter.java` (runtime-core) | 2,423 | Extract SQL builder / DDL classes; separate concerns |
| `CollectionDetailPage.tsx` (kelta-ui) | 2,208 | Split tabs into their own components |
| `DynamicCollectionRouter.java` (runtime-core) | 2,043 | Extract RouteResolutionHandler, InclusionHandler, FieldMapperHandler |
| `FieldEditor.tsx` (kelta-ui) | 1,988 | Split per-type config sections |
| `PageBuilderPage.tsx` (kelta-ui) | 1,474 | Extract page-list table, config modal, editor shell |
| `RuntimeModuleManager.java` (kelta-worker) | 1,300 | Split JAR loading / registration / lifecycle |
| `SchemaMigrationEngine.java` (runtime-core) | 1,106 | Split non-destructive vs destructive paths |

**Resolved:** `SystemCollectionDefinitions.java` (runtime-core, ~3,400 lines and the repo's
biggest merge hotspot) was split 2026-10-12 into one class per section (`CoreSystemCollections`,
`UiSystemCollections`, `AutomationSystemCollections`, `AuditLogSystemCollections`, … — 17 in
`model/system/`, largest ~390 lines) plus package-private `SystemCollectionBuilders`.
`SystemCollectionDefinitions` keeps only `all()` / `byName()` / `SYSTEM_TENANT_ID`.
Two parallel slices adding system collections now usually touch different section files and
only collide on the `all()` list.

**Other:**
- **Two `oidc-providers` hooks have never run.** `OidcDiscoveryHook` (fills endpoints from
  `.well-known/openid-configuration`) and `OidcProviderSecretHook` (encrypts a submitted
  `clientSecret` into the server-only `clientSecretEnc`) were `@Component`s while hooks were
  registered by hand in `FlowConfig`, and nothing registered them. Hooks are now registered by
  being beans (`BeforeSaveHookRegistrar`), so both were made plain classes to keep behaviour
  unchanged. Consequence today: discovery does not run on save, and the API cannot set
  `clientSecretEnc` — the admin UI's required client secret is silently dropped, so a
  confidential-client SSO provider has no stored secret. Enabling them is a deliberate change: annotate, add to
  `BeforeSaveHookWiringTest`, and test the OIDC provider flow end to end.
- **Email templates have two lookup axes** — `EmailRepository.findTemplateByKey` (`template_key`,
  V133 `user.*` seeds) and `findTemplateByName` (`name`, V141 seeds). Both fall back
  tenant → `'system'`, so the "wrong" axis still works but may land on a different seed row.
  Deliberately retained (both live on password reset/invite); pick one axis once callers settle.
- **`@dnd-kit` is scoped to the page canvas only** — `PageLayoutsPage`/`MenuBuilderPage`/
  `FlowDesignerPage` stay on native HTML5 DnD; don't mix the two in one tree. jsdom pointer-DnD is
  flaky: drive dnd-kit via the KeyboardSensor in tests.
- **Page save-path invariant:** `mergeConfig` overlays a key only when the `handleSavePage` call
  passes it — every page-level sibling (`components`, `variables`, `dataSources`, `access`,
  `schemaVersion`, …) must be passed at the call site.
- **`config.layout` is inert legacy** — the create-form `layoutType` select still writes it;
  nothing reads it. Remove once no page depends on it.
- `SupersetDatabaseUserService` defaults the DB name to `emf_control_plane`
  (`kelta.worker.superset.database-name`); cosmetic.
- Possible N+1 in `SearchIndexService` reindex (per-collection queries) — matters past ~1k collections.
- **Bulk/related-list mass edit** is gated on `MANAGE_DATA` in the UI because bulk writes bypass
  per-record Cerbos; bulk UPDATE runs `QueryEngine.update` (hooks fire, write-FLS doesn't).
- **`npm run knip` (kelta-ui) is not a CI gate.** After the 2026-10 dead-code batch it reports zero
  unused files but still ~300 unused exports, ~300 unused exported types, ~100 default+named
  duplicate exports and a few unlisted deps, so wiring it in would fail every build. Clear those
  (or scope knip to `--include files,dependencies`) before adding it to `ci.yml`.
- **Media fields — accepted v1 limits** (`MediaUploadController`; revisit before media carries
  anything but public images):
  - Declared `fileSize` is advisory — the presigned PUT signs `Content-Type`, not
    `Content-Length`; the media bucket needs its own quota/lifecycle policy.
  - No storage accounting or cleanup: media is outside the `file_attachment` governor sum, and
    objects outlive record deletes, value changes and abandoned uploads (never served — a
    download needs a referencing record — but they cost space).
  - Every download is a query per media field until a match, plus a record-level Cerbos decision;
    mark media fields `indexed` (redirect cached 60 s).
  - Keys embed the collection name and the download checks it, so renaming a collection orphans
    its existing media URLs.
  - The upload response (`media-upload-urls`) passes through `CerbosRecordAuthorizationAdvice`
    (`create`) and `CerbosFieldSecurityAdvice`; a profile denied `create` collection-wide gets
    `data: null`, not 403. The worker-side checks are the backstop behind the gateway route authz.
  - Deploy coupling: a collection's downloads 404 until its field is media-enabled, and the bucket
    is configured only via `KELTA_MEDIA_*` — set both before rolling the worker.

## Fragile Areas

### The local auth issuer must be one URL for the browser and for every container

Locally `KELTA_AUTH_ISSUER_URI` is `http://auth.localhost:8081` in kelta-auth, worker and gateway
(`docker-compose.yml`, `.run/*.run.xml`). The browser fetches the issuer's discovery document and
follows its endpoints; the gateway accepts only that exact `iss` and fetches `<iss>/oauth2/jwks`
from its container — so the issuer must resolve to kelta-auth on both sides. It works because
`auth.localhost` is a network alias of the kelta-auth container, kelta-auth listens on 8081
*inside* the container too (`SERVER_PORT`), and `*.localhost` resolves to loopback → published
8081. Breakers: a different host vs container port, dropping the alias, or one service on a
different issuer. Chromium in a container needs `E2E_BROWSER_HOST_RULES=MAP auth.localhost
kelta-auth` and `E2E_BROWSER_SECURE_ORIGINS=http://kelta-ui:8080` (PKCE needs `crypto.subtle`).
`BaselineIdentityProviderReconciler` repairs the `default` tenant's baseline `oidc_provider` rows
when the issuer differs from production. Guard: `e2e-tests/tests/auth/real-sign-in.spec.ts`
(`E2E_REAL_SIGN_IN`). **kelta-auth's `spring.session.*` block (`store-type: redis`,
`same-site: strict`) is inert** — Boot 4 moved Spring Session auto-config to a module not on the
classpath; wiring it with `same-site: strict` would break the cross-site local sign-in hop, and
multi-replica kelta-auth has no shared sessions today. The harness uses `http://kelta-auth:8080`
consistently.

### `DynamicCollectionRouter` shadows any `/**` mapping under `/api` (GET only)

`DynamicCollectionRouter` is `@RequestMapping("/api")` with all-variable mappings up to four
segments (`@GetMapping("/{parentName}/{parentId}/{childName}/{childId}")`). Spring ranks **any
pattern containing `**` last**, so a controller catch-all under `/api` loses every GET the router
can match → 404 as a record read, with only an INFO `Collection '<segment>' not in registry` line.
POST mappings stop at three segments, so POST works — which reads as a method-specific bug.
**Rules:** never map `/**` for a worker endpoint under `/api` (spell out depth-1..N, as
`ModuleHttpController` does); a MockMvc test of the controller alone cannot see this — register a
stand-in for the router's nested GET mapping (`ModuleHttpControllerMvcTest`,
`CollectionSchemaControllerTest`).

### Runtime module loading

- **A transient infrastructure error must never be written as a terminal state.** Only failures
  `ModuleLoadFailureClassifier` calls `PERMANENT` (bad signature/checksum, missing JAR, classload,
  throwing `onStartup`) may record `QUARANTINED`; storage/DB/network failures fail closed on the
  pod, write only `last_error`/`load_attempts` (`ModuleStore.recordLoadError`) and retry
  (`ModuleLoadRetryScheduler`). Any new "mark it broken" path must ask whether a retry could fix it.
  Alert on `kelta_worker_module_load_retry_pending`.
- **Rows quarantined before the transient-retry fix stay `QUARANTINED`** (open) — an old outage
  quarantine is indistinguishable from a real one. Re-enable them
  (`POST /api/modules/{moduleId}/enable`), which now reloads on running pods without a restart.

### Gateway routing

- **Member-controller static routes that share a path with a system collection must be in
  `RouteRegistry.AUTHORITATIVE_STATIC_PATHS`** (`/api/watches|wins|devices/**`), or the
  collection's generic route overwrites the `static-` route and portal members get 403. Not a
  blanket "static wins": config collections rely on their generic route's per-resource Cerbos.
- **Routes are keyed by (path, tenant)** — collection names are unique only per tenant (sandboxes
  clone every name). `RouteRegistry` resolves the caller's tenant, then a `static-` route, then any
  tenant's route so authorization still runs. A collection turning `active=false` keeps its route
  until the next bootstrap (four publishers send UPDATED without the flag).
- **Never publish `RefreshRoutesEvent` directly** — go through `RouteRefresher.refresh()`, which
  serializes fetch-and-store; concurrent refreshes stored stale snapshots (new collections 404'd).
- **Any state the gateway loads only at startup needs a retry.** `RouteInitializer` retries the
  `/internal/bootstrap` fetch with backoff; without it a gateway that started before the worker
  served static routes only for hours.
- **Error bodies are built from `JsonApiError.toMap()`**, never the bean — on native images the
  bean serializes as `{"errors":[{}]}` (worker `GlobalExceptionHandler`, gateway `GlobalErrorHandler`).

### Storage, DDL and collection deletion

- **Identifiers:** never pass a collection name into `sanitizeIdentifier` directly — generated
  index/constraint *names* go through `identifierPart` (hyphens → underscores) and
  `buildBoundedIdentifier` (≤63 chars, CRC32 suffix when truncated); table *references* go through
  `TableRef` quoting; column references through `quoteIdentifier` (reserved words like `user`).
- **FK targets** resolve via `resolveTargetTable` (source schema, then `public`) and
  `SYSTEM_COLLECTION_TABLES` (system collection name → Flyway table, e.g. `users` →
  `platform_user`); a tenant collection named like a system one wins in its own schema. FKs are
  added `NOT VALID` then validated (warn only); the existence check is scoped to the table
  (`conrelid`).
- **Collection delete cascades** (V181/V182) and is guarded by `CollectionDeletionGuardHook`:
  rejected with counts unless `?force=true` (`kelta collections delete --force`); no-request-
  context callers can't force. S3 keys (`file_attachment`, `bulk_job`) are purged in
  `beforeDelete`. The physical table is dropped in `afterDelete` (never `CASCADE` — delete
  master-detail children first; a failed drop logs and leaves the table). External adapters'
  `dropCollection` is a no-op. `CollectionDeletionGuardSchemaTest` fails the build on any FK to
  `collection(id)` without an `ON DELETE` action.
- **Destructive schema-migration execute** (`MigrationExecutionService` +
  `SchemaMigrationEngine.migrateSchemaDestructive`) is the only path that `DROP COLUMN`s tenant
  data. Other pods refresh via NATS and then try to deprecate the already-dropped column with
  `COMMENT ON COLUMN`; that fails harmlessly after re-registration (one `StorageException` log per
  removed field). Make deprecate tolerant of a missing column as a follow-up. Security — not
  auto-merged.
- **runtime-core's jar-packaged `application.properties` becomes config for worker/ai/mcp/auth.**
  Never put operational settings there (it once forced JDBC DEBUG = 92% of all log volume); it
  still ships datasource/Hikari/Redis/actuator values each service must override.
- **Never give a signing key a default** — `VisitTokenService`, `CampaignProperties`, the bot
  challenge HMAC and `KELTA_MAILBOX_VERP_SECRET` fail startup when unset. A `log.warn` is not a control.
- **Exporters that read rows with `queryForList` must convert `PGobject`** before the value leaves
  the service (KLT-479); **any id stored inside a JSON config column** is a source-tenant id —
  export a natural key beside it and remap on import (KLT-480). Open gap:
  `MetadataPromotionService.parentKeys` doesn't list `GLOBAL_PICKLIST:<name>` as a parent of a
  bound FIELD, so a selective promotion of that field without its picklist fails loudly instead of
  pulling it in.

### Auth and Cerbos

- **Any new authorization-request validator must throw with a token whose redirect URI is null**
  for redirect-URI failures (#1636 — otherwise Spring AS redirects to the unregistered URI, an open
  redirect). Errors after the URI is validated may redirect to the registered URI.
- **Cerbos `collectionId` keying:** the `collection` policy is UUID-keyed (gateway passes the
  route id); the `record` policy keys on the collection **name** (`architecture.md`). Mixing them
  silently denies every record to non-admin profiles.
- **Every Cerbos decision cache key must carry `:geo:<country>`** wherever geo can influence a
  decision (gateway system/object caches, worker field-access + collection-wide record caches) —
  dropping it reintroduces cross-location stale allows. New caches too.
- Per-record checks short-circuit to one cached collection-wide check when a collection has no
  custom record rules (`RecordRuleIndex`); DENY is never cached. Deferred: attribute slimming for
  the remaining batch path and breaker tuning (timeouts vs connectivity errors).

### Frontend

- **`ui-pages` widget catalogue covers built-ins only.** `UiPageConfigHook` rejects any
  `components[].type` absent from the generated `page-widgets.json`; plugin and module-UI-bundle
  page components have no server-side declaration, so a page using one 400s on save. Latent (no
  such page exists). Fix: module manifests declare page component types merged into the catalogue;
  until then a widget whose pages must be saveable belongs in the built-in registry.
- **A bare `/{tenant}/login` auto-redirects** when there is one provider (every fresh tenant), so
  any e2e asserting the provider picker must pass `?logged_out=true` (or `authError`), and a
  multi-provider assertion must first give the tenant a second provider.
- **`kelta-ui/app` types are only checked by CI's `Type check kelta-ui` step** — the prod image
  runs bare `vite build` (no `tsc`). If that step is dropped, type drift resumes invisibly. Locally:
  run `npm run build` in `kelta-web` first (types resolve through built `dist/*.d.ts`; stale dist =
  phantom "has no exported member" errors); a `__vite-optional-peer-dep` stub in `dist/video.js`
  means `kelta-web` was built without `@livekit/components-react` installed — `npm install` and
  rebuild.
- **Hand-maintained narrow copies of shared types drift silently** — prefer `@/types/config`,
  `@kelta/sdk`, `hooks/useCollectionSchema`. `ResourceDetailPage.tsx` still forks
  `CollectionSchema`/`fetchCollectionSchema` from `hooks/useCollectionSchema.ts`; collapsing them
  is an open follow-up.
- **`useRecordMutation`'s `onSuccess` fires for every mutation** incl. inline-edit PATCHes —
  operation-specific side effects (navigate after delete, announcements) go in the specific
  handler via `mutateAsync().then(...)`.

### CI, deploy and test infrastructure

- **Frontend suite starves under concurrent image builds** (open). `Test Frontend` shares
  the runner with three GraalVM builds; `build-and-push` is gated on it, so a frontend flake
  **stops every image build** — a red frontend suite on main is a deploy outage. Mitigations:
  vitest `testTimeout`/`hookTimeout` 20 s, `asyncUtilTimeout` 10 s in `kelta-ui/app`, no
  per-keystroke typing, and no real-timer windows in assertions (resolve a test-owned deferred
  instead — `testing.md` → Component Testing). Still open: give the frontend job its own runner
  or serialize it against the native builds.
- **Post-merge image bump lives in two places:** this repo's `build-and-publish-containers.yml`
  deploy job is authoritative for `emf` images; the RZWare fleet (`rzware-ceo`, `agents/worker/`)
  runs its own overlay-discovery bump for other repos.
- **Marketing-site manifests are generated into homelab-argo.** `kelta-marketing/k8s/deployment.yaml`
  is copied to `homelab-argo/emf/kelta-marketing.yaml` on every main deploy — edits made directly in
  homelab-argo vanish. The manifest is written blind; the verified contract (pinned in
  `kelta-marketing/test/marketing-site.test.ts`): namespace `emf`, `emf-<service>` names, Traefik v3
  (no `ingressClassName: nginx`, no nginx annotations), **no cert-manager annotation** (Traefik
  serves the `*.kelta.io` wildcard; an issuer on `kelta-io-tls` could overwrite it). Verify against
  the cluster or `CustomDomainProvisioner`'s javadoc; don't guess.
- **Smoke-step timing rules:** keep the "Deployment exists" and "serves 200" waits on independent
  deadlines (a first ArgoCD sync can eat a shared budget, PLT-282); `rollout status` success
  doesn't mean every Service endpoint accepts connections — the marketing pod has a `preStop`
  `sleep 5`, and docs probes retry **only** `000` for ≤30 s (don't widen to other codes, PLT-477).

## Dependency Risks

- **Native images (worker, gateway, auth) — reflection.** Every class reflectively (de)serialized on
  a native image needs a `reflect-config.json` entry in the same PR, or it breaks only in
  production: gateway bootstrap DTOs (reachable from `BootstrapConfig`; a miss once took the whole
  API down — `RouteConfigService.refreshRoutes` now registers static routes independently);
  **every NATS event payload in both worker and gateway** (`EventPayloadReflectConfigTest`);
  `io.kelta.jsonapi` atomic-operation records; GeoLite2 models (`io.kelta.gateway.geo.model.*`);
  GraalJS host classes (`java.util.Locale`, `Locale$Category`, `TimeZone` — a
  `MissingReflectionRegistrationError` message prints the exact entry to add).
- **Native images freeze `@Conditional*` at build time.** Any property that gates a bean must be
  declared in `application.yml` as `${ENV_VAR:default}` — an env-var-only property silently loses
  the bean (all tracing was dead for months). Check
  `<service>/target/spring-aot/main/sources/**/…__BeanFactoryRegistrations.java` after
  `mvn spring-boot:process-aot`; `TracingExportPropertiesTest` guards the OTLP endpoint.
- **Build-time static initializers capture the build host's state, incl. `System.nanoTime()`.**
  Shaded Netty in grpc-netty-shaded was initialized at build time, so Netty time went negative on
  nodes that booted after the build node → every timer fired immediately → Cerbos
  `DEADLINE_EXCEEDED` → fail-closed 403 on every call (2026-10-05 outage). The gateway initializes
  both clock origins at run time; `NativeImageClockOriginTest` guards it. A library that ships
  `--initialize-at-build-time` for a whole package needs its clock/entropy/host-state statics
  checked; reproduce with `unshare --time --monotonic=<negative>`.
- **Every new `kelta.*` NATS subject namespace needs its own JetStream stream** in
  `JetStreamInitializer` (`ensureStream` is add-if-absent; existing streams can't gain subjects).
  Otherwise the publish never acks (`CancellationException`) and the event is dropped.
- **Changing a durable-consumer setting (maxDeliver, ackWait, deliverPolicy, …) is a
  rolling-upgrade hazard** (`SUB-90016 Existing consumer cannot be modified`).
  `NatsSubscriptionManager` now updates the durable in place (never delete/recreate — that drops the
  backlog). If flows stop after a deploy, grep worker logs for `SUB-90016` first.
- **GeoLite2 license key rides the download URL** — wrap any log/exception that could contain it in
  `GeoIpDatabaseManager.redact(...)`.
- **Homelab-bound builds.** `Dockerfile.jvm` files take `BASE_REGISTRY`/`MAVEN_MIRROR` (homelab
  defaults; `docker-compose.jvm.yml` overrides to Docker Hub + Central). Still homelab-bound: native
  `Dockerfile`s (`make up`, production) and `docker/bootstrap/Dockerfile` (`make seed`) — fix
  before relying on public contributors building native.
- **`make up` needs ~24 GB for Docker** (three concurrent `native-image` builds each size their heap
  to ~80% of the cgroup). Use `make up-jvm` unless debugging native-only behaviour.
- **`kelta-marketing` installs only because of an `overrides` pin** (`@astrojs/tailwind@6` peer vs
  Astro 6). Real fix: move to `@tailwindcss/vite` + CSS-first `@theme` (visual-regression risk).
  Don't remove the override until then.
- **pgvector:** `PhysicalTableStorageAdapter.initializeCollection` creates the `vector` extension
  lazily on the first VECTOR field; production Postgres must have the extension available (or
  pre-created) before any tenant defines one.
- Multiple BOM version overrides in `kelta-worker/pom.xml` raise transitive conflict risk — audit
  on every Spring Boot bump.

## Postgres max_connections sizing

Single standalone Postgres, default `max_connections = 100`. Per-pod Hikari max: worker 30, auth 15,
ai 15; Cerbos has its own pool.

| Service | Pool max | Typical replicas | Connections |
|---------|---------:|-----------------:|------------:|
| kelta-worker | 30 | 2-3 | 60-90 |
| kelta-auth   | 15 | 1-2 | 15-30 |
| kelta-ai     | 15 | 1-2 | 15-30 |
| cerbos       | ~10 | 1 | ~10 |
| Migrations PreSync (transient) | 10 | 1 | 10 |
| Headroom for psql / admin | | | 10 |
| **Required** | | | **120-180** |

Recommendations: raise `max_connections` to 200 (~2 GB shared memory) until PgBouncer fronts the
services (then the default suffices); long-term a dedicated/managed Postgres with 500+ past ~1k
tenants. Follow-up: alert on `numbackends / max_connections > 0.8` (needs `postgres_exporter`).

## Connection Pooler Compatibility

`TenantAwareDataSource` (runtime-core, registered by worker, auth and ai) scopes
`app.current_tenant_id` per transaction with `SET LOCAL`, so it survives PgBouncer
`pool_mode = transaction`; unbound connections keep the session `''` sentinel (`admin_bypass`).
Guards: `TenantAwareDataSourceTest`, `RowLevelSecurityIntegrationTest`, `TenantBindingIntegrationTest`.

**Open — compose healthchecks for kelta-worker and kelta-ai can never fail.** Both use
`show-details: always` and `wget … | grep -q UP`, which matches a healthy sub-component when the
overall status is DOWN. The gateway was fixed (exit code against `/actuator/health/readiness`). Not
applied to worker/ai because a strict check could block startup on an optional component — fix as
its own change with a full compose run.

## Test Coverage Gaps

- **OPEN — kelta-gateway integration tests never run in CI.** Its pom runs `*IntegrationTest` only
  via failsafe under `-Pintegration-tests`, which `test-java` doesn't activate, so its ~14 classes
  are never attempted and `assert-integration-tests-ran.sh` finds nothing to judge. Fix: run with
  `-Pintegration-tests` (expect some suites to need work). kelta-auth was fixed by PLT-343.
- **No worker Spring-context test** — bean-wiring bugs compile, pass unit tests and fail at pod
  start. **A `@Component` with a second (test) constructor needs `@Autowired` on the production
  one**; a second bean of an injected type needs `@Primary` or qualifiers. Copy
  `PushProviderWiringTest` (`ApplicationContextRunner` over the beans involved). A whole-worker
  context test is still owed.
- **Rate-limit tests are mocked** — nothing runs the Lua against real Redis or proves two replicas
  share a counter; the window guard is a text assertion on the script source. A Testcontainers-Redis
  harness scenario would close both.
- **Harness Cerbos is allow-all** — real deny paths (object policy, `/api/operations`, geo rules)
  are verified only by `CerbosGeneratedPolicyIT` against generated goldens or on a live stack.
- `SchemaMigrationEngine.migrateSchema` (non-destructive deprecate branch) is lightly covered; the
  destructive path has unit tests + `SchemaMigrationScenarioTest`.
- SQL filter operator mappings in `PhysicalTableStorageAdapter` have no dedicated tests.
- `FederatedUserMapper` — happy path only; group→profile permutations not covered.
- Password reset — unit-tested; no end-to-end DB → email → link → new password test.
- `CreateValidationRuleTool` (kelta-mcp) has no test; every MCP tool should assert its on-the-wire
  JSON:API body with WireMock JSON-path matchers.
- **Page-builder action runtime** (`runtime/executeAction.ts`) is covered only by Vitest with mocked
  `apiClient`/router/toast, except `createRecord`: `e2e-tests/tests/page-builder-v2.spec.ts` (runs in
  every PR's E2E job since the 2026-10 e2e-honesty batch) authors a page via the `ui-pages` API and
  asserts the button's record persists. `runFlow` polling and `assertSafeUrl` stay Vitest-only.
- **Rules from past false greens:** an assertion that a value was passed to a mock is not an
  assertion that the database accepts it (DB-constraint writes need a real-DB harness scenario); a
  suite that compiles and typechecks is not coverage until a job runs it under a runtime that can
  start it; YAML-valid is not Actions-valid (`actionlint` gates workflows); a fallback that reports
  success is worse than none.

## Resolved (archive)

One line each; full write-ups are in git history.

**Security**
- PLT-260 (2026-09-17) — direct `queryEngine` callers read system collections across tenants; scoping moved into `PhysicalTableStorageAdapter.query()`.
- PLT-354 (2026-10-05) — anonymous no-tenant reads returned several tenants' rows; now `WHERE FALSE` (`refusesAnonymousRead`).
- 2026-09-17 — gateway routes keyed by path alone let same-named collections shadow each other's authorization; now keyed by (path, tenant).
- 2026-09-16/17 (playbook §8, PLT-264, V200–V202, V205) — RLS was configured but bypassed by a `BYPASSRLS` app role; enforced in production, pinned direct-login roles, child tables covered, auth/ai bind tenants, Superset grants narrowed.
- PLT-423 (2026-10-07) — sandbox one-time admin password never worked (UPDATE ran under the parent's binding); now `callWithTenant(<sandbox>)` and 0 rows fails the create.
- 2026-09-14 — no dependency scanning ran at all; npm audit gate added (Java still open, above).
- #1472 — `protobufjs` RCE and `maplibre-gl` XSS criticals cleared.
- 2026-09-09 — `TenantContext.runAsPlatform`/`callAsPlatform` silently returned zero rows; removed.
- 2026-10-11 — `TenantContext.set`/`setSlug`/`clear` were silent no-ops under a bound `ScopedValue` and leaked on pooled threads; removed, callers use `run/callWithTenant[IfUnbound]`.
- 2026-10-11 (V208) — encrypted/hashed secret columns were serialized like ordinary attributes (ciphertext/hashes, permission-gated); `FieldDefinition.serverOnly` (above), setup-audit values scrubbed, unused `CredentialResponseMasker` deleted.
- 2026-07-05 — `POST /api/operations` skipped per-collection authorization; `checkCollectionAccess` added.
- PLT-330 (2026-10-04) — any token (and, via the public path, anyone) could list every tenant; gateway narrowing (#1603/#1604) + self-scoped storage predicate.
- PLT-331 (2026-10-04) — static routes for `users`/`profiles`/`collections` skipped the collection check; `COLLECTION_CHECKED_STATIC_ROUTES` + advice count correction.
- PLT-337 (2026-10-04, V204) — provisioned tenant admins shared the password `password`; claim by invite.
- PLT-343 (2026-10-05) — platform admin shipped with a well-known password; `BaselineAdminPasswordInitializer`.
- PLT-355 (2026-10-05) — client tenant headers could select a tenant; now a claim that must match the credential.
- #1636 (2026-10-06) — kelta-auth open redirect on rejected `redirect_uri`.
- KLT-377 (2026-10-06) — own-record CEL rules compared a UUID with the caller's email; PATs were always INTERNAL.
- KLT-380 (2026-10-10) — `user-ui-preferences` rows were tenant-readable; owner-scoped.
- KLT-206 — `GET /api/fields` returned every tenant's fields; `field.tenant_id` (V198) + `visibleToTenant`.
- 2026-09-16 (#1534/#1535) — `PageLayoutTreeService` resolved collections/layouts across tenants.
- 2026-07-12 — `RouteRegistry.matchesPath` prefix-shadowed hyphenated collections; segment-boundary check.
- Fail-fast HMAC secrets — visit and campaign signing keys defaulted to values published in this repo; now required.
- 2026-07-02 audit (power controllers on bare `API_ACCESS`) — data export gated on `VIEW_ALL_DATA`; bulk writes on `MANAGE_DATA` (2026-07-04); reports/dashboards on `VIEW_ANALYTICS` (2026-07-08); every `PackageController` endpoint on `CUSTOMIZE_APPLICATION` (V158).
- Older: Superset SQL injection hardening; gateway actuator requires JWT except health/info; `/internal/**` JWT auth (flag-gated); schema-name sanitization in `SchemaLifecycleModule`.

**Bugs**
- 2026-10-11 — portal magic-link sign-in never reached the end-user app: `PortalLoginController.verify` stored the tenant UUID in the session while `TenantContextFilter` compares the `/oauth2/authorize` redirect slug, so it read as a tenant switch and cleared the new session (back to the password form). Now stores the slug; found by un-gating `e2e-tests/tests/auth/portal-login.spec.ts`.
- `RouteRegistry.AUTHORITATIVE_STATIC_PATHS` — member-controller static routes were overwritten by their collection's generic route (portal 403s).
- V181/V182 + `CollectionDeletionGuardHook` — collections became undeletable once used; cascade, S3 purge, `?force=true` guard, physical table drop.
- e2e litter in a product tenant (2026-08-06) — cli-smoke refuses schema mutation on non-disposable tenants; `DataFactory` force-delete teardown; stuck collections cleaned 2026-08-08.
- Gateway rate limiter refreshed its window TTL per request → tenant-wide 429 lockout (the "storage-ready" e2e flake); also fixed `"default"` tenant id in delete hooks, field-quota hook, NATS `maxDeliver=5`.
- Cerbos per-record batch checks ran for collections with no record rules and could open the breaker; `RecordRuleIndex` short-circuit.
- Cerbos batch limit (50) silently emptied list pages over 50 rows; chunked checks.
- Cerbos ~27 ms per check from one rule per collection × action; constants-driven policy shape.
- Cerbos reseeded all tenants on every replica start; per-build done-marker.
- #1174 — record policy keyed by UUID but checked by name (non-admins saw no records).
- `unique` flag toggles never reached the schema; unique-violation 409s named the wrong field.
- kelta-mcp admin tooling batch (7 defects + kebab-case leftovers): wrong id extraction, rejected field types, picklist dialect, camelCase paths, inverted validation semantics, string JSON defaults, empty error envelopes.
- Kebab-case collection names broke generated identifiers (`identifierPart`).
- runtime-core's jar-packaged `application.properties` forced JDBC DEBUG on every consumer.
- `initializeCollection` aborted on reserved-word columns, system-collection FK targets and orphaned rows; FK existence check was global (25 missing FKs); FK targets used collection name as table name.
- FK constraint names could exceed/collide on long names → `buildBoundedIdentifier` (CRC32 suffix); tenant schema creation now verified via `information_schema` before `CREATE TABLE` (`initializeCollection`).
- BUG-2026-09-13-0001 — valid PATs 401'd during worker outages; `PatAuthenticationFilter` grace cache.
- #1579 — new collections 404'd after create from concurrent route-cache refreshes; `RouteRefresher`.
- KLT-205 — client-caused SQL errors surfaced as 500; SQLSTATE classification → 400 `INVALID_QUERY`.
- 2026-09-14 — dashboard time ranges 500'd (DATE/DATETIME bound as varchar); write-path conversion for filters.
- #1334 — gateway 404'd live tenants during slug-cache refresh; reactive lookups.
- 2026-07-12 — `/api/operations` 500 on the native worker (missing jsonapi reflect entries).
- 2026-10-07 — gateway served only static routes after starting before the worker; bootstrap retry; error bodies serialized empty (PLT-456).
- KLT-479 — metadata export wrote JSON columns as `PGobject` beans.
- KLT-480 — metadata apply kept the source tenant's global picklist id.
- PLT-266 / PLT-521 — `FieldEditor`/`CollectionForm` async-submit test races (sync zod resolver, test-owned deferred).
- homelab-argo #312 (2026-09-21, PLT-307/309) — kelta.io apex/www/downloads SERVFAIL'd from inside the cluster (CoreDNS `hosts … fallthrough` with no `forward`).
- PLT-278 / PLT-282 / PLT-477 — marketing manifest named nonexistent namespace/class/issuer; smoke deadlines shared; rollout dropped requests to a terminating pod.
- 2026-07-02 — gateway native image missing a bootstrap DTO reflect entry took the API down; 2026-07-11 — NATS payloads serialized `{}` on native (#1240); 2026-07-12 — GraalJS `new Date()` failed on native.
- 2026-08-10 (#1344) — tracing exported nothing on native images (env-var-only conditional property).
- 2026-10-05 — Netty build-time clock origin outage.
- 2026-10-06 — an object-store outage during JAR load quarantined every runtime module; quarantine was sticky (startup loads only `ACTIVE`), so modules stayed off ~5 days until re-enabled by hand. Load failures now classified by phase; transient ones retry with backoff instead of quarantining, and re-enable reloads on running pods. Lesson: transient infra errors must never be terminal.
- 2026-07-11 — chat/video subjects had no JetStream streams; 2026-07-12 — durable consumer config drift took down worker subscriptions.
- Older: federated users stuck PENDING_ACTIVATION; password reset sent no email; `EmptyResultDataAccessException` and null `reset_token_expires_at` in `PasswordController`.

**Tech debt / test infrastructure**
- 2026-10-12 — the stale `KELTA_BILLING` JetStream stream (empty, no consumers since compiled-in billing was removed) was deleted from the production NATS server by hand; nothing provisions or publishes to it.
- 2026-10-12 — module-platform slice 8 finished: V207/V209 dropped `billing_webhook_event` and the five V178 billing tables with their `billing-*` system collections (prod checked first: only `billing_plan` had rows, all already in the module's own plans collection).
- DB-design cleanup complete: permission sets removed (#1186), package authz export + config-health query fixed (#1187), orphan tables dropped (#1188), **Flyway history flattened to `V1__baseline` (#1189)**.
- Feature-flag audit: frontend `RENDER_TREE_V2` removed (#1137).
- 2026-10 dead-code batch A (#1718) — removed runtime-core `datapath`, `WorkflowRuleToFlowMigrator`, the no-op `evaluateBeforeSaveWorkflows`, `TriggerFlowActionHandler`'s `workflowRuleId` fallback (missing `flowId` now fails "flowId is required"), SDK `AdminClient.workflowRules`, `TenantCellResolver` (`tenant.cell_id` column kept), unreachable kelta-ui files, unused e2e page objects and npm deps; `FieldType.LOOKUP` lost a wrong `@Deprecated` (live type).
- Phase 0 — stale `spring-kafka` dependency removed; `KafkaRecordEventPublisher` renamed `NatsRecordEventPublisher`.
- Phase 0 — worker RLS tenant variable made transaction-scoped (PgBouncer-safe); auth/ai followed in PLT-264.
- Related-list mass edit ships via one bulk job (2026-07-04).
- `RowLevelSecurityIntegrationTest` and other Testcontainers ITs silently skipped in CI; moved to `k8s-runner-integration` + `assert-integration-tests-ran.sh`.
- 2026-09-15 — no workflow validation (a broken `ci.yml` ran no jobs at all); `actionlint` gate (shellcheck off on purpose, see `ci-cd.md`).
- 2026-09-09 — `kelta-ui/app`'s ~2,700 tests never ran (not invoked + Node 18); Node 20, `Test kelta-ui` step, `asyncUtilTimeout` 10 s.
- Runtime modules' ~1,900 tests never ran (`-DskipTests`); `test-runtime` job in both gates.
- V188 — Mockito audit tests passed against a table that rejected every row: credential-access audit rows were all rejected by a CHECK constraint while Mockito tests passed; `SetupAuditActionScenarioTest`.
- 2026-08-31 — a rejected module reported success via stub handlers; quarantine.
- `StripeApiClient`, `FcmPushProvider`, `ApnsPushProvider` unstartable (multi-constructor `@Component`); `PushProviderWiringTest`.
- Test-harness: runtime-core storage tests build H2 with `DATABASE_TO_LOWER=TRUE` to match Postgres identifier folding.
