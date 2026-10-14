import { test, expect, SEEDED, apiLogin, gotoSettled, seedSession } from './support/fixtures'

/**
 * F29: the dashboard must show the server's current company-wide blockers,
 * not an optimistic guess from paginated driver counts.
 *
 * Uses the real backend and seeded partner. The state may vary depending on
 * when the seed's GPS positions were last updated; assert against the server's
 * answer instead of hard-coding an expected readiness state.
 */
test('partner sees server-derived readiness with one action per blocker', async ({
  page,
  context,
  app,
  guards,
}) => {
  void app
  void guards

  const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
  await seedSession(context, tokens)

  const response = await page.request.get('/api/v1/partner/readiness', {
    headers: { Authorization: `Bearer ${tokens.accessToken}` },
  })
  expect(response.status(), await response.text()).toBe(200)

  const readiness = (await response.json()) as {
    readyNow: boolean
    checkedAt: string
    blockers: string[]
  }

  expect(readiness.checkedAt).toBeTruthy()
  expect(readiness.readyNow).toBe(readiness.blockers.length === 0)

  await gotoSettled(page, '/partner')

  const panel = page.getByTestId('partner-readiness')
  await expect(panel).toBeVisible({ timeout: 30_000 })

  if (readiness.readyNow) {
    await expect(panel).toContainText('Company ready for bookings')
    await expect(panel.getByRole('link', { name: 'Review' })).toHaveCount(0)
  } else {
    await expect(panel).toContainText('Company not ready for bookings')
    await expect(panel.getByRole('link', { name: 'Review' })).toHaveCount(
      readiness.blockers.length,
    )
  }
})
