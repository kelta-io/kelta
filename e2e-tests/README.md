# Kelta E2E Tests

Playwright tests that exercise the platform end-to-end against a running Kelta stack. Runs in CI on every PR via the `e2e` job in [.github/workflows/ci.yml](../.github/workflows/ci.yml), and locally against your docker-compose stack.

## Quick start (local)

From the repo root:

```bash
# 1. One-time setup (idempotent)
make setup

# 2. Start the stack with the same JVM-mode images CI uses (~5-10 min cold)
docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --wait

# 3. Seed + sanity-check
docker compose --profile seed run --rm kelta-bootstrap

# 4. Run e2e tests
cd e2e-tests
cp .env.local.example .env.local   # one-time
npm ci
npx playwright install --with-deps chromium
CI=1 npx playwright test
```

On a cold start expect ~15-20 min (image builds dominate); warm runs are ~5-10 min. `make up` also works locally but builds the native-image stack which adds ~20 min to first build.

## Configuration

The runner reads `e2e-tests/.env.local` if present, then `e2e-tests/.env`. Point at your docker-compose stack:

```bash
cp e2e-tests/.env.local.example e2e-tests/.env.local
```

| Variable | Local default | Notes |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:5173` | UI |
| `E2E_API_BASE_URL` | `http://localhost:8080` | Gateway |
| `E2E_AUTH_BASE_URL` | `http://localhost:8081` | Auth |
| `E2E_AUTH_DIRECT_LOGIN_URL` | `http://localhost:8081` | When set, [helpers/direct-login.ts](helpers/direct-login.ts) skips OIDC and POSTs to `/auth/direct-login` for tokens. Requires `DIRECT_LOGIN_ENABLED=true` on `kelta-auth` (set in [docker-compose.yml](../docker-compose.yml)). |
| `E2E_BROWSER_HOST_RULES` | *(unset)* | Chromium `--host-resolver-rules`, e.g. `MAP auth.localhost kelta-auth`. Only needed when the browser runs in a container beside the stack (CI): Chromium resolves every `*.localhost` name to its own loopback, so the local issuer `http://auth.localhost:8081` must be pointed at the kelta-auth container. |
| `E2E_TENANT_SLUG` | `default` | Seeded by Flyway `V102__seed_default_admin_users.sql` |
| `E2E_TEST_USERNAME` | `admin` | Seeded by V102 |
| `E2E_TEST_PASSWORD` | `password` | Seeded by V102 |

## Running subsets

```bash
cd e2e-tests
npm run test:auth        # tests/auth/
npm run test:end-user    # tests/end-user/
npm run test:admin       # tests/admin/
npm run test:journeys    # tests/journeys/
npm run test:ui          # interactive UI mode for debugging
npm run test:debug       # step through with the Playwright inspector
```

Single test: `npx playwright test tests/end-user/home.spec.ts -g "renders home"`

## Artifacts

After a run, look in:

- `playwright-report/index.html` — open in a browser for a visual report
- `test-results/` — per-test traces, screenshots, videos on failure
- `test-results/junit.xml` — for CI consumption (only written when `CI=1`)

`npm run report` opens the HTML report.

## Cleanup

```bash
# From the repo root
docker compose -f docker-compose.yml -f docker-compose.ci.yml \
  --profile ai --profile tools --profile observability --profile seed down -v
rm -rf e2e-tests/test-results e2e-tests/playwright-report \
       e2e-tests/auth/storage-state.json e2e-tests/auth/session-tokens.json
```

## CI

The `e2e` job in [.github/workflows/ci.yml](../.github/workflows/ci.yml) runs the same loop:

1. `docker buildx bake -f docker-compose.yml -f docker-compose.ci.yml` (JVM variants via GHA cache)
2. `docker compose ... up -d --wait` waits for healthchecks
3. `docker compose --profile seed run --rm kelta-bootstrap`
4. `npx playwright test`
5. Uploads `playwright-report/`, `test-results/`, and per-service logs as `e2e-report` artifact

The job is wired into `quality-gate` and **blocks merge** when it fails. To triage a flake, add `test.fixme(...)` with an issue link — do not skip the gate.

## Auth flow

`auth.setup.ts` runs once before all chromium tests and stores cookies + sessionStorage tokens. It prefers direct login (`E2E_AUTH_DIRECT_LOGIN_URL`); if absent or it fails, it falls back to browser-based OIDC against Authentik (requires `E2E_AUTHENTIK_URL` + credentials).

The `chromium` project depends on `auth-setup`, so individual specs start signed-in. State files: `auth/storage-state.json` and `auth/session-tokens.json` (gitignored).

## Common issues

- **`direct-login returned 404`** — `kelta-auth` was started without `DIRECT_LOGIN_ENABLED=true`. Recheck `docker-compose.yml` env block on `kelta-auth`.
- **`waitForURL` timeout to `/app`** — the stack is up but `kelta-worker` Flyway migrations didn't finish. Run `docker compose logs kelta-worker | grep -i flyway` to confirm.
- **Storage state missing** — the `auth-setup` project failed. Run `npx playwright test --project=auth-setup --headed` to debug.
- **Different admin password** — V102's BCrypt hash is for the literal string `password`. If you changed it on first login locally, either reset via `make reset` (wipes the DB) or update `E2E_TEST_PASSWORD` in `.env.local`.
