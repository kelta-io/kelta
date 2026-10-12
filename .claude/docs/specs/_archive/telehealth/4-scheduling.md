# [Slice 4] — Scheduling & Visit Links

> Child spec of [Telehealth parent](./README.md) — **SHIPPED 2026-07-10** (V169); as-built
> notes below. Conforms to parent §Shared contracts, §Security. Depends on slice 1.
> Backend + FE.
>
> **As-built decisions / deltas from this spec:**
> - **Bookings create as CONFIRMED** (auto-confirm; REQUESTED reserved for future approval
>   flows). The confirmation email fires immediately with the visit link + `.ics`.
> - **Overlap enforcement = per-provider `pg_advisory_xact_lock` + slot re-check inside one
>   transaction** — NOT a btree_gist exclusion constraint (the standalone prod Postgres
>   carries no extra extensions). The service also re-verifies the requested time against
>   `SlotService` under the lock, so a client can never book an unoffered time.
> - **Reminders are a platform-owned sweep** (`AppointmentReminderSweep`, @Scheduled 60s,
>   atomic UPDATE-claim on `reminder_sent_at` — multi-pod safe, fires once, drops rather
>   than double-sends on crash) instead of seeded per-tenant flows. Tenants can still layer
>   flows on record.changed. Offset configurable: `kelta.telehealth.reminders.offset-minutes`
>   (60 default).
> - **Visit links**: stateless HMAC token (`VisitTokenService`,
>   `kelta.telehealth.visit-secret` — DEV default with a startup warning; set
>   `KELTA_TELEHEALTH_VISIT_SECRET` in prod) bound to (tenant, appointment, portalUser, exp
>   = scheduledEnd+1h), multi-use until exp; `GET /api/telehealth/visits/{token}` (gateway
>   unauthenticated path) re-checks the LIVE appointment row (cancelled kills the link),
>   mints a fresh single-use 15-min portal login token, and 302s — to the tenant's
>   headless `portalAuth.inviteRedirectUri` callback (slice 8) as
>   `?token=<raw>&appointmentId=<id>` when configured, so the portal exchanges the
>   token via `POST /portal/api/login/verify` and deep-links its visit page; else
>   into the kelta-auth verify — one click from email to an authenticated session.
> - **Availability model**: RULE rows (weekday 0=Sun..6=Sat, start/end LOCAL time, per-row
>   timezone — wall-clock stable across DST) + EXCEPTION rows (closed date, or an additive
>   window for a date). Managed via the admin-only generic JSON:API for now (no authoring
>   UI in this slice).
> - **Staff surface is a minimal `/app/appointments` list** (provider view, complete /
>   no-show / cancel) — the CalendarMonthView integration and richer visit UX land with
>   slice 6.
> - New endpoint beyond spec: `GET /api/telehealth/providers` (users with active
>   availability) feeding the widget's provider select. Slot range capped at 62 days.
> - Widget props as-built: `providerId` (optional fixed), `visitType`, `durationMinutes`
>   (15/30/45/60).

## 1. Goal & scope

Delivers scheduled visits between a provider (internal) and a portal user:

- System collections: `telehealth-availability` (weekly rules + date exceptions per
  provider) and `telehealth-appointments` (portalUserId, providerId, scheduledStart/End,
  status `REQUESTED|CONFIRMED|CANCELLED|COMPLETED|NO_SHOW`, visitType, reason,
  conversationId?, videoSessionId? — video fields wired in slice 5).
- **Slot engine**: `GET /api/telehealth/slots?providerId&from&to&duration` computes
  availability minus booked appointments; `POST /api/telehealth/appointments` books with a
  conflict check that survives concurrent requests (constraint/locking, not read-then-write).
- **Booking surfaces**: page-builder widget `appointment-scheduler` (portal picks provider →
  slot → confirms; auto-creates the participant share via slice 1); staff side books from
  the appointment collection UI + `CalendarMonthView` on `scheduledStart` (existing saved
  calendar views).
- **Reminders & confirmations**: seeded, tenant-editable flow templates — confirmation email
  on CONFIRMED (with RFC 5545 `.ics` attachment), reminder at T-minus offset via
  `DelayActionHandler` (`delayUntilField: scheduledStart` minus configured minutes),
  cancellation notice. Seeded email templates `appointment_confirmed`,
  `appointment_reminder`, `appointment_cancelled` with a signed **visit link**.
- **Visit links**: `GET /api/telehealth/visits/{signedToken}` (public path) — HMAC token
  bound to (tenant, appointment, portal user), valid only in the appointment window ±
  grace, redirects into the portal app's appointment page (magic-link auth if no session).

Does NOT deliver: recurrence, group visits, external calendar sync (Google/CalDAV — the
`.ics` attachment is the v1 interop), SMS reminders, video session creation (slice 5).

## 2. UI samples

Widget: 3-step wizard (provider/visit type → date grid with free slots in the portal user's
timezone → confirm + reason). Staff: appointments list + calendar view; appointment record
detail shows status timeline, linked conversation, reschedule/cancel actions.

## 3. Data & API contracts

```
GET  /api/telehealth/slots?providerId&from&to&duration=30 → {slots:[{start,end}]}   (tenant TZ math server-side)
POST /api/telehealth/appointments {providerId, start, visitType, reason?}           → 201 (portal: self; staff: any portalUserId)
POST /api/telehealth/appointments/{id}/cancel|reschedule|complete
GET  /api/telehealth/visits/{token}    → 302: headless portal callback (?token&appointmentId) when configured, else kelta-auth verify
```

Availability shape: weekday, startTime, endTime, timezone, effectiveFrom/To, plus exception
rows (date, closed|window-override). Slot computation is pure worker logic (unit-testable);
booking enforces `status IN (REQUESTED, CONFIRMED)` non-overlap per provider. Portal authz:
own appointments only (participant share on create + in-controller checks on the scoped
endpoints, same idiom as chat).

## 4. DB migrations

One migration (verify head): two tables + RLS + indexes
(`(tenant_id, provider_id, scheduled_start)` and an overlap-guard constraint — exclusion
constraint on tstzrange per provider where status active, or equivalent locking strategy
decided at implementation).

## 5. File-by-file code changes (sketch)

| Area | Files |
|------|-------|
| runtime-core | `SystemCollectionDefinitions` +2 collections |
| kelta-worker | `TelehealthSchedulingController` + `SlotService` + `AppointmentService` (JdbcTemplate repos, conflict-safe booking), `AppointmentHook` (participant share, NATS via record.changed only — no custom subject needed), `.ics` generator util in the email path, seeded flow + email templates, gateway static route registration `/api/telehealth/**`, visit-token service (HMAC, campaign precedent), audit events |
| kelta-ui | `appointment-scheduler` widget descriptor; appointments admin/list config; calendar saved-view seed |
| i18n | `telehealth.scheduling.*` |

## 6. Test plan

- Worker unit: slot math (rules + exceptions + TZ/DST edges), double-book race (constraint
  path), visit-token window binding + single-use, `.ics` output validity (RFC 5545 lint).
- Harness: RLS on appointments; portal reads own only; booking conflict under two concurrent
  transactions (real Postgres).
- Vitest: widget wizard flow with mocked slots.
- Playwright (post-deploy, skip-gated): portal books via widget → confirmation email in
  mailpit with `.ics` + visit link → link lands authenticated on the appointment page;
  staff sees it on the calendar; reminder flow fires (delay shortened in test config).

## 7. Docs to update (same PR)

`status.md`; `integrations.md` (`.ics` + visit links); `architecture.md`
(`/api/telehealth/**` authz row + static route); `concerns.md` if the exclusion-constraint
approach adds operational notes.

## 8. Risks & open questions

- Timezone/DST correctness is the bug farm — all persistence UTC, slot expansion in the
  provider's zone, rendering in the viewer's; DST-transition tests are mandatory.
- Overlap enforcement choice (Postgres exclusion constraint vs advisory lock) decided at
  implementation; the harness race test pins the behavior either way.
- Reminder flows are tenant-editable seeds — a tenant deleting the flow silently loses
  reminders; note in the admin UI (badge "reminders disabled") as a stretch.
- Provider identity = platform user in v1; a separate "provider directory" collection
  (specialties, display bios for the widget) is deferred unless the widget needs it.
