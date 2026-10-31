import type { Page } from "@playwright/test";
import { test, expect } from "../../fixtures";
import { ObjectDetailPage } from "../../pages/end-user/object-detail.page";

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";

/**
 * Live record pages: a change made outside the open page (another user, a flow, an API
 * client) reaches it over /ws/realtime and the page refetches without a reload.
 *
 * The test collection is created on the fly, so it is in no nav menu — the page itself must
 * subscribe to its collection (useRealtimeCollection). Before that, only top-level nav
 * collections were subscribed, and grouped nav items were skipped entirely.
 */

/** Resolves once the gateway acknowledges a subscription to `collectionName`. */
function waitForSubscribed(page: Page, collectionName: string): Promise<void> {
  return new Promise((resolve) => {
    page.on("websocket", (ws) => {
      if (!ws.url().includes("/ws/realtime")) return;
      ws.on("framereceived", (frame) => {
        try {
          const message = JSON.parse(String(frame.payload));
          if (
            message.action === "subscribed" &&
            message.collection === collectionName
          ) {
            resolve();
          }
        } catch {
          // Non-JSON frame — ignore.
        }
      });
    });
  });
}

async function setupCollection(dataFactory: {
  createCollection: (overrides?: Record<string, unknown>) => Promise<{
    id: string;
    attributes: Record<string, unknown>;
  }>;
  addField: (
    collectionId: string,
    field: {
      name: string;
      displayName: string;
      type: string;
      required?: boolean;
    },
  ) => Promise<unknown>;
  waitForStorageReady: (collectionName: string) => Promise<void>;
}): Promise<string> {
  const collection = await dataFactory.createCollection();
  const collectionName = collection.attributes.name as string;
  await dataFactory.addField(collection.id, {
    name: "title",
    displayName: "Title",
    type: "string",
    required: true,
  });
  await dataFactory.waitForStorageReady(collectionName);
  return collectionName;
}

test.describe("Realtime updates", () => {
  test("an open record page shows a change made elsewhere", async ({
    page,
    dataFactory,
  }) => {
    const collectionName = await setupCollection(dataFactory);
    const record = await dataFactory.createRecord(collectionName, {
      title: `Before ${Date.now()}`,
    });

    const subscribed = waitForSubscribed(page, collectionName);
    const detailPage = new ObjectDetailPage(
      page,
      collectionName,
      record.id,
      tenantSlug,
    );
    await detailPage.goto();
    await expect(detailPage.fieldValues).toBeVisible();
    await subscribed;

    const after = `After ${Date.now()}`;
    await dataFactory.updateRecord(collectionName, record.id, { title: after });

    await expect(detailPage.fieldValues).toContainText(after, {
      timeout: 15_000,
    });
  });

  test("an open list shows a record created elsewhere", async ({
    page,
    dataFactory,
  }) => {
    const collectionName = await setupCollection(dataFactory);

    const subscribed = waitForSubscribed(page, collectionName);
    await page.goto(`/${tenantSlug}/app/o/${collectionName}`);
    await subscribed;

    const title = `Created elsewhere ${Date.now()}`;
    await dataFactory.createRecord(collectionName, { title });

    await expect(page.getByText(title)).toBeVisible({ timeout: 15_000 });
  });
});
