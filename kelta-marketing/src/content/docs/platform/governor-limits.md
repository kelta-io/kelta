---
title: Governor limits and editions
description: The per-tenant quotas, how editions seed them, where each is enforced, and how to view and change them.
section: platform
order: 30
---

Every tenant carries a quota map. An **edition** (`FREE`, `PROFESSIONAL`, `ENTERPRISE`, `UNLIMITED`) supplies
defaults; an operator can override any key.

## Keys

| Key | Enforced |
|---|---|
| `apiCallsPerDay` | at the gateway per UTC day — `429` past the limit ([Rate limits](/docs/api/rate-limits/)) |
| `storageGb` | on attachment upload |
| `maxUsers`, `maxPortalUsers` | when creating or inviting users |
| `maxCollections`, `maxFieldsPerCollection` | when creating collections and fields |
| `maxWorkflows`, `maxReports` | when creating flows and reports |
| `aiEnabled`, `aiTokensPerMonth` | AI assistant availability and monthly token budget |
| `campaignEmailsPerDay` | outbound campaign email (optional feature area) |
| `telehealthEnabled`, `videoMinutesPerMonth` | optional feature area |
| `archiveAfterDays`, `retentionYears`, `purgeLiveAfterDays` | data-lifecycle policies used by archival features |

Limits are enforced by before-save hooks and the gateway; hitting one answers a `400` or `429` naming the limit.

## View

```http
GET /api/governor-limits   # limits plus live usage: apiCallsUsed, users, collections, storage…
```

Setup → Platform → Governor limits (`VIEW_SETUP`) and the tenant dashboard show the same; `kelta limits get`.

## Change

```bash
kelta limits set-tier ENTERPRISE                          # PUT /api/governor-limits/tier
kelta limits set --data '{"apiCallsPerDay": 500000}'      # PUT /api/governor-limits (partial map)
```

Writes are authorized through the tenant's Cerbos route policy — in practice an administrator profile. Changes apply
immediately; the daily API counter is not reset.

## Sandboxes

A sandbox inherits its parent's limits at creation; adjust them separately afterwards.
