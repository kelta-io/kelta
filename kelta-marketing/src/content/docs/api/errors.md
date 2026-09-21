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
| `VALIDATION_FAILED` | 400 | required/constraint/validation-rule/immutable-field failure; `meta` may add `field`, `constraint` |
| `INVALID_PAYLOAD` | 400 | malformed JSON:API body |
| `INVALID_QUERY` | 400 | bad filter grammar or a client-caused SQL error (`meta.sqlState`) |
| `INVALID_ROW_LIMIT` | 400 | a list view `rowLimit` outside `{10, 25, 50, 100}` |
| `reference` | 400 | a lookup value does not resolve; `meta.field`, `meta.targetCollection`, `meta.value` |
| `UNAUTHORIZED` | 401 | missing, expired or revoked credential |
| — | 403 | permitted credential, denied action; `detail` names action and collection |
| `MASKED_FIELD_PREDICATE` | 403 | filter/sort/group on a field masked for the caller (body deliberately uniform) |
| `NOT_FOUND` | 404 | record not found (or not visible) |
| `COLLECTION_NOT_FOUND` | 404 | unknown collection |
| — | 409 | unique-constraint violation, or stale `If-Match` |
| `RATE_LIMIT_EXCEEDED` | 429 | see [Rate limits](/docs/api/rate-limits/); carries `Retry-After` |
| — | 5xx | unexpected; `meta.requestId` is what to report |

Hook-raised validation errors use `VALIDATION_FAILED` unless the hook defines a more specific code.

## CLI and SDK

The CLI prints the same envelope as one JSON line on stderr and maps status to exit codes
([Output contract](/docs/cli/scripting/)). `@kelta/sdk` throws typed errors — `ValidationError`,
`AuthenticationError`, `AuthorizationError`, `NotFoundError`, `ServerError`, `NetworkError` — that expose the
envelope ([SDK](/docs/sdk/sdk/)).
