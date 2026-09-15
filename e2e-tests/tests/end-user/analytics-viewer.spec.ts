import { test, expect } from '@playwright/test'

/**
 * End-user analytics viewer (app-surfacing slice 3) — post-deploy flow.
 *
 * Skip-gated until the /app/analytics routes are deployed (the e2e suite runs against the
 * live environment). To arm: remove `.skip` and seed a dashboard (dashboard +
 * dashboard-components rows targeting a real collection) plus a runnable report — no
 * native authoring UI exists yet, so seed via the admin resource browser or API.
 */
test.describe.skip('Analytics viewer (post-deploy)', () => {
  test('hub → dashboard → drill-through → report → export', async ({ page }) => {
    await page.goto('/acme/app/analytics')
    await expect(page.getByTestId('hub-dashboard-card').first()).toBeVisible()

    // Open the dashboard: a metric renders a value, the grid is present.
    await page.getByTestId('hub-dashboard-card').first().click()
    await expect(page.getByTestId('dashboard-grid')).toBeVisible()
    await expect(page.getByTestId('metric-widget').first()).not.toHaveText('—')

    // A widget seeded with config.ignoreTimeRange/fixedTimeRange shows its opt-out chip
    // regardless of the page's selected time range.
    await expect(page.getByTestId('widget-time-range-chip').first()).toBeVisible()

    // Chart segment drill-through lands on a filtered list.
    await page.getByTestId('chart-widget').first().locator('path,rect').first().click()
    await expect(page).toHaveURL(/\/app\/o\/[^/]+\?.*filter=/)

    // Run a report and export CSV.
    await page.goto('/acme/app/analytics')
    await page.getByTestId('hub-report-run').first().click()
    await expect(page.getByTestId('report-row').first()).toBeVisible()
    const download = page.waitForEvent('download')
    await page.getByTestId('export-csv').click()
    expect((await download).suggestedFilename()).toMatch(/\.csv$/)
  })

  test('a user without VIEW_ANALYTICS sees the denied fallback', async ({ page }) => {
    // Log in as a Minimum Access user (fixture-specific), then:
    await page.goto('/acme/app/analytics')
    await expect(page.getByText(/do not have the required permission/i)).toBeVisible()
  })
})
