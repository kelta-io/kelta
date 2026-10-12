import type { Page, Locator } from "@playwright/test";
import { BasePage } from "./base.page";

/**
 * Which record stack a {@link RecordVariant} drives.
 *
 * - `admin`   — `/<tenant>/resources/<collection>…` (ResourceListPage / ResourceFormPage /
 *               ResourceDetailPage)
 * - `enduser` — `/<tenant>/app/o/<collection>…` (ObjectListPage / ObjectFormPage /
 *               ObjectDetailPage)
 */
export type RecordVariantKind = "admin" | "enduser";

export const RECORD_VARIANTS: readonly RecordVariantKind[] = ["admin", "enduser"];

/** A field as the detail page labels it: API name plus the display name shown in the UI. */
export interface VariantField {
  name: string;
  displayName: string;
}

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Variant-aware page object for the record list / form / detail journey, so one spec can run
 * the same steps against the admin and the end-user record stacks (record-parity.spec.ts).
 *
 * Selectors are each stack's existing ones — no test ids were added for this. What the two
 * stacks share is used directly: form controls carry `id="field-<name>"` in both
 * (`<input>`, `<select>`, the native/Radix checkbox, and the LookupSelect trigger button),
 * and both lists render the shared ObjectDataTable (`data-testid="data-table"`). Everything
 * else is mapped per variant below.
 */
export class RecordVariant extends BasePage {
  constructor(
    page: Page,
    tenantSlug: string,
    readonly kind: RecordVariantKind,
  ) {
    super(page, tenantSlug);
  }

  // ---------------------------------------------------------------------------
  // Routes
  // ---------------------------------------------------------------------------

  /** Tenant-relative base of a collection's record pages. */
  private collectionPath(collection: string): string {
    return this.kind === "admin"
      ? `/resources/${collection}`
      : `/app/o/${collection}`;
  }

  private collectionUrlPrefix(collection: string): string {
    return escapeRegExp(`/${this.tenantSlug}${this.collectionPath(collection)}`);
  }

  /** The collection's list page and nothing deeper (query string allowed). */
  listUrlPattern(collection: string): RegExp {
    return new RegExp(`${this.collectionUrlPrefix(collection)}/?(\\?.*)?$`);
  }

  newUrlPattern(collection: string): RegExp {
    return new RegExp(`${this.collectionUrlPrefix(collection)}/new(\\?.*)?$`);
  }

  /** A record's detail page; capture group 1 is the record id. */
  detailUrlPattern(collection: string): RegExp {
    return new RegExp(`${this.collectionUrlPrefix(collection)}/(${UUID})(\\?.*)?$`);
  }

  editUrlPattern(collection: string): RegExp {
    return new RegExp(`${this.collectionUrlPrefix(collection)}/(${UUID})/edit(\\?.*)?$`);
  }

  /** The record id in the current URL when it is a detail page, else null. */
  recordIdFromUrl(collection: string): string | null {
    const match = new URL(this.page.url()).pathname.match(
      this.detailUrlPattern(collection),
    );
    return match ? match[1] : null;
  }

  // ---------------------------------------------------------------------------
  // List
  // ---------------------------------------------------------------------------

  async goto(collection: string): Promise<void> {
    await this.gotoList(collection);
  }

  async gotoList(collection: string): Promise<void> {
    await this.page.goto(this.tenantUrl(this.collectionPath(collection)));
    await this.waitForLoadingComplete();
  }

  /** "New" on the list toolbar. */
  get newButton(): Locator {
    return this.kind === "admin"
      ? this.testId("create-record-button")
      : this.testId("new-record-button");
  }

  async clickNew(): Promise<void> {
    await this.newButton.click();
  }

  /** The shared ObjectDataTable both lists render. */
  get dataTable(): Locator {
    return this.testId("data-table");
  }

  get rows(): Locator {
    return this.dataTable.locator("tbody tr");
  }

  async rowCount(): Promise<number> {
    return this.rows.count();
  }

  /** The list row whose cells contain `text` (e.g. a record's unique title). */
  rowWithText(text: string): Locator {
    return this.rows.filter({ hasText: text });
  }

  /**
   * Open a record from the list by clicking its `text` cell. A value click bubbles to the
   * row's navigation handler in both stacks (inline edit is pencil-triggered only).
   */
  async openRow(text: string): Promise<void> {
    await this.rowWithText(text)
      .first()
      .getByText(text, { exact: true })
      .first()
      .click();
  }

  // ---------------------------------------------------------------------------
  // Form
  // ---------------------------------------------------------------------------

  async gotoNew(collection: string): Promise<void> {
    await this.page.goto(this.tenantUrl(`${this.collectionPath(collection)}/new`));
    await this.waitForLoadingComplete();
  }

  async gotoEdit(collection: string, recordId: string): Promise<void> {
    await this.page.goto(
      this.tenantUrl(`${this.collectionPath(collection)}/${recordId}/edit`),
    );
    await this.waitForLoadingComplete();
  }

  /** The element that holds the form's field controls once the form has loaded. */
  get form(): Locator {
    return this.kind === "admin"
      ? this.testId("resource-form")
      : this.testId("form-fields");
  }

  async waitForForm(): Promise<void> {
    await this.waitForContentReady(this.form, 30_000);
  }

  /**
   * The control for a field: `<input>`, `<select>`, checkbox (native on admin, Radix
   * `role="checkbox"` on end-user) or the LookupSelect trigger button — all `id="field-<name>"`.
   */
  field(name: string): Locator {
    return this.form.locator(`[id="field-${name}"]`);
  }

  /** Fill a text / number / date input. */
  async fillField(name: string, value: string): Promise<void> {
    await this.field(name).fill(value);
  }

  /** Empty a text / number / date input. */
  async clearField(name: string): Promise<void> {
    await this.field(name).fill("");
  }

  async setChecked(name: string, checked: boolean): Promise<void> {
    await this.field(name).setChecked(checked);
  }

  /** Pick a value on a picklist `<select>`; waits for the async-loaded option first. */
  async selectPicklist(name: string, value: string): Promise<void> {
    const select = this.field(name);
    await select
      .locator(`option[value="${value}"]`)
      .waitFor({ state: "attached", timeout: 15_000 });
    await select.selectOption({ value });
  }

  /**
   * Pick a lookup / master-detail target by its display label through the shared
   * LookupSelect. Both stacks render it once the target collection's options have loaded
   * (admin briefly shows a plain text input before that), so wait for the combobox first.
   */
  async selectLookup(name: string, label: string): Promise<void> {
    const trigger = this.form.locator(`[id="field-${name}"][role="combobox"]`);
    await trigger.waitFor({ state: "visible", timeout: 15_000 });
    await trigger.click();
    await this.page
      .getByRole("listbox", { name })
      .getByRole("option", { name: label, exact: true })
      .click();
  }

  get saveButton(): Locator {
    return this.kind === "admin"
      ? this.testId("submit-button")
      : this.testId("save-button");
  }

  get cancelButton(): Locator {
    return this.testId("cancel-button");
  }

  async save(): Promise<void> {
    await this.saveButton.click();
  }

  async cancel(): Promise<void> {
    await this.cancelButton.click();
  }

  /**
   * The inline error under a field. Admin validates on the client (`error-<name>`); end-user
   * shows the server's JSON:API field error (`field-error-<name>`). Both are `role="alert"`.
   */
  fieldError(name: string): Locator {
    return this.kind === "admin"
      ? this.testId(`error-${name}`)
      : this.testId(`field-error-${name}`);
  }

  // ---------------------------------------------------------------------------
  // Detail
  // ---------------------------------------------------------------------------

  async gotoDetail(collection: string, recordId: string): Promise<void> {
    await this.page.goto(
      this.tenantUrl(`${this.collectionPath(collection)}/${recordId}`),
    );
    await this.waitForLoadingComplete();
  }

  /**
   * The detail page's field-value region when no page layout is configured: the admin
   * field grid, or the end-user Highlights + Details cards.
   */
  get detailBody(): Locator {
    return this.kind === "admin"
      ? this.testId("fields-grid")
      : this.testId("field-values");
  }

  async waitForDetail(): Promise<void> {
    await this.waitForContentReady(this.detailBody, 30_000);
  }

  /**
   * The rendered value of one field on the detail page. Admin has a per-field test id; the
   * end-user Highlights / Details cards render `<dt>label</dt><dd>value</dd>` pairs.
   */
  detailFieldValue(field: VariantField): Locator {
    if (this.kind === "admin") {
      return this.testId(`field-value-${field.name}`);
    }
    return this.detailBody
      .locator("dt")
      .filter({ hasText: new RegExp(`^\\s*${escapeRegExp(field.displayName)}\\s*$`) })
      .first()
      .locator("xpath=following-sibling::dd[1]");
  }

  get editButton(): Locator {
    return this.kind === "admin"
      ? this.testId("actions-edit-button")
      : this.testId("edit-button");
  }

  async clickEdit(): Promise<void> {
    await this.editButton.click();
  }

  /** Delete from the detail page's overflow menu and confirm the dialog. */
  async deleteRecord(): Promise<void> {
    if (this.kind === "admin") {
      await this.testId("actions-dropdown-toggle").click();
      await this.testId("actions-delete").click();
      await this.testId("confirm-dialog-confirm").click();
      return;
    }
    await this.page.getByRole("button", { name: /more actions/i }).click();
    await this.testId("delete-button").click();
    await this.page
      .getByRole("alertdialog")
      .getByRole("button", { name: "Delete", exact: true })
      .click();
  }
}
