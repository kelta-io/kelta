// Creates the throwaway collection the run works on, seeds it, and removes it afterwards.
import { sleep } from "k6";
import { data, query, withRetry } from "./api.js";

export const FIELDS = [
  { name: "title", displayName: "Title", type: "string" },
  { name: "status", displayName: "Status", type: "string" },
  { name: "amount", displayName: "Amount", type: "number" },
  { name: "active", displayName: "Active", type: "boolean" },
];
export const STATUSES = ["open", "pending", "closed"];

const READY_TIMEOUT_S = 60;

export function randomRecord(i) {
  return {
    title: `k6 record ${i} ${Math.random().toString(36).slice(2, 10)}`,
    status: STATUSES[Math.floor(Math.random() * STATUSES.length)],
    amount: Math.round(Math.random() * 100000) / 100,
    active: Math.random() < 0.5,
  };
}

function mustSucceed(res, what) {
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`${what} failed (${res.status}): ${String(res.body).slice(0, 500)}`);
  }
}

function idOf(res, what) {
  const id = data(res)?.id;
  if (!id) throw new Error(`${what}: response has no data.id: ${String(res.body).slice(0, 300)}`);
  return id;
}

/**
 * Polls until `check()` is true twice in a row. Two hits, because the gateway round-robins
 * across worker pods and config reaches each pod over NATS on its own time.
 */
function waitFor(what, check) {
  const deadline = Date.now() + READY_TIMEOUT_S * 1000;
  let consecutive = 0;
  while (Date.now() < deadline) {
    consecutive = check() ? consecutive + 1 : 0;
    if (consecutive >= 2) return;
    sleep(0.5);
  }
  throw new Error(`${what} not ready after ${READY_TIMEOUT_S}s`);
}

export function createFixture(api, seedCount) {
  const name = `k6_load_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
  const created = withRetry(() =>
    api.post(
      "/api/collections",
      { data: { type: "collections", attributes: { name, displayName: `k6 ${name}`, active: true } } },
      "setup",
    ),
  );
  mustSucceed(created, "Creating the load-test collection");
  const collectionId = idOf(created, "Creating the load-test collection");

  // teardown() never runs when setup() throws, so a failure from here on removes the
  // collection itself instead of leaking it into the tenant.
  try {
    return { name, collectionId, ids: populate(api, name, collectionId, seedCount) };
  } catch (error) {
    removeFixture(api, { name, collectionId });
    throw error;
  }
}

function populate(api, name, collectionId, seedCount) {
  for (const field of FIELDS) {
    mustSucceed(
      withRetry(() =>
        api.post(`/api/collections/${collectionId}/fields`, { data: { type: "fields", attributes: field } }, "setup"),
      ),
      `Adding field '${field.name}'`,
    );
  }

  waitFor(`Fields on '${name}'`, () => {
    const res = api.get(`/api/collections/${collectionId}?include=fields`, "setup");
    if (res.status !== 200) return false;
    const included = res.json("included") || [];
    const names = new Set(included.filter((r) => r.type === "fields").map((r) => r.attributes.name));
    return FIELDS.every((f) => names.has(f.name));
  });
  waitFor(`Storage for '${name}'`, () => api.get(`/api/${name}${query({ "page[size]": 1 })}`, "setup").status === 200);

  const ids = [];
  for (let i = 0; i < seedCount; i++) {
    const res = withRetry(() => api.post(`/api/${name}`, { data: { type: name, attributes: randomRecord(i) } }, "setup"));
    mustSucceed(res, `Seeding record ${i + 1}/${seedCount}`);
    ids.push(idOf(res, `Seeding record ${i + 1}/${seedCount}`));
  }
  return ids;
}

export function removeFixture(api, fixture) {
  // Retried for longer than one 5-minute rate-limit window: a run that ends throttled gets a
  // Retry-After of up to 300 s, and giving up sooner would leak the collection.
  const res = withRetry(() => api.del(`/api/collections/${fixture.collectionId}?force=true`, "teardown"), 330);
  if (res.status >= 300) {
    console.warn(
      `Could not delete collection ${fixture.name} (${fixture.collectionId}): ${res.status} — delete it by hand`,
    );
  }
}
