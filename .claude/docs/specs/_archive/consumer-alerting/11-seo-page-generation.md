# Slice 11 — SEO Page Generation

> Child of `specs/consumer-alerting/README.md`. Backend-only. The SEO/content substrate: a
> nightly sweep computes a per-target stat block into a `seo-pages` collection that a static
> content site renders at build time. This is the platform half of K9 (read surface) + K10
> (generation) — the venture-specific Astro rendering is a follow-up (slice 12).
>
> Source-verified 2026-08-08 (Flyway head **V184** on main; this slice takes **V185**).

## 1. Goal & scope

**Delivers:**

- **`seo-pages`** read-only system collection (`seo_page`, RLS) — one row per watchable target,
  carrying the target's public metadata + AGGREGATE signals (`watcherCount`, `winCount`,
  `lastWinAt`, extensible `stats`), a URL `slug`, and a `published` flag.
- **`SeoPageGenerationService`** — a `@Scheduled` (nightly default) sweep that reads every
  tenant's targets/watches/wins in one unscoped pass (`admin_bypass`), upserts a page per active
  target keyed on `(tenant_id, slug)`, applies the §6.3 quality guardrail to set `published`, and
  prunes pages not regenerated this cycle.
- **The read surface (K9), the spec-respecting way.** `seo-pages` is aggregate-only and safe to
  read with a **build-time service token** (a PAT on a read-only profile) over the ordinary
  authenticated JSON:API. **There is still no anonymous bulk API** — this holds the line the
  parent Key Decisions drew ("build-time authenticated reads… structured bulk data gets no open
  JSON endpoint"), and `PublicSurfaceTest` continues to guarantee nothing is anonymously
  reachable. The public-ness lives at the static-site/CDN layer, not in a Kelta endpoint.

**Does not deliver (follow-up / deferred):**

- **The Astro rendering** (slice 12): kelta-marketing consuming `seo-pages` at build time to
  generate target/Q&A/calendar pages + sitemap + schema.org. That is venture frontend + a build
  service token + deploy wiring.
- **Availability history / best-odds-by-day.** Raw availability observations are deliberately not
  persisted (parent Key Decisions), so v1's stat block is watcher + win aggregates. History
  charts wait for a rollup that captures availability over time.
- **Q&A pages from the analytics corpus** (needs the demand-clustering work) and **anonymous**
  serving.

## 2. UI samples

N/A — backend. Admins can read `seo-pages` through the standard read-only system-collection
admin surface; the content site reads it with a service token.

## 3. Data & API contracts

**`seo-pages`** (read-only system collection, `seo_page`, tenant-scoped, RLS): `targetId` (plain
id, no FK), `slug` (unique per tenant — the URL), `title`, `category`, `watcherCount`,
`winCount`, `lastWinAt`, `stats` (JSONB), `published` (bool, default false), `generatedAt`.
`UNIQUE (tenant_id, slug)`; indexes `(tenant_id, published)` (build query) + `(tenant_id,
target_id)`. **No member data** — counts are aggregate.

Read: standard authenticated `GET /api/seo-pages` (dynamic route). Build reads use a service PAT
on a read-only profile scoped to `seo-pages`. Config: `kelta.seo.generation.{enabled,
min-watchers:5, poll-interval-ms:86400000}`.

## 4. DB migrations

`V185__seo_pages.sql`: `seo_page` table, RLS (`tenant_isolation` + `admin_bypass`, V77 pattern),
`UNIQUE (tenant_id, slug)`, the two indexes. No FK on `target_id`.

## 5. File-by-file code changes

- `SystemCollectionDefinitions.java` — `seoPages()` factory (`readOnlySystemBuilder`) + `all()`.
- `V185__seo_pages.sql`.
- Worker `repository/SeoPageRepository.java` — the aggregate query (watch_target ⨝ active-watch
  distinct-member count ⨝ win count/last), `upsert` (`ON CONFLICT (tenant_id, slug)`), and
  `deleteGeneratedBefore` prune; records + `JdbcTemplate`; runs unscoped like the retention
  sweeps.
- Worker `service/seo/SeoPageGenerationService.java` — `@Scheduled` sweep; slug
  (slugify(name)+short-target-id), guardrail (`watcherCount ≥ min` OR any win), stat-block JSON
  (**`tools.jackson` ObjectMapper** — the Jackson-3 boot trap), best-effort (never throws), metric
  `kelta_worker_seo_pages_generated`.

No gateway change — `seo-pages` gets its dynamic collection route automatically (the
`watch-targets`/`analytics-events` precedent). No Cerbos change — a read-only aggregate
collection; a build reads it with an explicit service token, and portal profiles have no reason
to reach it.

## 6. Test plan

- **Unit** — `SeoPageGenerationServiceTest`: slug shape/fallbacks; guardrail (publish on enough
  watchers or any win, else not); `generate` upserts a page per target with the right `published`
  flag then prunes; disabled no-ops; a repository failure is swallowed.
- **Unit** — `SeoPageGenerationWiringTest` (`ApplicationContextRunner`): the service wires against
  the `tools.jackson` `ObjectMapper` — the Jackson-3 boot guard.
- **Harness (real Postgres)** — `SeoPageScenarioTest`: the aggregate query counts distinct ACTIVE
  watchers (a PAUSED watch excluded) and all wins; the upsert is idempotent on `(tenant, slug)`;
  the prune removes a stale page and spares a fresh one; RLS isolates two tenants' pages.

## 7. Docs to update (same PR)

`status.md` (SEO generation row), `architecture.md` (the build-time read-surface note — service
token, aggregate-only, no anonymous API), parent `README.md` slice table.

## 8. Risks & open questions

- **Thin-content risk is the whole reason for the guardrail.** `min-watchers` gates publish; tune
  it against real data before pointing a build at published pages, and keep unpublished pages
  noindex in the static layer.
- **Slug stability.** The slug is `slugify(name)-<8 id chars>`, so a target rename mints a new
  slug (new URL); the old page is pruned next cycle. If stable URLs across renames matter, key the
  page on `target_id` and treat the slug as a mutable attribute — deferred until it bites.
- **Cross-tenant sweep cost.** One pass over all targets nightly; the aggregate query is indexed
  on the join keys. At large target counts, batch by tenant — deferred until volume warrants.
- **Service-token scope is the security boundary for the read surface.** The build PAT must be a
  read-only profile scoped to `seo-pages` (and other aggregate collections), never one that can
  reach watch/billing/member data. Document the profile with the deploy; do not reuse an admin PAT.
