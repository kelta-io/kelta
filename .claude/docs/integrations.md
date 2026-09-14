# External Integrations

## APIs & Services

| Service | Purpose | SDK/Version | Connection | Key Files |
|---------|---------|------------|------------|-----------|
| Anthropic Claude | AI assistant | `anthropic-java` 2.18.0 | API key | `kelta-ai/.../service/AnthropicService.java` |
| Cerbos | Authorization (gRPC) | `cerbos-sdk-java` 0.12.0 | `${CERBOS_HOST}:${CERBOS_GRPC_PORT:3593}` | `kelta-gateway/.../authz/cerbos/CerbosAuthorizationService.java`, `kelta-worker/.../service/CerbosAuthorizationService.java` |
| Svix | Webhook delivery | `com.svix:svix` 1.68.0 | `${SVIX_SERVER_URL}` | `kelta-worker/.../listener/SvixWebhookPublisher.java`, `.../service/SvixTenantService.java` |
| Superset | Embedded analytics | REST API | `${SUPERSET_URL}` | `kelta-worker/.../service/SupersetApiClient.java`, `kelta-ui/app/` (`@superset-ui/embedded-sdk`) |
| AWS S3 / Garage | Object storage | `aws-sdk-s3` 2.30.1 | `${KELTA_S3_ENDPOINT}` | `kelta-worker/.../service/S3StorageService.java` |
| Keycloak | OIDC federation | Spring Security OAuth2 | Port 8180 (docker-compose) | `kelta-auth/.../federation/FederatedUserMapper.java` |

### Attachment lifecycle (S3)

Files attach to any record via the `attachments` system collection (backed by `file_attachment`, `S3StorageService`). The full lifecycle:

1. **Upload** — `POST /api/attachments/upload-url` validates the file (type/size, per-tenant storage governor), creates a pending row, and returns a presigned S3 PUT URL. The client PUTs the bytes directly to S3, then `POST /api/attachments/{id}/finalize` verifies the object exists and records the storage key. (`AttachmentUploadController`)
2. **List / read / download** — `attachments` is routed by `DynamicCollectionRouter` like any collection (`GET /api/attachments?filter[collectionId][eq]=…&filter[recordId][eq]=…`, get-by-id, `?include=attachments`). `AttachmentUrlEnricher` (`@ControllerAdvice`, `@ConditionalOnBean(S3StorageService)`) injects a presigned `downloadUrl` for any `attachments` resource with a `storageKey`. `FileController` (`GET /api/files/**`) streams objects server-side with `Range` support.
3. **Delete** — `DELETE /api/attachments/{id}` (`AttachmentUploadController#deleteAttachment`) deletes the S3 object **and** the row. Its literal path beats the router's `/api/{collection}/{id}`, so the generic delete (which would orphan the S3 object) is bypassed. S3 deletion is best-effort and never blocks the metadata delete.
4. **Cascade cleanup** — `AttachmentCleanupHook` (wildcard `afterDelete`, order 200) removes a record's `file_attachment` rows + S3 objects when the **parent** record is deleted. Lookup uses `idx_attachment_tenant_record` (Flyway V142). The `attachments` collection itself is skipped (handled by the dedicated delete above). S3 cleanup is gated on `S3StorageService.isEnabled()`; row cleanup always runs.

## Data Storage

| Store | Purpose | Config Key | Details |
|-------|---------|-----------|---------|
| PostgreSQL 15 | Primary DB | `${SPRING_DATASOURCE_URL}` | Multi-tenant per-tenant schemas, Flyway migrations in `kelta-worker/.../db/migration/`. Dynamic user-collection tables are created by `PhysicalTableStorageAdapter.initializeCollection` which uses `CREATE TABLE IF NOT EXISTS` + `SchemaMigrationEngine.reconcileSchema` (introspects `information_schema.columns` and ALTERs to add missing ones) — this path must reconcile before issuing FK constraint statements so post-create `ADD CONSTRAINT FOREIGN KEY` references columns that exist. Because `CREATE TABLE IF NOT EXISTS` is **not atomic against concurrent CREATEs** in PG (two transactions can both pass the existence check and then both INSERT into `pg_type`, losing one with SQLSTATE 23505 on `pg_type_typname_nsp_index`), `initializeCollection` catches `DuplicateKeyException` from the CREATE step and falls through to `reconcileSchema`. This matters whenever the same NATS `CollectionChanged` event is delivered to multiple worker pods in parallel. CI: shared `kelta-ci-db` pool (schema-isolated per run, see `scripts/ci/README.md`); local: docker-compose or Testcontainers |
| Postgres full-text + audit | Record search, audit trails | — | Search is Postgres, **not** OpenSearch: `kelta-worker/.../service/SearchIndexService.java` maintains a `tsvector` column over `JdbcTemplate` (record indexing via `SearchIndexListener`); semantic search is `SemanticSearchService` over `pgvector`. `SecurityAuditLogger.java` emits structured SLF4J events that land in Loki. Setup/config audit: `AuditBeforeSaveHook` → `setup_audit_trail` (config collections only; user-data change history lives in `field_history`/`record_version`). **OpenSearch was removed from the cluster on 2026-08-10** (homelab-argo#205) — no service ever held an OpenSearch client dependency; it survived only as Jaeger's trace store. It remains in `docker-compose.yml` for local dev, where Jaeger still backs onto it |
| Redis 7 | Cache + sessions | `${REDIS_HOST}:${REDIS_PORT:6379}` | Route caching, permission caching, session management |
| Caffeine | Local in-memory cache | — | Hot-path caching alongside Redis |
| H2 | Test database | — | In-memory for unit tests |

## Messaging

**NATS 2.10** (`nats:2.10-alpine` with `--jetstream`)
- Server: `${NATS_URL:nats://localhost:4222}` (K8s: `nats.nats.svc.cluster.local:4222`)

| Subject | Purpose |
|---------|---------|
| `kelta.config.collection.changed` | Schema change events |
| `kelta.config.menu.changed.<tenantId>` | Nav menu/menu-item changed (apps/nav v2) — `MenuConfigEventPublisher` hooks on `ui-menus`+`ui-menu-items`; every worker pod evicts its `SystemCollectionCache` menu entries (`MenuCacheInvalidationListener`) |
| `kelta.config.translation.changed.<tenantId>` | Tenant UI translation changed (tenant i18n) — `TranslationConfigEventPublisher` hook on `ui-translations`; every worker pod evicts its `SystemCollectionCache` translation entries (`TranslationCacheInvalidationListener`) |
| `kelta.presence.<tenantId>` | gateway `PresenceService` (join/leave/heartbeat deltas, plain-Map payload) | every gateway pod merges the fleet-wide viewer set and pushes `presence.changed` snapshots to its local sockets (broadcast; KELTA_PRESENCE stream, 1-min retention) |
| `kelta.config.tenant.ip-allowlist.changed.<tenantId>` | Tenant IP allowlist changed → gateway refreshes its allowlist cache |
| `kelta.config.environment.changed.<tenantId>.<envId>` | Sandbox environment lifecycle (created/cloned/refreshed/archived) |
| `kelta.config.promotion.executed.<tenantId>.<promotionId>` | Metadata promotion executed/failed/rolled back |
| `kelta.worker.assignment.changed` | Worker assignment changes |
| `kelta.record.changed` | Record CRUD events (`RecordChangedPayload`; carries `containsMaskedFields` when the collection has data-masking config — the gateway `RealtimeBridge` then omits record `data` from WebSocket fan-out) |

Event envelope: `PlatformEvent<T>` with `eventId`, `eventType`, `tenantId`, `correlationId`, `timestamp`, `payload`
Publishing: `PlatformEventPublisher` (transport-agnostic interface; the NATS impl is `NatsEventPublisher` in `runtime-messaging-nats`. The former Kafka publisher was removed in Phase 0 — do not reintroduce Kafka.)
Subscriptions: `NatsSubscriptionConfig` registration. Delivery-mode rule: **broadcast** when the handler mutates per-pod state (caches, route registries, local WebSocket sessions — every gateway subscription qualifies, fixed 2026-07-08 after queue groups left other pods' realtime subscribers silent); **queue group** only for load-balanced work executed once per event (flows, search indexing)
Redelivery: consumers are created with `maxDeliver=5`; a failing handler NAKs with a 2s delay (`NatsSubscriptionManager.nakBounded`), and after the fifth delivery JetStream stops redelivering (logged at ERROR as a dropped poison message). Handlers must stay idempotent across ≤5 deliveries; anything that must never be lost needs its own persistence, not infinite redelivery.
Location: `kelta-platform/runtime/runtime-events/src/main/java/io/kelta/runtime/event/`

## Monitoring & Observability

**Production (Kubernetes — Grafana LGTM stack in `observability` namespace):**

| Tool | Purpose | K8s Address |
|------|---------|-------------|
| Grafana | Dashboards & visualization | `grafana.observability.svc.cluster.local` |
| Tempo | Distributed tracing | `tempo.observability.svc.cluster.local:3200` |
| Loki | Log aggregation | `loki.observability.svc.cluster.local:3100` |
| Mimir | Metrics storage | `mimir.observability.svc.cluster.local:8080` |
| Alloy | OTLP collector | Routes telemetry from services to LGTM backends |

**Local Development (docker-compose `observability` profile):**

| Tool | Purpose | Port |
|------|---------|------|
| Jaeger 2 | Trace UI + OTLP receiver | 16686 (UI), 4317 (gRPC), 4318 (HTTP) |
| OpenSearch | Trace/log/metric storage | 9200 |

**Instrumentation:**

| Component | Details |
|-----------|---------|
| OpenTelemetry Java Agent | v2.25.0, bundled in all service Dockerfiles |
| Spring Boot OpenTelemetry Starter | Auto-instrumentation for traces, metrics |
| Logstash Logback Encoder | v8.0, JSON structured logging |
| OTLP export | HTTP to port 4318 (configurable via `MANAGEMENT_OTLP_METRICS_EXPORT_URL`) |
| Sampling | W3C propagation, 100% by default (configurable via `OTEL_TRACES_SAMPLER_ARG`) |
| `service.instance.id` | `management.opentelemetry.resource-attributes.service.instance.id: ${HOSTNAME:local}` in every service's `application.yml` |

**Every replica must carry a distinct `service.instance.id`.** `service.name` alone is identical
across replicas, so without an instance id the OTLP resource is identical too and Mimir stores
every replica's counters as one series — `rate()` then treats each replica switch as a counter
reset and adds the whole counter value back, inflating request-rate panels (this produced a
phantom ~100 req/s "Requests per Tenant" reading from three `kelta-worker` replicas on
2026-09-14, resetting on every rollout). `${HOSTNAME}` is the pod name in Kubernetes — unique per
replica — and still resolves outside it (container/host id), so the default needs no
deployment-specific override.

## Local Development (docker-compose.yml)

| Service | Port | Profile |
|---------|------|---------|
| PostgreSQL | 5432 | default |
| NATS | 4222 | default |
| Redis | 6379 | default |
| Keycloak | 8180 | default |
| Cerbos | 3592/3593 | default |
| Mailpit (SMTP / UI) | 1025 / 8025 | default |
| Jaeger | 16686 | default |
| OpenSearch | 9200 | default |
| NATS Box | 8090 | tools |
| Redis Commander | 8091 | tools |

## Frontend workspace build (`kelta-web` → `kelta-ui/app`)

`kelta-ui/app` consumes the four `@kelta/*` packages in `kelta-web/packages/*` as `file:` deps — `npm install` symlinks them but does **not** build them, so the `dist/` outputs that `package.json`'s `main`/`module`/`types` point at must exist before `tsc -b` can resolve any of them (otherwise: 46 `TS2307` "Cannot find module" errors).

Build order matters because `plugin-sdk` and `components` use `vite-plugin-dts` with `rollupTypes: true`, which resolves re-exported symbols (e.g. `KeltaClient`) from `@kelta/sdk`'s rolled-up `.d.ts` — that file must already exist. `kelta-web`'s root `build` script and `kelta-ui/Dockerfile` both run:

1. `formula` + `sdk` (no internal deps)
2. `plugin-sdk` + `components` (depend on `formula`/`sdk`)

Local dev: `cd kelta-web && npm install && npm run build` once before `cd kelta-ui/app && npm install && npm run build`.

## Flows

### Initial state shape

Every flow execution starts with a canonical state envelope, built by
`InitialStateBuilder` (`kelta-platform/runtime/runtime-core/.../flow/InitialStateBuilder.java`):

```jsonc
{
  "trigger": { "type": "API_INVOCATION" | "SCHEDULED" | "RECORD_CHANGE" | "WEBHOOK" | "NATS_MESSAGE", ... },
  "input":   { ...source data... },   // present for API_INVOCATION / SCHEDULED / WEBHOOK / NATS_MESSAGE
  "record":  { ...record data... },   // present for RECORD_CHANGE only
  "context": { "tenantId": "...", "flowId": "...", "executionId": "...", "userId": "..." }
}
```

JSONPath expressions inside a flow definition (`inputPath`, `outputPath`, condition
expressions) are evaluated against this envelope. **Always read inputs as `$.input.<key>`**
— never `$.<key>`. Reading the bare key skips the envelope, JSONPath silently returns
no value, and the downstream task fails with whatever generic error comes out of its
own input resolution (often a misleading `"Provider … does not exist"` for FETCH-style
tasks, not a clear "missing input" message).

### Execution actor & audit stamping (2026-07-18)

Every flow start resolves an **actor** — `FlowActorResolver` (worker):
**initiating user** (manual runs; emails from auth-code JWTs are translated via
`JdbcUserIdResolver`) → **flow `runAsUserId`** (`flow.run_as_user_id`, V175;
"Run as user" in the designer's trigger sheet, `runAsUserId` on the MCP
create/update_flow tools) → **flow owner** (`created_by`). The actor is passed
to `FlowEngine.startExecution` as the execution user, so:

- `CreateRecordActionHandler` stamps `createdBy`/`updatedBy` and
  `UpdateRecordActionHandler` stamps `updatedBy` on records the flow writes
  (`FlowAuditStamp` — UUID-shaped actors only, explicit field mappings win).
  Before this, all flow-written records had null audit users.
- `flow_execution.started_by` records the actor. The webhook path previously
  stored the literal `"webhook"`; it now stores the resolved actor UUID
  (provenance stays visible via the state envelope's `trigger.type`).
- `ActionContext.userId` is now populated for cron/NATS/webhook runs
  (previously null) — SendNotification's default recipient and
  `INVOKE_SCRIPT`'s `userId` see the actor.

### NATS-triggered flows

A flow with `flow_type = 'NATS_TRIGGERED'` (V153) and trigger config `{ "topic": "<topic>" }`
starts whenever a message is published to `kelta.trigger.<tenantId>.<topic>` (the
KELTA_TRIGGERS JetStream stream; topics may contain dots — the topic is everything after
the tenant token). `NatsTriggerFlowListener` consumes the namespace as a queue group
(one pod starts each execution), caches active configs per tenant (invalidated by
`kelta.config.flow.changed.<tenantId>`), and passes the message body as `$.input`
(`trigger.type = "NATS_MESSAGE"`, with `subject` + `topic`; a non-JSON body arrives as
`{ "raw": "<text>" }`). The body is arbitrary publisher JSON — not a `PlatformEvent`.

One platform publisher does use the namespace: the LiveKit webhook bridges every
video-session lifecycle event onto `kelta.trigger.<tenantId>.video.session` (same
`PlatformEvent<VideoSessionPayload>` envelope as `kelta.video.session.*`), so a
NATS_TRIGGERED flow with trigger topic `video.session` reads
`$.input.payload.{sessionId,appointmentId,conversationId,status,durationSeconds}`.

### Wait states — persistence and resume

A Wait with `Seconds` ≤ 10 sleeps in-process. Longer, timestamp-based
(`Timestamp`/`TimestampPath`), and event-based (`EventName`) Waits park the execution
WAITING and record a `flow_pending_resume` row (wake time for timed waits, event name for
event waits). The worker's `FlowResumePollerConfig` (10s default,
`kelta.flow.resume.poll-interval-ms`; disable with `kelta.flow.resume.enabled=false`)
claims due rows with `SELECT FOR UPDATE SKIP LOCKED` — exactly one pod resumes an
execution — and `FlowEngine.resumeExecution` re-loads the flow definition and continues
from the Wait's `Next` (a terminal Wait completes). Event-based waits resume through
`claimPendingResumeByEvent` when an event source calls it. Waits inside Parallel/Map
branch sub-definitions are **not** resumable (their state ids aren't in the top-level
definition) — the resume fails the execution with a clear error instead of hanging.

### Manual / MCP / HTTP invocation — the double-wrap rule

`POST /api/flows/{flowId}/execute` (in `FlowExecutionController.executeFlow`) reads its
input from `body.input` and passes it through to `buildFromApiInvocation`, which then
puts it under `state.input`. So the request body itself must already be wrapped under
`input` — the controller does **not** treat the whole body as input.

That means callers double-wrap: the outer wrap is the HTTP request shape, the inner
wrap is the value that ends up at `$.input`.

HTTP:
```bash
curl -X POST $GW/api/flows/$FLOW_ID/execute \
  -H 'Content-Type: application/json' \
  -d '{ "input": { "slug": "acme" } }'
# ⇒ state.input == { "slug": "acme" }, flow reads $.input.slug
```

MCP (`execute_flow` tool — the MCP layer passes the tool's `input` argument straight
through as the HTTP body, so the same double-wrap applies):
```jsonc
execute_flow({
  "flowId": "flow-123",
  "input": { "input": { "slug": "acme" } }   // double wrap: outer = HTTP body, inner = $.input
})
```

A single wrap (`input: { "slug": "acme" }`) sends `{ "slug": "acme" }` as the body,
the controller looks for `body.input` (absent), `$.input` becomes `{}`, and every
`$.input.slug` read returns no value.

Escape hatches in the controller:
- `body.state` — if present, the controller treats it as a pre-built initial state envelope (only `context` is auto-filled). Use this when you need to seed `record`/`headers` keys that `buildFromApiInvocation` doesn't produce.
- `body.test: true` — runs the flow in test mode (`isTest=true`); does not change how `input` is resolved.

### Page-event → flow framing (page builder, slice 2e)

A page-builder `runFlow` action (a button `onClick`, etc.) rides the **same**
`POST /api/flows/{flowId}/execute` double-wrap rule. The editor stores the **inner** input
map on `action.input` (e.g. `{ orderId: {$bind:'record.id'} }`); the client-side action runtime
(`kelta-ui/app/src/pages/PageBuilderPage/runtime/executeAction.ts`) resolves the `{$bind}` values
against the live click scope and sends `{ input: <resolved action.input> }` as the HTTP body — i.e.
it adds the **outer** wrap. So the flow reads `$.input.<key>` exactly as above; the author never
wraps twice. The execute response is async (`200` with `attributes.status:"RUNNING"`); the execution
id is `data.id` (read via `unwrapResource(json).id`). With `awaitResult:true` the runtime polls
`GET /api/flows/executions/{id}` every 1.5 s (up to 60 s) until a terminal status
(`COMPLETED`/`FAILED`/`CANCELLED`) — `FAILED`/`CANCELLED` reject and stop the action chain. This is
the only new flow framing 2e introduces (no new endpoint or subject). No backend change.

### Scheduled (cron) invocation — `triggerConfig.inputData`

For SCHEDULED flows there is no per-run HTTP body, so the static payload lives on the
flow definition itself. `ScheduledJobExecutorService` reads the flow's `trigger_config`
column and hands it to `InitialStateBuilder.buildFromSchedule`, which copies
`triggerConfig.inputData` into `state.input`:

```jsonc
// flow.trigger_config (stored on the flows row)
{
  "type":    "SCHEDULED",
  "cron":    "0 */15 * * * *",
  "timezone":"UTC",
  "inputData": { "slug": "acme", "mode": "full" }
}
// ⇒ state.input == { "slug": "acme", "mode": "full" }, flow reads $.input.slug
```

This is the SCHEDULED-equivalent of the inner wrap in `execute_flow`: the same flow
definition can be driven by cron or by `execute_flow` as long as the static
`triggerConfig.inputData` and the caller-supplied `body.input` produce the same
shape under `$.input`.

## Availability pollers (consumer-alerting slices 3–4)

An external poller watches a source and reports what it sees; the platform decides what is
worth alerting on. Pollers live **outside this repository** — the contract below is their
whole interface.

**Publish** to `kelta.availability.event.<tenantId>.<source>` (KELTA_AVAILABILITY stream, 1h
retention). The body is **plain poller-authored JSON**, not a `PlatformEvent` — a poller
should not have to construct a platform envelope, and keeping records off this subject means
no native reflect-config entries:

```json
{
  "source": "source-key", "targetExternalId": "12345", "slotKey": "2026-08-14",
  "status": "OPEN", "window": {"start": "2026-08-14T00:00:00Z", "end": "2026-08-16T00:00:00Z"},
  "quantity": 1, "meta": {}, "polledAt": "2026-08-02T17:31:04Z"
}
```

`source` may be omitted — it falls back to the subject segment. `window`, `quantity`, `meta`
and `polledAt` are optional: "this slot opened" is actionable without them. `targetExternalId`
resolves against `watch_target (tenant_id, source, external_id)`; an unregistered target is
dropped quietly, because a poller may legitimately cover more than any one tenant registers.

**Report what you see, not what changed.** Re-publishing the same OPEN slot every minute is
harmless and expected — the platform derives the transition against `availability_state` and
short-circuits when nothing changed. Pollers stay dumb and replaceable; deduplication is not
their problem.

**Alerting is once per opening.** A fresh `episode_id` is minted on each CLOSED→OPEN edge, and
`alert` is unique on `(tenant, watch, slot, episode)`. A slot open across a hundred polls
alerts once; a genuine close-then-reopen alerts again. A suppression window
(`kelta.availability.suppression-minutes`, default 30) additionally guards against a slot
flapping. Duplicate pollers double-publishing are therefore harmless.

**Consumed** on the queue group `worker-availability` — exactly one pod handles each
observation. This subject is deliberately **not** under `kelta.trigger.>`: it is the alert hot
path with a seconds-level budget and must not queue behind tenant flows.

**Flows react afterwards.** Once members are notified, a compact summary
(`{targetId, targetName, source, slotKey, windowStart, windowEnd, matchedWatches}` — ids and
counts, never member identities) is republished to `kelta.trigger.<tenantId>.availability`, so
NATS_TRIGGERED flows with topic `availability` fire for digests, tickers and custom
automations. Flows read `$.input.payload.*`.

**Tenant setup before go-live:** create `watch-targets` rows for whatever the poller covers,
and an `availability.alert` email template (tenant-overridable) — without it the email channel
records a FAILED delivery.

## Stripe (portal billing — consumer-alerting slice 1)

A tenant sells plans to its **PORTAL** members. Stripe owns money, tax, invoicing, and
dunning; the platform mirrors ids and coarse state and resolves entitlements from them.
Nothing here computes an amount.

**No `stripe-java`.** Its large Gson-reflective model surface is a native-image hazard and
the platform needs only a handful of calls, so outbound calls use a plain REST client
(form-encoded, `JsonNode` responses, pinned `Stripe-Version`, `Idempotency-Key` on POSTs).

**Credential.** Type key `stripe`, resolved by the name `stripe` via `CredentialResolver`.
Secrets `secretKey` (outbound auth) and `webhookSecret` (inbound verification); metadata
`publishableKey` and `allowedReturnOrigins` (bounds where a checkout/portal return URL may
point — without it a caller could redirect a paying member to an attacker-controlled page).
Test connection calls `GET /v1/account`; the webhook secret can only be format-checked,
since verifying it needs a real delivery.

**Webhook.** `POST /api/modules/webhooks/{tenantId}/kelta-billing` — a gateway
`unauthenticated-paths` entry. The compiled-in `/api/billing/webhooks/stripe/{tenantId}` route was
deleted once the module served this one; the verification below is now the **module's**, not the
platform's, and the platform verifies nothing on this path. The `Stripe-Signature` HMAC over the **raw** body is the only
trust anchor, so the body must be verified before it is parsed: re-serializing JSON changes
the bytes and invalidates a genuine signature. Verification is constant-time, tolerates 5
minutes of clock drift in either direction (an unbounded window leaves a captured request
replayable forever), and accepts any of several `v1` signatures so a secret can be rotated
without dropping deliveries. The `{tenantId}` in the path is **untrusted** — it selects which
tenant's `webhookSecret` to verify against, and a passing HMAC is what proves the event
belongs to that tenant. An unknown tenant and a bad signature both answer a bare 401, so an
unauthenticated caller cannot enumerate which tenants have billing configured.

Idempotency is a claim row in `billing_webhook_event` keyed by the processor's event id, and
**the claim shares a transaction with the mutation** — if processing throws, the claim rolls
back with it and the retry genuinely re-runs. Answers 200 for duplicates and for event types
the platform ignores, so the processor stops retrying them.

| Event | Action |
|---|---|
| `checkout.session.completed` (mode=payment) | upsert customer; grant a pass, idempotent on the session id |
| `checkout.session.completed` (mode=subscription) | upsert customer only — the subscription arrives on its own event |
| `customer.subscription.created` / `.updated` | upsert `billing_subscription`; plan resolved via the price id |
| `customer.subscription.deleted` | mark canceled — the member drops to the DEFAULT plan on next resolve |
| `invoice.paid` / `invoice.payment_failed` | flow-trigger bridge only |
| anything else | claimed, then ignored |

**Member resolution** for an event, in order: `client_reference_id` → `metadata.userId`
(stamped on both the session and `subscription_data`) → the stored `billing_customer`
mapping. An event with no resolvable member is skipped rather than guessed at.

**Entitlements.** `billing_plan.entitlements` is an opaque tenant-authored JSON object. The
base plan is the member's subscription plan while its status is `active`, `trialing`, or
`past_due` (grace while the card is retried), otherwise the tenant's DEFAULT plan; every live
pass then merges on top — numbers SUM, booleans OR, arrays union — so a pass can add a
capability but never revoke one. Pass expiry is judged when entitlements are read, not by the
sweep, so a member is never over-entitled by a sweep that has not run yet.

**Events out.** `kelta.billing.entitlement.changed.<tenantId>.<userId>` (KELTA_BILLING
stream, broadcast) invalidates the entitlement cache on every pod; a payload with no
`userId` means the whole tenant, which is what a plan edit publishes. Subscription changes
are additionally bridged onto `kelta.trigger.<tenantId>.billing.subscription`, so tenants
build welcome / dunning-nudge / win-back automations as flow configuration — flows read
`$.input.payload.*`.

**Outbound calls** (`StripeApiClient`): create customer, create checkout session, create
billing-portal session. Idempotency keys are chosen per operation, and the distinction
matters: customer creation uses a **deterministic** key (`cust:<tenantId>:<userId>`) because a
member gets exactly one processor customer ever and a racing retry must replay rather than
create a duplicate that would violate `billing_customer`'s unique constraint. Checkout and
portal sessions use a **per-attempt** key — a deterministic one would replay the previous
session for the processor's 24-hour idempotency window, which would stop a member buying a
second one-time pass of the same plan on the same day.

**Member endpoints.** Served by the module under `/api/modules/kelta-billing/x`:
`POST .../checkout-sessions {planCode, successUrl, cancelUrl}` → `{url}`;
`POST .../portal-sessions {returnUrl}` → `{url}` (refused when the member has never transacted —
there is nothing to manage); `GET .../me`; `GET .../plans`. The compiled-in `/api/billing/*`
controller was deleted; response shapes are byte-identical and pinned by a contract test.
Return URLs must be absolute HTTPS whose **origin** exactly matches an entry in the
credential's `allowedReturnOrigins` — origin equality, not prefix, because a prefix check also
accepts `https://app.example.com.evil.test`. An empty allowlist denies everything, so a tenant
that has not configured origins cannot redirect anywhere rather than anywhere at all.

**Quota enforcement.** `billing-entitlement-rules` rows say "records in collection X are capped
by entitlement key Y", and a wildcard before-save hook enforces them, so capping a new
collection is configuration rather than code. A rule's optional `countFilter` narrows what
counts (e.g. only ACTIVE rows); its field names are validated against the collection definition
and its values bound as parameters. At the limit the create is rejected with **HTTP 400**
(JSON:API code `beforeSaveHook`) carrying an upgrade-oriented message. Enforcement **fails
open** — a billing glitch must not block a tenant's data entry.

**Dashboard prerequisites** (nothing here provisions them): Products and Prices, with each
price id recorded on a `billing-plans` row; Stripe Tax if the tenant charges tax; the Billing
Portal configured; retry/dunning settings; and a webhook endpoint pointed at the URL above
with its signing secret stored in the credential. Local testing:
`stripe listen --forward-to localhost:<gw>/api/modules/webhooks/<tenantId>/kelta-billing`.

## Email (SMTP)

Worker emails go out via `spring-boot-starter-mail`. The kelta-worker `application.yml`
defaults target a local **mailpit** (`localhost:1025`, no auth, no TLS) so
`docker compose up` and bare `mvn spring-boot:run` both work out of the box —
mailpit captures every outbound message and serves them at <http://localhost:8025>.

Toggle via `kelta.email.enabled` (`EMAIL_ENABLED` env, default `true`). Override
the SMTP target in K8s via `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`,
`SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`. Per-tenant SMTP overrides live in
`TenantEmailSettings` and take precedence over the platform-wide config.

**Send paths.** Internal service-to-service delivery uses `InternalEmailController`
(`/api/internal/email/**`, shared-token, not gateway-routed). The FE-reachable
transactional send is `EmailSendController` `POST /api/email/send` (gateway static
route `/api/email/**`) — stored-template-only, `MANAGE_EMAIL_TEMPLATES`-gated,
per-tenant rate-limited; backs the `send_email` UI Quick Action. See
`architecture.md` → *Transactional send endpoint*.

## Push notifications (FCM · APNs · Web Push)

One `PushProvider` SPI, three transports. `kelta.push.provider` (`log` default, `fcm`,
`apns`) selects **one** mobile provider; `WebPushProvider` is not part of that choice — it
registers independently whenever VAPID keys are present and runs **alongside** the mobile
one, because a tenant can have both browser and native subscribers. `DefaultPushService`
routes per device: `PushProvider.supports(platform)` (default `true`) lets a specialist
claim a platform, and a platform-specific provider wins over the configured default.

**Browser Web Push (consumer-alerting slice 6)** implements the standards directly on JDK
crypto — RFC 8291 message encryption over RFC 8188 `aes128gcm`, RFC 8292 VAPID, RFC 5869
HKDF. **No push library and no BouncyCastle**: the available libraries pull BouncyCastle,
whose reflective surface would have to be enumerated in the worker's `reflect-config.json`
or it fails *only* on the native image in production. The VAPID JWT is signed with nimbus
(already native-proven here via LiveKit token minting). `WebPushCrypto` is verified against
the RFC 8291 §5 published test vector byte-for-byte — an encryption bug in this area does
not throw, it produces a body every browser silently discards, so vector conformance is the
guard.

| Piece | Detail |
|---|---|
| Config | `kelta.push.vapid.{public-key,private-key,subject}` (env `KELTA_PUSH_VAPID_*`). `make gen-vapid` writes a dev pair. Absent ⇒ provider not registered |
| Public key | base64url **uncompressed P-256 point**; private key its raw base64url scalar; subject a `mailto:`/`https:` URI (Safari is strict) |
| Key discovery | `GET /api/devices/vapid-public-key` → `{data:{publicKey}}`, 404 when unconfigured. Deliberately on the existing `/api/devices/**` gateway route rather than a new `/api/push/**` segment, which would need its own static route |
| Registration | `POST /api/devices` with `subscription` (the full `PushSubscription` JSON) instead of `deviceToken`. The device token is derived as **`sha256(endpoint)` hex** — endpoints run well past the 500-char column while the token carries `UNIQUE (tenant_id, device_token)`; the subscription JSON lives in `push_device.web_push_subscription` (V180) |
| Authorization | `/api/devices/**` is a static route ⇒ blanket `API_ACCESS` only, which the seeded **Portal User** profile has. Portal members can register and list **their own** devices without a policy change |
| Request | `POST <endpoint>`, `Content-Encoding: aes128gcm`, `TTL: 86400`, `Authorization: vapid t=<JWT>, k=<public key>`. The JWT audience is the push service **origin**, never the full endpoint — the endpoint path *is* the subscription |
| Pruning | 404/410 ⇒ `PushDeliveryException(invalidToken=true)` ⇒ the device row is deleted, exactly as for a stale FCM/APNs token. Other non-2xx are transient and keep the device. A missing/unparseable VAPID key fails **without** pruning — an operator config gap must not wipe every subscription |
| Rotation | Changing the VAPID pair invalidates every existing browser subscription; clients must re-subscribe |

## SMS (Twilio)

One `SmsProvider` SPI. `kelta.sms.provider` selects **one** implementation — `log` (default,
`LogOnlySmsProvider`) or `twilio` (`TwilioSmsProvider`) — via mutually-exclusive
`@ConditionalOnProperty`, so single-typed injection into `DefaultSmsService` /
`AlertDispatchService` never sees two candidates (`SmsProviderWiringTest` pins this). Used for
**MFA verification codes** (`DefaultSmsService`) and, since consumer-alerting slice 10,
**availability alerts** (`AlertDispatchService` `sms` channel → member's
`platform_user.phone_number`).

`TwilioSmsProvider` calls the Twilio Messages API directly — a form-encoded POST on Spring's
`RestClient` (Basic auth = account SID : auth token), deliberately **not** the `twilio-java`
SDK, whose reflective model surface is the same native-image hazard the payment client avoids.
It **constructs even with blank credentials** (validates on `send`), so selecting
`provider=twilio` without a secret can never stop the worker booting — a missing credential is a
FAILED SMS delivery, not a dead service.

| Piece | Detail |
|---|---|
| Config | `kelta.sms.provider` (`log`\|`twilio`); `kelta.sms.twilio.{account-sid,key-sid,auth-token,from-number}` (env `TWILIO_*`, from the deployment secret; `from-number` E.164). **Two auth modes:** API key (recommended, revocable) = `key-sid` (`SK…`) + `auth-token` (the key secret); or account = leave `key-sid` blank, `auth-token` = the account Auth Token. `account-sid` (`AC…`) is always required |
| Send | `POST https://api.twilio.com/2010-04-01/Accounts/{account-sid}/Messages.json`, `application/x-www-form-urlencoded` `From`/`To`/`Body`, HTTP Basic auth (**user = `key-sid` if set, else `account-sid`**; password = `auth-token`). Non-2xx → `SmsDeliveryException` carrying Twilio's error body (never the token) |
| Alert channel | `sms` must be in the watch channels ∩ the member's plan entitlement (keep `sms` a paid-tier entitlement so free members can't burn credits); one alert per opening bounds volume |
| Recipient | `platform_user.phone_number`; no number on file ⇒ FAILED sms delivery, other channels unaffected |
| Owed | Live round-trip verification needs a real Twilio account (native-image `RestClient` + Basic-auth path is what CI can't reach); per-tenant Twilio credentials would move to the credential vault (`StripeCredentialType` precedent) |

Per-tenant VAPID overrides could ride `TenantPushSettings` (as APNs/FCM overrides already
do) but are platform-level in v1.

## Portal magic-link login (telehealth slice 1)

Passwordless sign-in for external `user_type=PORTAL` users. kelta-auth serves the pages
(`/portal/login` → request form, `/portal/login/verify?token=` → consume + session) and
stores only SHA-256 hashes in `portal_login_token` (V167, RLS): purpose `PORTAL_LOGIN`
(15 min, max 3 outstanding per user per window) or `PORTAL_INVITE` (7 days, written by the
worker's `PortalUserService` with the invite). Consumption is an atomic conditional
`UPDATE … RETURNING`; responses are uniform ("check your email") to prevent account
enumeration. Emails ride the standard template path — system templates `portal.invite` and
`portal.login-link` (tenant-overridable by key), sent via `WorkerClient.sendTemplateEmail`
(auth → worker internal) or `EmailService.sendByKey` (worker). After verify, the user is
redirected to `{ui-base-url}/{tenantSlug}/app`; the app's OIDC redirect then completes
silently against the authenticated session. MFA/TOTP is not interposed — link possession is
the factor, and portal users cannot reach enrollment surfaces.

## Telehealth visit links + calendar invites (slice 4)

Appointment emails (`appointment.confirmed`/`.reminder`/`.cancelled`, system templates
V169) carry a **visit link**: a stateless HMAC token (`VisitTokenService`, secret
`kelta.telehealth.visit-secret` / `KELTA_TELEHEALTH_VISIT_SECRET` — a DEV default with a
startup warning applies when unset) binding (tenant, appointment, portalUser, exp =
scheduledEnd+1h). `GET /api/telehealth/visits/{token}` is a gateway
**unauthenticated path**: it re-validates against the LIVE appointment row (a cancelled
appointment kills the link), mints a fresh single-use 15-minute `portal_login_token`, and
302s into the kelta-auth magic-link verify — one click from email to an authenticated
portal session. Confirmations additionally attach a hand-rolled **RFC 5545 .ics**
(`IcsGenerator`, single VEVENT, UTC, METHOD:PUBLISH) via
`DefaultEmailService.queueEmailWithAttachments`. Reminders come from
`AppointmentReminderSweep` (60s poll, atomic UPDATE-claim on `reminder_sent_at`, offset
`kelta.telehealth.reminders.offset-minutes`, toggle `…reminders.enabled`).

## LiveKit (telehealth slice 5 — video SFU)

Self-hosted, Apache-2.0 WebRTC SFU. **Media never traverses the gateway** — clients connect
to LiveKit's own host (`wss://livekit.kelta.io` prod via the `livekit` app in homelab-argo;
`docker compose --profile telehealth` / `make up-telehealth` locally). The worker's only
integration points:

- **Token minting** (`LiveKitTokenService`, plain nimbus HS256 — no vendor SDK): `iss` =
  API key, `sub` = platform user id, a `video` claim with room-scoped grants
  (`roomJoin`/`canPublish`/`canSubscribe` for exactly one room). Config
  `kelta.telehealth.livekit.{url,api-key,api-secret}` (`KELTA_LIVEKIT_*`; dev defaults +
  startup warning when unset, matching the compose profile's dev keys). Sessions are
  created lazily at the first token request (`video-sessions`, one per appointment via a
  partial unique index; ad-hoc per chat conversation); rooms are opaque
  `t_<tenantId>_<uuid>` names, so a leaked token can never reach another tenant's room.
  Enforcement at mint (`VideoSessionService`, fail-closed): appointment/conversation
  membership, the join window (start−15min…end+60min), the per-tenant `telehealthEnabled`
  gate, and the `videoMinutesPerMonth` governor (SQL month-sum of ended durations — no
  counter infra).
- **Webhooks** (`POST /api/telehealth/webhooks/livekit`, gateway unauthenticated path):
  LiveKit signs each delivery with a JWT (same API secret) whose `sha256` claim is the raw
  body digest — `verifyWebhook` checks signature AND digest before parsing. Idempotency:
  INSERT into `livekit_webhook_event` is the claim (duplicate delivery → skip).
  `room_started`/`room_finished` drive session status + duration and publish
  `kelta.video.session.<tenantId>.<sessionId>`; `egress_ended` stamps `recording_key`.
  On `room_finished` (after stamping ended_at/duration) the worker calls
  `ArchiveService.archiveVideoSession` **best-effort** (try/catch-log — never fails the
  webhook; the manual archive-now path and the auto-archive sweep are the backstops).

## Telehealth encounter archival (slice 7 — S3 artifact layout)

`ArchiveService` turns a CLOSED chat conversation or an ENDED video session into an
immutable **encounter record** (`telehealth-archives` collection, one row per
`(sourceType, sourceId)` via a unique index). Reuses the attachment lifecycle above — no new
storage integration.

- **Artifacts.** Two S3 objects per archive, written via `S3StorageService.uploadObject` and
  recorded as `file_attachment` rows **OWNED by the archive row**
  (`collection_id='telehealth-archives'`, `record_id=<archiveId>`):
  1. **Canonical JSON** (`schemaVersion: 1`) — conversation: subject, ordered participants,
     ORDERED messages, an attachment **manifest** (attachment id + `storage_key` + filename +
     content-type; the objects are RETAINED in place, not copied), and status history
     (`closedAt`/`archivedAt`); video: session metadata, duration, consent, `recording_key`.
  2. **PDF render** via `PdfTableWriter` (chat: Timestamp/Sender/Message; video: Field/Value)
     — a convenience render; the JSON is canonical.
- **Storage key.** `{tenantId}/telehealth-archives/{archiveId}/{attachmentUuid}/{fileName}` —
  same shape as `AttachmentUploadController`.
- **Tamper evidence.** The archive row stores the **SHA-256 of the JSON bytes**. The JSON is
  emitted by a self-contained serializer that **sorts object keys** (minimal whitespace,
  RFC-8259 escaping), so identical encounter content hashes identically regardless of Jackson
  version or map iteration order — the SHA-256 is reproducible.
- **Attachment pinning.** When a conversation is archived its message attachments are
  **re-parented** to the archive row (`UPDATE file_attachment SET collection_id=…, record_id=…`)
  so (a) the later live-message purge can't delete what the transcript manifest references and
  (b) deleting the archive later cascades them via `AttachmentCleanupHook` (which matches by
  `record_id`).
- **Access.** `GET /api/telehealth/archives/{id}` returns short-lived (15-min) presigned
  download URLs for each artifact; every download is audited (`ARCHIVE_ACCESSED`). Portal
  participants get a `record_share` on the archive row and see only their own.
- **Retention & purge.** `retentionUntil = archivedAt + retentionYears` (tenant setting via
  `tenant.limits`; default 7). `RetentionPurgeSweep` (`@Scheduled`) is **DRY-RUN by default**
  (`kelta.telehealth.retention.purge-dry-run:true`) — see concerns.md before arming. When
  armed it deletes the artifact + linked recording S3 objects and stamps `purged_at` (the row
  is kept as a tombstone), skipping legal-hold rows; recording keys stored as `s3://bucket/…`
  URIs are normalized to the object path before delete.

## Mass-email campaigns (V152)

Bulk send on top of the same SMTP path. Three tenant-scoped tables (RLS): `email_campaign`
(the read-only **campaigns** system collection — config + aggregate stats), `email_campaign_recipient`
(per-recipient send + open/click/unsubscribe events), and `email_suppression` (per-tenant
unsubscribe/suppression list).

- **Admin API** — `/api/admin/campaigns` (`CampaignAdminController`), gated on the new
  **`MANAGE_CAMPAIGNS`** system permission (seeded to System Administrator + Marketing User).
  CRUD + actions `/{id}/send` (enqueue now), `/{id}/schedule`, `/{id}/cancel`, `/{id}/stats`,
  `/{id}/recipients`, `/{id}/test`, and `/suppressions` (list/add/remove). The generic collection
  route is read-only, so every mutation goes through this controller.
- **Runner** — `CampaignRunnerService`, polled by `CampaignPollerConfig` (default 15s). Claims
  runnable campaigns (QUEUED, or SCHEDULED whose time has arrived) with a conditional-claim
  `UPDATE ... WHERE status IN (...)` after a `SELECT ... FOR UPDATE SKIP LOCKED` (leader election,
  mirrors `BulkJobProcessorService`). Runs the claim with **no tenant context** (admin_bypass) so it
  sees campaigns across tenants; each campaign's work then runs under `TenantContext.withTenant`.
  Resolves recipients from `target_collection` via the `QueryEngine` (paged), renders subject/body
  with `${field}` merge fields (same grammar as `DefaultEmailService`), and sends via
  `EmailService.queueEmail(..., "CAMPAIGN_SEND", campaignId)`.
- **Tracking** — public, unauthenticated endpoints `/api/track/{open,click,unsubscribe}`
  (`CampaignTrackingController`), added to the gateway `unauthenticated-paths` allowlist. Each link
  carries an **HMAC-signed opaque token** (`TrackingTokenService`, `CAMPAIGN_TRACKING_SECRET`) that
  encodes the recipient id and self-authenticates — a forged/tampered token is rejected, so stats
  can't be poisoned and unsubscribes can't be forged. Bodies get the open pixel appended, `href`s
  rewritten through the click redirect, and an unsubscribe footer (`${unsubscribeUrl}` merge var).
  The click redirect only forwards to `http(s)` URLs (open-redirect guard).
- **Spam controls** — the suppression list is checked before every send; the daily
  `campaignEmailsPerDay` governor limit (`TenantTierQuotas`) caps volume; a configurable
  `send-rate-per-second` throttle paces delivery. Config lives under `kelta.campaigns.*`
  (`CampaignProperties`).

## Sandboxes & metadata promotion (multi-cluster)

Sandbox = a real linked tenant (`tenant.parent_tenant_id`, V158); config moves between
environments as **metadata packages** (`PackageService` export → `PackageImportService` import).

- **Package format v2** — `{formatVersion: 2, source: {instanceId, tenantId, tenantSlug},
  exportedAt, items: [{type, data}]}`. `instanceId` is the installation's stable identity
  (`platform_instance`, V158). Items carry natural-key context (`collection_name`,
  `reference_collection_name`, `layout_name`, `field_name`, `picklist_name`, `menu_name`) so
  cross-tenant/cross-cluster import remaps every reference **by name, never by UUID**.
  Importers accept v1 packages (minus the new types).
- **Topology rule** — a tenant's dev/qa/staging/prod may span isolated clusters whose databases
  don't know each other. The only universal identity check is **source ≠ target**: promotion
  rejects a package whose `source.instanceId` + `source.tenantId` equal the local target.
  Generic `/api/packages/import` still allows same-tenant reapply (restore).
- **Remote push promotion** — an environment row may describe a remote target
  (`remote_base_url`, `remote_tenant_slug`, `credential_ref`). `RemotePromotionClient` POSTs the
  package to `{base}/{slug}/api/packages/import?conflictMode=` with a **vault PAT**
  (`CredentialProvider`; the secret never lives on the env row). The remote PAT user needs
  `CUSTOMIZE_APPLICATION` on the remote cluster. URL scheme allow-list http/https, no userinfo,
  redirects disabled, bounded timeouts/response. Remote targets have no local snapshot —
  rollback → 409; restore on the remote side.
- **Manual cross-cluster path** — `kelta metadata export` on cluster A → move the file →
  `kelta metadata apply` on cluster B (same hardened import + provenance).
- **Security types** (`ROLE`/`POLICY`/`ROUTE_POLICY`/`FIELD_POLICY`) are cloned INTO sandboxes
  but **never promoted** — authz changes reach production only via the explicit packages API.

## Fleet notifications

Slack notifications about automated PRs are posted by the RZWare fleet from its own runtime
(`rzware-ceo`), not from this repo. Kelta itself has no Slack integration beyond the OAuth
credential template (`credential-templates/slack.json`); a flow reaches Slack through
`CALL_API`/`HTTP_CALLOUT` with a stored credential.
