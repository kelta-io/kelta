# Slice 8 — Analytics Capture

> Child of `specs/consumer-alerting/README.md`. Backend-only. The **capture half** of the
> analytics capability: durable, tenant-scoped storage of the questions a consumer product's
> users ask (search queries, assistant questions) plus lightweight acquisition/usage events,
> with age-based retention from day one. Derived rollups and dashboards (`target-stats`,
> `member-metrics`, embedded Superset) are a **later** slice — this one only lands the corpus.
>
> Source-verified against the codebase on 2026-08-08 (Flyway directory head **V182** — next new
> migration is **V183**). If code and this doc disagree, trust the code and fix this doc.

## 1. Goal & scope

**Delivers:** a high-volume `analytics-events` system collection (read-only over the generic
API — writes only through the capture paths below) with age-based pruning, and two capture
paths:

1. **Server-side search capture** — every `GET /api/_search?q=` records one `SEARCH_QUERY`
   event (verbatim query text, `zeroResult` flag, member + coarse geo), best-effort, never
   affecting the search response.
2. **Authenticated ingest** — `POST /api/analytics/events` accepts a client-emitted batch
   (page views, assistant questions, acquisition/UTM) from an authenticated member or staff
   session; tenant + member are stamped server-side from the auth context, never trusted from
   the body.

The corpus is the input to the demand-mining loop (parent §5.2) and the later Q&A/SEO page
generation (K10) and demand clustering (K12) — neither can start without it, which is why the
plan flags capture as *launch-needed* while dashboards are *month 3*.

**Does not deliver (deferred, called out so review does not flag them as gaps):**

- **Anonymous (unauthenticated) homepage ingest.** A public write surface belongs with the
  public read-surface / public-traffic slice (K9) — its per-IP budget and bot challenge are
  the prerequisites for accepting writes from strangers. Until then capture is authenticated +
  server-side only. The `analytics_event` shape already allows a null `member_id` so anonymous
  rows drop in later with no migration.
- **Rollups & dashboards** (`target-stats`, `member-metrics`, `query-clusters`, Superset) — a
  later slice reads this table; nothing here aggregates.
- **Cross-service assistant capture from kelta-ai.** kelta-ai can POST assistant questions to
  the ingest endpoint like any other client; a direct in-process hook in kelta-ai is a later,
  optional optimization, not this slice.

## 2. UI samples

N/A — backend capture. Staff reach `analytics-events` through the standard read-only
system-collection admin surface (same as `login-history` / `field-history`). No new admin page.

## 3. Data & API contracts

**`analytics-events`** (read-only system collection, table `analytics_event`, tenant-scoped,
RLS). Read-only over the generic API (`readOnlySystemBuilder`) — the generic route never
writes it; the capture paths do, via a dedicated repository. Columns:

| Field (JSON:API) | Column | Type | Notes |
|---|---|---|---|
| `eventType` | `event_type` | varchar(30), required | `SEARCH_QUERY` \| `ASSISTANT_QUESTION` \| `PAGE_VIEW` \| `WATCH_LIFECYCLE` \| `CUSTOM`. Open vocabulary; no DB CHECK (a new client kind must not need a migration). |
| `query` | `query` | text, nullable | Verbatim question/search text. Nullable for non-question events. |
| `zeroResult` | `zero_result` | bool, nullable | Search returned nothing — the highest-value signal (unmet demand). |
| `matchedTargetId` | `matched_target_id` | varchar(36), nullable | Best-effort resolved `watch_target` id when the query mapped to one. No FK — a target deleted later must not orphan-block or cascade-delete history. |
| `path` | `path` | varchar(500), nullable | Page path for `PAGE_VIEW`. |
| `referrer` | `referrer` | varchar(500), nullable | Acquisition referrer. |
| `utm` | `utm` | jsonb, default `{}` | `{source, medium, campaign, ...}` — the campaign-domain attribution (parent §2.2). |
| `sessionId` | `session_id` | varchar(64), nullable | Coarse session correlation; client-supplied opaque id, never a device fingerprint. |
| `memberId` | `member_id` | varchar(36), nullable | Portal/staff user; null for later anonymous rows. No FK (history outlives the user; deletion honored by the retention/erasure path, not a cascade). |
| `geoCountry` | `geo_country` | varchar(2), nullable | ISO country from the gateway geo header. **Coarse only** — no city, no precise coordinates (parent privacy stance + `status.md` geo row). |
| `geoRegion` | `geo_region` | varchar(80), nullable | Subdivision, coarse. |
| `metadata` | `metadata` | jsonb, default `{}` | Client extras; never required by any consumer. |
| `occurredAt` | `occurred_at` | timestamptz, default `NOW()` | Event time (client may supply for batched offline events; clamped to not-future server-side). |
| audit cols | — | — | Standard `created_at`/`updated_at` from `systemBuilder`. |

Indexes: `(tenant_id, event_type, occurred_at)` for the demand queries; `(tenant_id, occurred_at)` for the retention sweep's age scan.

**`POST /api/analytics/events`** (new static gateway route `/api/analytics/**` → worker
`AnalyticsIngestController`). Authenticated (`API_ACCESS`); tenant + `memberId` stamped from
the auth context — any `memberId`/`tenantId` in the body is ignored. Body:

```json
{ "events": [
  { "eventType": "ASSISTANT_QUESTION", "query": "earlier passport slot in Denver?",
    "sessionId": "s_abc", "utm": {"source":"campaign"}, "occurredAt": "2026-08-08T17:04:00Z" }
] }
```

Response `202 { "accepted": <n> }`. Batch cap **100** events/request (governor day-quota still
applies per tenant). Oversized batch → `400` JSON:API error. `query` clamped to 2000 chars,
`path`/`referrer` to their column widths (truncate, don't reject — telemetry is lossy by
nature). `occurredAt` in the future is clamped to `NOW()`.

## 4. DB migrations

`V183__analytics_events.sql` (re-check the directory head at implement time): one
`analytics_event` table, RLS (`tenant_isolation` + `admin_bypass`, the V77 system-table
pattern), the two indexes above. No FKs (see `matched_target_id`/`member_id` notes). Table is
expected to be the highest-volume system table after `record_version` — retention (§5) ships in
the same slice, per the parent's "apply the flow-log retention lessons from day one."

## 5. File-by-file code changes

- `SystemCollectionDefinitions.java` — `analyticsEvents()` factory (`readOnlySystemBuilder`,
  `tenantScoped(true)`) + `definitions.add(analyticsEvents())` in `all()`.
- `V183__analytics_events.sql`.
- Worker `repository/AnalyticsEventRepository.java` — `record AnalyticsEvent(...)` + batch
  `insert(...)`; hand-written `JdbcTemplate` under the request tenant context, explicit
  `tenant_id` param as defense-in-depth (the `FieldHistoryRepository` idiom). JSONB via
  `CAST(? AS jsonb)`.
- Worker `service/analytics/AnalyticsCaptureService.java` — `@Transactional`, **best-effort**:
  a single try/catch around the insert so a capture failure is logged at `debug` and never
  propagates into the search/ingest response (telemetry must never break the product). Clamps
  field widths, clamps future `occurredAt`, pulls coarse geo + member + tenant from
  `TenantContext` / request attributes.
- Worker `controller/AnalyticsIngestController.java` — `POST /api/analytics/events`; validates
  batch size, delegates to the capture service.
- Worker `controller/SearchController.java` — after building the response, one
  `captureService.captureSearch(q, resultCount == 0, matchedTargetId)` call (matched target
  resolution is best-effort/optional in v1: null unless the query exactly matches a target
  name — keep cheap).
- Gateway `RouteConfigService.registerStaticRoutes()` — add
  `{"analytics", "/api/analytics/**", "analytics"}`. Bump the hardcoded route-count assertion
  in `RouteConfigServiceTest` (memory: static-route count assertion exists).
- Worker `service/analytics/AnalyticsRetentionSweep.java` — `@Scheduled` hourly, **dry-run by
  default** (`kelta.analytics.retention.dry-run:true`), prunes `analytics_event` older than
  `kelta.analytics.retention.max-age-days` (default 90) with `FOR UPDATE SKIP LOCKED` + a
  per-cycle `batch-size × max-batches` cap, metric `kelta_worker_analytics_purged`. Directly
  mirrors `FlowLogRetentionSweep` — same posture, same knobs.

Cerbos: `analytics-events` is a read-only system collection carrying user-behavior data →
**deny portal profiles on the generic route** (same stance slice 5 applied to the watch/billing
collections). Staff read is gated by the collection's read permission; ingest is `API_ACCESS` +
owner-stamped, so a member can only ever write their own rows.

## 6. Test plan

- **Unit** — `AnalyticsCaptureServiceTest`: width clamping, future-`occurredAt` clamp,
  best-effort swallow (repository throws → no exception out, nothing returned to caller);
  batch-cap rejection in `AnalyticsIngestControllerTest`.
- **Unit** — `RouteConfigServiceTest` count bump; `SearchControllerTest` asserts a capture call
  fires with `zeroResult=true` on an empty result set and does **not** fail the search when the
  capture service throws.
- **Harness (real Postgres)** — `AnalyticsCaptureScenarioTest`: an insert under tenant A is
  invisible to tenant B (RLS, verified via a non-superuser probe role); a row older than the
  cutoff is pruned by the verbatim retention DELETE and a fresh row survives, with an idempotent
  second pass. Mockito worker tests cannot cover RLS or the retention DELETE — this is the
  required real-DB guard ([`testing.md`](../../testing.md#real-db-guard-for-constraint-bearing-writes)). The read-only-ness of the generic
  route is platform-wide behavior of `readOnlySystemBuilder` collections, already covered
  generically — not re-proven per collection here.

## 7. Docs to update (same PR)

- `status.md` — new row under the availability/consumer capabilities: analytics capture
  (backend capture + retention shipped; rollups/dashboards pending).
- `CLAUDE.md` — no new NATS subject (capture is a direct DB write, not an event). If a later
  rollup slice publishes, it updates the messaging table then.
- `architecture.md` — `/api/analytics/**` route + "authorizing a new endpoint" note (read-only
  collection + `API_ACCESS` ingest, portal deny).
- `concerns.md` — add `analytics_event` to the high-volume/retention watch list beside
  `record_version` and the flow logs.
- parent `README.md` slice table — mark slice 8 shipped.

## 8. Risks & open questions

- **Volume.** `analytics_event` is write-heavy. v1 mitigates with retention-from-day-one; if a
  single tenant's rate outgrows row-at-a-time inserts, batch the ingest writes and consider
  native partitioning by `occurred_at` month — deferred until a real rate justifies it (no
  speculative partitioning).
- **Privacy.** Coarse geo only; no fingerprinting; `member_id` deletion on account close must
  purge or anonymize these rows — wire that into the existing account-close/erasure path in the
  rollup/erasure slice, and note it here so it is not forgotten. Never log full event bodies.
- **Best-effort capture is deliberate.** A dropped analytics row is strictly better than a
  failed or slowed search. The capture service swallows everything; the only visibility into
  capture health is the (later) row-count dashboards and the sweep metric.
- **Anonymous ingest is the real demand firehose** (homepage strangers) and is intentionally
  out of scope until K9's public-traffic controls exist — do not open
  `/api/analytics/events` to `unauthenticated-paths` before then.
