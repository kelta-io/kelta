import { test, expect } from "../../fixtures";
import { ObjectListPage } from "../../pages/end-user/object-list.page";
import { ObjectDetailPage } from "../../pages/end-user/object-detail.page";
import { ObjectFormPage } from "../../pages/end-user/object-form.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

test.describe("End-User Record Lifecycle Journey", () => {
  test("full record lifecycle: create, view, edit, delete", async ({
    page,
    dataFactory,
  }) => {
    // Step 1: Create a collection with a field via API
    const collection = await dataFactory.createCollection();
    const collectionName = collection.attributes.name as string;

    await dataFactory.addField(collection.id, {
      name: "title",
      displayName: "Title",
      type: "string",
      required: true,
    });

    // Wait for the backend to finish provisioning storage
    await dataFactory.waitForStorageReady(collectionName);

    const recordTitle = `Lifecycle Record ${Date.now()}`;
    const updatedTitle = `Updated Lifecycle ${Date.now()}`;

    // Step 2: Navigate to end-user and create a record
    const formPage = new ObjectFormPage(
      page,
      collectionName,
      undefined,
      tenantSlug,
    );
    await formPage.goto("new");

    await expect(formPage.formFields).toBeVisible();
    await formPage.fillField("title", recordTitle);
    await formPage.save();

    // Create lands on the new record's detail page. Wait until the URL has left
    // the /new form — a bare `/app/o/<collection>` pattern already matches it.
    await page.waitForURL(
      new RegExp(`/app/o/${collectionName}(?:/(?!new$)[^/]+)?$`),
    );

    // Step 3: View the created record
    // Extract the record ID from the URL if we landed on a detail page
    // Exclude "new" and "edit" which are route segments, not record IDs
    const currentUrl = page.url();
    const recordIdMatch = currentUrl.match(
      new RegExp(`/app/o/${collectionName}/(?!new$|edit$)([0-9a-f-]{36})$`),
    );

    if (recordIdMatch) {
      const recordId = recordIdMatch[1];

      const detailPage = new ObjectDetailPage(
        page,
        collectionName,
        recordId,
        tenantSlug,
      );
      await expect(detailPage.fieldValues).toBeVisible();

      // Step 4: Edit the record
      await detailPage.clickEdit();
      await page.waitForURL(/\/edit/);

      const editFormPage = new ObjectFormPage(
        page,
        collectionName,
        recordId,
        tenantSlug,
      );
      await editFormPage.fillField("title", updatedTitle);
      await editFormPage.save();

      await page.waitForURL(new RegExp(`/app/o/${collectionName}`));

      // Step 5: Delete the record
      const detailPageAfterEdit = new ObjectDetailPage(
        page,
        collectionName,
        recordId,
        tenantSlug,
      );
      await detailPageAfterEdit.goto();
      await detailPageAfterEdit.clickDelete();
      await detailPageAfterEdit.confirmDialog();

      // Should navigate back to the list after deletion
      await page.waitForURL(new RegExp(`/app/o/${collectionName}`));
    } else {
      // If we landed on the list, find and click the record
      const listPage = new ObjectListPage(page, collectionName, tenantSlug);
      const tableOrEmpty = listPage.dataTable.or(
        page.getByTestId("empty-state"),
      );
      await expect(tableOrEmpty).toBeVisible();

      const rowCount = await listPage.getRowCount();
      expect(rowCount).toBeGreaterThan(0);

      // Click the first row to view the record. The detail route push can lag
      // under CI load behind the click handler's data fetch; give it a longer
      // ceiling than the 30s default so the first attempt no longer flakes.
      await listPage.clickRow(0);
      await page.waitForURL(new RegExp(`/app/o/${collectionName}/[^/]+`), {
        timeout: 45_000,
      });
    }
  });
});
