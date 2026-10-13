import type { Page, Locator } from "@playwright/test";
import { BasePage } from "../base.page";

export class ObjectDetailPage extends BasePage {
  readonly editButton: Locator;
  readonly deleteButton: Locator;
  readonly fieldValues: Locator;
  readonly historyTab: Locator;
  readonly historyPanel: Locator;
  readonly versionRows: Locator;
  readonly versionDetail: Locator;
  readonly changedBadges: Locator;
  readonly historyBackButton: Locator;
  readonly activityVersionLinks: Locator;
  readonly sectionNav: Locator;
  readonly sectionNavActivity: Locator;

  constructor(
    page: Page,
    private readonly collectionName: string,
    private readonly recordId: string,
    tenantSlug?: string,
  ) {
    super(page, tenantSlug);
    this.editButton = this.testId("edit-button");
    this.deleteButton = this.testId("delete-button");
    this.fieldValues = this.testId("field-values");
    this.historyTab = this.testId("detail-tab-history");
    this.historyPanel = this.testId("record-history-panel");
    this.versionRows = this.testId("record-version-row");
    this.versionDetail = this.testId("record-version-detail");
    this.changedBadges = this.testId("version-changed-badge");
    this.historyBackButton = this.testId("history-back-button");
    this.activityVersionLinks = this.testId("activity-version-link");
    this.sectionNav = this.testId("record-section-nav");
    this.sectionNavActivity = this.testId("section-nav-activity");
  }

  async goto(): Promise<void> {
    await this.page.goto(
      this.tenantUrl(`/app/o/${this.collectionName}/${this.recordId}`),
    );
    await this.waitForLoadingComplete();
  }

  async clickEdit(): Promise<void> {
    await this.editButton.click();
  }

  async clickDelete(): Promise<void> {
    // Delete is inside the "More actions" dropdown — open it first
    await this.page.getByRole("button", { name: /more actions/i }).click();
    await this.deleteButton.click();
  }

  async openHistoryTab(): Promise<void> {
    await this.historyTab.click();
  }
}
