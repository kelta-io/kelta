---
title: Tenants, sandboxes and limits
description: What a tenant is, how sandboxes relate to it, what governor limits bound, and how metadata moves between environments.
section: concepts
order: 20
---

This page is the conceptual map. The operational detail lives under
[Platform operations](/docs/platform/tenants/).

## Tenant identity

Every tenant has a **slug** — 3–63 lowercase characters — used as the first path segment of its URLs
(`https://api.example.com/<slug>/api/...`, `https://app.example.com/<slug>/app/...`). A tenant can also be reached
through a **custom domain** once ownership is verified with a DNS TXT record. A handful of first segments are
reserved for the platform (`api`, `actuator`, `platform`, `internal`, `otel`, `scim`, `auth`, `ws`) and can never
be slugs.

Tenants carry an **edition** (`FREE`, `PROFESSIONAL`, `ENTERPRISE`, `UNLIMITED`) that seeds their governor limits,
and a **status** (`PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DECOMMISSIONED`).

## Isolation

Tenant data lives in PostgreSQL tables protected by row-level security keyed on the tenant bound to the current
transaction. Caches, NATS subjects, Cerbos policies and encryption keys are all tenant-scoped. There is no
"platform-wide" query path for tenant data; cross-tenant work loops over tenants one at a time.

## Governor limits

Each tenant has a quota map. The keys are:

| Key | Bounds |
|---|---|
| `apiCallsPerDay` | authenticated API requests per UTC day (gateway answers `429` past it) |
| `storageGb` | attachment and record storage |
| `maxUsers`, `maxPortalUsers` | internal / portal user rows |
| `maxCollections`, `maxFieldsPerCollection` | schema size |
| `maxWorkflows`, `maxReports` | automation and analytics objects |
| `aiEnabled`, `aiTokensPerMonth` | AI assistant availability and budget |
| `campaignEmailsPerDay` | outbound campaign email |
| `telehealthEnabled`, `videoMinutesPerMonth` | optional telehealth features |
| `archiveAfterDays`, `retentionYears`, `purgeLiveAfterDays` | data lifecycle |

An edition sets defaults; an operator can override any key per tenant. Usage is visible in **Setup → Governor
limits** and at `GET /api/governor-limits`. → [Governor limits and editions](/docs/platform/governor-limits/)

## Sandboxes are real tenants

A **sandbox** is a child tenant whose slug is `<parent>--<name>`. Creating one clones the parent's metadata (not
its data) into a fresh tenant with its own users and a one-time administrator credential. You log in to a sandbox
exactly as you would to any tenant; the CLI treats it as a separate profile.

## Metadata packages and promotion

A **metadata package** is a JSON export of a tenant's configuration — collections, fields, picklists, validation
rules, layouts, flows, pages, menus — addressed by natural keys so it can be applied to another tenant. Exporting,
diffing and applying packages is how you move an app from a sandbox to production, or between clusters.

**Promotion** wraps that in a governed workflow: create a promotion from a sandbox to its parent, preview the diff,
have someone other than the author approve it, execute (the target is snapshotted first), and roll back if needed.
→ [Sandboxes and metadata promotion](/docs/platform/environments-and-promotion/)
