import { test, expect } from "../../../fixtures";
import { TenantsPage } from "../../../pages/tenants.page";

test.describe("Tenants", () => {
  test("displays tenants page", async ({ page }) => {
    const tenantsPage = new TenantsPage(page);
    await tenantsPage.goto();

    await expect(page).toHaveURL(/\/tenants/);
    await expect(tenantsPage.tenantsPage).toBeVisible();
  });

  test("shows tenants table or empty state", async ({ page }) => {
    const tenantsPage = new TenantsPage(page);
    await tenantsPage.goto();
    await tenantsPage.waitForTableLoaded();
  });

  test("has create tenant button", async ({ page }) => {
    const tenantsPage = new TenantsPage(page);
    await tenantsPage.goto();
    await tenantsPage.waitForTableLoaded();

    await expect(tenantsPage.createButton).toBeVisible();
  });

  test("offers a Bootstrap token action that opens and cancels without minting", async ({
    page,
  }) => {
    const tenantsPage = new TenantsPage(page);
    await tenantsPage.goto();
    await tenantsPage.waitForTableLoaded();

    const button = page.getByTestId("bootstrap-token-button-0");
    test.skip((await button.count()) === 0, "no tenants listed");
    await button.click();

    const dialog = page.getByTestId("bootstrap-token-dialog");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("bootstrap-token-expires-in")).toHaveValue("1h");
    await expect(page.getByTestId("bootstrap-token-value")).toHaveCount(0);

    await page.getByTestId("bootstrap-token-cancel").click();
    await expect(dialog).toHaveCount(0);
  });
});
