# Slice 5 — Optimistic locking (concurrency safety)

> Child of [README.md](./README.md). Backend + FE. Independent — any time after Slice 1.

## 1. Goal & scope
**Delivers:** additive optimistic locking so concurrent edits can't silently lose updates. Worker
emits a strong `ETag` on record GET; UI echoes `If-Match` on PATCH/PUT/DELETE; worker returns
**409 Conflict** on mismatch; UI shows a reload/merge prompt. Covers inline + form edits.
**Does NOT:** add merge tooling beyond reload-and-reapply, or lock at the DB row level (it's
compare-and-swap on version, not pessimistic). Conforms to parent "Concurrency safety".

## 2. UI samples
```
⚠ This record changed since you opened it.
  Someone updated "Stage" to "Won" 30s ago.
  [ Reload latest ]   [ Overwrite anyway ]      ← overwrite re-sends without If-Match (explicit)
```
Backend, before/after:
```
GET  /api/opportunities/3f2   → 200, ETag: "W/updated_at:2026-07-01T12:00:00Z"
PATCH /api/opportunities/3f2  If-Match: "W/updated_at:2026-07-01T12:00:00Z"
   stale → 409 { errors:[{ status:"409", code:"STALE_WRITE", detail:"Record was modified" }] }
```

## 3. Data & API contracts
- Version token = hash/encoding of the record's `updated_at` (no new column). Emitted as `ETag`
  response header on single-record GET; accepted as `If-Match` request header on write.
- Missing `If-Match` → unchanged behavior (back-compat; only enforced when the header is present),
  so non-migrated callers keep working. UI opts in.
- 409 envelope reuses the JSON:API error shape via `GlobalExceptionHandler`.

## 4. DB migrations
None — version derives from existing `updated_at`.

## 5. File-by-file code changes
- **Backend** `runtime-core .../query/DefaultQueryEngine.java` (or the router advice): compute +
  compare version on update/delete; throw a new `StaleWriteException`.
- `runtime-core .../router/GlobalExceptionHandler.java`: map `StaleWriteException` → 409.
- Single-record GET path: set `ETag` header.
- **FE** `kelta-ui/app/src/hooks/useRecordMutation.ts`: capture ETag on read, send `If-Match`,
  surface 409 as a typed conflict; `apiClient.ts` parse.
- Conflict prompt component consumed by `RecordShell` + `RecordDataGrid`.

## 6. Test plan
- Worker unit: matching If-Match → 200; stale → 409; absent → 200 (back-compat).
- **kelta-test-harness** real-DB: two concurrent writers, second gets 409 over real Postgres +
  RLS (the DB-constraint-test-gap lesson — mocks can't prove the compare-and-swap).
- Vitest: UI shows conflict prompt on 409; reload refetches; overwrite re-sends.

## 7. Docs to update
- `conventions.md` (ETag/`If-Match`/409 contract), `architecture.md` (write-path 409),
  `status.md` (concurrency-safe writes).

## 8. Risks & open questions
- `updated_at` resolution — if two writes land in the same clock tick the tokens match; acceptable
  (sub-second concurrent writes to one record are rare) but note it; a monotonic row-version column
  is the stronger follow-up if needed.
- PgBouncer transaction-scoped RLS already in place — compare-and-swap runs inside the write txn.
