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

1. **Multi-pod NATS config rule.** Never mutate an in-memory registry/cache on one pod
   only. Any change to configuration data (collections, fields, layouts, flows,
   features, domains, credentials) MUST be broadcast via NATS JetStream so every pod
   refreshes. Pattern:
   - Add a `BeforeSaveHook` for the system collection (in `kelta-worker/.../listener/`).
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
kelta-marketing Astro marketing site (not part of platform deploy)
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
| **Collection** | Runtime-defined table (object). **User collection** = tenant business data. **System collection** = platform metadata (collections, fields, flows, profiles…) declared in `SystemCollectionDefinitions`. |
| **Field / FieldType** | A column on a collection; the `FieldType` enum defines the kinds. |
| **Record** | A row in a collection, served as JSON:API. |
| **Flow / action handler** | A state-machine automation (Step-Functions style); an action handler is a Task node's executable unit. |
| **Module (`KeltaModule`)** | Compile-time bundle of action handlers + before-save hooks. |
| **BeforeSaveHook** | Lifecycle hook on a (usually system) collection — the NATS-broadcast trigger point. |
| **Profile / Permission set / Group** | RBAC layers; resolved most-permissive-wins, enforced by Cerbos. |
| **Governor limit** | Per-tenant resource quota (API/day, storage, users, collections…). |
| **Layout / List view / Record type** | UI metadata: page layout · saved list config · collection subtype. |
| **Tenant** | Isolated customer; resolved by URL slug or custom domain; enforced by Postgres RLS via `app.current_tenant_id`. |
| **Cell** | (Planned) tenant-sharding unit; runtime does not yet route by `cell_id`. |
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
| Frontend build | Vite / Vitest | web 5.1/1.3, ui 7.2/4.0 | package.json (npm, Node 18 in CI) |
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
  `CerbosFieldWriteSecurityAdvice`. Add Cerbos policy rules for new field permissions.
  **Data masking** rides the same advice (strip → mask): `MASKED` visibility denies the
  `unmask` + `write` Cerbos actions; mask shape lives in `fieldTypeConfig.masking`;
  masked records carry `meta.maskedFields`. See `architecture.md` → Data masking.
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
- ❌ Map a worker endpoint as `/**` under `/api` → `DynamicCollectionRouter`'s all-variable
  4-segment `@GetMapping` wins (Spring ranks catch-alls **last**) and answers 404 as a record
  read; POST stops at 3 segments so it still works, which reads as a method-specific bug in
  your controller → ✅ spell out depth-1..N patterns, and register the router stand-in in the
  mapping test — testing the controller alone cannot see it (`concerns.md` → Fragile Areas).
- ❌ Add `/api/<new-segment>/**` without a gateway static route → 404. Register in `RouteConfigService.registerStaticRoutes()`.
- ❌ Reuse or skip a Flyway version → check the migration dir for the head number first.
- ❌ Fork a new shared table/filter/form component → ✅ reuse/extend `@kelta/components`.
- ❌ Read a flow input as `$.<key>` → ✅ `$.input.<key>` (manual/MCP/HTTP double-wraps).
- ❌ Reintroduce Kafka → messaging is NATS JetStream only.
- ❌ Add a new `kelta.*` NATS subject without a JetStream stream in `JetStreamInitializer` **and** a native `reflect-config.json` entry for its payload in worker+gateway → ✅ else the publish no-acks (`CancellationException`, event dropped) or the payload serializes `{}` on the native image (`concerns.md` → Dependency Risks).
- ❌ Gate a bean on `@ConditionalOnProperty` and supply that property only as a deployment env var → ✅ declare it in `application.yml` as `${ENV_VAR:default}`. Native images (worker/gateway/auth) freeze condition evaluation at **build** time, so the bean is never registered and the env var arrives too late — silently. This is what left all three services exporting zero traces (`concerns.md` → Dependency Risks).
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
| `kelta.billing.entitlement.changed.<tenantId>.<userId>` | Portal-billing member entitlements changed (consumer-alerting slice 1) — ids and coarse state ONLY, never card data or the resolved entitlement map; KELTA_BILLING stream, broadcast-consumed so every pod evicts its cache. A payload with **no** `userId` means "every member of this tenant" (subject suffix `_all`) and is what a plan edit publishes. Subscription changes are additionally bridged onto `kelta.trigger.<tenantId>.billing.subscription` for tenant flows |
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
`KELTA_BILLING` (`kelta.billing.>`) · `KELTA_AVAILABILITY` (`kelta.availability.>`, 1h).
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
`*IntegrationTest` only under `-Pintegration-tests`. Frontend coverage gate: 80% in
`kelta-web`. **Run `/verify` and get it green before opening a PR.** Full pipeline detail:
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
and frontend lint + typecheck + `format:check` + `test:coverage`. CONTRIBUTING.md documents
the autopilot dispatcher that drives queued tasks.

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
| Adds/changes/removes a feature, or moves it from stub → working | `.claude/docs/status.md` — move the row to the right section (✅/🟡/🔴/⚪) |
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
| `specs/` | **Forward-looking feature specs** (multi-slice efforts). `specs/page-builder-parity.md` is the parent for the page-builder→OutSystems-parity work (per-slice specs in `specs/page-builder/`); `specs/unified-record/README.md` is the parent for collapsing the two drifted record stacks (admin `/resources` + end-user `/app/o`) into one Record Experience (per-slice specs `specs/unified-record/{1..8}-*.md`); `specs/app-surfacing/README.md` is the parent for surfacing built backends in the end-user app (analytics authz, approvals inbox, native dashboards/reports viewer, realtime client, saved views — per-slice specs `specs/app-surfacing/{1..5}-*.md`). `specs/app-data-entry/README.md` is the parent for the Phase-2 data-entry work (server-persisted user preferences, list power pack, grouping, mass edit, kanban/calendar/gallery views — per-slice specs `specs/app-data-entry/{1..7}-*.md`). `specs/app-platform/README.md` is the parent for the Phase-3 app-platform work (conditional visibility, computed variables, apps/workspaces nav v2, builder undo/redo — per-slice specs `specs/app-platform/{1..4}-*.md`). `specs/app-intelligence/README.md` is the parent for the Phase-4 work (offline outbox UI, AI page generation, presence, tenant i18n authoring — per-slice specs `specs/app-intelligence/{1..4}-*.md`). `specs/telehealth/README.md` is the parent for telehealth chat + video (portal identity/external users, human chat over the realtime socket, scheduling + visit links, self-hosted LiveKit video, headless portal auth for external frontends — per-slice specs `specs/telehealth/{1..8}-*.md`). `specs/consumer-alerting/README.md` is the parent for the consumer alerting substrate — portal commerce + availability alerting (portal billing/Stripe + per-member entitlements, flow-log retention, watch/target model, availability matcher + alert fanout, portal self-signup + owner-scoped watch API, Web Push/VAPID, public-traffic hardening — per-slice specs `specs/consumer-alerting/{1..7}-*.md`; the external poller + consumer-frontend contracts live in the parent). `specs/kelta-cli/README.md` is the parent for the cross-platform `kelta` CLI + local MCP (command registry, browser login → PAT profiles, admin command surface, AI ergonomics, stdio MCP bridge to hosted kelta-mcp, Bun-compiled self-updating binaries served from the cluster — per-slice specs `specs/kelta-cli/{1..6}-*.md`). `specs/module-platform/README.md` is the parent for making runtime-installable modules a first-class add-on surface (fail-closed loading, provenance + unified provisioning via `PackageImportService`, manifest v2 with install-consent and upgrade, a `system_permission` catalog making `manifest.permissions` real, module settings via `credential-ref`, module-declared HTTP routes under the already-static `/api/modules/**` prefix, capability gating of `getServices()`) — with the **complete removal of compiled-in billing** as the proving slice (per-slice specs `specs/module-platform/{0..8}-*.md`). Read the relevant slice spec before implementing it. |

Module-level: `kelta-{ai,auth,gateway,mcp,worker}/CLAUDE.md`, `kelta-ui/DESIGN.md`.
Agent-facing CLI guide: `kelta-web/packages/cli/AGENTS.md` (generated — `npm run gen:docs`;
also printed by `kelta docs agent`; machine catalog via `kelta manifest`).
Human-facing: `README.md` (local dev, ports, Makefile), `CONTRIBUTING.md`, `SECURITY.md`.
