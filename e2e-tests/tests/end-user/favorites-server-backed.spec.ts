import { test, expect } from "../../fixtures";
import type { Page, Response } from "@playwright/test";
import { AppHomePage } from "../../pages/end-user/app-home.page";

/**
 * Favorites are server-persisted per user (user-ui-preferences, prefType `favorites`;
 * KLT-481): a favorite pushed from one browser's localStorage must render on the home
 * page after that localStorage is wiped — i.e. in any other browser.
 *
 * The end-user UI has no star toggle yet, so the favorite is seeded the way an existing
 * browser carries one (the per-tenant localStorage key) and the one-time push does the
 * rest. The test owns the e2e user's single `favorites` row: any leftover is deleted
 * before, and the row it creates is deleted in `finally`.
 */

const PREFS_PATH = "/api/user-ui-preferences";

function isFavoritesRead(response: Response): boolean {
  const url = decodeURIComponent(response.url());
  return (
    response.request().method() === "GET" &&
    url.includes(PREFS_PATH) &&
    url.includes("filter[prefType][eq]=favorites")
  );
}

function isPreferenceCreate(response: Response): boolean {
  return (
    response.request().method() === "POST" &&
    new URL(response.url()).pathname.endsWith(PREFS_PATH)
  );
}

async function deleteRow(
  page: Page,
  apiPrefix: string,
  authorization: string,
  id: string,
): Promise<void> {
  await page.request.delete(`${apiPrefix}${PREFS_PATH}/${id}`, {
    headers: { Authorization: authorization },
  });
}

test.describe("End-user favorites follow the user", () => {
  test("a pushed favorite survives a wiped localStorage", async ({
    page,
    tenantSlug,
  }) => {
    const homePage = new AppHomePage(page, tenantSlug);
    const localKey = `kelta_favorites:${tenantSlug}`;
    const label = `E2E favorite ${Date.now()}`;
    let apiPrefix = "";
    let authorization = "";
    let createdId: string | null = null;

    try {
      // 1. Load once to learn the API prefix + bearer the app uses, and clear any
      //    favorites row a previous run left behind for this user.
      const firstRead = page.waitForResponse(isFavoritesRead);
      await homePage.goto();
      const read = await firstRead;
      apiPrefix = read.url().split(PREFS_PATH)[0];
      authorization = (await read.request().allHeaders())["authorization"] ?? "";
      expect(authorization).not.toBe("");
      const existing = (await read.json()) as { data?: Array<{ id: string }> };
      for (const row of existing.data ?? []) {
        await deleteRow(page, apiPrefix, authorization, row.id);
      }

      // 2. This browser has a local favorite and the server has none → one-time push.
      await page.evaluate(
        ([key, value]) => localStorage.setItem(key, value),
        [
          localKey,
          JSON.stringify([
            {
              key: `collection:e2e-favorite-${Date.now()}`,
              label,
              type: "collection",
              collectionName: "e2e-favorite",
            },
          ]),
        ] as const,
      );
      const push = page.waitForResponse(isPreferenceCreate);
      await page.reload();
      const created = await push;
      expect(created.ok()).toBe(true);
      expect(created.request().postData() ?? "").toContain('"favorites"');
      createdId =
        ((await created.json()) as { data?: { id?: string } }).data?.id ?? null;
      expect(createdId).toBeTruthy();

      // 3. A browser without the local copy still shows it — read from the server.
      await page.evaluate((key) => localStorage.removeItem(key), localKey);
      await page.reload();
      await expect(
        page.locator("[data-slot='card']").filter({ has: homePage.favorites }),
      ).toContainText(label, { timeout: 15_000 });
    } finally {
      if (createdId && apiPrefix && authorization) {
        await deleteRow(page, apiPrefix, authorization, createdId);
      }
      await page.evaluate((key) => localStorage.removeItem(key), localKey);
    }
  });
});
