import { test, expect } from "@playwright/test";

/**
 * Marketing site (kelta.io) — post-deploy spec (KLT-268).
 *
 * Runs only against the deployed site: the PR-level e2e job points
 * E2E_BASE_URL at the local compose stack, which serves the app, not the
 * marketing site. The production e2e job sets E2E_MARKETING_URL; without it
 * the suite skips.
 */

const MARKETING_URL = process.env.E2E_MARKETING_URL;
const WWW_URL =
  process.env.E2E_MARKETING_WWW_URL ??
  MARKETING_URL?.replace("://", "://www.");

test.describe("Marketing site", () => {
  test.skip(
    !MARKETING_URL,
    "E2E_MARKETING_URL not set — deployed marketing site not under test",
  );

  test("apex and www both serve the site", async ({ request }) => {
    for (const url of [MARKETING_URL!, WWW_URL!]) {
      const response = await request.get(`${url}/`);
      expect(response.status(), `${url}/ should serve the site`).toBe(200);
      expect(await response.text()).toContain("Kelta");
    }
  });

  // CHARTER.md §2 makes publishing pricing a red-zone [DECISION]; the route was
  // removed, and nginx answers a real 404 rather than falling back to the home
  // page, so "removed" stays observable from outside.
  test("does not publish a pricing page", async ({ request }) => {
    const response = await request.get(`${MARKETING_URL}/pricing`);
    expect(response.status()).toBe(404);
  });
});
