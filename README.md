# Kelta Platform

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

This software is available under the GNU Affero General Public License v3.0 (AGPL-3.0-only). For commercial or closed-source deployments, contact cklinker@rzware.com.

Kelta is a platform for building dynamic, runtime-configurable enterprise applications. It provides a metadata-driven architecture where collections (tables), fields, validation rules, relationships, and workflows are all defined and managed at runtime — no redeployment required.

See the [public roadmap](https://www.kelta.io/roadmap) for what's shipped, in progress, and next, and the
[documentation](https://www.kelta.io/docs/) for guides and reference. The docs site is rendered by
`kelta-marketing` from `kelta-marketing/src/content/docs/**` plus the canonical `docs/authoring/*.md` and the
generated CLI docs, read in place — editing those files republishes the site. Preview locally with
`cd kelta-marketing && npm run dev` (search needs `npm run build && npm run preview`).

## Quickstart

Start a released Kelta stack, open the UI, and create your first collection. Nothing is
built: Compose pulls the version pinned by `KELTA_VERSION` in `.env` from `ghcr.io/kelta-io`,
so you need only Docker Compose v2.24+, `make` and `git` — no JDK, Maven or Node. Host ports
**5173** (UI) and **8080** (gateway) must be free — `lsof -i :5173 -i :8080` should print nothing.
They cannot be remapped today: the released UI bundle calls `http://localhost:8080` and the OAuth
redirect is `http://localhost:5173/auth/callback`.

```bash
git clone https://github.com/kelta-io/kelta.git
cd kelta
make setup                  # first time only: copies .env.example → .env, generates dev signing keys
docker compose up -d --wait # pulls + starts postgres, redis, nats, cerbos, mailpit, auth, worker, gateway, ui
```

To build this checkout from source instead, run `make up-jvm` — see
[From source](#from-source-contributors).

1. Open **http://localhost:5173/default/** and sign in as `admin@kelta.local` with the
   first-boot password kelta-auth prints once in its log, then choose your own:
   `docker compose logs --no-color kelta-auth | sed -n 's/.*Password: *\([A-Za-z0-9]*\).*/\1/p' | head -n 1`.
   Set `KELTA_BOOTSTRAP_ADMIN_PASSWORD` in `.env` before the first start to pick it instead. The
   `/default/` path is the tenant — the bare `http://localhost:5173` shows a *Tenant Required* page.
2. Go to **Setup → Data Model → Collections**, click **Create Collection**, fill in
   the wizard (Basics → Fields → Authorization → Review), then click **Create
   Collection** on the Review step.

That's it — a running, runtime-configurable Kelta instance with your first collection. See
[Local Development](#local-development) below for ports, debugging, and the full service list.

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

## Local Development

### Contributor prerequisites

To build, test or debug this checkout (the Quickstart needs none of this):

- Java 25 (GraalVM Community 25.0.2; see `.tool-versions`)
- Maven 3.9+
- Node.js 20.19+ (pinned by `kelta-ui/app`'s `engines`; on 18 its test suite cannot start)
- Docker & Docker Compose (v2.24+)
  - `make up` builds GraalVM native images and needs **~24 GB allocated to Docker**.
    With less, use `make up-jvm` — see [Native vs JVM images](#native-vs-jvm-images).

### From source (contributors)

The Quickstart runs a released version. To run *this checkout*, every `make up*` target layers
`docker-compose.build.yml` on `docker-compose.yml` and builds the Kelta services from source:

```bash
make setup   # first time only: copies .env, generates RSA JWK + AES key
make up-jvm  # builds + starts postgres, redis, nats, cerbos, auth, worker, gateway, ui
make seed    # waits for healthy stack, then prints credentials
```

`make up-jvm` builds the Java services as ordinary JVM images, which fit a default Docker
Desktop memory allocation and build in a few minutes. `make up` builds GraalVM native
images — what production runs — but needs **~24 GB allocated to Docker**; on a default
allocation it fails with `cannot allocate memory`, which is native-image, not a code error.
See [Native vs JVM images](#native-vs-jvm-images). Built images are named
`<project>-<service>`, never the `ghcr.io/kelta-io` release tags, so a build and a pulled
release never stand in for each other.

**Running a different release.** Set `KELTA_VERSION` in `.env` (the tags are listed on
`ghcr.io/kelta-io/kelta-gateway`) and run `docker compose up -d` again. The `ai` and `seed`
profiles are not released images; they need the from-source targets (`make up-jvm-ai`,
`make seed`). Moving an existing install to a newer release (backup, pull, migrations,
rollback) is covered in [UPGRADING.md](UPGRADING.md).

Platform admin (seeded by Flyway; kelta-auth replaces the seeded password on first boot):

| Field | Value |
|-------|-------|
| URL | http://localhost:5173/default/ |
| Email | `admin@kelta.local` |
| Password | `KELTA_BOOTSTRAP_ADMIN_PASSWORD` from `.env`, else the one printed once in `docker compose logs kelta-auth` (banner *Kelta platform admin initial password*); change it at first sign-in |
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
| Mailpit (SMTP UI) | :8025 | Default stack — catches outgoing email |

### Useful Makefile targets

```bash
make up-ai           # default stack + kelta-ai (needs ANTHROPIC_API_KEY in .env)
make up-full         # default + ai + tools
make up-telehealth   # default stack + LiveKit SFU (video visits, dev keys built in)
make rebuild SVC=kelta-worker   # rebuild + restart one service
make logs SVC=kelta-gateway     # tail logs
make down            # stop all containers
make reset           # wipe volumes and restart clean (native images)
make reset-jvm       # same, restarting with JVM images — use this if you run up-jvm

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
overlay (`docker-compose.jvm.yml`) swaps `build.dockerfile` and points the build at
public sources. Switching modes recreates the containers but keeps volumes, so your data
survives.

**Where `make up-jvm` downloads from.** Base images come from Docker Hub and Maven
dependencies from Maven Central. The `Dockerfile.jvm` files default to the project's own
pull-through caches (`harbor.rzware.com`, `nexus.rzware.com`), which is what CI and
production builds use; the JVM overlay overrides that with the `BASE_REGISTRY` and
`MAVEN_MIRROR` build args, so a local build neither needs those hosts to be reachable nor
downloads the whole dependency tree through them. Every other Dockerfile takes the same
args with the same homelab defaults, so to build one by hand outside the homelab pass the
public values:

| Build arg | Homelab default | Public value | Dockerfiles |
|-----------|-----------------|--------------|-------------|
| `BASE_REGISTRY` | `harbor.rzware.com/dockerhub-cache/library` | `docker.io/library` | all |
| `MAVEN_MIRROR` | `nexus` (copies `ci/maven-settings.xml`) | `central` | every Java image |
| `MAVEN_DIST_URL` | `https://nexus.rzware.com/repository/maven-public` | `https://repo.maven.apache.org/maven2` | native `kelta-{worker,auth,gateway}/Dockerfile`, `kelta-worker/Dockerfile.migrate` |
| `BUN_IMAGE` | `harbor.rzware.com/dockerhub-cache/oven/bun:1.2.19` | `oven/bun:1.2.19` | `kelta-cli-downloads/Dockerfile` |

```bash
docker build -f kelta-worker/Dockerfile \
  --build-arg BASE_REGISTRY=docker.io/library --build-arg MAVEN_MIRROR=central \
  --build-arg MAVEN_DIST_URL=https://repo.maven.apache.org/maven2 .
```

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
the tokens it issues (it validates `iss` against this exact value). The containerised gateway
fetches kelta-auth's signing keys over `AUTH_SERVICE_URL=http://kelta-auth:8081` rather than the
alias: Netty (4.2.14+) resolves `*.localhost` to loopback per RFC 6761 even inside a container.
The `.run/*.run.xml` configs use the same issuer; the kelta-auth one also sets `SERVER_PORT=8081`.
(Browsers and macOS resolve `*.localhost` themselves; a minimal Linux without
`nss-myhostname`/systemd-resolved may not — add `127.0.0.1 auth.localhost` to `/etc/hosts` there
for services you run from the IDE.)

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
| `KELTA_ENCRYPTION_KEY` | AES-256 key for envelope encryption (generated by `make gen-keys`). **Required by kelta-worker** — startup fails without it; optional for kelta-auth (federation off) |
| `KELTA_INTERNAL_TOKEN` | Shared secret for internal service calls (default: `dev-internal-token`). Set the **same value on kelta-worker and kelta-auth**: the worker also uses it to sign a user out of kelta-auth after an admin password reset or deactivation |
| `KELTA_AUTH_INTERNAL_URL` | Optional (worker) — kelta-auth's in-cluster base URL for service-to-service calls (not the public issuer). Default `http://kelta-auth:8081`, the compose service; in Kubernetes set it to the kelta-auth Service URL. Unreachable ⇒ the admin action still succeeds and the worker logs an ERROR |
| `ANTHROPIC_API_KEY` | Required only when running kelta-ai (`--profile ai`) |
| `KELTA_TELEHEALTH_VISIT_SECRET` | HMAC secret for emailed visit links (generated by `make gen-keys`). **The worker refuses to start without it** — a visit token is exchangeable for a portal login, so there is no safe default. Compose sets a local-only literal; anything deployed must generate its own |
| `CAMPAIGN_TRACKING_SECRET` | HMAC secret for campaign open-pixel, click-redirect and unsubscribe links (generated by `make gen-keys`). **Also fails startup when unset.** Note rotating it invalidates tracking and unsubscribe links in already-delivered email, which never expire |
| `KELTA_LIVEKIT_URL` / `KELTA_LIVEKIT_API_KEY` / `KELTA_LIVEKIT_API_SECRET` | LiveKit SFU for video visits (`--profile telehealth`; dev defaults match the compose service) |
| `KELTA_PUSH_VAPID_PUBLIC_KEY` / `KELTA_PUSH_VAPID_PRIVATE_KEY` / `KELTA_PUSH_VAPID_SUBJECT` | Optional — browser Web Push (VAPID). Run `make gen-vapid` to write a dev pair into `.env`. The public key is a base64url uncompressed P-256 point, the private key its raw base64url scalar; the subject must be a `mailto:` or `https:` URI (Safari rejects anything else). Absent ⇒ `WebPushProvider` is not registered and `GET /api/devices/vapid-public-key` 404s; native push is unaffected either way. **Rotating the pair invalidates every existing browser subscription** — clients must re-subscribe |
| `MAXMIND_LICENSE_KEY` | Optional — enables the gateway's automatic GeoLite2 IP-geolocation downloads (free key from a MaxMind account). Without it geo enrichment is silently inactive; the mmdb persists in the `geoip-data` volume |
| `RATE_LIMIT_EXEMPT_CIDRS` | Optional — comma-separated IPs/CIDR ranges exempt from **both** gateway rate limiters (per-IP and per-tenant). For trusted infrastructure whose traffic is legitimately bursty: uptime probes, in-cluster callers, a payment processor's webhook egress. A bare address means a single host; invalid entries are logged and skipped. Empty (the default) exempts nobody |
| `KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS` | Optional (worker **and** kelta-auth — set the same value on both) — comma-separated CIDRs or bare IPs that may be fetched even though they are not public. Tenant-supplied URLs — OIDC discovery of a provider's issuer (worker on save, kelta-auth at login), kelta-auth's calls to a provider's token, user-info and JWKS endpoints during login, OpenAPI spec import/validate from a `sourceUrl`, flow `HTTP_CALLOUT`, `CALL_API` and `OUTBOUND_MESSAGE` steps, external-rest collections, OAuth 2.0 credential token URLs and Web Push endpoints — are refused when they resolve, directly or through a redirect, to loopback, private, link-local/metadata, CGNAT or IPv6 unique-local addresses; list a range here only if a legitimate target lives there, e.g. a self-hosted identity provider or an in-cluster service a flow calls. Compose passes it through from `.env`. A malformed entry fails startup. Empty (default) allows none |
| `RATE_LIMIT_IP_PATHS` | Optional — per-IP budgets for unauthenticated **gateway** paths as `<path-prefix>=<per-minute>`, matched by longest prefix, each prefix its own bucket. Default `/actuator/health=100,/api/modules/webhooks=300,/api/webhooks=300,/api/webhooks/mail=600,/api/webhooks/ses=600`. Redis-backed, so the budget is the fleet's, not each replica's. `/portal/**` is **not** here — it never transits the gateway; see `KELTA_AUTH_RATE_LIMIT_IP_PATHS` |
| `RATE_LIMIT_USER_SHARE` | Optional — fraction of a tenant's rate-limit window any single member may consume (default `0.9`). Bounds one member (or one stolen PAT) from consuming the **whole** tenant window; it is not a fair-share divider, and the default is deliberately close to 1 because one admin, bulk import, or integration PAT legitimately *is* most of a tenant. Tenants with many members should tighten it. `1.0` turns the per-user window off; an out-of-range value falls back to the default rather than disabling it |
| `KELTA_AUTH_RATE_LIMIT_IP_PATHS` | Optional — per-IP budgets for kelta-auth's public portal paths, same `<prefix>=<per-minute>` grammar. Default `/portal/api/signup=5,/portal/api/login/request=10,/portal/api/challenge=30,/portal/login=10`. Also `KELTA_AUTH_RATE_LIMIT_TRUSTED_PROXY_COUNT` (default `1`) — how many `X-Forwarded-For` hops to count back from the right to find the client; the leftmost entry is client-controlled and is never trusted. Set it to `0` if kelta-auth is directly exposed |
| `KELTA_AUTH_RATE_LIMIT_PASSWORD_RESET_PATHS` | Optional — per-IP budgets for kelta-auth's signed-out password reset (pages and JSON), same grammar, a separate list so overriding the portal paths keeps it. Default `/auth/password/reset-request=5,/auth/password/reset=10,/forgot-password=10,/reset-password=20`; empty disables |
| `KELTA_SECURITY_TRUSTED_PROXIES` | Optional (gateway + kelta-auth) — comma-separated CIDRs or bare IPs of the proxies in front of the service (the ingress pod CIDR). When set, `X-Forwarded-For` / `X-Real-IP` are honoured only from those peers and the client is the right-most untrusted hop; this is the IP used for audit, `login_history.source_ip`, geo, rate limits and tenant IP allowlists. Empty (default) keeps the legacy, spoofable client IP for audit, geo and rate limits (`IP_ALLOWLIST_TRUST_XFF`, `KELTA_AUTH_RATE_LIMIT_TRUSTED_PROXY_COUNT`), while tenant IP allowlists judge the socket peer only — so it is **required behind an ingress** if tenants use IP allowlists |
| `KELTA_AUTH_AUTHORIZATION_RETENTION_ENABLED` | Optional — kelta-auth deletes `oauth2_authorization` rows whose every token expired more than a grace period ago (Spring Authorization Server never deletes them). Default `true`. Also `KELTA_AUTH_AUTHORIZATION_RETENTION_INTERVAL` (default `1h`, between sweeps; first sweep 5 min after start), `KELTA_AUTH_AUTHORIZATION_RETENTION_GRACE` (default `1d`), `KELTA_AUTH_AUTHORIZATION_RETENTION_BATCH_SIZE` (default `500` rows per statement) and `KELTA_AUTH_AUTHORIZATION_RETENTION_MAX_BATCHES` (default `100` statements per sweep). Every replica sweeps; batches use `FOR UPDATE SKIP LOCKED`, so they never wait on each other |
| `KELTA_AUTH_BOT_CHALLENGE_ENABLED` / `KELTA_AUTH_BOT_CHALLENGE_HMAC_KEY` | Optional — proof-of-work challenge on portal signup and magic-link request (default off). **Enabling it without an HMAC key fails startup on purpose**: the key must be identical on every auth pod, and a per-pod key would verify only on the pod that issued the challenge, silently failing a share of real signups. Also `KELTA_AUTH_BOT_CHALLENGE_MAX_NUMBER` (default `100000` — raise to make solving cost more) and `KELTA_AUTH_BOT_CHALLENGE_TTL_SECONDS` (default `600`) |
| `KELTA_AUTH_SESSION_TIMEOUT` / `KELTA_AUTH_SESSION_AUTHENTICATED_TIMEOUT` | Optional — idle timeouts of kelta-auth's HTTP sessions, which are stored in Redis (shared by every kelta-auth replica, kept across restarts). `KELTA_AUTH_SESSION_TIMEOUT` (default `30m`) applies until the browser has signed in, `KELTA_AUTH_SESSION_AUTHENTICATED_TIMEOUT` (default `8h`) after. Also `KELTA_AUTH_SESSION_COOKIE_NAME` (default `kelta_auth_session`); `KELTA_AUTH_SESSION_COOKIE_SAME_SITE` (default `None`, which SP-initiated SAML sign-in needs; `Lax`, `Strict` or blank for no attribute — a cookie sent without `Secure` gets `Lax` in place of `None`); `KELTA_AUTH_SESSION_COOKIE_SECURE` (default `auto`: `Secure` when the browser used https — the proxy's `X-Forwarded-Proto`/`Forwarded: proto=`, else the request's own scheme — so plain-http local development and plain-http LAN installs can sign in; `always` behind a proxy that terminates TLS without sending `X-Forwarded-Proto`; `never`; also applies to the `kelta_session` SSO cookie); `KELTA_AUTH_SESSION_REDIS_CONFIGURE_ACTION` (default `notify-keyspace-events`: kelta-auth enables Redis keyspace notifications with `CONFIG SET` at startup; set `none` where `CONFIG` is not permitted and set `notify-keyspace-events` to include `Egx` on the server) |
| `KELTA_WATCH_PROMOTABLE_SOURCES` | Optional — comma-separated sources a member may create a watch target for by posting `{source, externalId, name}` to `/api/watches` instead of a `targetId`. **Empty by default, which turns promotion off**, and it has to be opt-in: creating a target creates something the poller will be asked to poll, so an open door lets any caller holding `API_ACCESS` mint rows in another system's name. Note what the platform cannot check — that the external id is real or pollable. That belongs to whoever owns the catalog, and the client is expected to offer only things it knows are watchable; a bogus id costs a watch that never fires, which is why the audit reconciling targets against their source matters |
| `KELTA_MODULES_SIGNING_REQUIRED` | Whether a module JAR install requires a verified publisher signature (default `false` for back-compat). This matters more than most flags: `SandboxedModuleClassLoader` allows `java.*` wholesale, so an installed module runs arbitrary Java in the worker JVM. **Set `true`.** Trust anchors are per tenant — `POST /api/modules/signing-keys`, gated on `MANAGE_CREDENTIALS` — and a tenant may hold several active keys at once so rotation is additive rather than breaking. When `true`, a tenant with no active key cannot install a JAR at all; that is deliberate, because the alternative makes "retire every key" a way to turn verification into a no-op |
| `KELTA_MODULES_SIGNING_PUBLIC_KEY` | Optional, and **legacy** — an X.509/SPKI PEM public key trusted as an *additional* anchor for **every** tenant. Useful for operator-published modules, but it is platform-wide signing authority, which is the property per-tenant keys exist to remove; a warning is logged while it is set. Prefer per-tenant keys and unset this once they are in place. Also `KELTA_MODULES_SIGNING_ALGORITHM` (default `Ed25519`; `SHA256withRSA` and ECDSA supported) — per-tenant keys carry their own algorithm, so migrating algorithm is the same rolling operation as migrating key |
| `KELTA_MODULES_LOAD_RETRY_*` | Optional — retry of runtime-module loads that failed for an **infrastructure** reason (object storage or the database unreachable). Such a load fails closed on that worker pod but is *not* quarantined; each pod retries it. `KELTA_MODULES_LOAD_RETRY_ENABLED` (default `true`), `…_POLL_INTERVAL_MS` (default `15000`, how often a pod checks for due retries), `…_INITIAL_BACKOFF_MS` (default `30000`), `…_MAX_BACKOFF_MS` (default `600000`), `…_ESCALATE_AFTER_ATTEMPTS` (default `8`, when a still-failing load is logged at ERROR again). There is deliberately no give-up. Alert on the `kelta_worker_module_load_retry_pending` gauge |
| `KELTA_MODULE_SIGNING_KEY` | Worker — the key the platform signs every request to an **external module** with (an ES256 assertion, `specs/module-platform/9-external-modules.md` §3.2): one or more PKCS#8 PEM P-256 private keys (`openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256`; a SEC1 `BEGIN EC PRIVATE KEY` is refused with the conversion command). Literal `\n` sequences are accepted for env files. The **first** key signs; every key's public half is served at `GET /api/modules/.well-known/jwks.json`, so rotate by putting the new key first and dropping the old one after modules have refreshed their JWKS cache (5 min). Blank (default) = the JWKS is empty and an external-module plan is blocked with `SIGNING_NOT_CONFIGURED`. A secret: keep it in the sealed secret, never in git |
| `KELTA_MODULE_ISSUER` | Worker — the assertions' `iss`; a module fetches `{issuer}/.well-known/jwks.json`. Default `${EXTERNAL_BASE_URL}/api/modules`; set it when modules reach the platform at a different public URL |
| `KELTA_MODULE_ALLOWED_HOSTS` | Worker — module hosts that may use `http` and resolve to private addresses (in-cluster modules), for **module traffic only**: comma-separated hostnames or `*.suffix` patterns. Public `https` module hosts need no entry; every other base URL is refused. Re-checked on every request; module calls never follow redirects. **Never widen `KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS` for a module instead** — that variable also opens flow `HTTP_CALLOUT`, `CALL_API` and outbound messages to the same ranges. Empty (default) allows none |
| `KELTA_VERSION` | Worker (optional) — the running release (e.g. `0.2.0`), compared with an external module's `minPlatformVersion` at plan time (`PLATFORM_TOO_OLD` blocks). Unset = unknown: the plan reports `platformCheck: UNKNOWN` and does not block. Compose already uses this name for the image tag; pass it into the worker's environment to enable the check |
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
