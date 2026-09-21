---
title: Kubernetes deployment
description: A generic production topology — one Deployment per service, a pre-sync migration Job, ingress rules, secrets, probes and scaling.
section: deploy
order: 30
---

Kelta ships as container images, one per service, built from the repository. This page describes the shape of a
production deployment without prescribing a specific ingress controller, GitOps tool or registry.

## Topology

| Workload | Image | Replicas | Notes |
|---|---|---|---|
| `gateway` | `kelta-gateway` | 2+ | stateless; Redis-backed limiters and NATS broadcast make replicas interchangeable |
| `worker` | `kelta-worker` | 2+ | stateless; flow resume and trigger consumption use queue groups |
| `worker-migrate` **Job** | `kelta-worker` with `SPRING_PROFILES_ACTIVE=migrate` | 1, pre-rollout | Flyway + system-collection seeding + collection DDL, then exits |
| `auth` | `kelta-auth` | 2+ | sessions in Redis |
| `ai` | `kelta-ai` | 1+ | optional |
| `mcp` | `kelta-mcp` | 1+ | optional; `GATEWAY_URL` → gateway service |
| `ui` | `kelta-ui` | 2+ | nginx serving the SPA; `VITE_API_BASE_URL` baked at build time |
| `cli-downloads` | `kelta-cli-downloads` | 1 | optional; CLI binaries + install scripts |

Backing services: PostgreSQL (with `pgvector`; managed or in-cluster), Redis, NATS JetStream, Cerbos PDP; optional
S3-compatible storage, SMTP, Svix, Superset.

## The schema gate

Schema changes are applied **once, before pods roll**, by the migrate Job:

- Run the worker image with `SPRING_PROFILES_ACTIVE=migrate`. It enables Flyway, seeds system collections, applies
  every active collection's `CREATE TABLE` / reconcile, and exits — failing the Job (and therefore the rollout)
  on any error, so a worker never starts against a half-applied schema.
- Hook it as a pre-sync / pre-upgrade job in your GitOps or Helm tooling with no retries (`backoffLimit: 0`).
- Worker pods run with Flyway disabled and `KELTA_SCHEMA_BOOTSTRAP_ENABLED=false`; they only *register*
  collections at startup. Collections created at runtime still get their tables immediately through the config
  event bus.

Migrations are forward-only. See [Database migrations](/docs/deploy/database-migrations/).

## Ingress

| Host | Rule |
|---|---|
| `api.example.com` | `/` → gateway. Also: `^/[a-z][a-z0-9-]+/mcp/(user\|admin)` → `mcp` service; `/ws/realtime` needs WebSocket upgrade support |
| `auth.example.com` | `/` → auth (including `/portal/**`, which must not pass through the gateway) |
| `app.example.com` | `/` → ui |
| `downloads.example.com` | `/` → cli-downloads (optional) |

Tenant custom domains are additional hosts routed to the gateway; certificates for them are your responsibility.

## Configuration and secrets

Every service is configured by environment variables — the full list is in the
[configuration reference](/docs/deploy/configuration/). Put these in Secrets: `JWK_SET`,
`KELTA_ENCRYPTION_KEY`, `KELTA_INTERNAL_TOKEN`, database credentials, `ANTHROPIC_API_KEY`, SMTP/Twilio/S3/Svix
credentials, VAPID keys, campaign and visit-link HMAC secrets. `KELTA_AUTH_ISSUER_URI` must be identical across
gateway, worker, auth and ai.

## Probes

Spring Boot actuator on every Java service: readiness `/actuator/health/readiness`, liveness
`/actuator/health/liveness`. The gateway's readiness waits for its route table; give it a generous initial delay
on first boot.

## Scaling

- Gateway and worker scale horizontally with no coordination.
- The worker exposes `kelta.worker.collection.count` and request/duration metrics for autoscaling signals.
- PostgreSQL connection budget = replicas × `DB_POOL_MAX` per service; size the database (or PgBouncer in
  transaction mode — the platform sets the tenant per transaction, so pooling is safe) accordingly.
- Native images start in tens of milliseconds, so rolling updates are quick.

## After deploying

1. `curl https://api.example.com/actuator/health/liveness` and the same on `auth`.
2. Sign in to the console; open Setup → Platform → System health.
3. `kelta auth login --url https://api.example.com --tenant <slug>` then `kelta collections list`.
4. Point your observability backend at the OTLP endpoints ([Observability setup](/docs/deploy/observability/)).
