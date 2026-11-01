// Pure planning helpers for the k6 load tests: profiles, the request mix, thresholds and the
// gateway rate-limit budget. No k6 imports, so `ci/load-tests.test.ts` runs them under Node.

/** Mirrors GatewayCacheManager.getRateLimitForTenant: a 5-minute window, 288 windows a day. */
export const RATE_WINDOW_SECONDS = 300;
const WINDOWS_PER_DAY = 288;
/** RateLimitFilter.DEFAULT_USER_SHARE (`kelta.gateway.rate-limit.user-share`). */
export const DEFAULT_USER_SHARE = 0.9;

/**
 * The tenant window for a given `apiCallsPerDay`: `floor(limit / 288) * 5` requests per
 * 5 minutes (Java int division, at least 1). The gateway reports it as `X-RateLimit-Limit`.
 */
export function tenantWindow(apiCallsPerDay) {
  const limit = Number(apiCallsPerDay);
  if (!Number.isFinite(limit) || limit <= 0) return 0;
  return Math.max(1, Math.floor(limit / WINDOWS_PER_DAY) * 5);
}

/**
 * Requests per second one principal can sustain. RateLimitFilter checks a per-user window of
 * `round(tenantWindow * userShare)` before the tenant window, and a load run uses one token,
 * so the per-user window is the one that binds.
 */
export function allowedRps(windowLimit, userShare = DEFAULT_USER_SHARE) {
  const limit = Number(windowLimit);
  if (!Number.isFinite(limit) || limit <= 0) return 0;
  const share = Number(userShare);
  if (!Number.isFinite(share) || share <= 0 || share > 1) {
    throw new Error(`LOAD_USER_SHARE must be in (0, 1], got '${userShare}'`);
  }
  return Math.max(1, Math.round(limit * share)) / RATE_WINDOW_SECONDS;
}

/** LOAD_SEED_RECORDS as a whole number of at least 1 (reads and updates pick seeded ids). */
export function seedCount(value) {
  if (value === undefined || value === "") return 200;
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1) {
    throw new Error(`LOAD_SEED_RECORDS must be a whole number >= 1, got '${value}'`);
  }
  return n;
}

/** Operation weights; anything not listed in LOAD_MIX keeps its default. */
export const DEFAULT_MIX = Object.freeze({
  list: 35,
  filter: 15,
  get: 30,
  create: 10,
  update: 8,
  delete: 2,
});

/** Parses `list=50,get=30,create=20` into a full mix; unknown names and bad weights throw. */
export function parseMix(spec) {
  const mix = { ...DEFAULT_MIX };
  if (!spec) return mix;
  for (const part of String(spec).split(",")) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const [name, raw] = trimmed.split("=").map((s) => s.trim());
    if (!(name in DEFAULT_MIX)) {
      throw new Error(`LOAD_MIX: unknown operation '${name}' (known: ${Object.keys(DEFAULT_MIX).join(", ")})`);
    }
    const weight = Number(raw);
    if (!Number.isFinite(weight) || weight < 0) {
      throw new Error(`LOAD_MIX: weight for '${name}' must be a number >= 0, got '${raw}'`);
    }
    mix[name] = weight;
  }
  if (Object.values(mix).reduce((a, b) => a + b, 0) <= 0) {
    throw new Error("LOAD_MIX: at least one operation needs a weight above 0");
  }
  return mix;
}

/** Picks an operation from the mix for a uniform random `r` in [0, 1). */
export function pickOperation(mix, r) {
  const entries = Object.entries(mix).filter(([, w]) => w > 0);
  const total = entries.reduce((sum, [, w]) => sum + w, 0);
  let point = r * total;
  for (const [name, weight] of entries) {
    if (point < weight) return name;
    point -= weight;
  }
  return entries[entries.length - 1][0];
}

function positive(value, fallback, name) {
  if (value === undefined || value === null || value === "") return fallback;
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) {
    throw new Error(`${name} must be a number above 0, got '${value}'`);
  }
  return n;
}

/**
 * The k6 scenario for a profile. All profiles use an open (arrival-rate) model, so the
 * request rate is fixed by the plan and latency growth shows up as latency, not as a
 * quietly lower request rate.
 *
 * env: LOAD_RPS (load/soak target), LOAD_MAX_RPS (stress peak), LOAD_HOLD (hold time).
 */
export function buildScenario(profile, env = {}) {
  const rps = positive(env.LOAD_RPS, 20, "LOAD_RPS");
  switch (profile) {
    case "smoke":
      return {
        executor: "constant-arrival-rate",
        rate: 2,
        timeUnit: "1s",
        duration: env.LOAD_HOLD || "1m",
        preAllocatedVUs: 2,
        maxVUs: 10,
      };
    case "load":
      return {
        executor: "ramping-arrival-rate",
        startRate: 1,
        timeUnit: "1s",
        preAllocatedVUs: Math.max(10, Math.ceil(rps)),
        maxVUs: Math.max(50, Math.ceil(rps * 4)),
        stages: [
          { target: rps, duration: "2m" },
          { target: rps, duration: env.LOAD_HOLD || "10m" },
          { target: 0, duration: "1m" },
        ],
      };
    case "stress": {
      const max = positive(env.LOAD_MAX_RPS, rps * 5, "LOAD_MAX_RPS");
      const steps = 5;
      const stages = [];
      for (let i = 1; i <= steps; i++) {
        const target = Math.ceil((max * i) / steps);
        stages.push({ target, duration: "1m" });
        stages.push({ target, duration: env.LOAD_HOLD || "2m" });
      }
      stages.push({ target: 0, duration: "1m" });
      return {
        executor: "ramping-arrival-rate",
        startRate: 1,
        timeUnit: "1s",
        preAllocatedVUs: Math.max(20, Math.ceil(max)),
        maxVUs: Math.max(100, Math.ceil(max * 6)),
        stages,
      };
    }
    case "soak":
      return {
        executor: "constant-arrival-rate",
        rate: Math.ceil(rps),
        timeUnit: "1s",
        duration: env.LOAD_HOLD || "1h",
        preAllocatedVUs: Math.max(10, Math.ceil(rps)),
        maxVUs: Math.max(50, Math.ceil(rps * 4)),
      };
    default:
      throw new Error(`LOAD_PROFILE must be one of smoke, load, stress, soak; got '${profile}'`);
  }
}

/** Highest iterations-per-second the scenario asks for (each iteration is one request). */
export function peakRate(scenario) {
  if (scenario.rate !== undefined) return scenario.rate;
  return Math.max(scenario.startRate || 0, ...scenario.stages.map((s) => s.target));
}

/**
 * Pass/fail gates. Latency budgets are p95 milliseconds per operation, overridable with
 * LOAD_P95_<OP> (e.g. LOAD_P95_LIST=800). Stress runs report but do not gate on latency:
 * they exist to find where it breaks.
 */
export function buildThresholds(profile, env = {}) {
  // Scoped to the workload scenario: setup's readiness polls (404 until a pod has the
  // collection) and teardown are not part of what is measured.
  const thresholds = {
    checks: ["rate>0.99"],
    "http_req_failed{scenario:records}": ["rate<0.01"],
    "rate_limited{scenario:records}": ["rate<0.01"],
    "concurrency_limited{scenario:records}": ["rate<0.01"],
  };
  if (profile === "stress") return thresholds;
  const defaults = { list: 500, filter: 600, get: 300, create: 500, update: 500, delete: 500 };
  for (const [op, ms] of Object.entries(defaults)) {
    const budget = positive(env[`LOAD_P95_${op.toUpperCase()}`], ms, `LOAD_P95_${op.toUpperCase()}`);
    thresholds[`http_req_duration{op:${op}}`] = [`p(95)<${budget}`];
  }
  return thresholds;
}
