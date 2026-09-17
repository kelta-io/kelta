# Playbooks — end-to-end recipes

Step-by-step file-lists for the common tasks. Each recipe is the **exact set of files to
create/edit, in order**, plus the non-obvious registration steps that an agent would
otherwise discover only by reading source. Every class/path here is verified against the
codebase — but verify it still exists before relying on it (code wins over docs).

Before starting any recipe: check [`status.md`](status.md) (is the subsystem real / already
built?) and [`concerns.md`](concerns.md) (is a file you'll touch fragile?).

Index: [Flow action handler](#1-add-a-flow-action-handler) ·
[Worker REST endpoint](#2-add-a-worker-rest-endpoint) ·
[Admin UI page](#3-add-an-admin-ui-page) ·
[MCP tool](#4-add-an-mcp-tool) ·
[System collection](#5-add-a-system-collection) ·
[Field type](#6-add-a-field-type)

---

## 1. Add a flow action handler

A handler that runs as a Task state in the Visual Flow Builder (e.g. `SendSlackMessage`).

**Backend**
1. Create `kelta-platform/runtime/runtime-module-integration/src/main/java/io/kelta/runtime/module/integration/handlers/<Name>ActionHandler.java` implementing `io.kelta.runtime.workflow.ActionHandler`:
   - `String getActionTypeKey()` → the action type (e.g. `"SEND_SLACK_MESSAGE"`).
   - `ActionResult execute(ActionContext context)` → read **static node config** from `context.actionConfigJson()` (parse with `ObjectMapper`) and **runtime-resolved values** from `context.resolvedData()` (the `$.input.*` envelope feeds this); do the work; return `ActionResult.success(map)` / `failure(...)`.
   - optional `validate(...)` and `getDescriptor()`.
   - Collaborators are **constructed** (not field-injected) and pulled from `ModuleContext.getExtension(Type.class)` in the module. ⚠️ `RestTemplate` is **not** a registered extension — default it (`new RestTemplate()`) if null. Resolve secret refs (webhook URLs/keys) via the `CredentialResolverPort` extension. See `runtime-module-integration/CLAUDE.md` → Handler collaborators & config.
2. **Register it** (the step no agent guesses): in `runtime-module-integration/.../IntegrationModule.java`, construct your handler in `onStartup(ModuleContext)` and return it from `getActionHandlers()`. The module is wired as a `@Bean` in `kelta-worker/.../config/FlowConfig.java` (`integrationModule()`); `ModuleRegistry` calls `onStartup` then registers handlers into `ActionHandlerRegistry`.
   - ⚠️ Handlers are **NOT** `@Component`-scanned. The `ActionHandler` Javadoc that says "implement as a `@Component`" is **wrong** — a `@Component` handler silently never registers.
   - ⚠️ The `workflow_action_type` DB table is a **display catalog only**; its `handler_class` column is never read by Java. You do **not** need a migration for the handler to execute.
   - Reference impl: `handlers/HttpCalloutActionHandler.java`. (Pure data/CRUD handlers go in `runtime-module-core` via `CoreActionsModule` instead.)

**Frontend — make it usable in the builder** (the flow builder is hardcoded TS, not descriptor-driven):
3. Add the action to `RESOURCE_GROUPS` in `kelta-ui/app/src/pages/FlowDesignerPage/types.ts`.
4. Write a `<Name>Params.tsx` config form in `kelta-ui/app/src/pages/FlowDesignerPage/components/properties/` (props: `{ parameters, onUpdate }`; ref `HttpCalloutParams.tsx`) and wire it into `TaskProperties.tsx` (switched on `resource`).
   - ⚠️ The `ActionHandlerDescriptor` SPI exists but the admin UI does **not** consume it for built-in handlers — the palette + params are hand-written. Skipping this step ships a handler that never appears in the builder.

**Tests**: `<Name>ActionHandlerTest.java` co-located (JUnit 5 + Mockito, MockWebServer for outbound HTTP) + a Playwright e2e exercising the node.
**Docs**: bump the handler count + list in [`status.md`](status.md) (Visual Flow Builder row); [`integrations.md`](integrations.md) if it's a new external dependency.

---

## 2. Add a worker REST endpoint

A new `GET/POST /api/...` served by the worker, tenant-scoped, optionally permission-gated.

**Files**
1. Controller in `kelta-worker/.../controller/` — thin, delegates to a service. (Ref: `ModuleController.java`; test ref: `scim/controller/ScimUserControllerTest.java`.)
2. Service in `kelta-worker/.../service/`.
3. Repository in `kelta-worker/.../repository/` — `@Repository` using `JdbcTemplate` + raw SQL (**not** JPA; models are records). Ref: `repository/ApprovalRepository.java`.

**Tenant scoping** (don't reinvent it):
- Tenant context is **already bound** per request by `kelta-worker/.../filter/TenantContextFilter.java` from the gateway's `X-Tenant-ID` / `X-Tenant-Slug` headers. In the controller, **read** the current tenant from `TenantContext` (or accept `@RequestHeader("X-Tenant-ID")`). Do **not** call `TenantContext.runWithTenant(...)` on a request path — that's for background/cross-tenant jobs. Postgres RLS then scopes every query automatically.

**Routing & authorization** (the part docs used to get backwards):
- If your path is under an **existing** top-level segment (e.g. `/api/admin/...`), the gateway already routes it.
- If it introduces a **new** top-level segment (`/api/<x>/**`), add a static-route row in `kelta-gateway/.../service/RouteConfigService.registerStaticRoutes()` (and `config/RouteInitializer.registerStaticRoutes()`) with id `static-<x>` — otherwise the gateway returns **404** before reaching the worker.
- **Authorization reality**: `static-*` routes (`/api/admin/**`, `/api/me/**`, etc.) are **skipped** by the gateway's collection-level Cerbos check (`RouteAuthorizationFilter` skips ids starting `static-`) — they get only the blanket `API_ACCESS` system-permission check. The worker's `CerbosRecordAuthorizationAdvice` and FLS advices **exclude** `/api/admin/`. So a new admin endpoint is, by default, reachable by **any** authenticated user with API access. To enforce a *specific* permission, **check it inside the controller/service**: inject `CerbosPermissionResolver` for identity (`getProfileId`/`getEmail`/`getTenantId` — the gateway forwards `X-User-Profile-Id`/`X-User-Email`/`X-Cerbos-Scope` on every request incl. admin) and check `profile_system_permission` like `SupersetGuestTokenService.hasSystemPermission(profileId, name)`. Full detail: `architecture.md` → Authorizing a new endpoint. Don't assume "it's under /api/admin so it's protected."

**Response contract**: build success with `JsonApiResponseBuilder` (`runtime-jsonapi`); errors flow through the runtime-core router `GlobalExceptionHandler` into the JSON:API error envelope (`code` = `UPPER_SNAKE_CASE`) — see [`conventions.md`](conventions.md) (canonical). Pagination: `page[number]`/`page[size]`, default 20, clamp `MAX_HTTP_PAGE_SIZE = 200` via `Pagination.fromParams`.

**Tests**: controller test (mirror `ScimUserControllerTest`) + service/repository tests.
**Docs**: [`architecture.md`](architecture.md) if it adds a data flow; "Keeping Docs Current" if a new top-level path (gateway static route).

---

## 3. Add an admin UI page

**Files & wiring**
1. Page component in `kelta-ui/app/src/pages/<Name>/`.
2. **Register the route** (no central router config beyond this): in `kelta-ui/app/src/App.tsx`, add a `React.lazy(...)` import and a `<Route>` inside the tenant routes, wrapping the element in `<AdminPageRoute requiredPolicies={[...]}>` (applies `ProtectedRoute` + policy guards) and/or `<RequirePermission permission="VIEW_SETUP">`. ⚠️ Different prop shapes: `AdminPageRoute` takes `requiredPolicies?: string[]`, `RequirePermission` takes `permission: string`.
3. **Nav/menu**: there is **no sidebar-config array** and `Header` has no nav list. Surface the page by adding an entry to the `defaultCommands` array in `kelta-ui/app/src/components/SearchModal/SearchModal.tsx` — shape `{ id, type: 'page', title, subtitle, path }` (the `Cmd/Ctrl+K` palette).

**Data fetching**
- Collection data: use the existing TanStack Query hooks (`useCollectionRecords`, etc.).
- **Non-collection / admin endpoints**: there is no generated hook — call `apiClient.get('/api/admin/...')` (`kelta-ui/app/src/services/apiClient.ts`) inside a `useQuery`. `apiClient` only auto-unwraps standard `/api/{collection}` JSON:API; admin controllers often return a `single(...)` envelope with rows under `attributes` — unwrap manually.

**Styling**: follow `kelta-ui/DESIGN.md` (uppercase-11px field labels, token colors not hex, Lucide icons, em-dash empty states). **Reuse `@kelta/components`** — never fork a new table/filter/form (see [`conventions.md`](conventions.md)).
**Tests**: Vitest + Testing Library + MSW (unit) and a Playwright e2e in `e2e-tests/`.
**Docs**: [`kelta-ui/README.md`](../../kelta-ui/README.md) routes table; [`status.md`](status.md) if it's a new capability.

---

## 3b. Add a page-builder widget / inspector field kind

The page builder (`kelta-ui/app/src/pages/PageBuilderPage/`) is **descriptor-driven** (slices 2a/2b). The
palette, inspector, canvas chip, editor preview, and runtime renderer are all single loops over the widget
registry — never add a per-type `if (node.type === …)` branch anywhere.

**Add a new widget**
1. Create a `WidgetDescriptor` in `widgets/builtins/<group>.tsx` (`{ type, label, icon, category, defaultProps, propSchema, acceptsChildren?, supportedEvents?, Render }`) and add it to that file's exported array (registered via `widgets/builtins/index.ts`).
2. The palette, inspector, and both renderers pick it up automatically. `category` controls its palette section; `propSchema` controls its inspector fields; `Render` is the one function used by both the editor preview and the runtime.

**Typed-input sub-pattern** (slice 2f — a `category:'input'` widget bound to a `{collection, field}`)
- The `Render` reads `{collection, field}` and resolves the field's `FieldType` via `useFieldDef` (wraps `useCollectionSchema`), then maps the type to a control (`widgets/builtins/inputs/*` — `text-input`/`number-input`/`checkbox`/`dropdown`/`datepicker`/`lookup`/`multi-picklist`/`rich-text`). The `field-picker` prop carries a `fieldTypeFilter` so the inspector lists only compatible fields.
- For **picklist/multi-picklist** options use the shared `usePicklistOptions` hook (FIELD vs GLOBAL source via `fieldTypeConfig.globalPicklistId`, hitting `GET /api/picklist-values?filter[…]`); for **lookups** use `useLookupOptions` (→ `useLookupDisplayMap`). Never re-implement the picklist source resolution — reuse the hook.
- **Reuse** `LookupSelect`/`MultiPicklistSelect`/`RichTextEditor` — never re-implement a typed control. A `{$bind}` default value arrives **already resolved** at `Render` (resolved-node invariant) — do not call `resolveBindings`.
- For the **`form` widget**, prefer extending `@kelta/components` `ResourceForm` via `setComponentRegistry` (`registerFormFieldRenderers.ts`) over forking it — that upgrades its picklist/lookup/multi/rich-text fields to the same rich controls.
- **HTML-bearing output** (`rich_text` display, a bound HTML value) MUST pass the **same** sanitizer the `FieldRenderer` `rich_text` path uses (render via `<FieldRenderer type="rich_text">`, which strips tags) — **never** `dangerouslySetInnerHTML` on unsanitized bound HTML.

**Add a new inspector field kind** (when no existing `PropFieldKind` fits a prop)
1. Add the kind to `PropFieldKind` in `widgets/types.ts`.
2. Create `inspector/fields/<Kind>Field.tsx` implementing `FieldEditorProps` (from `inspector/fields/types.ts`) with its `onChange` **value-write contract** (literal editors write a scalar; export it from `inspector/fields/index.ts`).
3. Add one `case` to the kind→editor map in `inspector/Inspector.tsx` (the single place that knows the mapping).
4. If the prop should support the `fx` literal↔expression toggle, mark its schema field `bindable: true` — `Inspector` auto-wraps it in `BindableField` (which writes `{ $bind, mode:'expr' }` in expr mode).
5. Add a Vitest block to `inspector/fields/fields.test.tsx`.
6. **Localize every visible string** via `useI18n`/`t('builder.*')` (group/field labels, hints, category headers) — never hardcode English; `data-testid`s stay untranslated.

> Authoring an `event-list`/`expression` value only **writes** the model. The runtime that consumes it is
> slice 2e (action runtime) / 2d (binding resolution) — do not add execution/resolution to the editor.

**Tests**: Vitest for the field write-contract + an `Inspector`/`Palette` render test.
**Docs**: [`status.md`](status.md) page-builder row if it's a new capability; [`conventions.md`](conventions.md) if it changes the inspector convention.

---

## 4. Add an MCP tool

Expose a platform operation to MCP clients (kelta-admin or kelta-user toolset).

**Files**
1. Tool class in `kelta-mcp/.../tool/admin/` (admin) or `tool/user/` (data). Implement the marker interface **`AdminTool`** or **`UserTool`** as a `@Component`:
   - `toSpecification()` returns `io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification`.
   - Build the input schema with `Schemas`; annotate behavior with `ToolHints`.
   - In the call handler, translate **friendly camelCase args → native JSON:API body** at the tool boundary (see [`conventions.md`](conventions.md) → MCP tools), then forward via `GatewayHttpClient` (`http://emf-gateway:80`); shape the JSON:API response into MCP content; map gateway 4xx/5xx via `McpErrorMapper` (redacts `klt_`).
2. **Registration is automatic**: Spring autowires `List<AdminTool>` / `List<UserTool>` and `kelta-mcp/.../config/McpServerConfig.java` registers them. Do **NOT** hand-write `server.addTool(...)`.
   - ⚠️ The old "`server.addTool(new Tool().toSpecification())` + `McpServerFeatures`" pattern is wrong — it won't compile/register.

**Endpoint map**: e.g. `create_validation_rule` → `POST /api/validationRules` (type `validationRules`); pass `collectionName` through verbatim (worker resolves it) — don't pre-resolve names unless the existing tools do.
**Tests**: `<Name>ToolTest.java` asserting the on-the-wire body with WireMock JSON-path matchers (every sibling tool has one).

---

## 5. Add a system collection

Platform-managed metadata (not a user collection) — e.g. `feature_announcements`.

1. **Declare it**: in `runtime-core/.../model/system/SystemCollectionDefinitions.java`, add a `<name>()` factory using the private `systemBuilder(name, displayName, tableName)` helper (it injects audit fields + `systemCollection(true)` + the physical table), then add `definitions.add(<name>())` to `all()`.
2. **Create the table** (the worker does **NOT** auto-create system tables — Flyway owns them):
   - Ship `kelta-worker/src/main/resources/db/migration/V<next>__create_<table>.sql` (check the dir for the head number; currently V142 → V143).
   - Add the table to the RLS-enable migration set (the V77-style migration hardcodes table names) so RLS applies.
3. **Multi-pod refresh**: register a `<Name>RefreshHook` as a `@Bean` in `kelta-worker/.../config/FlowConfig.java` via `hookRegistry.register(hook)`. The hook implements `BeforeSaveHook`, and in `afterCreate/Update/Delete` publishes via `PlatformEventPublisher` to `kelta.config.collection.changed.<id>` (reuse `CollectionConfigEventPublisher.SUBJECT_PREFIX`, build a `CollectionChangedPayload` via `EventFactory`). All pods consume it via `NatsSubscriptionConfig` → `CollectionLifecycleManager` refreshes the `CollectionRegistry`. See [Critical Rule 1](../../CLAUDE.md).
   - Reference impl: `listener/ValidationRuleRefreshHook.java`, `listener/CollectionConfigEventPublisher.java`.
   - ⚠️ The reference `ValidationRuleRefreshHook.afterDelete` is a **no-op** (the `collectionId` isn't available on delete). If your collection needs delete to refresh other pods, handle it explicitly rather than copying the no-op.
**Tests**: registry/refresh unit test + migration applied against a non-empty DB.
**Docs**: [`status.md`](status.md); [`concerns.md`](concerns.md) notes `SystemCollectionDefinitions.java` is already 1,400+ lines — keep additions minimal.

> **This recipe is for platform-owned metadata only.** A collection that belongs to one tenant's
> installed module is declared in that module's `kelta-module.json` instead — see recipe 5b. It
> needs no code in this repo, no Flyway migration, and no redeploy.

---

## 5b. Ship a runtime-installable module (collections + hooks + handlers)

A module is one signed JAR + `kelta-module.json`, uploaded through `POST /api/modules/install-jar`
and installed **per tenant at runtime**. Nothing in this repo is recompiled or redeployed.

**Reference implementation: [`kelta-modules/billing/`](../../kelta-modules/billing/)** — collections,
two action handlers, a webhook handler, and a UI bundle in one JAR. Copy its `pom.xml` shape: no
parent pom, outside the reactor, platform deps `provided` (they load from the platform
classloader; bundling them puts a second copy of the runtime model in the JAR and every cast to a
platform type fails).

1. **Implement `KeltaModule`** (`runtime-core/.../workflow/module/KeltaModule.java`) with a public
   no-arg constructor — `RuntimeModuleManager.loadFromJar` instantiates it reflectively. Build
   handlers in `onStartup(ModuleContext)`, not the constructor: the context does not exist yet at
   construction. `context.getExtension(CredentialResolverPort.class)` is how a module reaches the
   credential vault — the only supported way to hold a secret.
   - `getActionHandlers()` → flow steps, registered tenant-scoped in `ActionHandlerRegistry`.
   - `getBeforeSaveHooks()` → registered tenant-scoped in `BeforeSaveHookRegistry`, so they fire
     only on the installing tenant's records. They run **before** global hooks for the same
     collection; `"*"` still means every collection (for that tenant only).
   - `getServices()` → `Map<Class<?>, Object>` of **platform-defined port → your implementation**,
     registered tenant-scoped in `ModuleServiceRegistry`. This is the only way platform code can
     ask a module a question *inline*; handlers and hooks are dispatch-only, and no Spring bean can
     reach through `SandboxedModuleClassLoader` on its own. Platform callers resolve
     `moduleServiceRegistry.find(tenantId, SomePort.class)` **per call** and fall back to their
     compiled-in behaviour when it is empty — so publishing nothing changes nothing.
     - The key must be an interface **the platform defines**, under a package in the classloader's
       parent allowlist (`io.kelta.runtime.module.` and friends). Bundling your own copy of it is
       rejected at registration rather than failing later as a `ClassCastException`.
     - Two modules cannot publish the same port for one tenant; the second load is refused and
       falls back to stubs. A refusal withdraws that module's already-accepted ports.
     - **Never cache the instance platform-side** — unload closes the module's ClassLoader.
2. **Declare collections in the manifest**, not in code:
   ```json
   "collections": [
     { "name": "invoices", "displayName": "Invoices",
       "fields": [ { "name": "reference", "type": "STRING", "required": true } ] }
   ]
   ```
   `ModuleCollectionProvisioner` creates them at install via `queryEngine.create` on the
   `collections`/`fields` system collections — the standard admin write path, so the NATS config
   broadcast and lifecycle init (including the physical table) fire on every pod. Collection names
   must match `^[a-z][a-z0-9_]*$`; field names cannot be the platform's reserved ones
   (`id`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `tenantId`).
3. **Accept webhooks** by naming one of your action handlers in `"webhookHandlerKey"`. External
   systems then POST to `/api/modules/webhooks/{tenantId}/{moduleId}` — one generic,
   platform-owned route (`ModuleWebhookController`), since a module cannot contribute a
   `@RestController` of its own.
   - **The platform verifies nothing.** The path is unauthenticated (an external sender has no
     platform JWT), so **your handler owns the trust anchor** — resolve your credential and check
     the signature before treating the payload as real.
   - The handler receives `resolvedData` = `{rawBody, headers, moduleId}` and `context.tenantId()`.
     Use `rawBody` verbatim for any HMAC: re-serializing changes the bytes the signature covers.
     `headers` is lower-cased and limited to signature-bearing prefixes.
   - Return `ActionResult.failure(...)` to reject (→ 401). Nothing-to-dispatch-to (unknown tenant,
     inactive module, no declared handler) uniformly returns 404 so a caller cannot enumerate a
     tenant's modules.
4. **Ship UI** by putting a browser bundle in the same JAR and naming it in `"uiBundlePath"`
   (e.g. `static/ui-bundle.js`). The admin UI fetches it from
   `GET /api/modules/{moduleId}/ui-bundle.js` and evaluates it; its top-level code calls
   `ComponentRegistry.registerPageComponent(...)` from `@kelta/plugin-sdk`, which makes the
   component available to the Page Builder.
   - ⚠️ **Same-origin, signature-gated.** The bundle runs with the admin session's full DOM and
     cookie access — the JAR signature is the entire trust model, there is no browser isolation.
   - Loading is opt-in via `<PluginProvider loadModuleBundles>` and enabled only by `App.tsx`;
     shared test wrappers keep the default so they issue no extra `GET /api/modules`.
   - The bundle is evaluated from a blob URL, so it has **no module graph** and cannot `import`
     a bare specifier like `@kelta/plugin-sdk`. The host publishes the registry at
     `globalThis.__keltaComponentRegistry` — that global is the only channel available.
5. **Classpath**: the module's own classes must be in the JAR. Only the prefixes in
   `SandboxedModuleClassLoader.ALLOWED_PARENT_PREFIXES` resolve from the platform.

**Installing onto a tenant that already has data — order matters.** `POST /api/modules/install-jar`
**loads the module immediately**: handlers, hooks and `getServices()` all register at *install*
time, on every pod. The `status` field stays `INSTALLED` until you call `/enable` — that flag is
bookkeeping, **not** a gate on whether the code is live. So a module that publishes a service starts
answering platform calls the moment the JAR lands.

That inverts the obvious sequence. If the module resolves something the platform already answers
(entitlements being the built case), install it and its collections are empty, so the platform
starts getting empty answers *before* you can migrate anything into them. On the billing pilot that
would have meant a tenant's members resolving to no plan — and, because `AlertDispatchService`
treats an empty channel entitlement as "this tenant isn't gating channels", **alert channel limits
would have silently stopped applying**, including paid SMS for free-tier members.

Populate first, install second:

1. Create the collections through the normal admin API (same names and fields as the manifest).
   `ModuleCollectionProvisioner` **skips collections that already exist**, so the later install
   will not clobber them.
2. Migrate the data the module will answer from, and verify it.
3. Install + enable, then confirm the module — not the fallback — is answering.

**When a module fails to load, it fails closed.** A JAR that does not verify (signature, checksum,
classloading, or a throwing `onStartup`) does **not** fall back to working-looking stubs any more. Its
declared action keys are registered as *quarantined* handlers that throw `ModuleUnavailableException`,
and the module reports `QUARANTINED` with the reason in `tenant_module.last_error`. So:

- A flow step calling it fails with a specific, attributable error — catchable as
  `ModuleUnavailableException` — rather than `ResourceNotFound` (which reads as a mistyped key) or,
  as before, *succeeding*.
- A **manifest-only** module (no JAR, so no implementation) quarantines for the same reason. Declaring
  a module before its code exists is fine; expecting its actions to do anything is not.
- Stub mode still exists for dev as `kelta.modules.stub-mode=true`, reports `STUB` rather than
  `ACTIVE`, and is never reached by falling back from an error.
- `GET /api/modules/{id}/health` reports whether the module is loaded **on the pod that answered**,
  which is not the same as the stored status: loading is per-pod and driven by a fire-and-forget NATS
  event.

**What the platform does for a module, so it doesn't have to:**
- **Binds the tenant** around a webhook dispatch. A module *cannot* — `io.kelta.runtime.context`
  is outside the classloader allowlist — and unbound, its reads and writes run under the
  `admin_bypass` RLS policy across tenants. Flow and CRUD paths already bind upstream.

**Known limits** — these still need a platform change, so design around them:
- **No scheduler.** A module is not a Spring bean, so no `@Scheduled`. Expose the work as an
  `ActionHandler` and let a tenant drive it from a scheduled flow (see `billing:expire-passes`) —
  more configurable than a hardcoded cron, and needs no new platform capability.
- **No request context.** Neither `jakarta.servlet` nor `org.springframework` is on the classpath,
  so a hook cannot read request headers (e.g. the gateway's `X-User-Type` actor tier). Design
  around the record and `tenantId` it is given.
- **No NATS.** A module cannot subscribe, so it cannot hold a cache that needs fleet-wide
  invalidation. Prefer resolving from collections over caching stale config.
- **No transactions.** A module writes through `QueryEngine` and cannot open one or use
  `FOR UPDATE SKIP LOCKED`. Design writes to converge on replay rather than relying on a claim.
- **No new `@RestController`.** Spring MVC resolves routes at context start. A module's HTTP
  surface rides existing dynamic routes: flows (`execute_flow`), collection CRUD, and the generic
  webhook route above.
- **No new `CredentialType`.** `CredentialTypeRegistry` is built once at boot from a Spring
  `List<CredentialType>`.
- **No DDL or Flyway.** By design — a module gets exactly the schema powers the admin API exposes.
- **Uninstall does not drop collections or data**, and reinstall/upgrade **skips** a collection
  whose name already exists rather than updating it.

**Tests**: `RuntimeModuleManagerHookTest` and `ModuleWebhookDispatchTest` load a real JAR through
the real sandboxed classloader — copy that shape rather than mocking the module instance, since
hook registration and handler resolution only happen inside `loadFromJar`.
`ModuleCollectionProvisionerTest` covers manifest → collection creation;
`ModuleWebhookControllerTest` covers the HTTP status contract.
**Docs**: [`status.md`](status.md) (Extensibility / modules row); adding an unauthenticated path
also means updating `kelta-gateway` `application.yml` (`unauthenticated-paths`),
`IpRateLimitFilter.DEFAULT_IP_PATHS`, and `PublicSurfaceTest`'s expected allowlist.

---

## 6. Add a field type

1. Add the constant to the `FieldType` enum (`runtime-core/.../model/FieldType.java`).
2. Map it to a Postgres column in the storage layer (`PhysicalTableStorageAdapter` / `SchemaMigrationEngine`) — pick the SQL type, nullability, and any companion column (e.g. currency has a currency-code column).
3. Add validation handling if the type has constraints (`runtime-core/.../validation/`).
4. Frontend: add a renderer/editor in the `@kelta/components` `FieldRenderer` family (reuse, don't fork) and any admin field-config UI.
5. MCP: add the friendly alias → enum mapping in `FieldBodyBuilder.resolveNativeType` (kelta-mcp) so `add_field`/`create_collection` accept it.
**Tests**: storage round-trip + validation unit tests; component test for the renderer.
**Docs**: [`status.md`](status.md) (Dynamic schema engine row).

---

## 7. Add a conversation-scoped realtime event family

For socket events that must reach only an authorized subset of a tenant's sessions (chat is
the reference; per-record channels would follow the same shape). The tenant-wide
`record.changed` broadcast is NOT this — use it only when every tenant subscriber may see
the event.

1. **Payload + subject** (worker): a payload class in `runtime-events` carrying **ids/state
   only — never content**; publish from a `BeforeSaveHook` `afterCreate/afterUpdate` via
   `PlatformEventPublisher` to `kelta.<domain>.<kind>.<tenantId>.<scopeId>`
   (reference: `listener/ChatMessageHook.java`). Register the hook as a `@Bean` in
   `FlowConfig` via `hookRegistry.register(hook)`. Add the subject to the CLAUDE.md
   Messaging table.
2. **Membership check** (worker): an `/internal/<domain>/...` endpoint answering "may this
   user join this scope?" (reference: `controller/InternalChatController.java` — tenant as
   an explicit param, matches user id OR email).
3. **Gateway join actions**: extend `RealtimeWebSocketHandler.handleMessage` with
   `<domain>.join`/`<domain>.leave`; verify via a reactive, fail-closed, ~30s-cached
   `WebClient` client (reference: `websocket/ChatMembershipClient.java` — NEVER block the
   event loop; apply the result in `.subscribe()`), then track the session in a dedicated
   routing index on `SubscriptionManager` with its own per-session cap.
4. **Bridge**: a `listener/<Domain>Bridge` consuming the subject family (BROADCAST
   subscription in gateway `NatsSubscriptionConfig` — every pod fans to its own sockets)
   and fanning to `getConversationSubscribers`-style lookups only
   (reference: `listener/ChatMessageBridge.java`).
5. **Client rule**: events are invalidation signals; content is refetched over the
   authorized HTTP path (`conventions.md` → Conversation-scoped socket events).

**Tests**: hook publish + validation unit tests; routing-index + bridge fanout gateway
tests; a harness scenario over the real HTTP path.
**Docs**: CLAUDE.md Messaging table, `architecture.md`, `conventions.md`, [`status.md`](status.md).

---

These recipes assume the conventions in [`conventions.md`](conventions.md) and the rules in
[`../../CLAUDE.md`](../../CLAUDE.md). When you complete one, update the docs it names — that's
how the next agent gets a correct recipe.

## 8. Enforce row-level security in a production database

The control plane's RLS policies only apply to roles **without** `BYPASSRLS` and only when
`app.current_tenant_id` is set (`''` = platform session, `<tenant id>` = that tenant). The
worker binds the setting per operation (`TenantAwareDataSource`); kelta-auth and kelta-ai
set `''` on every pooled connection (`hikari.connection-init-sql`). The database role must
still be told to obey the policies — a one-time, reversible operational step.

Preconditions: the image that carries `V200__rls_system_rows_readable.sql` has deployed and
the migrate Job ran (`SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC
LIMIT 1` ≥ 200); kelta-auth is on a build with the `connection-init-sql` line.

```sql
-- as the postgres superuser, on the control-plane database
ALTER ROLE emf SET app.current_tenant_id = '';   -- new sessions default to the platform session
ALTER ROLE emf NOBYPASSRLS;                       -- evaluated per statement: takes effect at once
SELECT rolbypassrls FROM pg_roles WHERE rolname = 'emf';   -- f
```

The `ALTER ROLE … SET` covers pooled connections opened before this change only after they
recycle (Hikari `max-lifetime`, 30 min); restart kelta-auth right after the flip so no
connection is left with a NULL setting (`kubectl -n emf rollout restart deploy/emf-auth`).
kelta-worker and kelta-ai set the value themselves on every borrow and need no restart.

Verify, as a tenant admin PAT: a dashboard `metric` on `users` returns that tenant's count;
`GET /api/pages/<slug>/render` returns that tenant's page; `kelta collections list` still
shows system collections; login works; `kelta sandbox create` still provisions (the sandbox
flow switches `TenantContext` per tenant). Watch the worker log for
`violates row-level security policy` — that is a code path writing another tenant's rows
under a tenant context, which the policy now refuses.

Rollback (instant, no restart): `ALTER ROLE emf BYPASSRLS;`.
