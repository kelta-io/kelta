---
title: Audit logs
description: The security audit log, the setup audit trail and login history — what each records, how to read them, and retention.
section: security
order: 100
---

Three read-only system collections record who did what. All are queryable as JSON:API (filters, sorting, paging)
and visible in the console; none can be written or deleted through the API.

## Security audit log

`security-audit-logs` — authentication and authorization events: `eventType`, `eventCategory`, `actorUserId`,
`actorEmail`, `targetType`/`targetId`/`targetName`, `details`, `ipAddress`, `userAgent`, `correlationId`,
`timestamp`.

Event types include `LOGIN_SUCCESS`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`, `ACCOUNT_UNLOCKED`, `PASSWORD_CHANGED`,
`PASSWORD_RESET_ADMIN`, `PASSWORD_POLICY_UPDATED`, `MFA_ENROLLED`, `MFA_CHALLENGE_SUCCESS`, `MFA_CHALLENGE_FAILED`,
`MFA_RESET`, `RECOVERY_CODE_USED`, `TOKEN_ISSUED`, `TOKEN_REVOKED`, `PAT_CREATED`, `PAT_ADMIN_CREATED`,
`PAT_REVOKED`, `DELEGATED_ADMIN_ACTION`, `DELEGATED_SCOPE_CHANGED`, `PORTAL_USER_INVITED`, `AUTH_FAILURE`.

```http
GET /api/security-audit-logs?filter[eventType][eq]=LOGIN_FAILED&sort=-timestamp&page[size]=100
```

Console: Setup → Security → Security audit (`MANAGE_USERS`). CLI: `kelta audit security --since 24h`.

## Setup audit trail

`setup-audit-entries` — every metadata change: `userId`, `action`, `section`, `entityType`, `entityId`,
`entityName`, `oldValue`, `newValue`, `timestamp`. Collection, field, layout, flow, profile and permission edits
all land here with before/after values.

Console: Setup → Security → Audit trail (`VIEW_SETUP`). CLI: `kelta audit setup`.

## Login history

`login-history` — one row per login attempt: `userId`, `loginTime`, `sourceIp`, `loginType`, `status`,
`userAgent` and, when the gateway has a geolocation database, `geoCountry`, `geoRegion`, `geoCity`, `geoLat`,
`geoLon`.

Console: Setup → Security → Login history; a user's own logins on their detail page. CLI: `kelta audit logins`,
`kelta users logins <userId>`.

## Data change history

Changes to **record data** are not in these logs; they are captured per field or per record by
[field history and record versioning](/docs/data-model/record-history/).

## Correlation

Every API response carries `meta.requestId`; audit rows carry `correlationId`. Use it to join an audit event to the
request log and traces in Setup → Platform → Monitoring ([Audit trails and tenant monitoring](/docs/platform/audit-and-monitoring/)).

## Retention

Audit rows are kept indefinitely; there is no user-facing purge. Monitoring data (request logs, traces) has its own
retention setting — see [Audit trails and tenant monitoring](/docs/platform/audit-and-monitoring/).
