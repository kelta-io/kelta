# Kelta Platform — AI Agent Guide

Multi-tenant, metadata-driven enterprise application platform. Collections (tables),
fields, relationships, validation, security, and workflows are all defined and changed
**at runtime** — no redeploy. This file is the entry point for an AI agent writing or
maintaining code here. Deep detail lives in the reference docs indexed at the bottom;
read the relevant one before substantial work in its area.

> Global rules in `~/.claude/CLAUDE.md` also apply (never commit to `main`, branch +
> PR, conventional commits). This file is source-verified against the codebase; if code
> and this doc disagree, **trust the code and fix this doc**.

---

## Agent Operating Procedure (start here)

Follow this loop on every task — without being prompted:

1. **Classify the task** → open the matching recipe in [`.claude/docs/playbooks.md`](.claude/docs/playbooks.md) (flow action handler, worker endpoint, admin UI page, MCP tool, system collection, field type). It lists the exact files + registration steps.
2. **Check reality** → [`.claude/docs/status.md`](.claude/docs/status.md): is the subsystem Complete, a UI-only stub, or already built? Don't rebuild what exists; don't assume a 🔴 stub works end-to-end.
3. **Check hazards** → [`.claude/docs/concerns.md`](.claude/docs/concerns.md): is a file you'll touch fragile or oversized? Plan around it.
4. **Read the local map** → the `CLAUDE.md` of the module you're editing (e.g. `kelta-worker/CLAUDE.md`) + the relevant reference doc.
5. **Plan**, then **implement** following existing patterns (Critical Rules below + `conventions.md`). Don't invent when a pattern exists.
6. **Test** — unit tests for new logic; Playwright e2e for new UI/feature behavior.
7. **Update docs in the same change** — see [Keeping Docs Current](#keeping-docs-current-mandatory-every-pr).
8. **`/verify`** until fully green.
9. **Commit** (conventional) + **`/pr`**.

Then run the **Definition of Done** check (in Task Workflow) before declaring done.

---

## Critical Rules (do not violate)

0. **Generic only.** Every change to this repo is a generic, extensible platform feature
   usable by any tenant. Nothing tenant-specific, customer-specific, or specific to the
   RZWare agent fleet or its tracker tenant ever lands here — no special-cased slugs,
   tables, flags, flows or prompts. Tenant-specific behaviour is expressed as tenant
   metadata (collections, flows, views, approvals) created through the API, CLI or MCP.
   If a tenant needs something the platform cannot express, add the generic capability
   (with the same shape any other tenant would get) and configure it from metadata.
   (RZWare standing rule, 2026-09-13; `rzware-ceo/CHARTER.md` §2.)

1. **Multi-pod NATS config rule.** Never mutate an in-memory registry/cache on one pod
   only. Any change to configuration data (collections, fields, layouts, flows,
   features, domains, credentials) MUST be broadcast via NATS JetStream so every pod
   refreshes. Pattern:
   - Add a `BeforeSaveHook` `@Component` for the system collection (in `kelta-worker/.../listener/`);
     being a bean is what registers it (`playbooks.md` → Add a BeforeSaveHook).
   - In `afterCreate`/`afterUpdate`/`afterDelete`, publish via `PlatformEventPublisher`
     to a `kelta.config.<resource>.changed.<id>` subject.
   - All pods consume it (registered in `NatsSubscriptionConfig`) and refresh local state.
   - **Never** call `lifecycleManager.refreshX()` from a hook *as a substitute* for the
     broadcast — that leaves other pods stale.
   - **Read-after-write exception (#910):** a hook MAY *additionally* call
     `CollectionLifecycleManager.refreshOrInitializeLocally(...)` for same-pod
     consistency — **only when it also publishes the NATS event**.
   Reference impl: `kelta-worker/.../listener/CollectionConfigEventPublisher.java`,
   `FieldConfigEventPublisher.java`.

2. **Persistence is JDBC, not JPA.** This codebase does **not** use Spring Data JPA,
   `@Entity`, or a `BaseEntity` base class for service data. Models are **Java records**;
   repositories use **`JdbcTemplate` with hand-written SQL** (see `kelta-ai/.../repository/`,
   `kelta-worker/.../repository/`). `runtime-core` defines the metadata model as records
   (`CollectionDefinition`, `FieldDefinition`, `FieldType`). User-collection *data* tables
   are created dynamically by `PhysicalTableStorageAdapter`, not mapped entities. Match the
   surrounding repository idiom; do not introduce JPA.

3. **Tenant isolation is mandatory.** Every data path runs under a tenant. Use
   `TenantContext.runWithTenant(...)` / `callWithTenant(...)` (ScopedValue, virtual-thread
   safe). Postgres RLS enforces it via the transaction-scoped `app.current_tenant_id`
   (PgBouncer-safe). Never bypass with raw queries that drop the tenant filter.
   **Cross-tenant work: loop `callWithTenant(<uuid>, …)` per tenant** — that is what
   `SandboxProvisioningService` / `MetadataPromotionService` do. There is no
   "run as platform" helper: the RLS bypass is keyed on an **empty** setting
   (`admin_bypass` is `USING (current_setting('app.current_tenant_id', true) = '')`), which
   `TenantAwareDataSourceConfig` issues only when **no** tenant is bound — so genuine
   platform paths (Flyway, bootstrap) just leave the context unbound. Binding any sentinel
   value matches no policy and silently returns zero rows; `runAsPlatform`/`callAsPlatform`
   did exactly that and were removed (see `concerns.md`).

4. **Never commit to `main`.** Feature branch + PR + `/verify` green before review.

5. **Security tasks are never auto-merged.** See `SECURITY.md`.

6. **Docs are part of the change — keep them current in the SAME PR.** If your change
   alters anything documented here, update the relevant doc in the same commit. A PR that
   changes behavior but not its docs is incomplete. Never let this file or the reference
   docs drift from the code. See **Keeping Docs Current** below for the change→doc map.

---

## Module Map

```
kelta-platform/runtime/
  runtime-core            Core runtime: model, query engine, storage adapters, flow engine,
                          registries, hooks (BeforeSaveHook), TenantContext, validation
  runtime-events          PlatformEvent<T>, PlatformEventPublisher, payload records
  runtime-jsonapi         JSON:API response model
  runtime-module-core     CRUD/flow action handlers (CreateRecord, UpdateRecord, Decision, ...)
  runtime-module-integration  Integration handlers (HTTP callout, email, script SPI, delay)
  runtime-module-schema   Schema lifecycle hooks for system collections
  runtime-messaging-nats  NATS JetStream impl (NatsEventPublisher, subscription manager)
kelta-gateway   Spring Cloud Gateway (WebFlux): auth (JWT+PAT), tenant resolution,
                dynamic routing, Cerbos authz, rate limiting, WebSocket realtime
kelta-auth      Internal OIDC provider (Spring Authorization Server), federation, MFA
kelta-worker    Owns Flyway migrations, CRUD, flow execution, schema lifecycle, integrations
kelta-ai        Anthropic Claude integration (chat, proposals, token tracking); SSE streaming
kelta-mcp       MCP server — kelta-admin + kelta-user toolsets over HTTP, PAT auth, stateless
kelta-web       Frontend SDK monorepo: @kelta/{sdk,components,plugin-sdk,cli,formula}
kelta-ui/app    Admin/builder + end-user UI (React 19 + Vite)
kelta-marketing Astro marketing site (kelta.io) — built as `emf-marketing`; its k8s
                manifests live in `kelta-marketing/k8s/` and the deploy job generates them
                into homelab-argo (`ci-cd.md` → Marketing site)
kelta-modules/  Runtime-installable modules — built, signed and versioned SEPARATELY from the
                platform (no parent pom, NOT in the kelta-platform reactor) and uploaded into a
                tenant via `POST /api/modules/install-jar`. `billing/` is the reference impl.
                See `.claude/docs/playbooks.md` → "Ship a runtime-installable module"
kelta-cli-downloads  nginx image serving self-update CLI binaries + manifest (downloads.kelta.io)
kelta-test-harness  Cross-service integration tests (Testcontainers full mini-stack)
e2e-tests       Playwright end-to-end tests
```

Each backend service has its own `CLAUDE.md` (`kelta-{ai,auth,gateway,mcp,worker}/CLAUDE.md`),
and the `runtime-module-core` / `runtime-module-integration` submodules have one too (flow
action-handler SPI) — each with package layout, idioms, reference-impl tables, and single-test
commands. **Read the module's CLAUDE.md when working inside it.** `kelta-ui/DESIGN.md` is the UI
visual system.

---

## Glossary

| Term | Meaning |
|------|---------|
| **Collection** | Runtime-defined table (object). **User collection** = tenant business data. **System collection** = platform metadata (collections, fields, flows, profiles…) declared in the per-area `<Area>SystemCollections` classes in `runtime-core/.../model/system/`, registered (in order) by `SystemCollectionDefinitions.all()`. |
| **Field / FieldType** | A column on a collection; the `FieldType` enum defines the kinds. |
| **Record** | A row in a collection, served as JSON:API. |
| **Flow / action handler** | A state-machine automation (Step-Functions style); an action handler is a Task node's executable unit. |
| **Module (`KeltaModule`)** | Compile-time bundle of action handlers + before-save hooks. |
| **BeforeSaveHook** | Lifecycle hook on a (usually system) collection — the NATS-broadcast trigger point. |
| **Profile / Permission set / Group** | RBAC layers; resolved most-permissive-wins, enforced by Cerbos. |
| **Governor limit** | Per-tenant resource quota (API/day, storage, users, collections…). |
| **Layout / List view / Record type** | UI metadata: page layout · saved list config · collection subtype. |
| **Tenant** | Isolated customer; resolved by URL slug or custom domain; enforced by Postgres RLS via `app.current_tenant_id`. |
| **Cell** | (Planned) tenant-sharding unit. The `tenant.cell_id` column exists, but nothing reads it — there is no cell resolver and the runtime does not route by `cell_id`. |
| **PAT** | Personal access token (`klt_…`) for programmatic API auth. |

---

## Stack (source-verified)

| Layer | Tech | Version | Source of truth |
|-------|------|---------|-----------------|
| Language | Java | **25** (GraalVM Community 25.0.2) | `.tool-versions`, `kelta-platform/pom.xml` |
| Framework | Spring Boot | **4.0.5** | `kelta-platform/pom.xml` |
| Gateway | Spring Cloud | **2025.1.1** | `kelta-gateway/pom.xml` |
| Build | Maven | 3.9.9 (no wrapper) | CI `JAVA_VERSION`/`MAVEN_VERSION` |
| DB | PostgreSQL | 15 (RLS, per-tenant schemas) | `docker-compose.yml` |
| Cache | Redis | 7 | `docker-compose.yml` |
| Messaging | NATS JetStream | 2.10 | `docker-compose.yml` |
| Authz | Cerbos PDP | 0.40.0 (SDK 0.12.0) | `docker-compose.yml`, poms |
| Search/Audit | PostgreSQL (`tsvector` full-text, `pgvector` semantic, audit tables) | — | `integrations.md` |
| AI | `anthropic-java` SDK | 2.18.0 (model via `AI_DEFAULT_MODEL`) | `kelta-ai/pom.xml`, `application.yml` |
| Frontend | React | 19.2 | `kelta-ui/app/package.json` |
| Frontend build | Vite / Vitest | web 5.1/1.3, ui 7.2/4.0 | package.json (npm, Node 20 in CI — `kelta-ui/app` `engines` requires ≥ 20.19) |
| E2E | Playwright | 1.50 | `e2e-tests/package.json` |
| CLI binaries | Bun (compile-only) | 1.2.19 (pinned in `kelta-cli-downloads/Dockerfile`) | `kelta-web/scripts/build-binaries.mjs` |
| Migrations | Flyway | baseline **V1__baseline** (#1189 flatten); check the migration directory for the current head before adding one (deployed history keeps pre-flatten numbering) | `kelta-worker/.../db/migration/` |

Check the relevant `pom.xml` / `package.json` for exact current versions before pinning.

---

## Coding Patterns (verified — follow these)

- **System-collection change → BeforeSaveHook + NATS broadcast.** See Critical Rule 1.
- **Service models = Java records; repositories = `JdbcTemplate` + raw SQL.** See Critical Rule 2.
- **Flow action handlers** live in `runtime-module-*/.../handlers/`; implement the action
  handler interface, receive action def + flow context, return result/error.
- **Gateway filters** are reactive `WebFilter`/`GlobalFilter`s — **never block**. State is
  passed via exchange attributes. Ordered chain (lower `getOrder()` runs first): custom-domain
  (-310) → tenant-slug extraction (-300) → tenant resolution (-200) → JWT auth (-100) →
  per-tenant rate limit (-50) → Cerbos route authz (0) → header transform for worker (50).
  Full table in `architecture.md`. Don't duplicate tenant/auth logic across filters.
- **Field-Level Security** is enforced read-side by `CerbosFieldSecurityAdvice`
  (`@ControllerAdvice`) — strips denied keys from JSON:API `attributes` **and to-one
  `relationships`**; preserves has-many and system audit fields. Write-side:
  `CerbosFieldWriteSecurityAdvice` — same rule on create and update. Add Cerbos policy rules for new field permissions.
  **Data masking** rides the same advice (strip → mask): `MASKED` visibility denies the
  `unmask` + `write` Cerbos actions; mask shape lives in `fieldTypeConfig.masking`;
  masked records carry `meta.maskedFields`. See `architecture.md` → Data masking.
  **Server-only fields** (`FieldDefinition.withServerOnly(true)` — encrypted secrets, credential
  hashes) are a separate, non-per-viewer guarantee enforced in `DefaultQueryEngine`: never
  serialized, never filterable, only a before-save hook may set one. See `architecture.md`.
- **Flow inputs:** read as `$.input.<key>` against the state envelope; manual/MCP/HTTP
  invocation double-wraps (`{ "input": { ... } }`). See `integrations.md` → Flows.
- **Pagination:** HTTP page size clamps at `MAX_HTTP_PAGE_SIZE = 200`; internal at
  `MAX_PAGE_SIZE = 1000`. JSON:API error envelope shape is owned by `conventions.md`.

---

## Pitfalls — never do these

Each maps to a real mistake an agent has made here. Violating one usually compiles but is wrong.

- ❌ Introduce Spring Data JPA / `@Entity` / `BaseEntity` → ✅ records + `JdbcTemplate` (Rule 2).
- ❌ Update an in-memory registry without the NATS broadcast → ✅ BeforeSaveHook + publish (Rule 1).
- ❌ Block in a gateway filter → ✅ reactive `Mono`/`Flux` only.
- ❌ Call `runWithTenant` on a worker request path → ✅ context is pre-bound by `TenantContextFilter`; just read `TenantContext`.
- ❌ Assume `/api/admin/**` is Cerbos-protected per-resource → it gets only `API_ACCESS`; enforce specific permissions in-controller (`architecture.md` → Authorizing a new endpoint).
- ❌ Register a flow `ActionHandler` as `@Component` → ✅ wire it in the module's `onStartup()` (`playbooks.md`). The `@Component` Javadoc is wrong.
- ❌ Hand-write MCP `server.addTool(...)` → ✅ implement the `AdminTool`/`UserTool` marker; `McpServerConfig` auto-registers.
- ❌ Wire a platform `BeforeSaveHook` as a `@Bean` + `hookRegistry.register(hook)` → ✅ annotate it `@Component` with a
  per-collection-unique `getOrder()`; `BeforeSaveHookRegistrar` registers every hook bean, so a manual call registers it
  twice. Add it to `BeforeSaveHookWiringTest` (`playbooks.md` → Add a BeforeSaveHook).
- ❌ Map a worker endpoint as `/**` under `/api` → `DynamicCollectionRouter`'s all-variable
  4-segment `@GetMapping` wins (Spring ranks catch-alls **last**) and answers 404 as a record
  read; POST stops at 3 segments so it still works, which reads as a method-specific bug in
  your controller → ✅ spell out depth-1..N patterns, and register the router stand-in in the
  mapping test — testing the controller alone cannot see it (`concerns.md` → Fragile Areas).
- ❌ Add `/api/<new-segment>/**` without a gateway static route → 404. Register in `RouteConfigService.registerStaticRoutes()`.
- ❌ Reuse or skip a Flyway version → check the migration dir for the head number first.
- ❌ Fork a new shared table/filter/form component → ✅ reuse/extend the canonical one in `kelta-ui/app` (`AdminDataTable`, `ObjectDataTable`/`ListShell`, `FilterBuilder`, `fieldControl` — `conventions.md` → Component reuse); `@kelta/components`' `DataTable`/`FilterBuilder` are deprecated. Record pages are thin wrappers over `RecordShell` / `RecordForm` / `RecordList` with URLs from `recordRoutes` — add record behavior to the core, never to a page.
- ❌ Read a flow input as `$.<key>` → ✅ `$.input.<key>` (manual/MCP/HTTP double-wraps).
- ❌ Reintroduce Kafka → messaging is NATS JetStream only.
- ❌ Add a new `kelta.*` NATS subject without a JetStream stream in `JetStreamInitializer` **and** a native `reflect-config.json` entry for its payload in worker+gateway → ✅ else the publish no-acks (`CancellationException`, event dropped) or the payload serializes `{}` on the native image (`concerns.md` → Dependency Risks).
- ❌ Gate a bean on `@ConditionalOnProperty` and supply that property only as a deployment env var → ✅ declare it in `application.yml` as `${ENV_VAR:default}`. Native images (worker/gateway/auth) freeze condition evaluation at **build** time, so the bean is never registered and the env var arrives too late — silently. This is what left all three services exporting zero traces (`concerns.md` → Dependency Risks).
- ❌ Put `@ConditionalOnBean` on a bean an auto-configuration supplies (e.g. `EncryptionService`) from your own config or a component → it is always false, because auto-configuration runs last. This disabled kelta-auth federation and its sign-out initiator everywhere → ✅ register unconditionally and resolve with `ObjectProvider.getIfAvailable()` at run time (`concerns.md` → Dependency Risks).
- ❌ Store a secret, ciphertext or credential hash in a plain field → ✅ `.withServerOnly(true)` and read it over JDBC where needed; plain fields are served by every generic read path (`architecture.md` → Server-only fields).
- ❌ Bind a tenant with a ThreadLocal setter → ✅ `TenantContext.set`/`setSlug`/`clear` no longer exist; use `runWithTenant`/`callWithTenant` (or `…IfUnbound` for shared request+background code), and `@WithTenant` in worker tests.
- ❌ Leave docs stale → update them in the same PR (Rule 6).

---

## Messaging — NATS subjects

| Subject | Meaning |
|---------|---------|
| `kelta.config.collection.changed.<tenantId>` | Collection schema changed |
| `kelta.config.field.changed.<tenantId>` | Field changed |
| `kelta.config.layout.changed.<layoutId>` | Page layout changed |
| `kelta.config.page.changed.<pageId>` | UI page (screen builder) changed |
| `kelta.config.flow.changed.<tenantId>` | Flow definition changed |
| `kelta.config.feature.changed.<tenantId>` | System feature toggled |
| `kelta.config.menu.changed.<tenantId>` | Nav menu / menu item changed (apps/nav v2) — pods evict `SystemCollectionCache` for `ui-menus`/`ui-menu-items` |
| `kelta.config.translation.changed.<tenantId>` | Tenant UI translation changed (tenant i18n) — pods evict `SystemCollectionCache` for `ui-translations` |
| `kelta.presence.<tenantId>` | Ephemeral presence deltas (join/leave/heartbeat; KELTA_PRESENCE stream, 1-min retention) — gateway pods merge fleet-wide viewer sets and push `presence.changed` to co-present sockets |
| `kelta.config.domain.changed.<domainId>` | Custom domain changed |
| `kelta.config.credential.changed.*` | Credential changed |
| `kelta.config.api-spec.changed.<tenantId>` | API spec changed |
| `kelta.config.tenant.email.changed.<tenantId>` | Tenant SMTP config changed |
| `kelta.config.tenant.ip-allowlist.changed.<tenantId>` | Tenant IP allowlist (network access) changed |
| `kelta.config.environment.changed.<tenantId>.<envId>` | Sandbox environment created/cloned/refreshed/archived |
| `kelta.config.promotion.executed.<tenantId>.<promotionId>` | Metadata promotion executed/failed/rolled back |
| `kelta.chat.message.<tenantId>.<conversationId>` | Chat message created (telehealth slice 2) — ids/sender/kind ONLY, never the body; gateway `ChatMessageBridge` fans to conversation-joined sockets (membership-checked `chat.join`) as an invalidation signal |
| `kelta.chat.conversation.<tenantId>.<conversationId>` | Chat conversation lifecycle (OPEN\|ASSIGNED\|CLOSED\|ARCHIVED) — ids/state only; same conversation-scoped fanout |
| `kelta.video.session.<tenantId>.<sessionId>` | Video session lifecycle (ACTIVE\|ENDED + durationSeconds, telehealth slice 5) — published by the verified LiveKit webhook; ids/state only. The same envelope is bridged onto `kelta.trigger.<tenantId>.video.session`, so NATS_TRIGGERED flows with topic `video.session` fire (post-visit follow-up; flows read `$.input.payload.*`) |
| `kelta.availability.event.<tenantId>.<source>` | Availability observation from an external poller (consumer-alerting slice 4) — **poller-authored JSON, NOT a `PlatformEvent`** (pollers live outside this repo and should not build a platform envelope), so it adds no native reflect-config surface. KELTA_AVAILABILITY stream (1h maxAge — a stale availability report is worse than none), **queue-group** consumed by `worker-availability` so exactly one pod processes each observation. Deliberately NOT on `kelta.trigger.>`: this is the alert hot path and must not queue behind tenant flows. After fanout a compact ids-and-counts summary is republished to `kelta.trigger.<tenantId>.availability` so tenant flows react *after* members are notified |
| `kelta.record.changed.<tenantId>.<collection>` | Record CRUD (flows, search index, webhooks, realtime, cross-pod system-collection cache eviction). Payload `containsMaskedFields=true` ⇒ realtime bridge omits record data |
| `kelta.trigger.<tenantId>.<topic>` | External flow trigger — starts active `NATS_TRIGGERED` flows whose trigger-config `topic` matches (KELTA_TRIGGERS stream, queue-group consumed; body = arbitrary JSON, not a `PlatformEvent`) |

Envelope: `PlatformEvent<T>` (`eventId`, `eventType`, `tenantId`, `correlationId`, `userId`,
`timestamp`, `payload`). Subscriptions registered in each service's `NatsSubscriptionConfig`.
Kafka is fully removed — do not reintroduce.

**JetStream streams** (provisioned in `runtime-messaging-nats/.../JetStreamInitializer.java`, add-if-absent):
`KELTA_RECORDS` (`kelta.record.changed.>`) · `KELTA_CONFIG` (`kelta.config/cerbos/worker/data.>`) ·
`KELTA_TRIGGERS` (`kelta.trigger.>`) · `KELTA_PRESENCE` (`kelta.presence.>`) ·
`KELTA_VIDEO_SESSION` (`kelta.video.session.>`) · `KELTA_CHAT` (`kelta.chat.message/conversation.>`) ·
`KELTA_AVAILABILITY` (`kelta.availability.>`, 1h). (`KELTA_BILLING` / `kelta.billing.entitlement.changed.*`
was retired with the compiled-in billing on 2026-10-11 — billing is the `kelta-billing` runtime module,
which caches nothing and publishes no NATS; see `integrations.md` → Stripe.)
A new subject namespace needs its **own** `ensureStream(...)` (an existing stream's subjects can't be
extended — it's add-if-absent), and the payload must be in each **native** service's `reflect-config.json`,
or the publish no-acks and the event is dropped / serializes `{}`. See `concerns.md` → Dependency Risks.

---

## Build, Test, Verify

**Build runtime libs first** (required before building any service or IDE run):

```bash
mvn clean install -DskipTests -f kelta-platform/pom.xml \
  -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi,\
runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema,\
runtime/runtime-messaging-nats -am -B
```

| Task | Command / Skill |
|------|-----------------|
| Full pre-PR build + tests (all modules) | `/verify` |
| Java service tests | `/test-java` · or `mvn verify -f kelta-<svc>/pom.xml -B` |
| Integration tests (Testcontainers) | `mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests` |
| Frontend lint/type/test | `/test-frontend` · or in `kelta-web`/`kelta-ui/app`: `npm run lint && npm run typecheck && npm run test:run`. For `kelta-ui/app`, **build the `kelta-web` packages first** (`cd kelta-web && npm run build`) — its `@kelta/*` types resolve through their built `dist/*.d.ts`, so a stale `dist` yields phantom "has no exported member" errors (`concerns.md` → Fragile Areas) |
| Single Java test | `mvn test -f kelta-<svc>/pom.xml -Dtest=ClassName` |
| Run full local stack | `make up` (then `make seed`) — see `README.md` |
| Local stack, low-memory box | `make up-jvm` — JVM images instead of GraalVM native. `make up` builds 3 native images concurrently and needs ~24 GB allocated to Docker, else `cannot allocate memory` |

Java tests: surefire runs `*Test`/`*Tests`/`*Properties` in parallel; failsafe runs
`*IntegrationTest` only under `-Pintegration-tests`. Frontend coverage gate in `kelta-web`:
a ratchet at the measured totals (`vitest.config.ts` → `coverage.thresholds`; raise, never lower). **Run `/verify` and get it green before opening a PR.** Full pipeline detail:
`.claude/docs/ci-cd.md`.

---

## Database Migrations

- Location: `kelta-worker/src/main/resources/db/migration/`
- Naming: `V<n>__<snake_description>.sql`. **Baseline file is V1__baseline (#1189 flatten) — deployed flyway_schema_history retains pre-flatten numbering, so a lower-numbered migration is silently skipped on existing databases.** The directory head moves often; always take head+1.
  Check the directory for the true highest number before creating one — never reuse/skip.
- Flyway runs in the **migrate Job**, not in worker pods (`spring.flyway.enabled=false` in the
  default profile; `application-migrate.yml` re-enables it). Migrations execute under the platform
  sentinel tenant.
- **Collection DDL is deploy-time, not pod-startup.** The same Job (ArgoCD `PreSync` hook,
  `backoffLimit: 0`) also runs `SchemaBootstrapRunner`, which applies every active collection's
  `CREATE TABLE`/reconcile once and **throws on failure** — failing the hook so the worker rollout
  never starts against a half-applied schema. Worker pods only *register* collections at startup
  (`kelta.storage.schema-bootstrap.enabled=false`); N replicas each running the same DDL was
  duplicated work racing itself. Collections created **at runtime** still get their table on the
  pod, via the NATS `collection.changed` path.

---

## Task Workflow

```bash
git checkout main && git pull origin main
git checkout -b <type>/<short-kebab-desc>      # feat|fix|refactor|test|chore|docs
# implement (unit + integration tests for backend; unit + e2e for features)
# update docs in the same change — see "Keeping Docs Current" above
/verify                                          # must be fully green
git commit -m "<type>(<scope>): <description>"   # conventional commits
/pr                                              # creates PR + enables auto-merge (squash)
git checkout main && git pull origin main        # after merge
```

Every feature needs UI configuration + unit tests + e2e (Playwright) coverage **and its
doc updates** (see Keeping Docs Current). Pre-PR the `/verify` skill runs Java build/tests
and frontend lint + typecheck + `format:check` + `test:coverage`. Automated PRs arrive from
the RZWare agent fleet as `rzware-developer[bot]` and are reviewed by
`rzware-reviewer[bot]`; the fleet's runtime lives in `rzware-ceo`, not here.

### Definition of Done (self-check before declaring a task complete)

- [ ] Followed the matching `playbooks.md` recipe + Critical Rules; violated no Pitfall.
- [ ] Unit tests added for new logic; Playwright e2e for new UI/feature behavior.
- [ ] `/verify` fully green (Java build/tests + frontend lint/typecheck/format/coverage).
- [ ] Docs updated in this same change per Keeping Docs Current (incl. `status.md` if a capability moved).
- [ ] Flyway migration numbering sequential (if any added).
- [ ] No stale doc left behind — code and docs agree.

---

## Keeping Docs Current (mandatory, every PR)

Docs are source-verified and must stay that way. Before opening a PR, check whether your
change touches any row below and update that doc **in the same PR**. This is enforced by
the pre-PR checklist — a behavior/config change without its doc update fails review.

| If your change… | Update |
|-----------------|--------|
| Adds/changes/removes a feature, or moves it from stub → working | `.claude/docs/status.md` — keep its row to one line and in the right section (✅/🟡/🔴/⚪); narrative goes in the PR, not the row |
| Bumps a framework/lib/tool version (Java, Spring, React, Vite, Cerbos, etc.) | `CLAUDE.md` Stack table + the affected module `README.md` |
| Adds/renames a module, service, or runtime submodule | `CLAUDE.md` Module Map + module's own `CLAUDE.md`/`README.md` |
| Adds/changes a NATS subject or event payload | `CLAUDE.md` Messaging table + `integrations.md` |
| Changes a coding convention, error envelope, pagination, or component-reuse rule | `conventions.md` |
| Adds/changes an external integration (Cerbos, Svix, Superset, S3, SMTP) or a flow contract | `integrations.md` |
| Changes the gateway filter chain, worker layers, or a data-flow contract | `architecture.md` |
| Adds a new top-level `/api/<x>/**` path or changes endpoint authorization | `architecture.md` → Authorizing a new endpoint (+ gateway static route) |
| Changes a build mechanic (handler SPI, MCP tool registration, route/nav wiring) | `playbooks.md` + the affected module `CLAUDE.md` |
| Changes CI workflows, build/deploy, Harbor/ArgoCD, or the verify steps | `ci-cd.md` (+ `CLAUDE.md` Build/Verify if commands change) |
| Adds a known risk, fragile area, or resolves one | `concerns.md` |
| Adds/changes a test framework, harness, or mocking pattern | `testing.md` |
| Adds a Flyway migration | none (docs say "check the directory for the head") — just keep numbering sequential |
| Changes local-dev setup, ports, Makefile targets, or env vars | `README.md` |

If a doc claim is now wrong, fix it even if it's outside your feature's scope — stale docs
are bugs. When in doubt, trust the code and correct the doc to match.

## Reference Docs (`.claude/docs/`) — read before deep work

| Doc | When to read |
|-----|--------------|
| `playbooks.md` | **Before building anything** — end-to-end recipes (exact files + registration) for handler/endpoint/UI page/MCP tool/system collection/field type |
| `architecture.md` | System map, gateway/worker layers, data-flow contracts (includes, FLS, cron), authorizing a new endpoint, where to add code |
| `conventions.md` | Java/TS naming, import order, JSON:API error envelope + pagination (canonical), UI component-reuse rule, MCP tool style |
| `integrations.md` | External services (Cerbos, Svix, Superset, S3, SMTP), S3 attachment lifecycle, NATS, **Flows input/double-wrap rules** |
| `testing.md` | Test frameworks, integration harness, mocking patterns (JdbcTemplate races, InOrder) |
| `concerns.md` | Known risks, fragile files (don't bloat), tech debt, test gaps, Postgres connection sizing |
| `ci-cd.md` | GitHub Actions pipeline, Harbor/ArgoCD deploy, smoke/rollback, container builds |
| `status.md` | **Capability map: what's Complete vs UI-only-stub vs Planned** — check before touching a subsystem |
| `specs/` | Forward-looking multi-slice specs; each `specs/<name>/README.md` has a status line. Shipped specs live in `specs/_archive/` (see `specs/README.md`). Active: `page-builder-parity.md`, `consumer-alerting/`, `kelta-cli/`, `member-data-ownership/`, `module-platform/`, `support-mailbox/`. Read the relevant slice spec before implementing it. |

Module-level: `kelta-{ai,auth,gateway,mcp,worker}/CLAUDE.md`, `kelta-ui/DESIGN.md`.
Agent-facing CLI guide: `kelta-web/packages/cli/AGENTS.md` (generated — `npm run gen:docs`;
also printed by `kelta docs agent`; machine catalog via `kelta manifest`).
Human-facing: `README.md` (local dev, ports, Makefile), `CONTRIBUTING.md`, `SECURITY.md`.
