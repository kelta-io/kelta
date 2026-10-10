import type { Page } from "@playwright/test";
import { test, expect } from "../../fixtures";
import { AppHomePage } from "../../pages/end-user/app-home.page";

/**
 * Member self-profile: the user menu's "Profile" item opens a dialog backed by
 * GET/PATCH /api/me/profile, and saved values survive a reload.
 */
test.describe("Profile dialog", () => {
  async function openProfileDialog(page: Page) {
    await page.getByTestId("user-menu-button").click();
    await page.getByTestId("profile-menu-item").click();
    const dialog = page.getByTestId("profile-dialog");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("profile-first-name")).toBeVisible({
      timeout: 15_000,
    });
    return dialog;
  }

  async function saveProfile(page: Page) {
    const saved = page.waitForResponse(
      (resp) =>
        resp.url().includes("/api/me/profile") &&
        resp.request().method() === "PATCH",
      { timeout: 15_000 },
    );
    await page.getByTestId("profile-save").click();
    const response = await saved;
    expect(response.status()).toBe(200);
    await expect(page.getByTestId("profile-dialog")).toBeHidden();
  }

  test("saves name and time zone and keeps them after reload", async ({
    page,
  }) => {
    const homePage = new AppHomePage(page);
    await homePage.goto();

    await openProfileDialog(page);
    const lastNameInput = page.getByTestId("profile-last-name");
    const timezoneSelect = page.getByTestId("profile-timezone");
    const originalLastName = await lastNameInput.inputValue();
    const originalTimezone = await timezoneSelect.inputValue();

    const newLastName = `E2E ${Date.now().toString(36)}`;
    const newTimezone =
      originalTimezone === "Europe/Lisbon" ? "Asia/Tokyo" : "Europe/Lisbon";
    await lastNameInput.fill(newLastName);
    await timezoneSelect.selectOption(newTimezone);
    await saveProfile(page);

    try {
      // The page is still interactive once the dialog closes (no focus trap or
      // pointer-events lock left behind by the menu → dialog hand-off).
      await page.getByTestId("user-menu-button").click();
      await expect(page.getByTestId("profile-menu-item")).toBeVisible();
      await page.keyboard.press("Escape");

      await page.reload();
      await homePage.waitForPageLoad();
      await openProfileDialog(page);
      await expect(page.getByTestId("profile-last-name")).toHaveValue(
        newLastName,
      );
      await expect(page.getByTestId("profile-timezone")).toHaveValue(
        newTimezone,
      );
    } finally {
      // Put the shared admin user back the way it was.
      if (!(await page.getByTestId("profile-dialog").isVisible())) {
        await openProfileDialog(page);
      }
      await page.getByTestId("profile-last-name").fill(originalLastName);
      await page.getByTestId("profile-timezone").selectOption(originalTimezone);
      await saveProfile(page);
    }
  });
});
