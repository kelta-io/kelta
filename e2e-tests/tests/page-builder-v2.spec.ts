import { test, expect } from "../fixtures";
import { tenantApi } from "../helpers/api-request";

/**
 * Page Builder v2 — an authored page's events run on the published runtime page
 * (page-builder parity slice 2e; parent spec §"End-to-end e2e ownership").
 *
 * The load-bearing assertion is a REAL MUTATION: a button whose `onClick` carries a
 * `createRecord` action persists a record when clicked on `/app/p/{slug}`, queried back
 * over the authorized JSON:API — not merely that the page renders.
 *
 * The page is authored through the same `ui-pages` JSON:API the builder saves through, in
 * the v2 `config` shape (`schemaVersion: 2`, `components[].events`). The builder's own
 * editing UI (palette drag-and-drop, the event-list inspector) is covered by
 * PageBuilderPage*.test.tsx / EventListField; the server's config round-trip by the
 * kelta-test-harness PageRenderV2ScenarioTest.
 */
test.describe("Page Builder v2 — events & actions", () => {
  test("a button onClick createRecord event persists a record on the runtime page", async ({
    page,
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    // 1. A target collection with a text field the button writes to.
    const collection = await dataFactory.createCollection();
    const collectionName = collection.attributes.name as string;
    await dataFactory.addField(collection.id, {
      name: "status",
      displayName: "Status",
      type: "string",
    });
    await dataFactory.waitForStorageReady(collectionName);

    const marker = `e2e-${Date.now()}`;

    // 2. A published v2 page: one button whose onClick creates a record with the marker.
    const uiPage = await dataFactory.createResource("ui-pages", {
      name: `E2E Events ${marker}`,
      slug: `e2e-events-${marker}`,
      path: `/e2e-events-${marker}`,
      title: `E2E Events ${marker}`,
      published: true,
      config: {
        schemaVersion: 2,
        layout: { kind: "grid" },
        components: [
          {
            id: "place-order",
            type: "button",
            props: { label: "Place order" },
            events: {
              onClick: [
                {
                  action: "createRecord",
                  collection: collectionName,
                  attributes: { status: marker },
                },
              ],
            },
          },
        ],
      },
    });
    const pageSlug = uiPage.attributes.slug as string;
    expect(pageSlug, "ui-pages create returns the page slug").toBeTruthy();

    // 3. Open the published runtime page and click the button (fires the action).
    await page.goto(`/${tenantSlug}/app/p/${pageSlug}`);
    const button = page.getByTestId("page-node-button");
    await expect(button).toHaveText("Place order");

    const created = page.waitForResponse(
      (resp) =>
        resp.request().method() === "POST" &&
        new URL(resp.url()).pathname.endsWith(`/api/${collectionName}`),
    );
    await button.click();
    const createResponse = await created;
    expect(
      createResponse.ok(),
      `createRecord POST returned ${createResponse.status()}`,
    ).toBe(true);

    // 4. THE load-bearing assertion: the record exists server-side with the marker.
    const api = tenantApi({ apiBaseUrl, tenantSlug });
    await expect
      .poll(
        async () => {
          const list = await api<{
            data?: { attributes?: Record<string, unknown> }[];
          }>("GET", `/api/${collectionName}?page[size]=50`);
          return (list.body?.data ?? []).filter(
            (r) => r.attributes?.status === marker,
          ).length;
        },
        {
          timeout: 15_000,
          message: "expected the onClick createRecord to persist a record",
        },
      )
      .toBe(1);
  });

  test("a form widget creates a record on the runtime page and runs onSubmit", async ({
    page,
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    // The form widget embeds the app's RecordForm. It used to mount a library form that
    // needed a provider the app never mounts, so any page with a form crashed to the error
    // screen — this pins that the form renders, saves, and stays on the page.
    const collection = await dataFactory.createCollection();
    const collectionName = collection.attributes.name as string;
    await dataFactory.addField(collection.id, {
      name: "status",
      displayName: "Status",
      type: "string",
    });
    await dataFactory.waitForStorageReady(collectionName);

    const marker = `e2e-form-${Date.now()}`;
    const uiPage = await dataFactory.createResource("ui-pages", {
      name: `E2E Form ${marker}`,
      slug: marker,
      path: `/${marker}`,
      title: `E2E Form ${marker}`,
      published: true,
      config: {
        schemaVersion: 2,
        layout: { kind: "grid" },
        components: [
          {
            id: "order-form",
            type: "form",
            props: {
              dataView: { collection: collectionName },
              mode: "create",
              readOnly: false,
            },
            events: {
              // The saved record is `record` in the onSubmit scope.
              onSubmit: [
                {
                  action: "showToast",
                  level: "info",
                  message: { $bind: "record.status" },
                },
              ],
            },
          },
        ],
      },
    });
    const pageSlug = uiPage.attributes.slug as string;

    await page.goto(`/${tenantSlug}/app/p/${pageSlug}`);
    const status = page.getByTestId("field-status");
    await expect(status).toBeVisible();
    await expect(page.getByTestId("error-boundary-fallback")).toHaveCount(0);

    await status.fill(marker);
    const created = page.waitForResponse(
      (resp) =>
        resp.request().method() === "POST" &&
        new URL(resp.url()).pathname.endsWith(`/api/${collectionName}`),
    );
    await page.getByTestId("save-button").click();
    const createResponse = await created;
    expect(
      createResponse.ok(),
      `form create POST returned ${createResponse.status()}`,
    ).toBe(true);

    // onSubmit ran with the saved record; the form stayed on the page, reset for the next entry.
    await expect(page.getByText(marker, { exact: true })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/app/p/${pageSlug}$`));
    await expect(status).toHaveValue("");

    const api = tenantApi({ apiBaseUrl, tenantSlug });
    await expect
      .poll(
        async () => {
          const list = await api<{
            data?: { attributes?: Record<string, unknown> }[];
          }>("GET", `/api/${collectionName}?page[size]=50`);
          return (list.body?.data ?? []).filter(
            (r) => r.attributes?.status === marker,
          ).length;
        },
        {
          timeout: 15_000,
          message: "expected the form widget to persist a record",
        },
      )
      .toBe(1);
  });
});
