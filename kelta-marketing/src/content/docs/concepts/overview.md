---
title: How Kelta works
description: The mental model behind the platform — metadata-driven collections, tenants, the JSON:API, automation and the security model — in one page.
section: concepts
order: 10
---

## Everything is metadata

The unit of design in Kelta is not code but **metadata**: rows in *system collections* that describe your
application. A collection is a row in `collections`; its fields are rows in `fields`; a flow is a row in `flows`; a
profile is a row in `profiles`. The platform reads that metadata at runtime and does the work — creates the table,
serves the endpoint, renders the page, enforces the permission.

Because metadata is data, everything that can read and write data can build an application: the admin console, the
REST API, the CLI, MCP tools used by an AI agent, and metadata packages moved between environments. They all end up
writing the same rows.

## Tenants

A **tenant** is an isolated customer of the platform: its own users, metadata, data and configuration. Requests are
resolved to a tenant by URL slug (`https://api.example.com/acme/api/...`) or by a verified custom domain, and every
data path runs with that tenant bound. Isolation is enforced by PostgreSQL row-level security on every tenant
table, so a query that forgets to filter still cannot see another tenant's rows. Sandboxes are tenants too — child
tenants cloned from a parent's metadata. See [Tenants, sandboxes and limits](/docs/concepts/tenants-and-environments/).

## Collections, fields and records

A **collection** is a table. A **user collection** holds your business data (`invoices`, `customers`); a **system
collection** holds platform metadata (`collections`, `fields`, `flows`, `profiles`, `users`, `page-layouts`…).
Both kinds are served the same way: as JSON:API resources at `/api/<collection>`. That symmetry is why the CLI and
MCP server can administer the platform with the same code paths they use for records.

A **field** is a column with a type (`STRING`, `CURRENCY`, `LOOKUP`, `PICKLIST`, `FORMULA`…), options
(`required`, `unique`, `indexed`, `searchable`, `trackHistory`) and type-specific configuration. A **record** is a
row, addressed by UUID, always carrying the audit columns `createdAt`, `updatedAt`, `createdBy`, `updatedBy`.

Saving a collection or field runs **schema lifecycle hooks** that create or alter the physical table, then
broadcast the change over NATS so every running pod refreshes its registry and the gateway adds the route. A new
collection is queryable within seconds on every replica.

→ [Collections](/docs/data-model/collections/), [Field types](/docs/data-model/field-types/)

## The API

Every collection speaks [JSON:API](/docs/reference/jsonapi/): `GET /api/invoices?filter[status][eq]=SENT&sort=-due_date&page[size]=50&include=customer`.
Writes go through the same validation as the console — required fields, constraints, validation rules, unique
constraints, referential checks — and the same security. Responses carry a `meta.requestId` you can correlate with
the audit and request logs.

→ [REST API overview](/docs/api/overview/)

## The user experience is metadata too

- A **page layout** describes a record page: sections, columns, field placement, header, related lists.
- A **list view** is a saved query with columns, filters, sort and a renderer (table, kanban, calendar, gallery).
- A **record type** is a subtype of a collection with its own picklist values and defaults.
- A **UI page** is a custom page composed from widgets bound to data.
- A **menu** is an app in the end-user shell; its items are the navigation tabs.
- **Dashboards** and **reports** are analytic views over collections.

The admin console edits them; the end-user app renders them. → [Console & app](/docs/console/admin-console/)

## Automation

A **flow** is a state machine in the style of AWS Step Functions: `Task`, `Choice`, `Parallel`, `Map`, `Wait`,
`Pass`, `InvokeFlow`, `Succeed`, `Fail`. Tasks call **action handlers** — create or update records, call an HTTP
API, send email, submit for approval, run SQL, publish an event. Flows start from record changes, schedules, inbound
webhooks, NATS messages, or an explicit API call. **Approval processes** route a record through steps and lock it
while a decision is pending.

Runtime-installable **modules** (signed JARs) extend the handler catalogue, add before-save hooks, contribute
collections and UI components.

→ [Flows](/docs/automation/flows/), [Runtime modules](/docs/platform/modules/)

## Security in one paragraph

A **user** authenticates against Kelta's built-in OIDC provider (password + MFA, federated SSO, or a magic link
for portal users) or presents a **personal access token**. Each user has one **profile** that grants system
permissions (`API_ACCESS`, `CUSTOMIZE_APPLICATION`, `MANAGE_USERS`…) and object permissions per collection, and can
belong to **groups**. Effective access is most-permissive-wins across profile and groups, evaluated by Cerbos for
every route, record and field. Field-level security hides or masks columns per profile. Record shares widen access to
individual records. Everything is logged.

→ [Users, profiles, groups and tokens](/docs/concepts/identity-and-access/)

## Where the pieces run

The **gateway** authenticates, resolves the tenant, rate-limits, authorizes the route and forwards to the
**worker**, which owns collections, flows, search and migrations. The **auth** service is the OIDC provider. **AI**
and **MCP** are optional services that talk to the platform through the same API you do. PostgreSQL, Redis, NATS
JetStream and Cerbos back them. → [Architecture overview](/docs/deploy/architecture/)
