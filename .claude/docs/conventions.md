# Coding Conventions

## Java

### Naming
- **Classes**: PascalCase (`RouteDefinition`, `CollectionLifecycleManager`)
- **Methods**: camelCase (`getId()`, `refreshCollectionDefinition()`)
- **Constants**: UPPER_SNAKE_CASE (`TAG_TENANT`, `TAG_METHOD`)
- **Fields**: camelCase with `private final` (`private final MeterRegistry registry`)
- **Booleans**: `is`/`has` prefix for getters (`isActive()`, `hasRateLimit()`)
- **Packages**: `io.kelta.<service>.<feature>` (e.g., `io.kelta.authz.cerbos`)

### Style
- 4-space indentation, 1TBS braces
- No wildcard imports; sorted alphabetically within groups
- Import order: (1) `io.kelta.*`, (2) external libs (`com.fasterxml.*`, `org.*`), (3) Java stdlib (`java.*`, `javax.*`)
- Constructor injection preferred over field injection
- `@Component` for beans, `@Service` for business logic, `@Repository` for data access
- NATS subscriptions registered explicitly via `NatsSubscriptionManager` (no annotation-based listeners)

### Logging
- `private static final Logger logger = LoggerFactory.getLogger(ClassName.class)`
- Parameterized logging (not string concat): `logger.info("Processing: id={}, name={}", id, name)`
- JSON structured output via Logstash Logback Encoder

### Error Handling
- Try/catch with full exception logging: `logger.error("Error: {}", e.getMessage(), e)`
- Custom exceptions extend from base types
- No silent failures, graceful degradation on non-critical failures

#### JSON:API error response shape (4xx)

All 4xx responses returned from any Kelta service MUST use the JSON:API error envelope. Every object in the `errors[]` array carries at minimum `status`, `code`, `detail`, and (for field-level errors) `source.pointer`:

```json
{
  "errors": [
    {
      "status": "400",
      "code": "VALIDATION_FAILED",
      "title": "Validation Error",
      "detail": "name must not be blank",
      "source": { "pointer": "/data/attributes/name" },
      "meta": { "requestId": "abc12345" }
    }
  ]
}
```

- `status` — HTTP status code as a string (`"400"`, `"404"`, …)
- `code` — stable, machine-readable identifier in `UPPER_SNAKE_CASE` (`NOT_FOUND`, `INVALID_PAYLOAD`, `VALIDATION_FAILED`, `UNAUTHORIZED`, `RATE_LIMIT_EXCEEDED`, …). Clients branch on `code`, not `detail`.
- `title` — short, human-readable category (`"Bad Request"`, `"Not Found"`, …)
- `detail` — human-readable description of *this* failure. Never `null`, never empty.
- `source.pointer` — JSON Pointer (RFC 6901) to the offending field on a request body — e.g. `/data/attributes/email`. For query / path parameters use `source.parameter` with the parameter name instead.

Never emit an empty error object (`{}`). If you reach a path with no specific information, fall through to the generic handler so clients still get a populated envelope.

- `meta` — always carries `requestId`. Field-level errors may add error-specific keys next to it; `GlobalExceptionHandler.handleValidationException` merges `FieldError.meta()` into the response error's `meta` map. The `reference` code (a lookup/master-detail field whose value doesn't resolve to an existing record) sets `meta.field`, `meta.targetCollection` and, when the offending value is known, `meta.value` — e.g. `{"field": "sectionId", "value": "page-layout-1", "targetCollection": "layout-sections", "requestId": "abc12345"}`. When the field's configured target collection itself doesn't exist (`FieldError.referenceTargetMissing`), `meta.value` is omitted since there was no candidate record to check. `INVALID_QUERY` raised from a `StorageQueryException` (a client-caused SQL error `PhysicalTableStorageAdapter` classified from the Postgres SQLSTATE — see `concerns.md` → Resolved) additionally sets `meta.sqlState` (e.g. `"22P02"`); `source.pointer` is present only when the offending field could be identified from the driver's message.

Where errors are constructed:
- `kelta-gateway/src/main/java/io/kelta/gateway/error/GlobalErrorHandler.java` — reactive (`ErrorWebExceptionHandler`) for all gateway-originating 4xx/5xx
- `kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/router/GlobalExceptionHandler.java` — servlet (`@ControllerAdvice`) covering bean validation, malformed bodies, missing params, type mismatches, `NoResourceFoundException`/`NoHandlerFoundException`, `MethodNotAllowed`, `UnsupportedMediaType`, `ResponseStatusException`, plus the platform's own `ValidationException` / `InvalidQueryException` / `UniqueConstraintViolationException`
- `io.kelta.jsonapi.JsonApiResponseBuilder.error(...)` — utility for one-off error documents in controllers; the 3-arg overload derives `code` from `title` so existing callers stay compliant
- `io.kelta.jsonapi.JsonApiError` — POJO used by the handlers; `@JsonInclude(NON_NULL)` keeps absent fields out of the wire format

#### Masked fields on read (data masking)

A record whose fields were redacted for the caller carries a record-level
`meta.maskedFields: ["ssn", …]` (sorted) in JSON:API responses. Clients MUST
branch on `meta.maskedFields` to render lock state / disable editing — never
sniff placeholder strings like `***`. Masked values are always JSON strings.

`MASKED_FIELD_PREDICATE` — the 403 `code` returned when a list request's
`filter[...]`/`sort` references a field masked for the requester
(`MaskedFieldPredicateInterceptor`, kelta-worker). The body is deliberately
**byte-identical for every rejection** (no `source`, no field name): a
distinguishable error would itself be a value-probing oracle. Do not "improve"
it with specifics.

#### Immutable fields on update

Immutability is declared two ways and **both are loud** — an update that would
change an immutable field fails with a `VALIDATION_FAILED` envelope naming the
field (`constraint: "immutable"`), never a 200 for a write that did not happen
(#1330):

- **Field-level** (`FieldDefinition.immutable()`) — rejected by
  `DefaultValidationEngine` whenever the field is present in an UPDATE payload.
- **Collection-level** (`CollectionDefinition.immutableFields()`, e.g.
  `watch-targets.source`/`externalId`) — rejected by `DefaultQueryEngine.update`
  when the submitted value **differs** from the stored one. Re-sending the value
  the record already holds is accepted and dropped from the patch, so a
  full-record round-trip (read → edit one field → write the whole map back)
  still works. A `null`/blank submission is treated as "not supplied", not as a
  clear — generic forms coerce untouched inputs to `null`, and an immutable
  field cannot be emptied anyway.

Collection-level immutability is **not** exposed on the schema/describe API, so a
generic client cannot pre-disable those inputs — it finds out by being rejected.
A relationship field that is immutable also cannot be re-parented:
`RecordMergeService` skips it and returns it under `skippedImmutable` instead of
counting a write it cannot make.

Prefer the field-level flag for new fields in the declared field list;
collection-level covers fields outside it (`tenantId`, join keys).

### Javadoc
- Required for public classes and methods
- Include `@param`, `@returns`, `@throws`

### BeforeSaveHook signatures

`BeforeSaveHook` exposes two parallel signatures for each lifecycle method: the **legacy** form without a collection name (`beforeCreate(record, tenantId)`, `beforeUpdate(id, record, previous, tenantId)`, `afterCreate(record, tenantId)`, …) and the **collection-name-aware** form that takes `collectionName` as the first argument. Both `BeforeSaveHookRegistry.evaluateBeforeCreate/Update` and `invokeAfterCreate/Update/Delete` dispatch the collection-name-aware variant, whose default delegates to the legacy variant — so existing hooks remain source-compatible.

- **Collection-specific hook** — override the legacy form. The collection name is already implicit from `getCollectionName()`.
- **Wildcard hook** (`getCollectionName() == "*"`) — override the collection-name-aware form when behaviour depends on which collection triggered the call (e.g. routing audit events by collection).
- Never override **both** variants on the same hook — the collection-name-aware default already calls through, so duplicating logic risks running it twice if the registry contract ever changes.

### Analytics permission semantics

`VIEW_ANALYTICS` = run/consume analytics (report execute/export, dashboard data);
`MANAGE_REPORTS` = author/admin reports and dashboards, and **implies** view — the in-controller
gate (`requireAnalyticsAccess` in `ReportExecutionController`/`DashboardDataController`) accepts
either. Grant `VIEW_ANALYTICS` to end-user profiles; reserve `MANAGE_REPORTS` for builders.

### End-user list deep links

`kelta-ui/app/src/pages/app/ObjectListPage/listUrlState.ts` owns the list-view URL contract
(`?page/pageSize/sort/filter=<JSON FilterCondition[]>`). Build deep links ONLY through its
`buildListUrl(tenantSlug, collection, filters, sort?)` — the canonical drill-through helper
(dashboard chart segments, saved views); never hand-assemble the `filter` param.
### Realtime events are invalidation-only

`/ws/realtime` pushes record `data` to every tenant subscriber with NO per-subscriber
FLS/Cerbos check (masking-configured collections already suppress `data`). Frontend
consumers therefore NEVER write pushed `data` into caches — on `record.changed`, invalidate
the matching React Query keys (`src/realtime/invalidation.ts` is the canonical mapping) and
let the refetch go through the authorized JSON:API path where per-viewer authz applies.

### Conversation-scoped socket events (chat)

Chat events do NOT use the tenant-wide collection broadcast — that would leak conversation
activity across patients. The socket gains `chat.join`/`chat.leave` actions: the gateway
verifies membership against the worker (`ChatMembershipClient` → `/internal/chat/...`,
30s-cached, fail-closed) before adding the session to a per-conversation routing set
(`SubscriptionManager`, 20 joins/session), and `ChatMessageBridge` fans
`kelta.chat.message.*`/`kelta.chat.conversation.*` only to joined sessions. Event payloads
carry ids/state ONLY — never message bodies — so the invalidation-only client rule holds:
on `chat.message`, refetch over `/api/chat/**` where participant authz applies. Any future
event family with sub-collection scoping (e.g. per-record) follows this pattern (recipe in
`playbooks.md` §7).

### Per-user UI preferences

Persist per-user UI state (saved views, favorites, panel layouts) ONLY through
`usePreferenceValue` (`kelta-ui/app/src/hooks/usePreferenceStore.ts`) — one
`user-ui-preferences` row per (prefType, prefKey), owner-guarded server-side, with a
localStorage `localKey` as warm cache/offline fallback. Never write a new bare
localStorage preference; never write another user's row (the guard rejects it).

### SavedView v2 + list deep links

`SavedView` (useSavedViews) carries optional v2 fields — ordered `sorts[]` (supersedes
`sortField`/`sortDirection`; resolve via `viewSorts()`), `viewType`
(table|kanban|calendar|gallery), `density`, `groupBy`, `typeConfig` — absent fields read
as legacy behavior. List URLs carry `view=<id>` plus the standard
`?filter/sort/pageSize`; the `sort` param uses the server's comma grammar (`a,-b`,
multi-level — already supported end-to-end server-side).

### Shared list views own their renderer; the toolbar switch is a per-user override

A `list-views` row carries `viewType` (`TABLE`|`KANBAN`|`CALENDAR`|`GALLERY`, uppercase on
the wire) and `typeConfig` (per-renderer settings keyed by lowercased view type), so the
renderer is publishable metadata — not something each user re-picks. Rules when touching
this path:

- **Map shared rows through `mapSharedListView`** (`ObjectListPage/listViewMapping.ts`) —
  never read `row.viewType` directly. It lowercases into `SavedViewType` and returns
  `undefined` (⇒ table) for anything unrecognized, and `parseTypeConfig` drops a section
  missing its required field (kanban `laneField`, calendar `dateField`). **An unknown
  renderer or a malformed config must degrade to the table, never throw** — rows predate
  V196 and a rollback can reintroduce them.
- **Never write a user's renderer choice back to the shared row.** The toolbar switch on a
  shared view persists through `useViewTypeOverrides` (a `user-ui-preferences` row,
  `prefType: 'list-view-type'`, keyed by collection, holding shared-view id → override) —
  i.e. through `usePreferenceValue`, per *Per-user UI preferences* above. Precedence is
  personal override → published `viewType`/`typeConfig` → `table`; gate the first paint on
  `isLoaded` so the published board does not flash in ahead of the override.
- **A new renderer is four coordinated edits**: the `view_type` CHECK + the enum values in
  `SystemCollectionDefinitions.listViews()`, `VIEW_TYPES`/`parseTypeConfig` in
  `listViewMapping.ts`, the CLI `--view-type` vocabulary (`commands/layouts.ts`), and the
  MCP `create_listview`/`update_listview` schema text. Keep the four in sync — they are the
  same vocabulary, and the CLI/MCP paths are expected to produce an identical row.

## REST API: pagination

Every paginated REST endpoint MUST use **JSON:API bracket syntax** — `page[number]` and `page[size]`. The flat forms `pageNumber` / `pageSize` are not honored and a request that sends them silently falls back to defaults.

```
GET /api/customers?page[number]=2&page[size]=50&sort=-createdAt
```

### Defaults and bounds

| Param          | Default | Maximum | Behavior on over-cap                              |
|----------------|---------|---------|---------------------------------------------------|
| `page[number]` | 1       | —       | Negative / zero values clamp to 1                 |
| `page[size]`   | 20      | 200     | Values above the cap silently clamp down to 200   |

The constants live in `io.kelta.runtime.query.Pagination`:
- `DEFAULT_PAGE_SIZE = 20`
- `MAX_HTTP_PAGE_SIZE = 200` — clamp applied by `Pagination.fromParams`
- `MAX_PAGE_SIZE = 1000` — absolute ceiling on the record itself; only reached when internal services (report export, include resolution, data export) build a `Pagination` directly

Endpoints that need higher caps for internal batch fetches (e.g. report execution, CSV export) clamp against their own constant — they never expose that ceiling over HTTP.

### Response shape

Paginated list responses carry pagination state under both `meta` (the JSON:API standard key) and `metadata` (legacy alias), plus a `links` block (URLs for navigation):

```json
{
  "data": [ { "type": "customers", "id": "…", "attributes": { … } } ],
  "meta": {
    "totalCount": 100,
    "currentPage": 2,
    "pageSize": 20,
    "totalPages": 5
  },
  "metadata": {
    "totalCount": 100,
    "currentPage": 2,
    "pageSize": 20,
    "totalPages": 5
  },
  "links": {
    "self": "/api/customers?sort=-createdAt&page[number]=2&page[size]=20",
    "prev": "/api/customers?sort=-createdAt&page[number]=1&page[size]=20",
    "next": "/api/customers?sort=-createdAt&page[number]=3&page[size]=20"
  }
}
```

- `meta` and `metadata` carry **identical content** and reference the same underlying map. New clients should read `meta` (JSON:API spec). `metadata` is **deprecated** — kept for backward compatibility with existing integrations and will be removed in a future major version.
- `links.self` is always present.
- `links.prev` is `null` when the response is on page 1.
- `links.next` is `null` when the response is on (or past) the last page.
- URLs are **relative** paths — they reuse the request URI so cached system-collection responses remain reusable across hosts and behind load balancers.
- Non-pagination query parameters (`filter[…]`, `sort`, `fields`, `include`) are preserved verbatim in the generated links; only `page[number]` and `page[size]` are rewritten.

Link generation lives in `io.kelta.jsonapi.PaginationLinks.build(...)`; the dynamic collection router calls it from `toJsonApiListResponse(...)`.

### MCP tools

MCP tools (`query_collection`, `list_picklists`, `list_approvals`) take flat `pageNumber` / `pageSize` arguments as an ergonomic affordance for LLM callers, and translate them to the bracket form when constructing the HTTP request to the gateway. The same `page[size]` cap (200) applies — the MCP tool's input schema declares `maximum: 200` and the call handler clamps defensively.

## REST API: filter grammar

Every `filter…` query key must be one of two shapes:

```
filter[field][op]=value   # e.g. filter[status][eq]=active, filter[id][in]=a,b
filter[field]=value       # shorthand — behaves as [eq]
```

Any other `filter…`-prefixed key (e.g. the HTML-form array shape `filter[status][in][]=a`,
or an indexed variant `filter[status][in][0]=a`) is rejected with `400 INVALID_QUERY` whose
detail states the grammar above, rather than being silently dropped (which used to return the
whole, unfiltered collection). `io.kelta.runtime.query.FilterCondition.fromParams` implements
this; non-`filter…` keys (`sort`, `page[…]`, `fields`, `include`) are untouched.

`io.kelta.runtime.query.FilterOperator.parse(String)` is the **single** operator vocabulary
shared by the JSON:API filter grammar above, dashboard widget filters
(`DashboardDataService.mapOperator`), and the MCP `query_collection` tool. It resolves, case-insensitively:
- the canonical enum names (`eq`, `neq`, `gt`, `lt`, `gte`, `lte`, `isnull`, `contains`, `starts`,
  `ends`, `icontains`, `istarts`, `iends`, `ieq`, `in`)
- the `any` alias for `in`
- the end-user-UI aliases: `equals`, `not_equals`, `greater_than`, `less_than`,
  `greater_than_or_equal`, `less_than_or_equal`, `starts_with`, `ends_with`

An unrecognized token throws `InvalidFilterException` naming the token and listing the accepted
names — callers must not fall back to `EQ` on an unknown operator (a dashboard widget filter
with a bad operator is a widget error, not a silently-wrong query).

## TypeScript

### Naming
- **Files**: PascalCase for components (`FilterBuilder.tsx`), camelCase for modules in SDK
- **Interfaces**: PascalCase, no `I` prefix (`TokenState`, `TokenProvider`)
- **Functions**: camelCase (`parseTokenExpiration()`)
- **Enums**: PascalCase name, UPPER_SNAKE_CASE values

### Style (kelta-web)
- 2-space indent, single quotes, semicolons required, trailing commas (ES5), 100 char width
- **Note**: `kelta-ui/app/.prettierrc` omits semicolons (`"semi": false`)

### ESLint Rules (kelta-web)
- `@typescript-eslint/no-explicit-any`: warn
- `@typescript-eslint/no-floating-promises`: error
- `@typescript-eslint/no-unused-vars`: error (ignore `_` prefix)
- `react-hooks/rules-of-hooks`: error
- `no-console`: warn (allow warn/error)
- `prefer-const`: error, `no-var`: error

### Imports
- Order: (1) type imports, (2) internal (`@kelta/sdk`, `@kelta/components`), (3) relative, (4) external
- Named exports preferred, barrel files for public API, default exports only for React components

### Error Handling
- Custom hierarchy: `KeltaError extends Error` with `statusCode`, `details`
- `mapAxiosError()` for HTTP error translation
- Zod schemas for runtime validation

### Type Safety
- Generics for type-safe resource ops: `ResourceClient<T>`
- Explicit return types on public methods
- Readonly properties for immutability
- **`jsonb`-backed fields carry objects on the wire, not JSON strings.** Send the object
  (`definition`, `triggerConfig`, …). Sending a JSON *string* lands in the column as
  `{"type":"jsonb","value":"<escaped json>"}`, which every reader then has to unwrap — see
  the defensive `"jsonb"`/`"value"` unwrap in `NatsTriggerFlowListener` /
  `FlowEventListener`. In `@kelta/sdk` these are typed `JsonObjectField | string` (the
  `string` arm covers rows written before that was understood; narrow on `typeof`).
- **A cast at a call site is a bug report about the type.** `as unknown as X` around an SDK
  request body means the request type disagrees with what the endpoint accepts — fix the
  type, don't widen the cast. Three flow call sites carried such casts, all hiding the same
  stale `triggerConfig?: string`; the casts also masked a `description: null` mismatch that
  only surfaced once they were removed.

### Component reuse (kelta-ui/app and kelta-web)

- Do not add a new shared list/table/filter/form component in `kelta-ui/app/src/components/` if one already exists for the same purpose in either `kelta-ui/app/` or `kelta-web/packages/components/`. Reuse or extend the existing one.
- The unification target for these families (DataTable, FilterBuilder, FieldRenderer, ResourceForm, RelatedList) is the library variant under `@kelta/components`. **Reuse that variant or extend it — never fork a new app-side variant.** App-side variants are being collapsed into thin re-exports of `@kelta/components`.
- `@kelta/components` is a public plugin surface. Breaking changes to its exported props need a deprecation window (additive props, `legacy*` flags) — never a hard cutover.
- **Record detail = one `RecordShell`.** Both `ObjectDetailPage` (end-user) and `ResourceDetailPage` (admin) are thin `variant="enduser"|"admin"` wrappers over `RecordShell` (`kelta-ui/app/src/components/record/`), which owns the page skeleton (loading/status + breadcrumb→header→(sectionNav+body+rail)→tabBar→belowTabs→dialogs slots; `sectionNav` is the left panel — sticky wrapper owned by the page holding the `RecordSectionNav` jump list with the `ActivityTimeline` docked beneath it on lg+; smaller screens and no-layout pages render the timeline inside `body` below the sections, never in `belowTabs`); the field body goes through `RecordDetailBody` (`LayoutFieldSections` when the layout has sections, else a variant fallback slot). **Do not add per-page detail bodies or a third section renderer** — extend `RecordShell`/`RecordDetailBody` (add a slot) so both stacks stay in sync. The kelta-web `LayoutRenderer` is a separate legacy public export (its related-list is a display-only stub); it is not on this path — don't wire new record-detail work through it.
- **Collection schema = one hook.** `useCollectionSchema` / its exported `fetchCollectionSchema` (`kelta-ui/app/src/hooks/useCollectionSchema.ts`) own the `CollectionSchema`/`FieldDefinition` types, the backend→UI field-type normalization, and the `['collection-schema', name]` query key (shared with the `CollectionStoreProvider` bulk preload). **Never declare a page-local `CollectionSchema` or schema fetcher** — a narrower fork silently drops fields the shared one resolves. `ResourceDetailPage` carried such a fork and its missing `trackHistory` left the admin record History tab permanently dead until it was collapsed onto the hook.

### FieldControl registry (unified record experience, slice 1)

- **Offline read/write belongs in the shared data hooks, gated on the optional `useOffline()` context** (`kelta-ui/app/src/offline/`). `useOffline()` returns `undefined` when no `OfflineProvider` is mounted (admin pages) — so the offline branch collapses to today's online-only behavior. **Do not add a second offline path or bypass these hooks:** online reads write through to the replica (`store.putRecords`) + `registerCollection`; offline reads serve `store.getAll`/`get`; offline writes go through `engine.queue` (never `apiClient` directly); reconnect flush lives in `OfflineProvider` (`engine.sync` on false→true). New end-user read/write surfaces reuse `useCollectionRecords`/`useRecord`/`useRecordMutation`/`usePageDataSources` rather than calling `apiClient` inline.
- **How a field type renders in view/edit/inline is owned by ONE registry** — `getFieldControl(type)` returns `{ View, Edit, InlineEdit, coerce, validate, editable }` (keyed by `FieldType`). Do not add per-page field switch/if-chains for rendering or editing a value; go through the registry. Lives at `kelta-ui/app/src/components/fieldControl/` in slice 1 (promoted to `@kelta/components` in slice 2).
- `View` **delegates to `FieldRenderer`** (parity + plugin override for free — `FieldRenderer` already consults `componentRegistry.getFieldRenderer`). `Edit`/`InlineEdit` **reuse** `LookupSelect`/`MultiPicklistSelect`/`RichTextEditor` (never re-implement a typed control). `InlineEdit` = `Edit` wrapped with commit-on-Enter/blur + cancel-on-Escape.
- `coerce` maps a raw editor value to the API payload; **returns `undefined` to OMIT a field** — server-computed types (`formula`/`rollup_summary`/`auto_number`/`encrypted`) are `editable:false` and never round-trip. `validate` mirrors `DefaultValidationEngine` (required, number, enum membership, json parse, geo range) and is **advisory only — the worker is the source of truth.**
- Plugins/consumers override any member via `registerFieldControl(type, partial)`; unknown types fall back to the string control.
- **Picklist label/color is presentation-only, never renaming the stored value (K-8).** `usePicklistDisplayMap(field)` (`kelta-ui/app/src/hooks/usePicklistOptions.ts`) fetches the same `/api/picklist-values` as `usePicklistOptions` and returns a memoised raw-value→`{label, color, description}` `Map`. `FieldRenderer`'s `picklist`/`multi_picklist` cases take an optional `picklistDisplayMap` prop: a mapped value renders the authored `label` on a badge colored to `color` (foreground auto-picked between black/white for WCAG AA contrast via relative luminance); an unmapped value (or no map) renders the raw value on the existing neutral badge — unchanged behavior. `FieldControlContext.picklistDisplayMap` carries the same map through `FieldControlView` to `FieldRenderer`, and `controls.tsx`'s `PicklistEdit` looks up the label per `<option>` while the `<option value>` (and thus `coerce`/PATCH payload) stays the raw stored value. `KanbanBoard`'s lane header and `FilterBar`'s filter-value badge take an analogous display map — lane **grouping** and filter **querying** always key on the raw value in `resolveLanes`/`FilterCondition.value`; only the header/badge text changes. Never rename a picklist value to "fix" its display — flows/SQL/filters compare against the stored value. Surfaces rendering many columns/fields at once (the object list table, the layout-driven record-detail body, the list page's filter bar) use `usePicklistDisplayMaps(fields)` (same file) instead of calling `usePicklistDisplayMap` per field — `FieldDefinition[]` is dynamic length, so a plain loop of the singular hook would violate the Rules of Hooks; it fans out one `useQueries` entry per picklist/multi_picklist field and returns a field-name-keyed `Record<string, PicklistDisplayMap>`. **A component accepting a `picklistDisplayMap`/`picklistDisplayMaps` prop is dead code until its caller actually passes it** — `ObjectDataTable`, `InlineFieldValue`/`LayoutFieldSections`, and `FilterBar` all had this exact gap (prop plumbed and unit-tested in isolation, never wired from `ObjectListPage`/`ObjectDetailPage`) until KLT-194's follow-up fix; when adding a new display-map consumer, trace it all the way to the page that owns the schema fields.

### Client layout rules (`RuleEngine` / `useLayoutRules`)

- The layout rule engine (`@kelta/components` `LayoutRenderer/clientRules/`) drives `compute` / `validate` / `default` / `transform` / `script` rules on four events — `onLoad` / `onChange` / `onBlur` / `onBeforeSave` (`LayoutRuleEvent`). Both record form pages already call `ruleEngine.runBeforeSave()` and block submit on `blocked`; **new client-rule behavior rides that gate — do not add a parallel submit hook.**
- **`SCRIPT` kind (slice 6, `ScriptLayoutRule`):** a general handler evaluating a **sandboxed `@kelta/formula` expression** — never `eval`/`window`/JS (the AST parser resolves identifiers from form scope only; a bare `window` is just an absent field). The result is treated as a **message**: a non-empty string surfaces on `target` (or the form when `target` is omitted); a boolean `true` uses the rule's static `message`; any falsy result clears it. On `onBeforeSave` a non-empty message **blocks** the submit; on live events it shows inline. Runtime errors **fail open** — the server record-event hook (slice 7, `RecordScriptHook`) is the authoritative gate; the client rule is bypassable via the API and is UX only.
- **Event naming:** the engine uses `onBeforeSave` (not the spec's idealized `onBeforeSubmit`). Persisted in the `layout-rules` system collection: `kind` column ∈ {`COMPUTE`,`VALIDATE`,`DEFAULT`,`TRANSFORM`,`SCRIPT`} (uppercase DTO), body carries `{expression, message?}` for SCRIPT; the client `LayoutRule.kind` is lowercase (`dtoToLayoutRule` maps DTO→engine). No migration — rides `layout_rule.body` JSON.

### Page builder config v2 (`ui-pages.config` JSON)

The page-builder stores its whole tree + page-level config inside the single `ui-pages.config` JSON column (`PageBuilderPage/pageConfig.ts`). The server is a pass-through and never parses it — all binding/layout resolution is client-side (preserving Cerbos/FLS). Canonical shape: the component tree lives at **`config.components`** (no `config.tree` wrapper); `variables`, `dataSources`, `access`, and `schemaVersion` are **siblings** of `components`.

- **Layout vocabulary (slice 2c):** `grid`/`row` are 12-col CSS-grid tracks (`grid grid-cols-12`), `column` is a vertical stack cell, `divider` is a leaf `<hr>`. The **only persisted layout state is per-child `span: {base, sm?, md?, lg?}`** (each `1..12`, mobile-first — `base` applies at all widths, prefixes override upward), mapped to Tailwind `col-span-*` + `sm:/md:/lg:` literals (`canvas/spanClasses.ts`; the 48 class names are spelled out so the Tailwind JIT scanner can see them — never `` `col-span-${n}` ``). **No pixel/row/column coordinates are stored.** The legacy `position {row,column,width,height}` is migrated away on builder load by `model/migrate.ts` (`width → span.base`, grouped by `row` into a root `grid` of `column`s) — idempotent; a page at `schemaVersion: 2` is never re-migrated.
- **`schemaVersion: 2`** marks a migrated/v2-authored page (slice 2c is the first writer, stamped on every save).
- **Save-path rule (load-bearing):** `mergeConfig` overlays a key ONLY when the `handleSavePage` call passes it. Widening `mergeConfig`'s accepted keys is useless unless the call passes them — every page-level sibling (`schemaVersion`/`variables`/`dataSources`/`access`) must be passed at the save call site or it is silently dropped (and a migrated page re-migrates on every reload). Test the `updateMutation.mutate` **payload**, not just `mergeConfig`'s return.
- **`config.layout` is inert legacy (slice 2c):** the create-form `layoutType` select still writes `config.layout.type` and it round-trips untouched, but the widget tree + `span` own layout — nothing reads `config.layout`. Removal deferred.
- **Bindings & expressions (slice 2d):** any prop value may be a literal or a binding object `{ $bind: "<token>", mode?: "path" | "expr" }`. `$bind` holds a **bare** token (e.g. `record.name`); `{{…}}` is display-only (and may be embedded in literal strings via `interpolate`). `mode:'path'` (default) resolves a dotted/`[n]` path via `getPath`; `mode:'expr'` runs the token through `@kelta/formula` `FormulaEvaluator` — because that parser is **flat-key only** (stops at `.`), `resolveBindings` flattens the referenced scope leaves before `evaluate`. **Resolution is 100% client-side** (the server round-trips `$bind` untouched, preserving Cerbos/FLS). Authoritative namespace: `record` (current record / repeat row), `vars` (page variables), `data.<source>` (on-load data source), `page` (route params/meta), `item` (per-row `list`/`repeater` scope). `getPath` **refuses `__proto__`/`constructor`/`prototype` tokens** (prototype-pollution guard). Caps: `MAX_PAGE_DATA_SOURCES = 12`, `MAX_REPEATER_ROWS = 200` (`model/limits.ts`). The resolved-node invariant holds: a widget's `Render` receives already-resolved props and must not re-resolve — the sole exception is `list`/`repeater`, which re-resolves children under each per-row `item` scope.
- **Typed inputs (slice 2f):** page-builder typed inputs **reuse** `LookupSelect`/`MultiPicklistSelect`/`RichTextEditor`/`ResourceForm` — never re-implement a typed control. The `form` widget renders via `@kelta/components` `ResourceForm`, upgraded through `setComponentRegistry` (`registerFormFieldRenderers.ts`) rather than a fork. Picklist source resolution is canonical: `fieldTypeConfig.globalPicklistId` present → `GLOBAL` source (`sourceId = globalPicklistId`), else `FIELD` (`sourceId = field.id`) — shared in `usePicklistOptions` (handles `fieldTypeConfig` as object **or** JSON string). **Client (Zod) validation is advisory only** — the worker validates required/type/unique on write and is the source of truth. **HTML-bearing output (`rich_text`, bound HTML) is sanitized through the same `FieldRenderer` `rich_text` path (`stripHtml`) — never `dangerouslySetInnerHTML` on unsanitized bound HTML.** All new widget strings go through `useI18n` (`builder.*`).
- **DnD convention:** the **page canvas** uses `@dnd-kit` (`PageBuilderPage/canvas/*`), scoped to that surface only. `PageLayoutsPage`/`MenuBuilderPage`/`FlowDesignerPage` stay on **native HTML5 DnD** — do not migrate them, and do not mix the two libs in one tree.
- **Menu item paths carry a query string (K-8 item 15).** A `ui-menu-items.path` of `/resources/<collection>?view=shared:<id>&pageSize=100` deep-links a nav tab straight into a shared view (or any `ObjectListPage` param: `filter`, `sort`, `pageSize`) — `parseResourcePath` (`kelta-ui/app/src/shells/EndUserShell/navTabs.ts`) is the single place that splits a `/resources/...` path into `{ collectionName, query? }`; `menuItemToTab` keeps the query on `NavTab.query` and `TopNavBar`'s `tabPath` appends it verbatim to the `…/o/<collection>` href (mobile nav sheet reuses the same `tabPath`). `AppHomePage`'s quick actions and `GlobalSearch`'s collection list only need the bare collection name, so they route through `parseResourcePath` too rather than re-deriving it — a raw `path.split('/')[0]` would leave the query string glued onto the last path segment. The active-tab match is index-based (which tab was clicked), not URL-based, so it already ignores the query string; `path` stays capped at 200 characters (`FieldDefinition.string("path", 200)` in `SystemCollectionDefinitions`), which a `view=shared:<uuid>` query comfortably fits inside.

## MCP tools (kelta-mcp)

### Friendly args → native JSON:API body
MCP admin tools expose camelCase, lowercase-friendly argument names to LLM callers and translate them to the native worker payload before hitting the gateway. The worker is strict — uppercase enums, `name` (not `fieldName`), `collectionId` (not `collectionName`), `uniqueConstraint` (not `unique`) — so do the translation at the tool boundary, not in the prompt.

- **Field types**: accept friendly aliases (`text`, `number`, `picklist`, `lookup`, …) and map to the uppercase `FieldType` enum the worker expects. Centralise the mapping in `FieldBodyBuilder.resolveNativeType` so `add_field` and `create_collection`'s nested field array stay in sync.
- **Per-type payload**: picklist fields need `attributes.fieldTypeConfig = {picklistSourceType, picklistSourceId}`; reference/lookup fields need `relationships.referenceCollectionId.data.id` + `attributes.relationshipName` (and optional `relationshipType`). Build these in the same helper so the on-the-wire shape can be unit-tested with WireMock JSON path matchers.
- **ID resolution**: accept either a UUID (`collectionId`, `referenceCollectionId`, `picklistSourceId`) or a friendly name (`collectionName`, `referenceCollection`) and resolve names via `GET /api/collections?filter[name][eq]=…`. UUIDs match `FieldBodyBuilder.UUID_PATTERN`; anything else triggers the lookup.

## CLI output & error contract

The `kelta` CLI's machine contract (output formats, JSON:API flattening, single-line
stderr error envelope carrying the platform's stable `code`, exit codes 0/1/2/3/4/5,
`--yes` gating for destructive commands) is owned by `specs/kelta-cli/README.md` →
Shared contracts. Discovery surface: `kelta manifest` (versioned command catalog with
JSON Schemas) and `packages/cli/AGENTS.md` (generated — `npm run gen:docs`).
