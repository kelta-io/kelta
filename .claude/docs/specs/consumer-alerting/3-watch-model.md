# Slice 3 — Watch / Target Model

> Child of `specs/consumer-alerting/README.md`. Foundation for slice 4 (matcher) and slice 5
> (member surface). Backend-only.

> **Landed (V179).** Five tables, both system collections, five repositories, the
> `service/availability` scaffold, 21 unit tests and a harness scenario. Four notes against
> the design below:
> - **No refresh hooks were written.** §5 calls for `WatchTargetRefreshHook` /
>   `WatchRefreshHook`, but `SystemCollectionCacheInvalidationListener` derives its name set
>   from `SystemCollectionDefinitions.byName()` and consumes `kelta.record.changed.>`
>   broadcast — so simply registering the collections already gives fleet-wide cache eviction.
>   Dedicated hooks would have been two dead classes. Slice 4 should revisit only if its
>   matcher adds a target cache of its own.
> - **Episode minting moved into the upsert statement.** `AvailabilityStateRepository.record`
>   is one atomic `INSERT … ON CONFLICT DO UPDATE … RETURNING`, so a CLOSED→OPEN edge is
>   decided by the database. A read-then-write would let two pods processing the same event
>   both conclude "this just opened" and both alert.
> - **`alert_delivery` has no `tenant_id` and no RLS policy.** It is reachable only through
>   its alert, which is tenant-scoped and RLS'd, and the FK cascade ties their lifetimes.
>   A denormalized tenant column purely to hang a policy on would be a second source of truth
>   that could drift from the parent.
> - **`WatchCriteria` is versioned** (`v` key). The matcher pushes the date overlap into SQL,
>   so the stored shape is effectively part of a query plan: a silent shape change would not
>   error, it would just stop matching and members would quietly receive nothing. Dates are
>   `LocalDate`, not instants — "August 14–16" means those calendar days wherever the target
>   is, and converting to an instant would shift the window by a timezone offset.
>
> Verified against real Postgres before building on it: duplicate `(tenant, source,
> external_id)` rejected; same external id under a different source allowed; deleting a target
> with live watches blocked; alert dedupe tuple rejected; a **new episode for the same slot
> accepted** (the reopen case); deliveries cascade. Episode semantics tested directly —
> first sighting alerts, four repeat polls do not, close clears, reopen alerts again.

## 1. Goal & scope

Delivers: the watch/target data model as reusable platform substrate — `watch-targets` +
`watches` system collections (fixed shapes for the matcher's indexed SQL; JSON:API, RLS,
flows, audit for free) and the matcher-internal plain tables `availability_state`, `alert`,
`alert_delivery` + repositories. New worker package `io.kelta.worker.service.availability`
(mirrors `service/telehealth/`).

Does not deliver: matching/fanout logic (slice 4), member-facing API (slice 5), persistence of
raw availability observations (deliberately none — parent Key Decisions), reporting aggregates.

## 2. UI samples

N/A — data model. Admins reach `watch-targets`/`watches` through the standard
system-collection admin surface.

## 3. Data & API contracts

**`watch-targets`** (system collection, table `watch_target`, admin/flow-writable):
`source`, `external_id`, `name`, `category`, `metadata` JSONB (source extras — upstream ids,
grouping, optional geo), `active`. `UNIQUE (tenant_id, source, external_id)` — the poller's
`targetExternalId` resolves here.

**`watches`** (system collection, table `watch`, written via the slice-5 controller +
generic route owner-guarded by the `watches` ownership metadata): `member_id` (varchar(36), the portal user), `target_id` (FK →
`watch_target`), `criteria` JSONB (`{dateStart, dateEnd, quantity?, minDuration?}` — the
matcher pushes date-range overlap into SQL and evaluates the rest in Java), `channels` (JSONB
array, subset of the member's entitled channels), `status` CHECK
`ACTIVE|PAUSED|EXPIRED|FULFILLED`, `expires_at` (for pass-scoped watches), audit columns.
Matcher index: `(tenant_id, target_id, status)`; count index `(tenant_id, member_id, status)`
for the entitlement quota hook.

**`availability_state`** (plain table): `(tenant_id, target_id, slot_key)` PK, `status`
OPEN|CLOSED, `episode_id` uuid (minted on each CLOSED→OPEN transition), `window_start`,
`window_end`, `last_seen_at`, `last_change_at`.

**`alert`** (plain table): dedupe ledger — `UNIQUE (tenant_id, watch_id, slot_key,
episode_id)`; `created_at`; suppression-window queries index
`(tenant_id, watch_id, slot_key, created_at)`.

**`alert_delivery`** (plain table): `alert_id` FK CASCADE, `channel` push|email|sms, `status`
PENDING|SENT|FAILED, `sent_at`, `error`, index `(alert_id)`.

## 4. DB migrations

One migration (head+1 at implementation time — V178 is taken by slice 1 if it lands first;
always re-check the directory): `V<n>__watch_model.sql` — 5 tables, RLS (`tenant_isolation` +
`admin_bypass`) on all of them, the indexes above, FK `watch.target_id → watch_target(id)`
(NO ACTION — a target with live watches must not be deleted silently),
`alert_delivery.alert_id → alert(id) ON DELETE CASCADE`.

## 5. File-by-file code changes

- `SystemCollectionDefinitions.java` — `watchTargets()`, `watches()` factories + `all()`.
- `V<n>__watch_model.sql`.
- Worker `listener/{WatchTargetRefreshHook, WatchRefreshHook}` — collection-changed NATS
  broadcast (`ValidationRuleRefreshHook` recipe) + registration in `FlowConfig`.
- Worker `repository/{WatchTargetRepository, WatchRepository, AvailabilityStateRepository,
  AlertRepository, AlertDeliveryRepository}` — records + JdbcTemplate.
- Worker `service/availability/` package scaffold (model records: `AvailabilityEvent`,
  `WatchCriteria` parse helper).

## 6. Test plan

Unit: criteria JSONB parse/validation; repository SQL shape tests where non-trivial.
Harness: `WatchModelScenarioTest` — real DB: unique constraints
(`watch_target(tenant, source, external_id)`, the `alert` dedupe tuple) enforce; RLS isolates
two tenants' watches; the FK blocks deleting a target with live watches.

## 7. Docs to update

`status.md` (availability alerting row, 🟡 while slices 3–4 are in flight) ·
`architecture.md` if the package layout note helps · parent README slice table marked shipped.

## 8. Risks & open questions

`watch.criteria` must stay stable for the matcher's SQL pushdown — version the JSONB with a
`v` key if it evolves. Cerbos: portal profiles must not read these collections via generic
routes (enforced in slice 5; called out here so slice-3 review checks the default policy).
Target collisions across sources sharing ids are prevented by the `(source, external_id)` key.
