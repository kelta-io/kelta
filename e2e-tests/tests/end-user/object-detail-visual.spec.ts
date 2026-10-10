/**
 * Visual-regression coverage for the runtime record detail page.
 *
 * Seeds a deterministic collection + record, navigates to the detail view,
 * then takes a full-page screenshot in both dark and light themes. The
 * theme is forced by setting `kelta_theme_mode` in localStorage before the
 * page loads so the snapshot doesn't depend on the user's system preference.
 *
 * Per-run-variable chrome is fixed or masked so snapshots don't churn:
 *   - the collection is created with a fixed display name, so the breadcrumb
 *     and the header badge don't render the generated `e2e_test_<ts>_<rand>`;
 *   - Gravatar requests are aborted, so the UserMenu avatar always renders
 *     the admin user's initials instead of racing an external image fetch;
 *   - masked: the record id pill, the header "Joined <createdAt>" meta row,
 *     the system-information timestamps/ids/authors, and the notifications
 *     bell (its unread badge depends on what earlier specs did in the tenant).
 *
 * Baselines must come from CI's Linux E2E container: list this spec in
 * `e2e-tests/.update-snapshots` and CI regenerates and pushes them
 * (`.claude/docs/testing.md` → Updating visual baselines). Never raise
 * `maxDiffPixelRatio` to absorb churn; mask or stub the source instead.
 */

import type { Page } from "@playwright/test";
import { test, expect } from "../../fixtures";
import { ObjectDetailPage } from "../../pages/end-user/object-detail.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

// Fixed so the breadcrumb and header badge don't show the generated name.
const COLLECTION_DISPLAY_NAME = "Visual Snapshot Collection";

// Gravatar is an external fetch that races the screenshot; aborting it makes
// UserMenu fall back to the (stable) initials of the E2E admin user.
async function blockGravatar(page: Page): Promise<void> {
  await page.route(/gravatar\.com/, (route) => route.abort());
}

const MASK_SELECTORS = [
  // Record id pill in RecordHeader (UUID changes per run)
  '[data-component="RecordHeader"] .font-mono',
  // Header meta row under the title ("Joined <createdAt>" changes per run)
  '[data-component="RecordHeader"] h1 + div',
  // Notifications bell (unread badge count depends on earlier specs in the run)
  // (descendants too: the badge is absolutely positioned outside the button box)
  'button[aria-label^="Notifications"], button[aria-label^="Notifications"] *',
  // System-information rail rows (created / updated timestamps + author)
  '[data-component="MetadataCard"] dd',
  // System-information tab card (timestamps + ids)
  '[data-testid="system-internal-id"]',
  '[data-testid="system-created-at"]',
  '[data-testid="system-updated-at"]',
];

test.describe("Record detail — visual regression", () => {
  test.describe.configure({ mode: "serial" });

  test("dark theme", async ({ page, dataFactory }) => {
    const collection = await dataFactory.createCollection({
      displayName: COLLECTION_DISPLAY_NAME,
    });
    await dataFactory.addField(collection.id, {
      name: "title",
      displayName: "Title",
      type: "string",
      required: true,
    });
    await dataFactory.addField(collection.id, {
      name: "summary",
      displayName: "Summary",
      type: "string",
    });
    await dataFactory.waitForStorageReady(
      collection.attributes.name as string,
    );

    const record = await dataFactory.createRecord(
      collection.attributes.name as string,
      {
        title: "Visual snapshot record",
        summary: "Stable seed for visual regression",
      },
    );

    // Force dark theme before navigation.
    await page.addInitScript(() => {
      window.localStorage.setItem("kelta_theme_mode", "dark");
    });

    const detail = new ObjectDetailPage(
      page,
      collection.attributes.name as string,
      record.id as string,
      tenantSlug,
    );
    await blockGravatar(page);
    await detail.goto();

    await expect(detail.fieldValues).toBeVisible();

    await expect(page).toHaveScreenshot("object-detail-dark.png", {
      fullPage: true,
      mask: MASK_SELECTORS.map((s) => page.locator(s)),
      // 0.5% tolerance for sub-pixel font rendering across machines
      maxDiffPixelRatio: 0.005,
      animations: "disabled",
    });
  });

  test("light theme", async ({ page, dataFactory }) => {
    const collection = await dataFactory.createCollection({
      displayName: COLLECTION_DISPLAY_NAME,
    });
    await dataFactory.addField(collection.id, {
      name: "title",
      displayName: "Title",
      type: "string",
      required: true,
    });
    await dataFactory.waitForStorageReady(
      collection.attributes.name as string,
    );

    const record = await dataFactory.createRecord(
      collection.attributes.name as string,
      { title: "Visual snapshot record" },
    );

    await page.addInitScript(() => {
      window.localStorage.setItem("kelta_theme_mode", "light");
    });

    const detail = new ObjectDetailPage(
      page,
      collection.attributes.name as string,
      record.id as string,
      tenantSlug,
    );
    await blockGravatar(page);
    await detail.goto();

    await expect(detail.fieldValues).toBeVisible();

    await expect(page).toHaveScreenshot("object-detail-light.png", {
      fullPage: true,
      mask: MASK_SELECTORS.map((s) => page.locator(s)),
      maxDiffPixelRatio: 0.005,
      animations: "disabled",
    });
  });
});
