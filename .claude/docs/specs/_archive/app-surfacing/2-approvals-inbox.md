# Slice 2 — Approvals Inbox + Record Actions

> Child spec of [App Surfacing (Phase 1)](./README.md). Conforms to the parent's
> [Key architecture decisions](./README.md#key-architecture-decisions-verified-against-the-code),
> [Shared contracts → Approvals](./README.md#approvals-existing-endpoints--slice-2-consumes-hardens-identity),
> and [Security](./README.md#security) sections.
>
> **Security-typed change — never auto-merged** (actor-identity hardening on the approval
> write path). Source-verified 2026-07-08.

> **Post-implementation deltas (2026-07-08):** (1) the §8 JWT-`sub` check came back
> NEGATIVE for the auth-code flow (`KeltaUserDetails.getUsername()` returns the email, so
> Spring AS mints `sub = email`; only direct-login mints the UUID) — the fallback shipped as
> **`GET /api/me/identity`** on `UserPermissionsController` (gateway header → `UserIdResolver`
> → `{userId, email, profileId}`, fail-closed 403) and the FE `useMyIdentity` hook. (2)
> Timeline inline approve/reject was **deferred** — the inbox, record-header actions, and
> bell cover every flow; the timeline kept the read-only entries plus the server-side filter
> fix. (3) All four write endpoints had the body-identity fallback (not just submit); all
> hardened. (4) The parent's "gateway static route `/api/approvals/**` exists" claim (from a
> verification agent) was WRONG — only `approval-processes` was registered, so the
> ApprovalController action endpoints were unreachable through the gateway all along; the
> route is registered in this slice (`RouteConfigService.registerStaticRoutes`) with a
> regression assertion in `RouteConfigServiceTest`.

## 1. Goal & scope

**Delivers:**
- **`/app/approvals`** (`ApprovalsInboxPage`) — the end-user inbox: *Pending on me* /
  *My submissions* tabs over plain JSON:API filters on the existing
  `approval-step-instances` / `approval-instances` system collections (no new backend
  reads); approve/reject with a comment dialog; recall on my pending submissions.
- **Record-detail surfaces** (`ObjectDetailPage`): a *Submit for approval* header action
  (shown when an active process exists for the collection and no approval is pending), a
  *Pending approval / locked* badge, and inline approve/reject on the timeline's pending
  approval entry when the current step is assigned to the viewer.
- **Notification bell first real feed**: `TopNavBar`'s stub `notificationCount` becomes the
  live pending-on-me count; click navigates to `/app/approvals`. User-menu "Approvals" entry.
- **Backend actor-identity hardening (security)** on `ApprovalController` — see §3. This
  also fixes a latent correctness bug: the gateway stamps `X-User-Id` with the caller's
  **email**, while `approval_step_instance.assigned_to` / `approval_instance.submitted_by`
  store platform-user **UUIDs**, and the controller does no translation — so the
  header-derived path can never match an assignee, and the **spoofable body `userId` is the
  path that actually works today**.

**Does not deliver:** approval-visibility restriction (any tenant user can read all
approval instances/steps today — documented gap, see §8), queue/group approver inbox
semantics beyond direct `assignedTo`, reassignment UI, realtime badge updates (slice 4
layers that on), admin `ApprovalProcessesPage` changes, or delegated approvals.

## 2. UI samples

**Inbox** (`/app/approvals`):

```
Approvals
┌──────────────────────────────────────────────────────────────┐
│ [ Pending on me (3) ]  [ My submissions ]                    │
├──────────────────────────────────────────────────────────────┤
│ Expense report #EXP-0042        orders · submitted by Ana    │
│ waiting since 2h ago                     [Approve] [Reject]  │
├──────────────────────────────────────────────────────────────┤
│ Discount override — Acme Corp   opportunities · Ben          │
│ waiting since 1d ago                     [Approve] [Reject]  │
└──────────────────────────────────────────────────────────────┘
Row click → /app/o/<collection>/<recordId>. Empty state: "No approvals waiting on you."
My submissions tab: status pill (PENDING/APPROVED/REJECTED/RECALLED) + [Recall] on PENDING.
```

**Approve/reject dialog** (shared `ApprovalActionDialog`):

```
┌ Approve record ───────────────────────────┐
│ Comment (optional)                        │
│ ┌───────────────────────────────────────┐ │
│ │                                       │ │
│ └───────────────────────────────────────┘ │
│                     [Cancel]  [Approve]   │
└───────────────────────────────────────────┘
```

**Record header** (ObjectDetailPage): `[✎ Edit] [Submit for approval] [⋯]`; when pending:
a `Pending approval` badge (+ lock icon when `record_editability=LOCKED`) in the header
meta row, and the submit action hidden. Timeline pending entry gains `[Approve] [Reject]`
when `assignedTo == me`.

**Bell**: destructive-variant badge with pending count (existing `TopNavBar` markup, count
finally non-zero); click → `/app/approvals`.

## 3. Data & API contracts

### Backend hardening (the security half)

`ApprovalController` (today: `@RequestHeader("X-User-Id") required=false`, plus body
`submittedBy` fallback on submit and `userId` fields on action bodies; no
`CerbosPermissionResolver`, no `UserIdResolver`):

- New private `resolveActingUser(String xUserIdHeader)`: header **required** — missing/blank
  → 403 `ResponseStatusException` ("No identity"); value resolved via the existing
  **`UserIdResolver.resolve(identifier, tenantId)`** (the `PersonalAccessTokenController`
  pattern) to the `platform_user.id` UUID; unresolvable → 403. All four endpoints
  (submit/approve/reject/recall) pass the resolved UUID to `ApprovalService`.
- **Body-supplied identity is ignored**: `submittedBy` (submit) and `userId`
  (approve/reject/recall) no longer read. Fields stay on the request records for wire
  back-compat; Javadoc marks them inert. This kills the spoof path (any caller acting as any
  assignee by posting their UUID).
- Trust chain (verified): gateway `IdentityHeaderStripFilter` (order −400) strips
  client-supplied `X-User-Id`; `HeaderTransformationFilter` (order 50) re-stamps it from the
  validated principal. The worker may trust the header — it just must translate email→UUID.
- `SubmitForApprovalActionHandler` (flow path) unchanged — it passes an explicit userId at
  the service layer (system-trust tier, same contract as slice 1's scheduled delivery).
- `ApprovalService` signatures unchanged; its `findStepInstanceForApprover` /
  `submitted_by` comparison remains the authorization core, now fed a correct UUID.

### Frontend reads (all existing generic JSON:API — no new endpoints)

```
Pending on me:   GET /api/approval-step-instances?filter[assignedTo][eq]=<userUuid>
                     &filter[status][eq]=PENDING&include=approvalInstanceId&page[size]=50
My submissions:  GET /api/approval-instances?filter[submittedBy][eq]=<userUuid>
                     &include=collectionId&sort=-submittedAt&page[size]=50
Process exists:  GET /api/approval-processes?filter[collectionId][eq]=<collectionUuid>
                     &filter[active][eq]=true
Status/lock:     GET /api/approvals/status?collectionId=&recordId=
                 GET /api/approvals/lock-status?collectionId=&recordId=   → {locked, ...}
```

`approvalInstanceId`/`collectionId` are MASTER_DETAIL fields — `include=` resolves them
(verified: `DynamicCollectionRouter.resolveIncludes`), giving the inbox the record id,
collection (name for the `/app/o/...` link), submitter, and status without N+1 calls.
Actions ride the existing `POST /api/approvals/{instanceId}/approve|reject|recall`
`{comments?}` — **no identity in the body** (see hardening).

**Viewer identity (client):** `useAuth().user.id` = JWT `sub` claim. The filters compare it
to `assigned_to`/`submitted_by` (platform-user UUIDs). **Verification step for the
implementer:** confirm the internal-OIDC `sub` is the `platform_user.id` UUID; if it is the
email, add a tiny `GET /api/me` id resolution (or reuse the bootstrap payload) instead —
do not ship an inbox filtered on the wrong identifier. (§8 Open questions.)

### New FE modules

```ts
// src/hooks/useMyApprovals.ts
useMyApprovals(userId): { pending: PendingApprovalRow[], submissions: SubmissionRow[],
                          pendingCount: number, isLoading, refetch }
usePendingApprovalsCount(userId): { count: number }   // same query, count-only consumer

// src/components/ApprovalActionDialog/ApprovalActionDialog.tsx
{ open, mode: 'approve'|'reject', onConfirm(comment?: string), onCancel, isPending }

// src/pages/app/ApprovalsInboxPage/ApprovalsInboxPage.tsx  (route /app/approvals)
```

Query keys: `['my-approvals', userId]`; invalidated after every action mutation (and by
slice 4's realtime `approval-step-instances` subscription later).

## 4. DB migrations

None — no schema change. (Numbering reminder: head is V162 after slice 1; anything new
starts at V163 — not applicable here.)

## 5. File-by-file code changes

| File | Change |
|------|--------|
| `kelta-worker/.../controller/ApprovalController.java` | Inject `UserIdResolver`; add `resolveActingUser` (403 on missing/unresolvable); all four endpoints use it; stop reading body `submittedBy`/`userId` (fields kept, documented inert). |
| `kelta-worker/.../controller/ApprovalControllerTest.java` | Extend/create: header-missing → 403; body-`userId` spoof ignored (service called with header-resolved UUID, never the body value); email→UUID resolution delegated to mocked `UserIdResolver`. |
| `kelta-test-harness/.../scenarios/ApprovalActorScenarioTest.java` | **New** real-stack scenario: seed approver+bystander users; submit via admin; approver token approves 200; bystander token approving → failure (not acted); bystander posting body `userId=<approverUuid>` → still refused (spoof dead); DB-assert `acted_at`/`status` only via the legit path. |
| `kelta-ui/app/src/hooks/useMyApprovals.ts` | **New** — queries above, row mapping from includes. |
| `kelta-ui/app/src/pages/app/ApprovalsInboxPage/ApprovalsInboxPage.tsx` (+`index.ts`, tests) | **New** — tabs, rows, actions via `ApprovalActionDialog`, recall confirm, empty/loading/error states, row → record nav. |
| `kelta-ui/app/src/components/ApprovalActionDialog/` | **New** — AlertDialog + optional-comment textarea (extends the `ConfirmDialog` pattern; `ConfirmDialog` itself untouched). |
| `kelta-ui/app/src/App.tsx` | Lazy `EndUserApprovalsPage` + `<Route path="approvals">` under the EndUserShell outlet (per-module import, PageLoader suspense — existing pattern). |
| `kelta-ui/app/src/shells/EndUserShell/EndUserShell.tsx` | Replace hardcoded `notificationCount={0}` with `usePendingApprovalsCount(user.id)`; `onNotificationsOpen` → navigate `/app/approvals`. |
| `kelta-ui/app/src/components/UserMenu/` | "Approvals" item (app variant) → `/app/approvals`. |
| `kelta-ui/app/src/pages/app/ObjectDetailPage/ObjectDetailPage.tsx` | Header: *Submit for approval* action (gated on active process for the collection + `!hasActiveApproval` + `permissions.canEdit`); pending/lock badge in header meta (from `status`/`lock-status`); submit mutation → toast + timeline/query invalidation. |
| `kelta-ui/app/src/components/ActivityTimeline/ActivityTimeline.tsx` | Pending approval entry: when a PENDING step instance is assigned to the viewer, render Approve/Reject (opens `ApprovalActionDialog`); also switch the instance fetch from list-everything+client-filter to `filter[collectionId][eq]=&filter[recordId][eq]=` (matches every sibling fetch in the same file; drive-by perf fix). |
| `kelta-ui/app/src/i18n/translations/en.json` | `navigation.approvals`, `approvalsInbox.*` (title, tabs, empty states, waitingSince), `approvalsDialog.*` (commentLabel, approveTitle, rejectTitle, recallConfirm); reuse existing `approvals.approve/reject/recall`, `recordActions.submitForApproval`. Other locales fall back. |
| `e2e-tests/tests/end-user/approvals-inbox.spec.ts` | **New, post-deploy, `test.describe.skip`-gated** (route absent until deployed): submit → inbox shows → approve with comment → record shows Approved in timeline → badge count decrements. |

## 6. Test plan

- **Worker unit** (`ApprovalControllerTest`, Mockito): missing header → 403 before any
  service call; body-spoof case asserts the service receives the header-resolved UUID and
  never the body value; unresolvable identifier → 403.
- **Harness** (`ApprovalActorScenarioTest`, real Postgres + gateway header stamping): the
  three-actor matrix in §5 — this is the test that proves the email→UUID translation works
  through the real `HeaderTransformationFilter` stamp, which Mockito cannot.
- **Vitest**: `useMyApprovals` (query URLs, include mapping, count), `ApprovalActionDialog`
  (comment passed through, disabled-while-pending), `ApprovalsInboxPage` (tabs, empty
  states, action → invalidation), `EndUserShell` bell count render, `ObjectDetailPage`
  submit-button gating (process exists / already pending / no-edit-permission),
  `ActivityTimeline` approve-button visibility (assigned-to-me vs not).
- **Playwright**: post-deploy spec per §5 (skip-gated, `page-builder-v2.spec.ts` precedent).
- `/verify` green before PR.

## 7. Docs to update (same PR)

- `.claude/docs/status.md` — Approval processes row: end-user inbox + record actions +
  hardened actor identity ship; note the header-email vs UUID bug fixed.
- `.claude/docs/concerns.md` — **new entry**: approval instances/steps are tenant-visible
  to every user (no row-level read restriction; comments included); v1 accepts this
  (identical to the pre-existing `ActivityTimeline` exposure) — restriction is a deferred
  follow-up.
- `.claude/docs/architecture.md` — Authorizing-an-endpoint section: approvals actor
  identity = gateway-stamped `X-User-Id` → `UserIdResolver` → UUID; body identity inert.
- `.claude/docs/specs/_archive/app-surfacing/README.md` — slice 2 marked shipped; correct the parent's
  approvals contract snippet (bodies no longer carry identity).
- Memory `project_app_ux_roadmap.md` — tick slice 2.

## 8. Risks & open questions

- **JWT `sub` = platform-user UUID?** The inbox filters live on it (§3). Verify against
  kelta-auth's token issuance before building; fallback path defined. **Blocking check,
  cheap to do first.**
- **Approvals read visibility (documented gap):** any tenant user can list every approval
  instance/step + comments. Not widened by this slice (same generic routes the timeline
  already uses) but now more discoverable. Concerns entry + deferred fix (row-level
  restriction needs a policy design — submitter/assignee/admin).
- **Header identity is email, storage is UUID:** the hardening's `UserIdResolver` step is
  load-bearing, not cosmetic — without it approve/reject via header can never match. The
  harness scenario exists precisely to pin this through the real gateway stamp.
- **Queue/FIELD approver types:** step assignment may resolve to a queue or field-derived
  user at submit time; the inbox only understands direct `assigned_to` UUIDs. If a tenant
  uses queue approvers, those rows carry whatever UUID the resolution stored — v1 renders
  them as-is; queue-inbox semantics deferred.
- **Removed body-identity back-compat:** any external caller that relied on posting
  `userId`/`submittedBy` (the previously-working spoofable path) breaks by design. Release
  note it. **Auto-merge stays OFF** (security-typed).
- **Bell polling cost:** count query is one filtered page per staleTime window (5 min) per
  session; acceptable, replaced by realtime invalidation in slice 4.
