import { test, expect } from "../../../fixtures";
import { EmailSettingsPage } from "../../../pages/email-settings.page";

test.describe("Email Settings", () => {
  let emailSettingsPage: EmailSettingsPage;

  test.beforeEach(async ({ page }) => {
    emailSettingsPage = new EmailSettingsPage(page);
    await emailSettingsPage.goto();
  });

  test("renders the page and shows the platform default state", async () => {
    await expect(emailSettingsPage.heading).toBeVisible();
    await expect(emailSettingsPage.hostInput).toBeVisible();
    await expect(emailSettingsPage.fromAddressInput).toBeVisible();
    await expect(emailSettingsPage.testRecipientInput).toBeVisible();
    await expect(emailSettingsPage.saveButton).toBeVisible();
  });

  test("save sends the SMTP form to the API", async ({ page }) => {
    // This suite also runs post-deploy against a live tenant, so the save must
    // never reach the server: a persisted fake host replaces that tenant's real
    // mail settings until someone notices. Intercept the PUT, assert what the
    // form sent, and answer it the way the worker does.
    let sent: Record<string, unknown> | undefined;
    await page.route("**/api/admin/tenant/email-settings", async (route) => {
      if (route.request().method() !== "PUT") return route.continue();
      sent = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ status: "ok" }),
      });
    });

    await emailSettingsPage.hostInput.fill("smtp.acme.example");
    await emailSettingsPage.portInput.fill("2525");
    await emailSettingsPage.fromAddressInput.fill("noreply@acme.example");
    await emailSettingsPage.saveButton.click();

    await expect(page.getByText("Email settings saved")).toBeVisible();
    expect(sent).toMatchObject({
      host: "smtp.acme.example",
      port: 2525,
      fromAddress: "noreply@acme.example",
    });
  });

  test("test-send button disabled until a recipient is provided", async () => {
    await expect(emailSettingsPage.testSendButton).toBeDisabled();
    await emailSettingsPage.testRecipientInput.fill("qa@example.com");
    await expect(emailSettingsPage.testSendButton).toBeEnabled();
  });
});
