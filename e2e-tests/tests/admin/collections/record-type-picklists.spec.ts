import { test, expect } from "../../../fixtures";
import { CollectionDetailPage } from "../../../pages/collection-detail.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

const STATUS_VALUES = ["open", "closed", "pending"];

/**
 * Record type picklist overrides round-trip: the collection designer's Record Types tab
 * restricts a picklist field for one record type, the editor persists it to the
 * `record-type-picklists` system collection (the rows the record form and the worker's
 * enforcement hook read), and reopening the editor shows the stored override.
 */
test.describe("Record type picklist overrides", () => {
  test("saves allowed values and a default for a record type and loads them back", async ({
    page,
    dataFactory,
  }) => {
    const collection = await dataFactory.createCollection({
      displayName: `Record Type Picklists ${Date.now()}`,
    });
    const status = await dataFactory.addField(collection.id, {
      name: "status",
      displayName: "Status",
      type: "picklist",
    });
    for (const [sortOrder, value] of STATUS_VALUES.entries()) {
      await dataFactory.createResource("picklist-values", {
        picklistSourceType: "FIELD",
        picklistSourceId: status.id,
        value,
        label: value,
        sortOrder,
        isActive: true,
      });
    }
    const recordType = await dataFactory.createResource("record-types", {
      collectionId: collection.id,
      name: `Restricted ${Date.now()}`,
      isActive: true,
      isDefault: false,
    });

    const detailPage = new CollectionDetailPage(page, tenantSlug);
    const openEditor = async () => {
      await detailPage.goto(collection.id);
      await detailPage.clickTab("record-types");
      await page.getByTestId("picklist-overrides-0").click();
      await expect(page.getByTestId("picklist-override-modal")).toBeVisible();
    };
    const valueCheckbox = (value: string) =>
      page.getByTestId(`value-checkbox-${status.id}-${value}`);

    // The status badge reads the record type's `isActive` attribute
    await detailPage.goto(collection.id);
    await detailPage.clickTab("record-types");
    await expect(page.getByTestId("record-type-row-0")).toContainText("Active");
    await expect(page.getByTestId("record-type-row-0")).not.toContainText(
      "Inactive",
    );

    await openEditor();
    // No override yet: every value is allowed
    await expect(valueCheckbox("pending")).toBeChecked();

    await valueCheckbox("pending").uncheck();
    await page.getByTestId(`default-select-${status.id}`).selectOption("open");

    const createResponse = page.waitForResponse(
      (resp) =>
        resp.request().method() === "POST" &&
        resp.url().includes("/api/record-type-picklists"),
    );
    await page.getByTestId("picklist-override-save").click();
    expect((await createResponse).ok()).toBe(true);
    await expect(page.getByTestId("picklist-override-modal")).toBeHidden();

    // Persisted in the shape the runtime reads: a JSON array plus the default
    const rows = await dataFactory.queryRecords("record-type-picklists", {
      recordTypeId: recordType.id,
    });
    expect(rows).toHaveLength(1);
    expect(rows[0].attributes.availableValues).toEqual(["open", "closed"]);
    expect(rows[0].attributes.defaultValue).toBe("open");

    // Reopening the editor shows the stored override
    await openEditor();
    await expect(valueCheckbox("pending")).not.toBeChecked();
    await expect(valueCheckbox("open")).toBeChecked();
    await expect(page.getByTestId(`default-select-${status.id}`)).toHaveValue(
      "open",
    );
  });
});
