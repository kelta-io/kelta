# PAT-Based Read-Only API Access

This is the generic recipe for exposing a **read-only, single-collection**
API to an external caller (a build pipeline, a partner integration, a
static site) using nothing but existing platform primitives: Personal
Access Tokens, profiles, and Cerbos. There is no separate "public data
API" — a scoped PAT against the ordinary collection endpoint *is* the
public read API.

## Auth header

Every request carries a `klt_`-prefixed bearer token:

```
Authorization: Bearer klt_<the token>
```

The gateway's `PatAuthenticationFilter` validates it (Redis-first, worker
fallback) and authenticates the request **as the platform user who owns
the token** — a PAT is not a separate identity, it's a bearer credential
for an existing user. There is no additional required header beyond
tenant identification:

- **Path-prefixed** (works from anywhere): `https://<host>/<tenant-slug>/api/<collection>`
- **Header-based** (service-to-service, no slug in the path):
  `X-Tenant-Slug: <tenant-slug>` (or `X-Tenant-ID: <uuid>`) alongside a
  bare `/api/<collection>` path
- A request against a tenant's **custom domain** needs neither — the host
  itself resolves the tenant.

## Scoping a PAT to read-only on one collection

A PAT has a `scopes` field (set at `POST /api/me/tokens` time, default
`["api"]`), but **that field is not currently enforced anywhere** — it is
carried through to the gateway principal (`pat_scopes` claim) and read by
nothing downstream. Treat it as reserved/informational, not as the access
boundary. **The real boundary is the token owner's `profiles` assignment**,
enforced by Cerbos exactly as it would be for that same user authenticating
with a password — a PAT inherits whatever the issuing user can do, no more
and no less.

To scope access to read-only on a single collection:

1. **Create a profile** (`profiles` system collection) with no elevated
   system permissions — e.g. `POST /api/profiles` with `{"name": "..."}`.
2. **Grant object permissions for exactly one collection** on
   `profile-object-permissions`: `profileId`, `collectionId`, `canRead:
   true`, and `canCreate`/`canEdit`/`canDelete` all `false` (they default
   to `false`, but set them explicitly for clarity):
   ```json
   {
     "type": "profile-object-permissions",
     "attributes": {
       "profileId": "<profile id>",
       "collectionId": "<target collection id>",
       "canCreate": false,
       "canRead": true,
       "canEdit": false,
       "canDelete": false
     }
   }
   ```
   Cerbos folds every profile's grants into one policy per action
   (`create`/`read`/`edit`/`delete`); a profile with only `canRead: true`
   for this collection is denied every write action on it, and has no
   grant at all on any other collection.
3. **Create (or reuse) a platform user for this purpose** and set its
   `profileId` to the profile from step 1 — e.g. a service account named
   for the integration, not a real person's login.
4. **Mint a PAT for that user**: that user calls `POST /api/me/tokens`
   themselves, or an admin holding `MANAGE_USERS` calls
   `POST /api/admin/users/{id}/tokens` on their behalf
   (`AdminPersonalAccessTokenController`) — useful when the token is for
   a service account nobody logs into interactively. Either way the
   response's `token` field is the plaintext `klt_...` value, shown
   exactly once.

From here, `GET` against the scoped collection succeeds; `POST`/`PATCH`/
`DELETE` are rejected with `403` by the same Cerbos check every other
write goes through — there is no separate code path for "read-only API"
requests versus normal UI requests. This exact fixture (a profile with
`canRead: true` and every other action `false`) is regression-tested in
`kelta-worker/src/test/java/io/kelta/worker/service/CerbosPolicyGeneratorTest.java`
(`CollectionPolicyTests.readOnlyGrantAllowsReadAndDeniesEveryWrite`), which
asserts the generated Cerbos policy grants `read` and denies
`create`/`edit`/`delete` for it.

That generated-policy shape isn't just asserted in isolation — it's proven
against a **real, running Cerbos PDP** (not a mock, not the harness's
dev-mode allow-all policies) by
`kelta-test-harness/src/test/java/io/kelta/testharness/scenarios/CerbosGeneratedPolicyIT.java`.
Its golden fixture's `editor-profile` carries the identical grant shape on
`col-b` — `canRead: true` and nothing else — and
`editorGetsExactlyItsObjectPermissions()` pushes the generator's actual
output into a throwaway Cerbos container and asserts the check result is
`.A..` (create/edit/delete denied, read allowed). That is the end-to-end
evidence for "GET succeeds, write is rejected with 403" in this repo: a
real policy engine evaluating the real generated policy for exactly this
grant shape, run in CI on every PR. What follows is a worked *recipe*, not
a transcript of an actual run against `rzware`'s live tenant — this repo
has no credentials for and makes no request against any live tenant.

Field-level restriction (hiding specific columns rather than the whole
collection) is a separate, additive mechanism —
`profile-field-permissions` — not covered here; see
`.claude/docs/architecture.md` → Field-Level Security.

## Rate limit (per tenant)

Every authenticated request, PAT or otherwise, counts against the
tenant's daily governor limit (`GET/PUT /api/governor-limits`,
`apiCallsPerDay`). This is a shared budget across every user and
integration in the tenant — see `.claude/docs/architecture.md` → Rate
limiting for the exact window/key behavior (per-tenant window, plus a
per-user sub-window that stops one credential from exhausting the whole
tenant's budget). Exceeding it returns `429` with a `Retry-After` header.

## Per-key request count

Independent of the rate limit, every authenticated PAT request
increments a **per-token** counter (`RedisRateLimiter.incrementPatUsageCounter`,
Redis key `pat-usage:<sha256(token)>`, gateway-side, fire-and-forget so it
never adds latency or fails the request). The token owner reads their own
usage back as `requestCount` on `GET /api/me/tokens`:

```json
{
  "data": [
    {
      "id": "...",
      "name": "couchpicks-readonly",
      "tokenPrefix": "klt_AbCd",
      "scopes": ["api"],
      "expiresAt": "...",
      "lastUsedAt": "...",
      "createdAt": "...",
      "requestCount": 128
    }
  ]
}
```

This is a lifetime counter for the token (it lives as long as the token
does), distinct from the tenant's daily governor count — it answers "how
much has this specific key been used", not "is the tenant near its
quota".

## Worked example: a read-only feed over a tenant's dataset

This is the pattern the `rzware` tenant intends to use to expose its
CouchPicks dataset (a collection kept fresh by a scheduled refresh job)
to a consumer outside the tenant's own admin/end-user UI — e.g. a
build-time fetch from a static site. None of this is platform code; it's
ordinary tenant metadata, created the same way any tenant would create it
(API, CLI, or MCP).

**This is a recipe to follow, not a transcript of a run that already
happened.** The commands below use placeholder ids and a placeholder host
because this repo (and the agent authoring this doc) has no credentials
for, and makes no request against, `rzware`'s live tenant — issuing the
real PAT and running these commands against production is an operational
step for whoever owns that tenant, not a platform code change. The
mechanism itself — read-allow, write-deny for exactly this profile shape,
and the counter incrementing on every authenticated request — is what's
actually verified end-to-end in this repo, by the automated tests cited
throughout this page (real Cerbos PDP evaluation in
`CerbosGeneratedPolicyIT`, PAT auth + counter increment in
`PatAuthenticationFilterTest`/`RedisRateLimiterTest`, read-back in
`PersonalAccessTokenControllerTest`) rather than by a manual curl session:

```bash
# 1. A profile with no system permissions and no other object grants
kelta api POST /api/profiles --data '{"data":{"type":"profiles","attributes":{"name":"couchpicks-readonly"}}}' --yes

# 2. Read-only grant on exactly one collection (replace with the real ids)
kelta api POST /api/profile-object-permissions --data '{
  "data": { "type": "profile-object-permissions", "attributes": {
    "profileId": "<couchpicks-readonly profile id>",
    "collectionId": "<couchpicks collection id>",
    "canCreate": false, "canRead": true, "canEdit": false, "canDelete": false
  } }
}' --yes

# 3. A service user carrying that profile (or reuse an existing one),
#    then mint its PAT as an admin holding MANAGE_USERS
kelta api POST /api/admin/users/<service-user-id>/tokens --data '{
  "name": "couchpicks-feed", "expiresInDays": 365
}' --yes
# response's "token" field is the plaintext klt_... value, shown exactly once

# 4. Read access — expected to succeed, and to increment this PAT's usage counter
curl -H "Authorization: Bearer <the minted PAT>" \
     https://rzware.<host>/api/couchpicks-titles

# 5. Write access with the SAME PAT — expected to be rejected
curl -X POST -H "Authorization: Bearer <the minted PAT>" \
     -H "Content-Type: application/json" \
     -d '{"data":{"type":"couchpicks-titles","attributes":{"title":"x"}}}' \
     https://rzware.<host>/api/couchpicks-titles
# expected: 403 (Cerbos denies "create" — this profile has canCreate: false),
# per the same read-allow/write-deny shape CerbosGeneratedPolicyIT asserts
# against a real PDP for editor-profile/col-b
```

To confirm the usage counter moved, read it back as the token owner:
authenticate as step 3's service user and call `GET /api/me/tokens`,
checking `requestCount` on the `couchpicks-feed` entry. There is no
admin-facing listing of another user's tokens — usage is only readable
by the token's own owner, the same as the token list itself.

The redistribution-rights review per data source, the pricing page, and
outbound emails to prospective API consumers are out of scope here (a
separate, later effort) — this page covers only the read-only API
mechanism itself.
