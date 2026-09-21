---
title: Configuration reference
description: Every environment variable by service — shared secrets, gateway, worker, auth, AI, MCP and the UI build — with defaults and what each one does.
section: deploy
order: 40
---

Services are configured entirely through environment variables. Defaults shown are the shipped ones; "—" means
no default (the service fails to start, or the feature stays off). Defaults that name a local host
(`localhost`, a compose service) are placeholders for development.

## Shared secrets and identity

| Variable | Used by | Meaning |
|---|---|---|
| `JWK_SET` | auth | RSA JWK set used to sign JWTs. No default — generate with `make gen-keys`. |
| `KELTA_ENCRYPTION_KEY` | worker, auth | AES-256 master key for envelope encryption (`ENCRYPTED` fields, stored secrets). No default. Losing it makes encrypted data unrecoverable. |
| `KELTA_INTERNAL_TOKEN` | worker, auth, ai | Shared secret for service-to-service calls under `/internal/**`. |
| `KELTA_AUTH_ISSUER_URI` | gateway, worker, auth, ai | The OIDC issuer (`https://auth.example.com`). Must be identical everywhere. The gateway also honours the legacy `OIDC_ISSUER_URI` / `JWT_ISSUER_URI`. |
| `HOSTNAME` | all | Becomes `service.instance.id` for metrics; the pod name in Kubernetes. Leave unset. |
| `LOG_LEVEL`, `SECURITY_LOG_LEVEL` | all | Root and security-logger levels (`INFO`, `WARN`). |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | gateway, worker, auth | OTLP/HTTP traces endpoint (`…/v1/traces`). |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | all | OTLP/HTTP metrics endpoint (`…/v1/metrics`). |
| `OTEL_TRACES_SAMPLER_ARG` | all | Trace sampling ratio (default `1.0`). |
| `SERVER_PORT` | Java services | HTTP port (default `8080`). |

## Gateway

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Rate limits, PAT cache and revocation, identity cache |
| `REDIS_POOL_MAX_ACTIVE` / `MAX_IDLE` / `MIN_IDLE` / `MAX_WAIT_MS` | `100` / `50` / `10` / `2000` | Connection pool |
| `NATS_URL` | `nats://localhost:4222` | Event bus |
| `WORKER_SERVICE_URL`, `AI_SERVICE_URL` | in-cluster service URLs | Upstreams |
| `CERBOS_HOST`, `CERBOS_GRPC_PORT` | —, `3593` | Policy decision point |
| `CORS_ALLOWED_ORIGIN_PATTERN` | — (required) | Allowed browser origins; `*` is accepted but logged as a warning |
| `TENANT_SLUG_ENABLED` | `true` | Resolve tenants from the first path segment |
| `TENANT_SLUG_REQUIRE_PREFIX` | `false` | Reject `/api` requests with no slug or header |
| `PERMISSIONS_ENABLED`, `PERMISSIONS_CACHE_TTL` | `true`, `5` (min) | Cerbos route checks and the identity/permission cache |
| `IP_ALLOWLIST_ENABLED`, `IP_ALLOWLIST_TRUST_XFF` | `true`, `true` | Tenant IP allowlist feature and whether forwarded headers count |
| `RATE_LIMIT_IP_PATHS` | `/actuator/health=100,/api/modules/webhooks=300,/api/webhooks/mail=600` | Per-IP per-minute budgets for unauthenticated prefixes, longest-prefix match |
| `RATE_LIMIT_EXEMPT_CIDRS` | — | Addresses exempt from **both** limiters; keep to narrow trusted ranges |
| `RATE_LIMIT_USER_SHARE` | `0.9` | Fraction of the tenant window one member may use; `1.0` disables |
| `GEO_ENABLED`, `GEO_DB_PATH`, `GEO_REFRESH_MS`, `GEO_STALE_AFTER_DAYS`, `GEO_DOWNLOAD_TIMEOUT_MS`, `MAXMIND_LICENSE_KEY` | `true`, `/data/geoip/GeoLite2-City.mmdb`, 1 day, `7`, `120000`, — | IP geolocation; without a MaxMind key enrichment is silently inactive |
| `GATEWAY_HTTPCLIENT_MAX_CONNECTIONS` / `MAX_IDLE_TIME` / `ACQUIRE_TIMEOUT_MS` | `2000` / `30s` / `5000` | Upstream HTTP client |

## Worker

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `USERNAME` / `PASSWORD` | local | PostgreSQL |
| `DB_POOL_MAX` / `MIN_IDLE` / `CONNECTION_TIMEOUT_MS` / `IDLE_TIMEOUT_MS` / `MAX_LIFETIME_MS` / `LEAK_DETECTION_MS` | `30` / `5` / `5000` / `600000` / `1800000` / `30000` | Connection pool |
| `SPRING_FLYWAY_ENABLED` | `false` | Run migrations at startup (compose) — Kubernetes uses the `migrate` profile |
| `KELTA_SCHEMA_BOOTSTRAP_ENABLED` | `false` | Apply collection DDL at pod startup; leave off where a migrate Job runs |
| `SPRING_DATA_REDIS_HOST` / `PORT` | local | Redis |
| `NATS_URL` | local | Event bus |
| `CERBOS_HOST`, `CERBOS_GRPC_PORT`, `CERBOS_CB_THRESHOLD`, `CERBOS_CB_COOLDOWN_SECONDS`, `CERBOS_SEED_FORCE` | —, `3593`, `3`, `10`, `false` | PDP, its fail-closed circuit breaker, force a full policy seed on start |
| `EXTERNAL_BASE_URL` | `http://localhost:8080` | Public API base used in links (tracking, mailbox) |
| `SCHEDULER_ENABLED`, `SCHEDULER_POLL_INTERVAL_MS` | `true`, `60000` | Scheduled-job executor |
| `REQUEST_LOGGING_ENABLED` | `true` | Tenant request log |
| `WORKER_ID` | — | Optional stable worker identifier |
| `EMAIL_ENABLED`, `EMAIL_FROM_ADDRESS`, `EMAIL_FROM_NAME` | `true`, —, `Kelta Platform` | Outbound email |
| `SMTP_HOST` / `PORT` / `USERNAME` / `PASSWORD` / `AUTH` / `STARTTLS` | `localhost` / `1025` / — / — / `false` / `false` | Platform SMTP |
| `KELTA_SMS_PROVIDER` | `log` | `log` or `twilio` |
| `TWILIO_ACCOUNT_SID`, `TWILIO_KEY_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` | — | Twilio ([Messaging](/docs/platform/messaging/)) |
| `KELTA_PUSH_VAPID_PUBLIC_KEY` / `PRIVATE_KEY` / `SUBJECT` | — | Browser Web Push; absent ⇒ provider not registered |
| `kelta.push.provider` (property) | `log` | Mobile push: `log`, `fcm`, `apns` (with `kelta.push.apns.*`) |
| `KELTA_S3_ENABLED`, `KELTA_S3_ENDPOINT`, `KELTA_S3_PUBLIC_ENDPOINT`, `KELTA_S3_REGION`, `KELTA_S3_BUCKET`, `KELTA_S3_ACCESS_KEY`, `KELTA_S3_SECRET_KEY`, `KELTA_S3_URL_EXPIRY_MINUTES` | `false`, …, `15` | Attachments and module JARs; the public endpoint is what presigned URLs use |
| `SVIX_SERVER_URL`, `SVIX_PUBLIC_URL`, `SVIX_AUTH_TOKEN` | — | Outbound webhooks |
| `SUPERSET_URL`, `SUPERSET_PUBLIC_URL`, `SUPERSET_ADMIN_USERNAME`, `SUPERSET_ADMIN_PASSWORD` | — | Embedded BI |
| `FLOW_RETENTION_ENABLED` / `DRY_RUN` / `MAX_AGE_DAYS` / `BATCH_SIZE` / `MAX_BATCHES` / `POLL_INTERVAL_MS` | `true` / **`true`** / `60` / `1000` / `20` / hourly | Flow-log pruning; dry-run until you set `FLOW_RETENTION_DRY_RUN=false` |
| `KELTA_MODULES_SIGNING_REQUIRED` | `false` | **Set `true`** in production ([Modules](/docs/platform/modules/)) |
| `KELTA_MODULES_SIGNING_PUBLIC_KEY`, `KELTA_MODULES_SIGNING_ALGORITHM` | —, `Ed25519` | Legacy platform-wide signing anchor (prefer per-tenant keys) |
| `KELTA_MODULES_STUB_MODE` | `false` | Development only |
| `LOKI_URL`, `TEMPO_URL`, `MIMIR_URL` | — | Backends the console's monitoring pages query for logs, traces and metrics |
| `CAMPAIGN_*`, `KELTA_MAILBOX_*`, `KELTA_TELEHEALTH_VISIT_SECRET`, `KELTA_LIVEKIT_*`, `TELEHEALTH_REMINDER*`, `KELTA_WATCH_PROMOTABLE_SOURCES` | — | Optional feature areas (campaigns, support mailboxes, telehealth video, availability watches). Some HMAC secrets fail startup when unset — see `.env.example` |

## Auth

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`, `DB_POOL_*` | local, `DB_POOL_MAX` `15` | PostgreSQL |
| `REDIS_HOST` / `REDIS_PORT` | local | Sessions and caches |
| `WORKER_SERVICE_URL` | local | Internal user lookup |
| `UI_BASE_URL` | `http://localhost:5173` | Where to send users after login |
| `COOKIE_DOMAIN` | — | Session cookie domain |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | |
| `DIRECT_LOGIN_ENABLED` | `false` | Password grant for bootstrap/tests — keep `false` in production |
| `SUPERSET_CLIENT_ID` / `CLIENT_SECRET` / `REDIRECT_URI` | — | OAuth client for embedded BI |
| `KELTA_AUTH_RATE_LIMIT_IP_PATHS` | `/portal/api/signup=5,/portal/api/login/request=10,/portal/api/challenge=30,/portal/login=10` | Per-IP budgets on public portal paths |
| `KELTA_AUTH_RATE_LIMIT_TRUSTED_PROXY_COUNT` | `1` | `X-Forwarded-For` hops to trust from the right; `0` when directly exposed |
| `KELTA_AUTH_BOT_CHALLENGE_ENABLED`, `_HMAC_KEY`, `_MAX_NUMBER`, `_TTL_SECONDS` | `false`, —, `100000`, `600` | Proof-of-work challenge on portal signup; enabling without a key fails startup on purpose |
| `kelta.auth.saml.sp-signing-certificate` / `sp-signing-private-key` (properties) | — | Platform SP key pair for signed SAML requests |

## AI

| Variable | Default | Meaning |
|---|---|---|
| `ANTHROPIC_API_KEY` | — (required) | |
| `AI_DEFAULT_MODEL`, `AI_DEFAULT_MAX_TOKENS`, `AI_DEFAULT_TEMPERATURE` | `claude-sonnet-5`, `32768`, `0.7` | Defaults a tenant can override |
| `AI_RATE_LIMIT_ENABLED`, `AI_RATE_LIMIT_REQUESTS_PER_MINUTE` | `true`, `60` | Per-user |
| `AI_SSE_TIMEOUT_MS` | `300000` | Streaming timeout |
| `SPRING_DATASOURCE_*`, `DB_POOL_*`, `SPRING_DATA_REDIS_*`, `WORKER_SERVICE_URL` | local | |

## MCP

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | in-cluster gateway | Where tool calls go |
| `MCP_SESSION_TTL_MINUTES` | `30` | |
| `MCP_TOOL_TIMEOUT_MS` | `60000` | Per tool call |

## UI (build-time)

`VITE_API_BASE_URL` (the gateway origin — required), `VITE_APP_VERSION`, `VITE_OTEL_ENDPOINT`,
`VITE_OTEL_SERVICE_NAME`, `VITE_OTEL_SERVICE_NAMESPACE`, `VITE_OTEL_DEPLOYMENT_ENVIRONMENT`. These are baked into
the static bundle when the image is built.

## Generating secrets

```bash
make gen-keys            # JWK_SET, KELTA_ENCRYPTION_KEY and the HMAC secrets into .env
make gen-vapid           # Web Push key pair
openssl rand -base64 32  # any single HMAC key
```
