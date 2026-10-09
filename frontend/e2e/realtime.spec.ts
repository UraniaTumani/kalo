import {
  test,
  expect,
  SEEDED,
  TIRANA,
  gotoSettled,
  apiLogin,
  seedSession,
  registerCustomer,
  expectSignedIn,
} from './support/fixtures'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * F14 — do ride views update on their own, and do they catch up after the tab
 * has been in the background?
 *
 * Written BEFORE the fix, deliberately, because the manual evidence could not
 * separate two different faults and the distinction decides how far the fix has
 * to go:
 *
 *   foreground  — the tab is visible and the server state changes. If this
 *                 fails, polling itself is broken and no amount of
 *                 focus-handling configuration would help.
 *   background  — the tab was hidden while the state changed. If only this
 *                 fails, the fault is confined to focus handling.
 *
 * The manual runs always had both conditions mixed together, and a real phone
 * made it worse by suspending the page. These three tests hold one variable
 * still at a time.
 *
 * Why the timings below are what they are. On returning to a hidden tab,
 * TanStack rebuilds the refetch timer rather than resuming a paused one, so the
 * first SCHEDULED poll after a reveal lands a full interval later — 5s for the
 * customer's ride, 10s for the partner's queue. An assertion that the screen is
 * current within 4s of the reveal therefore cannot be satisfied by a scheduled
 * poll; only a refetch triggered BY the reveal can satisfy it. That is the
 * behaviour under test, and it is why 4s is a threshold rather than a guess.
 */

type Tokens = { accessToken: string; refreshToken: string }

function auth(tokens: Tokens) {
  return { Authorization: `Bearer ${tokens.accessToken}` }
}

/**
 * Books a ride and leaves it REQUESTED — the state a dispatcher acts on, and
 * the one every test here starts from.
 *
 * The customer is registered per test so the queue row this test looks for
 * cannot be confused with one an earlier run left behind.
 */
async function requestedRide(request: APIRequestContext, lastName: string) {
  const customer = await registerCustomer(request, { lastName })
  const customerTokens = await apiLogin(request, customer.phone, customer.password)
  const partnerTokens = await apiLogin(request, SEEDED.partner.phone, SEEDED.partner.password)

  const search = await request.post('/api/v1/rides/search', {
    headers: auth(customerTokens),
    data: {
      pickupLatitude: TIRANA.lat,
      pickupLongitude: TIRANA.lng,
      destinationLatitude: 41.32,
      destinationLongitude: 19.83,
    },
  })
  expect(search.status(), await search.text()).toBe(201)

  const { rideRequestId, taxiOptions } = await search.json()
  expect(taxiOptions.length, 'the seeded company should be bookable').toBeGreaterThan(0)

  const selected = await request.post(`/api/v1/rides/requests/${rideRequestId}/select`, {
    headers: auth(customerTokens),
    data: { offerId: taxiOptions[0].offerId },
  })
  expect(selected.ok(), await selected.text()).toBeTruthy()

  const { rideId } = await selected.json()

  return { rideId, customer, customerTokens, partnerTokens }
}

/** Accepts a REQUESTED ride as the partner, which is what the views must notice. */
async function acceptRide(request: APIRequestContext, partnerTokens: Tokens, rideId: number) {
  const drivers = await request.get('/api/v1/partner/drivers?availabilityStatus=ONLINE&size=50', {
    headers: auth(partnerTokens),
  })
  const free = (await drivers.json()).content as Array<{ id: number }>

  /*
   * Named rather than left to throw on content[0].
   *
   * Accepting a ride puts its driver into BUSY, so a run against a database
   * that still holds an earlier run's rides can exhaust the seeded fleet — and
   * the symptom was a TypeError on `undefined.id`, which says nothing about the
   * cause. Each test here releases its driver again (see completeRide), so this
   * should not trigger; if it does, the message is the diagnosis.
   */
  expect(
    free.length,
    'no ONLINE driver is free — the seeded fleet is exhausted, which means an earlier ride was left active',
  ).toBeGreaterThan(0)

  const accept = await request.post(`/api/v1/partner/rides/${rideId}/accept`, {
    headers: auth(partnerTokens),
    data: { driverId: free[0].id },
  })
  expect(accept.ok(), await accept.text()).toBeTruthy()
}

/**
 * Drives an accepted ride to COMPLETED, which returns its driver to ONLINE.
 *
 * Without this each test here would permanently consume one of the seeded
 * drivers, so the file would depend on the fleet being larger than the number
 * of tests in it and would fail differently the moment either changed. The
 * completion path itself is verified elsewhere (P30, P31); it is used here only
 * to put the world back.
 */
async function completeRide(request: APIRequestContext, partnerTokens: Tokens, rideId: number) {
  for (const step of ['driver-arriving', 'driver-arrived', 'start']) {
    const response = await request.post(`/api/v1/partner/rides/${rideId}/${step}`, {
      headers: auth(partnerTokens),
    })
    expect(response.ok(), `${step}: ${await response.text()}`).toBeTruthy()
  }

  const complete = await request.post(`/api/v1/partner/rides/${rideId}/complete`, {
    headers: auth(partnerTokens),
    data: { finalAmount: 850.0 },
  })
  expect(complete.ok(), await complete.text()).toBeTruthy()
}

/**
 * Hides or reveals the tab the way switching away from it does.
 *
 * TanStack's focus manager listens for `visibilitychange` and reads
 * `document.visibilityState`, so overriding the property and firing the event is
 * exactly what a real tab switch looks like from inside the page. Playwright
 * cannot background a tab directly — a second page in the same context does not
 * change the first one's visibilityState.
 */
async function setTabHidden(page: Page, hidden: boolean) {
  await page.evaluate((isHidden) => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => (isHidden ? 'hidden' : 'visible'),
    })
    Object.defineProperty(document, 'hidden', {
      configurable: true,
      get: () => isHidden,
    })
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    window.dispatchEvent(new Event('visibilitychange'))
  }, hidden)
}

/**
 * Timestamps of every GET the page makes to one endpoint.
 *
 * Counting requests rather than only watching the screen is what makes these
 * tests say something precise. A screen that is correct after a reveal proves
 * nothing on its own: it may have been kept current by polling that never
 * paused, which would be a different behaviour with a different cost. The
 * counts separate "caught up on return" from "never stopped asking", and they
 * are also the guard against a fix that keeps every hidden tab talking to the
 * API — which at a hundred companies is the difference between a quiet
 * deployment and a noisy one.
 */
function trackGets(page: Page, fragment: string): number[] {
  const at: number[] = []

  page.on('request', (request) => {
    if (request.method() !== 'GET') return
    if (!request.url().includes(fragment)) return
    at.push(Date.now())
  })

  return at
}

const since = (times: number[], from: number) => times.filter((t) => t >= from).length

test.describe('F14 · ride views update without a manual refresh', () => {
  /*
   * Each test books a ride through the API before it touches the browser, then
   * waits on real poll intervals. The suite's default budget is not enough for
   * that once the shell render is included.
   */
  test.setTimeout(180_000)

  test('foreground · the customer sees the driver assigned while the tab stays visible', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const { rideId, customerTokens, partnerTokens } = await requestedRide(
      page.request,
      'Foreground',
    )
    await seedSession(context, customerTokens)

    const currentGets = trackGets(page, '/api/v1/rides/current')

    await gotoSettled(page, '/ride/current')
    await expectSignedIn(page)

    /*
     * The waiting banner, not the status badge.
     *
     * The badge's text cannot be used here: the progress rail below it renders
     * EVERY stage name unconditionally — "Requested", "Driver assigned" and the
     * rest are all in the DOM from first paint, so getByText('Driver assigned')
     * matches a rail label and passes before anything has happened. An earlier
     * version of this test did exactly that and "passed" in 2.3 seconds without
     * a single poll having occurred.
     *
     * This banner renders only while the ride is REQUESTED, so its presence and
     * then its absence track the real status.
     */
    const waiting = page.getByText('Waiting for the company to respond')
    await expect(waiting).toBeVisible({ timeout: 30_000 })

    const acceptedAt = Date.now()
    await acceptRide(page.request, partnerTokens, rideId)

    /*
     * Generous on purpose. This test asks whether the update arrives AT ALL
     * while the tab is visible, not how quickly — three poll periods is enough
     * to answer that without turning a slow machine into a failure.
     */
    await expect(waiting).toBeHidden({ timeout: 15_000 })

    /* The screen being right is not enough: it must be right because the page
     * asked again, which is the mechanism this whole finding is about. */
    const polls = since(currentGets, acceptedAt)
    expect(
      polls,
      `a visible tab should keep polling the active ride — polls after accept: ${polls}, total current-ride GETs observed: ${currentGets.length}. A total of 0 means the request tracker matched nothing, not that the page stopped polling.`,
    ).toBeGreaterThan(0)

    await completeRide(page.request, partnerTokens, rideId)
  })

  test('background · the customer catches up on returning to the tab', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const { rideId, customerTokens, partnerTokens } = await requestedRide(
      page.request,
      'Background',
    )
    await seedSession(context, customerTokens)

    const currentGets = trackGets(page, '/api/v1/rides/current')

    await gotoSettled(page, '/ride/current')
    await expectSignedIn(page)
    /* The REQUESTED-only banner, for the reason set out in the test above. */
    const waiting = page.getByText('Waiting for the company to respond')
    await expect(waiting).toBeVisible({ timeout: 30_000 })

    /*
     * Proves the tracker works BEFORE anything depends on it reading zero.
     *
     * Without this, the "quiet while hidden" assertion below would be satisfied
     * just as well by instrumentation that never matched a single request — an
     * absence assertion passing hollow, which is the exact failure mode this
     * suite is known for elsewhere.
     */
    expect(
      currentGets.length,
      'the request tracker saw no requests at all, so it cannot be trusted to show that a hidden tab is quiet',
    ).toBeGreaterThan(0)

    const hiddenAt = Date.now()
    await setTabHidden(page, true)

    /* The state changes while nobody is looking at this tab. */
    await acceptRide(page.request, partnerTokens, rideId)

    /* Longer than both the 5s poll and the 10s staleTime, so neither can be
     * credited with the catch-up that follows. */
    await page.waitForTimeout(12_000)

    const whileHidden = since(currentGets, hiddenAt)

    await setTabHidden(page, false)

    await expect(waiting).toBeHidden({ timeout: 4_000 })

    /*
     * Asserted AFTER the screen, because it is the second half of the same
     * claim: the page caught up because returning made it ask, not because it
     * had been asking all along behind the user's back.
     */
    expect(
      whileHidden,
      `the page made ${whileHidden} request(s) while the tab was hidden; a hidden tab should be quiet`,
    ).toBe(0)

    await completeRide(page.request, partnerTokens, rideId)
  })

  test('background · the partner queue shows a ride that arrived while the tab was hidden', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const partnerTokens = await apiLogin(
      page.request,
      SEEDED.partner.phone,
      SEEDED.partner.password,
    )
    await seedSession(context, partnerTokens)

    const queueGets = trackGets(page, '/api/v1/partner/rides')

    await gotoSettled(page, '/partner/rides')
    await expectSignedIn(page)

    const hiddenAt = Date.now()
    await setTabHidden(page, true)

    /* A passenger books while the dispatcher is on another tab. This is the
     * exact sequence that lost ride #33 during manual testing. */
    const lastName = `Hidden${Date.now() % 100_000}`
    await requestedRide(page.request, lastName)

    await page.waitForTimeout(12_000)

    const whileHidden = since(queueGets, hiddenAt)

    await setTabHidden(page, false)

    await expect(page.getByText(lastName, { exact: false })).toBeVisible({ timeout: 4_000 })

    expect(
      whileHidden,
      `the queue made ${whileHidden} request(s) while the tab was hidden; a hidden tab should be quiet`,
    ).toBe(0)
  })
})
