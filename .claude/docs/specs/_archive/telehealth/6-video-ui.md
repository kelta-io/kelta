# [Slice 6] — Video UI & Visit Experience

> Child spec of [Telehealth parent](./README.md), to be refined with the implementation.
> Conforms to parent §Reuse Map, §Security. Depends on slice 5 (+ slice 3 for in-call
> chat). Frontend-only.

> **SHIPPED 2026-07-11 (as-built).** `VideoRoom` in `@kelta/components` (LiveKit,
> lazy-loaded via a dedicated `@kelta/components/video` subpath entry — bundle-split
> build-verified: 0 livekit refs in the base chunk) + portal `video-visit` widget +
> `/app/visits/:appointmentId` `VisitPage` (window±grace join gating with live
> countdown, `usePresence('visit:<appointmentId>')` waiting room, `consentMachine` →
> member-only `POST /api/telehealth/sessions/{id}/consent` stamping
> `video-sessions.recordingConsent` + audit `RECORDING_CONSENT_CAPTURED`) + staff
> Join-visit / chat Start-video. **Deviation:** no per-tenant "records visits" flag
> exists yet, so the consent screen is **config-driven** (widget `consentTextOverride`
> / VisitPage `recordingEnabled` prop, default off), not a tenant policy — thread a
> backend flag into `VisitJoinCard.recordingEnabled` when one lands. Consent POST is
> best-effort on join (decline still joins unrecorded — default policy).

## 1. Goal & scope

Delivers the call experience for both personas:

- **Shared `VideoRoom` component** in `@kelta/components` wrapping
  `@livekit/components-react` + `livekit-client` (Apache-2.0): pre-join device check
  (cam/mic select, preview, permissions troubleshooting), grid layout, control bar
  (mute/cam/leave; screen share if free), connection-quality indicator, ended state.
  Lazy-loaded — the LiveKit bundle never ships in the base app chunk.
- **Staff side**: "Join visit" on the appointment record (window-gated), "Start video"
  escalation from a chat conversation (creates an ad-hoc session via slice 5), in-call side
  panel embedding the slice-3 message thread.
- **Portal side**: `video-visit` page-builder widget — upcoming visit card, join enabled in
  the appointment window ± grace, **waiting room** (patient joins presence resource
  `visit:<appointmentId>`; provider's appointment view shows "patient is waiting" via
  `usePresence`), consent screen when tenant recording is enabled (consent stored on the
  session before any egress starts), always-visible recording indicator, post-call summary
  screen.
- Visit-link landing (slice 4) resolves into this widget's page.

Does NOT deliver: transcription, backgrounds/blur (LiveKit track processors — v2), kiosk
mode, provider multi-call juggling.

## 2. UI samples

Pre-join: device selectors + preview + "Join visit" (disabled outside window with
countdown). In-call: video grid, control bar, chat side panel (staff), recording pill.
Waiting room (portal): "Dr. K will admit you shortly" + camera preview. Consent modal:
tenant-configurable text, Accept/Decline (decline → audio-video call proceeds unrecorded or
join blocked per tenant policy).

## 3. Data & API contracts

Consumes slice-5 token endpoint verbatim. Consent:
`POST /api/telehealth/sessions/{id}/consent {accepted}` (added here, worker-side trivial,
stored on `video-sessions.recordingConsent`, audited `RECORDING_CONSENT_CAPTURED`).
Presence resource convention `visit:<appointmentId>` (parent §Shared contracts). Widget
props: `showUpcomingList`, `joinGraceMinutes`, `consentTextOverride?`.

## 4. DB migrations

None.

## 5. File-by-file code changes (sketch)

| Area | Files |
|------|-------|
| @kelta/components | `VideoRoom/` (pre-join, room, ended states; deps `@livekit/components-react`, `livekit-client`) |
| kelta-ui app | appointment record "Join visit" action, chat console "Start video" escalation, `pages/app/VisitPage/` (route target for visit links), `widgets/builtins/engagement.tsx` +`video-visit` descriptor, `usePresence` waiting-room wiring |
| kelta-worker | consent endpoint (small; rides this slice) |
| i18n | `telehealth.visit.*` |

Bundle rule: `React.lazy` + dynamic import for everything touching livekit-client; verify
chunk split in the build output.

## 6. Test plan

- Vitest: window-gating logic, consent flow state machine, token-fetch error surfaces
  (feature off / governor cap → friendly messages), VideoRoom renders with a mocked LiveKit
  room object.
- Playwright (post-deploy, skip-gated): two contexts with
  `--use-fake-device-for-media-stream` / fake UI stream — portal joins waiting room,
  provider sees presence, both connect to the dev-compose LiveKit, media tracks present,
  leave → session ENDED; consent modal path when recording enabled.
- Manual cross-network smoke (TURN path) documented as a release gate with slice 5.

## 7. Docs to update (same PR)

`status.md` (Telehealth → ✅ when this lands); `kelta-ui/DESIGN.md` if the call surface
introduces new visual tokens; parent README slice table SHIPPED annotations.

## 8. Risks & open questions

- Browser permission UX (blocked camera) is the top support driver — the pre-join
  troubleshooting states are in-scope, not polish.
- LiveKit bundle size (~hundreds of KB) — enforced lazy split; measure in CI.
- Consent-decline policy (proceed unrecorded vs block) — default: proceed unrecorded;
  per-tenant override flag. Operator decides the legal stance.
- e2e against a real SFU in CI may be flaky — keep the Playwright spec skip-gated to the
  deployed dev stack (per repo precedent) rather than fighting CI networking.
