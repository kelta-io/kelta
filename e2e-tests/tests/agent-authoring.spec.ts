import { test, expect } from "../fixtures";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DataFactory } from "../helpers/data-factory";
import { getApiToken } from "../fixtures/auth-tokens";

/**
 * K-9 regression gate: proves an agent holding only the CLI can author a tenant UI —
 * layouts, saved list views, a dashboard, a page and a menu — over a neutral demo
 * domain (a library: books, loans, members; never a real tenant's names), within a
 * fixed HTTP-request budget, idempotently.
 *
 * kelta-mcp is not in the CI compose stack (`docker-compose.ci.yml`), so this drives
 * the CLI directly — the same `runCli` shape as `admin/cli-smoke.spec.ts` — rather than
 * MCP tools. Per-tool MCP call counts are asserted in kelta-mcp's own unit tests.
 */

test.describe.configure({ mode: "serial" });

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CLI_ENTRY = path.resolve(__dirname, "../../kelta-web/packages/cli/dist/index.js");
const FIXTURES_DIR = path.resolve(__dirname, "../fixtures/authoring");

const API_BASE = process.env.E2E_API_BASE_URL || "https://api.kelta.io";
const TENANT = process.env.E2E_TENANT_SLUG || "default";

/** kelta list-views apply x4, kelta layouts apply x2, dashboards apply, pages apply + publish, menus apply. */
const MAX_HTTP_REQUESTS = 20;

/**
 * Machine-readable request count for the ci.yml e2e job summary — written into
 * `test-results/`, which the e2e job's "Copy Playwright reports out of container"
 * step already copies to the host, so a regression in the call budget is visible
 * on the PR without any new artifact plumbing.
 */
const REQUEST_COUNT_REPORT_PATH = path.resolve(__dirname, "../test-results/agent-authoring-request-count.txt");

interface CliResult {
  status: number | null;
  stdout: string;
  stderr: string;
}

interface ViewSpec {
  name: string;
  columns: string;
  filter?: string;
  sort?: string;
  visibility?: string;
}

interface TreeCounts {
  created: number;
  updated: number;
  deleted: number;
  unchanged: number;
}

interface SequenceResult {
  bookLayout: TreeCounts & { layoutId: string };
  memberLayout: TreeCounts & { layoutId: string };
  views: Array<{ id: string; action?: string }>;
  dashboard: TreeCounts & { dashboardId: string };
  page: { id: string; action?: string };
  pagePublish: { id: string; action?: string };
  menu: TreeCounts & { menuId: string };
}

const viewSpecs: ViewSpec[] = JSON.parse(readFileSync(path.join(FIXTURES_DIR, "views.json"), "utf-8"));

const BOOK_TITLES: Array<[title: string, genre: string, available: boolean]> = [
  ["Cloud Atlas", "Fiction", true],
  ["The Hobbit", "Fiction", true],
  ["Dune", "Fiction", false],
  ["Sapiens", "Nonfiction", true],
  ["Silent Spring", "Nonfiction", true],
];
const MEMBER_NAMES = ["Ada Lovelace", "Alan Turing", "Grace Hopper"];
const LOAN_ROWS: Array<[bookTitle: string, memberName: string, dueDate: string]> = [
  ["Cloud Atlas", "Ada Lovelace", "2026-09-01"],
  ["Dune", "Alan Turing", "2026-09-05"],
  ["Sapiens", "Grace Hopper", "2026-09-10"],
  ["The Hobbit", "Ada Lovelace", "2026-09-15"],
];

test.describe("agent authoring benchmark", () => {
  test.skip(!existsSync(CLI_ENTRY), "CLI dist not built");

  let configDir: string;
  let tmpFixturesDir: string;
  let token: string;
  let dataFactory: DataFactory;

  let books: { id: string; name: string };
  let members: { id: string; name: string };
  let loans: { id: string; name: string };

  let firstRun: SequenceResult;
  let firstTrace: string[];
  let secondRun: SequenceResult;

  function runCli(args: string[], extraEnv: Record<string, string | undefined> = {}): CliResult {
    const result = spawnSync(process.execPath, [CLI_ENTRY, ...args], {
      encoding: "utf-8",
      timeout: 30_000,
      env: {
        ...process.env,
        KELTA_CONFIG_DIR: configDir,
        KELTA_URL: API_BASE,
        KELTA_TENANT: TENANT,
        KELTA_TOKEN: token,
        ...extraEnv,
      },
    });
    return { status: result.status, stdout: result.stdout, stderr: result.stderr };
  }

  /** Runs a CLI command with KELTA_TRACE=1 and appends its `[kelta-trace]` stderr lines to `sink`. */
  function tracedCli(args: string[], sink: string[]): CliResult {
    const result = runCli(args, { KELTA_TRACE: "1" });
    for (const line of result.stderr.split("\n")) {
      if (line.startsWith("[kelta-trace]")) sink.push(line);
    }
    return result;
  }

  let fixtureWriteCount = 0;

  /** Substitutes `{{TOKEN}}` placeholders in a committed authoring fixture and writes it to a temp file. */
  function writeFixture(name: string, substitutions: Record<string, string>): string {
    const raw = readFileSync(path.join(FIXTURES_DIR, name), "utf-8");
    const substituted = Object.entries(substitutions).reduce(
      (text, [key, value]) => text.split(`{{${key}}}`).join(value),
      raw
    );
    const outPath = path.join(tmpFixturesDir, `${String(fixtureWriteCount++)}-${name}`);
    writeFileSync(outPath, substituted);
    return outPath;
  }

  /** Reads one attribute of a system-collection record directly (outside the traced/counted sequence). */
  async function fetchAttribute(resourcePath: string, attribute: string): Promise<unknown> {
    const response = await fetch(`${API_BASE}/${TENANT}${resourcePath}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const body = (await response.json()) as { data?: { attributes?: Record<string, unknown> } };
    return body.data?.attributes?.[attribute];
  }

  /** Snapshots `updatedAt` for every row the sequence addresses, to prove a repeat run touches none of them. */
  async function snapshotUpdatedAt(run: SequenceResult): Promise<Record<string, unknown>> {
    const snapshot: Record<string, unknown> = {};
    snapshot.bookLayout = await fetchAttribute(`/api/page-layouts/${run.bookLayout.layoutId}`, "updatedAt");
    snapshot.memberLayout = await fetchAttribute(`/api/page-layouts/${run.memberLayout.layoutId}`, "updatedAt");
    for (const [i, view] of run.views.entries()) {
      snapshot[`view${i}`] = await fetchAttribute(`/api/list-views/${view.id}`, "updatedAt");
    }
    snapshot.dashboard = await fetchAttribute(`/api/dashboards/${run.dashboard.dashboardId}`, "updatedAt");
    snapshot.page = await fetchAttribute(`/api/ui-pages/${run.page.id}`, "updatedAt");
    snapshot.menu = await fetchAttribute(`/api/ui-menus/${run.menu.menuId}`, "updatedAt");
    return snapshot;
  }

  /** The full authoring sequence: 2x layouts apply, 4x list-views apply, dashboards apply, pages apply + publish, menus apply. */
  async function runSequence(): Promise<{ trace: string[]; result: SequenceResult }> {
    const trace: string[] = [];

    const bookLayoutFile = writeFixture("layouts.json", { PRIMARY_FIELD: "title" });
    let r = tracedCli(
      ["layouts", "apply", books.name, "--file", bookLayoutFile, "--name", "Book Layout", "--output", "json"],
      trace
    );
    expect(r.status, r.stderr).toBe(0);
    const bookLayout = JSON.parse(r.stdout) as SequenceResult["bookLayout"];

    const memberLayoutFile = writeFixture("layouts.json", { PRIMARY_FIELD: "name" });
    r = tracedCli(
      ["layouts", "apply", members.name, "--file", memberLayoutFile, "--name", "Member Layout", "--output", "json"],
      trace
    );
    expect(r.status, r.stderr).toBe(0);
    const memberLayout = JSON.parse(r.stdout) as SequenceResult["memberLayout"];

    const views: SequenceResult["views"] = [];
    for (const spec of viewSpecs) {
      const args = ["list-views", "apply", books.id, "--name", spec.name, "--columns", spec.columns, "--output", "json"];
      if (spec.filter) args.push("--filter", spec.filter);
      if (spec.sort) args.push("--sort", spec.sort);
      if (spec.visibility) args.push("--visibility", spec.visibility);
      r = tracedCli(args, trace);
      expect(r.status, r.stderr).toBe(0);
      views.push(JSON.parse(r.stdout));
    }

    const dashboardFile = writeFixture("dashboard.json", {
      BOOKS: books.name,
      MEMBERS: members.name,
      LOANS: loans.name,
    });
    r = tracedCli(
      ["dashboards", "apply", "--file", dashboardFile, "--name", "Library Overview", "--output", "json"],
      trace
    );
    expect(r.status, r.stderr).toBe(0);
    const dashboard = JSON.parse(r.stdout) as SequenceResult["dashboard"];

    const pageFile = writeFixture("page.json", { BOOKS: books.name });
    r = tracedCli(["pages", "apply", pageFile, "--output", "json"], trace);
    expect(r.status, r.stderr).toBe(0);
    const page = JSON.parse(r.stdout) as SequenceResult["page"];

    r = tracedCli(["pages", "publish", "/library-home", "--output", "json"], trace);
    expect(r.status, r.stderr).toBe(0);
    const pagePublish = JSON.parse(r.stdout) as SequenceResult["pagePublish"];

    // "Available Books" (views[1]) is the one PUBLIC view — the only one the end-user
    // shell will surface as a shared view for the menu child to deep-link into.
    const menuFile = writeFixture("menu.json", { BOOKS: books.name, VIEW_ID: views[1].id });
    r = tracedCli(["menus", "apply", "--file", menuFile, "--name", "Library Menu", "--output", "json"], trace);
    expect(r.status, r.stderr).toBe(0);
    const menu = JSON.parse(r.stdout) as SequenceResult["menu"];

    return { trace, result: { bookLayout, memberLayout, views, dashboard, page, pagePublish, menu } };
  }

  test.beforeAll(async () => {
    token = await getApiToken();
    dataFactory = new DataFactory({ baseUrl: API_BASE, tenantSlug: TENANT, token });
    configDir = mkdtempSync(path.join(tmpdir(), "kelta-cli-authoring-"));
    tmpFixturesDir = mkdtempSync(path.join(tmpdir(), "kelta-authoring-fixtures-"));

    const runId = Date.now();

    const booksResource = await dataFactory.createCollection({
      name: `e2e_lib_books_${runId}`,
      displayName: "Library Books",
    });
    books = { id: booksResource.id, name: booksResource.attributes.name as string };
    await dataFactory.addField(books.id, { name: "title", displayName: "Title", type: "string", required: true });
    await dataFactory.addField(books.id, { name: "genre", displayName: "Genre", type: "string" });
    await dataFactory.addField(books.id, { name: "available", displayName: "Available", type: "boolean" });
    await dataFactory.waitForStorageReady(books.name);

    const membersResource = await dataFactory.createCollection({
      name: `e2e_lib_members_${runId}`,
      displayName: "Library Members",
    });
    members = { id: membersResource.id, name: membersResource.attributes.name as string };
    await dataFactory.addField(members.id, { name: "name", displayName: "Name", type: "string", required: true });
    await dataFactory.waitForStorageReady(members.name);

    const loansResource = await dataFactory.createCollection({
      name: `e2e_lib_loans_${runId}`,
      displayName: "Library Loans",
    });
    loans = { id: loansResource.id, name: loansResource.attributes.name as string };
    await dataFactory.addField(loans.id, { name: "bookTitle", displayName: "Book Title", type: "string" });
    await dataFactory.addField(loans.id, { name: "memberName", displayName: "Member Name", type: "string" });
    await dataFactory.addField(loans.id, { name: "dueDate", displayName: "Due Date", type: "date" });
    await dataFactory.addField(loans.id, { name: "returned", displayName: "Returned", type: "boolean" });
    await dataFactory.waitForStorageReady(loans.name);

    for (const [title, genre, available] of BOOK_TITLES) {
      await dataFactory.createRecord(books.name, { title, genre, available });
    }
    for (const name of MEMBER_NAMES) {
      await dataFactory.createRecord(members.name, { name });
    }
    for (const [bookTitle, memberName, dueDate] of LOAN_ROWS) {
      await dataFactory.createRecord(loans.name, { bookTitle, memberName, dueDate, returned: false });
    }
  });

  test.afterAll(async () => {
    try {
      if (firstRun) {
        runCli(["menus", "delete", firstRun.menu.menuId, "--yes", "--output", "json"]);
        runCli(["dashboards", "delete", firstRun.dashboard.dashboardId, "--yes", "--output", "json"]);
        runCli(["pages", "delete", firstRun.page.id, "--yes", "--output", "json"]);
        for (const view of firstRun.views) {
          runCli(["list-views", "delete", view.id, "--yes", "--output", "json"]);
        }
        runCli(["layouts", "delete", firstRun.bookLayout.layoutId, "--yes", "--output", "json"]);
        runCli(["layouts", "delete", firstRun.memberLayout.layoutId, "--yes", "--output", "json"]);
      }
    } catch {
      // Best-effort — the collection force-delete below is the load-bearing cleanup.
    }
    await dataFactory.cleanup();
    rmSync(configDir, { recursive: true, force: true });
    rmSync(tmpFixturesDir, { recursive: true, force: true });
  });

  test("builds the tenant UI in at most 20 HTTP requests with zero 5xx", async () => {
    const run = await runSequence();
    firstRun = run.result;
    firstTrace = run.trace;

    mkdirSync(path.dirname(REQUEST_COUNT_REPORT_PATH), { recursive: true });
    writeFileSync(REQUEST_COUNT_REPORT_PATH, `${String(firstTrace.length)}\n`);

    expect(firstTrace.length, firstTrace.join("\n")).toBeLessThanOrEqual(MAX_HTTP_REQUESTS);
    const serverErrors = firstTrace.filter((line) => /\s5\d\d$/.test(line));
    expect(serverErrors, serverErrors.join("\n")).toHaveLength(0);
  });

  test("running the same sequence again reports every item unchanged with updatedAt untouched", async () => {
    const before = await snapshotUpdatedAt(firstRun);

    const run = await runSequence();
    secondRun = run.result;

    expect(run.trace.length, run.trace.join("\n")).toBeLessThanOrEqual(MAX_HTTP_REQUESTS);

    for (const counts of [secondRun.bookLayout, secondRun.memberLayout, secondRun.dashboard, secondRun.menu]) {
      expect(counts.created).toBe(0);
      expect(counts.updated).toBe(0);
      expect(counts.deleted).toBe(0);
    }
    for (const view of secondRun.views) {
      expect(view.action).toBe("unchanged");
    }
    expect(secondRun.page.action).toBe("unchanged");
    expect(secondRun.pagePublish.action).toBe("unchanged");

    const after = await snapshotUpdatedAt(secondRun);
    expect(after).toEqual(before);
  });

  test("dashboard renders all 6 widgets with data and no error state", async ({ page }) => {
    await page.goto(`/${TENANT}/app/dashboards/${firstRun.dashboard.dashboardId}`);
    await page.waitForLoadState("load");

    await expect(page.getByTestId("dashboard-grid")).toBeVisible();
    await expect(page.getByTestId("widget-error")).toHaveCount(0);
    await expect(page.getByTestId("metric-widget")).toHaveCount(3);
    await expect(page.getByTestId("chart-widget")).toHaveCount(1);
    await expect(page.getByTestId("records-widget")).toHaveCount(2);
    await expect(page.getByTestId("records-row").first()).toBeVisible();
  });

  test("home page shows the bound count", async ({ page }) => {
    await page.goto(`/${TENANT}/app/home`);
    await page.waitForLoadState("load");

    const metric = page.getByTestId("page-node-metric");
    await expect(metric).toBeVisible();
    await expect(page.getByTestId("page-node-metric-error")).toHaveCount(0);
    await expect(metric).toContainText(String(BOOK_TITLES.length));
  });

  test("menu group's child opens the shared view", async ({ page }) => {
    await page.goto(`/${TENANT}/app/home`);
    await page.waitForLoadState("load");

    const group = page.getByTestId("nav-group-Catalog");
    await expect(group).toBeVisible();
    await group.click();
    await page.getByTestId("nav-group-item-Available Books").click();

    await expect(page).toHaveURL(new RegExp(`/app/o/${books.name}`));
    // "Available Books" is a 2-column view (title, genre) — narrower than the
    // collection's default view — so its presence (and "Available" absence)
    // proves the shared view, not the default list, actually rendered.
    await expect(page.getByRole("columnheader", { name: "Title" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Genre" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Available" })).toHaveCount(0);
  });
});
