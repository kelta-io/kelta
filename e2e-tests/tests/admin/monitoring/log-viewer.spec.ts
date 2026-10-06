import { test, expect } from "../../../fixtures";
import { LogViewerPage } from "../../../pages/log-viewer.page";

test.describe("Log Viewer", () => {
  let logViewerPage: LogViewerPage;

  test.beforeEach(async ({ page, tenantSlug }) => {
    logViewerPage = new LogViewerPage(page, tenantSlug);
    await logViewerPage.goto();
  });

  test("displays log viewer page with title", async ({ page }) => {
    const heading = page.getByRole("heading", { name: /log viewer/i });
    await expect(heading).toBeVisible();
  });

  test("shows filter controls", async () => {
    await expect(logViewerPage.searchInput).toBeVisible();
    await expect(logViewerPage.levelFilter).toBeVisible();
    await expect(logViewerPage.serviceFilter).toBeVisible();
  });

  test("shows table or empty state", async ({ page }) => {
    const table = logViewerPage.table;
    const noEntries = page.getByText(/no log entries/i);
    const tableOrEmpty = table.or(noEntries);
    // The E2E stack has no Loki: the worker only falls back to an empty result after
    // DNS resolution + its 5s connect timeout, which can outlast the default 10s expect.
    await expect(tableOrEmpty).toBeVisible({ timeout: 30_000 });
  });

  test("can filter by log level", async ({ page }) => {
    await logViewerPage.filterByLevel("ERROR");
    await page.waitForTimeout(500);
  });

  test("can search logs", async ({ page }) => {
    await logViewerPage.search("exception");
    await page.waitForTimeout(500);
  });

  test("navigates via monitoring hub tabs", async ({ page, tenantSlug }) => {
    await page.goto(`/${tenantSlug}/monitoring`);
    await page.waitForLoadState("load");
    await page.getByTestId("monitoring-tab-logs").click();
    await page.waitForURL(`**/${tenantSlug}/monitoring/logs`);
    const heading = page.getByRole("heading", { name: /log viewer/i });
    await expect(heading).toBeVisible();
  });
});
