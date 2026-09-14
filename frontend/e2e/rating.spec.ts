import {
  test,
  expect,
  SEEDED,
  TIRANA,
  apiLogin,
  seedSession,
  registerCustomer,
  expectSignedIn,
} from './support/fixtures'
import type { APIRequestContext } from '@playwright/test'

/**
 * Rating, and what is still true after a reload.
 *
 * The bug this covers: the ride API returned no rating state, so history
 * offered a Rate button on every completed ride forever. A passenger who had
 * already rated could only find out by filling the form in and having it
 * refused — and the screen then reported that refusal as "Your rating has been
 * recorded", which was the opposite of what happened.
 *
 * A real completed ride is driven through the API here rather than the UI: the
 * booking flow across two roles is covered by customer.spec.ts and by nine
 * backend tests, and repeating it through the browser would make this file
 * about booking instead of about rating.
 */

type Tokens = { accessToken: string; refreshToken: string }

function auth(tokens: Tokens) {
  return { Authorization: `Bearer ${tokens.accessToken}` }
}

/**
 * Books, accepts, drives and completes one ride, and hands back its id.
 *
 * The customer is registered for this test so nothing depends on what an
 * earlier run left in the seeded account's history — and so the ride under
 * test is always the newest row, which is what history shows first.
 */
async function completedRide(request: APIRequestContext) {
  const customer = await registerCustomer(request)
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

  /* Whichever driver the offer was built from is the one free to take it. */
  const drivers = await request.get('/api/v1/partner/drivers?availabilityStatus=ONLINE&size=50', {
    headers: auth(partnerTokens),
  })
  const driverId = (await drivers.json()).content[0].id

  const accept = await request.post(`/api/v1/partner/rides/${rideId}/accept`, {
    headers: auth(partnerTokens),
    data: { driverId },
  })
  expect(accept.ok(), await accept.text()).toBeTruthy()

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

  return { rideId, customer, customerTokens, partnerTokens }
}

test.describe('Rating a ride', () => {
  test('a rating survives a reload and history stops offering to rate again', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const { customerTokens } = await completedRide(page.request)
    await seedSession(context, customerTokens)

    await page.goto('/ride/history')
    await expectSignedIn(page)

    const rate = page.getByRole('button', { name: /^rate$/i }).first()
    await expect(rate).toBeVisible({ timeout: 30_000 })

    await rate.click()

    await expect(page.getByText(/rate this ride/i)).toBeVisible()

    /* Both scores are required before the form will submit. */
    await page.getByRole('radiogroup', { name: /driver/i }).getByRole('radio').nth(4).click()
    await page.getByRole('radiogroup', { name: /company/i }).getByRole('radio').nth(3).click()

    await page.getByRole('button', { name: /submit rating/i }).click()

    await expect(page.getByText(/thanks for the feedback/i)).toBeVisible()

    /*
     * The row must update without a reload — the mutation invalidates the
     * history query — and then survive one, which is the actual bug.
     */
    await expect(page.getByRole('button', { name: /^rate$/i })).toHaveCount(0)

    await page.reload()
    await expectSignedIn(page)

    await expect(page.getByRole('button', { name: /^rate$/i })).toHaveCount(0, {
      timeout: 30_000,
    })
    await expect(page.getByText(/5 of 5/i).first()).toBeVisible()
  })

  test('the rating is in the database, not just on the screen', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    const { rideId, customerTokens } = await completedRide(page.request)

    const rated = await page.request.post(`/api/v1/rides/${rideId}/rating`, {
      headers: auth(customerTokens),
      data: { driverRating: 5, companyRating: 4, comment: 'Quick and friendly' },
    })
    expect(rated.status()).toBe(201)

    /* Read back through a fresh request, as a reload would. */
    const history = await page.request.get('/api/v1/rides/history', {
      headers: auth(customerTokens),
    })

    const first = (await history.json()).content[0]

    expect(first.rideId).toBe(rideId)
    expect(first.rated).toBe(true)
    expect(first.driverRating).toBe(5)
    expect(first.companyRating).toBe(4)
  })

  test('a second rating is refused and says so honestly', async ({ page, app, guards }) => {
    void app
    void guards

    const { rideId, customerTokens } = await completedRide(page.request)

    const first = await page.request.post(`/api/v1/rides/${rideId}/rating`, {
      headers: auth(customerTokens),
      data: { driverRating: 5, companyRating: 4 },
    })
    expect(first.status()).toBe(201)

    const second = await page.request.post(`/api/v1/rides/${rideId}/rating`, {
      headers: auth(customerTokens),
      data: { driverRating: 1, companyRating: 1 },
    })
    expect(second.status(), 'a duplicate rating must be refused').toBe(409)

    /* And the refusal must not have overwritten the first. */
    const history = await page.request.get('/api/v1/rides/history', {
      headers: auth(customerTokens),
    })
    expect((await history.json()).content[0].driverRating).toBe(5)
  })

  test('the company is notified, and the notification persists', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    const { rideId, customerTokens, partnerTokens } = await completedRide(page.request)

    const before = await page.request.get('/api/v1/partner/notifications?size=100', {
      headers: auth(partnerTokens),
    })
    const countBefore = (await before.json()).totalElements

    await page.request.post(`/api/v1/rides/${rideId}/rating`, {
      headers: auth(customerTokens),
      data: { driverRating: 5, companyRating: 4 },
    })

    const after = await page.request.get('/api/v1/partner/notifications?size=100', {
      headers: auth(partnerTokens),
    })
    const body = await after.json()

    expect(body.totalElements, 'the company should have one more notification').toBe(
      countBefore + 1,
    )

    const notification = body.content.find(
      (row: { rideId: number }) => row.rideId === rideId,
    )

    expect(notification, 'a notification for this ride should exist').toBeTruthy()
    expect(notification.type).toBe('RIDE_RATED')
    expect(notification.read).toBe(false)
    expect(notification.message).toContain('5')

    /* Marking it read sticks, which is what makes it a record and not a flash. */
    const marked = await page.request.post(
      `/api/v1/partner/notifications/${notification.id}/read`,
      { headers: auth(partnerTokens) },
    )
    expect(marked.ok()).toBeTruthy()

    const reread = await page.request.get('/api/v1/partner/notifications?size=100', {
      headers: auth(partnerTokens),
    })
    const rereadRow = (await reread.json()).content.find(
      (row: { id: number }) => row.id === notification.id,
    )

    expect(rereadRow.read).toBe(true)
  })

  test("a customer cannot read a company's notifications", async ({ page, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)

    const response = await page.request.get('/api/v1/partner/notifications', {
      headers: auth(tokens),
    })

    expect(response.status()).toBe(403)
  })
})
