import { test, expect } from "../../fixtures";
import { LoginPage } from "../../pages/login.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

// These tests run unauthenticated to verify login page behavior.
test.use({ storageState: { cookies: [], origins: [] } });

test.describe("Login Page", () => {
  test("displays error message on failed login", async ({ page }) => {
    const loginPage = new LoginPage(page, tenantSlug);
    await page.goto(`/${tenantSlug}/login?error=auth_failed`);

    await expect(loginPage.errorMessage).toBeVisible();
  });

  // `logged_out=true` is what suppresses auto-login. The seeded tenant has exactly
  // one OIDC provider and it is the internal one, so a bare /login auto-redirects to
  // kelta-auth instead of rendering a picker -- this test only ever passed by winning
  // the race against that redirect. The flag is the app's real "let me choose" path.
  test("shows provider buttons on login page", async ({ page }) => {
    await page.goto(`/${tenantSlug}/login?logged_out=true`);

    const loginPage = new LoginPage(page, tenantSlug);
    await expect(loginPage.container).toBeVisible();
    await expect(loginPage.providerButtons.first()).toBeVisible();
  });
});
