---
title: REST API overview
description: Base URLs, the JSON:API contract every collection follows, the resource families, the schema endpoint, and the per-tenant OpenAPI document and Swagger UI.
section: api
order: 10
---

Every collection — yours and the platform's own metadata — is served as a [JSON:API](https://jsonapi.org)
resource at `/api/<collection>`. Learn the conventions once and they apply to invoices, users, flows and page
layouts alike.

## Base URL

| Form | Example |
|---|---|
| Path-prefixed tenant slug | `https://api.example.com/acme/api/invoices` |
| Custom domain (tenant resolved from the host) | `https://acme.example.com/api/invoices` |
| Header-resolved (service to service) | `https://api.example.com/api/invoices` with `X-Tenant-Slug: acme` |

The `api` segment is fixed; the segments `api actuator platform internal otel scim auth ws` can never be tenant
slugs. Authentication is a bearer JWT or personal access token — see [API authentication](/docs/api/authentication/).

## The JSON:API contract

```http
GET /api/invoices?filter[status][eq]=SENT&sort=-due_date&page[size]=50&include=customer&fields[invoices]=number,amount
```

```json
{
  "data": [ { "type": "invoices", "id": "…", "attributes": { "number": "INV-1", "amount": 120.5, "customer": "9d2c…" },
              "relationships": { "customer": { "data": { "type": "customers", "id": "9d2c…" } } } } ],
  "included": [ { "type": "customers", "id": "9d2c…", "attributes": { "name": "Acme" } } ],
  "meta": { "totalCount": 1, "currentPage": 1, "pageSize": 50, "totalPages": 1 },
  "links": { "self": "…", "prev": null, "next": null }
}
```

- Reads: `GET /api/<collection>` (list), `GET /api/<collection>/{id}`.
- Writes: `POST`, `PATCH /{id}`, `DELETE /{id}` with a `{ "data": { "type", "attributes" } }` body and
  `Content-Type: application/vnd.api+json` (plain `application/json` is accepted).
- Relationship ids appear in both `attributes` and `relationships`; writes accept either.
- Every response carries `meta.requestId`.

The full wire conventions — attribute vs relationship placement, sparse fieldsets, filter grammar, the
`pageSizeClamped` flag, the atomic-operations extension — are in [JSON:API conventions](/docs/reference/jsonapi/),
the same document the CLI (`kelta docs jsonapi`) and MCP server (`kelta://docs/jsonapi`) ship. Querying details:
[Pagination, filtering, sorting, includes](/docs/api/querying/).

## Resource families

| Family | Examples |
|---|---|
| User collections | `/api/invoices`, `/api/customers` — whatever you defined |
| System collections | `/api/collections`, `/api/fields`, `/api/flows`, `/api/profiles`, `/api/users`, `/api/page-layouts`, `/api/list-views`, `/api/ui-pages`, `/api/ui-menus` — the platform's metadata, same contract |
| Sub-resources | `/api/<collection>/_changes?since=`, `/api/<collection>/semantic-search`, `/api/<collection>/{id}` |
| Action endpoints | `/api/flows/{id}/execute`, `/api/approvals/submit`, `/api/attachments/upload-url`, `/api/operations` (batch), `/api/_search` |
| Self | `/api/me/identity`, `/api/me/permissions`, `/api/me/tokens` |
| Administration | `/api/admin/**` — users, MFA policy, domains, SCIM, delegated admin, constraints; each gated by a specific system permission |

## Describing a collection

`GET /api/collections/{name}/schema` returns the fields, types, defaults, enum values and relationship targets
of any collection, system collections included. See [Collections](/docs/data-model/collections/#describing-a-collection).

## OpenAPI and Swagger UI

Each tenant has a generated OpenAPI 3 document covering every collection it contains:

| | |
|---|---|
| `GET /api/docs/openapi.json` | the document (authenticated; reflects the live schema) |
| `GET /api/docs` | Swagger UI over it |
| MCP `describe_api` / `kelta://openapi.json` | the same document for agents |
| `kelta sdk types` | TypeScript types generated from it |

What the document describes:

- **Server URL** — `servers[0].url` ends in your tenant slug (`https://<host>/<tenant-slug>`), and paths are
  written `/api/...`, so a client generated from it calls `https://<host>/<tenant-slug>/api/<collection>`.
- **Authentication** — two HTTP bearer schemes, either of which authorizes a call: `bearerAuth` (a sign-in JWT)
  and `patAuth` (a personal access token, the `klt_`-prefixed value, sent as `Authorization: Bearer klt_...`).
- **Operations** — list, get, create, update and delete per collection (read-only collections list and get
  only), `POST /api/operations` for [batch operations](#batch-operations), and, for each collection that can be
  listed, `GET /api/<collection>/aggregate` and `GET /api/<collection>/latest`
  ([aggregates and the latest value](/docs/api/querying/#aggregates-and-the-latest-value)).

The document validates with standard OpenAPI tooling (swagger-parser), so code generators accept it as is.
Flows, approvals, bulk jobs and admin endpoints are described on these pages, not in the spec.

## Batch operations

`POST /api/operations` applies up to 100 create/update/delete operations atomically (the deployment may raise the
cap to 500), with local ids (`lid`) so later operations can reference records created earlier in the same batch.
Shape and rules: [JSON:API conventions → atomic operations](/docs/reference/jsonapi/). CLI: `kelta records bulk`.

## Bulk jobs, imports and duplicates

For more records than a batch holds, a **bulk job** runs in the background (`MANAGE_DATA`):

| Endpoint | Does |
|---|---|
| `POST /api/bulk-jobs` | Queue `{ collectionId, operation, records, externalIdField?, batchSize? }`; `operation` is `INSERT`, `UPDATE`, `UPSERT` (needs `externalIdField`) or `DELETE` |
| `POST /api/bulk-jobs/upload` | The same from a multipart CSV or JSON `file`, with the other values as form fields |
| `GET /api/bulk-jobs`, `GET /api/bulk-jobs/{id}` | List jobs; one job's status and progress |
| `GET /api/bulk-jobs/{id}/results`, `/results/download` | Per-record results, as JSON:API or as a CSV file |
| `POST /api/bulk-jobs/{id}/abort` | Stop a queued or running job |

`POST /api/collections/{name}/import/csv` (multipart `file`) creates one record per CSV row, header names matching
field names. It needs `create` on the collection, skips columns the caller may not write, and reports failed rows
individually while committing the rest.

Duplicates (`MANAGE_DATA`): `POST /api/collections/{name}/duplicates` with `{ "matchFields": [...], "limit"? }`
returns groups of records sharing those values; `POST /api/collections/{name}/merge` with
`{ "masterId", "duplicateIds": [...], "fieldOverrides"? }` keeps the master, re-points references to the duplicates
at it and deletes them, in one transaction.

## Clients

- **CLI** — `kelta records list|get|create|update|delete <collection>` and the raw `kelta api <METHOD> <path>`;
  JSON output when piped. [CLI](/docs/cli/overview/)
- **TypeScript SDK** — `@kelta/sdk` `KeltaClient` / `ResourceClient<T>` with generated types. [SDK](/docs/sdk/sdk/)
- **MCP** — `query_collection`, `get_record`, `create_record`, … for agents. [MCP](/docs/mcp/overview/)
