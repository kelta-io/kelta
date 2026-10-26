---
title: Error responses
description: The JSON:API error envelope every 4xx/5xx uses, the stable error codes, field-level errors and what to branch on.
section: api
order: 40
---

## Envelope

```json
{
  "errors": [
    {
      "status": "400",
      "code": "VALIDATION_FAILED",
      "title": "Validation Error",
      "detail": "amount must be greater than zero",
      "source": { "pointer": "/data/attributes/amount" },
      "meta": { "requestId": "abc12345" }
    }
  ]
}
```

| Member | Meaning |
|---|---|
| `status` | HTTP status as a string |
| `code` | stable `UPPER_SNAKE_CASE` identifier — **branch on this** |
| `title` | short category |
| `detail` | human-readable description of this failure; never empty |
| `source.pointer` | JSON Pointer to the offending body field; `source.parameter` for query/path parameters |
| `meta.requestId` | correlate with audit logs, request logs and traces |

Several problems in one request produce several entries (one per field).

## Codes

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | a request or field failed validation and no more specific code applies (a hook error without its own code, a malformed tree or batch body); `meta` may add `field`, `constraint` |
| `nullable`, `type`, `pattern`, `minValue`, `maxValue`, `minLength`, `maxLength`, `enum`, `picklist`, `unique`, `immutable`, `systemMaintained` | 400 | a field constraint failed — the code is the constraint's name; one entry per field, `source.pointer` names it (`nullable` is a missing required value, `systemMaintained` a write to a field only the platform sets) |
| `reference` | 400 | a lookup value does not resolve; `meta.field`, `meta.targetCollection`, `meta.value` |
| `INVALID_PAYLOAD` | 400 | malformed JSON:API body |
| `INVALID_QUERY` | 400 | bad filter grammar or a client-caused SQL error (`meta.sqlState`) |
| `MISSING_PARAMETER` | 400 | a required query parameter is missing; `source.parameter` names it |
| `INVALID_PARAMETER` | 400 | a query or path parameter has the wrong type; `source.parameter` names it |
| `INVALID_ROW_LIMIT` | 400 | a list view `rowLimit` outside `{10, 25, 50, 100}` |
| `INVALID_SINCE` | 400 | `GET /api/{collection}/_changes` with a `since` that is not an ISO-8601 instant |
| `SEMANTIC_SEARCH_INVALID` | 400 | `POST /api/{collection}/semantic-search` without a `query`, or on a collection that is unknown or has no matching `VECTOR` field |
| `INVALID_TYPE`, `INVALID_CHANGESET` | 400 | `/api/metadata/impact` or `/api/metadata/deployment-plan` with an unknown metadata type or a malformed `changeSet` |
| `PARSE_ERROR`, `EMPTY_BATCH`, `UNSUPPORTED_COLLECTION` | 400 | `POST /api/operations`: unparseable `atomic:operations`, none given, or a system collection named |
| `FIELD_NOT_EDITABLE` | 400 | `PATCH /api/me/profile` with a field the profile does not let you change |
| `RESERVED_COLLECTION_NAME` | 400 | a collection name the platform API already uses (`/api/<name>`) |
| `INVALID_OWNER_SCOPE`, `INVALID_OWNER_FIELD` | 400 | a collection's `ownerScope` or `ownerField` is not valid |
| `OWNER_MISMATCH`, `OWNER_IMMUTABLE` | 400 | an owner-scoped record created for someone else, or its owner changed |
| `INVALID_PROPOSAL`, `PROPOSAL_ALREADY_APPLIED` | 400 | `POST /api/ai/proposals/{id}/apply` on an unknown or already-applied proposal |
| `UNAUTHORIZED` | 401 | missing, expired or revoked credential |
| `CALLER_UNRESOLVED` | 401 | the credential is valid but does not resolve to a user in this tenant |
| `FORBIDDEN` | 403 | permitted credential, denied action (`detail` names action and collection), or a source IP outside the tenant's allowlist |
| `MASKED_FIELD_PREDICATE` | 403 | filter/sort/group on a field masked for the caller (body deliberately uniform) |
| `READ_ONLY_COLLECTION` | 403 | a write to a read-only collection |
| `FLOW_NOT_LAUNCHABLE`, `FLOW_STATE_OVERRIDE_FORBIDDEN` | 403 | `POST /api/flows/{id}/execute` without `MANAGE_WORKFLOWS` on a flow members cannot launch, or with a `state` override |
| `AI_DISABLED` | 403 | AI features are not enabled for the tenant |
| `NOT_FOUND` | 404 | record or route not found (or not visible) |
| `COLLECTION_NOT_FOUND` | 404 | unknown collection |
| `TENANT_NOT_FOUND` | 404 | the tenant slug in the path does not resolve |
| `METHOD_NOT_ALLOWED` | 405 | the endpoint does not support the HTTP method |
| `UNIQUE_VIOLATION` | 409 | unique-constraint violation; one entry per field of the constraint |
| `STALE_WRITE` | 409 | stale `If-Match`: the record changed since you read it |
| `REFERENCED_RECORD` | 409 | delete of a record other records still reference |
| `FLOW_INACTIVE` | 409 | `POST /api/flows/{id}/execute` on an inactive flow (a `test: true` run by a `MANAGE_WORKFLOWS` holder is exempt) |
| `FLOW_HAS_ACTIVE_EXECUTIONS` | 409 | delete of a flow with running or waiting executions; `meta.activeExecutions` |
| `DEFAULT_VIEW_EXISTS` | 409 | a second default list view for the same collection and visibility; `meta.existingId` |
| `USER_ANONYMIZED` | 409 | a `users` update that moves an anonymized user to another status or gives them an email, or sets `status: ANONYMIZED` outside [anonymize](/docs/security/users-and-groups/#anonymizing-a-user); `meta.userId` |
| `ENVIRONMENT_NAME_CONFLICT`, `EMPTY_SANDBOX_REFRESH` | 409 | a sandbox environment name already in use, or a refresh with nothing to copy |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | the `Content-Type` is not one the endpoint accepts |
| `VALIDATION_RULE_FAILED` | 422 | a collection validation rule rejected the record; `source.pointer` names its error field |
| `TOO_MANY_OPERATIONS`, `OPERATION_FAILED` | 422 | `POST /api/operations` over the operation limit, or one operation failed (`source.pointer` is `/atomic:operations/<n>`) and the batch rolled back |
| `RATE_LIMIT_EXCEEDED` | 429 | tenant quota or per-user share used up; carries `Retry-After` and `X-RateLimit-*` headers — body below, details in [Rate limits](/docs/api/rate-limits/#handling-429) |
| `TOO_MANY_REQUESTS` | 429 | per-IP limit on the gateway or the portal sign-in endpoints; carries `Retry-After`; the body is `{"error": {"status", "code", "message", "path"}}`, not the envelope above |
| `AI_RATE_LIMIT_EXCEEDED`, `TOKEN_LIMIT_EXCEEDED` | 429 | the tenant's AI request rate (`Retry-After`) or monthly AI token limit is used up |
| `AI_PROVIDER_ERROR`, `TOKEN_BUDGET_EXCEEDED` | — | in an AI chat response's `error.code` (or a streamed `error` event): the model provider failed, or the tool loop hit its token budget |
| `INTERNAL_ERROR` | 500 | unexpected; `meta.requestId` is what to report |
| `STORAGE_ERROR`, `JSONAPI_PARSE_ERROR`, `APPLY_FAILED` | 500 | the database, the gateway's response parsing, or applying an AI proposal failed; `meta.requestId` is what to report |
| `TENANT_CONCURRENCY_LIMIT` | 503 | too many concurrent requests for the tenant; retry after `Retry-After` |

Hook-raised validation errors use `VALIDATION_FAILED` unless the hook defines a more specific code.

A rate-limited request (`Retry-After: 42`):

```json
{
  "errors": [
    {
      "status": "429",
      "code": "RATE_LIMIT_EXCEEDED",
      "title": "RATE_LIMIT_EXCEEDED",
      "detail": "Rate limit exceeded. Retry after 42 seconds.",
      "meta": { "requestId": "3f2b9c1e-…" }
    }
  ]
}
```

## CLI and SDK

The CLI prints the same envelope as one JSON line on stderr and maps status to exit codes
([Output contract](/docs/cli/scripting/)). For `RATE_LIMIT_EXCEEDED` it adds `error.hint` (and a `Hint:`
line in table output) telling you to wait the `Retry-After` seconds. `@kelta/sdk` throws typed errors — `ValidationError`,
`AuthenticationError`, `AuthorizationError`, `NotFoundError`, `ServerError`, `NetworkError` — that expose the
envelope ([SDK](/docs/sdk/sdk/)).
