// Thin JSON:API client for the k6 scripts. Every request carries an `op` tag so thresholds
// and the end-of-test summary break latency down per operation.
import http from "k6/http";
import { sleep } from "k6";
import { Rate } from "k6/metrics";

/** Share of requests the gateway rejected with 429 (the per-user or tenant rate-limit window). */
export const rateLimited = new Rate("rate_limited");
/**
 * Share of requests a worker pod refused with 503 TENANT_CONCURRENCY_LIMIT: more in-flight
 * requests for the tenant than `kelta.worker.tenant-concurrency-limit` (default 10) per pod.
 * Throttling by design, kept apart from failures so a summary does not read it as a crash.
 */
export const concurrencyLimited = new Rate("concurrency_limited");

export function requireEnv(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required (see load-tests/README.md)`);
  }
  return value;
}

/** Encodes a query map, bracketed keys included (`page[size]`, `filter[status][eq]`). */
export function query(params) {
  const parts = Object.entries(params).map(
    ([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`,
  );
  return parts.length ? `?${parts.join("&")}` : "";
}

export function isConcurrencyLimited(res) {
  return res.status === 503 && String(res.body).includes("TENANT_CONCURRENCY_LIMIT");
}

export function client() {
  const baseUrl = requireEnv("LOAD_BASE_URL").replace(/\/+$/, "");
  const tenant = requireEnv("LOAD_TENANT");
  const token = requireEnv("LOAD_API_TOKEN");
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/vnd.api+json",
    Accept: "application/vnd.api+json, application/json",
  };

  function request(method, path, body, op) {
    const res = http.request(
      method,
      `${baseUrl}/${tenant}${path}`,
      body === undefined ? null : JSON.stringify(body),
      { headers, tags: { op, name: `${method} ${op}` }, timeout: "30s" },
    );
    rateLimited.add(res.status === 429, { op });
    concurrencyLimited.add(isConcurrencyLimited(res), { op });
    return res;
  }

  return {
    get: (path, op) => request("GET", path, undefined, op),
    post: (path, body, op) => request("POST", path, body, op),
    patch: (path, body, op) => request("PATCH", path, body, op),
    del: (path, op) => request("DELETE", path, undefined, op),
  };
}

/**
 * Repeats `send` while the platform throttles it (429, or 503 TENANT_CONCURRENCY_LIMIT),
 * honouring Retry-After, for at most `budgetSeconds`. For setup and cleanup, never the workload.
 */
export function withRetry(send, budgetSeconds = 90) {
  const deadline = Date.now() + budgetSeconds * 1000;
  let res = send();
  let backoff = 1;
  while ((res.status === 429 || isConcurrencyLimited(res)) && Date.now() < deadline) {
    const retryAfter = Number(res.headers["Retry-After"]);
    const wait = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : backoff;
    sleep(Math.min(wait, Math.max(0, (deadline - Date.now()) / 1000)));
    backoff = Math.min(backoff * 2, 16);
    res = send();
  }
  return res;
}

/** `data` of a JSON:API response, or null when the body is not JSON. */
export function data(res) {
  try {
    return res.json("data");
  } catch {
    return null;
  }
}
