import type { Page, Locator } from "@playwright/test";
import { BasePage } from "./base.page";

export class FlowDesignerPage extends BasePage {
  readonly canvas: Locator;
  readonly toolbar: Locator;
  readonly nodePanel: Locator;
  readonly saveButton: Locator;
  readonly autoLayoutButton: Locator;
  readonly nodes: Locator;

  constructor(page: Page, tenantSlug?: string) {
    super(page, tenantSlug);
    this.canvas = this.page.locator(".react-flow");
    this.toolbar = this.page.locator('[role="toolbar"]');
    this.nodePanel = this.page.locator('.steps-palette, [data-panel="steps"]');
    this.saveButton = this.page.getByRole("button", { name: /save/i });
    this.autoLayoutButton = this.page.getByRole("button", {
      name: /auto layout/i,
    });
    this.nodes = this.page.locator(".react-flow__node");
  }

  async goto(flowId: string): Promise<void> {
    await this.page.goto(this.tenantUrl(`/flows/${flowId}/design`));
    await this.waitForLoadingComplete();
  }

  async isCanvasVisible(): Promise<boolean> {
    return this.canvas.isVisible();
  }

  async save(): Promise<void> {
    await this.saveButton.click();
  }

  async autoLayout(): Promise<void> {
    await this.autoLayoutButton.click();
  }

  /** Bounding boxes of every rendered node, keyed by node id. */
  async nodeBoxes(): Promise<
    Record<string, { x: number; y: number; width: number; height: number }>
  > {
    const out: Record<
      string,
      { x: number; y: number; width: number; height: number }
    > = {};
    const count = await this.nodes.count();
    for (let i = 0; i < count; i++) {
      const node = this.nodes.nth(i);
      const id = await node.getAttribute("data-id");
      const box = await node.boundingBox();
      if (id && box) out[id] = box;
    }
    return out;
  }
}
