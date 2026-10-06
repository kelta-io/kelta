import { test, expect } from "../../fixtures";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

/**
 * Sessions are kept per workspace in localStorage: a new tab shares the session instead of
 * starting at the login page, and the slug-less root offers the workspaces this browser is
 * still signed in to, one click away.
 */
test.describe("Workspace sessions", () => {
  test("a new tab reuses the signed-in session", async ({ page, context }) => {
    await page.goto(`/${tenantSlug}/app`);
    await expect(page).not.toHaveURL(/\/login/);

    const second = await context.newPage();
    await second.goto(`/${tenantSlug}/app`);
    await expect(second).toHaveURL(new RegExp(`/${tenantSlug}/app`));
    await expect(second).not.toHaveURL(/\/login/);
    await second.close();
  });

  test("the root page offers a signed-in workspace and opens it without a login", async ({
    page,
  }) => {
    // Land in the workspace once so its session is in this browser's storage.
    await page.goto(`/${tenantSlug}/app`);
    await expect(page).not.toHaveURL(/\/login/);

    // rememberTenant() deliberately skips the "default" seed tenant, so record it directly.
    await page.evaluate((slug) => {
      localStorage.setItem(
        "kelta_recent_tenants",
        JSON.stringify([
          {
            slug,
            name: "E2E Workspace",
            users: [],
            lastUsedAt: new Date().toISOString(),
          },
        ]),
      );
    }, tenantSlug);

    await page.goto("/");
    await expect(page.getByTestId(`recent-tenant-${tenantSlug}`)).toBeVisible();
    await expect(page.getByTestId(`signed-in-${tenantSlug}`)).toBeVisible();

    await page
      .getByTestId(`recent-tenant-${tenantSlug}`)
      .getByRole("link")
      .first()
      .click();
    await expect(page).toHaveURL(new RegExp(`/${tenantSlug}/app`));
    await expect(page).not.toHaveURL(/\/login/);
  });
});
