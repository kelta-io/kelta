// Unit tests for the k6 load-test planning helpers (load-tests/lib/plan.js). The k6 scripts
// themselves need a running stack; these pin the parts that decide what a run asks for.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  allowedRps,
  buildScenario,
  buildThresholds,
  DEFAULT_MIX,
  DEFAULT_USER_SHARE,
  parseMix,
  peakRate,
  pickOperation,
  seedCount,
  tenantWindow,
} from "../load-tests/lib/plan.js";

test("tenantWindow mirrors GatewayCacheManager: floor(apiCallsPerDay / 288) * 5, at least 1", () => {
  assert.equal(tenantWindow(100_000), 1735); // PROFESSIONAL default
  assert.equal(tenantWindow(1_000_000), 3472 * 5);
  assert.equal(tenantWindow(10), 1);
  assert.equal(tenantWindow(2_147_483_647), 7456540 * 5); // UNLIMITED: no int overflow
  assert.equal(tenantWindow(undefined), 0);
});

test("allowedRps applies RateLimitFilter's per-user share of the window", () => {
  // One token gets round(1735 * 0.9) = 1562 requests per 300 s on the PROFESSIONAL default.
  assert.equal(allowedRps(1735), 1562 / 300);
  assert.equal(DEFAULT_USER_SHARE, 0.9);
  assert.equal(allowedRps(1735, 0.5), 868 / 300);
  assert.equal(allowedRps(1, 0.1), 1 / 300); // never below one request per window
  assert.equal(allowedRps(undefined), 0);
  assert.equal(allowedRps(Number.NaN), 0);
  assert.throws(() => allowedRps(1735, 0), /LOAD_USER_SHARE must be in \(0, 1\]/);
  assert.throws(() => allowedRps(1735, 1.5), /LOAD_USER_SHARE/);
});

test("seedCount defaults to 200 and refuses anything that leaves reads without ids", () => {
  assert.equal(seedCount(undefined), 200);
  assert.equal(seedCount("50"), 50);
  for (const bad of ["0", "-3", "abc", "2.5"]) {
    assert.throws(() => seedCount(bad), /LOAD_SEED_RECORDS must be a whole number >= 1/);
  }
});

test("parseMix overrides named weights and keeps the rest", () => {
  const mix = parseMix("list=50, delete=0");
  assert.equal(mix.list, 50);
  assert.equal(mix.delete, 0);
  assert.equal(mix.get, DEFAULT_MIX.get);
  assert.deepEqual(parseMix(""), { ...DEFAULT_MIX });
});

test("parseMix rejects unknown operations, bad weights and an all-zero mix", () => {
  assert.throws(() => parseMix("search=10"), /unknown operation 'search'/);
  assert.throws(() => parseMix("list=-1"), /must be a number >= 0/);
  assert.throws(() => parseMix("list=x"), /must be a number >= 0/);
  const allZero = Object.keys(DEFAULT_MIX).map((k) => `${k}=0`).join(",");
  assert.throws(() => parseMix(allZero), /at least one operation/);
});

test("pickOperation follows the weights and skips zero-weight operations", () => {
  const mix = { list: 1, filter: 0, get: 3, create: 0, update: 0, delete: 0 };
  assert.equal(pickOperation(mix, 0), "list");
  assert.equal(pickOperation(mix, 0.24), "list");
  assert.equal(pickOperation(mix, 0.25), "get");
  assert.equal(pickOperation(mix, 0.9999), "get");
});

test("every profile is an open-model scenario with the requested peak", () => {
  assert.equal(peakRate(buildScenario("smoke")), 2);
  assert.equal(peakRate(buildScenario("load", { LOAD_RPS: "40" })), 40);
  assert.equal(peakRate(buildScenario("soak", { LOAD_RPS: "15" })), 15);
  assert.equal(peakRate(buildScenario("stress", { LOAD_MAX_RPS: "300" })), 300);
  for (const p of ["smoke", "load", "stress", "soak"]) {
    assert.match(buildScenario(p).executor, /arrival-rate/);
  }
});

test("stress climbs in five steps to the peak and then ramps down", () => {
  const s = buildScenario("stress", { LOAD_MAX_RPS: "100" });
  const targets = s.stages.map((st) => st.target);
  assert.deepEqual(targets, [20, 20, 40, 40, 60, 60, 80, 80, 100, 100, 0]);
});

test("buildScenario rejects an unknown profile and a non-positive rate", () => {
  assert.throws(() => buildScenario("bogus"), /must be one of smoke, load, stress, soak/);
  assert.throws(() => buildScenario("load", { LOAD_RPS: "0" }), /LOAD_RPS must be a number above 0/);
});

test("thresholds gate latency per operation except under stress", () => {
  const load = buildThresholds("load", { LOAD_P95_LIST: "900" });
  assert.deepEqual(load["http_req_duration{op:list}"], ["p(95)<900"]);
  assert.deepEqual(load["http_req_duration{op:get}"], ["p(95)<300"]);
  // Error and throttle gates cover the workload scenario only, not setup's readiness polls.
  assert.deepEqual(load["rate_limited{scenario:records}"], ["rate<0.01"]);
  assert.deepEqual(load["concurrency_limited{scenario:records}"], ["rate<0.01"]);
  assert.equal(load.http_req_failed, undefined);
  const stress = buildThresholds("stress");
  assert.equal(stress["http_req_duration{op:list}"], undefined);
  assert.deepEqual(stress["http_req_failed{scenario:records}"], ["rate<0.01"]);
});
