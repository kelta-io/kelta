/**
 * Forgot password, signed out: kelta-auth's login form links to /forgot-password, which
 * mails a single-use link to /reset-password; the new password must meet the tenant's
 * policy, and afterwards the user signs in with it.
 *
 * Imports the plain Playwright `test` with an empty storage state: the `../../fixtures`
 * page fixture re-injects the e2e admin's session on every navigation. The flow uses a
 * fresh user (never the e2e admin, whose password other specs rely on) and reads the
 * link from Mailpit, so it runs only where E2E_MAILPIT_URL points at the stack's Mailpit
 * API and E2E_REAL_SIGN_IN is on (the CI stack sets both). The JSON endpoints, CSRF,
 * rate limit and policy are covered through the real security chain by kelta-auth's
 * PasswordResetFlowTest.
 */
import { test, expect, type APIRequestContext } from "@playwright/test";
import { tenantApi } from "../../helpers/api-request";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";
const apiBaseUrl = process.env.E2E_API_BASE_URL || "https://kelta.io";
const mailpitUrl = process.env.E2E_MAILPIT_URL || "";

/** Meets the default password policy: no dictionary word, nothing of the user's name. */
const NEW_PASSWORD = "Qz7#Lm2!Vx9w-e2e";

test.use({
  storageState: { cookies: [], origins: [] },
  // The SPA's OIDC PKCE step needs crypto.subtle — see real-sign-in.spec.ts.
  ...(process.env.E2E_BROWSER_SECURE_ORIGINS ? { channel: "chromium" } : {}),
});

test.describe("Forgot password", () => {
  test.skip(
    process.env.E2E_REAL_SIGN_IN !== "true",
    "set E2E_REAL_SIGN_IN=true where the browser can reach kelta-auth's login form",
  );

  test("asking for a link shows the same confirmation for any email", async ({
    page,
  }) => {
    await page.goto(`/${tenantSlug}/login`, { waitUntil: "load" });
    await page.getByRole("link", { name: "Forgot password?" }).click();

    await page.getByLabel("Email").fill(`nobody-${Date.now()}@example.com`);
    await page.getByRole("button", { name: "Email me a reset link" }).click();

    // Enumeration-safe: the page does not say whether the address has an account.
    await expect(
      page.getByText("If an account exists with that email"),
    ).toBeVisible();
  });

  test("a user resets a forgotten password from the emailed link and signs in with it", async ({
    page,
    request,
  }) => {
    test.skip(!mailpitUrl, "E2E_MAILPIT_URL not set — the link is read from Mailpit");
    test.setTimeout(150_000);

    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const email = `e2e-forgot-${Date.now()}@example.com`;
    const profiles = await api<{ data?: { id: string }[] }>(
      "GET",
      `/api/profiles?filter[name][eq]=${encodeURIComponent("Standard User")}`,
    );
    const profileId = profiles.body?.data?.[0]?.id;
    expect(profileId, "the Standard User profile is seeded").toBeTruthy();

    const created = await api<{ data?: { id: string } }>("POST", "/api/users", {
      data: {
        type: "users",
        attributes: { email, firstName: "Forgot", lastName: "Flow", profileId },
      },
    });
    expect(created.status, `user create failed: ${JSON.stringify(created.body)}`).toBe(201);
    const userId = created.body.data!.id;

    try {
      // The invite gives the user a credential (no usable password yet).
      const invited = await api("POST", `/api/admin/users/${userId}/invite`);
      expect(invited.status, `invite failed: ${JSON.stringify(invited.body)}`).toBeLessThan(300);

      // From the app's sign-in page, as a person would.
      await page.goto(`/${tenantSlug}/login`, { waitUntil: "load" });
      await page.getByRole("link", { name: "Forgot password?" }).click();
      await page.getByLabel("Email").fill(email);
      await page.getByRole("button", { name: "Email me a reset link" }).click();
      await expect(
        page.getByText("If an account exists with that email"),
      ).toBeVisible();

      let link: string | undefined;
      await expect
        .poll(
          async () => {
            link = await findResetLink(request, email);
            return Boolean(link);
          },
          { timeout: 30_000, message: `no reset link mailed to ${email}` },
        )
        .toBe(true);

      // A password the tenant's policy refuses is not accepted.
      await page.goto(link!);
      await page.getByLabel("New Password", { exact: true }).fill("password123");
      await page.getByLabel("Confirm New Password").fill("password123");
      await page.getByRole("button", { name: "Reset Password" }).click();
      await expect(page.getByRole("alert")).toContainText("common");

      // The same link still works for a password that meets the policy.
      await page.getByLabel("New Password", { exact: true }).fill(NEW_PASSWORD);
      await page.getByLabel("Confirm New Password").fill(NEW_PASSWORD);
      await page.getByRole("button", { name: "Reset Password" }).click();

      // Same browser as the app's sign-in: back to the form, which resumes the app.
      await expect(
        page.getByText("Your password was reset. Sign in with your new password."),
      ).toBeVisible();
      await page.locator("#username").fill(email);
      await page.locator("#password").fill(NEW_PASSWORD);
      await page.getByRole("button", { name: "Sign In" }).click();
      await page.waitForURL(
        (url) =>
          url.pathname.startsWith(`/${tenantSlug}/`) &&
          !url.pathname.includes("/auth/callback") &&
          !url.pathname.includes("/login"),
        { timeout: 60_000 },
      );
      await expect(page.getByTestId("user-menu-button")).toBeVisible({
        timeout: 30_000,
      });

      // Single use: the link is refused afterwards.
      await page.goto(link!);
      await expect(
        page.getByText("This reset link is invalid, expired, or was already used."),
      ).toBeVisible();
    } finally {
      await api("DELETE", `/api/users/${userId}`);
    }
  });
});

interface MailpitSearch {
  messages?: { ID: string }[];
}

/** The /reset-password link mailed to `to` (the invite email, which has none, is skipped). */
async function findResetLink(
  request: APIRequestContext,
  to: string,
): Promise<string | undefined> {
  const search = await request.get(
    `${mailpitUrl}/api/v1/search?query=${encodeURIComponent(`to:${to}`)}&limit=10`,
  );
  if (!search.ok()) return undefined;
  const { messages = [] } = (await search.json()) as MailpitSearch;
  for (const { ID } of messages) {
    const message = await request.get(`${mailpitUrl}/api/v1/message/${ID}`);
    if (!message.ok()) continue;
    const { Text = "", HTML = "" } = (await message.json()) as {
      Text?: string;
      HTML?: string;
    };
    const found = `${Text}\n${HTML}`.match(
      /https?:\/\/[^\s"'<>]+\/reset-password\?token=[^\s"'<>&]+/,
    )?.[0];
    if (found) return found;
  }
  return undefined;
}
