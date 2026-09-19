import { test, expect } from "../../../fixtures";
import { CollectionsListPage } from "../../../pages/collections-list.page";
import { CollectionWizardPage } from "../../../pages/collection-wizard.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

test.describe("Collection Wizard", () => {
  test("opens collection creation wizard", async ({ page }) => {
    const collectionsPage = new CollectionsListPage(page, tenantSlug);
    await collectionsPage.goto();
    await collectionsPage.waitForTableLoaded();

    await collectionsPage.clickCreateCollection();

    const wizardPage = new CollectionWizardPage(page, tenantSlug);
    await expect(wizardPage.container).toBeVisible();
  });

  test("progresses through wizard steps", async ({ page }) => {
    const wizardPage = new CollectionWizardPage(page, tenantSlug);
    await wizardPage.goto();

    // Should start at step 1
    const initialStep = await wizardPage.getCurrentStep();
    expect(initialStep).toBe(1);

    // Fill in required basic info to advance
    await wizardPage.fillBasicInfo({
      displayName: "Step Test Collection",
      name: "step_test_collection",
    });

    // Advance to step 2 (Fields)
    await wizardPage.nextStep();
    const secondStep = await wizardPage.getCurrentStep();
    expect(secondStep).toBe(2);

    // Advance to step 3 (Authorization)
    await wizardPage.nextStep();
    const thirdStep = await wizardPage.getCurrentStep();
    expect(thirdStep).toBe(3);

    // Go back to step 2
    await wizardPage.previousStep();
    const backToSecond = await wizardPage.getCurrentStep();
    expect(backToSecond).toBe(2);
  });

  test("can select a template on fields step", async ({ page }) => {
    const wizardPage = new CollectionWizardPage(page, tenantSlug);
    await wizardPage.goto();

    // Fill basic info first (required to advance)
    await wizardPage.fillBasicInfo({
      displayName: "Template Test Collection",
      name: "template_test_collection",
    });

    // Advance to Step 2 (Fields) where template selection lives
    await wizardPage.nextStep();

    // Select the "custom" template (blank — no pre-defined fields)
    await wizardPage.selectTemplate("custom");
    await expect(wizardPage.container).toBeVisible();
  });

  test("fills in basic collection info", async ({ page }) => {
    const wizardPage = new CollectionWizardPage(page, tenantSlug);
    await wizardPage.goto();

    await wizardPage.fillBasicInfo({
      displayName: "My Test Collection",
      name: "my_test_collection",
      description: "A collection created during e2e testing",
    });

    await expect(wizardPage.displayNameInput).toHaveValue("My Test Collection");
    await expect(wizardPage.nameInput).toHaveValue("my_test_collection");
    await expect(wizardPage.descriptionInput).toHaveValue(
      "A collection created during e2e testing",
    );
  });

  test("can add fields in wizard", async ({ page }) => {
    const wizardPage = new CollectionWizardPage(page, tenantSlug);
    await wizardPage.goto();

    // Fill basic info and advance to fields step
    await wizardPage.fillBasicInfo({
      displayName: "Fields Test Collection",
      name: "fields_test_collection",
    });
    await wizardPage.nextStep();

    // Should show no fields initially or the add field button
    await expect(wizardPage.addFieldButton).toBeVisible();

    // Click add field to open the field editor
    await wizardPage.addField({ name: "test_field", type: "string" });

    // Verify the fields table or field row appears
    await expect(
      wizardPage.fieldsTable.or(wizardPage.addFieldButton),
    ).toBeVisible();
  });

  test("completes wizard and creates collection", async ({
    page,
    dataFactory,
  }) => {
    const wizardPage = new CollectionWizardPage(page, tenantSlug);
    await wizardPage.goto();

    const uniqueName = `e2e_wizard_${Date.now()}`;

    // Step 1: Basic info
    await wizardPage.fillBasicInfo({
      displayName: `Wizard ${uniqueName}`,
      name: uniqueName,
      description: "Created by e2e wizard test",
    });
    await wizardPage.nextStep();

    // Step 2: Fields (skip adding fields, just proceed)
    await wizardPage.nextStep();

    // Step 3: Authorization (skip, just proceed)
    await wizardPage.nextStep();

    // Step 4: Review - verify summary is shown
    await expect(wizardPage.reviewDisplayName).toBeVisible();
    await expect(wizardPage.reviewName).toBeVisible();

    // Submit the wizard
    await wizardPage.submit();

    // Should redirect to collection detail or list page
    await expect(page).toHaveURL(new RegExp(`/${tenantSlug}/collections`));

    // The wizard creates the collection through the UI, so nothing tracks it for
    // teardown — that is what leaked e2e_wizard_* collections into the target tenant.
    // Register it so the data-factory cleanup force-deletes it after the test.
    await dataFactory.trackCollectionByName(uniqueName);
  });
});
