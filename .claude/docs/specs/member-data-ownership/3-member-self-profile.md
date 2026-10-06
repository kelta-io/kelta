# Slice 3 — Member Self-Profile

> Parent: [`README.md`](README.md). Depends on slice 1. **Security — never auto-merged.**

## 1. Goal & scope

**Delivers** `GET /api/me/profile` and `PATCH /api/me/profile`: any authenticated caller
(INTERNAL or PORTAL) reads and updates a fixed allow-list of fields on **their own** `users`
record — `firstName`, `lastName`, `locale`, `timezone` — without `MANAGE_USERS`.

**Does not** add tenant-defined fields to `users` (the definition stays fixed — tenant member
data belongs in an owner-scoped tenant collection, slice 2), touch `email`/`status`/`profileId`/
`userType`/`managerId`/`mfaEnabled`/`settings`, or change delegated administration.

## 2. UI samples

End-user app: the user menu's "Profile" item (currently inert in `UserMenu.tsx`) opens a small
form (first/last name, language, time zone) backed by this endpoint. External portals call the
same endpoint with the member token.

## 3. Data & API contracts

```http
GET /api/me/profile
200 {"data":{"type":"users","id":"<uuid>","attributes":{
      "email":"…","firstName":"…","lastName":"…","locale":"en","timezone":"Europe/Lisbon","userType":"PORTAL"}}}

PATCH /api/me/profile
{"data":{"type":"users","attributes":{"firstName":"Sam","timezone":"UTC"}}}
200 same shape as GET
400 {"errors":[{"status":"400","code":"FIELD_NOT_EDITABLE","source":{"pointer":"/data/attributes/email"}}]}
400 VALIDATION (locale not a supported tag, timezone not a valid IANA zone, names > 100 chars)
```

Gateway: `/api/me/**` is already routed and `API_ACCESS`-only (architecture.md) — no new static
route.

## 4. DB migrations

None.

## 5. File-by-file code changes

| File | Change |
|---|---|
| `kelta-worker/.../controller/MyProfileController.java` (new) | `/api/me/profile`; caller id from `CallerContext` (404 if none); allow-list + `rejectUnknownFields` (the `DelegatedUserAdminController` pattern); writes via `queryEngine.update(usersDefinition(), callerId, attrs)` inside `SelfProfileWriteContext.callAuthorized(callerId, fields, …)` |
| `kelta-worker/.../service/SelfProfileWriteContext.java` (new) | ScopedValue carrying `(userId, allowedFields)` |
| `kelta-worker/.../listener/IdentityCollectionGuardHook.java` | admit a `users` **update** when `SelfProfileWriteContext` is bound, the target id equals its `userId`, and the changed fields ⊆ its allow-list; everything else unchanged. Also add `user-permission-sets` to `GUARDED` (parent finding 5) |
| `kelta-ui/app/src/components/UserMenu/UserMenu.tsx` + new `ProfileDialog` | wire the "Profile" item |
| i18n ×6 | labels |

## 6. Test plan

- Worker unit: own record updates allowed fields; any other field → 400 `FIELD_NOT_EDITABLE`;
  `IdentityCollectionGuardHook` still blocks a `users` write *without* the context, and blocks a
  context-bound write to **another** id or a non-allow-listed field (defence in depth).
- Harness (real DB): a PORTAL user updates their timezone; cannot change `profileId` via
  `/api/me/profile`, `/api/users/{id}`, or `/api/operations`.
- Playwright: Profile dialog saves and reloads.

## 7. Docs to update

`architecture.md` (Authorizing a new endpoint → `/api/me/profile`; delegated-admin bullet notes
the separate self path), `status.md`, `concerns.md` (delegated-admin guardrails unchanged; new
self path), `playbooks.md` reference for external portals.

## 8. Risks & open questions

- **Open:** should portals be allowed to edit `locale`/`timezone` only (names managed by staff)?
  Proposed: all four — they are the member's own display data.
- The allow-list is code, intentionally not configurable — widening it is a reviewed change.
