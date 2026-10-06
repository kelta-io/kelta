# Slice 4 — Retire the Bespoke Owner-Guard Hooks

> Parent: [`README.md`](README.md). Depends on slice 2. **Security — never auto-merged.**

## 1. Goal & scope

**Delivers:** the seven per-collection hooks (`WatchGuardHook`, `WinGuardHook`,
`UserPreferenceGuardHook`, `NoteGuardHook`, `FieldReportGuardHook`, `PhotoGuardHook`,
`CommentGuardHook`) are deleted; the same protection comes from ownership metadata enforced by
`OwnerScopeGuardHook` + the read predicate:

| Collection | Kind | Declared where | `ownerField` | `ownerScope` | `ownerScopeReads` |
|---|---|---|---|---|---|
| `watches` | system | `SystemCollectionDefinitions.watches()` | `memberId` | `ALL` | `true` |
| `wins` | system | `SystemCollectionDefinitions.wins()` | `memberId` | `ALL` | `true` |
| `user-ui-preferences` | system | `SystemCollectionDefinitions` | `userId` | `ALL` | `true` |
| `notes` | system | `SystemCollectionDefinitions` | `createdBy` | `ALL` | `false` — see §8 |
| `field-reports`, `facility-photos`, `facility-comments` | **tenant** | tenant metadata via API (not code) | `createdBy` | `ALL` | `false` (public reads) |

Today's hooks guard writes for **all** callers; `ALL` + `MODIFY_ALL_DATA` bypass preserves that
and adds the admin escape hatch the hooks lacked.

**Also delivers:** reconciliation of `WatchController`/`WinController` support mode with the
generic guard (parent finding 3), and owner-column indexes on the converted system tables.

## 2. UI samples

N/A — no UI change.

## 3. Data & API contracts

No API change for members. Error contract change: foreign writes return **404** (was 400
`ValidationException`) — matches the member controllers' existing 404s.

**Read semantics change (by design):** `user-ui-preferences` reads become owner-filtered (closes
the concerns.md "reads unguarded" entry). The three tenant collections and `notes` keep
**unscoped reads** (`ownerScopeReads=false`) — facility pages show everyone's reports, photos and
comments; notes are read through their parent record — exactly today's behaviour.

## 4. DB migrations

`V2xx__owner_scope_system_indexes.sql` — indexes on `watch(member_id)`, `win(member_id)`,
`user_ui_preference(user_id)` if missing (check existing indexes first).

## 5. File-by-file code changes

| File | Change |
|---|---|
| the seven hook classes + their tests | delete |
| `kelta-worker/.../config/FlowConfig.java` | remove the seven `@Bean` registrations |
| `SystemCollectionDefinitions` (`watches`, `wins`, `userUiPreferences`, `notes`) | declare ownership |
| `WatchController`, `WinController` | support mode writes run under `CallerContext.callAs(<subject as owner>…)` only when the caller holds `MANAGE_DATA`, so the generic guard sees the member as caller — or document that support writes use `MODIFY_ALL_DATA` bypass |
| the tenant that owns those three collections | **metadata change via the admin API** (not code): set `ownerField=createdBy`, write-only scope on its three collections — in the PR's rollout notes |
| `kelta-worker/CLAUDE.md` | replace the "owner-guard hook" reference implementations |

## 6. Test plan

- Port every existing hook test to a parameterised `OwnerScopeGuardHook` test over the seven
  collection shapes (same cases: create as other → rejected, update/delete foreign → 404, internal
  tier admitted).
- `WatchControllerSupportModeMvcTest` re-run **without mocking QueryEngine** (harness), proving
  support mode works with the real guard (it could not have with `WatchGuardHook`).
- Harness: a tenant shaped like the one that owns those collections — public facility page still lists all reports; member can
  only edit their own.

## 7. Docs to update

`kelta-worker/CLAUDE.md`, `concerns.md` (remove per-hook notes; close user-ui-preferences read
gap), `specs/consumer-alerting/*` and `specs/app-data-entry/1-user-preferences.md` (point to the
generic mechanism), `status.md`.

## 8. Risks & open questions

- **Rollout order for the tenant collections:** set that tenant's metadata **before** deploying
  the hook deletion, or members lose write protection in between. The PR must include the
  metadata change as a pre-deploy step and a post-deploy probe.
- `notes` is attached to records across collections; owner-only edits match today's hook, reads
  stay unscoped (notes are read through their parent record's access).
