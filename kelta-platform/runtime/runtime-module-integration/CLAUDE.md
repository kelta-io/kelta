# runtime-module-integration

A compile-time `KeltaModule` that contributes **flow action handlers** for outbound
integrations (HTTP callout, outbound message, email alert, invoke script, delay, publish
event, etc.). Its sibling `runtime-module-core` contributes the data/CRUD/control handlers
(create/update/delete record, query, decision, log, field update, create task).

## How handlers are registered (read this first)

Action handlers are **NOT** Spring `@Component`s and are **not** classpath-scanned. The flow
engine discovers them through the module SPI:

1. A handler implements `io.kelta.runtime.workflow.ActionHandler`:
   - `String getActionTypeKey()` — the action type string used in flow definitions.
   - `ActionResult execute(ActionContext context)` — inputs arrive already resolved from the
     `$.input.*` JSONPath state envelope; do the work, return success/error.
   - optional `validate(...)`, `getDescriptor()`.
2. `IntegrationModule` (this module) **constructs** the handler in `onStartup(ModuleContext)`
   and returns it from `getActionHandlers()`. `ModuleRegistry` calls `onStartup` first, then
   registers the returned handlers into `ActionHandlerRegistry`.
3. The module is wired as a `@Bean` in `kelta-worker/.../config/FlowConfig.java`
   (`integrationModule()`); that's where worker-side collaborators get injected into it.

> ⚠️ The `ActionHandler` Javadoc claiming "implement as a `@Component`, discovered via
> classpath scanning" is **wrong** — a `@Component` handler silently never registers.
> ⚠️ The DB `workflow_action_type` table is a **display catalog**; its `handler_class` is
> never read by Java. No migration is required for a handler to execute.

## Handler collaborators & config (the non-obvious bits)

- **Reading config vs. inputs**: `ActionContext` is a record. Read the node's **static config**
  from `context.actionConfigJson()` (raw JSON — parse with `ObjectMapper`); read **runtime-
  resolved values** from `context.resolvedData()` (a `Map`). The `$.input.*` JSONPath envelope
  feeds `resolvedData` — inputs are not handed in as typed params.
- **Collaborators come from the module, not Spring**: in `onStartup(ModuleContext)`, pull
  dependencies via `context.getExtension(Type.class)`. The worker registers these extensions in
  `FlowConfig.moduleRegistry()`: `FlowEngine`, `RollupSummaryService`, `JdbcTemplate`,
  `TransactionTemplate`, `EmailService`, `ScriptExecutor`, `ApiSpecStore`, **`CredentialResolverPort`**,
  `IdempotencyStore`, **`OutboundUrlGuard`**.
  - A handler that fetches a **tenant-configured URL** uses runtime-core's `OutboundHttpClient`
    with the `OutboundUrlGuard` extension (the worker registers it; `IntegrationModule` falls back
    to a guard that allows no private range) — see `HttpCalloutActionHandler`,
    `CallApiActionHandler` (raw URL and spec base URL) and `OutboundMessageActionHandler`. That
    gives the non-public-address check, per-hop redirect re-validation, a deadline and a body cap.
    Each handler has a package-private constructor taking a pre-built `OutboundHttpClient` so
    tests can shorten the limits.
  - ⚠️ `RestTemplate` is **NOT** a registered extension and no handler uses one — don't add a
    `RestTemplate`/`RestClient` for outbound calls.
- **Secrets** (webhook URLs, API keys): resolve through the `CredentialResolverPort` extension
  (`resolve(tenantId, reference, purpose)`) — store a credential **reference** in node config,
  not the plaintext secret.

## Package layout

```
io.kelta.runtime.module.integration/
  IntegrationModule.java     ← the KeltaModule: onStartup() + getActionHandlers()
  handlers/                  ← one class per action type
```

## Reference implementations

| Pattern | File |
|---------|------|
| Outbound HTTP action handler | `handlers/HttpCalloutActionHandler.java` |
| Module SPI (construct + return handlers) | `IntegrationModule.java` |
| Worker-side bean wiring | `kelta-worker/.../config/FlowConfig.java` (`integrationModule()`) |

## Adding a handler

Full recipe (including the Visual Flow Builder UI wiring, which is hardcoded TypeScript, not
descriptor-driven): see `.claude/docs/playbooks.md` → "Add a flow action handler".

## Tests

JUnit 5 + Mockito; a real loopback `com.sun.net.httpserver.HttpServer` for outbound HTTP (allow
`127.0.0.0/8` on the guard in the test; `handlers/LoopbackServer` is a small shared one). Co-locate
`*ActionHandlerTest.java`.

```bash
mvn test -f kelta-platform/pom.xml -pl runtime/runtime-module-integration
```
