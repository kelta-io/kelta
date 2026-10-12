import { test, expect } from "../../fixtures";
import { myUserId, tenantApi } from "../../helpers/api-request";

/**
 * Approvals inbox (app-surfacing slice 2): a full mutation cycle, not just render.
 *
 * Seeds a collection with an active approval process whose single USER step is assigned
 * to the signed-in e2e user, then: submit from the record header → the record shows
 * Pending Approval → the step is in /app/approvals → approve with a comment → it leaves
 * the inbox, and the instance is APPROVED server-side with the comment recorded.
 *
 * Submitter and approver are the same user here; that the approver must be the assignee
 * (and that a body-supplied identity cannot impersonate one) is proven against the real
 * stack by the kelta-test-harness ApprovalActorScenarioTest.
 */
interface JsonApiList {
  data?: { id: string; attributes: Record<string, unknown> }[];
}

test.describe("Approvals inbox", () => {
  // Leaves rows with no delete endpoint (or edits the shared admin user), so it
  // runs only on the throwaway PR stack — never in the post-deploy run.
  test.skip(
    process.env.E2E_EPHEMERAL_STACK !== "true",
    "ephemeral CI stack only (E2E_EPHEMERAL_STACK)",
  );

  test("submit → inbox → approve with comment → instance approved", async ({
    page,
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const me = await myUserId(api);

    const collection = await dataFactory.createCollection();
    const collectionName = collection.attributes.name as string;
    await dataFactory.addField(collection.id, {
      name: "title",
      displayName: "Title",
      type: "string",
    });
    await dataFactory.waitForStorageReady(collectionName);
    const record = await dataFactory.createRecord(collectionName, {
      title: "Needs sign-off",
    });

    const process = await dataFactory.createResource("approval-processes", {
      collectionId: collection.id,
      name: `E2E approval ${Date.now()}`,
      active: true,
      recordEditability: "UNLOCKED",
      allowRecall: true,
    });
    await dataFactory.createResource("approval-steps", {
      approvalProcessId: process.id,
      stepNumber: 1,
      name: "Sign-off",
      approverType: "USER",
      approverId: me,
    });

    try {
      // 1. Submit for approval from the record header.
      await page.goto(`/${tenantSlug}/app/o/${collectionName}/${record.id}`);
      const submitButton = page.getByTestId("submit-approval-button");
      await expect(submitButton).toBeVisible({ timeout: 15_000 });
      const submitted = page.waitForResponse(
        (resp) =>
          resp.url().includes("/api/approvals/submit") &&
          resp.request().method() === "POST",
      );
      await submitButton.click();
      expect((await submitted).ok(), "POST /api/approvals/submit").toBe(true);
      await expect(page.getByTestId("pending-approval-badge")).toBeVisible();

      // 2. The pending step is in the assignee's inbox.
      await page.goto(`/${tenantSlug}/app/approvals`);
      const row = page
        .getByTestId("pending-approval-row")
        .filter({ hasText: record.id.slice(0, 8) });
      await expect(row).toHaveCount(1, { timeout: 15_000 });

      // 3. Approve with a comment.
      await row.getByTestId("approve-button").click();
      await page.getByTestId("approval-comment").fill("e2e approved");
      const approved = page.waitForResponse(
        (resp) =>
          /\/api\/approvals\/[^/]+\/approve$/.test(new URL(resp.url()).pathname) &&
          resp.request().method() === "POST",
      );
      await page.getByTestId("approval-confirm").click();
      expect((await approved).ok(), "POST /api/approvals/{id}/approve").toBe(true);
      await expect(row).toHaveCount(0);

      // 4. Server-side: the instance is APPROVED and the step carries the comment.
      const instances = await api<JsonApiList>(
        "GET",
        `/api/approval-instances?filter[recordId][eq]=${record.id}`,
      );
      expect(instances.status).toBe(200);
      expect(instances.body.data ?? []).toHaveLength(1);
      const instance = instances.body.data![0];
      expect(instance.attributes.status).toBe("APPROVED");

      const steps = await api<JsonApiList>(
        "GET",
        `/api/approval-step-instances?filter[approvalInstanceId][eq]=${instance.id}`,
      );
      expect(steps.body.data ?? []).toHaveLength(1);
      expect(steps.body.data![0].attributes.status).toBe("APPROVED");
      expect(steps.body.data![0].attributes.comments).toBe("e2e approved");

      // 5. The submitter's view of it: My submissions lists it as APPROVED.
      await page.getByTestId("tab-submissions").click();
      await expect(
        page
          .getByTestId("submission-row")
          .filter({ hasText: record.id.slice(0, 8) }),
      ).toContainText("APPROVED");
    } finally {
      // Instances reference the process; remove them (children first) so the
      // dataFactory teardown can delete the steps, the process and the collection.
      const instances = await api<JsonApiList>(
        "GET",
        `/api/approval-instances?filter[approvalProcessId][eq]=${process.id}`,
      );
      for (const instance of instances.body?.data ?? []) {
        const steps = await api<JsonApiList>(
          "GET",
          `/api/approval-step-instances?filter[approvalInstanceId][eq]=${instance.id}`,
        );
        for (const step of steps.body?.data ?? []) {
          await api("DELETE", `/api/approval-step-instances/${step.id}`);
        }
        await api("DELETE", `/api/approval-instances/${instance.id}`);
      }
    }
  });
});
