import { test, expect } from "../../fixtures";

/**
 * The Access Denied page (`/{tenant}/unauthorized`, `UnauthorizedPage`). `ProtectedRoute` /
 * `RequirePermission` send a signed-in user here when a route needs a permission they lack;
 * the route itself is public, so it can be opened directly.
 */
test.describe("Unauthorized Page", () => {
  test("renders the access-denied page for a signed-in user", async ({
    page,
    tenantSlug,
  }) => {
    await page.goto(`/${tenantSlug}/unauthorized`);

    const container = page.getByTestId("unauthorized-page");
    await expect(container).toBeVisible();
    await expect(
      container.getByRole("heading", { name: "Access Denied" }),
    ).toBeVisible();
    // The signed-in identity is shown so the user knows which account was refused.
    await expect(container.getByText(/^Logged in as: /)).toBeVisible();
  });

  test("Go to Dashboard leaves the page for the tenant's app", async ({
    page,
    tenantSlug,
  }) => {
    await page.goto(`/${tenantSlug}/unauthorized`);

    await page.getByRole("button", { name: "Go to Dashboard" }).click();

    // `/{tenant}` redirects a signed-in user to the end-user app.
    await expect(page).toHaveURL(new RegExp(`/${tenantSlug}/app(/|$|\\?)`));
    await expect(page.getByTestId("unauthorized-page")).toHaveCount(0);
  });

  test("Go Back returns to the previous page", async ({ page, tenantSlug }) => {
    await page.goto(`/${tenantSlug}/app/home`);
    await expect(page).toHaveURL(new RegExp(`/${tenantSlug}/app/home`));

    await page.goto(`/${tenantSlug}/unauthorized`);
    await expect(page.getByTestId("unauthorized-page")).toBeVisible();

    await page.getByRole("button", { name: "Go Back" }).click();

    await expect(page).toHaveURL(new RegExp(`/${tenantSlug}/app/home`));
  });
});
