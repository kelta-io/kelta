/**
 * Real browser sign-in, from a signed-out browser to an authenticated page (#1389, #1591).
 *
 * Every other spec starts from the storage state `auth.setup.ts` saved — which on a
 * local/CI stack comes from kelta-auth's direct-login API, not from a browser. So
 * nothing exercised the path a person actually takes, and two outages went green:
 *   - #1388: a /login ⇄ /api/modules redirect loop broke login on every tenant;
 *   - #1591: a fresh install's login page offered only unreachable providers and the
 *     internal one pointed at production, so no newcomer could sign in at all.
 *
 * This spec deliberately imports the plain Playwright `test` (not ../../fixtures, which
 * injects saved session tokens) and clears storage state, then:
 *   1. opens /{tenant}/login and reaches kelta-auth's own login form,
 *   2. signs in with the e2e admin credentials,
 *   3. asserts the SPA lands on an authenticated page and STAYS there — a redirect
 *      loop that bounces back to /login is the failure this must catch.
 */
import { test, expect } from "@playwright/test";
import { loginViaInternalForm } from "../../helpers/internal-login";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

test.use({ storageState: { cookies: [], origins: [] } });

test.describe("Real sign-in", () => {
  test("signs in through kelta-auth's form and stays signed in", async ({ page }) => {
    test.setTimeout(120_000);

    // Record every navigation to the SPA login page after we have signed in.
    let loginNavigationsAfterSignIn = 0;
    let signedIn = false;
    page.on("framenavigated", (frame) => {
      if (signedIn && frame === page.mainFrame() && new URL(frame.url()).pathname.endsWith("/login")) {
        loginNavigationsAfterSignIn += 1;
      }
    });

    await page.goto(`/${tenantSlug}/login`, { waitUntil: "load" });

    // The SPA must hand off to kelta-auth's server-rendered form. If it cannot reach
    // the provider's discovery document (the #1591 failure) the form never appears
    // and loginViaInternalForm times out waiting for #username.
    await loginViaInternalForm(page, {
      username: process.env.E2E_TEST_USERNAME || "e2e-admin@kelta.local",
      password: process.env.E2E_TEST_PASSWORD || "",
    });

    await page.waitForURL(
      (url) =>
        url.pathname.startsWith(`/${tenantSlug}/`) &&
        !url.pathname.includes("/auth/callback") &&
        !url.pathname.includes("/login"),
      { timeout: 60_000 },
    );
    signedIn = true;

    // Authenticated-only chrome is rendered.
    await expect(page.getByTestId("user-menu-button")).toBeVisible({ timeout: 30_000 });

    // ...and it stays: no bounce back to /login once the app has settled (#1388).
    await page.waitForTimeout(5_000);
    await expect(page).not.toHaveURL(/\/login/);
    await expect(page.getByTestId("user-menu-button")).toBeVisible();
    expect(loginNavigationsAfterSignIn).toBe(0);
  });
});
