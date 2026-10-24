import { test, expect } from "../../fixtures";
import { AppHomePage } from "../../pages/end-user/app-home.page";

/**
 * In-app notifications bell in the end-user header: the badge is
 * meta.unreadCount of GET /api/me/notifications?unread=true, the dropdown lists
 * the unread ones, and opening one POSTs /{id}/read and drops the count.
 *
 * The list/mark-read responses are stubbed so the spec does not depend on a
 * flow having sent this user notifications; the first test checks the real
 * endpoint the bell calls.
 */
test.describe("Notification bell", () => {
  test("loads the caller's unread notifications from the real API", async ({
    page,
  }) => {
    const unread = page.waitForResponse(
      (resp) =>
        resp.url().includes("/api/me/notifications?unread=true") &&
        resp.request().method() === "GET",
      { timeout: 15_000 },
    );
    await new AppHomePage(page).goto();

    const response = await unread;
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(typeof body.meta.unreadCount).toBe("number");
    await expect(page.getByTestId("notification-bell")).toBeVisible();
  });

  test("shows the unread badge and marks a notification read", async ({
    page,
  }) => {
    let unreadCount = 2;
    const items = [
      { id: "e2e-n-1", title: "E2E order approved" },
      { id: "e2e-n-2", title: "E2E shipment late" },
    ];
    await page.route(/\/api\/me\/notifications\?unread=true/, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          data: items.map((n) => ({
            id: n.id,
            type: "notifications",
            attributes: {
              title: n.title,
              body: `${n.title} body`,
              link: null,
              level: "INFO",
              readAt: null,
              createdAt: "2026-10-24T10:00:00Z",
            },
          })),
          meta: { unreadCount },
        }),
      }),
    );
    let markedRead: string | null = null;
    await page.route(/\/api\/me\/notifications\/[^/]+\/read$/, (route) => {
      markedRead = route.request().url();
      unreadCount = 1;
      items.shift();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          data: { id: "e2e-n-1", type: "notifications", attributes: {} },
          meta: { unreadCount: 1 },
        }),
      });
    });

    await new AppHomePage(page).goto();
    await expect(page.getByTestId("notification-bell-badge")).toHaveText("2", {
      timeout: 15_000,
    });

    await page.getByTestId("notification-bell").click();
    await expect(page.getByTestId("notification-item-e2e-n-2")).toBeVisible();
    await page.getByTestId("notification-item-e2e-n-1").click();

    await expect(page.getByTestId("notification-bell-badge")).toHaveText("1");
    expect(markedRead).toContain("/api/me/notifications/e2e-n-1/read");
  });
});
