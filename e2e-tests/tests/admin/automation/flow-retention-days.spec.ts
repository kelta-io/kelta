import { test, expect } from "../../../fixtures";
import { FlowDesignerPage } from "../../../pages/flow-designer.page";
import { tenantApi } from "../../../helpers/api-request";

const DEFINITION = {
  StartAt: "Done",
  States: { Done: { Type: "Succeed" } },
};

test.describe("Flow run-history retention", () => {
  test("is set from the trigger sheet and persisted on the flow", async ({
    page,
    dataFactory,
    tenantSlug,
  }) => {
    const flow = await dataFactory.createResource("flows", {
      name: `e2e_retention_${Date.now()}`,
      flowType: "AUTOLAUNCHED",
      active: false,
      definition: DEFINITION,
    });
    expect(flow.attributes?.retentionDays ?? null).toBeNull();

    const designer = new FlowDesignerPage(page, tenantSlug);
    await designer.goto(flow.id);
    await expect(designer.canvas).toBeVisible();

    await page
      .getByRole("button", { name: "Edit", exact: true })
      .first()
      .click();
    const sheet = page.getByRole("dialog");
    const input = sheet.getByTestId("flow-retention-days-input");
    await expect(input).toHaveValue("");

    await input.fill("7");
    await sheet.getByRole("button", { name: "Save" }).click();
    await expect(sheet).toBeHidden();

    await expect
      .poll(
        async () =>
          (await dataFactory.getRecord("flows", flow.id)).attributes
            ?.retentionDays,
      )
      .toBe(7);
  });

  test("the flows API rejects zero, negative and fractional days with a 400", async ({
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const flow = await dataFactory.createResource("flows", {
      name: `e2e_retention_bad_${Date.now()}`,
      flowType: "AUTOLAUNCHED",
      active: false,
      definition: DEFINITION,
      retentionDays: 30,
    });
    expect(flow.attributes?.retentionDays).toBe(30);

    const api = tenantApi({ apiBaseUrl, tenantSlug });
    for (const retentionDays of [0, -1, 7.5]) {
      const response = await api("PATCH", `/api/flows/${flow.id}`, {
        data: { type: "flows", id: flow.id, attributes: { retentionDays } },
      });
      expect(response.status, JSON.stringify(response.body)).toBe(400);
    }
    expect(
      (await dataFactory.getRecord("flows", flow.id)).attributes?.retentionDays,
    ).toBe(30);
  });
});
