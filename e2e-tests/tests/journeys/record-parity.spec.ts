import { test, expect } from "../../fixtures";
import { clearTokenCache, getApiToken } from "../../fixtures/auth-tokens";
import { DataFactory } from "../../helpers/data-factory";
import {
  RECORD_VARIANTS,
  RecordVariant,
  type VariantField,
} from "../../pages/record-variant.page";

/**
 * Record parity harness — the SAME record journey against both record stacks:
 *
 *   admin    /<tenant>/resources/<collection>…  (ResourceList/Form/DetailPage)
 *   end-user /<tenant>/app/o/<collection>…      (ObjectList/Form/DetailPage)
 *
 * It guards the convergence of the two stacks into one (unified-record slice 8,
 * `.claude/docs/specs/_archive/unified-record/8-admin-convergence.md` §6): when both
 * describe blocks pass every step, the stacks behave the same. Steps where a stack
 * differs from the target today are `test.fixme` with the gap named, so removing a
 * fixme is the signal that the gap is closed.
 *
 * One collection per run carries a representative field set (string, number, boolean,
 * date, picklist, lookup, master-detail); each step seeds its own records through the
 * API and asserts what was persisted through the API, not only what the UI shows. Since P10
 * both lists are the same `RecordList` core, so the list steps (filter builder, view switcher,
 * inline cell edit) run unchanged against both.
 */

const TENANT = process.env.E2E_TENANT_SLUG || "default";
const API_BASE = process.env.E2E_API_BASE_URL || "https://kelta.io";
const EPHEMERAL = process.env.E2E_EPHEMERAL_STACK === "true";

const F = {
  title: { name: "title", displayName: "Title" },
  amount: { name: "amount", displayName: "Amount" },
  isActive: { name: "is_active", displayName: "Active" },
  startDate: { name: "start_date", displayName: "Start Date" },
  priority: { name: "priority", displayName: "Priority" },
  account: { name: "accountRef", displayName: "Account" },
  parent: { name: "parentRef", displayName: "Parent" },
} satisfies Record<string, VariantField>;

const PRIORITY_VALUES = ["Low", "High"];

interface Schema {
  records: string;
  accounts: string;
  parents: string;
  account: { id: string; name: string };
  parent: { id: string; name: string };
}

type ApiRecord = Awaited<ReturnType<DataFactory["getRecord"]>>;

/** A to-one reference's target id, whether served as a relationship or an attribute. */
function referenceId(record: ApiRecord, field: string): string | null {
  const related = record.relationships?.[field]?.data;
  if (related?.id) return related.id;
  const attribute = record.attributes[field];
  if (typeof attribute === "string" && attribute) return attribute;
  if (attribute && typeof attribute === "object" && "id" in attribute) {
    return String((attribute as { id: unknown }).id);
  }
  return null;
}

function uniqueTitle(variant: string, step: string): string {
  return `Parity ${variant} ${step} ${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

test.describe("Record parity (admin vs end-user)", () => {
  // Creates collections and records in the target tenant. Everything is force-deleted
  // with the collections in afterAll, but a run cut short mid-way would leave them
  // behind, so it runs only on the throwaway CI stack — never in the post-deploy run.
  test.skip(!EPHEMERAL, "ephemeral CI stack only (E2E_EPHEMERAL_STACK)");

  let factory: DataFactory;
  let schema: Schema;

  test.beforeAll(async () => {
    if (!EPHEMERAL) return;
    test.setTimeout(180_000);

    factory = new DataFactory({
      baseUrl: API_BASE,
      tenantSlug: TENANT,
      token: await getApiToken(),
      refreshToken: async () => {
        clearTokenCache();
        return getApiToken();
      },
    });

    // Lookup / master-detail targets, each with a `name` field (their display label).
    const accounts = await factory.createCollection({ displayName: "Parity Accounts" });
    const accountsName = accounts.attributes.name as string;
    const accountNameField = await factory.addField(accounts.id, {
      name: "name",
      displayName: "Name",
      type: "string",
    });

    const parents = await factory.createCollection({ displayName: "Parity Parents" });
    const parentsName = parents.attributes.name as string;
    await factory.addField(parents.id, { name: "name", displayName: "Name", type: "string" });

    const records = await factory.createCollection({ displayName: "Parity Records" });
    const recordsName = records.attributes.name as string;
    await factory.addField(records.id, { ...F.title, type: "string", required: true });
    await factory.addField(records.id, { ...F.amount, type: "number" });
    await factory.addField(records.id, { ...F.isActive, type: "boolean" });
    await factory.addField(records.id, { ...F.startDate, type: "date" });
    const priority = await factory.addField(records.id, { ...F.priority, type: "picklist" });
    const accountRef = await factory.addField(records.id, {
      ...F.account,
      type: "lookup",
      referenceTarget: accountsName,
    });
    await factory.addField(records.id, {
      ...F.parent,
      type: "master_detail",
      referenceTarget: parentsName,
    });

    await factory.waitForStorageReady(accountsName);
    await factory.waitForStorageReady(parentsName);
    await factory.waitForStorageReady(recordsName);

    // Field-level picklist values (no global picklist): both forms resolve a picklist
    // field without `globalPicklistId` to `picklistSourceType=FIELD`, source = field id.
    for (const [sortOrder, value] of PRIORITY_VALUES.entries()) {
      await factory.createResource("picklist-values", {
        picklistSourceType: "FIELD",
        picklistSourceId: priority.id,
        value,
        label: value,
        sortOrder,
        isActive: true,
      });
    }

    // The account detail page lists the account's records (a layout related list through
    // the lookup), showing each record's master-detail Parent as a link — so one page
    // carries both a related-record row and a lookup link to keep inside the stack.
    // Only the accounts collection gets a layout: the records' own detail steps assert the
    // no-layout field body.
    const { layoutId } = await factory.createDetailLayout(accounts.id, [
      { heading: "Account", fields: [{ fieldId: accountNameField.id }] },
    ]);
    await factory.createResource("layout-related-lists", {
      layoutId,
      relatedCollectionId: records.id,
      relationshipFieldId: accountRef.id,
      displayColumns: [F.title.name, F.parent.name],
      sortOrder: 0,
      rowLimit: 5,
    });

    const accountName = `Parity Account ${Date.now()}`;
    const account = await factory.createRecord(accountsName, { name: accountName });
    const parentName = `Parity Parent ${Date.now()}`;
    const parent = await factory.createRecord(parentsName, { name: parentName });

    schema = {
      records: recordsName,
      accounts: accountsName,
      parents: parentsName,
      account: { id: account.id, name: accountName },
      parent: { id: parent.id, name: parentName },
    };
  });

  test.afterAll(async () => {
    if (!factory) return;
    test.setTimeout(120_000);
    await factory.cleanup();
  });

  /** Seed one record through the API with the given title and an optional amount. */
  async function seedRecord(title: string, amount?: number): Promise<string> {
    const record = await factory.createRecord(schema.records, {
      [F.title.name]: title,
      ...(amount === undefined ? {} : { [F.amount.name]: amount }),
    });
    return record.id;
  }

  /** The single record carrying `title`, once the API returns it. */
  async function findByTitle(title: string): Promise<ApiRecord> {
    let found: ApiRecord | undefined;
    await expect
      .poll(
        async () => {
          const matches = await factory.queryRecords(schema.records, {
            [F.title.name]: title,
          });
          found = matches[0];
          return matches.length;
        },
        { timeout: 15_000, message: `record titled "${title}" via the API` },
      )
      .toBe(1);
    return found!;
  }

  for (const variant of RECORD_VARIANTS) {
    test.describe(variant, () => {
      let ui: RecordVariant;

      test.beforeEach(async ({ page }) => {
        ui = new RecordVariant(page, TENANT, variant);
      });

      test("create through the form persists every field type", async ({ page }) => {
        const title = uniqueTitle(variant, "create");

        await ui.gotoNew(schema.records);
        await ui.waitForForm();
        await ui.fillField(F.title.name, title);
        await ui.fillField(F.amount.name, "42");
        await ui.setChecked(F.isActive.name, true);
        await ui.fillField(F.startDate.name, "2026-01-15");
        await ui.selectPicklist(F.priority.name, "High");
        await ui.selectLookup(F.account.name, schema.account.name);
        await ui.selectLookup(F.parent.name, schema.parent.name);
        await ui.save();

        await expect(page).not.toHaveURL(ui.newUrlPattern(schema.records), {
          timeout: 15_000,
        });

        const saved = await findByTitle(title);
        expect(Number(saved.attributes[F.amount.name])).toBe(42);
        expect(saved.attributes[F.isActive.name]).toBe(true);
        expect(String(saved.attributes[F.startDate.name]).slice(0, 10)).toBe("2026-01-15");
        expect(saved.attributes[F.priority.name]).toBe("High");
        expect(referenceId(saved, F.account.name)).toBe(schema.account.id);
        expect(referenceId(saved, F.parent.name)).toBe(schema.parent.id);
      });

      test("create lands on the new record's detail page", async ({ page }) => {
        const title = uniqueTitle(variant, "landing");

        await ui.gotoNew(schema.records);
        await ui.waitForForm();
        await ui.fillField(F.title.name, title);
        await ui.save();

        await expect(page).toHaveURL(ui.detailUrlPattern(schema.records), {
          timeout: 15_000,
        });
        const saved = await findByTitle(title);
        expect(ui.recordIdFromUrl(schema.records)).toBe(saved.id);
        await ui.waitForDetail();
        await expect(ui.detailFieldValue(F.title)).toContainText(title);
      });

      test("detail page shows the persisted values", async () => {
        const title = uniqueTitle(variant, "detail");
        const id = await seedRecord(title, 17);

        await ui.gotoDetail(schema.records, id);
        await ui.waitForDetail();
        await expect(ui.detailFieldValue(F.title)).toContainText(title);
        await expect(ui.detailFieldValue(F.amount)).toContainText("17");
      });

      test("edit one field persists it and returns to the detail page", async ({ page }) => {
        const title = uniqueTitle(variant, "edit");
        const id = await seedRecord(title, 5);

        await ui.gotoDetail(schema.records, id);
        await ui.waitForDetail();
        await ui.clickEdit();
        await expect(page).toHaveURL(ui.editUrlPattern(schema.records));
        await ui.waitForForm();
        await expect(ui.field(F.title.name)).toHaveValue(title);

        const updated = `${title} (edited)`;
        await ui.fillField(F.title.name, updated);
        await ui.save();

        await expect(page).toHaveURL(ui.detailUrlPattern(schema.records), {
          timeout: 15_000,
        });
        await expect
          .poll(async () => (await factory.getRecord(schema.records, id)).attributes[F.title.name])
          .toBe(updated);
        const saved = await factory.getRecord(schema.records, id);
        expect(Number(saved.attributes[F.amount.name])).toBe(5);
      });

      test("clearing an optional field persists the empty value", async ({ page }) => {
        const title = uniqueTitle(variant, "clear");
        const id = await seedRecord(title, 7);

        await ui.gotoEdit(schema.records, id);
        await ui.waitForForm();
        await expect(ui.field(F.amount.name)).toHaveValue("7");
        await ui.clearField(F.amount.name);
        await ui.save();

        await expect(page).toHaveURL(ui.detailUrlPattern(schema.records), {
          timeout: 15_000,
        });
        await expect
          .poll(async () => (await factory.getRecord(schema.records, id)).attributes[F.amount.name] ?? null)
          .toBeNull();
      });

      test("a blank required field shows a field error and nothing is saved", async ({ page }) => {
        // A per-test amount identifies the record the rejected save would have created.
        const marker = 100_000 + Math.floor(Math.random() * 800_000);

        await ui.gotoNew(schema.records);
        await ui.waitForForm();
        await ui.fillField(F.amount.name, String(marker));
        await ui.save();

        await expect(ui.fieldError(F.title.name)).toBeVisible();
        await expect(page).toHaveURL(ui.newUrlPattern(schema.records));
        const created = await factory.queryRecords(schema.records, {
          [F.amount.name]: marker,
        });
        expect(created).toHaveLength(0);
      });

      test("cancel leaves the record unchanged", async ({ page }) => {
        const title = uniqueTitle(variant, "cancel");
        const id = await seedRecord(title);

        await ui.gotoEdit(schema.records, id);
        await ui.waitForForm();
        await expect(ui.field(F.title.name)).toHaveValue(title);
        await ui.fillField(F.title.name, `${title} (discarded)`);
        await ui.cancel();

        await expect(page).toHaveURL(ui.detailUrlPattern(schema.records));
        const saved = await factory.getRecord(schema.records, id);
        expect(saved.attributes[F.title.name]).toBe(title);
      });

      test("list shows the record and opens it", async ({ page }) => {
        const title = uniqueTitle(variant, "list");
        const id = await seedRecord(title);

        await ui.gotoList(schema.records);
        await expect(ui.rowWithText(title)).toBeVisible({ timeout: 15_000 });
        expect(await ui.rowCount()).toBeGreaterThan(0);

        await ui.openRow(title);
        await expect(page).toHaveURL(ui.detailUrlPattern(schema.records), {
          timeout: 15_000,
        });
        expect(ui.recordIdFromUrl(schema.records)).toBe(id);
      });

      test("list New opens the create form", async ({ page }) => {
        await ui.gotoList(schema.records);
        await ui.clickNew();

        await expect(page).toHaveURL(ui.newUrlPattern(schema.records));
        await ui.waitForForm();
        await expect(ui.field(F.title.name)).toBeVisible();
      });

      test("the filter builder ANDs its conditions and keeps them in the URL", async ({ page }) => {
        // Both lists offer the same AND-only builder (record-stack convergence P10); its `>`
        // maps onto the list's `greater_than` and the applied conditions live in `filter=`.
        const tag = uniqueTitle(variant, "filter");
        const small = `${tag} small`;
        const large = `${tag} large`;
        await seedRecord(small, 5);
        await seedRecord(large, 500);

        await ui.gotoList(schema.records);
        await ui.applyFilters([
          { field: F.title.displayName, operator: "contains", value: tag },
          { field: F.amount.displayName, operator: ">", value: "100" },
        ]);

        await expect(ui.rowWithText(large)).toBeVisible({ timeout: 15_000 });
        await expect(ui.rowWithText(small)).toHaveCount(0);
        await expect(page).toHaveURL(/[?&]filter=/);
        const filter = JSON.parse(new URL(page.url()).searchParams.get("filter") ?? "[]");
        expect(filter.map((f: { field: string; operator: string }) => [f.field, f.operator])).toEqual([
          [F.title.name, "contains"],
          [F.amount.name, "greater_than"],
        ]);
      });

      test("the view switcher shows the records as a board", async () => {
        const title = uniqueTitle(variant, "board");
        const record = await factory.createRecord(schema.records, {
          [F.title.name]: title,
          [F.priority.name]: "High",
        });

        await ui.gotoListWhere(schema.records, F.title.name, title);
        await expect(ui.rowWithText(title)).toBeVisible({ timeout: 15_000 });
        await ui.switchView("kanban");

        await expect(ui.kanbanBoard).toBeVisible();
        await expect(ui.kanbanCard("High", record.id)).toContainText(title);
      });

      test("an inline cell edit persists", async () => {
        const title = uniqueTitle(variant, "inline");
        const id = await seedRecord(title, 1);

        await ui.gotoListWhere(schema.records, F.title.name, title);
        await expect(ui.rowWithText(title)).toBeVisible({ timeout: 15_000 });
        await ui.editCell(title, F.amount.name, "64");

        await expect
          .poll(async () =>
            Number((await factory.getRecord(schema.records, id)).attributes[F.amount.name]),
          )
          .toBe(64);
      });

      test("delete removes the record and returns to the list", async ({ page }) => {
        const title = uniqueTitle(variant, "delete");
        const id = await seedRecord(title);

        await ui.gotoDetail(schema.records, id);
        await ui.waitForDetail();
        await ui.deleteRecord();

        await expect(page).toHaveURL(ui.listUrlPattern(schema.records), {
          timeout: 15_000,
        });
        await expect
          .poll(async () => {
            try {
              await factory.getRecord(schema.records, id);
              return "present";
            } catch (error) {
              return String(error).includes("(404)") ? "gone" : String(error);
            }
          })
          .toBe("gone");
      });

      test("related-list rows and lookup links stay in this stack", async ({ page }) => {
        // The shared RelatedList / FieldRenderer once linked into the end-user app from
        // every stack (record-stack convergence P7): both must stay in the stack they render in.
        const title = uniqueTitle(variant, "related");
        const record = await factory.createRecord(schema.records, {
          [F.title.name]: title,
          [F.account.name]: schema.account.id,
          [F.parent.name]: schema.parent.id,
        });

        await ui.gotoDetail(schema.accounts, schema.account.id);
        await ui.relatedListTab(schema.records).click({ timeout: 30_000 });
        const row = page.getByRole("row").filter({ hasText: title });
        await expect(row).toBeVisible({ timeout: 15_000 });

        // The row's master-detail Parent cell links to the parent record in this stack.
        await expect(row.locator(`a[href$="/${schema.parent.id}"]`)).toHaveAttribute(
          "href",
          ui.recordPath(schema.parents, schema.parent.id),
        );

        // Opening the row lands on the related record's detail page in this stack.
        await row.getByText(title, { exact: true }).click();
        await expect(page).toHaveURL(ui.detailUrlPattern(schema.records), {
          timeout: 15_000,
        });
        expect(ui.recordIdFromUrl(schema.records)).toBe(record.id);
      });

      test("a concurrent edit (409) prompts instead of overwriting", async ({ page }) => {
        // Both forms send the loaded record's ETag as If-Match; a stale save keeps the edits
        // and opens the shared conflict prompt instead of overwriting the other change.
        const title = uniqueTitle(variant, "conflict");
        const id = await seedRecord(title);

        await ui.gotoEdit(schema.records, id);
        await ui.waitForForm();
        await expect(ui.field(F.title.name)).toHaveValue(title);

        // Someone else saves the record while this form is open.
        const theirs = `${title} (theirs)`;
        await factory.updateRecord(schema.records, id, { [F.title.name]: theirs });

        await ui.fillField(F.title.name, `${title} (mine)`);
        await ui.save();

        await expect(ui.conflictPrompt).toBeVisible({ timeout: 15_000 });
        await expect(ui.conflictPrompt).toHaveRole("alertdialog");
        await expect(page).toHaveURL(ui.editUrlPattern(schema.records));
        await expect(ui.field(F.title.name)).toHaveValue(`${title} (mine)`);
        const saved = await factory.getRecord(schema.records, id);
        expect(saved.attributes[F.title.name]).toBe(theirs);

        // Reload latest: the form re-initialises from the other user's version.
        await ui.conflictReloadButton.click();
        await expect(ui.conflictPrompt).toBeHidden();
        await expect(ui.field(F.title.name)).toHaveValue(theirs);
      });
    });
  }
});
