# App Intelligence & Resilience (App-UX Phase 4) — Parent Spec

> **Status:** parent planning spec. Phase 4 of the app-UX roadmap (Phases 1–3 —
> `specs/_archive/app-surfacing/`, `specs/_archive/app-data-entry/`, `specs/_archive/app-platform/` — are
> complete). Each slice below gets a child spec in this directory before (or with) its
> implementation PR. Child specs extend, never contradict, this parent.
>
> Source-verified against the codebase on 2026-07-08 (post app-platform slices 1–4,
> Flyway head V164). If code and this doc disagree, trust the code and fix this doc.

## Goal

Four independent capabilities that make the platform feel intelligent and resilient:

1. **AI page generation** — describe a page, get an applyable draft in the builder.
2. **Offline outbox UI** — see, retry, and trust queued offline changes.
3. **Presence** — see who's viewing the same record / editing the same page.
4. **Tenant i18n authoring** — tenants override UI text and metadata labels per locale.

## Verified current state (trust these over stale docs)

- **AI**: kelta-ai runs a bounded Anthropic tool-use loop (max 8 iterations, 100k
  token cap/conversation) with **12 registered `ToolHandler`s** — 5 read tools + 6
  propose tools (`propose_collection`/`layout`/`add_fields`/`update_field`/
  `remove_field`/`picklist`). Proposals are `{id, type, status: pending→applied|
  dismissed, data}`; `ProposalService.applyProposal()` switches on type and calls the
  worker's authorized APIs; the admin `AiChat` panel streams SSE (`delta`/`tool_use`/
  `proposal`/`done`) and renders per-type proposal cards with an Apply button.
  **No ui-pages proposal exists** — `SystemPromptService` only mentions `ui_pages` as
  domain knowledge. Handlers auto-register via component scan into `ToolRegistry`.
  Token accounting: Redis monthly budget/tenant (429 via `TokenLimitFilter`).
- **Offline**: the outbox already exists end-to-end — IndexedDB store v2
  (`records`/`cursors`/`outbox`/`pages`), `useRecordMutation` queues create/update/
  delete offline with optimistic replica writes + temp ids, `SyncEngine.push()`
  replays FIFO on reconnect. **Gaps are UI + failure semantics**: no visibility into
  pending ops, no manual retry, and **replay failures are silently dropped** (409 ⇒
  drop + pull; other 4xx ⇒ drop permanently) — the user's change vanishes without a
  trace. `OfflineIndicator` is a plain "you're offline" banner.
- **Realtime**: the `/ws/realtime` protocol is **record-change invalidation only** —
  client `{action: subscribe|unsubscribe, collection}`, server `record.changed`.
  No presence, no custom channels, no client-to-client. Limits: 50 subs/session, 100
  connections/tenant; JWT at connect (tenant+userId from claims). `RealtimeBridge`
  fans NATS record events out per pod. **Gateway is GraalVM native — every new
  message/DTO shape parsed by Jackson needs a reflect-config entry or it breaks at
  runtime** (2026-07-02 outage lesson).
- **i18n**: six static JSON bundles (`en ar fr de es pt`), en-fallback `t()` with
  `{param}` interpolation, RTL for Arabic, locale picked from
  localStorage→browser→default. **Nothing tenant-authored**: no translations system
  collection, no admin UI, collection `displayName` is a single non-localized string.
  (Child spec must re-verify field-level display names in the metadata model — the
  scout report on `FieldDefinition` lacking a label conflicts with the FE consuming
  `field.displayName`; trust the code at implementation time.)

## Key decisions

- **AI page generation extends the proposal system — no new engine.** One new
  `propose_ui_page` tool (input schema = a constrained widget tree + `variables` +
  `dataSources`, validated against the FE widget catalog), one new
  `ProposalService` apply case (`POST /api/ui-pages` with the tree nested in
  `config`, `published: false` — a draft, never live), one new proposal card, and a
  widget-catalog section in `SystemPromptService`. The page opens in the builder for
  human review; publish stays a human act. Same authz path as every proposal (tools
  run as the invoking user).
- **Offline slice fixes the silent drop before adding chrome.** IndexedDB v3 adds a
  `failed` store (op + error + failedAt); replay moves 4xx-rejected ops there instead
  of dropping (409 keeps its drop-and-pull semantics but records the fact). UI:
  `useOfflineOutbox()` hook → pending/failed counts + op list; `OfflineIndicator`
  grows a count badge and an expandable panel with per-op retry/discard.
- **Presence is ephemeral and best-effort.** New socket actions
  `presence.join`/`presence.leave` with a resource key (`record:<collection>/<id>`,
  `page-builder:<pageId>`); the gateway tracks per-resource session sets and
  broadcasts `presence.changed` snapshots to co-present sessions, bridged across pods
  via a new NATS subject (`kelta.presence.<tenantId>` — messaging table updated in
  the slice). Nothing persists; disconnect = leave; per-resource cap (~20) and the
  existing session limits apply. FE: avatar stack on record detail + builder header.
  **Native-image rule: all new message DTOs get reflect-config entries + a native
  smoke check in the child spec's test plan.**
- **Tenant i18n = an overlay, not a fork.** New tenant-scoped system collection
  `ui-translations` (`locale`, `key`, `value`; unique per tenant+locale+key; migration
  V165+ — verify head) with the standard BeforeSaveHook + NATS broadcast (Rule 1) and
  bootstrap exposure; `I18nContext` merges the tenant overlay over the static bundle
  (tenant wins, en-fallback unchanged). Metadata labels ride the same keyspace
  (`meta.collection.<name>.displayName` etc.) resolved in the FE — no schema change
  to `CollectionDefinition` in v1. Admin editor page (key search, per-locale values,
  missing-key report seeded from the static bundle).

## Reuse Map

| Need | Reuse | Path |
|------|-------|------|
| Tool registration | `ToolHandler` + `ToolRegistry` (component scan) | `kelta-ai/.../service/tools/` |
| Proposal lifecycle + cards | `ProposalService`, `AiChat` proposal cards | `kelta-ai/.../service/`, `kelta-ui/app/src/components/AiChat/` |
| Page persistence shape | `PageConfig` (`components`/`variables`/`dataSources`) | `kelta-ui/app/src/pages/PageBuilderPage/pageConfig.ts` |
| Outbox store + replay | `IndexedDbOfflineStore`, `SyncEngine.push()` | `kelta-ui/app/src/offline/` |
| Socket client | `RealtimeClient` (reconnect/backoff/resubscribe) | `kelta-ui/app/src/realtime/` |
| Socket server + limits | `RealtimeWebSocketHandler`, `SubscriptionManager` | `kelta-gateway/.../websocket/` |
| Config broadcast pattern | `MenuConfigEventPublisher` (+ cache invalidation listener) | `kelta-worker/.../listener/` |
| System-collection recipe | `playbooks.md` + `user-ui-preferences` precedent | `.claude/docs/`, V163 |
| Static bundles + `t()` | `I18nContext` | `kelta-ui/app/src/context/I18nContext.tsx` |

## Slice plan

| Slice | Child spec | Axis |
|-------|-----------|------|
| 1 — Offline outbox UI | `1-offline-outbox.md` — **SHIPPED 2026-07-08** (failed replays retained + retry/discard; banner + panel; fixed single-brace i18n interpolation in the Phase-2 keys en route) | offline UX (FE, IndexedDB v3) |
| 2 — AI page generation | `2-ai-page-generation.md` — **SHIPPED 2026-07-08** (`propose_ui_page` tool + validated apply → unpublished draft; proposal card + builder link) | **kelta-ai + FE** |
| 3 — Presence | `3-presence.md` — **SHIPPED 2026-07-08** (presence.join/leave/changed protocol, KELTA_PRESENCE NATS bridge w/ heartbeat+expiry, record-detail avatars; Map-only payloads ⇒ no new reflect-config) — **PHASE 4 COMPLETE** | **gateway + FE** (native-image risk) |
| 4 — Tenant i18n authoring | `4-tenant-i18n.md` — **SHIPPED 2026-07-08** (`ui-translations` V165 + Rule-1 broadcast; I18nContext overlay via bootstrap; Setup Translations editor) | **worker + FE** (migration) |

**Dependency order: none — all four are independent.** Suggested sequence: 1 (small,
pure FE, fixes a live UX bug) → 2 (highest value) → 4 → 3 (riskiest infra). One PR
per slice; 2/3/4 each touch a different backend service, so don't batch them.

- **Slice 1 — Offline outbox UI** (FE). IndexedDB v3 `failed` store; replay retains
  rejected ops with the server error; `useOfflineOutbox()` (pending/failed lists +
  counts, retry/discard); `OfflineIndicator` badge + panel; temp-id rows badge
  "pending sync" in lists. No backend change.
- **Slice 2 — AI page generation** (kelta-ai + FE). `propose_ui_page` tool
  (constrained widget-tree schema — widget types enumerated from the registry
  catalog, depth/size caps), apply case creating a **draft** ui-page, system-prompt
  widget catalog, `UiPageProposalCard` (tree summary + "open in builder" after
  apply), kelta-ai unit tests + card Vitest. Rides existing token budgets/authz.
- **Slice 3 — Presence** (gateway + FE). `presence.join`/`leave`/`changed` protocol,
  per-resource session registry + NATS cross-pod bridge (`kelta.presence.<tenantId>`),
  reflect-config entries + limits; FE `usePresence(resource)` + avatar stack on
  `ObjectDetailPage`/record header and the builder editor header. Ephemeral only.
- **Slice 4 — Tenant i18n authoring** (worker + FE). `ui-translations` system
  collection + V165+ migration + Rule-1 broadcast; bootstrap overlay into
  `I18nContext` (tenant wins over bundle, en-fallback preserved); metadata-label
  keyspace; admin Translations editor (Setup), missing-key report. Locale preference
  optionally moves to `usePreferenceValue` (follow-up if scope grows).

Every slice: unit tests in-PR (Vitest / service unit); harness scenario only where a
real-DB behavior is in play (slice 4's unique constraint); a Playwright spec that runs in
the PR's E2E job (never skip-gated — `testing.md` → End-to-end); i18n keys for all new
strings; docs rows in the same PR.

## Child-spec template

Identical to the prior phases — every section present or "N/A — <reason>": Goal &
scope · UI samples · Data & API contracts · DB migrations (verify head; next expected
**V165**) · File-by-file changes · Test plan · Docs to update · Risks & open
questions.

## Risks / open questions

- **Slice 3 is the only novel protocol work** — the socket stack is deliberately
  minimal today, and the GraalVM native gateway punishes casual DTO additions.
  Child spec must list every new Jackson-parsed shape + its reflect-config entry.
- AI-generated trees can reference collections/fields the tenant lacks — the apply
  path validates against the schema registry and the card shows unresolved
  references before Apply; drafts are never published automatically.
- Tenant translation values render through the existing `t()` interpolation — treat
  values as plain text (no HTML), same as bundle strings; the editor enforces it.
- Presence leaks coarse activity ("X is viewing this record") — visible to users who
  can already read the record (join requires the same JWT/tenant as the data
  subscription); no field data crosses the channel.
