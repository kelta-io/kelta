---
title: Profiles, permissions and record sharing
description: System permissions, object and field permissions per profile, the built-in profiles, how Cerbos evaluates access, and record-level sharing.
section: security
order: 50
---

Every user has one **profile**. Everything the user may do comes from that profile, the groups they belong to and
the records shared with them, combined **most-permissive-wins** and evaluated by the Cerbos policy engine for every
route, record and field.

## System permissions

| Permission | Grants |
|---|---|
| `API_ACCESS` | Call the API at all (the app, CLI, MCP and PATs all need it) |
| `VIEW_SETUP` | Open the Setup area |
| `CUSTOMIZE_APPLICATION` | Collections, fields, picklists, layouts, pages, menus, packages, migrations |
| `MANAGE_USERS` | Users, password/MFA policy, login history, security audit |
| `MANAGE_GROUPS` | Group definitions (membership writes are guarded by `MANAGE_USERS`) |
| `MANAGE_DELEGATED_ADMINS` | Delegated-admin scopes |
| `MANAGE_SHARING` | Record shares on behalf of others |
| `MANAGE_WORKFLOWS` | Flows and scheduled jobs |
| `MANAGE_APPROVALS` | Approval processes |
| `MANAGE_REPORTS` | Author reports and dashboards |
| `VIEW_ANALYTICS` | Run reports and view dashboards |
| `MANAGE_LISTVIEWS` | Shared list views |
| `MANAGE_DATA` | Bulk operations: mass edit, CSV import, deduplicate, exports |
| `VIEW_ALL_DATA` / `MODIFY_ALL_DATA` | Read / write every record regardless of object permissions |
| `MANAGE_CONNECTED_APPS` | Connected apps, webhooks, scripts, modules, OIDC providers |
| `MANAGE_CREDENTIALS` / `VIEW_CREDENTIALS` | Credential vault, module signing keys |
| `MANAGE_API_SPECS` / `VIEW_API_SPECS` | Imported API specs |
| `MANAGE_EMAIL_TEMPLATES` | Email templates |
| `MANAGE_SANDBOXES` | Environments and promotions |
| `MANAGE_TENANTS` | Tenant settings, network access; bypasses the IP allowlist |

A few more (`MANAGE_CAMPAIGNS`, `MANAGE_CHAT`, `MANAGE_BILLING`, `VIEW_SUPPORT_MAILBOX`, `MANAGE_SUPPORT_MAILBOX`)
gate optional feature areas.

`/api/admin/**` endpoints require `API_ACCESS` plus the specific permission named above; the check happens in the
endpoint, not only in the console.

## Object permissions

Per profile and collection: `canCreate`, `canRead`, `canEdit`, `canDelete` (`profile-object-permissions`). The
collection wizard's Authorization step writes these at creation time. `VIEW_ALL_DATA` and `MODIFY_ALL_DATA`
override them.

## Field permissions

Per profile and field: `VISIBLE`, `READ_ONLY`, `HIDDEN` or `MASKED`. See
[Field-level security and data masking](/docs/security/field-security-and-masking/).

## Built-in profiles

| Profile | Grants |
|---|---|
| System Administrator | everything |
| Standard User | `API_ACCESS`, `MANAGE_LISTVIEWS`, `VIEW_CREDENTIALS`, `VIEW_API_SPECS`, `VIEW_ANALYTICS` |
| Read Only | `VIEW_ALL_DATA`, `VIEW_ANALYTICS` — note: no `API_ACCESS`, so it is a console/app profile; add `API_ACCESS` to use it with the CLI or a PAT |
| Marketing User | Standard User + `MANAGE_EMAIL_TEMPLATES`, `MANAGE_CAMPAIGNS` |
| Contract Manager | Standard User + `MANAGE_APPROVALS` |
| Solution Manager | `VIEW_SETUP`, `CUSTOMIZE_APPLICATION`, `MANAGE_REPORTS`, `MANAGE_WORKFLOWS`, `MANAGE_LISTVIEWS`, `API_ACCESS`, `VIEW_ANALYTICS` |
| Minimum Access | login only |
| Portal User | `API_ACCESS` only; data via record shares |

Built-in profiles are `isSystem: true` but editable. Create your own under Setup → Administration → Profiles.

## How access is evaluated

1. **Route** — the gateway asks Cerbos whether the caller's profile may perform the action implied by the HTTP
   method on the collection (or whether the admin endpoint's permission is held).
2. **Record** — the worker checks the specific record: object permissions, `VIEW_ALL_DATA`/`MODIFY_ALL_DATA`,
   record shares, and any ABAC rule (a CEL expression attached to the profile).
3. **Field** — the response is filtered field by field; writes to hidden/read-only fields are rejected.

Policies are generated from the tenant's metadata and pushed to Cerbos whenever profiles or permissions change; a
Cerbos outage fails closed.

Your own effective permissions: `GET /api/me/permissions`; identity and profile: `GET /api/me/identity`.

## Record sharing

A `record-shares` row grants `READ` or `EDIT` on one record to a user or a group (`sharedWithType`). Shares only
**widen**: a `READ` share adds read, an `EDIT` share adds read and edit; neither adds create or delete, and nothing
can narrow what the profile already allows. Portal users get all their data this way.

Share from the *Sharing* panel on a record in the admin Resource Browser (`/resources/<collection>/<id>`), or:

```http
POST /api/record-shares
{ "data": { "type": "record-shares", "attributes": {
  "collectionId": "<id>", "recordId": "<id>", "sharedWithType": "USER", "sharedWithId": "<user id>", "accessLevel": "EDIT" } } }
```
