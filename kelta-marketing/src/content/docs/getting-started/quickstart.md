---
title: Quickstart with Docker Compose
description: Run the full Kelta stack on your machine in three commands and sign in to the console.
section: getting-started
order: 20
---

The repository ships a Docker Compose stack with every service and backing store. It is the fastest way to
evaluate Kelta or develop against it. For production, see [Kubernetes deployment](/docs/deploy/kubernetes/).

## Prerequisites

- Docker and Docker Compose (Docker Desktop or the Docker Engine plugin).
- `make` and `git`.
- **Memory.** The default build produces GraalVM native images for the three Java services and builds them
  concurrently, which needs roughly **24 GB allocated to Docker**. If you have less, use the JVM images
  (`make up-jvm`) — same behaviour, faster build, slower startup.

## Start the stack

```bash
git clone https://github.com/kelta-io/kelta.git
cd kelta
make setup   # first time only: copies .env and generates dev signing/encryption keys
make up      # postgres, redis, nats, cerbos, auth, worker, gateway, ui
make seed    # waits for the stack to be healthy, then prints login details
```

`make setup` writes an `.env` from `.env.example` and generates the secrets that have no defaults: an RSA JWK set
for JWT signing (`JWK_SET`) and an AES-256 key for envelope encryption (`KELTA_ENCRYPTION_KEY`). Services that need a
signing key fail to start without one rather than falling back to a shared value.

If the native build dies with `cannot allocate memory`, that is GraalVM native-image running out of heap, not a
code error:

```bash
make up-jvm   # JVM images: fits a default Docker allocation, ~2-3 min per service
```

| | `make up` (native) | `make up-jvm` |
|---|---|---|
| Docker memory needed | ~24 GB | default allocation |
| Build time | ~10 min per service | ~2–3 min per service |
| Startup | ~50 ms | ~20–40 s |

Ports, container names and environment are identical in both modes; switching recreates the containers but keeps
the volumes.

## Sign in

Open **http://localhost:5173** and sign in with the seeded administrator:

| Field | Value |
|---|---|
| Email | `admin@kelta.local` |
| Password | `password` — you are asked to change it on first login |
| Tenant | `default` |

You land in the admin console. Continue with [your first app](/docs/getting-started/first-app/).

## Service ports

| Service | Port | Notes |
|---|---|---|
| kelta-ui | 5173 | Admin console and end-user app |
| kelta-gateway | 8080 | API entry point — the URL you give the CLI and SDK |
| kelta-auth | 8081 | OIDC provider |
| kelta-worker | 8083 | Not called directly; reachable for debugging |
| kelta-ai | 8084 | `--profile ai` |
| Cerbos | 3592 (HTTP) / 3593 (gRPC) | Authorization engine |
| PostgreSQL | 5432 | |
| Redis | 6379 | |
| NATS | 4222 | |
| Mailpit | 8025 | Captures every email the stack sends (`--profile tools`) |
| pgAdmin | 8092 | `--profile tools` |
| Redis Commander | 8091 | `--profile tools` |

## Optional profiles

```bash
make up-ai          # + kelta-ai — needs ANTHROPIC_API_KEY in .env
make up-full        # + ai + tools (pgAdmin, Redis Commander, Mailpit)
make up-telehealth  # + LiveKit SFU for video visits (dev keys built in)
```

The compose stack does **not** include `kelta-mcp`, the hosted MCP server. To use MCP tools against a local stack,
run the CLI's local bridge instead: `kelta mcp serve --source local` (see [Local MCP bridge](/docs/cli/mcp-bridge/)).

## Everyday commands

```bash
make logs SVC=kelta-gateway        # tail one service
make rebuild SVC=kelta-worker      # rebuild + restart one service (rebuild-jvm for JVM mode)
make ps                            # container status
make down                          # stop everything, keep data
make reset                         # stop, wipe volumes, start clean
make help                          # every target
```

## Point the CLI at your local stack

Install the CLI ([instructions](/docs/getting-started/install-cli/)), then log in. On `localhost` the auth server
cannot be derived from the API host, so pass it explicitly:

```bash
kelta auth login --url http://localhost:8080 --tenant default --auth-url http://localhost:8081
kelta collections list
```

Or skip the browser entirely with a personal access token created under **Profile → API tokens** in the app:

```bash
kelta auth login --url http://localhost:8080 --tenant default --token klt_...
```

## Troubleshooting

- **Gateway answers 404 for `/api/...` right after start.** The gateway builds its route table from the worker's
  collection registry on startup; wait for `make seed` to report healthy (it polls readiness, not liveness).
- **Emails never arrive.** In the compose stack all mail goes to Mailpit on http://localhost:8025 — start it with
  `--profile tools` / `make up-full`.
- **`cannot allocate memory` during build.** See [Start the stack](#start-the-stack): use `make up-jvm` or raise
  Docker's memory limit.
