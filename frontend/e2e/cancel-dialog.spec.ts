import { expect } from '@playwright/test'
import { test, apiLogin, registerCustomer, seedSession, RENDER_TIMEOUT } from './support/fixtures'

/**
 * The customer's cancellation dialog, on both outcomes.
 *
 * F7: the dialog closed from the per-call `onSuccess` alone, so a refused
 * cancellation left it open over the ride. Nothing else could clear it — the
 * dialog's visibility is local state, so unlike the stale-ride bug next door
 * the five-second poll cannot rescue it — and its confirm button re-enabled
 * the moment `isPending` cleared, inviting the same click that had just
 * failed.
 *
 * WHY CLOSING ON SETTLE DOES NOT HIDE THE ERROR, which is the one thing worth
 * checking before making this change: the error is rendered in the page body,
 * above the cancel control, not inside the dialog. The dialog was COVERING it.
 * Closing is what reveals it, and the failure test below asserts the error is
 * on screen afterwards rather than merely that the dialog went away.
 *
 * The refusal is real, not mocked: the ride is cancelled through the API
 * first, so the dialog's own attempt meets "Ride is already cancelled" — which
 * is also a refusal a customer can genuinely reach, by cancelling in two tabs.
 */

/** Both the page control and the dialog's confirm read "Cancel ride". */
const dialog = (page: import('@playwright/test').Page) => page.getByRole('dialog')

const confirmCancel = (page: import('@playwright/test').Page) =>
  dialog(page).getByRole('button', { name: 'Cancel ride' })

test.describe('Customer cancellation dialog', () => {
  let auth: { Authorization: string }

  test.beforeEach(async ({ page, context, app }) => {
    void app
    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)
    auth = { Authorization: `Bearer ${tokens.accessToken}` }
  })

  /** Books a ride through the real API and returns it. */
  async function bookARide(page: import('@playwright/test').Page) {
    const search = await page.request.post('/api/v1/rides/search', {
      headers: auth,
      data: {
        pickupLatitude: 41.3275,
        pickupLongitude: 19.8187,
        pickupAddress: 'Rruga e Kavajes',
        destinationLatitude: 41.3307,
        destinationLongitude: 19.8231,
        destinationAddress: 'Sheshi Skenderbej',
      },
    })
    expect(search.status(), 'the search must find a company').toBe(201)
    const found = await search.json()
    expect(found.taxiOptions?.length, 'a company must be available').toBeGreaterThan(0)

    const booked = await page.request.post(
      `/api/v1/rides/requests/${found.rideRequestId}/select`,
      { headers: auth, data: { offerId: found.taxiOptions[0].offerId } },
    )
    expect(booked.status(), 'the booking must succeed').toBe(201)
    return booked.json()
  }

  test('F7 · a successful cancellation closes the dialog', async ({ page, guards }) => {
    void guards

    await bookARide(page)
    await page.goto('/ride/current')

    await page.getByRole('button', { name: 'Cancel ride' }).click()
    await expect(dialog(page), 'the dialog must open first').toBeVisible({
      timeout: RENDER_TIMEOUT,
    })

    const cancelled = page.waitForResponse(
      (r) => /\/api\/v1\/rides\/\d+\/cancel$/.test(r.url()) && r.request().method() === 'POST',
    )
    await confirmCancel(page).click()
    expect((await cancelled).status(), 'the cancellation must succeed').toBe(200)

    await expect(dialog(page), 'the dialog must close once the ride is cancelled').toHaveCount(0)
  })

  test('F7 · a refused cancellation closes the dialog and shows why', async ({ page, guards }) => {
    void guards

    const ride = await bookARide(page)
    await page.goto('/ride/current')

    /* Open the dialog BEFORE the ride changes underneath it. */
    await page.getByRole('button', { name: 'Cancel ride' }).click()
    await expect(dialog(page), 'the dialog must open first').toBeVisible({
      timeout: RENDER_TIMEOUT,
    })

    /*
     * The ride is cancelled elsewhere — a second tab, in practice. The dialog
     * is local state and stays open through this.
     */
    const elsewhere = await page.request.post(`/api/v1/rides/${ride.rideId}/cancel`, {
      headers: auth,
    })
    expect(elsewhere.status(), 'the first cancellation must succeed').toBe(200)

    const refused = page.waitForResponse(
      (r) => /\/api\/v1\/rides\/\d+\/cancel$/.test(r.url()) && r.request().method() === 'POST',
    )
    await confirmCancel(page).click()

    const status = (await refused).status()
    expect(status, 'the second cancellation must be refused by a rule').toBeGreaterThanOrEqual(400)
    expect(status, 'the second cancellation must be refused by a rule').toBeLessThan(500)

    /*
     * Both halves of the fix. The dialog gone is the bug; the error on screen
     * is what makes closing it safe — if closing hid the failure this
     * assertion would catch it.
     */
    await expect(dialog(page), 'the dialog must not survive a refused cancellation').toHaveCount(0)
    await expect(
      page.getByRole('alert'),
      'the refusal must be visible now that the dialog is out of the way',
    ).toHaveCount(1)
  })
})
