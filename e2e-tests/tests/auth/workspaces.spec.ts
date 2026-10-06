import { test, expect } from "../../fixtures";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

/**
 * Sessions are kept per workspace in localStorage: a new tab shares the session instead of
 * starting at the login page, and the user menu offers the other workspaces this browser has
 * used, marking the ones still signed in.
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

  // The slug-less root's workspace list is unit-tested (NoTenantPage.test.tsx): this stack
  // serves the UI on a non-kelta.io host, where "/" is custom-domain mode and mounts the tenant
  // app instead. The in-app switcher lives under /<slug>/ and works on any host.
  test("the user menu offers other remembered workspaces and marks live sessions", async ({
    page,
  }) => {
    await page.goto(`/${tenantSlug}/app`);
    await expect(page).not.toHaveURL(/\/login/);

    // A second workspace this browser has signed in to, still holding a session.
    await page.evaluate(() => {
      const now = new Date().toISOString();
      localStorage.setItem(
        "kelta_recent_tenants",
        JSON.stringify([
          {
            slug: "e2e-other",
            name: "Other Workspace",
            users: [],
            lastUsedAt: now,
          },
        ]),
      );
      localStorage.setItem(
        "kelta_auth_tokens:e2e-other",
        JSON.stringify({ accessToken: "x", refreshToken: "y", expiresAt: 0 }),
      );
    });
    await page.reload();

    await page.getByTestId("user-menu-button").click();
    await page.getByTestId("switch-workspace-trigger").click();

    const other = page.getByTestId("switch-workspace-e2e-other");
    await expect(other).toBeVisible();
    await expect(other).toContainText("Other Workspace");
    await expect(other.getByLabel("Signed in")).toBeVisible();
    await expect(page.getByTestId("all-workspaces-menu-item")).toBeVisible();
    // The current workspace is not offered as a switch target.
    await expect(
      page.getByTestId(`switch-workspace-${tenantSlug}`),
    ).toHaveCount(0);
  });
});
