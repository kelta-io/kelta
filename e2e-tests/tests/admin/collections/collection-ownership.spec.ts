import { test, expect } from "../../../fixtures";
import { getApiToken } from "../../../fixtures/auth-tokens";

/**
 * Owner-scoped collections (member data ownership slice 2).
 *
 * An admin turns on Ownership in the collection form (owner field "Created by", restricted to
 * portal members). A portal member — acting through a personal access token an admin minted for
 * them, the same credential an external member site uses — then sees only the records they
 * created on the generic collection route, while staff still see every record.
 */
test.describe("Collection ownership", () => {
  test("setting Ownership limits a portal session to its own rows", async ({
    page,
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const collection = await dataFactory.createCollection();
    const name = collection.attributes.name as string;
    await dataFactory.addField(collection.id, {
      name: "title",
      displayName: "Title",
      type: "string",
    });
    await dataFactory.waitForStorageReady(name);

    // ── Admin UI: Ownership → owner field Created by, restricted to portal members ──
    await page.goto(`/${tenantSlug}/collections/${collection.id}/edit`);
    const group = page.getByTestId("collection-ownership-group");
    await expect(group).toBeVisible({ timeout: 15_000 });
    await page.getByTestId("collection-owner-field-select").selectOption("createdBy");
    await page.getByTestId("collection-owner-scope-portal").check();
    await expect(page.getByTestId("collection-owner-scope-reads-checkbox")).toBeChecked();

    const saved = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/collections/${collection.id}`) &&
        ["PUT", "PATCH"].includes(resp.request().method()),
      { timeout: 15_000 },
    );
    await page.getByTestId("collection-form-submit").click();
    expect((await saved).ok()).toBeTruthy();

    const adminToken = await getApiToken();
    const api = async (token: string, method: string, path: string, body?: unknown) => {
      const resp = await fetch(`${apiBaseUrl}/${tenantSlug}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/vnd.api+json",
          Accept: "application/vnd.api+json",
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await resp.text();
      let parsed: any = null;
      try {
        parsed = text ? JSON.parse(text) : null;
      } catch {
        parsed = { raw: text };
      }
      return { status: resp.status, body: parsed };
    };

    const stored = await api(adminToken, "GET", `/api/collections/${collection.id}`);
    expect(stored.body.data.attributes.ownerField).toBe("createdBy");
    expect(stored.body.data.attributes.ownerScope).toBe("PORTAL");

    // A row owned by the admin, which the member must never see.
    const adminRow = await dataFactory.createRecord(name, { title: "staff row" });

    // ── A portal member with a PAT and ordinary CRUD on the collection ──
    const email = `e2e-owner-${Date.now()}@example.com`;
    const invite = await api(adminToken, "POST", "/api/admin/users/portal-invite", {
      email,
      firstName: "Owner",
      lastName: "Scoped",
    });
    expect(invite.status, `portal invite failed: ${JSON.stringify(invite.body)}`).toBe(201);
    const memberId = invite.body.userId as string;

    const minted = await api(adminToken, "POST", `/api/admin/users/${memberId}/tokens`, {
      name: "e2e owner scoping",
      expiresInDays: 1,
    });
    expect(minted.status, `admin PAT mint failed: ${JSON.stringify(minted.body)}`).toBe(200);
    const memberToken = minted.body.token as string;

    const profiles = await api(
      adminToken,
      "GET",
      `/api/profiles?filter[name][eq]=${encodeURIComponent("Portal User")}`,
    );
    const portalProfileId = profiles.body?.data?.[0]?.id as string | undefined;
    expect(portalProfileId, "the Portal User profile is seeded for every tenant").toBeTruthy();
    await api(adminToken, "POST", "/api/profile-object-permissions", {
      data: {
        type: "profile-object-permissions",
        attributes: {
          profileId: portalProfileId,
          collectionId: collection.id,
          canCreate: true,
          canRead: true,
          canEdit: true,
          canDelete: true,
        },
      },
    });

    // The grant reaches Cerbos asynchronously; poll the member's own create.
    let created: { status: number; body: any } = { status: 0, body: null };
    for (let attempt = 0; attempt < 20; attempt++) {
      created = await api(memberToken, "POST", `/api/${name}`, {
        data: { type: name, attributes: { title: "member row" } },
      });
      if (created.status === 201 || created.status === 200) break;
      await new Promise((resolve) => setTimeout(resolve, 2_000));
    }
    expect(
      [200, 201],
      `portal member could not write after the grant (${created.status}): ${JSON.stringify(created.body)}`,
    ).toContain(created.status);

    // ── The member sees only their own row; staff see both ──
    const memberList = await api(memberToken, "GET", `/api/${name}`);
    expect(memberList.status).toBe(200);
    const memberTitles = memberList.body.data.map(
      (r: { attributes: { title: string } }) => r.attributes.title,
    );
    expect(memberTitles).toEqual(["member row"]);
    expect(memberList.body.meta.totalCount).toBe(1);

    const foreign = await api(memberToken, "GET", `/api/${name}/${adminRow.id}`);
    expect(foreign.status).toBe(404);

    const staffList = await api(adminToken, "GET", `/api/${name}`);
    expect(staffList.body.meta.totalCount).toBe(2);
  });
});
