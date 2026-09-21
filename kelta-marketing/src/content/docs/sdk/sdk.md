---
title: "@kelta/sdk"
description: The TypeScript client — KeltaClient, ResourceClient, QueryBuilder, AdminClient, typed errors, Zod schemas and generated collection types.
section: sdk
order: 10
---

`@kelta/sdk` is the typed TypeScript client the console, CLI and components are built on. It lives in the
repository's `kelta-web` workspace and is currently **pre-release** (built from source; not yet published to a
registry).

```bash
git clone https://github.com/kelta-io/kelta.git && cd kelta/kelta-web
npm install && npm run build          # builds formula, sdk, then components and plugin-sdk
```

## Client

```ts
import { KeltaClient } from '@kelta/sdk';

const client = new KeltaClient({
  baseUrl: 'https://api.example.com',
  tenantSlug: 'acme',                                     // becomes the /acme path prefix
  tokenProvider: async () => process.env.KELTA_TOKEN!,   // a PAT, or a JWT from your OIDC session
  retry: { maxRetries: 3 },
});

const invoices = client.resource<Invoice>('invoices');
const page = await invoices.list({
  filters: [{ field: 'status', operator: 'eq', value: 'open' }],
  sort: [{ field: 'dueDate', direction: 'desc' }],
  size: 50,
  include: ['customer'],
});
const one = await invoices.get(id, { include: ['customer'] });
await invoices.create({ number: 'INV-9', amount: 120 });
await invoices.patch(id, { status: 'PAID' });
```

| Export | Role |
|---|---|
| `KeltaClient` (`KeltaClientConfig`: `baseUrl`, `tenantSlug`, `tokenProvider`, `cache`, `retry`) | connection, token management, discovery, retry and cache |
| `ResourceClient<T>` (`list`, `get`, `create`, `update`, `patch`, `delete`; `ListOptions` = `page`, `size`, `sort`, `filters`, `fields`, `include`; `ListResponse<T>` = `data[]` + `pagination`) | CRUD on one collection |
| `QueryBuilder<T>` | fluent filter / sort / pagination / sparse fields |
| `AdminClient` | collections, fields, picklists, validation rules, record types, layouts, list views, pages, menus, flows, users, profiles, tenants, governor limits, packages, migrations, tokens, audit… |
| `TokenManager` | refresh and validation for OIDC tokens |

## Errors

`KeltaError` and its subclasses `ValidationError`, `AuthenticationError`, `AuthorizationError`, `NotFoundError`,
`ServerError`, `NetworkError` expose the JSON:API envelope (`errors[]`, `code`, `requestId`).

## Validation

Zod schemas for `ResourceMetadata`, `ListResponse` and `ErrorResponse` validate responses at the boundary.

## Generated types

Generate TypeScript interfaces for your tenant's collections from its OpenAPI document:

```bash
kelta sdk types -o kelta-types.ts
```

or programmatically with `generateTypesFromUrl` / `generateTypesFromSpec`. Regenerate after schema changes.
