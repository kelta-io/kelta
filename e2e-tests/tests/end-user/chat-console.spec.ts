import { test, expect } from "../../fixtures";
import { tenantApi } from "../../helpers/api-request";

/**
 * Staff chat console (`/app/chat`, telehealth slice 3), driven by the signed-in e2e user:
 * start an internal conversation → send a message from the composer → it is in the thread
 * and stored server-side → close it → the thread turns read-only.
 *
 * The portal side (chat-panel widget, a portal member's session) and the realtime
 * invalidation between two browsers are not exercised here: the conversation/message
 * contract, participant rows and the closed-conversation write rejection are proven
 * against the real stack by the kelta-test-harness ChatBackendScenarioTest, and the
 * widget by chatPanel.test.tsx.
 *
 * Conversations have no delete endpoint; the one this test opens is left CLOSED.
 */
test.describe("Chat console", () => {
  // Leaves rows with no delete endpoint (or edits the shared admin user), so it
  // runs only on the throwaway PR stack — never in the post-deploy run.
  test.skip(
    process.env.E2E_EPHEMERAL_STACK !== "true",
    "ephemeral CI stack only (E2E_EPHEMERAL_STACK)",
  );

  test("start a conversation, send a message, close it", async ({
    page,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const body = `hello from e2e ${Date.now()}`;

    await page.goto(`/${tenantSlug}/app/chat`);
    await expect(page.getByTestId("chat-console")).toBeVisible();

    // 1. Start a conversation; the console opens it.
    const started = page.waitForResponse(
      (resp) =>
        new URL(resp.url()).pathname.endsWith("/api/chat/conversations") &&
        resp.request().method() === "POST",
    );
    await page.getByTestId("chat-console-new").click();
    const startResponse = await started;
    expect(startResponse.status(), "POST /api/chat/conversations").toBe(201);
    const conversationId = ((await startResponse.json()) as { id: string }).id;
    await expect(page.getByTestId(`chat-console-item-${conversationId}`)).toBeVisible();

    // 2. Send a message from the composer.
    const composerInput = page.getByTestId("chat-console-composer-input");
    await expect(composerInput).toBeVisible();
    await composerInput.fill(body);
    const sent = page.waitForResponse(
      (resp) =>
        new URL(resp.url()).pathname.endsWith(
          `/api/chat/conversations/${conversationId}/messages`,
        ) && resp.request().method() === "POST",
    );
    await page.getByTestId("chat-console-composer-send").click();
    expect((await sent).status(), "POST .../messages").toBe(201);
    await expect(page.getByTestId("chat-console-messages")).toContainText(body);

    // Stored server-side, attributed to an internal sender.
    const history = await api<{
      data?: { body: string; senderType: string }[];
    }>("GET", `/api/chat/conversations/${conversationId}/messages`);
    expect(history.status).toBe(200);
    expect(history.body.data?.map((m) => m.body)).toContain(body);
    expect(
      history.body.data?.find((m) => m.body === body)?.senderType,
    ).toBe("INTERNAL");

    // 3. Close it: the composer is replaced by the read-only strip.
    const closed = page.waitForResponse(
      (resp) =>
        new URL(resp.url()).pathname.endsWith(
          `/api/chat/conversations/${conversationId}/close`,
        ) && resp.request().method() === "POST",
    );
    await page.getByTestId("chat-console-close").click();
    expect((await closed).ok(), "POST .../close").toBe(true);
    await expect(page.getByText("This conversation is closed")).toBeVisible();
    await expect(page.getByTestId("chat-console-composer")).toHaveCount(0);

    const conversation = await api<{ status?: string }>(
      "GET",
      `/api/chat/conversations/${conversationId}`,
    );
    expect(conversation.body.status).toBe("CLOSED");
  });
});
