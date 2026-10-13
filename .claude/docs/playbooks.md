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
[BeforeSaveHook](#5a-add-a-beforesavehook) ·
[Field type](#6-add-a-field-type) ·
[External portal: member's own profile](#9-let-an-external-portal-edit-the-signed-in-members-own-profile) ·
[Member-owned collection](#10-make-a-collection-member-owned)

---

## 1. Add a flow action handler

A handler that runs as a Task state in the Visual Flow Builder (e.g. `SendSlackMessage`).

**Backend**
1. Create `kelta-platform/runtime/runtime-module-integration/src/main/java/io/kelta/runtime/module/integration/handlers/<Name>ActionHandler.java` implementing `io.kelta.runtime.workflow.ActionHandler`:
   - `String getActionTypeKey()` → the action type (e.g. `"SEND_SLACK_MESSAGE"`).
   - `ActionResult execute(ActionContext context)` → read **static node config** from `context.actionConfigJson()` (parse with `ObjectMapper`) and **runtime-resolved values** from `context.resolvedData()` (the `$.input.*` envelope feeds this); do the work; return `ActionResult.success(map)` / `failure(...)`.
   - optional `validate(...)` and `getDescriptor()`.
   - Collaborators are **constructed** (not field-injected) and pulled from `ModuleContext.getExtension(Type.class)` in the module. A handler that calls a **tenant-configured URL** uses runtime-core's `OutboundHttpClient` with the `OutboundUrlGuard` extension (as `HttpCalloutActionHandler`, `CallApiActionHandler` and `OutboundMessageActionHandler` do), never a bare HTTP client or `RestTemplate` (`RestTemplate` is not a registered extension). Resolve secret refs (webhook URLs/keys) via the `CredentialResolverPort` extension. See `runtime-module-integration/CLAUDE.md` → Handler collaborators & config.
2. **Register it** (the step no agent guesses): in `runtime-module-integration/.../IntegrationModule.java`, construct your handler in `onStartup(ModuleContext)` and return it from `getActionHandlers()`. The module is wired as a `@Bean` in `kelta-worker/.../config/FlowConfig.java` (`integrationModule()`); `ModuleRegistry` calls `onStartup` then registers handlers into `ActionHandlerRegistry`.
   - ⚠️ Handlers are **NOT** `@Component`-scanned. The `ActionHandler` Javadoc that says "implement as a `@Component`" is **wrong** — a `@Component` handler silently never registers.
   - ⚠️ The `workflow_action_type` DB table is a **display catalog only**; its `handler_class` column is never read by Java. You do **not** need a migration for the handler to execute.
   - Reference impl: `handlers/HttpCalloutActionHandler.java`. (Pure data/CRUD handlers go in `runtime-module-core` via `CoreActionsModule` instead.)

**Frontend — make it usable in the builder** (the flow builder is hardcoded TS, not descriptor-driven):
3. Add the action to `RESOURCE_GROUPS` in `kelta-ui/app/src/pages/FlowDesignerPage/types.ts`.
4. Write a `<Name>Params.tsx` config form in `kelta-ui/app/src/pages/FlowDesignerPage/components/properties/` (props: `{ parameters, onUpdate }`; ref `HttpCalloutParams.tsx`) and wire it into `TaskProperties.tsx` (switched on `resource`).
   - ⚠️ The `ActionHandlerDescriptor` SPI exists but the admin UI does **not** consume it for built-in handlers — the palette + params are hand-written. Skipping this step ships a handler that never appears in the builder.

**Tests**: `<Name>ActionHandlerTest.java` co-located (JUnit 5 + Mockito, a loopback `HttpServer` for outbound HTTP) + a Playwright e2e exercising the node.
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

**CRUD list pages (list + create/edit modal + delete confirm) use `components/AdminCrud`** — never hand-roll the modal, `FormErrors`/`validateForm`, the `isFormOpen`/`editingX`/`deleteTarget` state, the mutation trio or a raw `<table>`. Reference impls: `pages/ProfilesPage/` (extra row action: clone; row click → detail) and `pages/PicklistsPage/` (i18n messages, checkbox fields, field locked on edit, extra dialog).
- `useAdminCrud<Row, Values>({ queryKey, create, update, remove, messages })` → `{ isFormOpen, editing, openCreate, openEdit, closeForm, submit, deleteTarget, requestDelete, cancelDelete, confirmDelete, isSaving, createMutation, updateMutation, deleteMutation }`. Each mutation invalidates `queryKey` and toasts `messages.created|updated|deleted`; errors toast the thrown message, else `messages.<op>Error ?? messages.error`. `submit` creates or updates (by `editing`) and closes the form on success; failures keep the form open. Use the lower-level `useCrudMutations` when a page has its own state machine. `onCreated(result)` covers one-time follow-ups (e.g. show a generated secret).
- `AdminCrudPage` = page shell: header (`title`, `description`, `actions`), then loading spinner / `ErrorMessage` with retry (`loadErrorMessage`, `onRetry`) / `empty-state` / `list`. Dialogs go in `children` (mounted in the empty state too).
- `list` = `AdminDataTable` with `testId`, `ariaLabel`, `rowTestId={(row, i) => …}` and `renderActions={(row, i) => …}` (both get the displayed index; so does a column's `cell(row, i)`), `actionsHeader`, `onRowClick`, `filterable={false}` to hide the client-side Filters toggle (server-filtered lists, e.g. Users).
- `CrudFormDialog<Values>` (on `components/ui/dialog`: focus trap, Escape, labelled `role="dialog"`, first enabled field focused) takes a zod `schema` + `defaultValues` (re-read on every open), `onSubmit={crud.submit}`, `isSubmitting={crud.isSaving}`, `testIdPrefix` → `<p>-form-modal|-form-close|-form-cancel|-form-submit` (`submitTestId` overrides the last, to keep a legacy id). Cancel and close keep focus on the edited field, so abandoning a form never runs blur validation. Fields read the form from context and disable while submitting: `CrudTextField` (`multiline` for textarea; `hint`; `errorTestId`), `CrudSelectField` (native select, `options`/`placeholder`/`hint`; its value follows the form, so options may load after the dialog opens), `CrudNumberField` (stores a number; `parse(raw)` keeps a page's old empty/invalid-input rule — return `null` to ignore the keystroke), `CrudCheckboxField`. A page-specific control can join the form through `useFormContext` + `useCrudFormState`. Build a zod schema whose messages come from `t()` inside `useMemo` when the page is translated.
- Delete = existing `ConfirmDialog` with `open={crud.deleteTarget !== null}`, `onConfirm={crud.confirmDelete}`, `onCancel={crud.cancelDelete}`.

**Migrating an existing CRUD page**: (1) grep `e2e-tests/pages/` + `e2e-tests/tests/` + the page's `*.test.tsx` for every `data-testid`, role and label it uses and keep them identical (`testIdPrefix`, `rowTestId`, action-button test ids, `empty-state`); (2) port the validator to a zod schema with the same messages and limits (use `.refine` rather than `.trim()` so submitted values are not transformed); (3) replace state + mutations with `useAdminCrud`, the modal with `CrudFormDialog`, the table with `AdminDataTable`, the page frame with `AdminCrudPage`; (4) keep page-specific extras (clone, secondary dialogs) beside the block; (5) adapt the page test only where the shared shell changed markup (e.g. loading is `loading-spinner-label`), never by weakening an assertion. All admin CRUD pages are migrated (2026-10-12). Three use the block only in part, deliberately: `EmailTemplatesPage` (its rich-text editor with merge tags and live preview doesn't fit `CrudFormDialog`), `UsersPage` (server-side search/filter/pagination — `AdminCrudPage`'s loading state would unmount the search box on every keystroke — so only the create dialog and `AdminDataTable`), `PageLayoutsPage` (the drag-and-drop layout builder and rules/assignment dialogs stay page-local).

**Styling**: follow `kelta-ui/DESIGN.md` (uppercase-11px field labels, token colors not hex, Lucide icons, em-dash empty states). **Reuse the canonical app components** (`AdminDataTable`, `ObjectDataTable`/`ListShell`, `FilterBuilder`, `fieldControl`) — never fork a new table/filter/form (see [`conventions.md`](conventions.md) → Component reuse).
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
- The **`form` widget** renders the app's record form — `RecordForm` with `presentation="embedded"` (`components/record/form/`) — so it gets the same field controls (`components/fieldControl`), layout, permissions, client validation and `If-Match` save as the record pages. Extend `RecordForm` (an embedded-only prop) for anything the widget needs; never mount `@kelta/components`' `ResourceForm` in the app (it needs a `KeltaProvider` the app does not mount, and crashed every page that used it).
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

1. **Declare it**: in `runtime-core/.../model/system/`, add a `public static CollectionDefinition <name>()` factory to the section class it belongs to (`CoreSystemCollections`, `UiSystemCollections`, `AutomationSystemCollections`, `IntegrationSystemCollections`, `AuditLogSystemCollections`, … — one per area; create a new `<Area>SystemCollections` only for a genuinely new area). Build it with `systemBuilder(name, displayName, tableName)` (or `readOnlySystemBuilder` for an API-read-only table), statically imported from the package-private `SystemCollectionBuilders` — it injects audit fields + `systemCollection(true)` + the physical table. Then add `definitions.add(<Section>SystemCollections.<name>())` to `SystemCollectionDefinitions.all()` (the registry entry point).
2. **Create the table** (the worker does **NOT** auto-create system tables — Flyway owns them):
   - Ship `kelta-worker/src/main/resources/db/migration/V<next>__create_<table>.sql` (check the dir for the head number; currently V142 → V143).
   - Add the table to the RLS-enable migration set (the V77-style migration hardcodes table names) so RLS applies.
3. **Multi-pod refresh**: add a `<Name>RefreshHook` in `kelta-worker/.../listener/` annotated `@Component` — that is the whole registration (see **Add a BeforeSaveHook** below). The hook implements `BeforeSaveHook`, and in `afterCreate/Update/Delete` publishes via `PlatformEventPublisher` to `kelta.config.collection.changed.<id>` (reuse `CollectionConfigEventPublisher.SUBJECT_PREFIX`, build a `CollectionChangedPayload` via `EventFactory`). All pods consume it via `NatsSubscriptionConfig` → `CollectionLifecycleManager` refreshes the `CollectionRegistry`. See [Critical Rule 1](../../CLAUDE.md).
   - Reference impl: `listener/ValidationRuleRefreshHook.java`, `listener/CollectionConfigEventPublisher.java`.
   - ⚠️ The reference `ValidationRuleRefreshHook.afterDelete` is a **no-op** (the `collectionId` isn't available on delete). If your collection needs delete to refresh other pods, handle it explicitly rather than copying the no-op.
4. **Secret material**: a field holding a secret, ciphertext or a credential hash is declared `.withServerOnly(true)` — the query engine never returns it, refuses it as a filter/sort/aggregate key and ignores a caller-supplied value, so set it from a before-save hook and read it over JDBC where the platform needs it. Add it to `SystemCollectionDefinitionsTest.secretMaterialIsServerOnly`. See [`architecture.md`](architecture.md) → Server-only fields.
**Tests**: registry/refresh unit test + migration applied against a non-empty DB.
**Docs**: [`status.md`](status.md). Keep each section class focused — if one grows past ~500 lines, split the area rather than letting a new single-file hotspot form.

> **This recipe is for platform-owned metadata only.** A collection that belongs to one tenant's
> installed module is declared in that module's `kelta-module.json` instead — see recipe 5b. It
> needs no code in this repo, no Flyway migration, and no redeploy.

---

## 5a. Add a BeforeSaveHook

A platform (global) hook on a system or wildcard collection — validation, defaults, a
config-change NATS broadcast (Critical Rule 1). A hook that belongs to one tenant's module is
returned from that module's `getBeforeSaveHooks()` instead (recipe 5b).

1. **Write the class** in `kelta-worker/.../listener/` implementing `BeforeSaveHook`, annotated
   `@Component`, with **exactly one constructor** (a `@Component` with two and no `@Autowired`
   fails worker boot while unit tests and CI stay green). Inject Jackson 3
   `tools.jackson.databind.ObjectMapper`, never `com.fasterxml…` (compiles, then crashes boot).
2. **That is the registration.** `config/BeforeSaveHookRegistrar` is a `BeanPostProcessor` that
   registers every `BeforeSaveHook` bean with the `BeforeSaveHookRegistry` as it is created — do
   not call `hookRegistry.register(...)` (it would register twice), and do not add it to
   `FlowConfig`. One class serving several collections (it takes the collection name) is one
   `@Bean` per collection in `config/MultiCollectionHookConfig`.
3. **Pick a unique `getOrder()`** for the collection. The registry sorts each collection's hooks
   by order, collection-specific hooks before wildcard (`*`) ones; ties keep registration order,
   and bean discovery order is unspecified — so no two hooks on one collection may share an order
   (count the schema module's `*LifecycleHook`s, default order 0, on `collections`/`fields`/
   `profiles`/`tenants`/`users`).
4. **Presence-gated?** (only when a secret/integration is configured) `@ConditionalOnProperty` on
   a property declared in `application.yml` as `${ENV_VAR:default}` — native images freeze
   conditions at build time. Never gate registration with a runtime `if`.
5. **Pin it**: add the hook, in execution order, to the expected lists in
   `kelta-worker/src/test/java/io/kelta/worker/config/BeforeSaveHookWiringTest.java` (it boots
   the real wiring and fails on a dropped/added/duplicated hook, a changed order, or an order tie).

**Tests**: the hook's own unit test + the wiring-test entry.
**Reference**: `listener/CollectionConfigEventPublisher.java`, `listener/ValidationRuleRefreshHook.java`.

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
   no-arg constructor — `ModuleLoader.loadFromJar` instantiates it reflectively. Build
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
   `ModuleMetadataProvisioner` translates them into a package and applies it at install through
   `PackageImportService.importPackage` — the importer sandbox clone, promotion and package import
   use, writing via `queryEngine` on the `collections`/`fields` system collections, so the NATS
   config broadcast and lifecycle init (including the physical table) fire on every pod. It runs a
   dry run first, then applies every collection plus the fields of only the collections that did
   not exist. Collection names must match `^[a-z][a-z0-9_]*$`; field names cannot be the platform's
   reserved ones (`id`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `tenantId`); a malformed
   manifest is rejected before anything is imported. Each collection and field is recorded in
   `module_provisioned_resource` as `CREATED` (with a SHA-256 `content_hash` of what the manifest
   asked for) or `ADOPTED` (null hash); a row once `CREATED` stays `CREATED` across uninstall +
   reinstall. `GET /api/modules/{moduleId}/provisioned-resources` lists them for the caller's
   tenant, including after uninstall. A failed import item marks the module `FAILED`.
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
   Install **adopts collections that already exist and leaves them completely alone** — no field
   is added or updated on them (`ModuleMetadataProvisioner` → `PackageImportService` in `SKIP`
   mode, with the adopted collections' fields filtered out), so the later install will not clobber
   them.
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
- **Infrastructure failures do not quarantine.** If object storage or the database is unreachable
  while the JAR is fetched or verified (or a connectivity error surfaces from `onStartup`), the module
  fails closed on that pod with the same refusing handlers, but its status is left alone, only
  `last_error` is recorded, and the pod retries with backoff (`kelta.modules.load-retry.*`, 30 s →
  10 min, never gives up). An `onStartup` that throws for any other reason is still the module's
  fault and quarantines — don't let a flaky external call in `onStartup` look like a bug.
- Re-enabling a `QUARANTINED` module (`POST /api/modules/{moduleId}/enable`) re-runs the load on
  every pod, without a restart.
- `GET /api/modules/{id}/health` reports whether the module is loaded **on the pod that answered**,
  which is not the same as the stored status: loading is per-pod and driven by a fire-and-forget NATS
  event. `loadStateOnThisPod` distinguishes `LOADED` from `RETRYING` (with `nextLoadRetryAt`) and
  `FAILED`.

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
`ModuleMetadataProvisionerTest` covers manifest → package translation, created vs adopted and
the content hash; `ModuleProvenanceStoreTest` pins the ownership-preserving upsert;
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
   (reference: `listener/ChatMessageHook.java`). Annotate the hook `@Component` — it is
   registered by being a bean (see **Add a BeforeSaveHook**). Add the subject to the CLAUDE.md
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

**Direct database logins** (a person's read-only role, a BI tool, the per-tenant Superset
users): the GUC is a plain custom setting any session can `SET`, so it is never their
boundary. Pin the role instead — `INSERT INTO tenant_db_role (role_name, tenant_id) VALUES
('<role>', '<tenant id>')` — and the policies use that tenant for `session_user` no matter
what the session sets (`kelta_pinned_tenant()`, V201; `SupersetDatabaseUserService` pins its
roles automatically). Grant such a role `SELECT` only on tables that have a `tenant_id`
column (RLS-scoped) — never `ALL TABLES IN SCHEMA public`, which includes `user_credential`,
`oauth2_*` and the tenant-less child/log tables RLS cannot scope.

---

## 9. Let an external portal edit the signed-in member's own profile

An external site (a consumer frontend, a patient portal) signs a member in with the portal
magic-link flow or gives them a PAT, and wants a "settings" page for that member. **Do not**
grant the Portal User profile `MANAGE_USERS` or object permissions on `users`, and do not route
the write through a backend-for-frontend holding a service account: the platform already has a
member-safe endpoint.

**Call, with the member's own token**
- `GET /{tenantSlug}/api/me/profile` → `{"data":{"type":"users","id":"<uuid>","attributes":{email,firstName,lastName,locale,timezone,userType}}}`.
- `PATCH /{tenantSlug}/api/me/profile` with `{"data":{"type":"users","attributes":{"timezone":"Europe/Lisbon"}}}`
  — send only the fields that changed. Editable: `firstName`, `lastName` (≤ 100 chars),
  `locale` (`en`, `pt_BR`, `es-419` …), `timezone` (IANA). Anything else → 400
  `FIELD_NOT_EDITABLE` with `source.pointer` `/data/attributes/<field>`; bad values → 400
  `VALIDATION_FAILED`. No signed-in user → 404.

**What it does not do — and where that data goes instead**
- The allow-list is fixed in code (`MyProfileController.EDITABLE_FIELDS`), and the `users`
  definition is fixed: a tenant cannot add fields to it. Site-specific member data (a region, UI
  preferences, …) belongs in a **tenant collection the member owns**, not on `users`; push
  registration uses the existing `/api/devices`.
- Email, status, profile, user type, manager, MFA and `settings` stay admin-only
  (`MANAGE_USERS`, or delegated administration within a scope).

**Platform side** (when touching it): `MyProfileController` → `SelfProfileWriteContext` →
`IdentityCollectionGuardHook`. Widening the allow-list is a security change (`concerns.md` →
Member self-profile); the in-app equivalent is `ProfileDialog` behind the user menu's Profile item.

## 10. Make a collection member-owned

Use this when each record belongs to one user and other users (portal members, or everyone
without `VIEW_ALL_DATA`) must not see or change it. It is pure metadata: **do not** write a
bespoke owner-guard hook (the old `WatchGuardHook`/`NoteGuardHook`/… pattern) for a new
collection.

1. **Give the collection an owner field.** Either rely on the built-in `createdBy`, or add a
   `LOOKUP` field whose target is `users` (e.g. `member`). Mark it `indexed` — the owner
   predicate is on every read, so a large collection needs the index.
2. **Set the three settings** on the collection (admin UI: Collection form → **Ownership**
   group; API: `PATCH /api/collections/{id}`; package import re-applies them after FIELD rows):
   - `ownerField` — the field from step 1 (`createdBy` or the LOOKUP's name).
   - `ownerScope` — `PORTAL` (portal members see/change only their rows; staff unaffected) or
     `ALL` (every caller, except INTERNAL callers whose profile grants `VIEW_ALL_DATA` for
     reads / `MODIFY_ALL_DATA` for writes). `NONE` is the default and turns it off.
   - `ownerScopeReads` — default `true`. Set `false` for public-read, author-edit collections
     (reads unscoped, writes still owner-only).
   `CollectionOwnershipValidationHook` rejects (400 `INVALID_OWNER_FIELD`) a scope other than
   `NONE` without `createdBy` or an active LOOKUP→`users` field. A brand-new collection has no
   fields yet, so set a LOOKUP owner **after** adding the field. The change propagates through
   the existing `collection.changed` NATS broadcast — no extra wiring.
3. **What you get** (enforcement lives in shared code — nothing per-collection to register):
   - Reads: `PhysicalTableStorageAdapter.ownerScope(...)` ANDs `<owner column> = :callerId`
     beside the tenant predicate for `query` (lists, counts, `/latest`, includes, exports,
     reports, dashboards), `aggregate`, semantic search and `getById` — a foreign id is a 404 and
     `totalCount` is correct. Under `ALL`, rows shared with the caller via `record_share`
     (directly or through a group) stay visible.
   - Writes: `OwnerScopeGuardHook` (wildcard `@Component`, order −99, right after `IdentityCollectionGuardHook`) stamps the
     owner on create (a different client-supplied owner → 400 `OWNER_MISMATCH`), 404s a foreign
     update/delete, and makes the owner field immutable (400 `OWNER_IMMUTABLE`).
   - Realtime: `kelta.record.changed` carries `ownerScoped=true`; `RealtimeBridge` strips `data`
     (invalidation-only).
   - The internal tier (no `CallerContext`: flows, NATS listeners, schedulers) is never scoped.
4. **Code that reads the table without `QueryEngine` is not covered.** A raw-`JdbcTemplate`
   repository, `search_index` full-text search, or a custom controller must scope itself (see
   `concerns.md` → Owner-scoped collections — paths outside the storage adapter).
5. **Test** with the real-DB pattern in
   `kelta-test-harness/.../scenarios/OwnerScopedCollectionScenarioTest.java` (two portal users +
   a `VIEW_ALL_DATA` staff user); unit-level adapter coverage is
   `PhysicalTableStorageAdapterOwnerScopeTest`.
