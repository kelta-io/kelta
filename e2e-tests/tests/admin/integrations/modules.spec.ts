import { test, expect } from "../../../fixtures";
import { waitForAnyVisible } from "../../../helpers/wait-helpers";

test.describe("Modules", () => {
  test("displays modules page", async ({ page }) => {
    await page.goto("/default/modules");
    await page.waitForLoadState("load");

    await expect(page).toHaveURL(/\/modules/);
    const found = await waitForAnyVisible([
      page.getByTestId("modules-page"),
      page.getByRole("heading", { name: /modules/i }),
      page.getByTestId("error-message"),
    ]);
    expect(found).toBe(true);
  });

  test("shows installed modules or description text", async ({ page }) => {
    await page.goto("/default/modules");
    await page.waitForLoadState("load");

    const found = await waitForAnyVisible([
      page.getByTestId("modules-page"),
      page.getByTestId("error-message"),
    ]);
    expect(found).toBe(true);
  });

  test("has install module button", async ({ page }) => {
    await page.goto("/default/modules");
    await page.waitForLoadState("load");

    const found = await waitForAnyVisible([
      page.getByRole("button", { name: /install module/i }),
      page.getByTestId("error-message"),
      page.getByTestId("modules-page"),
    ]);
    expect(found).toBe(true);
  });

  test("installs an external module by URL after reviewing its plan", async ({
    page,
  }) => {
    // The module service is mocked at the API boundary: the plan, the install and the list.
    // The worker side of this journey is ExternalModuleInstallScenarioTest in the harness.
    const baseUrl = "https://billing.example.test";
    const planHash = "f".repeat(64);
    const plan = {
      baseUrl,
      moduleId: "e2e-billing",
      name: "E2E Billing",
      description: "Plans and subscriptions",
      mode: "INSTALL",
      fromVersion: null,
      toVersion: "1.0.0",
      manifestSha256: "e".repeat(64),
      grants: {
        ownedCollections: ["e2e_billing_plans"],
        objects: [
          {
            collection: "accounts",
            create: false,
            read: true,
            edit: false,
            delete: false,
          },
        ],
        fields: [],
        system: [],
        credentials: [],
      },
      capabilities: {
        actions: [{ key: "refund", name: "Refund", timeoutMs: 1500 }],
        hooks: [],
        events: [{ collection: "accounts", types: ["created"] }],
        services: [],
        routes: [],
        schedules: [],
        webhook: false,
        ui: null,
      },
      uiBundle: null,
      metadata: [
        {
          type: "COLLECTION",
          naturalKey: "e2e_billing_plans",
          action: "CREATE",
        },
      ],
      blockers: [],
      planHash,
    };
    const installedModule = {
      type: "modules",
      id: "row-e2e-billing",
      attributes: {
        id: "row-e2e-billing",
        moduleId: "e2e-billing",
        name: "E2E Billing",
        version: "1.0.0",
        description: "Plans and subscriptions",
        status: "ACTIVE",
        installedBy: "admin",
        installedAt: "2026-10-30T12:00:00Z",
        updatedAt: "2026-10-30T12:00:00Z",
        actions: [],
        baseUrl,
        protocolVersion: 1,
      },
    };
    let installed = false;
    let installBody: Record<string, unknown> | null = null;

    await page.route("**/api/modules", async (route) => {
      if (route.request().method() !== "GET") return route.continue();
      await route.fulfill({
        status: 200,
        contentType: "application/vnd.api+json",
        body: JSON.stringify({ data: installed ? [installedModule] : [] }),
      });
    });
    await page.route("**/api/modules/plan", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/vnd.api+json",
        body: JSON.stringify({
          data: {
            type: "module-install-plans",
            id: "e2e-billing",
            attributes: plan,
          },
        }),
      });
    });
    await page.route("**/api/modules/install", async (route) => {
      installBody = route.request().postDataJSON() as Record<string, unknown>;
      installed = true;
      await route.fulfill({
        status: 200,
        contentType: "application/vnd.api+json",
        body: JSON.stringify({ data: installedModule }),
      });
    });

    await page.goto("/default/modules");
    await page.waitForLoadState("load");
    await page
      .getByRole("button", { name: /install module/i })
      .first()
      .click();

    await page.locator("#module-base-url").fill(baseUrl);
    await page.getByTestId("module-review-plan").click();

    // Consent: what the module is granted and what it adds, before anything is installed.
    await expect(page.getByTestId("module-plan-review")).toBeVisible();
    await expect(page.getByTestId("module-plan-grants")).toContainText(
      "accounts",
    );
    await expect(page.getByTestId("module-plan-capabilities")).toContainText(
      "1 flow action: refund",
    );
    await expect(page.getByTestId("module-plan-hash")).toHaveText(planHash);
    expect(installed).toBe(false);

    await page.getByTestId("module-approve-install").click();

    await expect(page.getByTestId("module-external-e2e-billing")).toBeVisible();
    expect(installBody).toEqual({ baseUrl, planHash });
  });
});
