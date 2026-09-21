---
title: What is Kelta
description: A metadata-driven, multi-tenant platform for building enterprise applications at runtime — what ships, how the pieces fit, and where to go next.
section: getting-started
order: 10
---

Kelta is an open-source platform for building business applications without redeploying code. Collections
(tables), fields, relationships, validation rules, security and automation are all **metadata**: you define them at
runtime — in the admin console, over the REST API, from the CLI, or through an AI agent — and the platform
immediately serves them as a JSON:API, renders them in a generated end-user app, and enforces them everywhere.

## What you get out of the box

- **A data model you change live.** Create a collection and it gets a physical PostgreSQL table, a REST endpoint
  and a place in the app within seconds. Twenty-plus field types, lookups and master-detail relationships,
  picklists, validation rules, unique constraints, formula and roll-up fields. → [Data model](/docs/data-model/collections/)
- **A JSON:API for every collection.** Filtering, sorting, pagination, includes, sparse fieldsets, batch operations
  and a per-tenant OpenAPI document. → [REST API](/docs/api/overview/)
- **Automation.** Flows are Step-Functions-style state machines with record, schedule, webhook and message
  triggers; approval processes lock records while a decision is pending. → [Automation](/docs/automation/flows/)
- **Security that is enforced, not decorative.** Built-in OIDC provider with MFA, SSO federation (OIDC and SAML),
  SCIM provisioning, profiles and groups resolved through Cerbos, field-level security and data masking, personal
  access tokens, IP allowlists, audit logs. → [Security & identity](/docs/security/authentication/)
- **A console and an app.** The admin console is where builders work; the end-user app renders your layouts, list
  views, custom pages, dashboards and reports for everyone else. → [Console & app](/docs/console/admin-console/)
- **Agent-first tooling.** A hosted MCP server with admin and data toolsets, a self-updating CLI whose output is
  machine-readable by default, and authoring docs that the CLI and MCP server ship verbatim. → [MCP](/docs/mcp/overview/),
  [CLI](/docs/cli/overview/)
- **Operations.** Multi-tenancy with PostgreSQL row-level security, sandboxes and metadata promotion, governor
  limits, tenant-level audit and monitoring, runtime-installable modules. → [Platform operations](/docs/platform/tenants/)

## How it works, in four steps

1. **Describe the data.** A collection is a record in the `collections` system collection; its fields are records
   in `fields`. Saving them triggers schema lifecycle hooks that create or alter the physical table and broadcast the
   change to every running pod.
2. **Use the API.** Every collection is served at `/api/<collection>` as JSON:API. The gateway authenticates the
   caller, resolves the tenant, applies rate limits and asks Cerbos whether the action is allowed; the worker
   executes the query under the tenant's row-level-security context.
3. **Automate.** Record changes, schedules, inbound webhooks and NATS messages start flows; flows call action
   handlers (create/update records, call APIs, send email, submit for approval…).
4. **Ship.** Page layouts, list views, menus, custom pages and dashboards describe the user experience. The end-user
   app renders them; the CLI, MCP tools and metadata packages move them between sandboxes and production.

## Services at a glance

| Service | Role |
|---|---|
| `kelta-gateway` | API entry point: JWT/PAT authentication, tenant resolution, rate limiting, Cerbos authorization, dynamic routing, realtime WebSocket |
| `kelta-worker` | Collections, JSON:API, flows, search, schema lifecycle, database migrations |
| `kelta-auth` | Built-in OIDC provider, SSO federation, MFA, portal identity |
| `kelta-ai` | AI assistant, proposals and governed agents (Anthropic Claude) |
| `kelta-mcp` | Model Context Protocol server exposing admin and data toolsets |
| `kelta-ui` | Admin console and end-user app (React) |

Backing services: PostgreSQL (with `pgvector`), Redis, NATS JetStream and the Cerbos policy decision point.
See the [architecture overview](/docs/deploy/architecture/) for the request path and event flow.

## Open source

Kelta is licensed under the GNU Affero General Public License v3.0 and is designed to be self-hosted. See
[Licensing](/docs/deploy/licensing/).

## Where next

- Run it locally: [Quickstart with Docker Compose](/docs/getting-started/quickstart/)
- Build something: [Tutorial: your first app](/docs/getting-started/first-app/)
- Learn the vocabulary: [How Kelta works](/docs/concepts/overview/) and the [glossary](/docs/concepts/glossary/)
- Automate it: [Install the CLI](/docs/getting-started/install-cli/) or [connect an MCP client](/docs/mcp/connecting/)
