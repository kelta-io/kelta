# Member Data Ownership — Owner-Scoped Collections + Member Self-Profile (Parent Spec)

> **Status:** parent planning spec, 2026-10-06. Defines two **generic** platform capabilities
> that let any tenant give its portal members (and, optionally, staff) access to *their own*
> records only — configured as collection metadata, never as per-collection code:
>
> 1. **Owner-scoped collections** — a collection declares an owner field and a scope mode; the
>    platform then filters every read and guards every write so a caller only sees and changes
>    rows they own.
> 2. **Member self-profile** — a caller may read and update a fixed, safe set of fields on
>    *their own* `users` record without `MANAGE_USERS`.
>
> Source-verified against the codebase on 2026-10-06 (Flyway directory head **V204** — next new
> migration is **V205**; deployed `flyway_schema_history` keeps pre-flatten numbering, so always
> re-check the directory and deployed history before numbering). Each slice below has its own
> child spec in this directory and **extends, never contradicts** the
> [Key decisions](#key-decisions), [Shared contracts](#shared-contracts) and
> [Security](#security) sections. If code and this doc disagree, trust the code and fix this doc.

## How to use this document

Read this parent first. Child specs each cover one PR-sized slice and follow the child-spec
template in `specs/app-surfacing/README.md` (sections 1–8; sections that don't apply say
"N/A — reason").

## Slice plan

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — Caller identity plumbing | `1-caller-identity.md` | **gateway + worker, security** |
| 2 — Owner-scoped collections | `2-owner-scoped-collections.md` | **worker + runtime-core + UI, security** |
| 3 — Member self-profile | `3-member-self-profile.md` | **worker, security** |
| 4 — Retire the bespoke owner-guard hooks | `4-retire-guard-hooks.md` | worker (refactor, security) |
| 5 — Tenant adoption guide (external sites) | `5-tenant-adoption.md` | docs + tenant metadata (no platform code) |

**Dependency order (hard edges): 1 → 2 → 4; 1 → 3.** Slice 5 depends on 2 and 3 being deployed.
Slices 2 and 3 are independent of each other. Every slice except 5 is **security-sensitive —
never auto-merged** (CLAUDE.md Rule 5).

## Context

Kelta's consumer-facing tenants (portal members on external sites — the
[consumer-alerting](../consumer-alerting/README.md) and [telehealth](../telehealth/README.md)
efforts) all need the same thing: *a member may only read and change their own rows.* The
platform has no generic way to say that. What exists today:

1. **Seven hand-written owner-guard hooks**, one per collection, all copies of one design
   (`kelta-worker/.../listener/`, registered as `@Bean`s in `config/FlowConfig.java`):

   | Hook | Collection | Owner field |
   |------|-----------|-------------|
   | `WatchGuardHook` | `watches` (system) | `memberId` |
   | `WinGuardHook` | `wins` (system) | `memberId` |
   | `UserPreferenceGuardHook` | `user-ui-preferences` (system) | `userId` |
   | `NoteGuardHook` | `notes` (system) | `createdBy` |
   | `FieldReportGuardHook` | `field-reports` (tenant collection) | `createdBy` |
   | `PhotoGuardHook` | `facility-photos` (tenant collection) | `createdBy` |
   | `CommentGuardHook` | `facility-comments` (tenant collection) | `createdBy` |

   They guard **writes only** (create: owner field must equal caller; update/delete: stored
   owner must equal caller), return **HTTP 400** (`BeforeSaveResult.error` →
   `ValidationException`), and apply to INTERNAL and PORTAL callers alike. The last three name
   collections that exist in one tenant — a tenant's data model encoded in platform code, which
   CLAUDE.md Rule 0 forbids.
2. **No read filtering.** A list query cannot be row-filtered per user. The only per-user
   narrowing is `CerbosRecordAuthorizationAdvice`, a `ResponseBodyAdvice` that drops records
   *after* the query (paging totals adjusted for the current page only), plus hand-written
   member controllers (`WatchController`, `WinController`, `PushDeviceController` — marked
   `SelfScopedController`). Aggregates, `/latest`, search, reports/dashboards/exports, includes
   and realtime are not filtered at all. `concerns.md` already records this as the deferred
   "row-level read policy — the shared v2 fix" (user-ui-preferences, approvals).
3. **The Cerbos "Restrict to own records" rule can never match.** The UI's CEL helper
   (`R.attr.createdBy == P.id`) and `$CURRENT_USER` (`CerbosPolicySyncService` →
   `attrRef + " == P.id"`) compare a UUID column with the principal id — which the worker
   builds as the **email** (`CerbosAuthorizationService`: `Principal.newInstance(email, "user")`).
4. **Caller identity is an email.** The gateway sets `X-User-Id` to the email
   (`HeaderTransformationFilter`); every hook re-resolves it to a UUID via `JdbcUserIdResolver`.
   `X-User-Type` comes from the token's `user_type` claim and defaults to INTERNAL when absent.
5. **No self-service on `users`.** `IdentityCollectionGuardHook` requires `MANAGE_USERS` (or
   `MODIFY_ALL_DATA`) for any write to `users`; `/api/me/identity` and `/api/me/permissions`
   are read-only; the delegated-admin path (`DelegatedUserAdminController`) explicitly rejects
   self-edit. The `users` definition is fixed (`SystemCollectionDefinitions.users()`): tenants
   cannot add fields to it.

**Consequences seen in production (2026-10-06):** an external member site could not let members manage
watchlists safely — granting the Portal User profile CRUD would expose every member's rows — so
it ships a backend-for-frontend that holds a service account and filters by owner in its own
code (the same workaround `specs/expat-clinic/README.md` §5.2 documents). Its settings page
writes `users` fields that don't exist (`displayName`, `region`, `pushTokens`, …) and a
member-facing key can't hold `MANAGE_USERS` safely.

**Intended outcome:** a tenant marks a collection "owned by `<field>`", grants its Portal User
profile ordinary CRUD on it, and members can then use the generic gateway routes with their own
token — the platform guarantees they only ever touch their own rows. Members can edit their own
name/locale/timezone through one `/api/me/profile` endpoint. The seven bespoke hooks become
metadata.

## Key decisions

1. **Ownership is collection metadata.** Three settings per collection: `ownerField` (a field
   holding a `users.id` UUID — a LOOKUP to `users`, or the system `createdBy`), `ownerScope`
   (who is scoped, below) and `ownerScopeReads` (default `true`; `false` = writes are
   owner-only but everyone with read permission still reads every row — e.g. public facility
   reports that only their author may edit):

   | `ownerScope` | Who is owner-scoped | Bypass |
   |---|---|---|
   | `NONE` (default) | nobody — today's behaviour | — |
   | `PORTAL` | callers with user type PORTAL | INTERNAL callers are unaffected |
   | `ALL` | every caller | profiles granting `VIEW_ALL_DATA` (reads) / `MODIFY_ALL_DATA` (writes) |

   Tenant collections store these as columns on `collection`; **system collections declare
   them in `SystemCollectionDefinitions`** (their DB rows are ignored — `buildDefinition` returns
   the canonical definition).
2. **One enforcement choke point for reads: the storage adapter** (skipped when
   `ownerScopeReads` is false). A request-scoped
   `CallerContext` (ScopedValue — virtual-thread safe, like `TenantContext`) carries the caller's
   UUID and user type. `PhysicalTableStorageAdapter` adds `AND <owner column> = ?` next to the
   existing `tenantScope(...)` predicate in `query`, `aggregate` **and** `getById`, so lists,
   counts/aggregates, `/latest`, includes, exports, reports and search inherit it. Filtering is
   in SQL, so paging totals are correct — unlike the response advice.
3. **One generic write guard.** A wildcard `OwnerScopeGuardHook` (order −100, runs where the
   seven hooks run today) enforces, for owner-scoped callers: create **stamps** the owner field
   with the caller (rejecting a different value), update/delete require the stored owner to be
   the caller, the owner field is immutable. It replaces the seven bespoke hooks (slice 4).
4. **Foreign rows answer 404, never 403/400.** Owner scoping must not reveal that a row exists
   (matches `SelfScopedController` and the member controllers). Reads return nothing; writes to
   a foreign or missing id return 404.
5. **Identity is a UUID, resolved once.** The worker binds `CallerContext` in one filter from
   `X-User-Id` (resolved via `UserIdResolver`) and `X-User-Type`. PAT requests get their
   `user_type` from the PAT owner's `platform_user.user_type`, so a portal user's PAT is PORTAL.
   The Cerbos principal gains `attr.userId` (UUID), and `$CURRENT_USER` compiles to
   `P.attr.userId` — fixing finding 3 without changing `P.id`.
6. **Self-profile is an endpoint, not a permission.** `GET/PATCH /api/me/profile` reads and
   updates the caller's own `users` row with a **fixed allow-list** (`firstName`, `lastName`,
   `locale`, `timezone`). It writes under a `SelfProfileWriteContext` scoped value that
   `IdentityCollectionGuardHook` honours only for *the caller's own id and allow-listed fields*.
   No permission to configure, nothing for a tenant to get wrong. Tenant-specific member data
   (a site's region, preferences, …) belongs in an **owner-scoped tenant collection**, not in
   `users`; push registration uses the existing `/api/devices`.

## Reuse map

| Need | Reuse | Do not |
|---|---|---|
| Request-scoped identity | `TenantContext` ScopedValue pattern; `TenantContextFilter` binding | add another ThreadLocal |
| email → UUID | `JdbcUserIdResolver` (per-tenant cache) | re-resolve in every hook |
| SQL scoping | `PhysicalTableStorageAdapter.tenantScope(...)` — "the single choke point" | filter in the router or the response advice |
| Collection-level setting | `capture_geo` precedent (V177, `CollectionDefinition`, builder, `CollectionLifecycleManager.buildDefinitionFromDb`, NATS `collection.changed`) | read settings from `collection` per request |
| Write guard | `BeforeSaveHook` wildcard registration (`IdentityCollectionGuardHook`, `MemberEntitlementQuotaHook`) | one hook per collection |
| Scoped write bypass | `DelegatedWriteContext` (ScopedValue) honoured by `IdentityCollectionGuardHook` | widen `MANAGE_USERS` |
| Member APIs | `SelfScopedController`, `SupportPermissions` (support mode), "404 not 403" | new per-collection controllers |
| Field allow-list | `DelegatedUserAdminController.UPDATE_FIELDS` + `rejectUnknownFields` | free-form PATCH on `users` |

## Shared contracts

### Collection metadata (slice 2)

```jsonc
// collections attributes (JSON:API) — new
"ownerField": "member",        // name of a field holding a users.id UUID, or "createdBy"; null = not owned
"ownerScope": "PORTAL",        // "NONE" | "PORTAL" | "ALL"; default "NONE"
"ownerScopeReads": true        // false = owner-only writes, unscoped reads; default true
```

Validation (collection save): `ownerScope != NONE` requires `ownerField`; the field must exist
and be `createdBy` or a LOOKUP whose target is `users`; it cannot be `ownerScope` on a
`readOnly` or tenant-shared system collection.

### Caller context (slice 1)

```java
record CallerContext(String userId /* UUID, null for internal tier */, UserType userType /* INTERNAL|PORTAL */,
                     boolean viewAll, boolean modifyAll) {}
CallerContext.current()  // Optional<CallerContext>; empty = internal tier (flows, NATS, schedulers) → no owner scoping
```

### Self-profile endpoint (slice 3)

```http
GET   /api/me/profile   → 200 {"data":{"type":"users","id":"<uuid>","attributes":{email,firstName,lastName,locale,timezone,userType}}}
PATCH /api/me/profile   body {"data":{"type":"users","attributes":{firstName?,lastName?,locale?,timezone?}}}
      → 200 same shape; 400 {errors:[{code:"FIELD_NOT_EDITABLE", source:{pointer:"/data/attributes/<f>"}}]} for any other field
```

## Security

- **Deny by default stays.** Owner scoping narrows access; it never grants it. A Portal User
  profile still needs object permissions on the collection; `ownerScope` then limits it to the
  caller's rows.
- **Internal tier is unscoped.** No `CallerContext` (flows, NATS listeners, schedulers, SCIM,
  provisioning) → no owner predicate, exactly as the hooks admit internal writes today. A flow
  acting for a member must therefore not expose results back to another member — unchanged
  from today.
- **No header trust.** `CallerContext` is built only from gateway-stamped headers
  (`IdentityHeaderStripFilter` strips client copies). A request with `X-User-Id` that resolves
  to no user in the tenant is **rejected** (fail closed), as the hooks' `CALLER_REJECTED` does.
- **Support mode.** INTERNAL callers with `VIEW_ALL_DATA` / `MODIFY_ALL_DATA` bypass `ALL`
  scoping; PORTAL callers never bypass. `WatchController`'s support mode (acting on another
  member with `MANAGE_DATA`) is reconciled in slice 4.
- **Owner stamping.** Create stamps the owner from `CallerContext`, including on
  `POST /api/operations` (`AtomicOperationExecutor` passes client `createdBy` through today).
- **Self-profile** can change only four low-risk fields, only on the caller's own row; never
  `email`, `status`, `profileId`, `userType`, `managerId`, `mfaEnabled`, `settings`.
- **Realtime** (`RealtimeBridge`) has no per-subscriber filtering today (concerns.md); owner-
  scoped collections must be excluded from realtime record payloads for owner-scoped
  subscribers (slice 2) — invalidation-only is acceptable.

## Findings recorded while specifying (2026-10-06)

Bugs this effort fixes or must account for:

1. `$CURRENT_USER` / "Restrict to own records" CEL rules compare UUID columns with an email
   `P.id` — they never match (slice 1).
2. The user type on PAT requests should come from the PAT owner's record (slice 1).
3. `WatchController` support mode (`MANAGE_DATA` acting on `memberId`) writes through
   `queryEngine`, where `WatchGuardHook` compares the stored owner with the *caller* — it would
   reject the write; the MVC test mocks `QueryEngine` so it never ran (unverified at runtime;
   slice 4 resolves it).
4. `AtomicOperationExecutor.executeAdd` does not stamp `createdBy` (slice 2 stamps owner
   fields on every create path).
5. `IdentityCollectionGuardHook.GUARDED` lacks `user-permission-sets`, which `FlowConfig`'s
   javadoc and `architecture.md` say it covers — reconcile in slice 3.

## Child-spec template

Per `specs/app-surfacing/README.md`: 1 Goal & scope · 2 UI samples · 3 Data & API contracts ·
4 DB migrations · 5 File-by-file code changes · 6 Test plan · 7 Docs to update · 8 Risks & open
questions.

## Docs to update (per CLAUDE.md Rule 6)

- `CLAUDE.md` Reference Docs → `specs/` list: this parent (slice 0).
- `architecture.md` → "Authorizing a new endpoint": owner scoping, `CallerContext`,
  `/api/me/profile` (slices 1–3).
- `status.md`: new rows as slices ship.
- `concerns.md`: close "row-level read policy (shared v2 fix)" for owner-scoped collections;
  record the residual realtime gap (slice 2); remove the bespoke-hook notes (slice 4).
- `kelta-worker/CLAUDE.md`: replace the "owner-guard hook" reference implementations with
  `OwnerScopeGuardHook` + metadata (slice 4).
- `playbooks.md`: "Make a collection member-owned" recipe (slice 2).
