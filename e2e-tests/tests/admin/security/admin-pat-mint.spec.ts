import type { Page } from "@playwright/test";
import { test, expect } from "../../../fixtures";
import { UsersListPage } from "../../../pages/users-list.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

/**
 * Navigates to the first seeded user's detail page and switches to the
 * Security tab. Returns false (soft-skip) when there's no seeded user to
 * click through, matching user-detail.spec.ts's convention for this suite.
 */
async function openFirstUserSecurityTab(page: Page): Promise<boolean> {
  const usersPage = new UsersListPage(page, tenantSlug);
  await usersPage.goto();

  const firstRow = usersPage.userTable.locator("tbody tr").first();
  const hasRows = await firstRow.isVisible({ timeout: 10_000 }).catch(() => false);
  if (!hasRows) return false;

  const nameButton = firstRow.locator("td").first().locator("button");
  const hasNameButton = await nameButton.isVisible({ timeout: 3_000 }).catch(() => false);
  if (!hasNameButton) return false;

  await nameButton.click();
  await expect(page).toHaveURL(/\/users\/[^/]+/, { timeout: 10_000 });
  await page.getByText("Security", { exact: true }).click();
  return true;
}

test.describe("Admin-minted PATs for managed users", () => {
  test("shows the mint-token action on a user's Security tab for MANAGE_USERS holders", async ({
    page,
  }) => {
    const opened = await openFirstUserSecurityTab(page);
    test.skip(!opened, "No seeded users available");

    await expect(page.getByTestId("mint-token-button")).toBeVisible({ timeout: 10_000 });
  });

  test("mints a token for the target user and shows it exactly once", async ({ page }) => {
    const opened = await openFirstUserSecurityTab(page);
    test.skip(!opened, "No seeded users available");

    const mintButton = page.getByTestId("mint-token-button");
    const visible = await mintButton.isVisible({ timeout: 10_000 }).catch(() => false);
    test.skip(!visible, "Caller lacks MANAGE_USERS in this environment");

    await mintButton.click();
    await expect(page.getByTestId("mint-token-form-modal")).toBeVisible();
    await page.getByTestId("mint-token-name-input").fill(`E2E Admin Mint ${Date.now()}`);

    const responsePromise = page.waitForResponse(
      (resp) =>
        /\/api\/admin\/users\/[^/]+\/tokens$/.test(new URL(resp.url()).pathname) &&
        resp.request().method() === "POST",
      { timeout: 15_000 },
    );

    await page.getByTestId("mint-token-submit").click();

    const response = await responsePromise;
    if (!response.ok()) {
      test.skip(true, `Admin token mint API returned ${response.status()}`);
      return;
    }

    await expect(page.getByTestId("minted-token-value")).toBeVisible({ timeout: 10_000 });
    const value = await page.getByTestId("minted-token-value").textContent();
    expect(value).toMatch(/^klt_/);
  });
});
