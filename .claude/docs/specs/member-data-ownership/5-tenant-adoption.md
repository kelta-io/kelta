# Slice 5 — Tenant Adoption Guide (External Member Sites)

> Parent: [`README.md`](README.md). Depends on slices 2 and 3 being deployed. **No platform
> code** — a docs page plus per-tenant metadata. Tenant-specific steps live in the tenant's own
> repo, never here (CLAUDE.md Rule 0).

## 1. Goal & scope

**Delivers** a generic recipe (`docs/authoring/member-data.md`, published on kelta.io/docs) for
an external site whose members sign in with portal magic links: how to model member data so
the platform enforces ownership, and how a site moves off a backend-for-frontend (BFF) that
holds a service account.

## 2. UI samples

N/A — documentation.

## 3. Data & API contracts — the recipe

1. **Member-owned collections:** add a LOOKUP field to `users` (e.g. `member`), index it, set
   `ownerField=member`, `ownerScope=PORTAL` (or `ALL`). Grant the tenant's **Portal User** profile
   the CRUD it needs on that collection. Members now call
   `/<slug>/api/<collection>` with their own token; the platform stamps `member` on create and
   hides everyone else's rows.
2. **Member profile data:** platform fields (name, locale, timezone) via `/api/me/profile`.
   Anything tenant-specific (region, preferences, email preferences) goes in a member-owned
   collection (e.g. `member-profiles`, one row per member, `ownerScope=PORTAL`) — **not** in
   `users`, which tenants cannot extend.
3. **Push tokens:** register with `/api/devices` (`PushDeviceController`), not a JSON blob on the
   user.
4. **Retiring a BFF:** once 1–3 are in place, the site can drop its service-account data path
   and call the gateway with the member token directly; the service account's key should then be
   revoked.

## 4. DB migrations

None (tenant metadata via API/CLI/MCP).

## 5. File-by-file code changes

| File | Change |
|---|---|
| `docs/authoring/member-data.md` (new) | the recipe above, with a CLI example (`kelta` collection/field/profile commands) |
| `kelta-marketing` docs nav | link (the docs site reads `docs/authoring` in place) |

## 6. Test plan

Docs hygiene test (`kelta-marketing` docs build) passes; the CLI snippets are run once against a
sandbox tenant before merge.

## 7. Docs to update

`playbooks.md` cross-links the authoring page.

## 8. Risks & open questions

- Known adopters live outside this repo: an external member site today runs a BFF with a scoped
  service account and stores member settings as `users` fields that do not exist — its settings
  cannot work until it moves that data to a member-owned collection and `/api/me/profile` /
  `/api/devices` as above. Tracked in that site's repo.
