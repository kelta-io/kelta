# Slice 4 — Availability Matcher + Alert Fanout

> Child of `specs/consumer-alerting/README.md`. Depends on slice 3. This is the alert hot
> path — the latency budget is seconds from poller publish to push/email send.

> **Landed.** `KELTA_AVAILABILITY` stream, `AvailabilityEventListener` (queue group
> `worker-availability`), `AvailabilityMatchService`, `AlertDispatchService`, the trigger
> bridge, and 42 unit tests. Four notes against the design below:
> - **Criteria are evaluated in Java, not pushed into SQL.** §3 step 3 proposed pushing the
>   date-window overlap into the match query. It is not: `criteria` is opaque member-authored
>   JSONB, and welding a query plan to a shape members can change is how a later tweak
>   silently stops matching. The candidate set is already bounded by the indexed
>   `(tenant_id, target_id, status)` query, so the Java filter is cheap — revisit only if a
>   single target ever carries enough watches to matter.
> - **No composite partial index was added.** §4 offered one conditionally; the existing
>   slice-3 index covers the match query, and adding a speculative index for an unmeasured
>   plan is worse than not having one.
> - **Invalid criteria alert rather than drop.** A member who mis-saved criteria gets a
>   slightly-too-broad alert instead of silence — an over-alert is recoverable, never
>   alerting someone who is paying for alerts is not.
> - **The email template is not code-seeded.** `availability.alert` is tenant-overridable and
>   must exist before go-live; a missing template records a FAILED delivery rather than
>   throwing. Documented in `integrations.md` as tenant setup.
>
> Ordering that matters and should not be "simplified" later: sends happen **outside any
> transaction and after the alert row is committed**, because that row is the dedupe record —
> rolling it back on a provider timeout would re-alert the member on retry.

## 1. Goal & scope

Delivers: the `KELTA_AVAILABILITY` stream + `AvailabilityEventListener` (normalize →
transition detection with an episode model → indexed watch query → alert dedupe →
`AlertDispatchService` push/email fanout with delivery tracking) + a post-fanout republish to
`kelta.trigger.<tenantId>.availability` for tenant flows.

Does not deliver: the SMS channel (later — the dispatch service leaves a seam), quiet-hours
hold-and-release (v1 is per-watch channel prefs), the poller itself (outside this repo;
contract in the parent), reporting aggregates.

Conforms to parent: dedicated subject (the hot path is not on `kelta.trigger.>`), the
AvailabilityEvent shared contract, and the flows-off-the-hot-path rule.

## 2. UI samples

N/A — backend. Sample push payload: title `Now available: <target name>`, body
`<window> just opened.`, data `{watchId, targetId, slotKey, deepLink}`.

## 3. Data & API contracts

Consumes `kelta.availability.event.<tenantId>.<source>` (AvailabilityEvent, parent §Shared
contracts) on queue group `worker-availability` (exactly one pod per event; the
`NatsTriggerFlowListener` registration idiom in `NatsSubscriptionConfig`).

Pipeline per event:
1. **Resolve target** by `(tenant, source, targetExternalId)`; unknown target → debug-log +
   drop (a poller may cover targets no tenant has registered).
2. **Upsert `availability_state`**; compute the transition. CLOSED→OPEN mints a new
   `episode_id`. No transition → stop (the volume killer: most polls report no change).
3. **Match**: `SELECT … FROM watch WHERE tenant_id=? AND target_id=? AND status='ACTIVE'`
   plus date-window overlap pushed into SQL (`window_start <= criteria.dateEnd AND window_end
   >= criteria.dateStart` when the event carries a window); residual criteria (quantity,
   minDuration) evaluated in Java.
4. **Dedupe**: `INSERT INTO alert … ON CONFLICT (tenant_id, watch_id, slot_key, episode_id)
   DO NOTHING`; plus a suppression-window guard (`NOT EXISTS` same watch+slot within
   `kelta.availability.suppression-minutes`, default 30) against episode flapping.
5. **Fanout** (`AlertDispatchService`): for each surviving alert × watch channel ∩ the
   member's entitled channels (`EntitlementService.listLimit(…, "channels")`): create an
   `alert_delivery` PENDING row → send (push via `DefaultPushService.sendToUser`, email via
   `DefaultEmailService` with the tenant template `availability.alert`) → mark SENT or
   FAILED+error. Sends happen outside the DB transaction; a send failure never rolls back the
   alert row (no duplicate alert on retry).
6. **Bridge**: fire-and-forget republish of a compact summary `{targetId, targetName, slotKey,
   window, matchedWatches}` to `kelta.trigger.<tenantId>.availability` — tenant flows (digest,
   ticker, custom automations) hang off this, never off the hot path.

## 4. DB migrations

None beyond slice 3 (tables exist). If EXPLAIN shows the match query wants a composite partial
index (`watch (tenant_id, target_id) WHERE status='ACTIVE'`), add head+1.

## 5. File-by-file code changes

- `JetStreamInitializer.java` — `ensureStream("KELTA_AVAILABILITY", ["kelta.availability.>"],
  ~1h maxAge)` (its own stream — an existing stream's subjects cannot be extended).
- Worker `listener/AvailabilityEventListener.java` — NEW; registered in
  `NatsSubscriptionConfig` (queue group `worker-availability`).
- Worker `service/availability/{AvailabilityMatchService, AlertDispatchService}` — NEW.
- Payloads: the inbound body is poller-authored JSON parsed as `JsonNode`/record — if a
  `PlatformEvent` wrapper is used anywhere, its payload record needs reflect-config entries in
  worker AND gateway (native rule). The outbound trigger bridge publishes a plain Map (the
  presence precedent — no new reflect entries).
- Config: `kelta.availability.suppression-minutes`, `kelta.availability.enabled`.
- Email template `availability.alert` (tenant-overridable) — seed in code if templates are
  code-seeded, else document as tenant setup.

## 6. Test plan

Unit: transition/episode logic (CLOSED→OPEN mints, OPEN→OPEN no-op, flap inside the
suppression window suppressed); the criteria-overlap SQL builder; dispatch channel
intersection; send failure → delivery FAILED without alert rollback. Harness:
`AvailabilityMatchScenarioTest` — real DB + NATS: publish the same event twice → one alert
row; a second OPEN episode after CLOSED → a second alert; two members watching the same target
both alerted; RLS isolation. Live check (post-deploy): synthetic event → push/email received +
trigger republish observed; poison-message `maxDeliver` behavior sane.

## 7. Docs to update

Root `CLAUDE.md` messaging table (`kelta.availability.event.*`, the trigger bridge row) +
stream list · `integrations.md` availability/poller contract section · `status.md` alerting
row → working · `concerns.md` for any accepted trade-off (e.g. the suppression default).

## 8. Risks & open questions

Event storms (a source flapping across many targets) — the queue group + transition
short-circuit bound the work; stream maxAge bounds replay. Duplicate pollers double-publishing
— the dedupe tuple makes it harmless. Tenant email templates must exist before go-live
(tenant config task). Per-target watch fanout is unbounded — fine at launch scale; revisit
batching past ~10k watches on one target.

## 9. Addendum — delivery latency metric + summary endpoint (landed)

Generic, per-tenant visibility into how fast alerts reach members. Two surfaces, one definition
(`service/availability/AlertLatencySummary`):

- **Latency = `alert_delivery.sent_at - alert.created_at`**, clamped at zero (the two stamps come
  from the worker's and the database's clocks). `alert.created_at` is the detection time because
  `AlertRepository.claim` writes it with `NOW()` in the same matcher pass that detects the
  CLOSED→OPEN transition (`AvailabilityMatchService.claimAlerts`); `claim` now returns it via
  `RETURNING created_at` and it rides on `ClaimedAlert.createdAt`. Nothing else can serve:
  `availability_state.last_change_at` is overwritten in place on the next transition (no
  history), and the poller's `polledAt` is never persisted. **Poller → platform lag is not
  included.** No migration.
- **Metric** — `AlertDispatchService.dispatch` records the Micrometer timer
  `kelta_alert_delivery_latency_seconds` (tags `tenant` = tenant id, `channel` =
  `push|email|sms`; percentile histogram published so quantiles can be computed downstream)
  exactly once per delivery, right after `markSent`, using the same `sentAt` it persisted. FAILED
  deliveries record nothing. Exported through the existing OTLP/Prometheus pipeline.
- **Endpoint** — `GET /api/alerts/latency?since=<ISO-8601 instant>&channel=<push|email|sms>`
  (`AlertLatencyController`) → `{"data":{"count","p50Seconds","p90Seconds","since","channel"}}`.
  `since` defaults to now − 7d and filters on `alert.created_at >= since`; an unparseable `since`
  or unknown `channel` is 400. Without `channel` each alert is one sample at its **earliest SENT**
  delivery (a failed push followed by a sent email counts once, at the email); with `channel`
  every SENT delivery on that channel is a sample. PENDING/FAILED never contribute; no samples
  is `count: 0` with null percentiles. Percentiles are computed in Java with linear
  interpolation between closest ranks (same result as Postgres `percentile_cont`).
- **Scoping & permission** — read-only; the tenant is the request's bound `TenantContext`.
  `AlertDeliveryRepository.findSentSince` joins `alert_delivery` to `alert` and filters
  `alert.tenant_id = ?` explicitly — `alert_delivery` has no `tenant_id` of its own (its only RLS
  is the parent-alert policy from V202), so the predicate does not lean on RLS alone.
  `/api/alerts/**` is a `static-` gateway route (also in `RouteRegistry`'s authoritative static
  paths), so only `API_ACCESS` is checked there; the controller requires an INTERNAL caller whose
  profile grants `MANAGE_DATA` or the read-only `VIEW_ALL_DATA` (`SupportPermissions.READ`, the
  same read check as the watch/win support views) and denies
  every PORTAL actor with 403. There is no cross-tenant or write surface on `alert` /
  `alert_delivery`.
- **Tests** — `AlertDispatchServiceTest` (Latency metric), `AlertLatencySummaryTest`,
  `AlertLatencyControllerTest`, `AlertLatencyIntegrationTest` (Testcontainers, two tenants).
