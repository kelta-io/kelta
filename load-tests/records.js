// Record API load test: a mixed read/write workload against one throwaway collection.
// Usage and every knob: load-tests/README.md. Settings use a LOAD_ prefix, never K6_: k6 reads
// K6_* variables as its own options (K6_DURATION, K6_RPS ...) and they would override the plan.
import { check, fail } from "k6";
import exec from "k6/execution";
import { client, data, query } from "./lib/api.js";
import { createFixture, removeFixture, randomRecord, STATUSES } from "./lib/fixture.js";
import {
  allowedRps,
  buildScenario,
  buildThresholds,
  DEFAULT_USER_SHARE,
  parseMix,
  peakRate,
  pickOperation,
  seedCount,
} from "./lib/plan.js";

const PROFILE = __ENV.LOAD_PROFILE || "smoke";
const SCENARIO = buildScenario(PROFILE, __ENV);
const MIX = parseMix(__ENV.LOAD_MIX);
const SEED_RECORDS = seedCount(__ENV.LOAD_SEED_RECORDS);

export const options = {
  scenarios: { records: SCENARIO },
  thresholds: buildThresholds(PROFILE, __ENV),
  setupTimeout: "10m",
  teardownTimeout: "6m",
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

/**
 * Refuses a plan the rate limit cannot carry: past it the run measures the gateway's 429s, not
 * the platform. LOAD_ALLOW_THROTTLE=true runs anyway (to test the limiter itself).
 *
 * The budget comes from the gateway's own X-RateLimit-Limit header (the tenant's 5-minute
 * window), not from the governor-limits body: the gateway reads `apiCallsPerDay` from the
 * tenant's limits override only, so a tier change shows a higher limit there without raising
 * the one enforced.
 */
function preflight(api) {
  const res = api.get("/api/governor-limits", "setup");
  const window = Number(res.headers["X-Ratelimit-Limit"] || res.headers["X-RateLimit-Limit"]);
  if (!Number.isFinite(window) || window <= 0) {
    console.warn(
      `No X-RateLimit-Limit header on GET /api/governor-limits (${res.status}); cannot check the ` +
        "rate-limit budget. Watch the rate_limited metric.",
    );
    return;
  }
  const share = __ENV.LOAD_USER_SHARE || DEFAULT_USER_SHARE;
  const budget = allowedRps(window, share);
  const peak = peakRate(SCENARIO);
  console.log(
    `Profile ${PROFILE}: peak ${peak} req/s; gateway window ${window} req/5 min, ` +
      `user share ${share} → ~${budget.toFixed(1)} req/s for this token`,
  );
  if (peak > budget * 0.9 && __ENV.LOAD_ALLOW_THROTTLE !== "true") {
    fail(
      `Peak ${peak} req/s exceeds 90% of this token's rate limit (~${budget.toFixed(1)} req/s). ` +
        "Raise the load-test tenant's apiCallsPerDay (PUT /api/governor-limits, see load-tests/README.md), " +
        "lower LOAD_RPS / LOAD_MAX_RPS, or set LOAD_ALLOW_THROTTLE=true.",
    );
  }
}

export function setup() {
  const api = client();
  preflight(api);
  return createFixture(api, SEED_RECORDS);
}

// Records this VU created, so deletes never remove the seeded set reads depend on.
const mine = [];

function randomId(fixture) {
  return fixture.ids[Math.floor(Math.random() * fixture.ids.length)];
}

const OPERATIONS = {
  list(api, fx) {
    const page = 1 + Math.floor(Math.random() * 5);
    const res = api.get(`/api/${fx.name}${query({ "page[size]": 20, "page[number]": page, sort: "-createdAt" })}`, "list");
    check(res, { "list 200": (r) => r.status === 200, "list has data": (r) => Array.isArray(data(r)) });
  },
  filter(api, fx) {
    const status = STATUSES[Math.floor(Math.random() * STATUSES.length)];
    const params = { "filter[status][eq]": status, "filter[amount][gte]": Math.floor(Math.random() * 500), "page[size]": 50 };
    const res = api.get(`/api/${fx.name}${query(params)}`, "filter");
    check(res, { "filter 200": (r) => r.status === 200 });
  },
  get(api, fx) {
    const id = randomId(fx);
    const res = api.get(`/api/${fx.name}/${id}`, "get");
    check(res, { "get 200": (r) => r.status === 200, "get returns the record": (r) => data(r)?.id === id });
  },
  create(api, fx) {
    const res = api.post(`/api/${fx.name}`, { data: { type: fx.name, attributes: randomRecord(exec.scenario.iterationInTest) } }, "create");
    const ok = check(res, { "create 2xx": (r) => r.status >= 200 && r.status < 300 });
    const id = data(res)?.id;
    if (ok && id) mine.push(id);
  },
  update(api, fx) {
    const id = randomId(fx);
    const body = { data: { type: fx.name, id, attributes: { amount: Math.round(Math.random() * 100000) / 100 } } };
    const res = api.patch(`/api/${fx.name}/${id}`, body, "update");
    check(res, { "update 200": (r) => r.status === 200 });
  },
  delete(api, fx) {
    // Nothing of this VU's to delete yet: create one instead, or read when creates are off.
    const id = mine.pop();
    if (!id) return MIX.create > 0 ? OPERATIONS.create(api, fx) : OPERATIONS.get(api, fx);
    const res = api.del(`/api/${fx.name}/${id}`, "delete");
    check(res, { "delete 2xx": (r) => r.status >= 200 && r.status < 300 });
  },
};

let api;

export default function (fixture) {
  api = api || client();
  OPERATIONS[pickOperation(MIX, Math.random())](api, fixture);
}

export function teardown(fixture) {
  if (__ENV.LOAD_KEEP_DATA === "true") {
    console.log(`LOAD_KEEP_DATA=true: leaving collection ${fixture.name} (${fixture.collectionId})`);
    return;
  }
  removeFixture(client(), fixture);
}
