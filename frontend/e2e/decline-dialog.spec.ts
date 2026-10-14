import {
  test,
  expect,
  SEEDED,
  TIRANA,
  apiLogin,
  seedSession,
  registerCustomer,
  RENDER_TIMEOUT,
} from './support/fixtures'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * The dispatcher's decline dialog, on both outcomes.
 *
 * F24: `onConfirm={() => decline.mutate()}` had no handler at all, so the
 * dialog stayed open over a ride that was already declined, with its confirm
 * button re-enabling as soon as `isPending` cleared. That is what invited the
 * repeat clicks behind the original observation — three decline requests for
 * one ride. The backend refused the repeats correctly; the dialog was the part
 * asking for them.
 *
 * Nothing is mocked. The refusal is produced by declining the ride through the
 * real partner API while the dialog is open, which is the same thing a second
 * dispatcher in another tab would do.
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

const dialog = (page: Page) => page.getByRole('dialog')

/**
 * Opens the company's whole week.
 *
 * Defensive, not decorative: availability.spec.ts rewrites this company's
 * operating hours, and a spec depending on whichever week another file left
 * behind would fail on the day of the week rather than on its own subject.
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

async function companyIdOf(request: APIRequestContext, tokens: Tokens): Promise<number> {
  const response = await request.get('/api/v1/partner/me', { headers: auth(tokens) })
  expect(response.ok(), `partner/me: ${await response.text()}`).toBeTruthy()
  return Number((await response.json()).companyId)
}

/** Books a ride against the partner's own company, so they can decline it. */
async function bookAgainstThePartner(request: APIRequestContext, partnerTokens: Tokens) {
  const surname = `Decline${Date.now() % 1_000_000}`
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
  expect(
    offer,
    `the partner's company was not offered: ${JSON.stringify(body.taxiOptions)}`,
  ).toBeTruthy()

  const selected = await request.post(`/api/v1/rides/requests/${body.rideRequestId}/select`, {
    headers: auth(customerTokens),
    data: { offerId: offer.offerId },
  })
  expect(selected.ok(), await selected.text()).toBeTruthy()

  return { rideId: Number((await selected.json()).rideId), surname }
}

test.describe('Partner decline dialog', () => {
  test.setTimeout(180_000)

  let partnerTokens: Tokens

  test.beforeEach(async ({ page, context, app }) => {
    void app
    partnerTokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await openTheWeek(page.request, partnerTokens)
    await seedSession(context, partnerTokens)
  })

  /** Opens the queue and the decline dialog for a given ride. */
  async function openDeclineDialog(page: Page, rideId: number, surname: string) {
    await page.goto('/partner/rides')

    /*
     * The queue is a list of collapsed rows; Accept and Decline live in the
     * detail card that Manage opens. The row is found by its customer's
     * surname, which is unique per booking, because several rides can be in
     * the queue at once and "the first Manage button" would be whichever the
     * sort happened to put on top.
     */
    const row = page.getByText(surname, { exact: false })
    await expect(row, 'the new request must be in the queue').toBeVisible({
      timeout: RENDER_TIMEOUT,
    })

    await page
      .getByTestId(`partner-ride-${rideId}`)
      .getByRole('button', { name: 'Manage' })
      .click()

    /* Proof the right row was opened, not merely that something opened. */
    await expect(
      page.getByText(`Ride #${rideId}`),
      'Manage must open the detail for this ride',
    ).toBeVisible({ timeout: RENDER_TIMEOUT })

    await page.getByRole('button', { name: 'Decline', exact: true }).click()
    await expect(dialog(page), 'the dialog must open first').toBeVisible({
      timeout: RENDER_TIMEOUT,
    })
  }

  test('F24 · a successful decline closes the dialog', async ({ page, guards }) => {
    void guards

    const { rideId, surname } = await bookAgainstThePartner(page.request, partnerTokens)
    await openDeclineDialog(page, rideId, surname)

    const declined = page.waitForResponse(
      (r) => /\/api\/v1\/partner\/rides\/\d+\/decline$/.test(r.url()) && r.request().method() === 'POST',
    )
    await dialog(page).getByRole('button', { name: 'Decline ride' }).click()
    expect((await declined).status(), 'the decline must succeed').toBe(200)

    await expect(dialog(page), 'the dialog must close once the ride is declined').toHaveCount(0)
  })

  test('F24 · a refused decline closes the dialog and shows why', async ({ page, guards }) => {
    void guards

    const { rideId, surname } = await bookAgainstThePartner(page.request, partnerTokens)
    await openDeclineDialog(page, rideId, surname)

    /*
     * Declined elsewhere while the dialog sits open — a second dispatcher, in
     * practice. The dialog is local state and survives this.
     */
    const elsewhere = await page.request.post(`/api/v1/partner/rides/${rideId}/decline`, {
      headers: auth(partnerTokens),
    })
    expect(elsewhere.ok(), `the first decline must succeed: ${await elsewhere.text()}`).toBeTruthy()

    const refused = page.waitForResponse(
      (r) => /\/api\/v1\/partner\/rides\/\d+\/decline$/.test(r.url()) && r.request().method() === 'POST',
    )
    await dialog(page).getByRole('button', { name: 'Decline ride' }).click()

    const status = (await refused).status()
    expect(status, 'the second decline must be refused by a rule').toBeGreaterThanOrEqual(400)
    expect(status, 'the second decline must be refused by a rule').toBeLessThan(500)

    /*
     * Both halves. The dialog gone is the bug; the reason still on screen is
     * what makes closing it safe, and is also why this fix does NOT refetch
     * the queue on failure — doing so would drop the declined ride out of the
     * ACTIVE filter and unmount the card carrying this very message.
     */
    await expect(dialog(page), 'the dialog must not survive a refused decline').toHaveCount(0)
    await expect(
      page.getByRole('alert').first(),
      'the refusal must be visible now that the dialog is out of the way',
    ).toBeVisible()
  })
})
