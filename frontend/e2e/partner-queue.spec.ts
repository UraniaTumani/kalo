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
 * The dispatcher's queue, as a dispatcher sees it (F41).
 *
 * The queue used to open on ALL, where the rides a company can act on sit among
 * every ride it has ever taken. Narrowing the default to REQUESTED was proposed
 * and rejected, because a ride disappears the instant it is accepted — which is
 * exactly when the dispatcher becomes responsible for driving it through
 * arriving, arrived, started and completed, all from this screen.
 *
 * ACTIVE is now the default. The middle test here is the one that matters: it
 * is written so that putting the default back to REQUESTED makes it fail.
 */

type Tokens = { accessToken: string; refreshToken: string }

const auth = (t: Tokens) => ({ Authorization: `Bearer ${t.accessToken}` })

const WEEK = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const

const filter = (page: Page) => page.getByRole('combobox').first()

/**
 * Opens the company's whole week.
 *
 * Defensive rather than decorative: availability.spec.ts rewrites this
 * company's operating hours, and a spec that depended on whichever week another
 * file happened to leave behind would fail on the day of the week rather than on
 * its own subject.
 */
async function openTheWeek(request: APIRequestContext, tokens: Tokens) {
  const response = await request.put('/api/v1/partner/availability-settings/operating-hours', {
    headers: auth(tokens),
    data: {
      hours: WEEK.map((dayOfWeek) => ({
        dayOfWeek,
        openTime: '00:00',
        closeTime: '00:00',
        closed: false,
      })),
    },
  })

  expect(response.ok(), `could not open the week: ${await response.text()}`).toBeTruthy()
}

/** Books a ride against the partner's company and returns its customer's surname. */
async function bookRide(
  request: APIRequestContext,
  partnerTokens: Tokens,
): Promise<{ rideId: number; surname: string }> {
  const surname = `Queue${Date.now() % 1_000_000}`

  const customer = await registerCustomer(request, { lastName: surname })
  const customerTokens = await apiLogin(request, customer.phone, customer.password)

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

  const body = await search.json()
  const companyId = await companyIdOf(request, partnerTokens)

  const offer = (body.taxiOptions ?? []).find(
    (option: { companyId: number }) => Number(option.companyId) === companyId,
  )

  expect(offer, `the partner's company was not offered: ${JSON.stringify(body.taxiOptions)}`)
    .toBeTruthy()

  const selected = await request.post(
    `/api/v1/rides/requests/${body.rideRequestId}/select`,
    { headers: auth(customerTokens), data: { offerId: offer.offerId } },
  )
  expect(selected.ok(), await selected.text()).toBeTruthy()

  return { rideId: Number((await selected.json()).rideId), surname }
}

async function acceptRide(request: APIRequestContext, tokens: Tokens, rideId: number) {
  const drivers = await request.get(
    '/api/v1/partner/drivers?availabilityStatus=ONLINE&size=50',
    { headers: auth(tokens) },
  )

  const free = (await drivers.json()).content as Array<{ id: number }>

  expect(free.length, 'the seeded fleet should have a free driver').toBeGreaterThan(0)

  const accept = await request.post(`/api/v1/partner/rides/${rideId}/accept`, {
    headers: auth(tokens),
    data: { driverId: free[0].id },
  })
  expect(accept.ok(), `accept: ${await accept.text()}`).toBeTruthy()
}

test.describe('F41 · the dispatcher queue defaults to work in hand', () => {
  test.setTimeout(180_000)

  test('the queue opens on active rides, not everything', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
    await openTheWeek(page.request, tokens)

    const { surname } = await bookRide(page.request, tokens)

    await gotoSettled(page, '/partner/rides')
    await expectSignedIn(page)

    await expect(filter(page))
      .toHaveValue('ACTIVE', { timeout: 30_000 })

    /* The positive anchor: the new request is on the opening screen. */
    await expect(page.getByText(surname, { exact: false })).toBeVisible({ timeout: 30_000 })
  })

  test('an accepted ride stays on the default queue', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
    await openTheWeek(page.request, tokens)

    const { rideId, surname } = await bookRide(page.request, tokens)

    await gotoSettled(page, '/partner/rides')
    await expectSignedIn(page)

    await expect(page.getByText(surname, { exact: false })).toBeVisible({ timeout: 30_000 })

    await acceptRide(page.request, tokens, rideId)

    /*
     * Reloaded on purpose, and this is the whole substance of the test.
     *
     * Asserting straight after the accept proves nothing: the row is already on
     * screen from the fetch before it, so toBeVisible passes on the stale render
     * without ever re-reading the queue. A mutation check caught exactly that —
     * with the default put back to REQUESTED the row stayed visible and only the
     * select-value assertion below failed, which is the weak half.
     *
     * A reload is what a dispatcher does anyway, and it makes the question
     * unambiguous: with the default filter, after the ride has been accepted,
     * is it still in the queue? Under REQUESTED it is not.
     */
    await gotoSettled(page, '/partner/rides')
    await expectSignedIn(page)

    await expect(page.getByText(surname, { exact: false }))
      .toBeVisible({ timeout: 30_000 })

    /*
     * Secondary. The row surviving is the behaviour; the select's value is only
     * the mechanism, and on its own it would pass for a default of ACTIVE that
     * filtered to the wrong set.
     */
    await expect(filter(page)).toHaveValue('ACTIVE')
  })

  test('a completed ride leaves the active queue but stays in all', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
    await openTheWeek(page.request, tokens)

    const { rideId, surname } = await bookRide(page.request, tokens)
    await acceptRide(page.request, tokens, rideId)

    for (const step of ['driver-arriving', 'driver-arrived', 'start']) {
      const response = await page.request.post(`/api/v1/partner/rides/${rideId}/${step}`, {
        headers: auth(tokens),
      })
      expect(response.ok(), `${step}: ${await response.text()}`).toBeTruthy()
    }

    const complete = await page.request.post(`/api/v1/partner/rides/${rideId}/complete`, {
      headers: auth(tokens),
      data: { finalAmount: 850.0 },
    })
    expect(complete.ok(), await complete.text()).toBeTruthy()

    await gotoSettled(page, '/partner/rides')
    await expectSignedIn(page)

    /*
     * Paired on purpose. "Not in ACTIVE" is an absence assertion that an empty
     * or broken page would satisfy, so the same row must then be found under
     * ALL in the same session — which proves the page renders and that the
     * ride still exists.
     */
    await expect(filter(page)).toHaveValue('ACTIVE', { timeout: 30_000 })
    await expect(page.getByText(surname, { exact: false })).toBeHidden()

    await filter(page).selectOption('ALL')

    await expect(page.getByText(surname, { exact: false })).toBeVisible({ timeout: 30_000 })
  })

  test('the individual status filters still work', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
    await openTheWeek(page.request, tokens)

    const { surname } = await bookRide(page.request, tokens)

    await gotoSettled(page, '/partner/rides')
    await expectSignedIn(page)

    await filter(page).selectOption('REQUESTED')
    await expect(page.getByText(surname, { exact: false })).toBeVisible({ timeout: 30_000 })

    /* A status the ride is not in: the row goes, the page stays. */
    await filter(page).selectOption('COMPLETED')
    await expect(page.getByText(surname, { exact: false })).toBeHidden()

    await filter(page).selectOption('REQUESTED')
    await expect(page.getByText(surname, { exact: false })).toBeVisible({ timeout: 30_000 })
  })
})

/* ----------------------------------------------------------------- helpers */

async function companyIdOf(request: APIRequestContext, tokens: Tokens): Promise<number> {
  const response = await request.get('/api/v1/partner/me', { headers: auth(tokens) })
  expect(response.ok(), await response.text()).toBeTruthy()

  const body = await response.json()
  return Number(body.companyId ?? body.id)
}
