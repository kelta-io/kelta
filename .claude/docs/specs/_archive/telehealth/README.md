# Telehealth — Chat + Video for Internal and External Users (Parent Spec)

> **Status:** parent planning spec. Authoritative shared contract for adding **human-to-human
> chat and scheduled video visits** to the platform: an internal user (agent/provider) working
> from the end-user app answers chats and joins scheduled video calls with **external users
> (patients/portal users)** who reach the same services through a **tenant custom application**
> (page-builder pages, optionally on a custom domain). Each slice in the
> [Slice plan](#slice-plan) is expanded into its own child spec in this directory and
> **extends, never contradicts** the [Reuse Map](#reuse-map),
> [shared contracts](#shared-contracts), and [Security](#security) sections below.
>
> Source-verified against the codebase on 2026-07-10 (Flyway directory head **V166**
> — next new migration is **V167**; deployed flyway_schema_history keeps pre-flatten
> numbering, so always check the directory + deployed history before numbering). If code and
> this doc disagree, trust the code and fix this doc.

## How to use this document

This parent defines the cross-cutting architecture once. Child specs each cover one PR-sized
slice with acceptance criteria, UI samples, exact contracts, DB migrations (or "none"),
file-by-file changes, and a test plan — per the child-spec template in
`specs/_archive/app-surfacing/README.md` (sections 1–8; sections that don't apply state "N/A — reason").
Read this parent first; every child references it.

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — Portal identity & record access | `1-portal-identity.md` | **backend + auth, security** |
| 2 — Chat backend | `2-chat-backend.md` | backend (collections, WS routing, NATS) |
| 3 — Chat UI (console + widget) | `3-chat-ui.md` | FE (internal console + portal widget) |
| 4 — Scheduling & visit links | `4-scheduling.md` | backend + FE (appointments, slots, reminders) |
| 5 — Video backend (LiveKit) | `5-video-backend.md` | **backend + infra, security** |
| 6 — Video UI & visit experience | `6-video-ui.md` | FE (join, waiting room, consent) |
| 7 — Archival & retention (chats + calls) | `7-archival-retention.md` | backend + FE (encounter record, retention/purge) |
| 8 — Headless portal auth (JSON magic-link + redirect allowlist) | `8-portal-auth-headless.md` | **backend + auth, security** |

**Dependency order (hard edges): 1 → 2 → 3, and 1 → 4 → 5 → 6; 7 follows 2 and 5.** Chat
(2–3) and scheduling (4) are independent tracks after slice 1. Slice 6 also consumes the
chat widget from 3 for in-call chat, but degrades gracefully without it. Slice 7's UI
touches (Archived tab, encounter-record card) land on the slice 3/6 surfaces.

## Context

The platform has no human-to-human communication features. The 2026-07-10 recon confirmed:

1. **Chat is greenfield** — no chat/message/conversation collections, controllers, or event
   streams exist anywhere (kelta-ai chat is Claude Q&A, not messaging).
2. **The realtime plumbing is built and extensible** — gateway `/ws/realtime`
   (`RealtimeWebSocketHandler`, `SubscriptionManager`, `RealtimeBridge`,
   `PresenceService`) authenticates JWTs, routes per `tenantId:collection`, fans out NATS
   events, and tracks per-resource presence with heartbeats. The FE `RealtimeClient` +
   `RealtimeProvider` (invalidation-only) ship in the end-user app.
3. **External users don't exist as a concept** — every user is a staff-shaped
   `platform_user`; no `user_type`, no self-serve or passwordless login, no default
   "own records only" enforcement. But the ingredients do exist: the invite flow +
   email templates, federated JIT provisioning, the `record_share` table +
   `RecordShareAccessService` Cerbos widening, and signed-token public endpoints
   (campaign tracking HMAC links).
4. **Scheduling primitives mostly exist** — DATE/DATETIME fields, `CalendarMonthView`,
   SCHEDULED (cron) flows, `DelayActionHandler` (delay-until-field), tenant SMTP email +
   templates + logs, S3 attachments with presigned URLs, governor limits, per-tenant feature
   flags, `SecurityAuditLogger`. Missing: availability/slot logic, recurrence, a production
   `SmsProvider`.
5. **Video is entirely absent** — no WebRTC/SFU/TURN references in the repo.

**Intended outcome:** a tenant can enable Telehealth, drop a `chat-panel` and
`appointment-scheduler`/`video-visit` widget onto custom-app pages for portal users, and
staff get a chat inbox console plus calendar-driven video visits in the end-user app — with
tenant isolation, auditability, and deny-by-default portal access preserved throughout.

## Key architecture decisions

- **Video = self-hosted LiveKit OSS (Apache-2.0 WebRTC SFU).** Standards-first (WebRTC,
  DTLS-SRTP media encryption), open source, single-binary server that deploys to the
  existing K8s cluster via ArgoCD, embedded TURN with TLS fallback, egress service for
  recording, signed webhooks for room lifecycle, and mature React components
  (`@livekit/components-react`, Apache-2.0). Access tokens are plain HS256 JWTs with a
  `video` grant claim — the worker mints them with existing JWT libs; no vendor SDK required.
  **Media never traverses the gateway**: clients connect to LiveKit's own ingress
  (`livekit.<domain>`); the platform only authorizes + mints tokens and consumes webhooks.
  Considered and rejected: Jitsi (multi-service footprint, weaker embed SDK), mediasoup
  (a library — we'd own SFU orchestration), SaaS video (Twilio/Daily — closed source,
  per-minute cost, PHI leaves the infrastructure; violates the open-source-only rule).
- **Chat = platform-native on the existing realtime socket, with conversation-scoped
  routing.** New `kelta.chat.message.<tenantId>.<conversationId>` NATS family; a
  `ChatMessageBridge` in the gateway (mirror of `RealtimeBridge`) fans events **only to
  sessions that joined the conversation and passed a membership check** — NOT the
  collection-broadcast model, which fans to every tenant subscriber and would leak message
  metadata across patients. Considered and rejected for v1: Matrix (open standard, but a
  foreign identity/homeserver model and heavy ops for an embedded tenant chat; revisit if
  cross-org federation ever matters).
- **Chat/scheduling data = system collections** (`chat-conversations`, `chat-messages`,
  `chat-participants`, `chat-queues`, `telehealth-appointments`, `telehealth-availability`,
  `video-sessions`) — the approvals precedent: platform code and UI rely on a fixed shape,
  and system collections get JSON:API, RLS, flows (RECORD_TRIGGERED automations), audit,
  attachments, and realtime for free. Growth risk (shared public-schema tables holding
  high-volume messages) is mitigated with indexes + a per-tenant retention policy and
  recorded in `concerns.md`.
- **External users = first-class platform users with `user_type = PORTAL`** — not anonymous
  guests. Passwordless magic-link/OTP login (email; SMS later via the existing `SmsProvider`
  SPI), admin- or flow-initiated invites, optional per-tenant federation (already built).
  Portal users authenticate exactly like staff (same JWT, same gateway filters, same WS
  auth); a seeded **Portal User profile** + participant-based `record_share` grants give
  deny-by-default access to only their own conversations/appointments.
- **Direct reads stay on the authorized HTTP path; the socket stays an invalidation
  signal.** The established invalidation-only client rule holds for chat too: a pushed
  `chat.message` event triggers a refetch of the conversation query — the client never
  writes pushed `data` into caches. (Join-time membership checks make payload push safe as
  a v2 latency optimization; not v1.)
- **Scheduling = availability rules + a slot engine + booked appointments**, with reminder
  automation built from existing pieces: seeded flow templates using `DelayActionHandler`
  (delay-until `scheduledStart` minus offset) + tenant email templates + an RFC 5545 `.ics`
  attachment. Visit-join links in emails are short-lived signed HMAC tokens (the
  campaign-tracking precedent), never bare record ids.
- **Feature-gated + governed per tenant.** A `telehealth` feature flag (tier defaults +
  per-tenant override, invalidated via `kelta.config.feature.changed.<tenantId>`) gates
  every endpoint and UI surface. New governor keys: `videoMinutesPerMonth` (usage counter
  mirrors the `ai-tokens-monthly` Redis pattern) and `maxPortalUsers` (enforced at
  invite/create — closing the known "gov_users tracked but not enforced" gap for the portal
  population).
- **Archive-then-purge, never delete-first.** Every closed chat and ended video call
  becomes an immutable **encounter record** (slice 7): a versioned, hashed JSON transcript
  /session-summary artifact (+ PDF render) in S3, indexed by a `telehealth-archives`
  system collection row carrying `retentionUntil` + `legalHold`. Tenant-configured
  retention (telemedicine default 7 years) drives a purge sweep that skips legal holds;
  live chat rows may be purged only *after* archival — which is also the growth mitigation
  for the shared message tables. ARCHIVED conversations are read-only.
- **Compliance posture for telemedicine.** Every conversation/session access emits
  `SecurityAuditLogger` events (OpenSearch); recording is **off by default**, per-tenant
  opt-in, with explicit in-call consent capture stored on the session record; media is
  DTLS-SRTP, signaling and chat ride wss/TLS; recordings land in the tenant's S3 prefix
  with a retention policy; NATS subjects and logs carry **ids only, never message bodies or
  PHI**. Self-hosting keeps PHI inside the operator's infrastructure (no third-party
  processor). HIPAA formalities (BAA, risk analysis) are an operator concern documented
  here, not enforced by code.

## Reuse Map

Use these — do not rebuild.

| Need | Reuse | Path |
|------|-------|------|
| WS auth + session lifecycle | `RealtimeWebSocketHandler` (JWT query param, 4000/4001 closes) | `kelta-gateway/.../websocket/RealtimeWebSocketHandler.java` |
| Socket routing + caps | `SubscriptionManager` (50 subs/session, 100 conns/tenant) | `kelta-gateway/.../websocket/SubscriptionManager.java` |
| NATS→WS fanout pattern | `RealtimeBridge` (copy for `ChatMessageBridge`) | `kelta-gateway/.../listener/RealtimeBridge.java` |
| Who-is-here / waiting room | `PresenceService` (resource-keyed, 30s heartbeat, 90s expiry) | `kelta-gateway/.../websocket/PresenceService.java` |
| FE socket client | `RealtimeClient` (backoff, resubscribe, token refresh) + `RealtimeProvider` | `kelta-ui/app/src/realtime/` |
| Record-level grants | `record_share` table + `RecordShareAccessService` Cerbos widening | `kelta-worker/.../repository/RecordShareRepository.java` |
| Invite + email delivery | `UserInviteService`, `EmailService` SPI, `email-templates`/`email-logs` | `kelta-worker/.../service/`, `runtime-module-integration/.../spi/EmailService.java` |
| Passwordless precedent | Signed HMAC public links (campaign tracking) + `PublicPathMatcher` | `kelta-worker/.../controller/CampaignTrackingController.java` |
| Time-based automation | `DelayActionHandler` (delayUntilField), `ScheduledJobExecutorService` (cron) | `runtime-module-integration/.../handlers/DelayActionHandler.java` |
| Flow triggers | RECORD_TRIGGERED / NATS_TRIGGERED / SCHEDULED flows | `runtime-core/.../flow/FlowTriggerEvaluator.java` |
| File attachments | S3 presigned upload/download, `AttachmentCleanupHook`, `AttachmentUrlEnricher` | `kelta-worker/.../service/S3StorageService.java` |
| Quotas + usage counters | `TenantTierQuotas`, `GovernorLimitsController`, Redis daily/monthly counters | `kelta-worker/.../service/TenantTierQuotas.java` |
| Per-tenant feature gate | `tenant.limits` + `kelta.config.feature.changed` invalidation | `kelta-worker/.../listener/SystemFeatureEventPublisher.java` |
| Audit trail | `SecurityAuditLogger` (structured, OpenSearch) | `kelta-worker/.../service/SecurityAuditLogger.java` |
| Inbox UX pattern | `ApprovalsInboxPage` + `TopNavBar` bell + count hook | `kelta-ui/app/src/pages/app/ApprovalsInboxPage/` |
| Calendar UI | `CalendarMonthView` (renders any DATE/DATETIME field) | `kelta-ui/app/src/components/CalendarMonthView/` |
| Custom-app embedding | Page-builder `WidgetDescriptor` registry (palette/inspector/runtime auto-wire) | `kelta-ui/app/src/pages/PageBuilderPage/widgets/` |
| Offline sends | IndexedDB outbox + `SyncEngine` + `OfflineIndicator` | `kelta-ui/app/src/offline/` |
| Custom domains + JWT issuers | `CustomDomainFilter`, `DynamicReactiveJwtDecoder` | `kelta-gateway/.../filter/CustomDomainFilter.java` |
| In-controller permission checks | `requirePermission` pattern | e.g. `kelta-worker/.../controller/EnvironmentController.java` |
| System-collection change events | BeforeSaveHook + `PlatformEventPublisher` (Critical Rule 1) | `kelta-worker/.../listener/CollectionConfigEventPublisher.java` |

## Shared contracts

### NATS subjects (new — added to the CLAUDE.md Messaging table when each slice ships)

```
kelta.chat.message.<tenantId>.<conversationId>       chat message created (ids + sender + kind; NO body text)
kelta.chat.conversation.<tenantId>.<conversationId>  conversation lifecycle (OPENED|ASSIGNED|CLOSED|ARCHIVED)
kelta.video.session.<tenantId>.<sessionId>           video session lifecycle (CREATED|ACTIVE|ENDED|RECORDING_READY)
```

Envelope: standard `PlatformEvent<T>`. Payloads carry ids, actor, timestamps, state — never
message bodies or PHI (bodies are fetched over the authorized JSON:API/REST path).

### WebSocket protocol extensions (same `/ws/realtime` socket)

```
send  {"action":"chat.join","conversationId":"<uuid>"}     (membership-checked server-side; max 20 joined/session)
send  {"action":"chat.leave","conversationId":"<uuid>"}
recv  {"action":"chat.joined","conversationId":"…"} | {"action":"error","message":"…"}
recv  {"event":"chat.message","conversationId":"…","messageId":"…","senderId":"…",
       "kind":"TEXT|SYSTEM|ATTACHMENT","timestamp":"…"}            (no body — invalidation signal)
recv  {"event":"chat.conversation","conversationId":"…","status":"OPEN|ASSIGNED|CLOSED|ARCHIVED",…}
```

Presence reuses the existing `presence.join` with resource conventions
`chat:<conversationId>` (typing/viewing) and `visit:<appointmentId>` (waiting room).

### REST namespace (gateway static route `/api/telehealth/**` and `/api/chat/**` — Pitfall: register in `RouteConfigService.registerStaticRoutes()`)

```
POST /api/chat/conversations                       start conversation (portal or staff)
GET  /api/chat/conversations?view=mine|queue|all   inbox lists (staff views permission-gated)
GET  /api/chat/conversations/{id}/messages?after=  history (participant-only, in-controller check)
POST /api/chat/conversations/{id}/messages         send (participant-only)
POST /api/chat/conversations/{id}/assign|close|read-receipt
GET  /api/telehealth/slots?providerId&from&to&duration
POST /api/telehealth/appointments                  book (conflict-checked)
POST /api/telehealth/sessions/{id}/token           mint LiveKit access token (member-only)
GET  /api/telehealth/visits/{signedToken}          visit-link landing (public path, HMAC)
POST /api/telehealth/webhooks/livekit              LiveKit webhook (public path, signature-verified)
POST /api/auth/portal/request-link | /exchange     magic-link login (kelta-auth, rate-limited)
POST /api/telehealth/archives                      archive-now {sourceType, sourceId} (staff)
GET  /api/telehealth/archives[/{id}]               encounter records (staff; portal sees own; downloads audited)
POST /api/telehealth/archives/{id}/legal-hold      MANAGE_DATA only
GET|PUT /api/telehealth/retention-settings         MANAGE_DATA only
```

Generic JSON:API on the chat/telehealth system collections remains available to staff
profiles; the Portal User profile gets **no** direct collection grants — portal access goes
through the scoped endpoints above plus `record_share` widening.

### Identity

`platform_user.user_type ∈ {INTERNAL, PORTAL}` (default INTERNAL). The JWT carries a
`user_type` claim; gateway forwards it like other identity headers. Portal sessions are
issued by kelta-auth exactly like staff sessions (same issuer/JWKS path, custom-domain
issuers already supported by `DynamicReactiveJwtDecoder`).

## Security

- **Slices 1 and 5 are security-typed PRs — never auto-merged** (SECURITY.md + standing
  rule): slice 1 changes authentication (passwordless login, new principal type); slice 5
  exposes signed public endpoints and mints media-access tokens.
- **Deny-by-default for portal users.** The seeded Portal User profile grants `API_ACCESS`
  only — no collection CRUD, no setup permissions. Every portal read/write flows through
  participant-scoped endpoints (in-controller membership checks) or `record_share` grants
  written by hooks when the user becomes a participant. A portal JWT hitting `/api/admin/**`
  or generic collections fails closed.
- **WS join authz.** `chat.join` verifies conversation membership against the worker
  (cached, short TTL) before any fanout; presence resources for chat/visits apply the same
  check. The existing tenant/collection subscribe path is unchanged.
- **Signed links.** Magic-link + visit tokens: single-use, short expiry (magic link ≤ 15
  min, visit link scoped to the appointment window), HMAC-signed server secret, bound to
  tenant + user + purpose, invalidated on use, rate-limited request endpoints with no
  account enumeration (uniform responses). Follow the campaign-tracking HMAC implementation,
  not a new scheme.
- **LiveKit trust boundary.** API key/secret live in the platform credential store (never
  tenant-visible); tokens are minted server-side per session with room-scoped grants and
  short TTL; webhook requests are signature-verified (LiveKit signs with the API key) and
  processed idempotently. Room names are opaque UUIDs, never PHI.
- **No PHI in transport metadata.** NATS payloads, WS events, audit entries, and logs carry
  ids only. Message bodies live in the RLS-protected tables and are fetched over the
  authorized path. Masking interplay: chat/telehealth system collections are excluded from
  full-text/embedding indexes by default.
- **Audit.** New `SecurityAuditLogger` event types: `PORTAL_LINK_REQUESTED`, `PORTAL_LOGIN`,
  `CHAT_CONVERSATION_OPENED/ASSIGNED/CLOSED`, `CHAT_ACCESS_DENIED`,
  `VIDEO_TOKEN_ISSUED`, `VIDEO_SESSION_STARTED/ENDED`, `RECORDING_CONSENT_CAPTURED`,
  `ARCHIVE_CREATED`, `ARCHIVE_ACCESSED`, `ARCHIVE_PURGED`, `LEGAL_HOLD_CHANGED`,
  `RETENTION_SETTINGS_CHANGED`.
- **JWT on the WS query param** (existing design) applies to portal sockets too — never log
  upgrade URLs.

## Deployment & infrastructure

- **LiveKit (prod):** new ArgoCD app in `~/GitHub/homelab-argo` — Deployment (single
  replica to start; Redis only needed multi-node), Service, Ingress `livekit.rzware.com` /
  `livekit.kelta.io` (wss signaling on 443), UDP media port range on the node (or
  TURN-TLS 443 fallback for restrictive patient networks — embedded TURN enabled), egress
  service optional until recording ships. Keys in a K8s Secret; image mirrored through
  `harbor.rzware.com`.
- **Local dev:** `docker-compose.yml` gains a `telehealth` profile (`livekit/livekit-server`
  dev-mode keys) + Makefile `up-telehealth`; mailpit already covers magic-link/reminder
  email testing.
- **Rollout:** feature flag off for all tenants until slice 6; enable per tenant via
  governor limits UI.

## Explicitly deferred (v2+)

- Production `SmsProvider` implementation (SPI exists; needs a provider decision) — email
  covers v1 notifications.
- Recurring appointments + multi-provider group visits (LiveKit rooms already support >2
  participants; the scheduling model is 1:1 for v1).
- Screen share + in-call file share polish (LiveKit supports screen share nearly free —
  stretch goal inside slice 6, not a commitment).
- Server-push message payloads over the socket (needs the per-subscriber authz stance
  formalized; v1 is invalidation-only).
- Automatic queue routing/assignment strategies beyond manual claim + flow templates
  (round-robin/least-busy as flow recipes later).
- Waiting-room auto-admit, kiosk mode, EHR/FHIR export (HL7 FHIR is the standards path if
  clinical-system integration is requested).
- Matrix bridge/federation.

## Docs to update (per CLAUDE.md Rule 6, as slices ship)

- `.claude/docs/status.md` — Telehealth row moves ⚪ → 🟡 → ✅ per slice.
- `CLAUDE.md` — Messaging table (new subjects, slices 2/5), Module Map only if a new module
  appears (none planned — code lands in worker/gateway/auth/ui), specs row (slice 0).
- `.claude/docs/architecture.md` — `/api/chat/**` + `/api/telehealth/**` authorization rows,
  WS protocol extension, LiveKit trust boundary.
- `.claude/docs/integrations.md` — LiveKit section (deploy, webhooks, token shape), magic
  links, `.ics` generation.
- `.claude/docs/conventions.md` — conversation-scoped socket rule (join-gated fanout, ids
  only), portal-endpoint authz idiom.
- `.claude/docs/concerns.md` — chat table growth/retention, LiveKit ops (UDP/TURN), portal
  enumeration surface.
- `.claude/docs/playbooks.md` — "add a conversation-scoped realtime event" recipe (slice 2).
- `README.md` — `telehealth` compose profile + env keys (slice 5).
