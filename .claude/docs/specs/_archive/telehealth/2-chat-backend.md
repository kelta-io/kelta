# [Slice 2] — Chat Backend

> Child spec of [Telehealth parent](./README.md) — **SHIPPED 2026-07-10** (V168); as-built
> notes below. Conforms to parent §Shared contracts, §Security. Depends on slice 1 (portal
> identity + participant shares). Backend: worker + gateway + runtime.
>
> **As-built decisions:**
> - **No Cerbos object grants were seeded** for the chat collections — the generic JSON:API
>   routes therefore admit only `VIEW_ALL_DATA`/`MODIFY_ALL_DATA` (admins), and
>   `ChatMessageHook` re-validates writes even on that path. `/api/chat/**` is the product
>   path; this is stricter than the spec's "Cerbos policies for staff profiles" line.
> - **Writes go through `QueryEngine`** (Delegated-scopes precedent) so hooks +
>   record.changed fire on every path; reads are direct JDBC; read receipts are direct JDBC
>   on purpose (no record.changed noise per focus).
> - Authorization matrix as-built: `view=mine` = participants (portal included);
>   `view=queue` + claim = INTERNAL users (per-queue membership deferred as planned);
>   `view=all` + cross-assign = `MANAGE_CHAT` (V168 seeds it granted to System
>   Administrator only; FE checklist entry added).
> - Gateway membership check: `GET /internal/chat/conversations/{id}/members?tenantId&user`
>   (matches platform_user id OR email — the WS JWT `sub` is a UUID on direct-login and an
>   email on auth-code); reactive `ChatMembershipClient`, 30s Caffeine cache, fail-closed.
> - `MAX_CONVERSATIONS_PER_SESSION = 20`, tracked separately from the 50-collection budget.
> - Playbook §7 ("conversation-scoped realtime event family") documents the pattern.

## 1. Goal & scope

Delivers human-to-human chat storage, APIs, and realtime delivery:

- System collections: `chat-queues`, `chat-conversations`, `chat-messages`,
  `chat-participants` (declared in `SystemCollectionDefinitions`, tables + RLS via Flyway,
  Cerbos policies for staff profiles).
- Scoped REST under `/api/chat/**` (gateway static route) with **in-controller participant
  authz** — the portal access path (parent §Shared contracts lists the endpoints).
- NATS publishers (BeforeSaveHooks) for `kelta.chat.message.*` / `kelta.chat.conversation.*`
  (ids only, per Critical Rule 1 — broadcast, never local-only refresh).
- Gateway: `chat.join`/`chat.leave` socket actions with membership verification, a
  conversation routing index (mirror of the collection index), and `ChatMessageBridge`
  fanning chat events to joined sessions only.
- Attachments on messages via the existing S3 lifecycle; queue + manual-claim assignment;
  RECORD_TRIGGERED flows fire on conversation/message creates for tenant automations
  (e.g. notify agents on new conversation).

Does NOT deliver: UI (slice 3), typing indicators (presence `chat:<id>` viewing only),
auto-routing strategies, server-push message bodies (invalidation-only rule holds).

## 2. Sample payloads

```
POST /api/chat/conversations {queueId?, subject?, contextRecordId?} → 201 conversation (status OPEN, origin from user_type)
POST /api/chat/conversations/{id}/messages {body, kind:"TEXT"}      → 201 message; hook publishes chat.message event
GET  /api/chat/conversations/{id}/messages?after=<messageId>&page[size]=50 → ordered page (participant-only)
WS   recv {"event":"chat.message","conversationId":"…","messageId":"…","senderId":"…","kind":"TEXT","timestamp":"…"}
```

## 3. Data & API contracts

Collection shapes (system collections; JSON:API names as listed):

- `chat-queues`: name, description, active.
- `chat-conversations`: queueId (lookup), subject, status `OPEN|ASSIGNED|CLOSED|ARCHIVED`
  (ARCHIVED transitions + read-only guard land in slice 7), origin `PORTAL|INTERNAL`,
  assignedTo (user), contextRecordId?, lastMessageAt, closedAt.
- `chat-messages`: conversationId (master-detail), senderId, senderType `INTERNAL|PORTAL`,
  kind `TEXT|SYSTEM|ATTACHMENT`, body (rich text), sentAt. Excluded from full-text/embedding
  indexes; excluded from generic realtime `data` push.
- `chat-participants`: conversationId (master-detail), userId, role `AGENT|PORTAL`,
  joinedAt, lastReadAt. Creating a PORTAL participant triggers the slice-1 participant-share
  grant on the conversation.

Authorization matrix: portal → scoped endpoints only (membership checked in-controller,
audited denials); staff → scoped endpoints + generic JSON:API per profile grants
(`view=queue|all` gated on a new `MANAGE_CHAT` system permission for supervisors; agents see
`mine` + their queues). Sender identity always resolved from forwarded identity headers —
body-supplied sender ignored (approvals slice-2 precedent).

Gateway membership check: `GET /internal/chat/conversations/{id}/members/{userId}` (worker
internal API), cached ~30s per (conversation,user); `chat.join` cap 20 per session
(sits under the existing 50-subscription budget separately tracked).

## 4. DB migrations

One migration (verify head; V167+ depending on slice-1 landing): four tables + RLS policies
+ indexes — `(tenant_id, conversation_id, sent_at)` on messages,
`(tenant_id, assigned_to, status)` + `(tenant_id, queue_id, status)` on conversations,
unique `(conversation_id, user_id)` on participants.

## 5. File-by-file code changes (sketch)

| Area | Files |
|------|-------|
| runtime-core | `SystemCollectionDefinitions` +4 collections |
| kelta-worker | `ChatController` (`/api/chat/**`), `ChatService` (membership, assignment, read receipts, JdbcTemplate repos), `ChatMessageHook`/`ChatConversationHook` (validate sender/participants, publish NATS, bump lastMessageAt), internal membership endpoint, `MANAGE_CHAT` permission seed, Cerbos policy generation entries, audit events |
| kelta-gateway | `SubscriptionManager` conversation index + caps, `RealtimeWebSocketHandler` `chat.join/leave` actions, `ChatMessageBridge`, `NatsSubscriptionConfig` +`kelta.chat.>` broadcast subscription, `RouteConfigService.registerStaticRoutes()` +`/api/chat/**` |
| runtime-events | `ChatMessagePayload`, `ChatConversationPayload` records |

## 6. Test plan

- Worker unit: membership authz (participant 200 / non-participant 403 / portal vs staff
  views), sender-identity hardening, hook publishes on create (InOrder with mocked
  publisher), assignment + close transitions.
- Gateway unit: join → membership check → routed; deny → error action + audit; bridge fans
  only to joined sessions; leave/disconnect cleanup.
- Harness (real Postgres/RLS): cross-tenant conversation invisible; portal share grant
  gives conversation read but not other conversations; message pagination under RLS.
- Playwright deferred to slice 3 (needs UI).

## 7. Docs to update (same PR)

CLAUDE.md Messaging table (+2 subject rows); `architecture.md` (chat endpoints authz, WS
extension); `conventions.md` (conversation-scoped socket rule); `playbooks.md`
("conversation-scoped realtime event" recipe); `concerns.md` (chat table growth + retention
TODO); `status.md` row update.

## 8. Risks & open questions

- **Message-table growth** in the shared public schema — indexes above; the durable
  mitigation is slice 7's archive-then-purge (live message rows purged only after the
  encounter is archived — never a bare retention delete). Tracked in `concerns.md`;
  partition only if real volume demands it.
- Membership cache (30s) means a just-removed participant may receive id-only events briefly
  — acceptable (no bodies on the wire); document it.
- `MANAGE_CHAT` naming vs a finer `chat-agent` concept: v1 keeps profile-based simplicity
  (agents = users in queue-linked groups or with the permission); revisit if per-queue
  membership needs first-class modeling.
