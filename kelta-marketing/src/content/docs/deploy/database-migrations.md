---
title: Database migrations and schema bootstrap
description: Who runs Flyway, the migrate profile, how collection tables are created, database roles and row-level security, and verifying a migration run.
section: deploy
order: 50
---

## Ownership

The **worker** image owns the database schema: Flyway migrations for the platform's own tables live inside it
(`db/migration`, versioned `V<n>__…`), and it also creates and reconciles the physical table of every collection.
No other service migrates.

## Two modes

| Mode | Where | How |
|---|---|---|
| Startup | Docker Compose | the worker starts with `SPRING_FLYWAY_ENABLED=true` and runs migrations, seeds system collections and applies collection DDL before serving |
| Job | Kubernetes | a one-shot run of the worker image with `SPRING_PROFILES_ACTIVE=migrate` — no HTTP server, no schedulers; Flyway → system-collection seeding → collection DDL → exit code. Worker pods then start with Flyway off and `KELTA_SCHEMA_BOOTSTRAP_ENABLED=false` |

In Job mode the Job's exit status is the gate: a failed migration fails the rollout before any new pod serves.
Migrations are forward-only; a rollback means redeploying previous images against a database restored from
backup.

## Collection tables

User collections get a physical table each (`PHYSICAL_TABLES` storage mode) in the tenant's schema. The migrate
step reconciles every active collection's table against its metadata; at runtime, collection and field changes
apply their DDL immediately through the config event bus, so the Job is only needed for platform upgrades.

## Database roles and row-level security

- The **application role** must **not** carry `BYPASSRLS`: every tenant table has `FORCE ROW LEVEL SECURITY`
  policies keyed on the transaction-scoped setting `app.current_tenant_id`, which the services set per
  transaction (PgBouncer-safe).
- Roles the platform creates for direct database logins (for example BI users) are **pinned** to a tenant by
  role name, so they cannot switch tenant by setting the variable.
- System collection definitions live once, in a platform tenant, and are readable by every tenant through a
  dedicated read policy.
- The application role needs `CREATE` on the database (schemas per tenant, `CREATE EXTENSION vector` when a
  `VECTOR` field is first used).

## Cerbos policies

At worker startup, authorization policies are seeded into Cerbos for every tenant if absent (`CERBOS_SEED_FORCE=true`
re-pushes them). Runtime changes to profiles and permissions sync continuously and never rely on this path.

## Verifying a run

- Job mode: the Job completes with exit code 0 and its log ends with the bootstrap summary; the
  `flyway_schema_history` table shows the new version.
- Then `curl https://api.example.com/actuator/health/readiness` on a fresh worker pod, and
  `kelta collections list`.

Setup → Platform → Monitoring → Health flags collections whose table is missing or out of sync.
