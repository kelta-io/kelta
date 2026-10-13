/**
 * Signing out from the user menu.
 *
 * Every other spec runs on the shared storage state plus the session tokens the
 * `../../fixtures` page fixture re-injects on EVERY navigation — so a signed-out page would
 * silently sign itself back in. This spec therefore imports the plain Playwright `test`,
 * starts from an empty storage state, and signs in through kelta-auth's form exactly like
 * `real-sign-in.spec.ts` (same E2E_REAL_SIGN_IN opt-in, which the CI stack sets). Signing
 * out here never touches the session the rest of the suite uses.
 *
 * What it asserts: the user menu's Sign out clears the SPA's tokens, kelta-auth's
 * end-session endpoint ends the authorization-server session and returns the browser to
 * the app, and a fresh tab (no SPA logout flag) then gets kelta-auth's sign-in form
 * instead of being signed straight back in.
 */
import { test, expect } from "@playwright/test";
import { loginViaInternalForm } from "../../helpers/internal-login";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

test.use({
  storageState: { cookies: [], origins: [] },
  // See real-sign-in.spec.ts: the SPA's PKCE step needs crypto.subtle, which an http
  // container origin only gets from full Chromium honouring the secure-origin flag.
  ...(process.env.E2E_BROWSER_SECURE_ORIGINS ? { channel: "chromium" } : {}),
});

test.describe("Logout", () => {
  test.skip(
    process.env.E2E_REAL_SIGN_IN !== "true",
    "set E2E_REAL_SIGN_IN=true where the e2e user can sign in through kelta-auth's form",
  );

  test("Sign out ends the session; protected pages then require login", async ({
    page,
  }) => {
    test.setTimeout(120_000);

    await page.goto(`/${tenantSlug}/login`, { waitUntil: "load" });
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
    const userMenu = page.getByTestId("user-menu-button");
    await expect(userMenu).toBeVisible({ timeout: 30_000 });
    const appOrigin = new URL(page.url()).origin;

    // Sign out from the user menu.
    await userMenu.click();
    await page.getByTestId("logout-button").click();

    // logout() clears the stored tokens, then leaves the app: either to kelta-auth's
    // end-session endpoint or, without an id token, back to the app with ?logged_out=true.
    await page.waitForURL(
      (url) =>
        url.origin !== appOrigin || url.searchParams.get("logged_out") === "true",
      { timeout: 30_000 },
    );
    // The end-session endpoint accepts the tenant-scoped post-logout URI and sends the
    // browser back to the app; a rejected URI leaves it on an error page at kelta-auth.
    await page.waitForURL((url) => url.origin === appOrigin, {
      timeout: 30_000,
    });

    // A fresh tab has none of the SPA's sessionStorage logout state, so the login page
    // auto-starts sign-in. With the kelta-auth session ended, that shows the sign-in
    // form rather than completing silently.
    const freshTab = await page.context().newPage();
    await freshTab.goto(`/${tenantSlug}/login`, { waitUntil: "load" });
    try {
      await freshTab
        .locator('[data-testid="login-page"] button[type="button"]')
        .last()
        .click({ timeout: 3_000 });
    } catch {
      // No provider button: auto-login already redirected to kelta-auth.
    }
    await expect(freshTab.locator("#username")).toBeVisible({
      timeout: 45_000,
    });
    await freshTab.close();

    // A protected page now sends the browser to the login page — and it stays there
    // (no silent re-authentication from leftover tokens).
    await page.goto(`/${tenantSlug}/app/home`);
    await expect(page).toHaveURL(new RegExp(`/${tenantSlug}/login`), {
      timeout: 30_000,
    });
    await expect(page.getByTestId("login-page")).toBeVisible();
    await expect(page.getByTestId("user-menu-button")).toHaveCount(0);
  });
});
