import { test, expect } from "../../../fixtures";
import { PageLayoutsPage } from "../../../pages/page-layouts.page";
import { ObjectDetailPage } from "../../../pages/end-user/object-detail.page";
import { getApiToken } from "../../../fixtures/auth-tokens";

test.describe("Page Layouts", () => {
  let pageLayoutsPage: PageLayoutsPage;

  test.beforeEach(async ({ page }) => {
    pageLayoutsPage = new PageLayoutsPage(page);
    await pageLayoutsPage.goto();
  });

  test("displays page layouts list", async () => {
    await pageLayoutsPage.waitForTableLoaded();
  });

  test("shows layouts in table", async () => {
    await pageLayoutsPage.waitForTableLoaded();
    const rowCount = await pageLayoutsPage.getRowCount();
    expect(rowCount).toBeGreaterThanOrEqual(0);
  });

  test("has create layout button", async () => {
    await pageLayoutsPage.waitForTableLoaded();
    await expect(pageLayoutsPage.createButton).toBeVisible();
  });
});

const tenantSlug = process.env.E2E_TENANT_SLUG || "default";
const apiBaseUrl = process.env.E2E_API_BASE_URL || "https://kelta.io";

interface TreeCounts {
  layoutId: string;
  collection: string;
  name: string;
  created: number;
  updated: number;
  deleted: number;
  unchanged: number;
}

async function treeFetch<T>(
  method: string,
  path: string,
  token: string,
  body?: unknown,
): Promise<T> {
  const response = await fetch(`${apiBaseUrl}/${tenantSlug}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${path} -> ${response.status}: ${text}`);
  }
  return JSON.parse(text) as T;
}

/**
 * The layout tree endpoint: one PUT builds sections + placements by field NAME, a second
 * identical PUT writes nothing, and the declared 0-based columns drive what the record
 * detail page renders.
 */
test.describe("Page Layouts — tree endpoint", () => {
  let apiToken: string;
  let layoutId: string | null = null;

  test.beforeAll(async () => {
    apiToken = await getApiToken();
  });

  test.afterEach(async () => {
    if (layoutId) {
      await fetch(`${apiBaseUrl}/${tenantSlug}/api/page-layouts/${layoutId}`, {
        method: "DELETE",
        headers: { Authorization: `Bearer ${apiToken}` },
      }).catch(() => undefined);
      layoutId = null;
    }
  });

  test("applies once, converges on re-apply, and renders the declared columns", async ({
    page,
    dataFactory,
  }) => {
    const collection = await dataFactory.createCollection();
    const collectionName = collection.attributes.name as string;

    for (const [name, displayName] of [
      ["title", "Title"],
      ["owner", "Owner"],
      ["status", "Status"],
      ["notes", "Notes"],
    ]) {
      await dataFactory.addField(collection.id, {
        name,
        displayName,
        type: "string",
      });
    }
    await dataFactory.waitForStorageReady(collectionName);

    // Declared order differs from render order on purpose: column 0 holds
    // title + owner and column 1 holds status + notes, so a renderer that
    // ignored `column` would show them in the body order below.
    const layoutName = `tree-layout-${Date.now()}`;
    const body = {
      isDefault: true,
      layoutType: "DETAIL",
      sections: [
        {
          heading: "Overview",
          columns: 2,
          collapsed: false,
          fields: [
            { name: "title", column: 0 },
            { name: "owner", column: 0 },
            { name: "status", column: 1 },
            { name: "notes", column: 1 },
          ],
        },
      ],
      relatedLists: [],
    };
    const treePath = `/api/collections/${collectionName}/layouts/${layoutName}/tree`;

    // ---- First apply: layout + section + four placements, in one call ----
    const first = await treeFetch<TreeCounts>("PUT", treePath, apiToken, body);
    layoutId = first.layoutId;
    expect(first.created).toBe(6);
    expect(first.updated).toBe(0);
    expect(first.deleted).toBe(0);

    // ---- Second apply: idempotent, nothing written ----
    const second = await treeFetch<TreeCounts>("PUT", treePath, apiToken, body);
    expect(second).toMatchObject({
      created: 0,
      updated: 0,
      deleted: 0,
      unchanged: 6,
    });

    // ---- GET returns names, not ids, and round-trips with no diff ----
    const tree = await treeFetch<Record<string, unknown>>(
      "GET",
      `/api/page-layouts/${first.layoutId}/tree`,
      apiToken,
    );
    expect(tree.collection).toBe(collectionName);
    const sections = tree.sections as Array<Record<string, unknown>>;
    const fields = sections[0].fields as Array<Record<string, unknown>>;
    expect(fields.map((f) => f.name)).toEqual([
      "title",
      "owner",
      "status",
      "notes",
    ]);
    expect(fields.map((f) => f.column)).toEqual([0, 0, 1, 1]);

    const roundTrip = await treeFetch<TreeCounts>(
      "PUT",
      `/api/page-layouts/${first.layoutId}/tree`,
      apiToken,
      tree,
    );
    expect(roundTrip).toMatchObject({ created: 0, updated: 0, deleted: 0 });

    // ---- Removing a field from the body deletes its placement ----
    const trimmed = {
      ...body,
      sections: [
        { ...body.sections[0], fields: body.sections[0].fields.slice(0, 3) },
      ],
    };
    const afterDelete = await treeFetch<TreeCounts>(
      "PUT",
      treePath,
      apiToken,
      trimmed,
    );
    expect(afterDelete.deleted).toBe(1);
    await treeFetch<TreeCounts>("PUT", treePath, apiToken, body);

    // ---- An unknown field name is a 400 naming the offending position ----
    const badResponse = await fetch(`${apiBaseUrl}/${tenantSlug}${treePath}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiToken}`,
      },
      body: JSON.stringify({
        ...body,
        sections: [
          {
            ...body.sections[0],
            fields: [...body.sections[0].fields.slice(0, 2), { name: "nope" }],
          },
        ],
      }),
    });
    expect(badResponse.status).toBe(400);
    const badJson = (await badResponse.json()) as {
      errors: Array<{ source?: { pointer?: string } }>;
    };
    expect(badJson.errors[0].source?.pointer).toBe(
      "/sections/0/fields/2/name",
    );

    // ---- The record detail page renders the fields in the declared columns ----
    const record = await dataFactory.createRecord(collectionName, {
      title: "Tree record",
      owner: "Ada",
      status: "Open",
      notes: "Rendered from the tree",
    });
    const detailPage = new ObjectDetailPage(
      page,
      collectionName,
      record.id,
      tenantSlug,
    );
    await detailPage.goto();
    await expect(detailPage.fieldValues).toBeVisible();

    const section = page.locator('[id^="record-section-"]').first();
    await expect(section).toBeVisible();
    // CSS grid fills left-to-right, top-to-bottom, so a 2-column section reads
    // row-major: (col0 row0, col1 row0, col0 row1, col1 row1).
    await expect(async () => {
      const labels = await section
        .locator(".kelta-field-label")
        .allTextContents();
      expect(labels.map((l) => l.trim())).toEqual([
        "Title",
        "Status",
        "Owner",
        "Notes",
      ]);
    }).toPass({ timeout: 15_000 });
  });
});
