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

Each tenant has a generated OpenAPI 3 document covering CRUD on every collection it contains:

| | |
|---|---|
| `GET /api/docs/openapi.json` | the document (authenticated; reflects the live schema) |
| `GET /api/docs` | Swagger UI over it |
| MCP `describe_api` / `kelta://openapi.json` | the same document for agents |
| `kelta sdk types` | TypeScript types generated from it |

The document covers collection CRUD only — flows, approvals, batch operations and admin endpoints are described
on these pages, not in the spec.

## Batch operations

`POST /api/operations` applies up to 100 create/update/delete operations atomically (the deployment may raise the
cap to 500), with local ids (`lid`) so later operations can reference records created earlier in the same batch.
Shape and rules: [JSON:API conventions → atomic operations](/docs/reference/jsonapi/). CLI: `kelta records bulk`.

## Clients

- **CLI** — `kelta records list|get|create|update|delete <collection>` and the raw `kelta api <METHOD> <path>`;
  JSON output when piped. [CLI](/docs/cli/overview/)
- **TypeScript SDK** — `@kelta/sdk` `KeltaClient` / `ResourceClient<T>` with generated types. [SDK](/docs/sdk/sdk/)
- **MCP** — `query_collection`, `get_record`, `create_record`, … for agents. [MCP](/docs/mcp/overview/)
