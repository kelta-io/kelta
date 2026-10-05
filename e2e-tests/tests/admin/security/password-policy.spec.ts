import { test, expect } from "../../../fixtures";

test.describe("Password Policy", () => {
  test.beforeEach(async ({ page, tenantSlug }) => {
    await page.goto(`/${tenantSlug}/password-policy`);
    await page.waitForLoadState("load");
  });

  test("displays password policy settings page", async ({ page }) => {
    await expect(page.getByTestId("password-policy-panel")).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Password Policy", level: 1 }),
    ).toBeVisible();
  });

  test("shows default policy values", async ({ page }) => {
    // NIST SP 800-63B defaults served when the tenant has no stored policy
    const minLength = page.getByTestId("min-length-input");
    await expect(minLength).toBeVisible();
    await expect(minLength).toHaveValue("8");
    await expect(page.getByTestId("max-length-input")).toHaveValue("128");
    await expect(page.getByTestId("history-count-input")).toHaveValue("3");
    await expect(page.getByTestId("lockout-threshold-input")).toHaveValue("5");
    await expect(page.getByTestId("lockout-duration-input")).toHaveValue("30");
    await expect(page.getByTestId("max-age-days-input")).toHaveValue("");

    const dictCheck = page.getByTestId("dictionary-check-checkbox");
    await expect(dictCheck).toBeVisible();
    await expect(dictCheck).toBeChecked();

    const personalCheck = page.getByTestId("personal-data-check-checkbox");
    await expect(personalCheck).toBeVisible();
    await expect(personalCheck).toBeChecked();

    for (const field of [
      "requireUppercase",
      "requireLowercase",
      "requireDigit",
      "requireSpecial",
    ]) {
      await expect(page.getByTestId(`${field}-checkbox`)).not.toBeChecked();
    }
  });

  test("shows save button", async ({ page }) => {
    // Not clicked: saving would persist a policy on the shared tenant
    const saveButton = page.getByTestId("save-policy-button");
    await expect(saveButton).toBeVisible();
    await expect(saveButton).toBeEnabled();
    await expect(saveButton).toHaveText("Save Policy");
  });
});
