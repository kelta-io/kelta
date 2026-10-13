import { test, expect } from "../../../fixtures";
import { FlowDesignerPage } from "../../../pages/flow-designer.page";
import { tenantApi } from "../../../helpers/api-request";

const DEFINITION = {
  StartAt: "Done",
  States: { Done: { Type: "Succeed" } },
};

test.describe("Flow step logging level", () => {
  test("is set from the trigger sheet and persisted on the flow", async ({
    page,
    dataFactory,
    tenantSlug,
  }) => {
    const flow = await dataFactory.createResource("flows", {
      name: `e2e_step_logging_${Date.now()}`,
      flowType: "AUTOLAUNCHED",
      active: false,
      definition: DEFINITION,
    });
    expect(flow.attributes?.stepLogging).toBe("FULL");

    const designer = new FlowDesignerPage(page, tenantSlug);
    await designer.goto(flow.id);
    await expect(designer.canvas).toBeVisible();

    await page
      .getByRole("button", { name: "Edit", exact: true })
      .first()
      .click();
    const sheet = page.getByRole("dialog");
    await expect(sheet.getByText("Step logging")).toBeVisible();

    await sheet.getByTestId("flow-step-logging-select").click();
    await expect(page.getByRole("option")).toHaveCount(3);
    await page.getByRole("option", { name: /errors only/i }).click();
    await sheet.getByRole("button", { name: "Save" }).click();
    await expect(sheet).toBeHidden();

    await expect
      .poll(
        async () =>
          (await dataFactory.getRecord("flows", flow.id)).attributes
            ?.stepLogging,
      )
      .toBe("ERRORS_ONLY");
  });

  test("the flows API rejects an unknown level with a 400", async ({
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const flow = await dataFactory.createResource("flows", {
      name: `e2e_step_logging_bad_${Date.now()}`,
      flowType: "AUTOLAUNCHED",
      active: false,
      definition: DEFINITION,
      stepLogging: "NONE",
    });
    expect(flow.attributes?.stepLogging).toBe("NONE");

    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const response = await api("PATCH", `/api/flows/${flow.id}`, {
      data: {
        type: "flows",
        id: flow.id,
        attributes: { stepLogging: "VERBOSE" },
      },
    });
    expect(response.status, JSON.stringify(response.body)).toBe(400);
  });
});
