import { test as setup, expect } from "@playwright/test";
import { loginViaInternalForm } from "../helpers/internal-login";
import { attemptDirectLogin, toSessionTokens } from "../helpers/direct-login";
import fs from "fs";

const TENANT_SLUG = process.env.E2E_TENANT_SLUG || "default";
const AUTH_STATE_PATH = "./auth/storage-state.json";
const SESSION_TOKENS_PATH = "./auth/session-tokens.json";

setup("authenticate via Authentik", async ({ page }) => {
  setup.setTimeout(90_000);

  // --- Strategy 1: Direct API login (avoids browser OIDC redirect issues) ---
  const directLoginUrl =
    process.env.E2E_AUTH_DIRECT_LOGIN_URL ||
    process.env.E2E_AUTH_BASE_URL ||
    "";

  if (directLoginUrl) {
    const username = process.env.E2E_TEST_USERNAME || "e2e-admin@kelta.local";
    const password = process.env.E2E_TEST_PASSWORD || "";

    const result = await attemptDirectLogin({
      authBaseUrl: directLoginUrl,
      username,
      password,
      tenantSlug: TENANT_SLUG,
    });

    if (result) {
      const sessionTokens = toSessionTokens(result);
      fs.writeFileSync(
        SESSION_TOKENS_PATH,
        JSON.stringify(sessionTokens, null, 2),
      );

      // Navigate to the app with tokens pre-injected so we can capture
      // cookies and localStorage for the storage state file.
      await page.addInitScript((tokenMap: Record<string, string>) => {
        for (const [key, value] of Object.entries(tokenMap)) {
          sessionStorage.setItem(key, value);
        }
      }, sessionTokens);

      await page.goto(`/${TENANT_SLUG}/app`, { waitUntil: "load" });

      // Wait for the SPA to recognize the injected tokens and render the app
      await page.waitForURL(
        (url) => {
          const pathname = url.pathname;
          return (
            pathname.startsWith(`/${TENANT_SLUG}/`) &&
            !pathname.includes("/login")
          );
        },
        { timeout: 30_000 },
      );

      await page.context().storageState({ path: AUTH_STATE_PATH });
      return;
    }
  }

  // --- Strategy 2: Browser login via kelta-auth's internal form ---
  // The SPA redirects (single internal OIDC provider) to kelta-auth's
  // server-rendered login form. This path is also the one exercised when
  // Strategy 1 (direct login) is disabled — e.g. on the deployed environment,
  // where /auth/direct-login is intentionally off for security.
  await page.goto(`/${TENANT_SLUG}/login`, { waitUntil: "load" });

  await loginViaInternalForm(page, {
    username: process.env.E2E_TEST_USERNAME || "e2e-admin@kelta.local",
    password: process.env.E2E_TEST_PASSWORD || "",
  });

  // Wait for redirect back to the app after OIDC callback processing.
  // The callback URL is /:tenantSlug/auth/callback, then the app redirects
  // to the originally requested page or /app.
  await page.waitForURL(
    (url) => {
      const pathname = url.pathname;
      return (
        pathname.startsWith(`/${TENANT_SLUG}/`) &&
        !pathname.includes("/auth/callback") &&
        !pathname.includes("/login")
      );
    },
    { timeout: 60_000 },
  );

  await expect(page).not.toHaveURL(/\/login/);

  // The app keeps auth tokens in localStorage (per tenant), which the storageState
  // saved below captures. Any kelta_auth* sessionStorage leftovers are saved too and
  // re-injected via addInitScript in each test, as the direct-login path does.
  const sessionTokens = await page.evaluate(() => {
    const result: Record<string, string> = {};
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i);
      if (key && key.startsWith("kelta_auth")) {
        result[key] = sessionStorage.getItem(key) || "";
      }
    }
    return result;
  });

  fs.writeFileSync(SESSION_TOKENS_PATH, JSON.stringify(sessionTokens, null, 2));

  // Save the authenticated browser state (cookies + localStorage) for all other test projects
  await page.context().storageState({ path: AUTH_STATE_PATH });
});
