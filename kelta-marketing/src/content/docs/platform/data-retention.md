---
title: Data retention, exports and backups
description: What the platform prunes automatically, what it can export, and what you must back up yourself.
section: platform
order: 80
---

## What is pruned automatically

| Data | Policy |
|---|---|
| Flow runs and step logs, scheduled-job logs | terminal runs older than `FLOW_RETENTION_MAX_AGE_DAYS` (default 60); **dry-run by default** — arm with `FLOW_RETENTION_DRY_RUN=false`; `WAITING` runs are never pruned; metric `kelta_worker_flowlog_purged` |
| Request logs and traces (tenant monitoring) | per-tenant retention in Setup → Platform → Monitoring → Settings |
| Presence and realtime events | ephemeral (minutes) |

## What is kept

Audit logs, login history, field history and record versions are retained indefinitely. Deleted records leave a
tombstone for sync clients and, when versioning is on, their last snapshot.

## Exports

- **Data** — `POST /api/data-exports` (`{ "exportScope": "FULL" | "SELECTIVE", "collectionIds": […], "format": "CSV" | "JSON" }`)
  runs as a job and returns a presigned download; Setup → Platform → Bulk jobs tracks it. Scheduled exports are scheduled jobs of
  type `DATA_EXPORT`. Exports are masked for the requester ([data masking](/docs/security/field-security-and-masking/)).
- **Reports** — CSV/PDF export and scheduled delivery ([Dashboards and reports](/docs/console/dashboards-and-reports/)).
- **Metadata** — packages via `kelta metadata export` ([Sandboxes and promotion](/docs/platform/environments-and-promotion/)).
  A metadata package is not a backup of data.

## Backups

The platform has **no built-in backup scheduler**. Back up:

1. **PostgreSQL** — all tenant data, metadata, audit and history. Use your database's native tooling (`pg_dump`,
   WAL archiving, managed snapshots).
2. **Object storage** — attachments and module JARs when `KELTA_S3_ENABLED` is on.
3. **Secrets** — the signing JWK set, `KELTA_ENCRYPTION_KEY` (without it `ENCRYPTED` fields are unrecoverable),
   VAPID and HMAC secrets.

Redis and NATS hold caches, rate-limit counters and in-flight events; they can be rebuilt. See
[Upgrading](/docs/deploy/upgrading/) for the order of operations around a restore.
