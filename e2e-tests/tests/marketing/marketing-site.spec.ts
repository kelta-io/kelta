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

  // The documentation site: authored pages, the canonical authoring docs read
  // from docs/authoring in place, and the Pagefind index built in postbuild.
  test("serves the documentation site", async ({ request }) => {
    const index = await request.get(`${MARKETING_URL}/docs/`);
    expect(index.status()).toBe(200);
    expect(await index.text()).toContain("Documentation");

    const reference = await request.get(`${MARKETING_URL}/docs/reference/jsonapi/`);
    expect(reference.status()).toBe(200);
    expect(await reference.text()).toContain("JSON:API");

    const guide = await request.get(`${MARKETING_URL}/docs/getting-started/quickstart/`);
    expect(guide.status()).toBe(200);
  });

  // ScimDiscoveryController advertises https://kelta.io/docs/scim as the SCIM
  // documentationUri; the page lives under /docs/security/scim/ and the short
  // URL is a static redirect page.
  test("answers the advertised SCIM documentation URL", async ({ request }) => {
    const redirect = await request.get(`${MARKETING_URL}/docs/scim/`, { maxRedirects: 0 });
    expect(redirect.status()).toBe(200);
    expect(await redirect.text()).toContain("/docs/security/scim/");

    const page = await request.get(`${MARKETING_URL}/docs/security/scim/`);
    expect(page.status()).toBe(200);
    expect(await page.text()).toContain("SCIM");
  });

  test("ships the search index", async ({ request }) => {
    const entry = await request.get(`${MARKETING_URL}/pagefind/pagefind-entry.json`);
    expect(entry.status()).toBe(200);
    expect(entry.headers()["content-type"]).toContain("application/json");

    const loader = await request.get(`${MARKETING_URL}/pagefind/pagefind.js`);
    expect(loader.status()).toBe(200);
  });

  // No SPA fallback under /docs either: an unknown page is a real 404.
  test("answers 404 for an unknown docs page", async ({ request }) => {
    const response = await request.get(`${MARKETING_URL}/docs/definitely-not-a-page/`);
    expect(response.status()).toBe(404);
  });
});
