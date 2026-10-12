import { test, expect } from "../../fixtures";
import { myUserId, tenantApi } from "../../helpers/api-request";

/**
 * Telehealth scheduling (slice 4) — the provider's side: a booked appointment appears on
 * /app/appointments and the provider cancels it from there.
 *
 * The signed-in e2e user acts as the provider (any INTERNAL user can). The booking is made
 * the way staff book on a portal member's behalf: an availability rule for tomorrow, a
 * portal member, a free slot from /api/telehealth/slots, then POST /api/telehealth/appointments.
 * That booking contract — slot computation, the double-book 409, the participant share and
 * cancel freeing the slot — is proven against the real stack by the kelta-test-harness
 * TelehealthSchedulingScenarioTest; the portal-side scheduler widget by
 * appointmentScheduler.test.tsx.
 *
 * Appointments have no delete endpoint; the one this test books is left CANCELLED. The
 * portal member is invited under a fixed address, and a re-invite reuses the user.
 */
interface Slot {
  start: string;
  end: string;
}

test.describe("Appointment scheduling", () => {
  // Leaves rows with no delete endpoint (or edits the shared admin user), so it
  // runs only on the throwaway PR stack — never in the post-deploy run.
  test.skip(
    process.env.E2E_EPHEMERAL_STACK !== "true",
    "ephemeral CI stack only (E2E_EPHEMERAL_STACK)",
  );

  test("a booked appointment is on the provider's schedule and can be cancelled", async ({
    page,
    dataFactory,
    tenantSlug,
    apiBaseUrl,
  }) => {
    const api = tenantApi({ apiBaseUrl, tenantSlug });
    const providerId = await myUserId(api);

    const invited = await api<{ userId?: string }>(
      "POST",
      "/api/admin/users/portal-invite",
      {
        email: "e2e-scheduling-patient@example.com",
        firstName: "Scheduling",
        lastName: "Patient",
      },
    );
    expect(
      [200, 201],
      `portal invite failed (${invited.status}): ${JSON.stringify(invited.body)}`,
    ).toContain(invited.status);
    const portalUserId = invited.body.userId as string;

    // Tomorrow, 09:00–17:00 UTC (weekday: Sunday = 0 … Saturday = 6).
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    tomorrow.setUTCHours(0, 0, 0, 0);
    await dataFactory.createResource("telehealth-availability", {
      providerId,
      kind: "RULE",
      weekday: tomorrow.getUTCDay(),
      startTime: "09:00",
      endTime: "17:00",
      timezone: "UTC",
      active: true,
    });

    const from = tomorrow.toISOString();
    const to = new Date(tomorrow.getTime() + 24 * 60 * 60 * 1000).toISOString();
    const slots = await api<{ data?: Slot[] }>(
      "GET",
      `/api/telehealth/slots?providerId=${providerId}&from=${encodeURIComponent(from)}` +
        `&to=${encodeURIComponent(to)}&duration=30`,
    );
    expect(slots.status, JSON.stringify(slots.body)).toBe(200);
    const slot = slots.body.data?.[0];
    expect(slot, "tomorrow's rule offers at least one free slot").toBeTruthy();

    const visitType = `E2E visit ${Date.now()}`;
    const booked = await api<{ id?: string; status?: string }>(
      "POST",
      "/api/telehealth/appointments",
      {
        providerId,
        portalUserId,
        start: slot!.start,
        durationMinutes: 30,
        visitType,
      },
    );
    expect(booked.status, JSON.stringify(booked.body)).toBe(201);
    const appointmentId = String(booked.body.id);

    // ── The provider's schedule lists it, confirmed ──
    await page.goto(`/${tenantSlug}/app/appointments`);
    await expect(
      page.getByRole("heading", { name: "My Appointments" }),
    ).toBeVisible();
    const row = page.getByTestId(`appointments-page-row-${appointmentId}`);
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText(visitType);
    await expect(row).toContainText("CONFIRMED");

    // ── Cancel from the row ──
    const cancelled = page.waitForResponse(
      (resp) =>
        new URL(resp.url()).pathname.endsWith(
          `/api/telehealth/appointments/${appointmentId}/cancel`,
        ) && resp.request().method() === "POST",
    );
    await row.getByRole("button", { name: "Cancel" }).click();
    expect((await cancelled).ok(), "POST .../cancel").toBe(true);
    await expect(row).toContainText("CANCELLED");
    await expect(row.getByRole("button", { name: "Cancel" })).toHaveCount(0);

    // The slot is free again server-side.
    const slotsAfter = await api<{ data?: Slot[] }>(
      "GET",
      `/api/telehealth/slots?providerId=${providerId}&from=${encodeURIComponent(from)}` +
        `&to=${encodeURIComponent(to)}&duration=30`,
    );
    expect(slotsAfter.body.data?.map((s) => s.start)).toContain(slot!.start);
  });
});
