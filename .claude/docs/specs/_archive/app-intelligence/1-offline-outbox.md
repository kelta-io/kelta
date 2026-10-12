# Slice 1 — Offline Outbox UI

> Child spec of [App Intelligence (Phase 4)](./README.md), authored with the
> implementation (same PR). Frontend-only; not security-typed. The outbox ENGINE
> (queue, FIFO replay, reconnect sync) already exists — this slice adds visibility and
> fixes the silent-drop failure semantics.

## 1. Goal & scope

Users can see and trust their offline changes: the `OfflineIndicator` grows a queued-
changes count while offline and — the bug fix — **failed replays stop vanishing**.
Today `SyncEngine.push()` drops a 409 (conflict) or any other 4xx (validation/authz)
op permanently with no trace. Now those ops move to a new IndexedDB `failed` store
(op + HTTP status + server error + `failedAt`); a banner surfaces them even while
online, with an expandable panel listing pending and failed ops and per-op
**retry** / **discard**. **Not delivered:** conflict-diff UI (server-wins semantics
unchanged), editing a failed op's payload, admin-shell surfacing (the provider mounts
only in `EndUserShell`).

## 2. UI samples

Offline: `⚠ You're offline — 3 changes queued · [Details]`. Online with failures:
`⚠ 2 changes failed to sync · [Details]`. Panel rows:
`create customers · queued 14:03 · pending` / `update orders/17 · 422 "credit_limit
must be positive" · [Retry] [Discard]`.

## 3. Data & API contracts

- `FailedOp = OutboxOp + {status?, error, failedAt}` (`types.ts`). IndexedDB **v3**
  adds the `failed` store (keyPath `id`); incremental upgrade preserves v1/v2 data.
  `OfflineStore` gains `addFailed`/`listFailed`/`removeFailed` (both impls).
- `SyncEngine.push()` failure routing (semantics otherwise unchanged): 409 → still
  drop-from-outbox + count as conflict, **now also retained as a FailedOp**; other
  4xx → retained as FailedOp; 5xx/network → still stays queued and halts (no
  retention — it will retry on the next push).
- New engine methods: `retryFailed(opId)` (move failed → outbox with a fresh
  `queuedAt`; caller then `push()`es when online) · `discardFailed(opId)` ·
  `listFailed()` · a lightweight `onChange(listener)` subscription notified after
  queue/push/retry/discard so the UI re-reads without polling.
- `useOfflineOutbox()` (exported from `@/offline`): `{pending, failed, pendingCount,
  failedCount, retry, discard}` — live via the engine subscription; inert empties
  outside the provider (admin shell). `retry` pushes immediately when online and
  invalidates the collection's record queries.
- No backend change; replay still flows through the authorized JSON:API path.

## 4. DB migrations

None (IndexedDB client-side version bump only).

## 5. File-by-file code changes

`offline/types.ts` (`FailedOp`) · `offline/store.ts` (v3 + failed CRUD, both impls,
+tests) · `offline/syncEngine.ts` (retention, retry/discard, onChange, +tests) ·
`offline/useOfflineOutbox.ts` (new, +tests) · `offline/index.ts` (exports) ·
`shells/EndUserShell/OfflineIndicator.tsx` (badge + panel, +tests) · `en.json`
(`offline.*`).

## 6. Test plan

Vitest: store failed-CRUD (in-memory + IndexedDB upgrade path via fake-indexeddb if
present, else in-memory only); push routing (409 retained + conflict count, 422
retained, 500 stays queued + nothing retained); retryFailed re-enqueues FIFO-last and
a subsequent push clears it; discard removes; useOfflineOutbox lists/counts/reacts to
onChange and no-ops without a provider; OfflineIndicator — offline banner with count,
online-with-failures banner, panel rows, retry/discard callbacks. Playwright
post-deploy: devtools-offline edit → banner count → reconnect → clean. `/verify`
green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md offline row · memory.

## 8. Risks & open questions

- Retained failed ops live client-side only (per browser/tenant DB) — acceptable for
  v1; server-side draft persistence would be a different feature.
- A retried op re-enters FIFO at the tail with a fresh `queuedAt` — ordering relative
  to newer queued edits is by retry time, which matches user intent ("try this
  again now").
