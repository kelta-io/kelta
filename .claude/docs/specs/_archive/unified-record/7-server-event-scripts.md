# Slice 7 — Server record-event scripts (the enforced gate)

> Child of [README.md](./README.md). Backend. Depends on Slice 6 (shared event vocabulary).

## 1. Goal & scope
**Delivers:** a declarative, tenant-facing binding of record events → server-side scripts,
invoked from a `BeforeSaveHook` and executed by `GraalVmScriptExecutor`. Before-create/update:
validate + transform + **can block**; after-*: side effects. This is the authoritative gate that
client rules (Slice 6) only approximate. **Does NOT:** replace flows (flows stay for orchestration)
or the validation-rules system collection (complementary). Conforms to parent "Event & validation
model → Server binding" + Critical Rule 1 (NATS broadcast on config change).

## 2. UI samples
```
Record Scripts (collection: opportunities)
  ┌ "Enforce approval" ── event: BEFORE_UPDATE ── active ─┐
  │ script:                                               │
  │   if (record.discount > 0.5 && !record.approved)      │
  │     throw kelta.validationError('discount',           │
  │       'Discount over 50% requires approval');         │
  └───────────────────────────────────────────────────────┘
```
Backend: on `PATCH /api/opportunities/{id}` the before-update hook runs the script; a thrown
`validationError` → 422 with `/data/attributes/discount` pointer (existing envelope).

## 3. Data & API contracts
- New system collection `record-scripts` (mirrors `validation-rules` shape): `collectionId`,
  `name`, `event` (`BEFORE_CREATE`|`BEFORE_UPDATE`|`AFTER_CREATE`|`AFTER_UPDATE`|`AFTER_DELETE`),
  `script` (JS), `active`, `order`, `timeoutMs`.
- Script bindings (mirror `InvokeScriptActionHandler`): `record`, `previousRecord`, `input`,
  `context` (tenantId, collectionName, recordId, userId) + a `kelta.validationError(field,msg)`
  helper mapped to `RecordValidationException`.
- Registry refresh: a `RecordScriptRefreshHook` (BeforeSaveHook on `record-scripts`) publishes
  `kelta.config.collection.changed.<collectionId>` (or a dedicated subject) so all pods refresh.

## 4. DB migrations
`V149__add_record_scripts.sql` — the `record_script` table (RLS-enabled, tenant-scoped), mirroring
`validation_rule` (V-numbering: verify head is **V148** at implementation time).

## 5. File-by-file code changes
- **`SystemCollectionDefinitions.java`** — declare `record-scripts` collection.
- **New** `kelta-worker .../listener/RecordScriptHook.java` (BeforeSaveHook, wildcard or per-
  collection) — loads active scripts for the collection+event from a `RecordScriptRegistry`, runs
  them via `ScriptExecutor`, maps thrown validation errors → `BeforeSaveResult.error` / 422,
  merges field updates.
- **New** `RecordScriptRegistry` + `RecordScriptRefreshHook` (NATS broadcast, per Critical Rule 1).
- **Wire** the active `ScriptExecutor` bean: confirm `GraalVmScriptExecutor` is `@Primary` (not the
  no-op `LoggingScriptExecutor`); add `@ConditionalOnProperty` guard + config doc. **Correct
  `status.md`** (it says the executor is a no-op; the GraalVM impl + `org.graalvm.polyglot` dep are
  present — verify at implementation).
- Per-tenant governor: count script executions against quota; enforce `timeoutMs`.

## 6. Test plan
- Worker unit (mocked `ScriptExecutor`): hook runs on the right event; thrown error → 422 with
  pointer; field transform merged; order respected; inactive skipped.
- **kelta-test-harness** real-DB: a `BEFORE_UPDATE` script blocks a write end-to-end over real
  Postgres + GraalVM (the DB-constraint-test-gap lesson — a mock can't prove the block persists
  nothing).
- Security: script cannot reach beyond its Cerbos/tenant context; timeout aborts a runaway script.

## 7. Docs to update
- `integrations.md` (record-script SPI + bindings + governor), `architecture.md` (hook order:
  formula 250 → validation → record-scripts → module hooks), `status.md` (script execution real;
  move off the 🟡 no-op note), `concerns.md` (script abuse/timeout surface), CLAUDE.md Messaging
  table if a new subject is added.

## 8. Risks & open questions
- **Security-sensitive → not auto-merged** (per SECURITY.md): arbitrary server JS. Sandbox limits,
  timeout, governor, and no-privilege-escalation must be reviewed.
- Confirm GraalVM is on the worker runtime classpath in the deployed image (dep present in
  `runtime-module-integration`; verify worker packaging).
- Decide subject: reuse `kelta.config.collection.changed` vs a new `kelta.config.record-script.changed`.
