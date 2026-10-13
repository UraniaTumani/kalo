import { expect } from '@playwright/test'
import { test, apiLogin, registerCustomer, seedSession, RENDER_TIMEOUT } from './support/fixtures'

/**
 * The current-ride screen after the ride has gone.
 *
 * F8: once a ride ends, `/rides/current` answers 404 — but TanStack keeps the
 * last successful `data` when a refetch fails, so the finished ride was still
 * in hand. The page read `currentQuery.data ?? fallback`, the retained ride
 * won, and the screen went on showing REQUESTED and "Waiting for the company
 * to respond" for a ride the customer had just cancelled themselves.
 *
 * WHAT MAKES THIS WORTH A BROWSER TEST, and the same thing that makes it
 * invisible to anything smaller: the bug only exists in the gap between two
 * fetches of the same query. One render is correct, and so is one request.
 * It takes a successful fetch, then a failing one, then a look at the screen.
 *
 * NOTHING IS MOCKED. The ride is cancelled through the real API, which is what
 * makes the next poll a real 404 — the same 404 a customer's own cancellation
 * produces.
 */

const WAITING = /waiting for the company to respond/i

test.describe('Current ride state lifetime', () => {
  let auth: { Authorization: string }

  test.beforeEach(async ({ page, context, app }) => {
    void app
    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)
    auth = { Authorization: `Bearer ${tokens.accessToken}` }
  })

  test('F8 · a cancelled ride stops being shown as the current one', async ({ page, guards }) => {
    void guards

    /*
     * Booked through the API rather than the form: this test is about what the
     * screen does after the ride ends, and driving the address pickers adds
     * two geocoder round trips and nothing else.
     */
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
    expect(found.taxiOptions?.length, 'a company must be available to book').toBeGreaterThan(0)

    const booked = await page.request.post(
      `/api/v1/rides/requests/${found.rideRequestId}/select`,
      { headers: auth, data: { offerId: found.taxiOptions[0].offerId } },
    )
    expect(booked.status(), 'the booking must succeed').toBe(201)
    const ride = await booked.json()

    await page.goto('/ride/current')

    /*
     * The positive half, established before anything is asserted absent: the
     * live ride really is on screen, waiting for the company.
     */
    await expect(page.getByText(WAITING)).toBeVisible({ timeout: RENDER_TIMEOUT })

    /* The ride ends. From here /rides/current answers 404. */
    const cancelled = await page.request.post(`/api/v1/rides/${ride.rideId}/cancel`, {
      headers: auth,
    })
    expect(cancelled.status(), 'the cancellation must succeed').toBe(200)

    const gone = await page.request.get('/api/v1/rides/current', { headers: auth })
    expect(gone.status(), 'the server must now say there is no current ride').toBe(404)

    /*
     * Both halves again. The page polls every 5 seconds while a ride is
     * active, so it gets its 404 on its own — no reload, which would hide the
     * bug by remounting the query.
     *
     * "Cancelled" present is what proves the screen moved on rather than
     * merely losing the banner, and the banner's absence is what proves the
     * retained ride is no longer winning.
     */
    await expect(page.getByText(/cancelled/i).first()).toBeVisible({ timeout: RENDER_TIMEOUT })
    await expect(
      page.getByText(WAITING),
      'the cancelled ride must not still be presented as the current one',
    ).toHaveCount(0)
  })

  /*
   * The other half of the fix, and the reason it reads the 404 specifically
   * rather than `isError`: a ride that is genuinely active must still be shown.
   * Without this, "stop trusting retained data" could be satisfied by a page
   * that shows nothing at all.
   */
  test('F8 · a live ride is still shown while it is live', async ({ page, guards }) => {
    void guards

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
    expect(search.status()).toBe(201)
    const found = await search.json()

    const booked = await page.request.post(
      `/api/v1/rides/requests/${found.rideRequestId}/select`,
      { headers: auth, data: { offerId: found.taxiOptions[0].offerId } },
    )
    expect(booked.status()).toBe(201)

    await page.goto('/ride/current')

    await expect(page.getByText(WAITING)).toBeVisible({ timeout: RENDER_TIMEOUT })

    /* Still there after several poll cycles, not blanked by the new ordering. */
    await page.waitForTimeout(6_000)
    await expect(page.getByText(WAITING)).toBeVisible()
  })
})
