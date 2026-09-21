---
title: External collections
description: Serve data that lives in another REST API or database as a Kelta collection — import an OpenAPI spec and materialize an operation, or configure a REST/JDBC data source directly.
section: data-model
order: 70
status: partial
---

An **external collection** has no physical table. Reads and writes are dispatched to a storage adapter that talks
to an external system, but the collection still appears in the schema, the API, list views, layouts and MCP tools
like any other. Two adapters ship: `external-rest` and `external-jdbc`.

## What works today

- Configure a collection's data source (physical, REST or JDBC) with a vault credential reference.
- Import an OpenAPI 3 document, browse its operations, and materialize a `GET` operation into a REST-backed
  collection with fields derived from the response schema.
- Query, get-by-id, create, update and delete through the adapter; pagination, sort and **equality** filters are
  pushed down to the backend. Optional per-collection response cache and rate limit for REST.

## What is not there yet

- Only `eq` filters push down; other operators are not applied by the adapter. Aggregates (dashboards, roll-ups)
  are unsupported on external collections.
- No schema DDL — the external system owns its schema. Field history, record versioning, full-text search and
  semantic search do not apply.
- Prebuilt connector packs (Salesforce, Slack, …) are planned on top of the module SPI.

## Option A — import an OpenAPI spec and materialize

1. **Import** the document (Setup → Integration → API specs, or `POST /api/api-specs/import`, or MCP
   `import_api_spec`). The spec is stored on `api-specs`; `POST /api/api-specs/validate` checks a document without
   storing it.
2. **Browse** operations: `GET /api/api-specs/{id}/operations`, `GET /api/api-specs/{specId}/operations/{opId}`,
   or search across specs with `GET /api/api-operations/search?q=`.
3. **Materialize** a `GET` operation:

   ```http
   POST /api/api-specs/{specId}/operations/{opId}/materialize
   { "collectionName": "external-products" }
   ```

   MCP: `materialize_api_collection` with `specId`, `operationId`, `collectionName`.

The materializer picks the operation's 2xx JSON schema, unwraps a bare array or an array-valued wrapper property
(recording it as `dataPath`), maps OpenAPI types to field types, infers the id attribute, and creates the
collection plus one field per property through the normal write path — so the change is broadcast to every pod
and the collection is immediately routable.

## Option B — configure the data source directly

On a user collection open the **Data source** tab (Setup → Data model → Collections → collection) and choose
*External REST* or *External JDBC*, or PATCH `adapterConfig` on the `collections` record:

```json
{
  "adapterType": "external-rest",
  "baseUrl": "https://api.example.com",
  "path": "/v1/products",
  "idAttribute": "productId",
  "credentialRef": "example-api",
  "cacheTtlSeconds": 30,
  "rateLimitPerSecond": 5
}
```

```json
{
  "adapterType": "external-jdbc",
  "jdbcUrl": "jdbc:postgresql://db.example.com:5432/erp",
  "table": "products",
  "idColumn": "product_id",
  "credentialRef": "erp-db"
}
```

`credentialRef` names a credential in the tenant vault (Setup → Integration → Credentials); the REST adapter
applies it by type (bearer, basic, API key, OAuth 2.0 client credentials), the JDBC adapter uses basic-auth
username/password. Secrets never live in `adapterConfig`. Every JDBC identifier is validated against
`^[A-Za-z_][A-Za-z0-9_]*$` and values are bound as parameters.

## Behaviour notes

- REST: `GET baseUrl+path` for list, `+/id` for one record; `POST`/`PUT`/`DELETE` for writes; a backend `404`
  reads as "no record".
- JDBC: pagination → `LIMIT/OFFSET`, sort → `ORDER BY`, `eq` → `WHERE col = ?`; one connection pool per
  `jdbcUrl` + user.
- Switching an existing physical collection to an external adapter does not migrate data.
