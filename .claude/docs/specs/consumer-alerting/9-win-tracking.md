# Slice 9 — Win Tracking + Live Ticker

> Child of `specs/consumer-alerting/README.md`. Backend-only. The social-proof + retention
> engine: a member confirms "I got the spot", and that claim feeds per-target success stats and
> a live-wins ticker.
>
> Source-verified 2026-08-08. **Migration numbering:** this slice takes **V184** because slice
> 8's V183 (analytics capture, PR #1313) is still open. **V184 must reach any database after
> V183** — a lower-numbered migration arriving after a higher one already applied is silently
> skipped on existing DBs, so **merge/deploy slice 8 before this slice.**

## 1. Goal & scope

**Delivers:** the `wins` system collection + a member-facing `WinController` on the
`/api/wins/**` static route:

- `POST /api/wins` — a member records a win (owner-stamped, written via `QueryEngine` so
  `WinGuardHook` fires).
- `GET /api/wins` — the caller's own wins.
- `GET /api/wins/recent` — the **live-wins ticker feed**: recent opt-in-`isPublic` wins,
  **redacted** to ticker-safe fields (first-name claimant label, summary, category, quantity,
  time) — cross-member by design (social proof) but never exposing member identity.
- `GET /api/wins/stats?targetId=` — per-target success stats (count + last win).

Plus `WinGuardHook` (owner guard on the generic route, a faithful mirror of `WatchGuardHook`)
and `AnalyticsRetentionSweep`-adjacent nothing — wins are low-volume and kept.

**Realtime ticker rides existing machinery.** A win create emits
`kelta.record.changed.<tenant>.wins` (all collection CRUD does), which `RealtimeBridge` already
fans to sockets subscribed to `wins` as an invalidation signal → the client refetches
`/api/wins/recent`. **No new realtime code, no new NATS subject.**

**Does not deliver (deferred, called out so review does not flag them):**

- **Anonymous (unauthenticated) ticker.** The public homepage ticker (strangers, no login)
  reads through the public read-surface slice once it lands — `GET /api/wins/recent` is
  authenticated for now, the same boundary slice 8 drew for analytics ingest.
- **Rollup tables / SEO success-stat pages.** `GET /api/wins/stats` computes on demand
  (indexed COUNT); a materialized `target-stats` rollup for SEO is the analytics-rollup /
  K10 slice's job.
- **"Did you get it?" alert→claim automation.** A flow that prompts a member after an alert
  and creates the win is tenant flow config (the win API is the primitive it would call), not
  platform code here. `alertId`/`watchId` columns are stored for that future link + the
  time-to-claim metric.

## 2. UI samples

N/A — backend. The ticker + wins wall render in the consumer frontend / member portal
(external repos) against `/api/wins/recent` and `/api/wins`. Admins see `wins` through the
standard system-collection admin surface.

## 3. Data & API contracts

**`wins`** (system collection, table `win`, member-writable via `WinController`):
`memberId` (LOOKUP → users, the owner), `targetId` / `watchId` / `alertId` (plain varchar(36),
**no FK** — a win outlives what it references and the alert ledger is pruned), `category`,
`summary` (required, ≤280 — the ticker text), `quantity`, `isPublic` (bool, default **false** —
gates ticker visibility), `claimantName` (server-set **first name only**, for the public feed),
`claimedAt`, audit columns. Indexes: `(tenant_id, member_id, claimed_at)`,
`(tenant_id, claimed_at) WHERE is_public` (ticker), `(tenant_id, target_id)` (stats).

`POST /api/wins` body: `{summary (required), targetId?, watchId?, alertId?, category?,
quantity?, isPublic?, claimedAt?}`. Server sets `memberId` = caller, `claimantName` = caller's
first name, ignores any body identity. `GET /recent?limit=` → `{data:[{claimantName, category,
summary, quantity, claimedAt}]}` (redacted). `GET /stats?targetId=` → `{data:{targetId,
winCount, lastWinAt}}`.

## 4. DB migrations

`V184__wins.sql`: one `win` table, RLS (`tenant_isolation` + `admin_bypass`, V77 pattern), the
three indexes. No FKs on `target_id`/`watch_id`/`alert_id`. See the numbering warning above.

## 5. File-by-file code changes

- `SystemCollectionDefinitions.java` — `wins()` factory + `all()`.
- `V184__wins.sql`.
- Worker `repository/WinRepository.java` — read queries (`findByMember`, `findRecentPublic`,
  `statsForTarget`) + `findMemberDisplayFirstName` for the claimant label; records + JdbcTemplate.
- Worker `controller/WinController.java` — the member API + redacted ticker feed; writes via
  `QueryEngine` (`WatchController` idiom).
- Worker `listener/WinGuardHook.java` — owner guard, mirror of `WatchGuardHook`; registered in
  `config/FlowConfig.java`.
- Gateway `RouteConfigService.registerStaticRoutes()` — `{"wins", "/api/wins/**", "wins"}` +
  bump the `RouteConfigServiceTest` route-count assertions.

Cerbos: no new policy. The seeded Portal User profile is `API_ACCESS`-only, so generic-route
reads of `wins` are default-denied (same stance the watch/billing collections rely on);
`WinController` is the only member path — `/list` is owner-scoped, `/recent` exposes only
opt-in-public redacted rows.

**Authorization of `POST /api/wins` (PLT-331, 2026-10-04).** Members intentionally hold no
`canCreate` on `wins`, so a gateway "403 without create" rule would refuse every member; the route
stays `API_ACCESS`-only at the gateway. The authorization decision is a **resolvable member
identity** (`requireActor`: `X-User-Id` present and resolvable in the tenant), and it runs before
any body validation — the body is `@RequestBody(required = false)` so even an empty POST from an
unidentified caller is a 403, not a 400. A resolved member with an invalid body (blank `summary`)
still gets 400. Pinned by `WinControllerCreateAuthzMvcTest`. The request shape of a member API is
not a secret; the point is that nothing about the body is evaluated for a caller who is not a member.

## 6. Test plan

- **Unit** — `WinGuardHookTest` (mirror of `WatchGuardHookTest`: own/foreign/re-own/internal-tier/
  fail-closed); `WinControllerTest` (create stamps owner + first-name label + clamps summary +
  defaults private; `/recent` is redacted — asserts **no `memberId`/`id`**; `/list` owner-scoped;
  `/stats` count).
- **Harness (real Postgres)** — `WinScenarioTest`: RLS isolates two tenants' wins (non-superuser
  probe); the ticker query returns only public wins newest-first; stats count public + private.

## 7. Docs to update (same PR)

`status.md` (new win-tracking row), `architecture.md` (`/api/wins/**` route + ticker-redaction
note), parent `README.md` slice table. No `CLAUDE.md` messaging change — the ticker reuses the
existing `kelta.record.changed` subject.

## 8. Risks & open questions

- **Privacy of the public ticker.** Only opt-in `isPublic` rows appear, and only a first-name
  claimant label + summary the member wrote — never `memberId`, email, or exact identity. The
  member controls publicity per win. `member_id` erasure on account close should also
  drop/anonymize public wins — owed with the analytics-rollup/erasure slice.
- **Summary is member-authored free text shown to others.** It is stored/escaped as data and
  rendered by the frontend; treat it as untrusted on render (standard XSS hygiene in the
  consumer app), same as any user-authored field.
- **`GET /stats` is an unbounded COUNT per call.** Indexed on `(tenant_id, target_id)`; fine at
  expected volume. If a target accrues very many wins, cache or roll up — deferred until real
  volume warrants it.
