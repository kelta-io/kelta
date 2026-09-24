# Kelta Platform

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

This software is available under the GNU Affero General Public License v3.0 (AGPL-3.0-only). For commercial or closed-source deployments, contact cklinker@rzware.com.

Kelta is a platform for building dynamic, runtime-configurable enterprise applications. It provides a metadata-driven architecture where collections (tables), fields, validation rules, relationships, and workflows are all defined and managed at runtime — no redeployment required.

See the [public roadmap](https://www.kelta.io/roadmap) for what's shipped, in progress, and next, and the
[documentation](https://www.kelta.io/docs/) for guides and reference. The docs site is rendered by
`kelta-marketing` from `kelta-marketing/src/content/docs/**` plus the canonical `docs/authoring/*.md` and the
generated CLI docs, read in place — editing those files republishes the site. Preview locally with
`cd kelta-marketing && npm run dev` (search needs `npm run build && npm run preview`).

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│   kelta-ui  │────▶│ kelta-gateway│────▶│  kelta-worker│
│  (React)    │     │ (API Gateway)│     │  (Services)  │
└─────────────┘     └──────┬───────┘     └──────┬───────┘
                           │                     │
                    ┌──────┴───────┐      ┌──────┴───────┐
                    │    Redis     │      │  PostgreSQL  │
                    │   (Cache)    │      │     (DB)     │
                    └──────────────┘      └──────────────┘
       ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
       │  kelta-auth  │     │    Cerbos    │     │     NATS     │
       │   (OIDC)     │     │   (Authz)    │     │  (JetStream) │
       └──────────────┘     └──────────────┘     └──────────────┘
```

## Project Structure

| Module | Description |
|--------|-------------|
| `kelta-platform/runtime/runtime-core` | Core runtime library — collection management, query engine, validation, dual storage modes |
| `kelta-platform/runtime/runtime-events` | Shared NATS event classes for lifecycle events |
| `kelta-platform/runtime/runtime-jsonapi` | Shared JSON:API model classes |
| `kelta-platform/runtime/runtime-module-core` | Workflow action handlers (field updates, record CRUD, tasks, decisions) |
| `kelta-platform/runtime/runtime-module-integration` | Integration action handlers (HTTP callouts, email, scripts, delays) |
| `kelta-platform/runtime/runtime-module-schema` | Schema lifecycle hooks for system collections |
| `kelta-gateway` | Spring Cloud Gateway — authentication, authorization, JSON:API processing |
| `kelta-worker` | Worker service — owns database migrations, executes business logic |
| `kelta-web` | TypeScript SDK, React component library, and plugin SDK |
| `kelta-ui/app` | Admin/builder UI application |

## Tech Stack

**Backend:** Java 25, Spring Boot 4.x, Spring Cloud Gateway, Maven

**Frontend:** TypeScript, React 19, Vite, Vitest, Tailwind CSS

**Infrastructure:** PostgreSQL 15, Redis 7, NATS 2.10 (JetStream), Cerbos (authz). OIDC is
provided by the internal `kelta-auth` service — no external identity server required.
(Keycloak appears only in docker-compose as an optional federation IdP for testing.)

**Deployment:** Docker, Kubernetes, ArgoCD

## Prerequisites

- Java 25 (GraalVM Community 25.0.2; see `.tool-versions`)
- Maven 3.9+
- Node.js 18+
- Docker & Docker Compose
  - `make up` builds GraalVM native images and needs **~24 GB allocated to Docker**.
    With less, use `make up-jvm` — see [Native vs JVM images](#native-vs-jvm-images).

## Quickstart

Clone the repo, start the stack, open the UI, and create your first collection.

```bash
make setup   # first time only: copies .env, generates dev signing keys
make up      # starts postgres, redis, nats, cerbos, auth, worker, gateway, ui
make seed    # waits for the stack to be healthy, prints login info
```

1. Open **http://localhost:5173** and sign in with `admin@kelta.local` / `password` (tenant `default`).
2. Go to **Setup → Data Model → Collections**, click **Create Collection**, fill in
   the wizard (Basics → Fields → Authorization → Review), then click **Create
   Collection** on the Review step.

That's it — a running, runtime-configurable Kelta instance with your first collection.
First build compiling native images can take a while; see
[Native vs JVM images](#native-vs-jvm-images) for a faster local build, or
[Local Development](#local-development) below for ports, debugging, and the full
service list.

## Local Development

### One-command start

```bash
make setup   # first time only: copies .env, generates RSA JWK + AES key
make up      # starts postgres, redis, nats, cerbos, auth, worker, gateway, ui
make seed    # waits for healthy stack, then prints credentials
```

> **Build fails with `cannot allocate memory`?** That's GraalVM native-image, not a
> code error. Run `make up-jvm` instead — see [Native vs JVM images](#native-vs-jvm-images).

Default credentials (seeded by Flyway migrations):

| Field | Value |
|-------|-------|
| URL | http://localhost:5173 |
| Email | `admin@kelta.local` |
| Password | `password` (force-change on first login) |
| Tenant slug | `default` |

### Service ports

| Service | Host port | Notes |
|---------|-----------|-------|
| kelta-ui | :5173 | React admin UI |
| kelta-gateway | :8080 | Main API entry point |
| kelta-auth | :8081 | Internal OIDC provider |
| kelta-worker | :8083 | Business logic + Flyway |
| kelta-ai | :8084 | AI service (`--profile ai`) |
| Cerbos | :3592 (HTTP) / :3593 (gRPC) | Authorization engine |
| PostgreSQL | :5432 | |
| Redis | :6379 | |
| NATS | :4222 | |
| pgAdmin | :8092 | `--profile tools` |
| Redis Commander | :8091 | `--profile tools` |
| Mailpit (SMTP UI) | :8025 | `--profile tools` |

### Useful Makefile targets

```bash
make up-ai           # default stack + kelta-ai (needs ANTHROPIC_API_KEY in .env)
make up-full         # default + ai + tools
make up-telehealth   # default stack + LiveKit SFU (video visits, dev keys built in)
make rebuild SVC=kelta-worker   # rebuild + restart one service
make logs SVC=kelta-gateway     # tail logs
make down            # stop all containers
make reset           # wipe volumes and restart clean

make gen-vapid       # generate a dev VAPID key pair for browser Web Push

make up-jvm          # default stack, JVM images (fast build, low memory)
make up-jvm-ai       # JVM stack + kelta-ai
make up-jvm-full     # JVM stack + ai + tools
make rebuild-jvm SVC=kelta-worker   # rebuild one service as a JVM image
```

`make help` lists every target.

### Native vs JVM images

`kelta-auth`, `kelta-worker` and `kelta-gateway` ship two Dockerfiles: `Dockerfile`
(GraalVM native-image, what production runs) and `Dockerfile.jvm` (plain JRE).

`make up` uses the native path. Compose builds those three **concurrently**, and
`native-image` sizes its heap to ~80% of whatever the cgroup reports — so three
builders on a 12 GB Docker Desktop each try to claim ~9 GB and the build dies with:

```
ResourceExhausted: ... "mvn -Pnative native:compile ..." did not complete
successfully: cannot allocate memory
```

Two ways to fix it:

| | `make up` (native) | `make up-jvm` |
|---|---|---|
| Docker memory needed | ~24 GB (Settings → Resources) | fits a default allocation |
| Build time | ~10 min per service | ~2–3 min per service |
| Startup | ~50 ms | ~20–40 s |
| Production parity | yes | behaviorally equivalent for dev |

JVM mode is the right default for day-to-day work. Reach for native when you're
debugging something native-specific — reachability metadata, `reflect-config.json`
gaps, or build-time initialization (see `.claude/docs/concerns.md`).

Ports, container names, healthchecks and env are identical in both modes — the
overlay (`docker-compose.jvm.yml`) only swaps `build.dockerfile`. Switching modes
recreates the containers but keeps volumes, so your data survives.

### Debugging a service in the IDE (hybrid mode)

Stop the container for the service you want to debug, then launch it from IntelliJ using the checked-in run config in `.run/`:

```bash
make debug SVC=kelta-worker
# IntelliJ → Run → kelta-worker  (or use .run/kelta-worker.run.xml)
```

**No `/etc/hosts` entry needed.** The issuer is `KELTA_AUTH_ISSUER_URI=http://auth.localhost:8081`
everywhere: inside Docker `auth.localhost` is a network alias of the kelta-auth container
(which listens on 8081 there too), and on your machine browsers and the OS resolve any
`*.localhost` name to loopback (RFC 6761), reaching the published port 8081. One URL on both
sides is what lets the browser follow kelta-auth's discovery endpoints *and* the gateway accept
the tokens it issues (it validates `iss` against this exact value). The `.run/*.run.xml` configs
use the same value; the kelta-auth one also sets `SERVER_PORT=8081`. (Browsers and macOS resolve
`*.localhost` themselves; a minimal Linux without `nss-myhostname`/systemd-resolved may not — add
`127.0.0.1 auth.localhost` to `/etc/hosts` there for services you run from the IDE.)

**Upgrading an existing dev database.** On startup the worker repairs the `default` tenant's
baseline identity providers (`BaselineIdentityProviderReconciler`). Tenants *you* created before
the issuer moved to `auth.localhost:8081` still carry `http://kelta-auth:8080` in their internal
`oidc_provider` row — wipe the dev volumes (`make down && docker compose down -v`, then
`make up-jvm`) to start clean, or update that row's `issuer`/`jwks_uri`.

**Secrets in run configs** — the `.run/*.run.xml` files use `$VAR_NAME$` for
secrets (`KELTA_ENCRYPTION_KEY`, `JWK_SET`, `ANTHROPIC_API_KEY`). Set them in
the IntelliJ _Run/Debug Configurations → Environment variables_ dialog by
copying the values from your `.env` file.

### Build runtime libraries (required before first IDE run)

```bash
mvn clean install -DskipTests -f kelta-platform/pom.xml \
  -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
  -am -B
```

## Kelta CLI

Install the self-updating `kelta` CLI (macOS/Linux; Windows via `install.ps1`):

```bash
curl -fsSL https://downloads.kelta.io/cli/install.sh | sh
kelta auth login --url https://api.kelta.io --tenant <slug>   # browser + PKCE
kelta update            # self-update from the cluster (sha256-verified)
```

Binaries are cross-compiled and published by CI on every merge to main
(`kelta-cli-downloads` image, served at downloads.kelta.io). Agent-facing docs:
`kelta docs agent` / `kelta manifest`; command reference in
`kelta-web/packages/cli/COMMANDS.md`.

## Running Tests

### Java

```bash
# Build runtime (required before testing)
mvn clean install -DskipTests -f kelta-platform/pom.xml \
  -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
  -am -B

# Test gateway
mvn verify -f kelta-gateway/pom.xml -B

# Test worker
mvn verify -f kelta-worker/pom.xml -B
```

### Frontend

```bash
cd kelta-web
npm run lint
npm run typecheck
npm run format:check
npm run test:coverage
```

## Environment Variables

See [`.env.example`](.env.example). The required secrets are generated by `make gen-keys`. Signing keys have no defaults — services that need one fail to start without it rather than falling back to a shared value:

| Variable | Description |
|----------|-------------|
| `JWK_SET` | RSA-2048 JWK Set for JWT signing (generated by `make gen-keys`) |
| `KELTA_ENCRYPTION_KEY` | AES-256 key for envelope encryption (generated by `make gen-keys`) |
| `KELTA_INTERNAL_TOKEN` | Shared secret for internal service calls (default: `dev-internal-token`) |
| `ANTHROPIC_API_KEY` | Required only when running kelta-ai (`--profile ai`) |
| `KELTA_TELEHEALTH_VISIT_SECRET` | HMAC secret for emailed visit links (generated by `make gen-keys`). **The worker refuses to start without it** — a visit token is exchangeable for a portal login, so there is no safe default. Compose sets a local-only literal; anything deployed must generate its own |
| `CAMPAIGN_TRACKING_SECRET` | HMAC secret for campaign open-pixel, click-redirect and unsubscribe links (generated by `make gen-keys`). **Also fails startup when unset.** Note rotating it invalidates tracking and unsubscribe links in already-delivered email, which never expire |
| `KELTA_LIVEKIT_URL` / `KELTA_LIVEKIT_API_KEY` / `KELTA_LIVEKIT_API_SECRET` | LiveKit SFU for video visits (`--profile telehealth`; dev defaults match the compose service) |
| `KELTA_PUSH_VAPID_PUBLIC_KEY` / `KELTA_PUSH_VAPID_PRIVATE_KEY` / `KELTA_PUSH_VAPID_SUBJECT` | Optional — browser Web Push (VAPID). Run `make gen-vapid` to write a dev pair into `.env`. The public key is a base64url uncompressed P-256 point, the private key its raw base64url scalar; the subject must be a `mailto:` or `https:` URI (Safari rejects anything else). Absent ⇒ `WebPushProvider` is not registered and `GET /api/devices/vapid-public-key` 404s; native push is unaffected either way. **Rotating the pair invalidates every existing browser subscription** — clients must re-subscribe |
| `MAXMIND_LICENSE_KEY` | Optional — enables the gateway's automatic GeoLite2 IP-geolocation downloads (free key from a MaxMind account). Without it geo enrichment is silently inactive; the mmdb persists in the `geoip-data` volume |
| `RATE_LIMIT_EXEMPT_CIDRS` | Optional — comma-separated IPs/CIDR ranges exempt from **both** gateway rate limiters (per-IP and per-tenant). For trusted infrastructure whose traffic is legitimately bursty: uptime probes, in-cluster callers, a payment processor's webhook egress. A bare address means a single host; invalid entries are logged and skipped. Empty (the default) exempts nobody |
| `RATE_LIMIT_IP_PATHS` | Optional — per-IP budgets for unauthenticated **gateway** paths as `<path-prefix>=<per-minute>`, matched by longest prefix, each prefix its own bucket. Default `/actuator/health=100,/api/modules/webhooks=300,/api/webhooks/mail=600`. Redis-backed, so the budget is the fleet's, not each replica's. `/portal/**` is **not** here — it never transits the gateway; see `KELTA_AUTH_RATE_LIMIT_IP_PATHS` |
| `RATE_LIMIT_USER_SHARE` | Optional — fraction of a tenant's rate-limit window any single member may consume (default `0.9`). Bounds one member (or one stolen PAT) from consuming the **whole** tenant window; it is not a fair-share divider, and the default is deliberately close to 1 because one admin, bulk import, or integration PAT legitimately *is* most of a tenant. Tenants with many members should tighten it. `1.0` turns the per-user window off; an out-of-range value falls back to the default rather than disabling it |
| `KELTA_AUTH_RATE_LIMIT_IP_PATHS` | Optional — per-IP budgets for kelta-auth's public portal paths, same `<prefix>=<per-minute>` grammar. Default `/portal/api/signup=5,/portal/api/login/request=10,/portal/api/challenge=30,/portal/login=10`. Also `KELTA_AUTH_RATE_LIMIT_TRUSTED_PROXY_COUNT` (default `1`) — how many `X-Forwarded-For` hops to count back from the right to find the client; the leftmost entry is client-controlled and is never trusted. Set it to `0` if kelta-auth is directly exposed |
| `KELTA_AUTH_BOT_CHALLENGE_ENABLED` / `KELTA_AUTH_BOT_CHALLENGE_HMAC_KEY` | Optional — proof-of-work challenge on portal signup and magic-link request (default off). **Enabling it without an HMAC key fails startup on purpose**: the key must be identical on every auth pod, and a per-pod key would verify only on the pod that issued the challenge, silently failing a share of real signups. Also `KELTA_AUTH_BOT_CHALLENGE_MAX_NUMBER` (default `100000` — raise to make solving cost more) and `KELTA_AUTH_BOT_CHALLENGE_TTL_SECONDS` (default `600`) |
| `KELTA_WATCH_PROMOTABLE_SOURCES` | Optional — comma-separated sources a member may create a watch target for by posting `{source, externalId, name}` to `/api/watches` instead of a `targetId`. **Empty by default, which turns promotion off**, and it has to be opt-in: creating a target creates something the poller will be asked to poll, so an open door lets any caller holding `API_ACCESS` mint rows in another system's name. Note what the platform cannot check — that the external id is real or pollable. That belongs to whoever owns the catalog, and the client is expected to offer only things it knows are watchable; a bogus id costs a watch that never fires, which is why the audit reconciling targets against their source matters |
| `KELTA_MODULES_SIGNING_REQUIRED` | Whether a module JAR install requires a verified publisher signature (default `false` for back-compat). This matters more than most flags: `SandboxedModuleClassLoader` allows `java.*` wholesale, so an installed module runs arbitrary Java in the worker JVM. **Set `true`.** Trust anchors are per tenant — `POST /api/modules/signing-keys`, gated on `MANAGE_CREDENTIALS` — and a tenant may hold several active keys at once so rotation is additive rather than breaking. When `true`, a tenant with no active key cannot install a JAR at all; that is deliberate, because the alternative makes "retire every key" a way to turn verification into a no-op |
| `KELTA_MODULES_SIGNING_PUBLIC_KEY` | Optional, and **legacy** — an X.509/SPKI PEM public key trusted as an *additional* anchor for **every** tenant. Useful for operator-published modules, but it is platform-wide signing authority, which is the property per-tenant keys exist to remove; a warning is logged while it is set. Prefer per-tenant keys and unset this once they are in place. Also `KELTA_MODULES_SIGNING_ALGORITHM` (default `Ed25519`; `SHA256withRSA` and ECDSA supported) — per-tenant keys carry their own algorithm, so migrating algorithm is the same rolling operation as migrating key |
| `FLOW_RETENTION_DRY_RUN` | Flow/job execution-log pruning is **DESTRUCTIVE and dry-run by default (`true`)** — the sweep only logs what it *would* delete. Set `false` to arm, per environment, after reviewing a cycle of "WOULD delete" logs. Purged runs disappear from the flow-run observability UI |
| `FLOW_RETENTION_MAX_AGE_DAYS` | Retention window for terminal flow executions and job logs (default `60`). Also `FLOW_RETENTION_ENABLED`, `FLOW_RETENTION_BATCH_SIZE`, `FLOW_RETENTION_MAX_BATCHES`, `FLOW_RETENTION_POLL_INTERVAL_MS` |

## CI/CD

Pull requests run the full quality gate via GitHub Actions:

1. **Build & Test Java** — builds runtime modules, runs tests for gateway, worker, auth, ai, mcp
2. **Test Frontend** — lint, typecheck, format check, and test coverage for kelta-web
3. **Integration & E2E** — Testcontainers harness + Playwright against the docker-compose stack
4. **Quality Gate** — all triggered jobs must pass before merge

On merge to `main`, container images are built and pushed to `harbor.rzware.com`, then
deployed to Kubernetes via ArgoCD (with smoke test + auto-rollback). Full detail:
[`.claude/docs/ci-cd.md`](.claude/docs/ci-cd.md).

## License

Kelta Platform is licensed under AGPLv3. If you need to use Kelta under terms other
than AGPLv3, email licensing@rzware.com.
