import { expect } from '@playwright/test'
import { test, apiLogin, registerCustomer, seedSession } from './support/fixtures'

/**
 * State that outlives the thing it described, on the booking page.
 *
 * F38 is the first of this family: a failed selection left its red banner on
 * screen for ever, because TanStack Query keeps a mutation's error until
 * something clears it and nothing did. The banner then sat under a fresh set
 * of search results, describing a ride request that no longer existed — and in
 * the observation that produced the finding, it was on screen before the
 * customer had chosen any company at all, claiming a failure for an action
 * they had not taken.
 *
 * WHY THIS HAS TO BE A BROWSER TEST. The bug is not in a function; it is in
 * how long a value lives across two user actions. Nothing that tests one
 * render, or one mutation, can see it. The test has to do the first thing,
 * then the second, and look at the screen after both.
 *
 * NOTHING IS MOCKED, per the suite's own rule. The selection is made to fail
 * the way it fails in production — the request is already SELECTED, so the
 * server refuses a second selection against it — by taking the offer through
 * the real API first. The ride that creates is then cancelled through the real
 * API too, which is what frees the customer to search again; without that the
 * second search would be refused for an active ride and would prove nothing.
 */

/** The offers panel's own alert. `tone="danger"` is what renders `role="alert"`. */
const alerts = (page: import('@playwright/test').Page) => page.getByRole('alert')

const offerButtons = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /choose|select/i })

/**
 * Puts an address into one of the two location fields.
 *
 * AddressInput swaps its own markup once a place is chosen: the text input is
 * replaced by a chip showing the label and a "Clear <field>" button, so there
 * is nothing left to type into. A second search therefore has to clear the
 * field first — which is also how a customer does it — and aiming `fill` at an
 * input that no longer exists is what made this test time out rather than
 * fail.
 */
async function enterAddress(
  page: import('@playwright/test').Page,
  field: 'pickup' | 'destination',
  address: string,
) {
  const clear = page.getByRole('button', { name: new RegExp('^clear ' + field + '$', 'i') })
  if (await clear.count()) {
    await clear.click()
  }

  await page.getByLabel(new RegExp('^' + field, 'i')).fill(address)
  await page.getByRole('option').first().click()
}

/**
 * Fills both addresses and searches, returning the search response body.
 *
 * The addresses are arguments because this test searches twice and the second
 * search has to be a different one: a new ride request, not a retry of the
 * refused one.
 */
async function searchForTaxis(
  page: import('@playwright/test').Page,
  pickup: string,
  destination: string,
) {
  const response = page.waitForResponse(
    (r) => r.url().includes('/api/v1/rides/search') && r.request().method() === 'POST',
  )

  await enterAddress(page, 'pickup', pickup)
  await enterAddress(page, 'destination', destination)

  await page.getByRole('button', { name: /search available taxis/i }).click()

  const search = await response
  expect(search.status(), 'the search itself must succeed').toBe(201)

  return search.json()
}

test.describe('Booking page state lifetime', () => {
  /*
   * The browser gets its session from localStorage; Playwright's own request
   * context does not, so the direct API calls below carry the header
   * themselves. Without it they answer 401 and the test fails somewhere other
   * than the behaviour it is about.
   */
  let auth: { Authorization: string }

  /*
   * A customer of its own. This test books and cancels a ride, and a seeded
   * account carrying an active ride from a previous run would make every later
   * attempt fail for the wrong reason.
   */
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)
    auth = { Authorization: `Bearer ${tokens.accessToken}` }
  })

  test('F38 · a failed selection does not leave its banner over the next search', async ({
    page,
    guards,
  }) => {
    void guards

    await page.goto('/ride')

    /* ---------------------------------------------- the first search */
    const first = await searchForTaxis(page, 'Rruga e Kavajes', 'Sheshi Skenderbej')
    await expect(offerButtons(page).first()).toBeVisible({ timeout: 20_000 })

    expect(first.taxiOptions?.length, 'the search must return something to choose').toBeGreaterThan(
      0,
    )

    await expect(alerts(page), 'nothing has failed yet').toHaveCount(0)

    /*
     * Take the request through the API, which moves it from SEARCHING to
     * SELECTED. The customer's own click below then hits the same rule a real
     * double submission hits.
     */
    const taken = await page.request.post(`/api/v1/rides/requests/${first.rideRequestId}/select`, {
      headers: auth,
      data: { offerId: first.taxiOptions[0].offerId },
    })
    expect(taken.status(), 'the API selection must succeed').toBe(201)
    const ride = await taken.json()

    /* ------------------------------------- the selection that fails */
    const refused = page.waitForResponse(
      (r) => r.url().includes('/select') && r.request().method() === 'POST',
    )
    await offerButtons(page).first().click()

    /*
     * Refused, without asserting WHICH rule refused it.
     *
     * Two apply once the request has been taken: the request is no longer
     * SEARCHING (400) and the customer now holds an active ride (409). Both
     * are correct and either may be reached first, so pinning the code would
     * make this test fail the next time that order changes — for a reason that
     * has nothing to do with the banner it is about. A 5xx would be a
     * different matter and is excluded.
     */
    const status = (await refused).status()
    expect(status, 'the selection must be refused by a rule, not a crash').toBeGreaterThanOrEqual(
      400,
    )
    expect(status, 'the selection must be refused by a rule, not a crash').toBeLessThan(500)

    /*
     * The positive half. An absence assertion below is worth nothing unless
     * the thing being asserted absent was demonstrably present first.
     */
    await expect(alerts(page), 'the refusal must be shown to the customer').toHaveCount(1)

    /* ------------------------------------------- free to search again */
    const cancelled = await page.request.post(`/api/v1/rides/${ride.rideId}/cancel`, {
      headers: auth,
    })
    expect(cancelled.status(), 'the ride must cancel so a second search is allowed').toBe(200)

    /*
     * The same two addresses as the first search, deliberately.
     *
     * What this test needs from the second search is a NEW ride request, and
     * the server makes one on every search regardless of where it is for. A
     * different pair of streets added nothing and cost reliability: the live
     * geocoder resolved two different Tirana street names to the same point,
     * which the server then refused for having identical pickup and
     * destination — a 400 that had nothing to do with the banner.
     */
    const second = await searchForTaxis(page, 'Rruga e Kavajes', 'Sheshi Skenderbej')
    expect(
      second.rideRequestId,
      'the second search must be a new request, not the refused one',
    ).not.toBe(first.rideRequestId)

    /*
     * Both halves together. Fresh offers on screen prove the search really
     * happened, so the banner's absence means it was cleared rather than that
     * the page never got anywhere.
     */
    await expect(offerButtons(page).first()).toBeVisible({ timeout: 20_000 })
    await expect(
      alerts(page),
      'the banner from the previous request must not survive the new search',
    ).toHaveCount(0)
  })
})
