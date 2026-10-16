import { expect } from '@playwright/test'
import { test, SEEDED, apiLogin, registerCustomer, seedSession, RENDER_TIMEOUT } from './support/fixtures'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * F25: a declined booking must not send the passenger back to two blank
 * address fields. This exercises the real UI selection and the real partner
 * decline API; neither the search nor the ride transition is mocked.
 */

async function enterAddress(page: Page, label: 'pickup' | 'destination', address: string) {
  await page.getByLabel(new RegExp('^' + label, 'i')).fill(address)
  await page.getByRole('option').first().click()
}

async function partnerIdentity(
  request: APIRequestContext,
  phone: string,
  password: string,
) {
  const tokens = await apiLogin(request, phone, password)
  const headers = { Authorization: `Bearer ${tokens.accessToken}` }
  const response = await request.get('/api/v1/partner/me', { headers })
  expect(response.status()).toBe(200)
  return { companyId: Number((await response.json()).companyId), headers }
}

test('F25 · declining a ride preserves both addresses and automatically searches again', async ({
  page,
  context,
  app,
  guards,
}) => {
  void app
  void guards
  test.setTimeout(180_000)

  const account = await registerCustomer(page.request)
  const tokens = await apiLogin(page.request, account.phone, account.password)
  await seedSession(context, tokens)

  const partners = await Promise.all(
    [SEEDED.partner, SEEDED.partnerB].map((partner) =>
      partnerIdentity(page.request, partner.phone, partner.password),
    ),
  )

  await page.goto('/ride')
  await enterAddress(page, 'pickup', 'Rruga e Kavajes')
  await enterAddress(page, 'destination', 'Sheshi Skenderbej')

  const firstResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/rides/search') && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: /search available taxis/i }).click()
  const first = await firstResponse
  expect(first.status(), await first.text()).toBe(201)
  const found = await first.json()

  const index = (found.taxiOptions ?? []).findIndex(
    (option: { companyId: number }) =>
      partners.some((partner) => partner.companyId === option.companyId),
  )
  expect(index, 'a seeded partner must be available to decline the ride').toBeGreaterThanOrEqual(0)

  const offer = found.taxiOptions[index]
  const partner = partners.find((entry) => entry.companyId === offer.companyId)!

  const selectedResponse = page.waitForResponse(
    (response) =>
      /\/api\/v1\/rides\/requests\/\d+\/select$/.test(response.url()) &&
      response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: /choose company/i }).nth(index).click()
  const selected = await selectedResponse
  expect(selected.status(), await selected.text()).toBe(201)
  const ride = await selected.json()

  await expect(page).toHaveURL(/\/ride\/current$/, { timeout: RENDER_TIMEOUT })

  // Decline as the very company the passenger chose.
  const declined = await page.request.post(
    `/api/v1/partner/rides/${ride.rideId}/decline`,
    { headers: partner.headers },
  )
  expect(declined.status(), await declined.text()).toBe(200)

  // The route must survive a browser reload while the declined ride is shown.
  await page.reload()
  const retry = page.getByRole('link', { name: /choose another company/i })
  await expect(retry).toBeVisible({ timeout: RENDER_TIMEOUT })

  // Do not click Search: the retry action itself must issue a NEW search.
  const secondResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/rides/search') && response.request().method() === 'POST',
  )
  await retry.click()
  await expect(page).toHaveURL(/\/ride$/)

  await expect(page.getByRole('button', { name: /clear pickup/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /clear destination/i })).toBeVisible()

  const second = await secondResponse
  expect(second.status(), await second.text()).toBe(201)
  const fresh = await second.json()
  expect(fresh.rideRequestId, 'the declined request must not be reused').not.toBe(
    found.rideRequestId,
  )

  if (fresh.taxiOptions.length > 0) {
    await expect(page.getByRole('button', { name: /choose company/i }).first()).toBeVisible({
      timeout: RENDER_TIMEOUT,
    })
  } else {
    // A real availability change may leave no drivers. That is still a valid
    // fresh search, and the screen must explain it rather than show blank fields.
    await expect(page.getByText(/no taxis|no companies|none available/i).first()).toBeVisible()
  }
})
