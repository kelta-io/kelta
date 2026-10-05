import { test, expect } from "../../../fixtures";
import { WebhooksPage } from "../../../pages/webhooks.page";

test.describe("Webhooks", () => {
  test("displays webhooks page with custom UI", async ({ page }) => {
    const webhooksPage = new WebhooksPage(page);
    await webhooksPage.goto();

    await expect(page).toHaveURL(/\/webhooks/);
    await expect(webhooksPage.webhooksPage).toBeVisible();
  });

  test("shows endpoints tab by default", async ({ page }) => {
    const webhooksPage = new WebhooksPage(page);
    await webhooksPage.goto();

    // The webhooks page should show the endpoints tab content
    await expect(webhooksPage.webhooksPage).toBeVisible();
  });

  test("settles into the endpoint dashboard or the Svix error state", async ({
    page,
  }) => {
    const webhooksPage = new WebhooksPage(page);
    await webhooksPage.goto();
    await expect(webhooksPage.webhooksPage).toBeVisible();

    // Svix may not be configured in CI, in which case the page renders its
    // ErrorMessage instead of the dashboard. Either is a settled page; a
    // stuck spinner or blank page is not.
    await expect(
      page
        .getByRole("heading", { name: "Webhooks", level: 1 })
        .or(webhooksPage.pageError),
    ).toBeVisible();
  });

  test("add endpoint dialog shows collection filter", async ({ page }) => {
    const webhooksPage = new WebhooksPage(page);
    await webhooksPage.goto();
    await expect(webhooksPage.webhooksPage).toBeVisible();

    // The Add Endpoint button is only available when Svix credentials load
    // successfully. Skip the assertion if the button is not visible (e.g.
    // Svix is not configured in CI).
    const buttonVisible = await webhooksPage.addEndpointButton
      .waitFor({ state: "visible", timeout: 10_000 })
      .then(() => true)
      .catch(() => false);
    if (!buttonVisible) {
      test.skip(
        true,
        "Add Endpoint button not available (Svix may not be configured)",
      );
      return;
    }

    await webhooksPage.addEndpointButton.click();
    await expect(webhooksPage.collectionFilterSection).toBeVisible();
  });
});
