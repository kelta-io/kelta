# Slice 1h — Per-page authorization (optional)

> Child spec of [page-builder-parity.md](../page-builder-parity.md). Extends, never contradicts, the
> parent's [Backend changes (pass-through only)](../page-builder-parity.md#backend-changes-small--pass-through-only)
> (the "Per-page authz (optional, slice 1h)" bullet) and [Page-level config (v2)](../page-builder-parity.md#page-level-config-v2)
> (the `access?: { requiredPermission?: string }` key). Builds directly on
> [1g — Render contract v2](./1g-render-contract-v2.md) — that slice's 7-field `PageRenderContract`
> and `parseConfig` helper are assumed to already be in place.
>
> Source-verified against the codebase on 2026-06-22 (Flyway head **V146**, next **V147**). If code
> and this doc disagree, trust the code and fix this doc.

This is the **optional, last** backend slice. It is additive and **config-driven**: a page with no
`config.access.requiredPermission` behaves exactly as today (the published+active+tenant gate is the
only visibility check). A page that declares a required permission is gated on that **system
permission**, checked at render time with the **same in-controller `profile_system_permission` lookup
that `/api/admin/**` controllers use** — and a denial returns **404, not 403**, to match the existing
unknown-slug privacy posture (we do not leak that a restricted page exists).

---

## 1. Goal & scope

**Deliver** an opt-in per-page authorization gate. When a page's stored `config` contains
`access.requiredPermission` (a `profile_system_permission.permission_name` value such as `VIEW_SETUP`),
`PageRenderService.render()` resolves the caller's profile, checks whether that profile is **granted**
the permission, and on denial returns `Optional.empty()` so `PageRenderController` returns **404** with
no body — indistinguishable from an unknown/unpublished slug. When the key is absent, behavior is
**byte-identical to 1g** (the existing slug + `published` + `active` filter is the whole gate).

**In scope**

- A new `config.access.requiredPermission` (optional `String`) key, nested inside the existing
  `ui-pages.config` JSON column (no DB change).
- `PageRenderService.render(...)` gaining the caller's `profileId` and, **only when
  `requiredPermission` is present**, performing the system-permission check via
  `BootstrapRepository.findProfileSystemPermissions(profileId)` — the exact pattern
  `TenantOtlpTargetController` and `UserPermissionsController` use (architecture.md
  ["Worker-side system-permission check"](../../architecture.md)).
- `PageRenderController` resolving `profileId` from the gateway-forwarded `X-User-Profile-Id` header
  via the existing `CerbosPermissionResolver`, and passing it into `render(...)`.
- A small FE inspector control — a **page-settings "Access" field** (permission picker) — that writes
  `config.access.requiredPermission` from the tenant's system-permission catalog. It is hosted **inside
  the page-settings drawer that slice 2d creates** (1h does not introduce its own panel and must not
  assume a pre-existing one), and reuses the 2b inspector field-kind machinery (a `select`-kind
  page-settings field). **The FE control therefore depends on 2d**; the backend gate (§3.3, §5.1–5.2)
  is independent of the FE and can ship first.
- Worker unit tests: allow → contract present; deny → `Optional.empty()` (404); absent key →
  unchanged back-compat (no permission lookup at all).
- A **kelta-test-harness `PageRenderAuthzScenarioTest`** (real Postgres + RLS, gateway → worker):
  a restricted page + a granted and a denied profile in one tenant → `200` vs `404` through the real
  stack, proving RLS-scoped grant reads and `X-User-Profile-Id` forwarding (§6.3, per the DB-constraint
  test-gap convention).
- i18n for the FE Access field label, the "Anyone (published)" option, and the 404 privacy-hint string
  (no hardcoded author-facing literals — §5.4).

**Explicitly NOT in scope (hard constraints)**

- **NO DB column, NO Flyway migration, NO data migration.** `requiredPermission` lives inside the
  existing `config` JSON. Promoting it to a real `ui_page` column is the **only** thing that would
  justify a future `V147`, and is deliberately **not** done here (§4).
- **NO NATS / BeforeSaveHook change.** This is a **read** path. The existing
  `UIPageConfigEventPublisher` (`kelta.config.page.changed.<pageId>`) / `UIPageSlugHook` write-path
  broadcast is untouched; no new subject.
- **NO Cerbos PDP call.** Per architecture.md, system permissions are **not** checked via a Cerbos
  `CheckResources` call in the worker — they are a DB lookup against `profile_system_permission`.
  We reuse that DB lookup; we do **not** introduce a Cerbos request.
- **NO 403.** A denial must be a **404** (privacy parity with unknown slug). Returning 403 would leak
  the existence of a restricted page; reviewers should reject any 403 path here.
- **NO server-side binding/dataSource resolution** (the 1g invariant stands — `render()` remains a
  projection over the `config` JSON; the only new behavior is the gate).
- **NO gateway route change.** `/api/pages/**` is already a registered static route; this slice adds
  no new top-level path.

**Conforms to:** parent §"Backend changes" (the 1h bullet — "check it via the same in-controller Cerbos
system-permission mechanism `/api/admin/**` uses; deny → `Optional.empty()` → 404"),
§"Page-level config (v2)" (`access?: { requiredPermission?: string }`), and architecture.md
§"Authorizing a new endpoint → Worker-side system-permission check".

---

## 2. UI samples

### 2.1 Inspector — page-settings "Access" control

A new **page-settings** field (not a per-widget prop) hosted **inside the page-settings drawer that
slice 2d creates** (1h adds no panel of its own and does not assume one pre-exists), rendered by the
same schema-driven inspector loop introduced in 2b. It is a `select`-kind field whose options are
the tenant's system-permission catalog (the 15 permissions in
[`SystemPermissionChecklist.tsx`](../../../../kelta-ui/app/src/components/SecurityEditor/SystemPermissionChecklist.tsx) —
`VIEW_SETUP`, `MANAGE_USERS`, `MANAGE_REPORTS`, …), plus a default "Anyone (published)" no-restriction
option that clears the key.

```
┌─ Page settings ─────────────────────────────────┐
│  Name        [ Support Dashboard            ]    │
│  Slug        [ dashboard                    ]    │
│  Path        [ /dashboard                   ]    │
│  Published   [x]                                 │
│                                                  │
│  ── Access ──────────────────────────────────   │
│  Required permission                             │
│  ┌────────────────────────────────────────────┐ │
│  │ Anyone (published)                       ▾ │ │  ← default; clears access.requiredPermission
│  └────────────────────────────────────────────┘ │
│    Options (grouped, from SystemPermissionChecklist):
│      Anyone (published)                          │
│      ── Application Setup ──                      │
│      View Setup            (VIEW_SETUP)           │
│      Customize Application (CUSTOMIZE_APPLICATION)│
│      ── User & Group Management ──                │
│      Manage Users         (MANAGE_USERS)         │
│      …                                           │
│  ⓘ Users without this permission get a 404 — the │
│     page is hidden, not shown as forbidden.      │
└──────────────────────────────────────────────────┘
```

Selecting **"View Setup"** writes `config.access.requiredPermission = "VIEW_SETUP"`; selecting
**"Anyone (published)"** deletes the `access` key (or sets `access.requiredPermission` to `undefined`,
which is stripped on save). The label/description shown come from the catalog entry
(`label: 'View Setup'`), the stored value is the raw `name` (`VIEW_SETUP`).

### 2.2 Stored `config` deltas

**Restricted page** (stored `config` excerpt — v2 shape from 1g, with the new `access` sibling).
Per the parent's [canonical storage](../page-builder-parity.md#page-level-config-v2), the tree lives
at **`config.components`** — there is **no `config.tree` wrapper** in stored config; `access`,
`variables`, `dataSources`, and `schemaVersion` are siblings of `components`:

```json
{
  "schemaVersion": 2,
  "access": { "requiredPermission": "VIEW_SETUP" },
  "variables": [],
  "dataSources": [],
  "components": [
    { "id": "h1", "type": "heading", "props": { "text": "Admin Dashboard" } }
  ]
}
```

**Unrestricted page** — no `access` key at all (identical to a 1g page):

```json
{
  "schemaVersion": 2,
  "variables": [],
  "dataSources": [],
  "components": [ { "id": "h1", "type": "heading", "props": { "text": "Home" } } ]
}
```

(The render *contract* below still carries a `tree` field — it is the whole `config` map verbatim, so
`tree.components` resolves — but that `tree` is a contract field, **not** a stored-config wrapper.)

### 2.3 Render responses (`GET /api/pages/{slug}/render`)

Tenant + JWT/PAT resolved by the gateway; `X-User-Profile-Id` forwarded on every request.

**(a) Allowed** — caller's profile **is granted** `VIEW_SETUP` → `200`, the full 1g v2 contract:

```http
GET /api/pages/dashboard/render        # X-User-Profile-Id: <profile-with-VIEW_SETUP>
→ 200 application/json
{
  "version": "2.0",
  "slug": "dashboard",
  "title": "Admin Dashboard",
  "path": "/dashboard",
  "variables": [],
  "dataSources": [],
  "tree": { "components": [ { "id": "h1", "type": "heading", "props": { "text": "Admin Dashboard" } } ] }
}
```

The `access` key is **not** echoed in the contract — it is consumed server-side, not part of the render
output (the FE never needs it at render time; the editor reads it from the page record it already
fetches for editing).

**(b) Denied** — caller's profile is **not** granted `VIEW_SETUP` → `404`, **no body** (identical to an
unknown slug — page existence is not leaked):

```http
GET /api/pages/dashboard/render        # X-User-Profile-Id: <profile-without-VIEW_SETUP>
→ 404 Not Found
(no body)
```

**(c) Absent key** — page has no `access.requiredPermission` → unchanged 1g behavior; the
permission lookup is **never performed** (no DB hit), the published+active+tenant gate is the whole
check:

```http
GET /api/pages/home/render             # any authenticated profile
→ 200 application/json
{ "version": "2.0", "slug": "home", ... }   # exactly as 1g
```

---

## 3. Data & API contracts

### 3.1 `config.access` shape (inside `ui-pages.config` JSON)

```ts
// pageConfig.ts — extends the v2 PageConfig (parent §"Page-level config (v2)")
interface PageAccess {
  /** A profile_system_permission.permission_name value, e.g. "VIEW_SETUP". Absent ⇒ no restriction. */
  requiredPermission?: string
}
interface PageConfig {
  layout?: PageLayout
  components?: PageComponent[]
  // v2 (1g):
  variables?: PageVariable[]
  dataSources?: PageDataSource[]
  tree?: PageTree
  schemaVersion?: 2
  // 1h:
  access?: PageAccess
}
```

The value is an **opaque string** server-side — the worker compares it against
`profile_system_permission.permission_name`; it does not validate it against the catalog. (An unknown
permission name simply matches no granted row ⇒ deny ⇒ 404, which is the safe default.) The
`access` key is **not** surfaced on `PageRenderContract` (it is consumed, not projected).

### 3.2 Endpoint (unchanged signature, new gate)

`GET /api/pages/{slug}/render` →
- `200` `application/json` = `PageRenderContract` (7-field, from 1g) when the slug matches a
  published+active page in the tenant **and** (no `requiredPermission`, **or** the caller's profile is
  granted it), else
- `404` (no body) when the slug is unknown / unpublished / inactive / blank **or** the caller lacks the
  required permission. **No new status code; no 403.**

### 3.3 `render(...)` change (authoritative)

The check inserts **after** the existing slug+published+active query succeeds (so an unknown page still
404s before any permission work) and **after** `parseConfig` (1g), but **before** building the
`PageRenderContract`. The current 1g tail:

```java
Map<String, Object> page = result.data().getFirst();
Map<String, Object> config = parseConfig(page.get("config"));   // 1g helper, never null
return Optional.of(new PageRenderContract(
        CONTRACT_VERSION,
        asString(page.get("slug")), asString(page.get("title")), asString(page.get("path")),
        extractObjectList(config.get("variables")),
        extractObjectList(config.get("dataSources")),
        extractTree(config)));
```

becomes (signature gains `profileId`; gate inserted before the `return`):

```java
public Optional<PageRenderContract> render(String slug, String profileId) {
    // ... unchanged blank-slug guard, ui-pages lookup, slug+published+active query ...
    Map<String, Object> page = result.data().getFirst();
    Map<String, Object> config = parseConfig(page.get("config"));

    // --- 1h: optional per-page authorization gate ---------------------------
    String requiredPermission = extractRequiredPermission(config);
    if (requiredPermission != null && !hasSystemPermission(profileId, requiredPermission)) {
        // Deny is a 404, not a 403 — never leak that a restricted page exists.
        return Optional.empty();
    }
    // ------------------------------------------------------------------------

    return Optional.of(new PageRenderContract(
            CONTRACT_VERSION,
            asString(page.get("slug")), asString(page.get("title")), asString(page.get("path")),
            extractObjectList(config.get("variables")),
            extractObjectList(config.get("dataSources")),
            extractTree(config)));
}
```

New private helpers in `PageRenderService` (the permission check is the **same** pattern as
`TenantOtlpTargetController.requireSetupPermission` and `SupersetGuestTokenService.hasSystemPermission`,
but returning `Optional.empty()` instead of throwing):

```java
/** config.access.requiredPermission, or null when the page declares no restriction. */
@SuppressWarnings("unchecked")
private static String extractRequiredPermission(Map<String, Object> config) {
    if (config.get("access") instanceof Map<?, ?> access
            && access.get("requiredPermission") instanceof String perm && !perm.isBlank()) {
        return perm;
    }
    return null;
}

/**
 * True iff {@code profileId} is granted {@code permission} in profile_system_permission.
 * Same lookup admin controllers use — BootstrapRepository.findProfileSystemPermissions —
 * NOT a Cerbos PDP call (system permissions are a DB grant, per architecture.md).
 */
private boolean hasSystemPermission(String profileId, String permission) {
    if (profileId == null || profileId.isBlank()) {
        return false;                              // no identity ⇒ deny ⇒ 404
    }
    return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
            .anyMatch(p -> permission.equals(p.get("permission_name"))
                    && Boolean.TRUE.equals(p.get("granted")));
}
```

**Permission-check helper found in the real code (reuse this exact mechanism):**

- Identity: [`CerbosPermissionResolver.getProfileId(HttpServletRequest)`](../../../../kelta-worker/src/main/java/io/kelta/worker/service/CerbosPermissionResolver.java)
  reads `request.getHeader("X-User-Profile-Id")` (set by the gateway's
  `RouteAuthorizationFilter.forwardWithHeaders` on **every** forwarded request, including
  `/api/pages/**`).
- Grant lookup: [`BootstrapRepository.findProfileSystemPermissions(String profileId)`](../../../../kelta-worker/src/main/java/io/kelta/worker/repository/BootstrapRepository.java)
  → `List<Map<String,Object>>` rows of
  `SELECT permission_name, granted FROM profile_system_permission WHERE profile_id = ?`. The exact
  `.anyMatch(p -> PERMISSION.equals(p.get("permission_name")) && Boolean.TRUE.equals(p.get("granted")))`
  predicate is copied verbatim from
  [`TenantOtlpTargetController.requireSetupPermission`](../../../../kelta-worker/src/main/java/io/kelta/worker/controller/TenantOtlpTargetController.java)
  (lines 102–104) — the canonical reference for an `/api/admin/**` in-controller system-permission gate.
  The only behavioral difference: that controller **throws** `ResponseStatusException(FORBIDDEN)`;
  here we return `Optional.empty()` so the controller maps to **404** (privacy posture).

### 3.4 `PageRenderController` change

Inject `CerbosPermissionResolver`, accept `HttpServletRequest`, resolve `profileId`, pass it through.
The `Optional.empty() → notFound()` mapping is **unchanged** — a permission denial flows through the
exact same `.orElseGet(() -> ResponseEntity.notFound().build())` branch that an unknown slug uses, which
is precisely why deny is a 404 for free.

```java
@GetMapping("/{slug}/render")
public ResponseEntity<PageRenderContract> render(@PathVariable String slug, HttpServletRequest request) {
    String profileId = permissionResolver.getProfileId(request);
    return pageRenderService.render(slug, profileId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
}
```

### 3.5 Back-compat

- A page with **no** `access` key ⇒ `extractRequiredPermission` returns `null` ⇒ the gate is skipped
  entirely (no DB lookup, no behavior change). Every existing v1 and 1g-v2 page renders unchanged.
- `render(String slug)` becomes `render(String slug, String profileId)`. The only production caller is
  `PageRenderController` (grep-verified); both worker test classes are updated (§6). No overload is
  kept — a single 2-arg method avoids an ambiguous "which gate runs" path.

---

## 4. DB migrations

**None — stored in `ui-pages.config` JSON, no DDL.**

`requiredPermission` nests under `config.access` inside the existing `FieldDefinition.json("config")`
column on `ui-pages` (declared in `SystemCollectionDefinitions.uiPages()`,
[`runtime-core/.../model/system/SystemCollectionDefinitions.java`](../../../../kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/model/system/SystemCollectionDefinitions.java)).
The grant data it is checked against (`profile_system_permission`) already exists. No new column, no
constraint, no Flyway migration.

> **Deliberate non-goal:** promoting `requiredPermission` to a real `ui_page` **column** (e.g.
> `ui_page.required_permission TEXT`) would be the **only** reason to add a future **`V147`** (Flyway
> head is **V146**; verify the directory before numbering). That would let the database filter
> restricted pages in the `QueryEngine` query itself and index on it. It is **intentionally not done in
> 1h** — the JSON key keeps the slice a pure config addition with zero schema/migration risk, consistent
> with the whole page-builder effort's "everything nests in `config`" rule. Revisit only if per-page
> authz needs DB-level filtering or reporting.

---

## 5. File-by-file code changes

Java 25, records, JDBC (no JPA). Read path — **no** new NATS subject, **no** `BeforeSaveHook`, **no**
gateway route change. `BootstrapRepository` is a `@Repository` bean and is already injected across the
worker, so wiring it into `PageRenderService` is a constructor add only.

### 5.1 `kelta-worker/src/main/java/io/kelta/worker/service/PageRenderService.java` — modify

- Add constructor dependency `BootstrapRepository bootstrapRepository` (field + assignment).
- Change `render(String slug)` → `render(String slug, String profileId)`.
- Insert the gate (after `parseConfig`, before building the contract) per §3.3.
- Add `extractRequiredPermission(Map<String,Object>)` and `hasSystemPermission(String,String)` private
  helpers per §3.3.
- The 1g `parseConfig`/`extractTree`/`extractObjectList`/`asString` helpers, the
  `slug+published+active` query, and the `Optional.empty()` guards are **unchanged**.
- Update the class Javadoc to note the optional `config.access.requiredPermission` gate and that a
  denial resolves to empty (→ 404, deliberately not 403).

### 5.2 `kelta-worker/src/main/java/io/kelta/worker/controller/PageRenderController.java` — modify

- Inject `CerbosPermissionResolver permissionResolver` (constructor + field).
- `render(@PathVariable String slug)` → `render(@PathVariable String slug, HttpServletRequest request)`;
  resolve `profileId = permissionResolver.getProfileId(request)` and pass it to the service (§3.4).
- The `Optional → 200/404` mapping is unchanged.

### 5.3 `kelta-ui/app/src/pages/PageBuilderPage/pageConfig.ts` — modify

- Add the `PageAccess` interface and the optional `access?: PageAccess` key to `PageConfig` (§3.1).
- Extend `mergeConfig` (or add a small `setRequiredPermission(config, perm | undefined)` helper) so the
  page-settings field can write/clear `access.requiredPermission` while preserving untouched keys.
  Clearing (selecting "Anyone (published)") deletes the `access` key so the stored config stays clean.
- Unit-test the helper in `pageConfig.test.ts` (set, clear, preserve-siblings).

### 5.4 FE inspector — page-settings "Access" field

> **Host dependency: slice 2d.** This field lives **inside the page-settings drawer that 2d creates** —
> 1h does not build a settings panel of its own and must not assume a pre-existing one. The backend gate
> (§5.1–5.2) is independent and can ship first; this FE control is sequenced **after 2d**.

Reuses the **2b** schema-driven inspector field-kind machinery (the parent's
[Inspector + canvas](../page-builder-parity.md#inspector--canvas) `PropFieldSchema` with
`kind ∈ … | select | …`). This is a **page-settings** field (page-level, not a widget prop), so it is
registered in the page-settings schema list (alongside name/slug/path/published) rather than a widget's
`propSchema`.

- **Path:** the **2d page-settings drawer** (the panel 2d extracts under
  `kelta-ui/app/src/pages/PageBuilderPage/inspector/`, hosting name/slug/path/published). One new
  `select`-kind page-settings field, `key: 'access.requiredPermission'`, added to that drawer's schema
  list. (Do **not** add it directly to `PageBuilderPage.tsx` — 1h ships after 2d so it lands
  schema-driven in the drawer from the start.)
- **Options source:** import the permission catalog from
  [`SystemPermissionChecklist.tsx`](../../../../kelta-ui/app/src/components/SecurityEditor/SystemPermissionChecklist.tsx).
  Export its `PERMISSION_CATEGORIES` (or a flattened `SYSTEM_PERMISSIONS` list) so the picker reuses the
  single canonical catalog instead of duplicating the 15 names. Prepend an "Anyone (published)" option
  whose value clears the key. (Per architecture.md, this FE constant **is** the canonical permission
  catalog — there is no Java enum to fetch.)
- **On change:** call the `pageConfig.ts` helper to set/clear `config.access.requiredPermission`, then
  persist via the existing page-save path (writes the `config` JSON — no new endpoint).
- **i18n (required):** both author-facing strings go through the existing `t(...)` translation layer
  (same convention as 2d's builder strings), not hardcoded literals:
  - the field **label** — `t('builder.pageSettings.access.label')` ("Required permission"), with the
    "Anyone (published)" no-restriction option as `t('builder.pageSettings.access.anyone')`;
  - the **404 privacy-hint** line under the control —
    `t('builder.pageSettings.access.hint')` ("Users without this permission get a 404 — the page is
    hidden, not shown as forbidden") — so authors understand the privacy posture.
  Add the keys to the page-builder i18n resource bundle alongside the other `builder.*` strings.

### 5.5 No other production code changes

`CustomPage.tsx`/`PageTreeRenderer.tsx` are **untouched** — a restricted page simply 404s at fetch time
(the renderer already handles a 404 render-contract fetch as "page not found", same as an unknown slug).
The `access` key is consumed server-side and not present on the contract, so the runtime renderer needs
no awareness of it.

---

## 6. Test plan

### 6.1 `PageRenderServiceTest` (extend existing class)

Mocked `QueryEngine` + `CollectionRegistry` + **`BootstrapRepository`**, real `JsonMapper` — matching
the existing idiom
([`PageRenderServiceTest`](../../../../kelta-worker/src/test/java/io/kelta/worker/service/PageRenderServiceTest.java):
`@ExtendWith(MockitoExtension.class)`, `JsonMapper.builder().build()`, the `oneRow(page)` helper). The
service factory gains the mocked `bootstrapRepository`; all existing tests call
`service().render("home", "p-1")` (a profile id is now required).

| Test | Setup | Assert |
|------|-------|--------|
| `allowsWhenProfileGranted` (new) | `config.access.requiredPermission = "VIEW_SETUP"`; `findProfileSystemPermissions("p-1")` → `[{permission_name:"VIEW_SETUP", granted:true}]` | contract **present**; `version=="2.0"`; `tree` contains `components`; `verify(bootstrapRepository).findProfileSystemPermissions("p-1")` |
| `deniesWith404WhenProfileNotGranted` (new) | same `access`; `findProfileSystemPermissions("p-1")` → `[]` (or `granted:false`) | `render(...)` is `Optional.empty()` (controller → 404); lookup was invoked |
| `deniesWhenPermissionGrantedFalse` (new) | `access` set; row `{permission_name:"VIEW_SETUP", granted:false}` | `Optional.empty()` — `granted:false` is not a grant |
| `deniesWhenNoProfileId` (new) | `access` set; `profileId == null` | `Optional.empty()`; `verifyNoInteractions(bootstrapRepository)` (short-circuit, no lookup) |
| `absentAccessKeySkipsCheck` (new, back-compat) | `config` with **no** `access` key | contract present; `verifyNoInteractions(bootstrapRepository)` — proves no DB lookup when unrestricted |
| `blankRequiredPermissionSkipsCheck` (new) | `config.access = {requiredPermission:""}` | treated as no restriction; contract present; no lookup |
| `notFoundWhenNoMatch` / `emptyWhenNoCollection` / `blankSlug` (keep) | as 1g | unchanged — permission gate is never reached for an unknown/blank slug or missing collection; `verifyNoInteractions(bootstrapRepository)` where the query never runs |
| `rendersPublishedPage` / v1+v2 1g cases (keep) | pass a `profileId`; no `access` key | unchanged; `version=="2.0"`; no lookup |

Key assertions to lock the design: **(a)** unknown slug and permission-deny both yield
`Optional.empty()` (controller returns the same 404 for both — privacy parity); **(b)** an unrestricted
page never calls `findProfileSystemPermissions` (no per-render DB cost regression for the common case).

### 6.2 `PageRenderControllerTest` (update existing fixture)

The controller now takes `HttpServletRequest` and calls `render(slug, profileId)`. Update both tests to
pass a `MockHttpServletRequest` (or mock) with `X-User-Profile-Id` set, and stub
`pageRenderService.render(eq("home"), any())`. The contract fixture already moves to the 7-arg form in
1g; keep that. Add one controller-level test: when the service returns `Optional.empty()` (deny), the
controller responds **404** (reuses the existing `notFound` assertion — no new branch).

### 6.3 kelta-test-harness integration test — `PageRenderAuthzScenarioTest` (required)

**Add a real-DB scenario.** Per the
[DB-constraint test-gap guard](../../testing.md#real-db-guard-for-constraint-bearing-writes),
the convention extends to any path whose correctness depends on a **real-Postgres + RLS** behavior that
mocked worker tests cannot observe. 1h's gate is exactly that: it reads `profile_system_permission`
under RLS (`app.current_tenant_id`) and **gates page visibility** (404-not-403). Mocked-permission
worker unit tests stub `findProfileSystemPermissions` and so **cannot catch an RLS misconfiguration**
(e.g. the lookup returning rows it should not, or zero rows because the tenant scoping is wrong) — they
would pass while the real grant read is broken. They also can't prove the gateway actually forwards
`X-User-Profile-Id` to the worker, which the whole gate hinges on. A real-stack scenario closes that gap.

Add `PageRenderAuthzScenarioTest` to **kelta-test-harness** (Testcontainers full mini-stack — real
Postgres + RLS, gateway → worker). Setup and assertions:

- Seed, in **one real tenant**, a **restricted page** (`config.access.requiredPermission = "VIEW_SETUP"`,
  published + active) and two profiles: **`grantedProfile`** with a `profile_system_permission` row
  `{permission_name:"VIEW_SETUP", granted:true}` and **`deniedProfile`** with no such grant (or
  `granted:false`).
- Hit `GET /api/pages/{slug}/render` **through the gateway → worker path** (so the real
  `X-User-Profile-Id` forwarding is exercised, not stubbed), once authenticated as each profile.
- **Assert 200** (full v2 contract) for `grantedProfile` and **404** (no body) for `deniedProfile`,
  against **real Postgres + RLS** — proving the grant read is correctly tenant-scoped and the deny path
  yields a privacy-preserving 404, not a 403 and not a leak.
- Optionally also seed an **unrestricted** page and assert it renders 200 for `deniedProfile` (back-compat
  under the real stack).

**Keep the §6.1 worker unit tests too** (allow / deny / `granted:false` / absent-key / blank / no-profile)
— they cover the branch logic fast and in isolation; the harness scenario covers what they structurally
cannot (real RLS + real header forwarding). Both layers are required.

### 6.4 FE

- **Vitest:** `pageConfig.test.ts` — set/clear/preserve-siblings for `access.requiredPermission`.
  The **2d page-settings drawer** test — selecting a permission in the "Access" field writes
  `config.access.requiredPermission`; selecting "Anyone (published)" clears it; the option list matches
  the `SystemPermissionChecklist` catalog; assert the label/hint render via their `t('builder.pageSettings.access.*')`
  keys (not hardcoded strings).
- **Playwright (post-deploy):** as a profile **without** the permission, navigating to a restricted
  page's `/:tenant/app/p/:slug` shows the not-found state (404 contract). As a profile **with** it, the
  page renders. (Positive + negative e2e per the project's UI+tests memory standard.)

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` | Page-builder row: **move per-page authz out of the gap column** — it now reads "per-page authz: optional `config.access.requiredPermission` gates render on a `profile_system_permission` grant (same in-controller lookup as `/api/admin/**`); deny → **404** (page existence not leaked); absent key ⇒ published+active+tenant gate unchanged". Remove the "per-page Cerbos authz" gap note. |
| `.claude/docs/architecture.md` | `PageRenderService` row + Authz section: document the render-time gate — optional `config.access.requiredPermission` checked via `BootstrapRepository.findProfileSystemPermissions` (the same worker-side system-permission pattern admin controllers use; **not** a Cerbos PDP call), and the **404-not-403 rationale** (denial is indistinguishable from an unknown slug to avoid leaking that a restricted page exists). Note `profileId` is resolved by `CerbosPermissionResolver.getProfileId` from `X-User-Profile-Id`. |

(Per parent §"Docs to update": `conventions.md` page-config-v2 contract is owned by the FE binding
slices; this backend slice touches only the two rows above. No `integrations.md` change — no external
service or NATS subject added.)

---

## 8. Risks & open questions

- **404-vs-403 is a deliberate privacy choice, and load-bearing.** Returning 403 would confirm a
  restricted page exists at that slug. We return 404 — identical to an unknown slug — by routing the
  denial through the same `Optional.empty()` the unknown-slug path uses. Reviewers must reject any 403
  here. Trade-off: a legitimately-restricted user gets a generic "not found", not "you lack permission";
  the inspector hint (§5.4) makes this explicit to authors. (If a future product decision wants a
  "request access" affordance, that is a separate FE concern that must still not change the 404 wire
  behavior.)
- **Per-render DB lookup only when restricted.** `findProfileSystemPermissions` is one extra
  RLS-scoped `SELECT` **only** for pages that declare `requiredPermission`; unrestricted pages do zero
  extra work (asserted in §6.1). Acceptable. If restricted pages become hot, the existing
  `WorkerCacheManager` profile-permission cache (which already caches `systemPermissions`) is the reuse
  target — but **do not** pre-optimize in 1h; wire the cache only if profiling shows a need.
- **Service signature change ripples to tests.** `render(String, String)` breaks both worker test
  classes' call sites until updated (§6). Grep-verified the only production caller is
  `PageRenderController`. No overload retained (avoids an ambiguous gate path).
- **`requiredPermission` is an unvalidated string.** The worker compares it as-is against
  `permission_name`; an unknown/misspelled name matches no grant ⇒ everyone is denied ⇒ 404. This is the
  safe-by-default failure mode. The FE picker constrains authoring to the catalog, so bad values only
  arise from API/MCP edits — acceptable; a future hardening could validate on write in `UIPageSlugHook`,
  but that is out of scope.
- **Catalog is a frontend constant, not a DB list.** The permission picker reuses
  `SystemPermissionChecklist.PERMISSION_CATEGORIES` (architecture.md confirms there is **no Java enum**;
  the catalog is the FE source of truth). Keep the export single-sourced so adding a permission updates
  both the profile editor and this picker at once.
- **Sequencing:** the **backend gate** depends only on 1g's `parseConfig`/`extractObjectList` and the
  7-field contract, so it can land any time after 1g (independent of the FE). The **FE page-settings
  "Access" field** depends on **2d** — it is hosted inside the page-settings drawer 2d creates and
  reuses 2b's inspector field-kind machinery. Order the FE control after 2d so it lands schema-driven in
  the drawer from the start; do not add it to `PageBuilderPage.tsx` directly. (Backend-first, FE-after-2d
  is the recommended split.)
- **File sizes.** `PageRenderService` stays small (~120 lines after this); not in `concerns.md`.
  `PageBuilderPage.tsx` is already large (~71 KB) and **is** a known bloat hazard — add the single
  page-settings field minimally, or (preferred) land it in the 2b-extracted `inspector/` to avoid
  growing the monolith.
