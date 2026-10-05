---
title: Tenants, multi-tenancy and custom domains
description: How tenants are resolved and isolated, administering tenants, network access, and registering and verifying a custom domain.
section: platform
order: 10
---

## Resolution

A request reaches a tenant in one of three ways: a **verified custom domain**, the **URL slug** as the first
path segment (`https://api.example.com/acme/api/...`, `https://app.example.com/acme/app/...`), or a
`X-Tenant-Slug` / `X-Tenant-ID` header on a bare `/api` path. Slugs are 3–63 lowercase characters; the segments
`api actuator platform internal otel scim auth ws` are reserved. See [API authentication](/docs/api/authentication/).

## Isolation

- **Database** — every table holding tenant data carries `tenant_id` and is protected by PostgreSQL row-level
  security, `FORCE`d, with the application role unable to bypass it. The request's tenant is bound to the
  connection for the duration of each transaction; a query that forgets to filter still sees only its tenant.
- **Authorization** — Cerbos policies are generated and synchronised per tenant.
- **Encryption** — `ENCRYPTED` fields use a key derived per tenant from the platform master key.
- **Caches, events, rate limits** — keyed by tenant.
- **Sandboxes** — child tenants with the same guarantees ([Sandboxes and metadata promotion](/docs/platform/environments-and-promotion/)).

## Administering tenants

Setup → Platform → Tenants (`MANAGE_TENANTS`) lists tenants with edition, status and limits; records live in the
`tenants` system collection (`slug`, `name`, `edition`, `status`, `parentTenantId`, IP-allowlist fields, settings).
Creating a tenant runs the provisioning chain: schema, seeded profiles, an administrator, Cerbos policies, and
any configured integrations. Suspending a tenant (`status: SUSPENDED`) rejects its logins and API calls without
deleting anything.

### Bootstrap token

A new tenant's administrator has no usable password until someone claims the account through the invite, and a
platform admin's own token cannot act inside another tenant. To let automation configure a fresh tenant
straight away, a `MANAGE_TENANTS` holder can mint a short-lived personal access token **in the target tenant**:

```http
POST /api/tenants/{id}/bootstrap-token
{ "expiresIn": "1h", "userId": "<optional>" }
```

- The token belongs to the tenant's seeded System Administrator, or to `userId` (or `email`) when that names a
  user of the same tenant. A user of any other tenant is `404`.
- `expiresIn` is `30m` / `2h` shorthand or ISO-8601 (`PT2H`). Default 1 hour, maximum 24 hours; zero, negative,
  longer or unparseable values are `400`.
- It works only on that tenant's URLs. Used on any other tenant, including the platform tenant, it is `401`.
- The platform tenant itself cannot be bootstrapped (`403`).
- The response carries the plaintext `token` once, with `tokenPrefix`, `name`, `tenantId`, `userId` and
  `expiresAt`. The token is named `bootstrap-<actor>-<timestamp>`, so the target user sees it in their token list
  and can revoke it.
- Every attempt, refused or not, is audited as `TENANT_BOOTSTRAP_TOKEN_ISSUED` with the actor, the target tenant
  and the target user ([Audit logs](/docs/security/audit-log/)).

Console: Setup → Platform → Tenants → *Bootstrap token* shows the token once with a copy button. SDK:
`admin.tenants.bootstrapToken(id, { expiresIn, userId })`. CLI: `kelta tenants bootstrap-token <slug> --expires-in 1h`
prints only the token on stdout, so `TOKEN=$(kelta tenants bootstrap-token acme)` works.

The tenant dashboard (`/tenant-dashboard`) summarises usage against limits; the system-health page shows
component status.

## Network access

Per-tenant IP allowlisting is described under [Authentication → Tenant IP allowlist](/docs/security/authentication/#tenant-ip-allowlist).

## Custom domains

A tenant can be served from its own hostname instead of the slug path.

1. **Register** the domain (requires `MANAGE_TENANTS`):

   ```http
   POST /api/admin/domains
   { "domain": "portal.acme.example" }
   ```

   The response returns a DNS record to create: a `TXT` at `_kelta-verify.portal.acme.example` with a
   verification token. A domain already claimed by another tenant answers `409`.
2. **Create the TXT record** at your DNS provider.
3. **Verify**: `POST /api/admin/domains/{domainId}/verify`. Until the record resolves the call answers `412`;
   once verified the gateway maps the host to the tenant on every pod within seconds, and the auth server
   accepts tokens issued for that host.
4. **Remove** with `DELETE /api/admin/domains/{domainId}`; `GET /api/admin/domains` lists them.

There is no console page or dedicated CLI command yet — use `kelta api POST /api/admin/domains --data '{…}' --yes`.

**Operator responsibility.** Routing the hostname to the gateway and terminating TLS for it are done in your
ingress; the open-source platform records and verifies domains but does not provision certificates or DNS.
