# Slice 1g — Render contract v2

> Child spec of [page-builder-parity.md](../page-builder-parity.md). Extends, never contradicts, the
> parent's [Backend changes (pass-through only)](../page-builder-parity.md#backend-changes-small--pass-through-only),
> [Page-level config (v2)](../page-builder-parity.md#page-level-config-v2), and
> [Child-spec template](../page-builder-parity.md#child-spec-template).
>
> Source-verified against the codebase on 2026-06-22 (Flyway head **V146**, next **V147**). Current
> `PageRenderService.CONTRACT_VERSION = "1.0"`. If code and this doc disagree, trust the code and fix
> this doc.

This is the **backend keystone** of the page-builder parity effort — ship it first. It is additive and
has **zero FE-feature dependency**: the only FE change is `CustomPage.tsx` reading two new sibling fields,
which it can ignore until later slices populate them.

---

## 1. Goal & scope

**Deliver** the v2 page render contract as a **pure pass-through** that surfaces the page's
`variables` and `dataSources` as distinct top-level fields alongside `tree` (which carries the **whole
`config` map verbatim**, so `tree.components` resolves exactly as today), and bumps the contract
`version` from `"1.0"` → `"2.0"`, while remaining **fully back-compatible** with v1 pages already stored
in `ui-pages.config`. The component tree stays at `config.components` — there is **no `config.tree`
wrapper**.

**In scope**
- New `PageRenderContract` Java record shape (7 fields, per parent
  [Backend changes](../page-builder-parity.md#backend-changes-small--pass-through-only)).
- `PageRenderService.render()` parsing the page's `config` once and projecting it into
  `{ variables, dataSources, tree }`, where `tree` is the whole parsed `config` map, `variables` =
  `config.variables` (else `[]`), and `dataSources` = `config.dataSources` (else `[]`). Null-safe.
- `CONTRACT_VERSION` constant `"1.0"` → `"2.0"`.
- `CustomPage.tsx` TS type updated to the new shape and to read sibling `variables` / `dataSources`
  (consumed for real in slices 2d/2e; surfaced here so the contract is stable from day one).
- Worker unit tests (mocked `QueryEngine`/`CollectionRegistry`) covering v2, v1 fallback,
  string-vs-Map config, null/missing config, and the unchanged 404 path. Existing
  `PageRenderControllerTest` fixture updated to the new constructor arity.
- A kelta-test-harness round-trip scenario (gateway → worker → real Postgres): PATCH a v2 `config`,
  then `GET …/render` and assert `variables`/`dataSources` surface and `tree.components` is
  byte-identical — a mocked-`QueryEngine` test can't catch a worker-side `config` JSON serialization
  drop (§6.4).

**Explicitly NOT in scope (hard constraints)**
- **NO server-side binding/expression resolution and NO dataSource fetching.** The server never reads a
  user record to resolve `{{record.name}}` or to execute a `dataSource` query. Doing so would fetch data
  **outside** the JSON:API path and leak Cerbos/FLS read-denied fields. The contract round-trips the
  `config` JSON untouched; `$bind` is never parsed server-side. (Parent
  [Key architecture decisions](../page-builder-parity.md#key-architecture-decisions-verified-against-the-code).)
- **NO DB migration / no schema change.** Everything new nests inside the existing `ui-pages.config`
  JSON column (§4).
- **NO data migration of existing v1 pages.** v1 pages are handled by a runtime fallback, not rewritten.
- **NO NATS / BeforeSaveHook change.** This is a *read* path; the existing `UIPageConfigEventPublisher` /
  `UIPageSlugHook` write-path broadcast is untouched (§5).
- **NO per-page authz** — that is slice 1h.

**Conforms to:** parent §"Backend changes (small — pass-through only)" (the 7-field record + version
bump + v1 fallback rule), §"Page-level config (v2)" (`variables`/`dataSources` shapes + `$bind`
round-trip rule), and §"Slice plan → 1g".

---

## 2. UI samples

Backend slice — no UI. Samples are request/response payloads of
`GET /api/pages/{slug}/render`.

### Request

```
GET /api/pages/dashboard/render
Host: app.<tenant>.kelta.io
Authorization: Bearer <jwt|pat>          # tenant resolved by gateway; RLS scopes the query
Accept: application/json
```

(No path/query params beyond `{slug}`. Tenant context is bound per request by `TenantContextFilter`;
the service only returns `published=true` + `active=true` pages.)

### (a) v2 page — `config` already authored in v2 shape

Stored `ui-pages.config` (object inside the JSON column — the tree lives at `config.components`;
`variables`/`dataSources`/`schemaVersion` are **siblings**, not nested under a `tree` key):

```json
{
  "schemaVersion": 2,
  "variables": [
    { "name": "statusFilter", "type": "string", "default": "open" }
  ],
  "dataSources": [
    { "name": "tickets", "collection": "tickets", "mode": "list",
      "filter": { "status": { "$bind": "vars.statusFilter", "mode": "path" } }, "limit": 25 }
  ],
  "layout": { "kind": "grid", "columns": 12 },
  "components": [
    { "id": "h1", "type": "heading", "props": { "text": "Open tickets" } },
    { "id": "t1", "type": "table",
      "props": { "source": { "$bind": "data.tickets", "mode": "path" } } }
  ]
}
```

Response `200 application/json` — `tree` is the **whole `config` map verbatim** (so `tree.components`
resolves); `variables`/`dataSources` are *also* surfaced as separate sibling fields read from
`config.*`:

```json
{
  "version": "2.0",
  "slug": "dashboard",
  "title": "Support Dashboard",
  "path": "/dashboard",
  "variables": [
    { "name": "statusFilter", "type": "string", "default": "open" }
  ],
  "dataSources": [
    { "name": "tickets", "collection": "tickets", "mode": "list",
      "filter": { "status": { "$bind": "vars.statusFilter", "mode": "path" } }, "limit": 25 }
  ],
  "tree": {
    "schemaVersion": 2,
    "variables": [
      { "name": "statusFilter", "type": "string", "default": "open" }
    ],
    "dataSources": [
      { "name": "tickets", "collection": "tickets", "mode": "list",
        "filter": { "status": { "$bind": "vars.statusFilter", "mode": "path" } }, "limit": 25 }
    ],
    "layout": { "kind": "grid", "columns": 12 },
    "components": [
      { "id": "h1", "type": "heading", "props": { "text": "Open tickets" } },
      { "id": "t1", "type": "table",
        "props": { "source": { "$bind": "data.tickets", "mode": "path" } } }
    ]
  }
}
```

Note: `tree` carries the entire `config` (siblings included); the FE reads `tree.components` exactly as
today. `$bind` markers are echoed verbatim — the server does not resolve them.

### (b) legacy v1 page — no `variables`/`dataSources` siblings

Stored `ui-pages.config` (the legacy shape — `components`/`layout` at the top level, no
`variables`/`dataSources` keys; structurally identical to a v2 page that simply has no page-level
siblings yet):

```json
{
  "components": [
    { "id": "c1", "type": "heading", "props": { "text": "Welcome" } },
    { "id": "c2", "type": "text", "props": { "content": "Legacy page" } }
  ],
  "layout": { "kind": "stack" }
}
```

Response `200 application/json` — `tree` is the **whole `config` map verbatim** (same pass-through as
the v2 case); `variables`/`dataSources` default to `[]` because the keys are absent; `version` is
`"2.0"`:

```json
{
  "version": "2.0",
  "slug": "welcome",
  "title": "Welcome",
  "path": "/welcome",
  "variables": [],
  "dataSources": [],
  "tree": {
    "components": [
      { "id": "c1", "type": "heading", "props": { "text": "Welcome" } },
      { "id": "c2", "type": "text", "props": { "content": "Legacy page" } }
    ],
    "layout": { "kind": "stack" }
  }
}
```

The FE still reads `tree.components`, so v1 pages render byte-identically — the v1 and v2 responses are
produced by the **same** pass-through (`tree:<wholeConfig>`); the only difference is whether
`variables`/`dataSources` are empty or populated. Unknown/unpublished slug → `404` with no body
(unchanged).

---

## 3. Data & API contracts

### 3.1 Endpoint (unchanged)

`GET /api/pages/{slug}/render` →
- `200` `application/json` body = `PageRenderContract` (new shape below), or
- `404` (no body) when the slug is unknown, unpublished, inactive, or blank.

Controller (`PageRenderController.render`) is unchanged — it maps `Optional.empty()` → `notFound()`.

### 3.2 New `PageRenderContract` Java record (7 fields)

Matches parent §"Backend changes" exactly:

```java
public record PageRenderContract(
        String version,
        String slug,
        String title,
        String path,
        List<Map<String, Object>> variables,
        List<Map<String, Object>> dataSources,
        Map<String, Object> tree
) {
}
```

Field semantics:

| Field | Type | Source | v1 fallback |
|-------|------|--------|-------------|
| `version` | `String` | `CONTRACT_VERSION` constant | `"2.0"` (always — version describes the *contract*, not the stored config) |
| `slug` | `String` | `page.get("slug")` | unchanged |
| `title` | `String` | `page.get("title")` | unchanged |
| `path` | `String` | `page.get("path")` | unchanged |
| `variables` | `List<Map<String,Object>>` | `config.get("variables")` if a `List` | `[]` |
| `dataSources` | `List<Map<String,Object>>` | `config.get("dataSources")` if a `List` | `[]` |
| `tree` | `Map<String,Object>` | the **whole parsed `config` map** (verbatim — both v1 and v2) | whole config |

`variables` and `dataSources` are surfaced as raw `List<Map<String,Object>>` — **no typed DTO**, no
parsing of their internals (consistent with the existing `tree` being raw `Map<String,Object>`). The
server treats them as opaque JSON to round-trip.

### 3.3 Version transition

`PageRenderService.CONTRACT_VERSION`: **`"1.0"` → `"2.0"`** (string literal). Bumped because the
contract is evolving (new sibling `variables`/`dataSources` fields surfaced from `config`). Every
response — including a v1 page with no such keys — reports `"2.0"`.

### 3.4 Parsing rules in `render()` (authoritative)

Given the page row's `config` value (which the runtime delivers as **either** a `Map<String,Object>`
**or** a JSON `String`, per the existing config-parse handling absorbed by `parseConfig` — see §5):

1. **Parse `config` once** into a `Map<String,Object>` (`parsedConfig`):
   - `config instanceof Map` → cast directly.
   - `config instanceof String` and non-blank → `objectMapper.readValue(str, MAP_TYPE)`; on parse failure
     log a warning and treat as empty (`Map.of()`) — preserves the current resilient behavior.
   - `null` / blank / unparseable → `Map.of()`.
2. **`tree`** = the **whole `parsedConfig` map, verbatim** — for **both** v1 and v2 pages. There is no
   `config.tree` key to unwrap; the tree always lives at `config.components`, so handing the FE the whole
   `config` makes `tree.components` resolve. Never `null` — defaults to `Map.of()`.
3. **`variables`** = `parsedConfig.get("variables")` **if it is a `List`**, coerced to
   `List<Map<String,Object>>`, **else** `List.of()`.
4. **`dataSources`** = `parsedConfig.get("dataSources")` **if it is a `List`**, coerced likewise, **else**
   `List.of()`.
5. Everything is null-safe: a missing/null/empty/garbage `config` yields
   `version:"2.0", variables:[], dataSources:[], tree:{}`.

**Back-compat rule (precise):** There is **no v1-vs-v2 discriminator** — both kinds run through the
identical projection. `tree` is always the whole parsed `config`; `variables`/`dataSources` are whatever
those `config` keys hold (empty for a legacy page that lacks them, populated for a v2 page). The contract
never inspects `schemaVersion` and never looks for a `tree` key. This keeps every existing v1 page
rendering byte-identically through `tree.components` while v2 pages additionally surface their
page-level siblings.

### 3.5 FE TypeScript contract (consumed in `CustomPage`)

```ts
/** Versioned page render contract returned by GET /api/pages/{slug}/render. */
interface PageRenderContract {
  version: string
  slug: string
  title: string | null
  path: string | null
  variables: PageVariable[]
  dataSources: PageDataSource[]
  tree: { components?: PageNode[]; layout?: unknown }
}
```

`PageVariable` / `PageDataSource` are the parent's
[Page-level config (v2)](../page-builder-parity.md#page-level-config-v2) types
(`pageConfig.ts`). For 1g, importing them (or typing the two arrays as those interfaces) is sufficient;
this slice does **not** wire them into rendering — it only stops them from being dropped on the floor.
`tree.components` continues to drive `PageTreeRenderer`, so runtime output is unchanged.

---

## 4. DB migrations

**None — stored in `ui-pages.config` JSON, no DDL.**

Verified: `ui-pages` (`SystemCollectionDefinitions.uiPages()`,
`runtime-core/.../model/system/SystemCollectionDefinitions.java:341`) already declares
`FieldDefinition.json("config")`. `variables`, `dataSources`, and `components` all nest inside that
single JSON column (and `tree` is just that whole `config` map echoed back, not a stored key) — no new
columns, no Flyway migration. Flyway head confirmed **V146**
(`V146__add_saml_provider.sql` is the highest in
`kelta-worker/src/main/resources/db/migration/`); next available is **V147**, but this slice adds none.

---

## 5. File-by-file code changes

Java 25, records, JDBC (no JPA). This is a **read** path (no system-collection *write*), so **no new
NATS subject and no `BeforeSaveHook`** are required — the existing `UIPageConfigEventPublisher` /
`UIPageSlugHook` write-path broadcast is untouched. No gateway route change (`/api/pages/**` already
registered).

### 5.1 `kelta-worker/src/main/java/io/kelta/worker/service/PageRenderContract.java` — modify

Old (5 fields):

```java
public record PageRenderContract(
        String version, String slug, String title, String path,
        Map<String, Object> tree) {}
```

New (7 fields — add `List` import):

```java
import java.util.List;
import java.util.Map;

public record PageRenderContract(
        String version,
        String slug,
        String title,
        String path,
        List<Map<String, Object>> variables,
        List<Map<String, Object>> dataSources,
        Map<String, Object> tree
) {}
```

Update the Javadoc to note `variables`/`dataSources` are pass-through page-level config and that the
server never resolves bindings.

### 5.2 `kelta-worker/src/main/java/io/kelta/worker/service/PageRenderService.java` — modify

- Bump the constant:

```java
static final String CONTRACT_VERSION = "2.0";   // was "1.0"
```

- Replace the `extractTree(...)`-only projection with a single parse + project. Sketch of the new
  `render()` tail and helpers (keeps the existing query/filter block exactly as-is):

```java
Map<String, Object> page = result.data().getFirst();
Map<String, Object> config = parseConfig(page.get("config"));   // never null
return Optional.of(new PageRenderContract(
        CONTRACT_VERSION,
        asString(page.get("slug")),
        asString(page.get("title")),
        asString(page.get("path")),
        extractObjectList(config.get("variables")),
        extractObjectList(config.get("dataSources")),
        config));                                                // tree = whole config, verbatim
```

```java
/** Parse the page's {@code config} JSON (delivered as a Map or a serialized String) once. */
@SuppressWarnings("unchecked")
private Map<String, Object> parseConfig(Object config) {
    if (config instanceof Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
    if (config instanceof String str && !str.isBlank()) {
        try {
            return objectMapper.readValue(str, MAP_TYPE);
        } catch (RuntimeException e) {
            log.warn("Failed to parse ui-page config JSON: {}", e.getMessage());
        }
    }
    return Map.of();
}

/** Page-level {@code variables}/{@code dataSources}; opaque pass-through, default empty. */
@SuppressWarnings("unchecked")
private static List<Map<String, Object>> extractObjectList(Object value) {
    if (value instanceof List<?> list) {
        return (List<Map<String, Object>>) (List<?>) list;
    }
    return List.of();
}
```

Notes:
- The `tree` field is the parsed `config` map handed back **verbatim** — no `tree`-key unwrap, no
  v1-vs-v2 branch. `parseConfig` absorbs and generalizes the old `extractTree(Object)` (which combined
  parse + cast); the former `extractTree(Map)` discriminator is **deleted** (there is no `config.tree`).
- `MAP_TYPE`, `objectMapper`, `asString`, `log`, the `QueryEngine`/`CollectionRegistry` lookup, the
  `slug+published+active` `FilterCondition`s, and the `result.data().isEmpty()` → `Optional.empty()`
  guard are **unchanged**.
- Still returns `Optional.empty()` for blank slug, missing `ui-pages` collection, and zero matches →
  controller still 404s.

### 5.3 `kelta-ui/app/src/pages/app/CustomPage/CustomPage.tsx` — modify

- Update the local `PageRenderContract` interface to the 7-field shape (§3.5), typing `variables` /
  `dataSources` as `PageVariable[]` / `PageDataSource[]` (import from
  `@/pages/PageBuilderPage/pageConfig` once those types land, or `unknown[]` as an interim if 1g ships
  before 2d — prefer the typed import; the types already exist per parent §"Page-level config (v2)").
- Rendering is unchanged: still
  `<PageTreeRenderer components={contract.tree?.components ?? []} tenantSlug={...} />`. Reading the two
  new siblings is wired up by slices 2d/2e; 1g only widens the type so they are not lost. Do **not**
  resolve bindings or fetch data sources here.

### 5.4 No other production code changes

Confirmed by grep: the only consumers of `PageRenderContract` are `CustomPage.tsx`,
`PageRenderController.java` (unchanged — it is generic over the record), and the two worker test classes.
`PageTreeRenderer.tsx` reads `tree.components` and is untouched.

---

## 6. Test plan

Worker unit tests — mocked `QueryEngine` + `CollectionRegistry`, real `JsonMapper` (no DB), matching the
existing idiom in
`kelta-worker/src/test/java/io/kelta/worker/service/PageRenderServiceTest.java`
(`@ExtendWith(MockitoExtension.class)`, `JsonMapper.builder().build()`, `QueryResult.of(...)` helper) per
`.claude/docs/testing.md`.

### 6.1 `PageRenderServiceTest` (extend existing class)

| Test | Setup | Assert |
|------|-------|--------|
| `rendersV2Page` (new) | `config` Map with top-level `components`, `variables`(1), `dataSources`(1) | `version=="2.0"`; `tree` == whole config (contains `components`, `variables`, `dataSources`); `tree.get("components")` is the components list; `variables` size 1; `dataSources` size 1 |
| `v1WholeConfigToTree` (new) | `config` Map with `components` but **no** `variables`/`dataSources` keys | `tree` == whole config (contains `components`); `variables` empty; `dataSources` empty; `version=="2.0"` |
| `parsesStringConfig` (update existing) | `config` as JSON **String** `{"components":[...]}` | `tree` contains `components`; lists default empty — proves String path parses once |
| `v1StringConfigToTree` (new) | `config` as JSON **String** with top-level `components`, no siblings | whole parsed config → `tree`; lists empty |
| `nullConfig` (new) | `config` == `null` | `tree` empty map; `variables`/`dataSources` empty; `version=="2.0"` (no NPE) |
| `garbageStringConfig` (new) | `config` == `"not json"` | `tree` empty; lists empty; warns, no throw |
| `rendersPublishedPage` (update existing) | as today | bump expected `version` to `"2.0"`; keep the `slug+published+active` filter assertions verbatim |
| `notFoundWhenNoMatch` (keep) | zero rows | `Optional.empty()` |
| `emptyWhenNoCollection` (keep) | `collectionRegistry.get("ui-pages")==null` | empty; `verifyNoInteractions(queryEngine)` |
| `blankSlug` (keep) | `"  "` | empty; registry never touched |

The existing `slug+published+active` `FilterCondition` assertions stay — this slice does not change the
query.

### 6.2 `PageRenderControllerTest` (update existing fixture — compile fix)

The current test constructs `new PageRenderContract("1.0", "home", "Home", "/home", Map.of(...))` — the
5-arg form. The new record is 7-arg, so this **will not compile** until updated. Change the fixture to:

```java
PageRenderContract contract = new PageRenderContract(
        "2.0", "home", "Home", "/home", List.of(), List.of(),
        Map.of("components", List.of()));
```

Keep both controller tests (`rendersPage` 200, `notFound` 404) otherwise unchanged — controller behavior
is identical.

### 6.3 FE

No new Vitest needed for 1g (no behavior change in `CustomPage` — type-only widening). `npm run typecheck`
in `kelta-ui/app` must pass with the new interface. The runtime-render Vitest coverage arrives with slice
2a. Playwright: existing custom-page e2e must stay green (v1 pages render unchanged); no new e2e required
for this pass-through slice.

### 6.4 Integration (kelta-test-harness)

**Add `PageRenderV2ScenarioTest`** (mirror `UiPageCreateScenarioTest`'s style:
`extends ScenarioBase`, `auth.loginAsAdmin()`, `tenants.slugForTenantId(...)`,
`gatewayClientWithToken(token)`, `waitForStatus(...)` before the first call). The mocked worker unit
tests prove the *parsing* matrix but run with no DB — they cannot catch a worker-side `config` JSON
serialization drop on the round-trip through real Postgres. Per the project's
[DB-constraint / serialization test-gap convention](../../testing.md#real-db-guard-for-constraint-bearing-writes)
(mocked-QueryEngine tests can't see persistence drift), this slice adds **exactly one** real-DB
round-trip:

1. Wait for `/{slug}/api/ui-pages` to be live, then **PATCH** (or create) a page whose `config` is a
   **v2** shape: a `variables` array (≥1), a `dataSources` array (≥1), and a `components` tree (with a
   `layout` sibling) — all inside the single `config` JSON column. Set `published=true` + `active=true`
   so the render gate passes.
2. **`GET /{slug}/api/pages/{pageSlug}/render`** through the gateway.
3. Assert: `version == "2.0"`; the response `variables`/`dataSources` arrays surface with the authored
   contents (proves they survived JSON (de)serialization in Postgres → worker); and
   `tree.get("components")` is **byte-identical** to the components list that was written
   (`assertThat(tree.get("components")).isEqualTo(authoredComponents)`), proving the whole-`config`
   pass-through round-trips with no drop or reordering.

This is the read-side analogue of `UiPageCreateScenarioTest`'s write-path guard, scoped to the v2
`config` serialization that the unit tests structurally cannot exercise.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/architecture.md` | `PageRenderService` row: new 7-field contract shape (`version, slug, title, path, variables, dataSources, tree`); `version "2.0"`; note it stays a **pass-through that resolves no bindings/dataSources, preserving Cerbos/FLS** (all data still flows through JSON:API in later slices). |
| `.claude/docs/status.md` | Page-builder row: note render contract is at **v2** (`variables`/`dataSources`/`tree` surfaced, v1 back-compat); subsystem still UI-stub for binding/logic until 2d/2e. |

(Per parent §"Docs to update", `conventions.md` documents the full page-config-v2 `$bind`/namespace
contract — that lands with the FE binding slices 2a/2d, not 1g. This backend slice touches only the two
rows above.)

---

## 8. Risks & open questions

- **Renderer must tolerate `version:"2.0"` with empty arrays.** Every v1 page now reports `"2.0"` with
  `variables:[]` / `dataSources:[]` and the whole config as `tree`. Any FE consumer that branches on
  `version` must treat `"2.0"` + empty arrays as the normal/legacy case — **not** assume `"2.0"` implies
  populated config. `CustomPage` already renders purely off `tree.components`, so it is safe; document this
  invariant so slices 2a–2e don't add a brittle `version === '2.0'` gate.
- **Pass-through is load-bearing for security.** Resolving bindings or executing `dataSources` server-side
  would bypass Cerbos/`CerbosFieldSecurityAdvice` FLS and leak read-denied fields. Keep `render()` a
  projection only; all record access must remain on the JSON:API path (enforced in 2d/2e on the client via
  the authorized API). Reviewers should reject any server-side `$bind`/dataSource evaluation in this or
  downstream slices.
- **Known breaking consumer of the old shape:** `PageRenderControllerTest` constructs the 5-arg record and
  **will fail to compile** until updated (§6.2). This is the only such site (grep-verified) — production
  code (`PageRenderController`) is generic over the record and needs no change.
- **`config` shape (resolved — no discriminator):** the contract does **not** branch on `schemaVersion`
  or on a `tree` key; `tree` is always the whole parsed `config`, and `variables`/`dataSources` are
  whatever those `config` keys hold. The component tree stays at `config.components` (per parent
  §"Page-level config (v2) → Canonical storage") — there is **no `config.tree` wrapper**, so 2c's
  `migrate.ts` and the builder's `handleSavePage` must keep writing `components` (plus
  `variables`/`dataSources`/`schemaVersion` as siblings) at the top level of `config`, never nest a
  `tree` key. 1g needs no change for that; the slices simply share the `config.components` convention.
- **Coercion is unchecked-cast pass-through.** `extractObjectList` does a `(List<Map<String,Object>>)`
  cast without per-element validation (consistent with `tree`'s existing raw-`Map` cast). Acceptable
  because the values are opaque JSON serialized straight back to the client; no element is dereferenced
  server-side. No `@Entity`/JPA involved (Critical Rule 2 upheld).
- **`PageRenderService` size:** small (~96 lines) and not flagged in `concerns.md`; this change keeps it
  small. No oversized-file hazard.
