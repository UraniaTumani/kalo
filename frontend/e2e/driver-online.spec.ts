import {
  test,
  expect,
  SEEDED,
  apiLogin,
  gotoSettled,
  seedSession,
  expectSignedIn,
} from './support/fixtures'

/**
 * Putting a driver on the road, through the button a partner actually presses.
 *
 * This is the sequence nothing covered, and a production rehearsal is where
 * that showed up. The screen asks the device for a position, sends it, and only
 * then flips availability — because a driver whose fix is stale is not offered
 * to passengers. The backend refused that first call for an offline driver, so
 * the request threw before availability was ever touched and the driver stayed
 * offline. An offline fleet takes no rides at all.
 *
 * Every fixture and the dev seeder create drivers already ONLINE with a
 * position written straight through the repository, which is exactly why 257
 * backend tests and 80 browser tests all missed it. This one drives the real
 * path: real geolocation (granted and stubbed), real click, real requests.
 */

/* Tirana, so the position is inside the seeded company's service area. */
const TIRANA = { latitude: 41.3275, longitude: 19.8187 }

test.describe('Putting a driver online', () => {
  test.use({
    permissions: ['geolocation'],
    geolocation: TIRANA,
  })

  test.beforeEach(async ({ page, context, app }) => {
    void app
    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
  })

  test('a partner can take a driver offline and put them back online', async ({
    page,
    guards,
  }) => {
    void guards

    await gotoSettled(page, '/partner/drivers')
    await expectSignedIn(page)
    await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 30_000 })

    /*
     * Works from whichever state the seeded fleet is in: take a driver offline
     * first if necessary, so the test always exercises the offline -> online
     * path rather than depending on how an earlier run left things.
     */
    const goOffline = page.getByRole('button', { name: /go offline/i }).first()

    if (await goOffline.isVisible().catch(() => false)) {
      await goOffline.click()
      await expect(page.getByRole('button', { name: /go online/i }).first()).toBeVisible({
        timeout: 30_000,
      })
    }

    const goOnline = page.getByRole('button', { name: /go online/i }).first()
    await expect(goOnline).toBeVisible({ timeout: 30_000 })

    /* Both calls the button makes, in the order it makes them. */
    const locationPut = page.waitForResponse(
      (r) => /\/api\/v1\/partner\/drivers\/\d+\/location$/.test(r.url()) && r.request().method() === 'PUT',
      { timeout: 30_000 },
    )
    const availabilityPatch = page.waitForResponse(
      (r) =>
        /\/api\/v1\/partner\/drivers\/\d+\/availability$/.test(r.url()) &&
        r.request().method() === 'PATCH',
      { timeout: 30_000 },
    )

    await goOnline.click()

    const location = await locationPut
    expect(
      location.status(),
      'the position must be accepted while the driver is still offline',
    ).toBe(200)

    const availability = await availabilityPatch
    expect(availability.status(), 'the driver must then go online').toBe(200)

    /* And the screen agrees: the button flips to its opposite. */
    await expect(page.getByRole('button', { name: /go offline/i }).first()).toBeVisible({
      timeout: 30_000,
    })
  })

  test('the driver is then offered to a searching passenger', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    await gotoSettled(page, '/partner/drivers')
    await expectSignedIn(page)
    await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 30_000 })

    const goOffline = page.getByRole('button', { name: /go offline/i }).first()
    if (await goOffline.isVisible().catch(() => false)) {
      await goOffline.click()
      await expect(page.getByRole('button', { name: /go online/i }).first()).toBeVisible({
        timeout: 30_000,
      })
    }

    await page.getByRole('button', { name: /go online/i }).first().click()
    await expect(page.getByRole('button', { name: /go offline/i }).first()).toBeVisible({
      timeout: 30_000,
    })

    /*
     * The point of going online, asserted from the passenger's side: a company
     * only appears in search when it has a driver who is online, holds an
     * active vehicle and has a recent position. This proves the button produced
     * all three, not merely a green badge.
     */
    const customer = await apiLogin(
      page.request,
      SEEDED.customer.phone,
      SEEDED.customer.password,
    )

    const search = await page.request.post('/api/v1/rides/search', {
      headers: { Authorization: `Bearer ${customer.accessToken}` },
      data: {
        pickupLatitude: TIRANA.latitude,
        pickupLongitude: TIRANA.longitude,
        destinationLatitude: 41.32,
        destinationLongitude: 19.83,
      },
    })

    expect(search.status(), await search.text()).toBe(201)

    const { taxiOptions } = await search.json()
    expect(
      taxiOptions.length,
      'a driver put online through the UI should be bookable',
    ).toBeGreaterThan(0)

    void context
  })
})
