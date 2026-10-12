import { test, expect } from "../../fixtures";
import { tenantApi } from "../../helpers/api-request";

/**
 * Provider self-service availability (`/app/provider-availability`, telehealth).
 *
 * The page reads and replaces GET/PUT `/api/telehealth/availability/me`; the provider id
 * comes from the authenticated identity, never the body, so any INTERNAL user — here the
 * e2e admin — edits only their own schedule. Unit coverage: AvailabilityServiceTest
 * (worker) + ProviderAvailabilityPage.test.tsx (ui).
 *
 * The suite also runs post-deploy against a shared tenant, so the admin's schedule is
 * read before the test and written back afterwards.
 */
interface AvailabilityDto {
  timezone: string;
  rules: { weekday: number; startTime: string; endTime: string }[];
  exceptions: {
    exceptionDate: string;
    closed: boolean;
    startTime: string | null;
    endTime: string | null;
  }[];
}

const MONDAY = 1;

test.describe("Provider availability", () => {
  // Leaves rows with no delete endpoint (or edits the shared admin user), so it
  // runs only on the throwaway PR stack — never in the post-deploy run.
  test.skip(
    process.env.E2E_EPHEMERAL_STACK !== "true",
    "ephemeral CI stack only (E2E_EPHEMERAL_STACK)",
  );

  test("a provider adds weekly hours, saves, and they persist", async ({
    page,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const original = await api<AvailabilityDto>(
      "GET",
      "/api/telehealth/availability/me",
    );
    expect(
      original.status,
      `GET availability/me failed: ${JSON.stringify(original.body)}`,
    ).toBe(200);
    const mondayWindowsBefore = original.body.rules.filter(
      (r) => r.weekday === MONDAY,
    ).length;

    try {
      await page.goto(`/${tenantSlug}/app/provider-availability`);
      await expect(
        page.getByRole("heading", { name: "My availability" }),
      ).toBeVisible();

      // The weekday rows render Monday first; its "Add hours" adds a 09:00–17:00 window.
      await page.getByRole("button", { name: "Add hours" }).first().click();

      const saved = page.waitForResponse(
        (resp) =>
          resp.url().includes("/api/telehealth/availability/me") &&
          resp.request().method() === "PUT",
      );
      await page.getByRole("button", { name: "Save changes" }).click();
      const saveResponse = await saved;
      expect(saveResponse.ok(), `PUT availability/me ${saveResponse.status()}`).toBe(
        true,
      );
      await expect(page.getByText("Availability saved")).toBeVisible();

      // Persisted server-side: the new Monday window is in the provider's own schedule…
      const after = await api<AvailabilityDto>(
        "GET",
        "/api/telehealth/availability/me",
      );
      const mondayRules = after.body.rules.filter((r) => r.weekday === MONDAY);
      expect(mondayRules).toHaveLength(mondayWindowsBefore + 1);
      expect(
        mondayRules.some(
          (r) =>
            r.startTime.startsWith("09:00") && r.endTime.startsWith("17:00"),
        ),
      ).toBe(true);

      // …and the page shows it again after a reload.
      await page.reload();
      await expect(
        page.getByRole("heading", { name: "My availability" }),
      ).toBeVisible();
      const startInputs = page.getByLabel("Start", { exact: true });
      await expect(startInputs).toHaveCount(
        original.body.rules.length + 1 + countOpenExceptions(original.body),
      );
      await expect(startInputs.first()).toHaveValue(/^\d{2}:\d{2}$/);
    } finally {
      const restored = await api("PUT", "/api/telehealth/availability/me", {
        timezone: original.body.timezone,
        rules: original.body.rules,
        exceptions: original.body.exceptions.map((e) => ({
          exceptionDate: e.exceptionDate,
          closed: e.closed,
          startTime: e.closed ? "" : e.startTime,
          endTime: e.closed ? "" : e.endTime,
        })),
      });
      expect(
        restored.status,
        `restoring availability failed: ${JSON.stringify(restored.body)}`,
      ).toBe(200);
    }
  });
});

/** Date exceptions with hours (not closed all day) also render a "Start" time input. */
function countOpenExceptions(dto: AvailabilityDto): number {
  return dto.exceptions.filter((e) => !e.closed).length;
}
