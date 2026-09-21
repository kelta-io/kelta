---
title: Rate limits and governor quotas
description: The daily tenant quota, the per-user share, per-IP limits on public endpoints, the 429 contract, and batch and page-size ceilings.
section: api
order: 50
---

## Daily tenant quota

Every authenticated request counts against the tenant's `apiCallsPerDay` governor limit — one shared budget for
all users, tokens and integrations of the tenant, reset daily. Past it, the gateway answers
`429 RATE_LIMIT_EXCEEDED` with a `Retry-After` header. Current usage:

```http
GET /api/governor-limits     # apiCallsUsed / apiCallsLimit, plus the other quotas
```

(Setup → Platform → Governor limits; `kelta limits get`.) See [Governor limits](/docs/platform/governor-limits/).

## Per-user share

Inside the tenant window, no single member may consume more than a configured **share** of it (default 90 %).
This bounds a runaway script or a stolen token so it cannot exhaust the whole tenant; it is not a fair-share
divider. The key is the member's email, so switching from a JWT to a PAT does not double the budget. A rejected
request does not spend the tenant's budget.

## Public endpoints

Unauthenticated paths — inbound flow webhooks, module webhooks, health — have separate per-IP, per-minute
budgets keyed by path prefix. They protect the platform from bursts; they do not count against your tenant.

## Metering per token

A personal access token's lifetime `requestCount` (`GET /api/me/tokens`) counts every authenticated call made
with it, refused or not. It is a usage readout, not a limit.

## Other ceilings

| Limit | Value |
|---|---|
| `page[size]` | 200 |
| `POST /api/operations` batch size | 100 by default; a deployment may raise it to at most 500 |
| Full-text search `limit` | 100 |
| Realtime subscriptions per socket | 50 |
| Realtime connections per tenant | 100 |
| Flow `QUERY_RECORDS` `pageSize` / `SQL_QUERY` `maxRows` | 200 default / 1000 default, 10 000 max |

## Handling 429

Honour `Retry-After`. Prefer fewer, larger requests: page at 200, use `include` instead of N+1 fetches, batch
writes through `/api/operations`, and subscribe to realtime events instead of polling.
