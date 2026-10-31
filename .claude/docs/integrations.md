# External Integrations

## APIs & Services

| Service | Purpose | SDK/Version | Connection | Key Files |
|---------|---------|------------|------------|-----------|
| Anthropic Claude | AI assistant | `anthropic-java` 3.3.0 (API version `2026-10-21`; `MessageParam` is a per-role union — build with `MessageParam.ofUser…`/`ofAssistant…`; `temperature`/`top_p`/`top_k` are rejected, so none is sent) | API key (sent as `Authorization: Bearer`) | `kelta-ai/.../service/AnthropicService.java` |
| Cerbos | Authorization (gRPC) | `cerbos-sdk-java` 0.20.1 | `${CERBOS_HOST}:${CERBOS_GRPC_PORT:3593}` | `kelta-gateway/.../authz/cerbos/CerbosAuthorizationService.java`, `kelta-worker/.../service/CerbosAuthorizationService.java` |
| Svix | Webhook delivery | REST via `RestClient` (no Java SDK); admin UI: `svix` 2.9.0 + `svix-react` 1.13.9 | `${SVIX_SERVER_URL}`; on only with a non-blank `SVIX_AUTH_TOKEN` (`SvixConfig.SvixTokenConfigured`; else no publishers and `/api/svix/**` 404s) | `kelta-worker/.../listener/SvixWebhookPublisher.java`, `.../service/SvixTenantService.java` |
| Superset | Embedded analytics | REST API | `${SUPERSET_URL}` | `kelta-worker/.../service/SupersetApiClient.java`, `kelta-ui/app/` (`@superset-ui/embedded-sdk`) |
| AWS S3 / Garage | Object storage | `aws-sdk-s3` 2.57.3 | `${KELTA_S3_ENDPOINT}` | `kelta-worker/.../service/S3StorageService.java` (attachments); media bucket `${KELTA_MEDIA_*}` → `.../service/MediaStorageService.java` (see Media fields below) |
| Keycloak | OIDC federation | Spring Security OAuth2 | Port 8180 (docker-compose) | `kelta-auth/.../federation/FederatedUserMapper.java` |
| GitHub Container Registry (ghcr.io) | Public multi-arch (amd64/arm64) JVM images of the quickstart services, one per `v*.*.*` tag: `ghcr.io/kelta-io/<service>:<version>`, `:latest` for non-prerelease tags | `docker/build-push-action`, `buildx imagetools` | `GITHUB_TOKEN` with `packages: write` (no other secret) | `.github/workflows/release.yml`, `.github/release/` — see `ci-cd.md` → `release.yml`. **Consumed by the quickstart:** base `docker-compose.yml` pulls `ghcr.io/kelta-io/<service>:${KELTA_VERSION}` (pinned in `.env.example`) for auth/worker/gateway/ui, and the CI `quickstart` job runs exactly those pulled images; `make up*` layers `docker-compose.build.yml` to build from source instead. The homelab deploy keeps using Harbor |
| GitHub Dependabot | Weekly dependency-update PRs (maven, npm, docker, github-actions); minor+patch grouped per ecosystem, majors one PR each | GitHub-native (no SDK) | none — GitHub app on the repo | `.github/dependabot.yml` — see `ci-cd.md` → Dependabot |
| GitHub CodeQL | Code scanning of Java and JS/TS on push to `main`, PRs and weekly; alerts in Security → Code scanning. Free on a public repo (private repos need GitHub Advanced Security) | `github/codeql-action@v4` | `GITHUB_TOKEN` with `security-events: write` (no other secret) | `.github/workflows/codeql.yml` — see `ci-cd.md` → `codeql.yml` |

### Cerbos policy seeding (worker startup)

`CerbosPolicySeeder` pushes base + per-tenant generated policies via the Cerbos Admin HTTP API
(`http://${CERBOS_HOST}:3592/admin/policy`, basic auth `kelta.worker.cerbos.admin-{username,password}`)
**once per build**: after a successful seed it writes `cerbos:policy-seed:done:<build.time>` in Redis
and later replicas of the same image skip, provided `GET /admin/policies` still lists the unscoped
`collection` policy (a wiped/restored Cerbos store re-seeds). `CERBOS_SEED_FORCE=true`
(`kelta.worker.cerbos.seed.force`) forces a full seed. Runtime permission changes never rely on this
path — they sync through the profile/permission hooks and `CerbosPolicySyncCoalescer`.

### Superset (embedded analytics)

On only when `SUPERSET_URL` (`kelta.superset.url`) is non-blank (`SupersetConfig.SupersetUrlConfigured`,
which also gates `SupersetController`). Leaving it unset registers no Superset bean and `/api/superset/**`
answers 404. Each tenant gets a dedicated Postgres user (`SupersetDatabaseUserService`) and a Superset
database connection (`SupersetTenantService`). Superset reaches Postgres at the
`SupersetDatabaseTarget`: by default the host, port and database of the worker's own
`spring.datasource.url`, each overridable with `SUPERSET_DATABASE_HOST`, `SUPERSET_DATABASE_PORT`,
`SUPERSET_DATABASE_NAME` (`kelta.worker.superset.database-*`). Use the overrides when Superset must use
another address, for example around a pooler the per-tenant users can't authenticate through. The
same database name is the one the tenant users are granted `CONNECT` on. If the datasource URL
can't be parsed and no override is set, the worker fails at startup with the setting to fix. A
connection is created once per tenant, so existing Superset connections keep whatever URI they
were created with; change them in Superset if the address moves.

### Cerbos SDK on native images

`cerbos-sdk-java` is gRPC + protobuf (`protobuf.version` is pinned in the gateway and worker poms,
as is `guava.version`: gRPC's own Guava 33.6.0 carries GHSA-xxph-c9ww-hj94).
Both native profiles initialize protobuf and the Cerbos/`build.buf.validate` generated code at build
time, so descriptor reflection runs on the build host. The gateway's `ProtobufInitOrderFeature`
fixes the order those classes initialize in, because protobuf fails if `JavaFeaturesProto` goes
before `DescriptorProtos`. Changing the Cerbos call paths alone can change that order (see
`concerns.md` → Dependency Risks).

### Cerbos principal attributes

Gateway (`CerbosPrincipalBuilder`) and worker (`CerbosAuthorizationService.buildPrincipal`) build
the same principal shape — any policy or custom rule may rely on it:

| Field | Value |
|-------|-------|
| `P.id` | the caller's **email** (username). Compare it only with email-valued fields |
| `P.attr.userId` | the caller's `platform_user.id` **UUID** — compare UUID columns (`createdBy`, user lookups) with this. Worker: from `CallerContext`; gateway: the PAT owner / worker identity lookup / a kelta-auth UUID `sub`. `""` on the internal tier and for connected apps |
| `P.attr.profileId`, `P.attr.profileName`, `P.attr.tenantId` | the caller's profile and tenant (`profileName` gateway only) |
| `P.attr.geoCountry` | request-origin ISO country, `""` when unknown |
| `P.attr.groups` | OIDC groups (gateway only, when present) |

The worker builds the principal on the request thread (its Cerbos calls run on an executor thread
where request ScopedValues are not bound). "Restrict to own records" in the custom-rule editor and
the visual `$CURRENT_USER` condition both compile to `<attr> == P.attr.userId`. At sync time
`CerbosPolicySyncService` rewrites legacy `R.attr.<f> == P.id` (either operand order, `==`/`!=`)
to `P.attr.userId` **only** when `<f>` is `createdBy`/`updatedBy` or a LOOKUP/MASTER_DETAIL/
REFERENCE field of the rule's collection; a comparison with any other field (e.g.
`R.attr.ownerEmail == P.id`) is left alone. The stored `profile_custom_rules` rows are not changed.

### Attachment lifecycle (S3)

Files attach to any record via the `attachments` system collection (backed by `file_attachment`, `S3StorageService`). The full lifecycle:

1. **Upload** — `POST /api/attachments/upload-url` validates the file (type/size, per-tenant storage governor), creates a pending row, and returns a presigned S3 PUT URL. The client PUTs the bytes directly to S3, then `POST /api/attachments/{id}/finalize` verifies the object exists and records the storage key. (`AttachmentUploadController`)
2. **List / read / download** — `attachments` is routed by `DynamicCollectionRouter` like any collection (`GET /api/attachments?filter[collectionId][eq]=…&filter[recordId][eq]=…`, get-by-id, `?include=attachments`). `AttachmentUrlEnricher` (`@ControllerAdvice`, `@ConditionalOnBean(S3StorageService)`) injects a presigned `downloadUrl` for any `attachments` resource with a `storageKey`. `FileController` (`GET /api/files/**`) streams objects server-side with `Range` support.
3. **Delete** — `DELETE /api/attachments/{id}` (`AttachmentUploadController#deleteAttachment`) deletes the S3 object **and** the row. Its literal path beats the router's `/api/{collection}/{id}`, so the generic delete (which would orphan the S3 object) is bypassed. S3 deletion is best-effort and never blocks the metadata delete.
4. **Cascade cleanup** — `AttachmentCleanupHook` (wildcard `afterDelete`, order 200) removes a record's `file_attachment` rows + S3 objects when the **parent** record is deleted. Lookup uses `idx_attachment_tenant_record` (Flyway V142). The `attachments` collection itself is skipped (handled by the dedicated delete above). S3 cleanup is gated on `S3StorageService.isEnabled()`; row cleanup always runs.

### Media fields (direct-to-storage uploads, public-capable)

A **media field** holds the storage key of an object uploaded straight from a browser to the
platform **media bucket** — e.g. listing photos on a public site. Any tenant collection can have
one; it is pure field metadata, so nothing in the platform names a tenant's collection. Unlike
attachments there is no `file_attachment` row and no finalize step: the tenant's own record *is*
the metadata.

**Configure (metadata).** On a STRING or TEXT field set `fieldTypeConfig.media` (the admin
FieldEditor → "Media uploads" section writes the same shape):

```json
{ "media": { "enabled": true, "contentTypes": ["image/"], "maxFileSize": 10485760 } }
```

`enabled` must be literally `true` (fail-closed parse, `MediaFieldConfig`). `contentTypes` entries
ending in `/` match by prefix, others exactly; default `["image/"]`. `image/svg+xml` is accepted
only when listed exactly, and HTML/JS/executable types never. `maxFileSize` (bytes) can only
lower the platform ceiling. A field update already broadcasts `kelta.config.field.changed`, so
every pod sees a config change — no new hook or subject.

**Endpoints** (`MediaUploadController`; both nested under the collection's own route):

1. `POST /api/{collection}/upload-url` `{fileName, contentType, fileSize, field?}` → a presigned
   PUT plus `storageKey` = `<tenantId>/<collection>/<uuid>/<sanitized fileName>`, `field`,
   `downloadPath`, `headers` (send exactly that `Content-Type`). `field` is required only when the
   collection has more than one media field. 404 when the collection has no media field; 503 when
   the media bucket is not configured.
2. The client PUTs the bytes, then creates/updates a record through the ordinary collection route
   with the media field set to `storageKey`.
3. `GET /api/{collection}/download/<storageKey>` → `302` to a presigned GET (15 min),
   `Cache-Control: private, max-age=60`, so a plain `<img src>` works without a bearer token.

**Authorization.** The gateway authorizes both paths as calls on that collection — `create` for
the POST, `read` for the GET — via the normal route lookup, so a Guest profile with read on the
collection makes its media public and nothing more. The download then requires that the key's
tenant segment is the caller's tenant, its collection segment is this collection, and a record of
this collection references it in a media field — looked up through the `QueryEngine` (RLS +
owner-scoped reads) and passed through the record-level Cerbos decision a record `GET` gets
(`CerbosRecordAuthorizationAdvice.isRecordReadable`, incl. manual shares). Anything else is a 404.
Writes of the record itself are owner-guarded by `OwnerScopeGuardHook` when the collection
declares ownership.

**Platform config** (`kelta.storage.media.*`, all `${ENV:default}` in `application.yml`, bean is
unconditional for the native image): `KELTA_MEDIA_ENABLED` (default `false`), `KELTA_MEDIA_BUCKET`
(default `kelta-media`), `KELTA_MEDIA_ENDPOINT` / `KELTA_MEDIA_PUBLIC_ENDPOINT` /
`KELTA_MEDIA_REGION` / `KELTA_MEDIA_ACCESS_KEY` / `KELTA_MEDIA_SECRET_KEY` (each defaults to the
matching `kelta.storage.s3.*` value), `KELTA_MEDIA_MAX_FILE_SIZE` (default 15 MB),
`KELTA_MEDIA_URL_EXPIRY_MINUTES` (default 15).

**Adopting it for an existing collection** (CLI/MCP/API, no code):

1. Make sure the deployment sets `KELTA_MEDIA_ENABLED=true` and `KELTA_MEDIA_BUCKET` to the bucket
   that already holds the objects.
2. Update the field that stores the key: `update_field` (MCP) / `PATCH /api/fields/{id}` with
   `fieldTypeConfig.media.enabled = true` (plus `contentTypes` / `maxFileSize` if needed). Mark it
   `indexed` — every download filters the collection on that field.
3. Grant the collection `create` to the profiles that upload and `read` to the ones that view
   (Guest for a public site); set `ownerField`/`ownerScope`/`ownerScopeReads` if members may only
   change their own rows.

Existing objects stay readable without being moved **when their keys already have the generic
layout** `<tenantId>/<collection name>/<uuid>/<file>` in the configured bucket and the record
stores the bare key: the download path, key layout and response shape are those of the
collection-specific endpoint this replaced. Do step 2 **before** the worker that drops the old
endpoint rolls out — until a field is media-enabled its collection's downloads 404.

## Data Storage

| Store | Purpose | Config Key | Details |
|-------|---------|-----------|---------|
| PostgreSQL 15 | Primary DB | `${SPRING_DATASOURCE_URL}` | Multi-tenant per-tenant schemas, Flyway migrations in `kelta-worker/.../db/migration/`. Dynamic user-collection tables are created by `PhysicalTableStorageAdapter.initializeCollection` which uses `CREATE TABLE IF NOT EXISTS` + `SchemaMigrationEngine.reconcileSchema` (introspects `information_schema.columns` and ALTERs to add missing ones) — this path must reconcile before issuing FK constraint statements so post-create `ADD CONSTRAINT FOREIGN KEY` references columns that exist. Because `CREATE TABLE IF NOT EXISTS` is **not atomic against concurrent CREATEs** in PG (two transactions can both pass the existence check and then both INSERT into `pg_type`, losing one with SQLSTATE 23505 on `pg_type_typname_nsp_index`), `initializeCollection` catches `DuplicateKeyException` from the CREATE step and falls through to `reconcileSchema`. This matters whenever the same NATS `CollectionChanged` event is delivered to multiple worker pods in parallel. CI: shared `kelta-ci-db` pool (schema-isolated per run, see `scripts/ci/README.md`); local: docker-compose or Testcontainers |
| Postgres full-text + audit | Record search, audit trails | — | Search is Postgres, **not** OpenSearch: `kelta-worker/.../service/SearchIndexService.java` maintains a `tsvector` column over `JdbcTemplate` (record indexing via `SearchIndexListener`; `/api/_search` authorizes each hit against its own collection and record — `CerbosRecordAuthorizationAdvice.readableSearchHits`, PLT-695); semantic search is `SemanticSearchService` over `pgvector`. `SecurityAuditLogger.java` emits structured SLF4J events that land in Loki. Setup/config audit: `AuditBeforeSaveHook` → `setup_audit_trail` (config collections only; user-data change history lives in `field_history`/`record_version`). **OpenSearch was removed from the cluster on 2026-08-10** (homelab-argo#205) — no service ever held an OpenSearch client dependency; it survived only as Jaeger's trace store. It remains in `docker-compose.yml` for local dev, where Jaeger still backs onto it |
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
| `kelta.record.changed` | Record CRUD events (`RecordChangedPayload`; carries `containsMaskedFields` when the collection has data-masking config — the gateway `RealtimeBridge` then omits record `data` from WebSocket fan-out; and `payloadOmitted: true` with no `data`/`previousData` when the full event exceeded `kelta.nats.max-event-payload-bytes` — consumers re-read or refetch by `recordId`, see `architecture.md` → Oversized events) |

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

**Flow metrics** (`kelta-worker`; all but the step-log counters come from `FlowMetricsConfig`,
the `FlowEngine`'s `FlowExecutionListener`). Test executions are not counted in the execution meters.

| Metric | Type | Labels |
|--------|------|--------|
| `kelta_flow_execution_total` | counter | `flow_id`, `flow_name`, `status` (`COMPLETED`/`FAILED`/`CANCELLED`) |
| `kelta_flow_execution_duration_seconds` | timer | `flow_id`, `flow_name` |
| `kelta_flow_step_total` | counter | `flow_id`, `state_type`, `resource` (Task resource key, `none` otherwise), `status` |
| `kelta_flow_step_duration_seconds` | timer | `flow_id`, `state_type`, `resource` |
| `kelta_flow_error_total` | counter | `flow_id`, `error_code` (`unknown` when absent) |
| `kelta_flow_execution_active` | gauge | none |
| `kelta_flow_step_log_bytes` | counter (bytes) | none — step-log snapshot JSON written, after the cap (`JdbcFlowStore`) |
| `kelta_flow_step_log_snapshots_truncated` | counter | none — snapshots replaced by a marker over the cap (`JdbcFlowStore`) |

The engine reports only a flow's id; `flow_name` (the flow's display name) is resolved through a
Caffeine cache (10k entries, 10-minute expiry) over `FlowRepository.findFlowNameById`, under the
execution's tenant. A missing flow or a failed lookup is tagged `unknown` and is not cached.
Two tenants may share a flow name, so group by `flow_id` to keep their series apart. No label
carries an execution, record or user id.

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

### Flow-to-flow and outbound events (`TRIGGER_FLOW`, `PUBLISH_EVENT`)

`TRIGGER_FLOW` (`{ flowId, input? }`) starts a saved flow through the `FlowEngine` module
extension (`FlowEngine.startTriggeredExecution`): a new async execution whose initial state is
`{ trigger: { type: "TRIGGER_FLOW" }, input, context: { tenantId, userId, flowId } }`, so the
target reads `$.input.<key>` like every other entry point. Output `{ flowId, executionId }`;
an unknown `flowId` or a missing engine fails the step. The hop count travels in the started
execution's **initial input** under `FlowEngine.TRIGGER_DEPTH_KEY` (`_triggerDepth`) — the
in-memory `invokeDepth` cannot cross an async hop, and the initial input is persisted, so the
count also survives a Wait/resume. `TaskStateExecutor` passes it to handlers as
`ActionContext.triggerDepth`; a step at `FlowEngine.MAX_TRIGGER_DEPTH` (= `MAX_INVOKE_DEPTH`, 10)
fails with `… MAX_TRIGGER_DEPTH=10`.

`PUBLISH_EVENT` publishes to `kelta.event.<tenantId>.<topic>` (stream `KELTA_EVENTS`, 24h) via the
`EventPublisher` extension the worker builds in `FlowConfig.natsFlowEventPublisher` (synchronous
JetStream publish; no NATS connection → no extension → the step fails). Subject rules and the
envelope: `architecture.md` → Flow-published external events, and the public
`kelta-marketing/src/content/docs/api/external-events.md`.

`DELAY` and `CREATE_TASK` are rejected by `FlowDefinitionValidator` (hence
`FlowDefinitionValidationHook`, 400) at any nesting depth; their handlers stay registered and fail
naming the replacement (`Wait` state / `CREATE_RECORD`) for flows saved earlier.

### Wait states — persistence and resume

A Wait with `Seconds` ≤ 10 sleeps in-process. Longer, timestamp-based
(`Timestamp`/`TimestampPath`), and event-based (`EventName`) Waits park the execution
WAITING and record a `flow_pending_resume` row (wake time for timed waits, event name for
event waits). The worker's `FlowResumePollerConfig` (10s default,
`kelta.flow.resume.poll-interval-ms`; disable with `kelta.flow.resume.enabled=false`)
claims due rows with `SELECT FOR UPDATE SKIP LOCKED` — exactly one pod resumes an
execution — and `FlowEngine.resumeExecution` re-loads the flow definition and continues
from the Wait's `Next` (a terminal Wait completes). The poller runs outside any tenant
scope, so `resumeExecution` resolves the slug itself (`FlowStore.findTenantSlug`) and
binds `runWithTenant(id, slug, …)` — an ID-only scope makes `PhysicalTableStorageAdapter`
fall back to `public`, where tenant collections don't exist (#1577). A missing tenant
fails the execution with `Cannot resume: tenant … no longer exists`. Event-based waits resume through
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

> **Billing is the `kelta-billing` runtime module only** (`kelta-modules/billing`, see its
> README). The worker's compiled-in copy was removed on 2026-10-11 (module-platform slice 8).
> The module keeps its data in its own collections (`billing_plans`, `billing_customers`,
> `billing_subscriptions`, `billing_passes`, `billing_entitlement_rules`); the V178 platform
> tables and their `billing-*` system collections were dropped by V209. Platform code reads entitlements through `ModuleEntitlementService`: the tenant's module
> `EntitlementProvider`, else `MemberEntitlements.EMPTY` (see **Entitlements** below).

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

Idempotency **converges** rather than claims: a module writes through `QueryEngine` with no
transaction of its own, so a pass is granted once per checkout session and a subscription is
upserted by its Stripe id — a redelivery converges instead of duplicating. (The removed
compiled-in webhook claimed each event id in `billing_webhook_event` inside the mutation's
transaction; that table was dropped in V207.) Answers 200 for duplicates and for event types
the module ignores, so the processor stops retrying them.

| Event | Action |
|---|---|
| `checkout.session.completed` (mode=payment) | upsert customer; grant a pass, idempotent on the session id |
| `checkout.session.completed` (mode=subscription) | upsert customer only — the subscription arrives on its own event |
| `customer.subscription.created` / `.updated` | upsert `billing_subscription`; plan resolved via the price id |
| `customer.subscription.deleted` | mark canceled — the member drops to the DEFAULT plan on next resolve |
| anything else | ignored |

**Member resolution** for an event, in order: `client_reference_id` → `metadata.userId`
(stamped on both the session and `subscription_data`) → the stored `billing_customer`
mapping. An event with no resolvable member is skipped rather than guessed at.

**Entitlements.** `billing_plan.entitlements` is an opaque tenant-authored JSON object. The
base plan is the member's subscription plan while its status is `active`, `trialing`, or
`past_due` (grace while the card is retried), otherwise the tenant's DEFAULT plan; every live
pass then merges on top — numbers SUM, booleans OR, arrays union — so a pass can add a
capability but never revoke one. Pass expiry is judged when entitlements are read, not by the
`billing:expire-passes` action (scheduled by a tenant flow — a module has no scheduler), so a
member is never over-entitled by a sweep that has not run yet.

Platform callers (`WatchController`, `AlertDispatchService`) ask the worker's
`ModuleEntitlementService`, which binds `TenantContext` (id **and** slug) and calls the tenant's
module `EntitlementProvider`, uncached. **No module installed ⇒ `MemberEntitlements.EMPTY`** —
the same answer a tenant without billing always got: watches uncapped, push/email ungated, SMS
never sent (billable channels need an affirmative entitlement). A module that throws, returns
null, or cannot be called safely (unresolvable slug) also resolves `EMPTY`, never anything wider.

**Events out: none.** The `kelta.billing.entitlement.changed.*` subject, its `KELTA_BILLING`
stream and the `kelta.trigger.<tenantId>.billing.subscription` flow bridge belonged to the
removed compiled-in implementation (they fed its entitlement cache); a module cannot publish
to NATS and caches nothing, so all three are retired. A tenant that wants billing automations
reacts to record changes on the module's collections (e.g. a record-triggered flow on
`billing_subscriptions`).

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

**Quota enforcement.** The module's `billing_entitlement_rules` rows say "records in collection
X are capped by entitlement key Y", and the module's wildcard before-save hook enforces them, so
capping a new collection is configuration rather than code. It **skips `appliesTo=PORTAL`
rules** (a module cannot read the caller's user type) — use `appliesTo=ALL`. A rule's optional `countFilter` narrows what
counts (e.g. only ACTIVE rows); its field names are validated against the collection definition
and its values bound as parameters. At the limit the create is rejected with **HTTP 400**
(JSON:API code `beforeSaveHook`) carrying an upgrade-oriented message. Enforcement **fails
open** — a billing glitch must not block a tenant's data entry.

**Dashboard prerequisites** (nothing here provisions them): Products and Prices, with each
price id recorded on a `billing_plans` row; Stripe Tax if the tenant charges tax; the Billing
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

## Outbound requests to tenant-supplied URLs

Any fetch whose target comes from tenant or admin configuration goes through
`io.kelta.runtime.outbound.OutboundHttpClient` (runtime-core, plain Java over
`java.net.http.HttpClient`) with the service's `OutboundUrlGuard` bean (`OutboundConfig` in the
worker and in kelta-auth, from `kelta.outbound.allowed-private-cidrs` =
`${KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS:}`):

- **Address check.** The URL must be absolute `http`/`https` without user-info, and **every**
  address its host resolves to must be public — loopback, private, link-local (incl. the cloud
  metadata address), CGNAT, ULA, multicast/reserved and IPv6-embedded forms are refused unless
  allow-listed. Compose service names resolve to private addresses, so they are refused too.
  A refused URL is never sent.
- **Redirects.** Never automatic. A client with a hop limit follows `GET`/`HEAD` redirects by hand,
  re-checking each `Location`; a hop to another origin drops `Authorization`/`Cookie`. Other
  methods return the 3xx to the caller.
- **Bounds.** One deadline covers all hops, headers and body; the body is read up to a cap and the
  response is flagged truncated beyond it.

| Fetcher | Connect / overall | Body cap | Redirects | On refusal |
|---|---|---|---|---|
| OIDC discovery — worker `OidcDiscoveryHook`, kelta-auth `OidcDiscoveryService` | 3 s / 5 s | 256 KB (over = error) | none | `discoveryStatus=blocked` (worker); no discovered endpoints (auth) |
| OpenAPI spec `sourceUrl` — `ApiSpecService.fetchSpec` (`POST /api/api-specs/import`, `POST /api/api-specs/validate`) | 10 s / 30 s | 10 MB (over = 422) | 3, re-validated | 400 `sourceUrl refused: …` |
| Flow `HTTP_CALLOUT` — `HttpCalloutActionHandler` | 10 s / 60 s | 1 MB read; `responseBody` keeps 50,000 chars, a truncated response is not parsed into `responseData` | 3 for `GET`, re-validated | step fails `HTTP callout refused: …` |
| Flow `CALL_API` (raw URL and an imported spec's base URL) — `CallApiActionHandler` | 10 s / 60 s | 1 MB read; `responseBody` keeps 50,000 chars, a truncated response is not parsed into `responseData` | 3 for `GET`/`HEAD`, re-validated | step fails `Api.Refused: …` |
| Flow `OUTBOUND_MESSAGE` — `OutboundMessageActionHandler` | 10 s / 60 s | 64 KB read; `responseBody` only when complete and ≤ 10,000 chars | 3 for `GET`, re-validated | step fails `Outbound message refused: …` |
| external-rest collections — worker `RestClientExecutor` (`ExternalRestStorageAdapter`) | 10 s / 30 s | 10 MB (over = transport failure) | 3 for `GET`, re-validated | `StorageException` `External REST transport failure: … (<reason>)` |
| OAuth 2.0 credential token URL (`oauth2_client_credentials` test/refresh, `oauth2_authorization_code` code exchange/refresh) — `OAuthTokenEndpointClient` (runtime-core) | 10 s / 15 s | 256 KB (over = error) | none | test/refresh fails `token URL refused: …` |
| Web Push subscription endpoint — worker `WebPushProvider`, checked again at `POST /api/devices` | 5 s / 15 s | 16 KB (body unused) | none | subscribe: 400 `subscription endpoint refused: …`; send: device pruned |
| kelta-auth federated OIDC login — token exchange, user info, JWKS (`OidcLoginHttp`) | 5 s connect / 10 s to response | — | none | registration not built; at fetch time the login fails |

`HTTP_CALLOUT`, `CALL_API` and `OUTBOUND_MESSAGE` get the guard as a `ModuleContext` extension
(`FlowConfig.moduleRegistry()`); without one `IntegrationModule` uses a guard that allows no
private range. A 4xx/5xx status fails the step, as before (`CALL_API`: `Api.HttpClientError` /
`Api.HttpServerError`; a timeout or transport failure stays `Api.Timeout`). Restricted headers the
JDK client manages (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`) are dropped from
node config. A flow that must call an in-cluster service needs that range in the allow-list.

**Web Push** endpoints are browser-supplied: an endpoint must be `https` and resolve to public
addresses, checked when a browser subscribes and again on every send. An endpoint that can never
be used (not `https`, non-public) prunes the device; a host that does not resolve at that moment
does not.

**kelta-auth OIDC login.** `DynamicClientRegistrationRepository` checks the token, user-info and
JWKS endpoints when it builds a registration (malformed or non-public ⇒ no registration, so no SSO
button); `OidcLoginHttp.requestFactory` checks every request again right before it is sent, never
follows redirects and bounds connect and response wait. `FederationConfig` registers the token
response client and the OIDC/OAuth2 user services (set on `oauth2Login` in
`AuthorizationServerConfig`) and the ID-token `JwtDecoderFactory<ClientRegistration>` bean (picked
up by type — keep exactly one). The authorization endpoint is a browser redirect, not a server
fetch, and is not checked.

## External modules — the module protocol (parts 9d, 9e)

An external module is an HTTP service the worker calls; the contract is
`docs/module-protocol/openapi.yaml`, the design `specs/module-platform/9-external-modules.md`
§3.6–§3.7, and the call path `architecture.md` → External module invocation. Every call carries
the ES256 platform assertion (`Authorization: Bearer`, `htm`/`htu`/`body_sha256` bound to the
exact request, 60 s `exp`), the principal's `Kelta-Access-Token`, and `traceparent` when a span is
current. A module must verify the assertion against `GET /api/modules/.well-known/jwks.json`
before acting.

| Call | When | Request → answer | On failure |
|---|---|---|---|
| `POST /kelta/v1/actions/{key}` | a flow Task whose `Resource` is `<moduleId>:<key>`, or a manifest schedule | `{tenantId, input, config, collection, recordId, record, previousRecord, changedFields, execution{executionId, flowId, stepName, attempt, triggerDepth}}` + `Idempotency-Key` → `{success, output}` or `{success: false, error{code, message}}` | flow error code `ModuleTimeoutException` / `ModuleUnavailableException` (a `Catch` can match either); `success: false` fails the step with `<code>: <message>` |
| `POST /kelta/v1/hooks` | before create/update/delete on a hooked collection | `{tenantId, collection, operation, recordId, record, previousRecord, changedFields}` → `{allowed, fieldUpdates, errors[], conflict}` | `failurePolicy`: `fail` → the write is `503`, `ignore` → the write proceeds unchecked |
| `POST /kelta/v1/services/entitlements/resolve` | `EntitlementService` lookups (watch quota, alert channels) | `{userId}` → `{planCode, subscriptionStatus, values}`, cached per member for `cacheTtlSeconds` | `MemberEntitlements.EMPTY` (restrictive), never cached |
| `{METHOD} /kelta/v1/routes/{path}` | a caller of `/api/modules/{id}/x/{path}` | the caller's method, query, body and `Content-Type` → any status + JSON | `5xx`/timeout → `502 ModuleUnreachable`; unreachable/breaker open → `503` |
| `POST /kelta/v1/webhooks` | a sender POSTing `/api/modules/webhooks/{tenantId}/{moduleId}` | raw bytes + allowlisted headers (`x-`, `stripe-`, `signature`, `digest`, `user-agent`, lower-cased; `Content-Type` as sent) → a status the sender sees | `503` so the sender retries |
| `GET /kelta/v1/health` | `GET /api/modules/{id}/health`, and the 60 s reconcile for a `DEGRADED`/breaker-open install | → `{status, …}` | reported `unreachable` |
| `POST /kelta/v1/events` | after a write to a collection the manifest's `events` subscribes to (`created`/`updated`/`deleted`), asynchronously | `{eventId, type, tenantId, collection, recordId, timestamp, data, previousData, changedFields}` (`payloadOmitted: true` and no `data` when the change was too large to carry) → any 2xx | retried after `kelta.modules.external.event-backoff` (default 5 s, 30 s, 2 min, 10 min, 1 h), then a `module_event_dead_letter` row; never fails the write |
| `GET {ui.bundlePath}` | plan time only (install and upgrade): unsigned, no redirects, at most 5 MiB | → the bundle's bytes, which must hash to `ui.sha256` | the plan is blocked (`UI_BUNDLE_UNAVAILABLE`, `UI_BUNDLE_MISMATCH`) |

What a module receives and must handle:

- **`input`** is the step's resolved data — its `input` object when it has one (a manual or
  HTTP-started flow double-wraps, so `$.input` is the caller's input), else the whole resolved
  data. **`config`** is the step's action config (`{}` when none).
- **No `act` claim on flows and schedules** — there is no request caller there. Routes, and
  hooks on request paths, carry the caller as `act {sub, user_type}` (routes add `profile_id`).
- **Records are the principal's view** (spec §3.4): server-only and `HIDDEN` fields absent,
  `MASKED` fields masked, an unreadable collection `{}`. A hook may only set a declared `VISIBLE`
  field that is neither server-only nor system-maintained, on a collection its principal may
  create in (create) or edit (update); any other `fieldUpdates` key — the audit fields (`id`,
  `createdBy`, …) included — fails the hook.
- **Deduplicate on `Idempotency-Key`.** Flow steps send `<executionId>:<stepName>` (stable across
  retries of one step), schedules `<jobId>:<fireTime>` — and a scheduled job can fire twice
  (`concerns.md` → External modules — invocation).
- **Schedules run in UTC.** A schedule's cron is a 6-field Spring expression evaluated in UTC; a
  changed cron is a new job.
- **Verify the webhook sender yourself.** The platform does not authenticate webhook senders; it
  forwards the bytes the sender signed and returns your status, with no body.
- **Events: at least once, unordered.** One queue-group consumer, `worker-module-events`, reads
  `kelta.record.changed.>` from `KELTA_RECORDS` (no new subject or stream). Deduplicate on
  `eventId`: a retry caused by another subscriber failing redelivers to you as well. Use
  `timestamp` to drop a stale update of one record. `data` and `previousData` are the principal's
  view, like every other call (`concerns.md` → External modules — events and UI bundles).
- **The UI bundle is pinned.** Kelta fetches it once per install or upgrade and serves its own
  copy from `GET /api/modules/{id}/ui-bundle.js`. Changing the file on your host changes nothing
  until an upgrade with a new `ui.sha256`, and the upgrade plan shows the admin the change. Object
  storage (`KELTA_S3_*`) is required for a module that ships a bundle.

## OIDC federation providers (`oidc-providers`)

A tenant's external identity providers are rows of the `oidc-providers` system collection
(`oidc_provider`), edited on the admin OIDC Providers page or through the API. kelta-auth turns
each active row with a client id and a stored secret into an OAuth2 client registration
(`DynamicClientRegistrationRepository`, 5-min cache), offered as an SSO button and via
`idp_hint`; it reads rows over the worker's internal bootstrap endpoints
(`/internal/oidc/providers`, `/internal/oidc/by-issuer`).

**Client secret (write-only).** Callers send `clientSecret`, which is not a field.
`OidcProviderSecretHook` (worker, order -100) encrypts it with the platform `EncryptionService`
into the server-only `clientSecretEnc` and removes the plaintext before any other hook, the
storage adapter or the `record.changed` event sees it. An absent or blank value keeps the stored
secret (the form's "leave blank to keep"); there is no API to clear one. Without
`KELTA_ENCRYPTION_KEY` a save that carries a secret is refused (400) rather than dropped.
kelta-auth decrypts with the same key. The secret is never returned, filterable or logged.

**Discovery.** `OidcDiscoveryHook` (order -50) fetches `<issuer>/.well-known/openid-configuration`
on create, and on update when the issuer changes or the last discovery did not succeed, then
fills the endpoint fields (`jwksUri`, `authorizationUri`, `tokenUri`, `userinfoUri`,
`endSessionUri`) the admin left blank. `discoveryStatus` is `discovered`, `error` (fetch failed,
host does not resolve, or the document names another issuer) or `blocked` (refused by the
outbound URL guard); a failure **never blocks the save** — kelta-auth resolves endpoints itself at
login time (overrides first, then its own discovery), and endpoints can be entered by hand. On an
issuer change, endpoint values still equal to the previous discovery are replaced, or cleared if
the new issuer cannot be discovered. The fetch goes through `OutboundHttpClient` (non-public
addresses refused unless listed in `KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS`), never follows
redirects, and is bounded (3 s connect, 5 s overall, 256 KB). kelta-auth's own login-time
discovery (`OidcDiscoveryService`, 1 h cache) applies the same guard and limits, so a refused or
failed issuer resolves to the stored overrides only — set the allow-list identically on both
services. See [Outbound requests to tenant-supplied URLs](#outbound-requests-to-tenant-supplied-urls).

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
- **Flow mail opt-out** — `EMAIL_ALERT` with `transactional: false` goes through
  `EmailService.queueEmail(..., FlowMailOptions)`: `DefaultEmailService` checks the same
  `email_suppression` list (suppressed ⇒ `email_log.status = SUPPRESSED`, nothing sent) and adds
  `List-Unsubscribe` / `List-Unsubscribe-Post: List-Unsubscribe=One-Click` pointing at
  `/api/track/email-unsubscribe` (`EmailUnsubscribeController`, address token from
  `TrackingTokenService.signAddress`). Transactional flow mail (the default) skips both. Every
  `SmtpEmailProvider` send now stamps `Message-ID` on the From domain and, when a tenant From is
  relayed by the platform SMTP, `Sender: <platform from-address>`. Author doc:
  `docs/authoring/flows.md` → `EMAIL_ALERT`.
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
  `reference_collection_name`, `layout_name`, `field_name`, `picklist_name`,
  `related_collection_name`, `relationship_field_name`, `menu_name`, `parent_label`) so
  cross-tenant/cross-cluster import remaps every reference **by name, never by UUID**.
  A FIELD's `reference_collection_name` resolves against the target tenant's collections
  first, then the platform **system** collections (`users`, …) — those are never in a package
  but are a valid LOOKUP target (every owner field is one). A package `COLLECTION` item
  never resolves to a system row.
  Importers accept v1 packages (minus the new types).
- **Types** (`PackageImportService.supportedTypes()`, applied in this order): `COLLECTION`,
  `GLOBAL_PICKLIST`, `FIELD`, `PICKLIST_VALUE`, `VALIDATION_RULE`, `PAGE_LAYOUT`,
  `LAYOUT_SECTION`, `LAYOUT_FIELD`, `LAYOUT_RELATED_LIST`, `FLOW`, `UI_PAGE`, `UI_MENU`,
  `UI_MENU_ITEM`, `LIST_VIEW`, `DASHBOARD`, then `RECORD` — the optional top-level `records`
  seed section (never exported). The list view / dashboard / records formats are documented in
  `architecture.md` → "Package item types, list views, dashboards and the `records` section".
  Natural keys: related lists are
  `collection:layout:relatedCollection:relationshipField`, menu items are
  `menu:parentLabel:label` (empty parent segment at the top level). A menu item's `parent_id`
  is a *source* id — the importer orders parents first and remaps it through the ids the
  parents got in this import; an unresolvable parent fails that item.
- **Export scope** — `POST /api/packages/export` with no `*Ids` list exports the **whole
  tenant** (`PackageService.exportAllOptions`); `name`/`version` default to the tenant slug and
  `1.0.0`. An explicitly empty list is respected as "none". `kelta metadata export` therefore
  needs no flags.
- **Conflicts** — `conflictMode=skip` (default) leaves an existing item alone; `overwrite`
  updates it in place on its natural key, which is what makes re-applying an edited package
  converge. `kelta metadata apply --conflict skip|overwrite`.
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
- **Authz is never in a package** — the legacy `role`/`policy`/`route_policy`/`field_policy`
  tables were dropped in V47; per-tenant authz is profiles + Cerbos, which a sandbox seeds
  itself via `TenantProvisioningHook`. Nothing authz-shaped is exported, cloned, or promoted.

## Fleet notifications

Slack notifications about automated PRs are posted by the RZWare fleet from its own runtime
(`rzware-ceo`), not from this repo. Kelta itself has no Slack integration beyond the OAuth
credential template (`credential-templates/slack.json`); a flow reaches Slack through
`CALL_API`/`HTTP_CALLOUT` with a stored credential.
