# kelta-mcp

Model Context Protocol (MCP) server for the Kelta platform. Exposes platform operations to MCP clients (e.g. Claude Code) over HTTP, authenticated by the user's Personal Access Token.

## Endpoints

Two MCP server instances run inside one Spring Boot process. The URL
convention follows the rest of the Kelta platform — slug first:

| URL | Profile | Purpose |
|-----|---------|---------|
| `/{tenantSlug}/mcp/user` | data-plane | CRUD records, run flows, approvals, search |
| `/{tenantSlug}/mcp/admin` | control-plane | Define collections/fields/layouts/flows/etc. |

The slug binds each MCP session to a specific tenant — different
PATs in different tenants get different MCP URLs in their Claude
Code config, so a single deployment can serve any number of tenants.
`McpAuthFilter` extracts the slug from the URL, rewrites the request
to the canonical `/mcp/(user|admin)` path the SDK servlet is
registered at, and stamps the slug as a request attribute.

Each endpoint has its own immutable tool registry — admin tools are
not callable from `/mcp/user` (or vice versa).

## Auth

Every request to `/{slug}/mcp/**` must carry `Authorization: Bearer
klt_*`. `McpAuthFilter` does a presence/prefix check only — the
gateway does the cryptographic validation when tool calls are
forwarded. The filter strips any client-supplied `X-Tenant-ID` /
`X-Tenant-Slug` / `X-User-Id` headers so a client can't impersonate
another tenant; the slug binding is the URL itself.

`KeltaTransportContextExtractor` picks both PAT and slug off the
inbound request and packs them into the SDK's
`McpTransportContext`. `PatPropagatingToolDecorator` reads them back
on the Reactor scheduler thread and pushes them into
`RequestPatHolder` / `RequestSlugHolder` for the duration of the
tool handler — `GatewayHttpClient` reads from those for outbound
calls.

## Package Layout

```
io.kelta.mcp/
  config/          ← McpServerConfig (two server instances), McpProperties, WebConfig
  auth/            ← McpAuthFilter, PatSessionStore
  transport/       ← KeltaMcpEndpoints (path mounting)
  tool/
    shared/        ← JsonApiClient, AttributeMapper, ResponseShaper
    user/          ← QueryCollectionTool, CreateRecordTool, ExecuteFlowTool, ...
    admin/         ← CreateCollectionTool, CreateLayoutTool, CreateFlowTool, ...
  resource/        ← MCP Resources for browsable schema/openapi
    docs/          ← DocResource (kelta://docs/<topic>): classpath copies of docs/authoring/*.md,
                     pinned against the repo-root source by DocResourceTest
  client/          ← GatewayHttpClient (in-cluster: http://emf-gateway:80)
  error/           ← McpErrorMapper (gateway 4xx/5xx → MCP error result, redacts klt_)
```

## Key Patterns

### MCP server wiring
`config/McpServerConfig` builds the two stateless servers. It autowires `List<UserTool>`
and `List<AdminTool>` (every tool is a `@Component`), calls each tool's `toSpecification()`,
wraps it (`wrap(...)` adds PAT/slug propagation), and registers it. Registration is
**automatic** — do **not** hand-write `server.addTool(...)`.

### Tool definition
Each tool implements the marker interface **`UserTool`** or **`AdminTool`** (`@Component`)
and returns an `io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification`
from `toSpecification()`. Build the input schema with `Schemas`; annotate behaviour with
`ToolHints`. The call handler builds an HTTP request, forwards through `GatewayHttpClient`
(PAT/slug from `RequestPatHolder` / `RequestSlugHolder`), shapes the JSON:API response into
MCP content, and maps gateway 4xx/5xx via `McpErrorMapper`. Translate friendly camelCase
args → native JSON:API at this boundary (see `.claude/docs/conventions.md`). Full recipe:
`.claude/docs/playbooks.md` → "Add an MCP tool".

### Server instructions
Each server's `.instructions(...)` call in `McpServerConfig` (`USER_INSTRUCTIONS` /
`ADMIN_INSTRUCTIONS`) is sent once, in the `initialize` response, before any tool call —
it's the front door that orients a client (discovery order, filter/formula gotchas, build
order for admin) instead of making it reconstruct the operating model tool-by-tool every
session. **Update it in the same PR** whenever a tool is added, removed, renamed, or its
documented behavior changes in a way that affects the recommended usage order.

### Resource definition
A static resource (fixed URI) implements **`UserResource`** and/or **`AdminResource`** — both
are `@Component`-discoverable marker interfaces returning a `SyncResourceSpecification`; a
class implementing both is registered on **both** endpoints (e.g. `docs/DocResource`, the
`kelta://docs/<topic>` authoring reference). A URI with variable segments implements
`UserResourceTemplate` instead (admin has no template variant yet). Registration is automatic
via the `List<UserResource>`/`List<AdminResource>`/`List<UserResourceTemplate>` autowiring in
`McpServerConfig` — do not hand-write `server.addResource(...)`.

### No DB
kelta-mcp is stateless. The runtime-core auto-configurations (`KeltaRuntimeAutoConfiguration`, `EncryptionAutoConfiguration`) are excluded in `McpApplication`. Component scan is restricted to `io.kelta.mcp` so we only depend on runtime-core for type definitions (CollectionDefinition, FieldDefinition, FieldType).

## Configuration

```yaml
kelta:
  mcp:
    gateway-url: http://emf-gateway:80
    session-ttl-minutes: 30
    tool-timeout-ms: 60000
```

## Running Tests

```bash
mvn test -f kelta-mcp/pom.xml                                       # All tests
mvn test -f kelta-mcp/pom.xml -Dtest=McpAuthFilterTest              # Single class
mvn test -f kelta-mcp/pom.xml -Dtest="*Tool*"                        # Pattern
```

## Local Smoke Test

```bash
mvn spring-boot:run -f kelta-mcp/pom.xml

claude mcp add kelta-local --transport http \
  --url http://localhost:8080/threadline-clothing/mcp/user \
  --header "Authorization: Bearer klt_smoke_test_token"
# In a Claude Code session, ask: "use the kelta-local MCP server to call ping"
```

## Status

kelta-mcp is shipped and live. It has no separate plan doc; this file is the source of
truth for the module.
