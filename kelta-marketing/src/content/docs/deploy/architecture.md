---
title: Architecture overview
description: The services and backing stores, the request path through the gateway, the event bus, multi-pod behaviour, and the hostnames a deployment exposes.
section: deploy
order: 10
---

## Components

```
 browser / CLI / MCP client / integrations
             │
             ▼
   ┌──────────────────┐        ┌──────────────────┐
   │   kelta-gateway  │──────▶ │   kelta-worker   │──────▶ PostgreSQL (+pgvector)
   │  auth · tenant · │        │ collections ·    │
   │  rate limit ·    │        │ JSON:API · flows │──────▶ object storage (S3 API)
   │  Cerbos · routes │        │ search · hooks   │
   └────────┬─────────┘        └────────┬─────────┘
            │  ▲                        │
            │  │ JWT / OIDC             │ NATS JetStream (config, records, triggers)
            ▼  │                        ▼
   ┌──────────────────┐        ┌──────────────────┐
   │    kelta-auth    │        │ Redis · Cerbos   │
   │ OIDC · SSO · MFA │        │ cache · authz    │
   └──────────────────┘        └──────────────────┘
   ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
   │     kelta-ai     │  │    kelta-mcp     │  │     kelta-ui     │
   │ assistant/agents │  │  MCP over HTTP   │  │ static SPA       │
   └──────────────────┘  └──────────────────┘  └──────────────────┘
```

| Service | Responsibility | Talks to |
|---|---|---|
| **gateway** | Every API request: strips forged identity headers, resolves the tenant (custom domain → slug → header), validates JWT or PAT, applies per-IP / per-user / per-tenant rate limits, geo-enrichment, the tenant IP allowlist, Cerbos route authorization, then forwards to the worker with stamped identity headers. Hosts the realtime WebSocket. | Redis, Cerbos, NATS, worker, ai |
| **worker** | Collections and their tables, JSON:API, validation and hooks, flows, approvals, search, attachments, schema lifecycle, database migrations, scheduled jobs, modules. | PostgreSQL, Redis, NATS, Cerbos, S3, SMTP |
| **auth** | OIDC provider (Spring Authorization Server), password login, MFA, SSO federation (OIDC/SAML), portal magic links, sessions in Redis. | PostgreSQL, Redis, worker (internal) |
| **ai** | Assistant chat, proposals, flow generation, governed agents. | Anthropic API, PostgreSQL, Redis, worker |
| **mcp** | Stateless MCP server exposing admin and data toolsets; calls the platform through the gateway. | gateway |
| **ui** | The admin console and end-user app — static files behind nginx. | gateway, auth |

Backing stores: **PostgreSQL 15+** with `pgvector`, **Redis 7**, **NATS JetStream 2.10**, **Cerbos** policy
decision point, an **S3-compatible** object store (optional), SMTP (optional), Svix (optional, outbound
webhooks), Apache Superset (optional, embedded BI).

## Request path

1. Gateway: identity-header strip → custom domain → slug extraction → tenant resolution → per-IP limit → JWT /
   PAT authentication → per-user and per-tenant limits → geo enrichment → tenant IP allowlist → Cerbos route
   check → header transformation → forward.
2. Worker: tenant bound to the database connection (row-level security) → controller / dynamic collection
   router → validation and before-save hooks → query engine → after-save hooks → field-level security advice
   on the response → JSON:API.
3. Events: record and configuration changes are published to NATS JetStream; every pod consumes configuration
   events (registry, routes, caches), flow triggers are consumed by one pod per message, realtime events fan
   out to every gateway.

## Multi-pod behaviour

All gateway and worker replicas are stateless. Rate limiters and revocation sets live in Redis; caches are
invalidated fleet-wide through NATS; flow resumes and trigger consumption use queue groups so exactly one pod
acts. A new collection created on one pod is routable on all of them within seconds. Every replica reports a
distinct `service.instance.id` for metrics.

## Hostnames a deployment exposes

| Host (example) | Serves |
|---|---|
| `app.example.com` | `kelta-ui` — console and app |
| `api.example.com` | `kelta-gateway` — `/api/**`, `/ws/realtime`, `/<tenant>/mcp/{admin,user}` (routed to `kelta-mcp`), `/scim/v2` |
| `auth.example.com` | `kelta-auth` — OIDC endpoints, login pages, `/portal/**` (never transits the gateway) |
| `downloads.example.com` | optional — CLI binaries and install scripts |

Custom tenant domains map onto the same gateway.

Next: [Docker Compose](/docs/deploy/docker-compose/) for evaluation, [Kubernetes](/docs/deploy/kubernetes/) for
production.
