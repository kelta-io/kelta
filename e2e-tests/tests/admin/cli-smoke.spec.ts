import { test, expect } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Smoke test for the kelta CLI against the deployed stack.
 *
 * Uses the pure env-override auth path (KELTA_URL/KELTA_TENANT/KELTA_TOKEN)
 * with the pre-issued E2E PAT — no login flow needed. The CLI must be built
 * first (the e2e workflow builds kelta-web/packages/cli); when the dist is
 * absent (local run without a build) the suite skips.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CLI_ENTRY = path.resolve(
  __dirname,
  "../../../kelta-web/packages/cli/dist/index.js",
);

const API_BASE = process.env.E2E_API_BASE_URL || "https://api.kelta.io";
const TENANT = process.env.E2E_TENANT_SLUG || "default";
const TOKEN = process.env.E2E_API_TOKEN;

interface CliResult {
  status: number | null;
  stdout: string;
  stderr: string;
}

function runCli(args: string[], extraEnv: Record<string, string | undefined> = {}): CliResult {
  const result = spawnSync(process.execPath, [CLI_ENTRY, ...args], {
    encoding: "utf-8",
    timeout: 30_000,
    env: {
      ...process.env,
      KELTA_CONFIG_DIR: configDir,
      KELTA_URL: API_BASE,
      KELTA_TENANT: TENANT,
      KELTA_TOKEN: TOKEN,
      ...extraEnv,
    },
  });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

let configDir: string;

test.describe("kelta CLI smoke", () => {
  test.skip(!existsSync(CLI_ENTRY), "CLI dist not built");
  test.skip(!TOKEN, "E2E_API_TOKEN not set");

  test.beforeAll(() => {
    configDir = mkdtempSync(path.join(tmpdir(), "kelta-cli-e2e-"));
  });

  test.afterAll(() => {
    rmSync(configDir, { recursive: true, force: true });
  });

  test("version prints and exits 0", () => {
    const result = runCli(["--version"]);
    expect(result.status).toBe(0);
    expect(result.stdout.trim()).toMatch(/^\d+\.\d+\.\d+$/);
  });

  test("collections list returns flattened JSON rows", () => {
    const result = runCli(["collections", "list", "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const rows = JSON.parse(result.stdout) as { id?: string; name?: string }[];
    expect(Array.isArray(rows)).toBe(true);
    expect(rows.length).toBeGreaterThan(0);
    expect(rows[0]).toHaveProperty("id");
  });

  test("records list works against a system collection with pagination", () => {
    const result = runCli(["records", "list", "collections", "--size", "1", "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const rows = JSON.parse(result.stdout) as unknown[];
    expect(Array.isArray(rows)).toBe(true);
    expect(rows.length).toBeLessThanOrEqual(1);
  });

  test("--raw returns the JSON:API envelope", () => {
    const result = runCli(["collections", "list", "--output", "json", "--raw"]);
    expect(result.status, result.stderr).toBe(0);
    const body = JSON.parse(result.stdout) as { data?: unknown[] };
    expect(Array.isArray(body.data)).toBe(true);
  });

  // Read coverage for the admin metadata groups added by KLT-217 (pages, menus,
  // dashboards, reports, list-views get/delete). Seed data varies by tenant, so
  // each test discovers a row via `list`/`records list` first and skips at
  // runtime rather than asserting a fixed row exists.

  test("list-views get retrieves a saved list view by id", () => {
    const listed = runCli(["records", "list", "list-views", "--size", "1", "--output", "json"]);
    expect(listed.status, listed.stderr).toBe(0);
    const rows = JSON.parse(listed.stdout) as { id?: string }[];
    test.skip(rows.length === 0, "no list views in the seeded tenant");
    const result = runCli(["list-views", "get", rows[0].id!, "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const row = JSON.parse(result.stdout) as { id?: string };
    expect(row.id).toBe(rows[0].id);
  });

  test("pages list and get return UI pages", () => {
    const listed = runCli(["pages", "list", "--output", "json"]);
    expect(listed.status, listed.stderr).toBe(0);
    const rows = JSON.parse(listed.stdout) as { id?: string; path?: string }[];
    expect(Array.isArray(rows)).toBe(true);
    test.skip(rows.length === 0, "no UI pages in the seeded tenant");
    const result = runCli(["pages", "get", rows[0].id!, "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const row = JSON.parse(result.stdout) as { id?: string; path?: string };
    expect(row.path).toBe(rows[0].path);
  });

  test("menus get --tree nests items under their group", () => {
    const listed = runCli(["menus", "list", "--output", "json"]);
    expect(listed.status, listed.stderr).toBe(0);
    const rows = JSON.parse(listed.stdout) as { id?: string; name?: string }[];
    test.skip(rows.length === 0, "no UI menus in the seeded tenant");
    const result = runCli(["menus", "get", rows[0].name ?? rows[0].id!, "--tree", "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const row = JSON.parse(result.stdout) as { id?: string; items?: unknown[] };
    expect(row.id).toBe(rows[0].id);
    expect(Array.isArray(row.items)).toBe(true);
  });

  test("dashboards list and get --components sort widgets by row/column", () => {
    const listed = runCli(["dashboards", "list", "--output", "json"]);
    expect(listed.status, listed.stderr).toBe(0);
    const rows = JSON.parse(listed.stdout) as { id?: string }[];
    test.skip(rows.length === 0, "no dashboards in the seeded tenant");
    const result = runCli(["dashboards", "get", rows[0].id!, "--components", "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const row = JSON.parse(result.stdout) as {
      id?: string;
      components?: { rowPosition?: number; columnPosition?: number }[];
    };
    expect(row.id).toBe(rows[0].id);
    expect(Array.isArray(row.components)).toBe(true);
    const positions = (row.components ?? []).map((c) => [c.rowPosition ?? 0, c.columnPosition ?? 0]);
    const sorted = [...positions].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    expect(positions).toEqual(sorted);
  });

  test("reports list and get return report definitions", () => {
    const listed = runCli(["reports", "list", "--output", "json"]);
    expect(listed.status, listed.stderr).toBe(0);
    const rows = JSON.parse(listed.stdout) as { id?: string; name?: string }[];
    expect(Array.isArray(rows)).toBe(true);
    test.skip(rows.length === 0, "no reports in the seeded tenant");
    const result = runCli(["reports", "get", rows[0].id!, "--output", "json"]);
    expect(result.status, result.stderr).toBe(0);
    const row = JSON.parse(result.stdout) as { id?: string; name?: string };
    expect(row.name).toBe(rows[0].name);
  });

  // Regression coverage for KLT-253: `metadata export` writes a package file,
  // then `metadata diff`/`metadata apply --dry-run` must accept that same file
  // as a multipart upload (the SDK client's default JSON Content-Type header
  // previously made axios silently JSON-stringify the multipart body, which
  // the worker rejected as a 500 instead of a usable response).
  test("metadata export -> diff -> apply --dry-run round-trips against the tenant", () => {
    const file = path.join(configDir, "metadata-round-trip.json");

    const exported = runCli(["metadata", "export", "--out", file]);
    expect(exported.status, exported.stderr).toBe(0);
    expect(existsSync(file)).toBe(true);

    const diffed = runCli(["metadata", "diff", file, "--output", "json"]);
    expect(diffed.status, diffed.stderr).toBe(0);
    const diff = JSON.parse(diffed.stdout) as {
      creates?: unknown[];
      updates?: unknown[];
      conflicts?: unknown[];
    };
    expect(diff).toHaveProperty("creates");
    expect(diff).toHaveProperty("updates");
    expect(diff).toHaveProperty("conflicts");

    const applied = runCli(["metadata", "apply", file, "--dry-run", "--output", "json"]);
    expect(applied.status, applied.stderr).toBe(0);
  });

  test("missing auth fails with the machine-readable error contract and exit 3", () => {
    const result = runCli(["collections", "list", "--output", "json"], {
      KELTA_TOKEN: undefined,
      KELTA_URL: undefined,
      KELTA_TENANT: undefined,
    });
    expect(result.status).toBe(3);
    const payload = JSON.parse(result.stderr) as { error: { code: string } };
    expect(payload.error.code).toBe("AUTH_REQUIRED");
  });

  test("destructive command without --yes is refused off-TTY with exit 2", () => {
    const result = runCli(["records", "delete", "collections", "nonexistent", "--output", "json"]);
    expect(result.status).toBe(2);
    const payload = JSON.parse(result.stderr) as { error: { code: string } };
    expect(payload.error.code).toBe("CONFIRMATION_REQUIRED");
  });

  // This round-trip CREATES a collection, adds a field + validation rule, and
  // WRITES records. It must never touch a real product tenant: the platform
  // cannot delete a collection once it has been used (child rows in tables like
  // field_history / validation_rule reference collection(id) with no
  // ON DELETE CASCADE and no delete API), so teardown is inherently
  // best-effort and any run leaves a permanent, undeletable collection behind.
  // An earlier version ran against `couchpicks` and littered it with
  // undeletable e2e_cli_* / e2e_test_* collections.
  //
  // Guard: run only against a tenant whose slug is clearly disposable, or when
  // the operator explicitly opts in. Default is skip.
  const disposableTenant = /(^|[-_])(e2e|test|sandbox|ci)([-_]|$)/i.test(TENANT ?? "");
  const mutationOptIn = process.env.KELTA_E2E_ALLOW_SCHEMA_MUTATION === "1";
  const mayMutate = disposableTenant || mutationOptIn;

  test("admin round-trip: collection → field → validation rule → rejected record → delete", () => {
    test.skip(
      !mayMutate,
      `refusing to create schema in tenant "${TENANT ?? "?"}" — set KELTA_E2E_ALLOW_SCHEMA_MUTATION=1 or use a disposable tenant`
    );
    test.setTimeout(120_000);
    const name = `e2e_cli_${String(Date.now())}`;
    try {
      const created = runCli([
        "collections", "create", "--name", name, "--display-name", "CLI e2e", "--output", "json",
      ]);
      expect(created.status, created.stderr).toBe(0);

      const field = runCli([
        "fields", "add", name,
        "--name", "amount", "--type", "number", "--required",
        "--output", "json",
      ]);
      expect(field.status, field.stderr).toBe(0);

      const rule = runCli([
        "validation-rules", "create", name,
        "--name", "amount_positive",
        "--formula", "amount <= 0",
        "--message", "Amount must be positive",
        "--output", "json",
      ]);
      expect(rule.status, rule.stderr).toBe(0);

      const fields = runCli(["fields", "list", name, "--output", "json"]);
      expect(fields.status, fields.stderr).toBe(0);
      const rows = JSON.parse(fields.stdout) as { name?: string }[];
      expect(rows.some((row) => row.name === "amount")).toBe(true);

      // the ERROR-condition semantics end to end: TRUE formula rejects the write
      const rejected = runCli([
        "records", "create", name, "--data", '{"amount": -5}', "--output", "json",
      ]);
      expect(rejected.status).toBe(1);
      const error = JSON.parse(rejected.stderr) as { error: { detail?: string } };
      expect(error.error.detail ?? "").toContain("Amount must be positive");

      const accepted = runCli([
        "records", "create", name, "--data", '{"amount": 5}', "--output", "json",
      ]);
      expect(accepted.status, accepted.stderr).toBe(0);
    } finally {
      // Best-effort teardown in dependency order. The collection itself may
      // survive (see the guard comment above) — clear what the API allows so a
      // disposable tenant stays as clean as possible.
      const ids = runCli(["records", "list", name, "--quiet"]).stdout.split("\n").filter(Boolean);
      for (const id of ids) {
        runCli(["records", "delete", name, id, "--yes", "--output", "json"]);
      }
      runCli(["collections", "delete", name, "--yes", "--output", "json"]);
    }
  });
});
