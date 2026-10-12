import { test, expect } from "../../fixtures";

/**
 * End-user analytics viewer (app-surfacing slice 3): the /app/analytics hub, a native
 * dashboard and a report run + CSV export.
 *
 * There is no native authoring UI for end users, so the dashboard (one COUNT metric
 * widget), its component and the report are seeded through the same JSON:API system
 * collections the admin builders write, over a fresh collection holding a known number
 * of records — every number the page shows is therefore checked against seeded data.
 *
 * The VIEW_ANALYTICS gate on the execution endpoints (a profile without it gets 403) is
 * proven against the real stack by the kelta-test-harness AnalyticsAuthzScenarioTest; the
 * UI's denied fallback is the shared RequirePermission route guard.
 */
const TITLES = ["Alpha", "Bravo", "Charlie"];

test.describe("Analytics viewer", () => {
  test("hub → dashboard metric → report run → CSV export", async ({
    page,
    dataFactory,
    tenantSlug,
  }) => {
    const collection = await dataFactory.createCollection();
    const collectionName = collection.attributes.name as string;
    await dataFactory.addField(collection.id, {
      name: "title",
      displayName: "Title",
      type: "string",
    });
    await dataFactory.waitForStorageReady(collectionName);
    for (const title of TITLES) {
      await dataFactory.createRecord(collectionName, { title });
    }

    const suffix = Date.now();
    const dashboardName = `E2E dashboard ${suffix}`;
    const reportName = `E2E report ${suffix}`;

    const report = await dataFactory.createResource("reports", {
      name: reportName,
      reportType: "TABULAR",
      primaryCollectionId: collection.id,
      columns: [{ fieldName: "title", label: "Title" }],
      sortBy: "title",
      sortDirection: "ASC",
      scope: "ALL_RECORDS",
      accessLevel: "PUBLIC",
    });
    const dashboard = await dataFactory.createResource("dashboards", {
      name: dashboardName,
      accessLevel: "PUBLIC",
      columnCount: 2,
    });
    await dataFactory.createResource("dashboard-components", {
      dashboardId: dashboard.id,
      componentType: "metric",
      title: "Seeded records",
      columnPosition: 1,
      rowPosition: 1,
      columnSpan: 1,
      rowSpan: 1,
      sortOrder: 0,
      config: {
        collectionName,
        aggregateFunction: "COUNT",
        label: "Records",
        // Opt out of the page's time range, which also renders the widget's range chip.
        ignoreTimeRange: true,
      },
    });

    // ── Hub lists both ──
    await page.goto(`/${tenantSlug}/app/analytics`);
    const dashboardCard = page
      .getByTestId("hub-dashboard-card")
      .filter({ hasText: dashboardName });
    await expect(dashboardCard).toBeVisible({ timeout: 15_000 });
    const reportRow = page
      .getByTestId("hub-report-row")
      .filter({ hasText: reportName });
    await expect(reportRow).toBeVisible();

    // ── Dashboard: the metric counts the seeded records ──
    await dashboardCard.click();
    await expect(page).toHaveURL(
      new RegExp(`/${tenantSlug}/app/dashboards/${dashboard.id}`),
    );
    await expect(page.getByTestId("dashboard-grid")).toBeVisible();
    const metric = page.getByTestId("metric-widget");
    await expect(metric).toHaveCount(1);
    await expect(metric).toContainText(String(TITLES.length), { timeout: 15_000 });
    await expect(metric).toContainText("Records");
    await expect(page.getByTestId("widget-time-range-chip")).toHaveText("All time");

    // ── Report: runs, shows the seeded rows, exports CSV ──
    await page.goto(`/${tenantSlug}/app/analytics`);
    await page
      .getByTestId("hub-report-row")
      .filter({ hasText: reportName })
      .getByTestId("hub-report-run")
      .click();
    await expect(page).toHaveURL(
      new RegExp(`/${tenantSlug}/app/reports/${report.id}`),
    );
    const rows = page.getByTestId("report-row");
    await expect(rows).toHaveCount(TITLES.length, { timeout: 15_000 });
    await expect(rows).toHaveText(TITLES.map((title) => new RegExp(title)));

    const download = page.waitForEvent("download");
    await page.getByTestId("export-csv").click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.csv$/);
    const csv = await readDownload(await file.createReadStream());
    for (const title of TITLES) {
      expect(csv).toContain(title);
    }
  });
});

async function readDownload(stream: NodeJS.ReadableStream): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  return Buffer.concat(chunks).toString("utf-8");
}
