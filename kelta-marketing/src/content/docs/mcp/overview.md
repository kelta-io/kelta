---
title: MCP server overview
description: The hosted Model Context Protocol server — endpoints per tenant, PAT-only authentication, admin vs user toolsets, timeouts, errors, and self-hosting.
section: mcp
order: 10
---

Kelta exposes its admin and data surfaces to AI agents over the [Model Context Protocol](https://modelcontextprotocol.io).
The same tools power the in-console assistant; an external agent gets exactly what the authenticated user is
permitted to do, nothing more.

## Endpoints

Two per tenant, on the **API origin** (there is no separate MCP hostname):

| | |
|---|---|
| `https://api.example.com/<tenant>/mcp/admin` | the **control plane**: collections, fields, picklists, validation rules, layouts, list views, pages, menus, dashboards, flows, API specs |
| `https://api.example.com/<tenant>/mcp/user` | the **data plane**: records, search, flows execution, approvals |

Transport is streamable HTTP (`GET`/`POST`/`DELETE`), stateless: every request carries its own credential and
tenant, so any replica can answer.

## Authentication

```
Authorization: Bearer klt_...
```

**Personal access tokens only.** JWTs are rejected with `401` and `WWW-Authenticate: Bearer realm="kelta-mcp"`.
The token acts as its owner: an admin-toolset call from a user without `CUSTOMIZE_APPLICATION` fails at the
platform with the same `403` the console would give. Create a least-privilege service user for agents — see
[Personal access tokens](/docs/security/personal-access-tokens/).

## Toolsets

| Endpoint | Tools |
|---|---|
| admin | 39 configuration tools plus `list_collections`, `get_collection_schema`, `ping` |
| user | 15 data tools plus `ping` |

The full catalogue with purposes is on [Tool catalog](/docs/mcp/tools/); resources and the idempotent `apply_*`
family on [Resources and apply semantics](/docs/mcp/resources-and-apply/). Each tool carries MCP annotations
(`readOnlyHint`, `destructiveHint`, `idempotentHint`) so a client can ask before a destructive call.

## Errors

A failed tool call sets `isError` and returns both a readable message and `structuredContent = { status, errors[] }`
where `errors` is the platform's JSON:API error array — branch on `errors[0].code` and `source.pointer` as you
would with the [REST API](/docs/api/errors/). Tokens are redacted from every error body.

## Limits

Each tool call has a server-side timeout (`MCP_TOOL_TIMEOUT_MS`, 60 s default). Calls count against the
tenant's API quota like any other request.

## Self-hosting

Deploy the `kelta-mcp` image with `GATEWAY_URL` pointing at the gateway service and route
`^/[a-z][a-z0-9-]+/mcp/(user|admin)` on the API host to it ([Kubernetes deployment](/docs/deploy/kubernetes/#ingress)).
The Docker Compose stack does not include it; use the CLI's [local bridge](/docs/cli/mcp-bridge/) instead.
