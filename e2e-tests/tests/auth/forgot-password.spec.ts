/**
 * Invite and forgot password, signed out. An invite email links to kelta-auth's
 * /reset-password page, where the invited user sets a first password; kelta-auth's login
 * form links to /forgot-password, which mails a single-use link to the same page. Every
 * password must meet the tenant's policy, and afterwards the user signs in with it.
 *
 * Imports the plain Playwright `test` with an empty storage state: the `../../fixtures`
 * page fixture re-injects the e2e admin's session on every navigation. The flow uses a
 * fresh user (never the e2e admin, whose password other specs rely on) and reads the
 * link from Mailpit, so it runs only where E2E_MAILPIT_URL points at the stack's Mailpit
 * API and E2E_REAL_SIGN_IN is on (the CI stack sets both). The JSON endpoints, CSRF,
 * rate limit and policy are covered through the real security chain by kelta-auth's
 * PasswordResetFlowTest.
 */
import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { tenantApi } from "../../helpers/api-request";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";
const apiBaseUrl = process.env.E2E_API_BASE_URL || "https://kelta.io";
const mailpitUrl = process.env.E2E_MAILPIT_URL || "";

/** Both meet the default password policy: no dictionary word and no part of the user's name
 * or email — the address is `e2e-forgot-…`, so not even "e2e". */
const FIRST_PASSWORD = "Qz7#Lm2!Vx9w-Kp4";
const NEW_PASSWORD = "Wy4$Hn8@Tc3q-Rm6";

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

  test("an invited user sets a first password from the invite, then resets a forgotten one", async ({
    page,
    browser,
    request,
  }) => {
    test.skip(!mailpitUrl, "E2E_MAILPIT_URL not set — the links are read from Mailpit");
    test.setTimeout(240_000);

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
      // An invite (re-)sent now: its link holds the user's one live token.
      const invited = await api("POST", `/api/admin/users/${userId}/invite`);
      expect(invited.status, `invite failed: ${JSON.stringify(invited.body)}`).toBeLessThan(300);
      const inviteLink = await pollLink(request, email, (subject) => !/^Reset/.test(subject));

      // The invite opens kelta-auth's page, worded for a first password.
      await page.goto(inviteLink);
      await expect(page.getByText("Set your password")).toBeVisible();
      await page.getByLabel("New Password", { exact: true }).fill("password123");
      await page.getByLabel("Confirm New Password").fill("password123");
      await page.getByRole("button", { name: "Set Password" }).click();
      await expect(page.getByRole("alert")).toContainText("common");

      // The same link still works for a password that meets the policy.
      await page.getByLabel("New Password", { exact: true }).fill(FIRST_PASSWORD);
      await page.getByLabel("Confirm New Password").fill(FIRST_PASSWORD);
      await page.getByRole("button", { name: "Set Password" }).click();
      await expect(page.getByText("Your password is set. Sign in with it.")).toBeVisible();

      // Opened from the email, not from an app: the page links to the tenant's app.
      await page.getByRole("link", { name: "Continue to sign in" }).click();
      await signInAndLand(page, email, FIRST_PASSWORD);

      // Later, on another device, the password is forgotten.
      const other = await browser.newContext({
        baseURL: test.info().project.use.baseURL,
        storageState: { cookies: [], origins: [] },
      });
      try {
        const device = await other.newPage();
        await device.goto(`/${tenantSlug}/login`, { waitUntil: "load" });
        await device.getByRole("link", { name: "Forgot password?" }).click();
        await device.getByLabel("Email").fill(email);
        await device.getByRole("button", { name: "Email me a reset link" }).click();
        await expect(device.getByText("If an account exists with that email")).toBeVisible();
        const resetLink = await pollLink(request, email, (subject) => /^Reset/.test(subject));

        await device.goto(resetLink);
        await expect(device.getByText("Choose a new password")).toBeVisible();
        await device.getByLabel("New Password", { exact: true }).fill(FIRST_PASSWORD);
        await device.getByLabel("Confirm New Password").fill(FIRST_PASSWORD);
        await device.getByRole("button", { name: "Reset Password" }).click();
        await expect(device.getByRole("alert")).toContainText("recently used");

        await device.getByLabel("New Password", { exact: true }).fill(NEW_PASSWORD);
        await device.getByLabel("Confirm New Password").fill(NEW_PASSWORD);
        await device.getByRole("button", { name: "Reset Password" }).click();

        // Same browser as the app's sign-in: back to the form, which resumes the app.
        await expect(device.getByText("Your new password is set. Sign in with it.")).toBeVisible();
        await device.locator("#username").fill(email);
        await device.locator("#password").fill(NEW_PASSWORD);
        await device.getByRole("button", { name: "Sign In" }).click();
        await waitForApp(device);

        // Single use: both links are refused afterwards.
        for (const link of [resetLink, inviteLink]) {
          await device.goto(link);
          await expect(
            device.getByText("This link is invalid, expired, or was already used."),
          ).toBeVisible();
        }
      } finally {
        await other.close();
      }
    } finally {
      await api("DELETE", `/api/users/${userId}`);
    }
  });
});

async function waitForApp(page: Page): Promise<void> {
  await page.waitForURL(
    (url) =>
      url.pathname.startsWith(`/${tenantSlug}/`) &&
      !url.pathname.includes("/auth/callback") &&
      !url.pathname.includes("/login"),
    { timeout: 60_000 },
  );
  await expect(page.getByTestId("user-menu-button")).toBeVisible({ timeout: 30_000 });
}

/** From the app's hand-off to kelta-auth's form: sign in and land in the app. */
async function signInAndLand(page: Page, email: string, password: string): Promise<void> {
  await page.locator("#username").waitFor({ state: "visible", timeout: 45_000 });
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();
  await waitForApp(page);
}

/** Waits for the newest /reset-password link mailed to `to` in a message whose subject matches. */
async function pollLink(
  request: APIRequestContext,
  to: string,
  subject: (subject: string) => boolean,
): Promise<string> {
  let link: string | undefined;
  await expect
    .poll(
      async () => {
        link = await findResetLink(request, to, subject);
        return Boolean(link);
      },
      { timeout: 30_000, message: `no link mailed to ${to}` },
    )
    .toBe(true);
  return link!;
}

interface MailpitSearch {
  messages?: { ID: string; Subject: string }[];
}

/** The /reset-password link in the newest message to `to` whose subject matches. */
async function findResetLink(
  request: APIRequestContext,
  to: string,
  subject: (subject: string) => boolean,
): Promise<string | undefined> {
  const search = await request.get(
    `${mailpitUrl}/api/v1/search?query=${encodeURIComponent(`to:${to}`)}&limit=20`,
  );
  if (!search.ok()) return undefined;
  const { messages = [] } = (await search.json()) as MailpitSearch;
  // Mailpit lists the newest message first.
  for (const { ID, Subject } of messages) {
    if (!subject(Subject)) continue;
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
