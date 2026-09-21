---
title: Glossary
description: The vocabulary used throughout the Kelta documentation.
section: concepts
order: 40
---

| Term | Meaning |
|---|---|
| **Action handler** | The executable unit behind a flow `Task` state — `CREATE_RECORD`, `CALL_API`, `EMAIL_ALERT`, … Provided by the platform or by an installed module. |
| **Approval process** | A configured sequence of approval steps a record is submitted through; the record is locked while a decision is pending. |
| **Collection** | A runtime-defined table. *User collections* hold business data; *system collections* hold platform metadata (`collections`, `fields`, `flows`, `profiles`, …). |
| **Connected app** | An OAuth 2.0 client registered in a tenant (client-credentials or authorization-code with PKCE). |
| **Custom domain** | A verified hostname that resolves to a tenant instead of the path slug. |
| **Environment / sandbox** | A child tenant cloned from a parent's metadata for development and testing. |
| **Field** | A column on a collection, with a `FieldType` and type-specific configuration. |
| **Field-level security (FLS)** | Per-profile visibility of a field: visible, read-only, hidden, or masked. |
| **Flow** | A Step-Functions-style state machine (`Task`, `Choice`, `Parallel`, `Map`, `Wait`, …) started by a trigger. |
| **Formula** | An expression evaluated against a record — used by formula fields, validation rules and flow trigger filters. |
| **Governor limit** | A per-tenant quota (API calls per day, storage, users, collections, …). |
| **Group** | A set of users (and groups) used for sharing and queue assignment. |
| **JSON:API** | The wire format of every collection endpoint — `data`, `attributes`, `relationships`, `included`, `meta`. |
| **JSONPath** | The `$.input.key` syntax used inside flow definitions to address state. |
| **Layout (page layout)** | Metadata describing a record page: sections, columns, field placements, header, related lists. |
| **List view** | A saved query — columns, filters, sort, renderer (table, kanban, calendar, gallery) — shared or personal. |
| **Lookup / master-detail** | Relationship field types. A lookup is optional (`ON DELETE SET NULL`); master-detail is required and cascades deletes. |
| **MCP** | Model Context Protocol. Kelta exposes admin and data toolsets over MCP for AI agents. |
| **Menu (app)** | A top-level app in the end-user shell; its items are the navigation tabs. |
| **Metadata package** | A JSON export of tenant configuration addressed by natural keys, applied with export / diff / apply. |
| **Module** | A signed JAR installed into a tenant at runtime that contributes action handlers, hooks, collections and UI. |
| **PAT** | Personal access token, `klt_…` — a bearer credential that acts as its owner. |
| **Picklist** | A controlled list of values; *global* picklists are shared across fields. |
| **Profile** | The role attached to a user: system, object and field permissions. |
| **Promotion** | The governed workflow that moves metadata from a sandbox to its parent tenant. |
| **Record** | A row in a collection, addressed by UUID. |
| **Record type** | A subtype of a collection with its own picklist values and defaults. |
| **RLS** | PostgreSQL row-level security — the mechanism that isolates tenants at the database. |
| **Slug** | The URL-safe identifier of a tenant. |
| **Tenant** | An isolated customer of the platform: its own users, metadata and data. |
| **UI page** | A custom page built from widgets in the page builder, served at `/app/p/<slug>`. |
| **Validation rule** | A formula that, when `TRUE`, rejects a record with a message. |
