---
title: Docker Compose deployment
description: The reference compose stack — files, native vs JVM images, profiles, secrets, persistence — and what it deliberately leaves out.
section: deploy
order: 20
---

The repository's `docker-compose.yml` runs the whole platform on one machine. It is the supported path for
evaluation and development; for production, see [Kubernetes deployment](/docs/deploy/kubernetes/).
Quick commands are on the [quickstart](/docs/getting-started/quickstart/).

## Files

| File | Purpose |
|---|---|
| `docker-compose.yml` | every service and backing store, healthchecks, dependencies |
| `docker-compose.jvm.yml` | overlay that swaps the three native Dockerfiles for `Dockerfile.jvm` |
| `.env` (from `.env.example`) | secrets and toggles |
| `Makefile` | `setup`, `gen-keys`, `gen-vapid`, `up`, `up-jvm`, `seed`, `logs`, `rebuild`, `down`, `reset` |

## Image variants

`kelta-gateway`, `kelta-worker` and `kelta-auth` each ship `Dockerfile` (GraalVM native image — what production
runs) and `Dockerfile.jvm` (plain JRE). `kelta-ai`, `kelta-mcp` and `kelta-ui` have one Dockerfile each.

| | native | JVM |
|---|---|---|
| Build memory | ~24 GB Docker allocation (three concurrent builds) | default allocation |
| Build time | ~10 min per service | ~2–3 min per service |
| Startup | ~50 ms | ~20–40 s |
| Behaviour | production parity | equivalent for development |

Ports, names, healthchecks and environment are identical; switching modes recreates containers but keeps volumes.

## Services and dependencies

`postgres`, `redis`, `nats`, `cerbos` start first; `kelta-auth`, `kelta-worker`, `kelta-gateway` wait on their
healthchecks; `kelta-ui` waits on the gateway. The gateway's readiness probe (`/actuator/health/readiness`) goes
green only after its route table is loaded, which is what `make seed` waits for. In compose the worker runs
Flyway migrations itself at startup (`SPRING_FLYWAY_ENABLED=true`); Kubernetes uses a migrate Job instead.

## Profiles

| Profile | Adds |
|---|---|
| `ai` | `kelta-ai` (needs `ANTHROPIC_API_KEY`) |
| `tools` | pgAdmin (8092), Redis Commander (8091), Mailpit (8025) |
| `observability` | Jaeger 2 (UI 16686, OTLP 4317/4318) + OpenSearch |
| `telehealth` | LiveKit SFU for video visits |

## Secrets

`make gen-keys` writes the secrets that have no defaults into `.env`: `JWK_SET` (RSA JWK set for JWT signing),
`KELTA_ENCRYPTION_KEY` (AES-256), and the HMAC secrets for visit links and campaign tracking. `make gen-vapid` adds a
Web Push key pair. Services that need a signing key refuse to start without one.

## Persistence

Named volumes: `postgres_data`, `redis_data`, `nats_data`, `geoip-data`, `opensearch_data`. `make reset` wipes
them. Back up `postgres_data` (or `pg_dump`) — see [Data retention and backups](/docs/platform/data-retention/).

## Development-only settings

The compose file sets a few values you must not carry to production: `DIRECT_LOGIN_ENABLED=true` on the auth
server, `RATE_LIMIT_EXEMPT_CIDRS` covering private ranges, `CORS_ALLOWED_ORIGIN_PATTERN` for `localhost`, and mail
routed to Mailpit.

## Not included

- **`kelta-mcp`** — build `kelta-mcp/Dockerfile` and run it with `GATEWAY_URL` pointing at the gateway if you
  want hosted MCP locally; the CLI's [local bridge](/docs/cli/mcp-bridge/) covers most needs.
- **Object storage** — attachments need an S3-compatible store (`KELTA_S3_*`).
- **Svix**, **Superset**, TLS termination, ingress.
