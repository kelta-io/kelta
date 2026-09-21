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
