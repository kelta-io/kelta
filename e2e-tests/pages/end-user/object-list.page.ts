import type { Page, Locator } from "@playwright/test";
import { BasePage } from "../base.page";

export class ObjectListPage extends BasePage {
  readonly dataTable: Locator;
  readonly searchInput: Locator;
  readonly pagination: Locator;

  constructor(
    page: Page,
    private readonly collectionName: string,
    tenantSlug?: string,
  ) {
    super(page, tenantSlug);
    this.dataTable = this.testId("data-table");
    this.searchInput = this.testId("search-input");
    this.pagination = this.testId("pagination");
  }

  async goto(): Promise<void> {
    await this.page.goto(this.tenantUrl(`/app/o/${this.collectionName}`));
    await this.waitForLoadingComplete();
  }

  async search(text: string): Promise<void> {
    await this.searchInput.fill(text);
  }

  async getRowCount(): Promise<number> {
    return this.dataTable.locator('tbody tr, [role="row"]').count();
  }

  async clickRow(index: number): Promise<void> {
    await this.dataTable.locator('tbody tr, [role="row"]').nth(index).click();
  }

  async sortByColumn(name: string): Promise<void> {
    await this.dataTable.getByRole("columnheader", { name }).click();
  }
}
