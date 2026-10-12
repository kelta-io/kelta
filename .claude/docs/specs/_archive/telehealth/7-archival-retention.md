# [Slice 7] — Archival & Retention (Chats + Video Calls)

> Child spec of [Telehealth parent](./README.md), to be refined with the implementation.
> Conforms to parent §Key architecture decisions, §Security. Depends on slice 2 (chat) and
> slice 5 (video sessions); its UI touches land in the slice 3 console and the slice 6
> appointment/visit surfaces. Backend + FE.

## 1. Goal & scope

Delivers the **encounter record**: closed chats and ended video calls are archived into an
immutable, retrievable artifact with tenant-controlled retention — archive-then-purge, never
delete-first. This is the compliance backbone for telemedicine (visit documentation) and
simultaneously the growth mitigation for the live chat tables flagged in slice 2.

- **`telehealth-archives` system collection** — one row per archived source:
  sourceType `CONVERSATION|VIDEO_SESSION`, sourceId, appointmentId?, portalUserId,
  artifact attachment ids, sha256, archivedAt, archivedBy (user or `system`),
  retentionUntil, legalHold (boolean), purgedAt?.
- **Archive artifact** — canonical JSON transcript/summary written to S3 via the existing
  attachment lifecycle and linked to the archive row (and the appointment record when one
  exists): for conversations — participants, full ordered messages, attachment manifest
  (keys + hashes; the attachment objects themselves are retained, not copied), status
  history; for video sessions — session metadata, participants + join/leave times,
  duration, consent state, recording key when present. A human-readable **PDF render** of
  the transcript is produced alongside the JSON (staff/patient download). SHA-256 of the
  JSON stored on the archive row for tamper evidence.
- **Lifecycle**: conversations gain an `ARCHIVED` status (CLOSED → ARCHIVED). Manual
  "Archive now" (staff) + **auto-archive** of CLOSED conversations after
  `archiveAfterDays` (tenant setting) via the scheduled-job pattern
  (`ScheduledJobExecutorService`). Video sessions auto-archive on `ENDED` (webhook path,
  slice 5). ARCHIVED conversations are read-only — the message hook rejects new messages.
- **Retention & purge**: `retentionYears` (tenant setting; telemedicine default **7
  years**, opinionated) sets `retentionUntil`. A retention sweep purges *expired* archives
  (artifact + linked recording objects + archive row marked `purgedAt`) — **skipping any
  row with `legalHold`**. Live-table cleanup: message rows may be purged
  `purgeLiveAfterDays` after successful archival (transcript preserved in the artifact) —
  this replaces the bare "retention purge" note from slice 2.
- **Access**: staff list/search archives (metadata filters: patient, provider, date range,
  type) and download artifacts; portal users see **their own** visit/chat history
  read-only (participant share carries over to the archive row). Every artifact download
  is audited.
- **Settings + permissions**: per-tenant retention settings (archiveAfterDays,
  retentionYears, purgeLiveAfterDays) on the telehealth admin settings surface; changing
  retention or toggling `legalHold` requires the existing **`MANAGE_DATA`** system
  permission (no new permission; delegated admins without it cannot shorten retention).

Does NOT deliver: WORM/object-lock storage guarantees (S3 bucket object-lock is an
operator/infra option, documented not enforced), e-discovery export bundles beyond
per-encounter download (data-export integration is listed, full legal-export tooling is
v2), transcript full-text search (metadata search only — PHI stays out of FTS per parent).

## 2. UI samples

Console (slice 3 surface): *Archived* filter tab; archived thread renders read-only with an
"Archived <date> · retained until <date>" banner and a Download (PDF/JSON) action.
Appointment record (slice 6 surface): "Encounter record" card — chat transcript + video
session summary + recording link, with download. Admin: Telehealth settings page section
"Retention" (three numeric settings + explanation), archive list with legal-hold toggle
(MANAGE_DATA-gated, confirm dialog).

## 3. Data & API contracts

```
POST /api/telehealth/archives                       {sourceType, sourceId}          → 201 archive row (manual archive-now; staff, member or MANAGE_CHAT)
GET  /api/telehealth/archives?filter…               metadata list (staff; portal sees own)
GET  /api/telehealth/archives/{id}                  detail incl. artifact download URLs (presigned; audited)
POST /api/telehealth/archives/{id}/legal-hold       {enabled}                       → MANAGE_DATA only
GET  /api/telehealth/retention-settings | PUT       tenant settings                 → MANAGE_DATA only
```

- Conversation status enum extends to `OPEN|ASSIGNED|CLOSED|ARCHIVED`; the
  `kelta.chat.conversation.*` event carries the new status (parent contract updated) so
  flows can trigger on archival.
- Archive rows ride `kelta.record.changed` like any system collection — no new NATS
  subject.
- Artifact JSON schema versioned (`schemaVersion: 1`) — future renders must stay
  back-readable.
- Idempotency: one archive row per (sourceType, sourceId) — re-archive requests return the
  existing row.

## 4. DB migrations

One migration (verify head): `telehealth-archives` table + RLS + indexes
(`(tenant_id, portal_user_id)`, `(tenant_id, retention_until) WHERE purged_at IS NULL AND NOT legal_hold`),
tenant retention-settings storage (tenant `limits`/settings JSON — no new table if the
existing settings pattern fits), `ARCHIVED` status check-constraint update on
chat-conversations.

## 5. File-by-file code changes (sketch)

| Area | Files |
|------|-------|
| runtime-core | `SystemCollectionDefinitions` +`telehealth-archives`; conversation status enum |
| kelta-worker | `ArchiveService` (transcript assembly, JSON+PDF render, hash, S3 write via attachment lifecycle), `TelehealthArchiveController`, auto-archive + retention-sweep scheduled jobs (`SELECT FOR UPDATE SKIP LOCKED` claim pattern), message-hook read-only guard for ARCHIVED, video `ENDED` → archive wiring (slice 5 webhook path), retention-settings endpoints, audit events (`ARCHIVE_CREATED`, `ARCHIVE_ACCESSED`, `ARCHIVE_PURGED`, `LEGAL_HOLD_CHANGED`, `RETENTION_SETTINGS_CHANGED`) |
| kelta-ui | Console Archived tab + read-only thread state; encounter-record card on appointment detail; admin Retention settings + legal-hold UI; portal history list (own archives) |
| i18n | `telehealth.archive.*` |

PDF render: reuse whatever the report PDF-export path uses (`ReportExecutionController`
export) — do not introduce a second PDF library.

## 6. Test plan

- Worker unit: transcript assembly determinism + sha256 stability, idempotent archive,
  read-only guard (message POST on ARCHIVED → 409), retention math (`retentionUntil`),
  sweep skips legalHold, purge removes artifact + recording keys and stamps `purgedAt`,
  settings permission gates (MANAGE_DATA 200 / others 403).
- Harness (real Postgres/RLS): cross-tenant archive invisibility; portal user reads own
  archive + denied on others; live-message purge after archival leaves artifact readable;
  concurrent sweep claim (SKIP LOCKED) processes each row once.
- Vitest: archived-thread read-only rendering, settings form validation, legal-hold
  confirm flow.
- Playwright (post-deploy, skip-gated): close chat → archive-now → Archived tab shows
  read-only thread → download PDF; appointment encounter card lists chat + video summary;
  portal sees own history.

## 7. Docs to update (same PR)

`status.md` (archival noted in the telehealth row); parent README slice table + contracts
(done when this slice was specced); `integrations.md` (artifact schema + S3 layout);
`concerns.md` — **close/reword the chat table-growth item** (archival + live purge is the
mitigation) and add "purge sweep is destructive — verify backup posture" note;
`architecture.md` (`/api/telehealth/archives` authz rows).

## 8. Risks & open questions

- **Purge is irreversible** — the sweep deletes PHI artifacts on schedule. Guardrails:
  legalHold check, `purgedAt` stamps (rows kept as tombstones), dry-run mode for the first
  release, and the operator backup-posture note in `concerns.md`.
- Retention defaults are jurisdiction-dependent (7 years is a common US medical-records
  default; EU/member-state rules differ) — tenant-configurable, operator decides; the spec
  ships a default, not legal advice.
- PDF rendering of rich-text messages can drift from the console rendering — the JSON
  artifact is canonical; PDF is a convenience render and says so in its footer.
- Storage cost: artifacts + retained recordings count against `gov_storage_mb` (existing
  enforcement) — no new governor key.
- Attachment objects referenced by archived transcripts must survive live-row purge —
  the manifest pins them; `AttachmentCleanupHook` cascade rules must be verified against
  this (the archive row becomes the owning record where needed).
