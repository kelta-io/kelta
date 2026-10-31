# Load tests (k6)

[k6](https://grafana.com/docs/k6/) scripts that put a measured request rate on a running Kelta
stack and report latency per operation. They answer "how much can this deployment carry" with
numbers instead of estimates.

`records.js` drives the record API, the hot path of every tenant: it creates a throwaway
collection (four fields), seeds it, then runs a weighted mix of reads and writes through the
gateway. When the run ends it force-deletes the collection.

| Operation | Default weight | Request |
|-----------|---------------:|---------|
| `list` | 35 | `GET /api/{collection}?page[size]=20&page[number]=1..5&sort=-createdAt` |
| `filter` | 15 | `GET /api/{collection}?filter[status][eq]=…&filter[amount][gte]=…&page[size]=50` |
| `get` | 30 | `GET /api/{collection}/{id}` (a seeded record) |
| `create` | 10 | `POST /api/{collection}` |
| `update` | 8 | `PATCH /api/{collection}/{id}` (a seeded record) |
| `delete` | 2 | `DELETE /api/{collection}/{id}` (only records this VU created; with none yet it creates one, or reads when `create` is 0) |

## Before you run it

- **Never point it at production.** Use a local stack (`make up`), staging, or a dedicated
  load-test environment. `LOAD_BASE_URL` has no default, so a run never targets anything by
  accident.
- **Use a dedicated tenant with a raised rate limit.** The gateway gives a tenant a window of
  `floor(apiCallsPerDay / 288) * 5` requests per 5 minutes, and one user (one token) at most
  `round(window * 0.9)` of it (`kelta.gateway.rate-limit.user-share`). On the PROFESSIONAL
  default (100,000 a day) a load-test token gets about 5.2 req/s, so a real run is mostly 429s.
  - Raise it by setting `apiCallsPerDay` in the tenant's limits override:
    `PUT /api/governor-limits` with the tenant's current overrides plus, for example,
    `"apiCallsPerDay": 100000000`. The body **replaces** the whole override object, so include
    any overrides set before (on a dedicated tenant, often just `{"apiCallsPerDay": N}`). Do not
    copy `data.attributes.limits` from `GET /api/governor-limits`: that is the tier defaults merged
    in, and storing it pins every default as an override. This needs `MANAGE_TENANTS`,
    which tenant admins do not hold: it is a platform-operator step.
  - Changing the tier (`PUT /api/governor-limits/tier`) does **not** work: the gateway reads only
    the override, not the tier (`.claude/docs/concerns.md`).
  - Before it starts, the script reads the window the gateway actually enforces (the
    `X-RateLimit-Limit` header) and refuses a plan above 90% of this token's share. Set
    `LOAD_ALLOW_THROTTLE=true` to run anyway, for example to test the limiter itself.
  - Setup's requests (about `LOAD_SEED_RECORDS` + 30) count against the same window.
- **Know the second ceiling.** Each worker pod admits at most
  `kelta.worker.tenant-concurrency-limit` (default 10) in-flight requests per tenant and
  answers the rest with `503 TENANT_CONCURRENCY_LIMIT`. At 50 ms a request, 10 permits carry
  about 200 req/s per pod. The run counts these 503s separately as `concurrency_limited`.
  Raise the limit on a load-test environment if you want to measure past it.
- **Get a token.** Create a personal access token (`klt_…`) on the admin UI's API tokens page,
  or with `kelta auth login` (a browser sign-in that mints one), for a user with the System
  Administrator profile. The run needs `API_ACCESS`, `CUSTOMIZE_APPLICATION` (to create and
  delete the collection), `VIEW_ALL_DATA`/`MODIFY_ALL_DATA` (records in a collection no profile
  has object permissions on yet) and `VIEW_SETUP` (the preflight's governor-limits read).
- Install k6 1.x (`brew install k6`, or see the k6 docs).

## Running

```bash
cd load-tests
export LOAD_BASE_URL=http://localhost:8080   # the gateway
export LOAD_TENANT=loadtest                  # tenant slug
export LOAD_API_TOKEN=klt_...

k6 run records.js                                         # smoke: 2 req/s for 1 minute
LOAD_PROFILE=load LOAD_RPS=50 k6 run records.js           # ramp to 50 req/s, hold 10 minutes
LOAD_PROFILE=stress LOAD_MAX_RPS=500 k6 run records.js    # step up to 500 req/s to find the knee
LOAD_PROFILE=soak LOAD_RPS=30 LOAD_HOLD=2h k6 run records.js
```

Add `--summary-export=summary.json` or `--out json=results.json` to keep the numbers; for live
dashboards, `K6_WEB_DASHBOARD=true k6 run …` serves one on `localhost:5665`.

### Settings

Every setting uses the `LOAD_` prefix. Do not use `K6_`: k6 reads `K6_*` variables as its own
options (`K6_DURATION`, `K6_RPS`, `K6_VUS` …), and they would replace the scenario below.

| Variable | Default | Meaning |
|----------|---------|---------|
| `LOAD_BASE_URL` | — (required) | Gateway origin |
| `LOAD_TENANT` | — (required) | Tenant slug; requests go to `{base}/{tenant}/api/…` |
| `LOAD_API_TOKEN` | — (required) | Bearer token: a PAT (`klt_…`) or an access token |
| `LOAD_PROFILE` | `smoke` | `smoke`, `load`, `stress` or `soak` |
| `LOAD_RPS` | `20` | Target req/s for `load` and `soak` |
| `LOAD_MAX_RPS` | `5 × LOAD_RPS` | Peak req/s for `stress` |
| `LOAD_HOLD` | per profile | How long to hold each level (`smoke` 1m, `load` 10m, `stress` 2m per step, `soak` 1h) |
| `LOAD_MIX` | see table above | Weights, e.g. `list=60,get=40,create=0,update=0,delete=0,filter=0` for read-only |
| `LOAD_SEED_RECORDS` | `200` | Records created before the run (at least 1) |
| `LOAD_P95_<OP>` | list 500, filter 600, get 300, writes 500 | p95 budget in ms per operation, e.g. `LOAD_P95_LIST=800` |
| `LOAD_USER_SHARE` | `0.9` | The deployment's `kelta.gateway.rate-limit.user-share`, for the preflight |
| `LOAD_ALLOW_THROTTLE` | `false` | Run even when the plan exceeds the token's rate limit |
| `LOAD_KEEP_DATA` | `false` | Leave the collection in place after the run |

### Profiles

All profiles are open-model (arrival-rate) scenarios: k6 starts requests at the planned rate
whatever the response times. A slow platform shows up as rising latency and dropped iterations,
not as a quietly lower request rate.

| Profile | Shape | Gates on |
|---------|-------|----------|
| `smoke` | 2 req/s for `LOAD_HOLD` | errors, checks, p95 per operation |
| `load` | 2-minute ramp to `LOAD_RPS`, hold, 1-minute ramp down | errors, checks, p95 per operation |
| `stress` | 5 equal steps up to `LOAD_MAX_RPS`, each held `LOAD_HOLD` | errors and checks only; it is for finding where latency breaks |
| `soak` | constant `LOAD_RPS` for `LOAD_HOLD` | errors, checks, p95 per operation |

The run fails (non-zero exit) when a threshold is crossed: more than 1% failed requests, more
than 1% 429s (`rate_limited`) or concurrency 503s (`concurrency_limited`), checks below 99%, or a
p95 budget exceeded. The error and throttle gates count only the workload (`{scenario:records}`):
setup's readiness polls answer 404 until a pod has the new collection, and that is expected.

## Reading the result

- `http_req_duration{op:…}`: latency per operation. Compare runs at the same rate on the
  same deployment.
- `rate_limited`: share of requests the gateway rejected with 429. Above zero means the rate
  limit, not the platform, is what you measured.
- `concurrency_limited`: share refused with `503 TENANT_CONCURRENCY_LIMIT`, the per-pod,
  per-tenant in-flight cap. Throttling by design; add worker pods or raise the cap to go past it.
- `dropped_iterations`: k6 had no free VU to start a request at the planned time. A few under
  stress is the point; under `load` it means the platform (or the load generator) cannot keep up.
- Watch the platform side during the run: worker CPU and Hikari pool use, Postgres
  `numbackends` against `max_connections` (`.claude/docs/concerns.md` → Postgres
  max_connections sizing) and gateway latency.

Setup waits until two reads in a row see the new collection and its fields. With more than two
worker pods that does not prove every pod has it yet (the e2e `DataFactory` uses the same check);
early 404s on a lagging pod show up as failed `get`/`list` checks in the first seconds.

## Cleanup

`teardown` force-deletes the collection (`DELETE /api/collections/{id}?force=true`), retrying
through 429/503 throttling for up to 5.5 minutes (one full rate-limit window). If setup fails partway, it deletes the collection
before the run stops. If a run is killed with a second
Ctrl-C, delete the leftover `k6_load_*` collection by hand.

## Tests

`ci/load-tests.test.ts` unit-tests the planning helpers in `lib/plan.js`: profiles, mix,
thresholds, seed validation and the rate-limit formulas, which must match `GatewayCacheManager`
(tenant window) and `RateLimitFilter` (per-user share). It runs in the
`test-ci-scripts` job. The k6 scripts themselves need a running stack, so CI does not run them.
