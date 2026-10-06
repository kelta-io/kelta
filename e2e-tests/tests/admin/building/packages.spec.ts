import fs from "fs";
import { test, expect } from "../../../fixtures";
import { PackagesPage } from "../../../pages/packages.page";

test.describe("Packages", () => {
  let packagesPage: PackagesPage;

  test.beforeEach(async ({ page }) => {
    packagesPage = new PackagesPage(page);
    await packagesPage.goto();
  });

  test("displays packages page", async () => {
    await expect(packagesPage.packagesPage).toBeVisible();
  });

  test("shows export tab by default", async () => {
    await expect(packagesPage.tabExport).toBeVisible();
  });

  test("can switch to import tab", async () => {
    await packagesPage.clickImportTab();
    await expect(packagesPage.tabImport).toBeVisible();
  });

  test("can switch to history tab", async () => {
    await packagesPage.clickHistoryTab();
    await expect(packagesPage.tabHistory).toBeVisible();
  });

  test("offers every metadata type the export endpoint accepts", async ({
    page,
  }) => {
    for (const section of [
      "collections",
      "pages",
      "menus",
      "flows",
      "layouts",
      "validation-rules",
      "picklists",
    ]) {
      await expect(page.getByTestId(`item-section-${section}`)).toBeVisible();
    }
  });

  test("exports a page layout and downloads a package containing it", async ({
    page,
    dataFactory,
  }) => {
    const collection = await dataFactory.createCollection();
    const collectionName = (collection.attributes as { name: string }).name;
    const { layoutId } = await dataFactory.createDetailLayout(
      collection.id,
      [],
    );

    await packagesPage.goto();
    // Layouts are labelled with their collection — names like "Default" repeat.
    const layoutItem = page.getByTestId(`item-${layoutId}`);
    await expect(layoutItem).toContainText(collectionName);

    await page.getByLabel(/Package Name/i).fill("e2e-layout-export");
    await page.getByTestId(`checkbox-${layoutId}`).check();

    const [download] = await Promise.all([
      page.waitForEvent("download"),
      packagesPage.exportButton.click(),
    ]);
    const pkg = JSON.parse(fs.readFileSync(await download.path(), "utf8")) as {
      items: Array<{ type: string; data: { id?: string } }>;
    };
    expect(
      pkg.items.some(
        (item) => item.type === "PAGE_LAYOUT" && item.data.id === layoutId,
      ),
    ).toBe(true);
  });
});
