/**
 * Portal magic-link login (telehealth slice 1): kelta-auth's `/portal/login` form, the
 * emailed single-use link, and the hand-off into the tenant's end-user app.
 *
 * Imports the plain Playwright `test` with an empty storage state: the `../../fixtures`
 * page fixture re-injects the e2e admin's session on every navigation, which would mask
 * whether the portal member actually got signed in.
 *
 * The emailed-link test reads the message from Mailpit, so it runs only where
 * E2E_MAILPIT_URL points at the stack's Mailpit API (the CI stack sets it). The portal
 * invite contract itself (PORTAL user type, no credential, hashed token) is proven by the
 * kelta-test-harness PortalIdentityScenarioTest.
 */
import { test, expect, type APIRequestContext } from "@playwright/test";
import { tenantApi } from "../../helpers/api-request";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";
const authBaseUrl = process.env.E2E_AUTH_BASE_URL || "";
const apiBaseUrl = process.env.E2E_API_BASE_URL || "https://kelta.io";
const mailpitUrl = process.env.E2E_MAILPIT_URL || "";

test.use({
  storageState: { cookies: [], origins: [] },
  // The link lands in the SPA, whose OIDC PKCE step needs crypto.subtle — see
  // real-sign-in.spec.ts for why an http container origin needs full Chromium.
  ...(process.env.E2E_BROWSER_SECURE_ORIGINS ? { channel: "chromium" } : {}),
});

test.describe("Portal magic-link login", () => {
  test.skip(!authBaseUrl, "E2E_AUTH_BASE_URL not set");

  test("requesting a link shows the same confirmation for any email", async ({
    page,
  }) => {
    await page.goto(`${authBaseUrl}/portal/login?tenant=${tenantSlug}`);
    await expect(page.getByText("Portal sign in")).toBeVisible();

    const email = `nobody-${Date.now()}@example.com`;
    await page.getByLabel("Email").fill(email);
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();

    // Enumeration-safe: the response does not reveal whether the email is a portal user.
    await expect(
      page.getByRole("heading", { name: "Check your email" }),
    ).toBeVisible();
    await expect(page.getByText(email)).toBeVisible();
  });

  test("a portal member signs in through the emailed link, once", async ({
    page,
    request,
  }) => {
    test.skip(!mailpitUrl, "E2E_MAILPIT_URL not set — the link is read from Mailpit");
    test.setTimeout(120_000);

    // A fresh portal member (a unique address keeps the per-user link rate limit clear).
    const email = `e2e-portal-${Date.now()}@example.com`;
    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const invited = await api("POST", "/api/admin/users/portal-invite", {
      email,
      firstName: "Portal",
      lastName: "Member",
    });
    expect(
      invited.status,
      `portal invite failed: ${JSON.stringify(invited.body)}`,
    ).toBe(201);

    // Open the form on the issuer's own origin, so the session the link establishes is
    // the one the SPA's OIDC authorize request (to the issuer) then sees.
    const discovery = await request.get(
      `${authBaseUrl}/.well-known/openid-configuration`,
    );
    expect(discovery.ok(), "kelta-auth discovery document").toBe(true);
    const issuer = ((await discovery.json()) as { issuer: string }).issuer;

    await page.goto(`${issuer}/portal/login?tenant=${tenantSlug}`);
    await page.getByLabel("Email").fill(email);
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();
    await expect(
      page.getByRole("heading", { name: "Check your email" }),
    ).toBeVisible();

    // The sign-in email (not the invite) arrives in Mailpit.
    let link: string | undefined;
    await expect
      .poll(
        async () => {
          link = await findSignInLink(request, email);
          return Boolean(link);
        },
        { timeout: 30_000, message: `no sign-in link mailed to ${email}` },
      )
      .toBe(true);

    // The link signs the member in and lands in the tenant's end-user app.
    await page.goto(link!);
    await page.waitForURL(
      (url) =>
        url.pathname.startsWith(`/${tenantSlug}/app`) &&
        !url.pathname.includes("/auth/callback"),
      { timeout: 60_000 },
    );
    await expect(page.getByTestId("user-menu-button")).toBeVisible({
      timeout: 30_000,
    });

    // Single use: the same link, in a browser without the session, is refused.
    await page.context().clearCookies();
    await page.goto(link!);
    await expect(
      page.getByText("This sign-in link is invalid, expired, or was already used."),
    ).toBeVisible();
  });
});

interface MailpitSearch {
  messages?: { ID: string; Subject: string }[];
}

/** The verify link from the "Your sign-in link" email to `to` (the invite email is skipped). */
async function findSignInLink(
  request: APIRequestContext,
  to: string,
): Promise<string | undefined> {
  const search = await request.get(
    `${mailpitUrl}/api/v1/search?query=${encodeURIComponent(`to:${to}`)}&limit=10`,
  );
  if (!search.ok()) return undefined;
  const { messages = [] } = (await search.json()) as MailpitSearch;
  const signIn = messages.find((m) => m.Subject.startsWith("Your sign-in link"));
  if (!signIn) return undefined;
  const message = await request.get(`${mailpitUrl}/api/v1/message/${signIn.ID}`);
  if (!message.ok()) return undefined;
  const { Text = "" } = (await message.json()) as { Text?: string };
  return Text.match(/https?:\/\/\S+\/portal\/login\/verify\?token=[^\s"<>]+/)?.[0];
}
