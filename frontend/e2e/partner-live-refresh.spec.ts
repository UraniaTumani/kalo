import { test, expect, SEEDED, apiLogin, seedSession } from './support/fixtures'

/**
 * F23: simulate server-side arrivals while the partner keeps the page open.
 * Only login uses the isolated E2E backend; notification and queue responses
 * are mocked. No booking or database writes are performed by these tests.
 */

const pageOf = <T>(content: T[], size = 20, totalElements = content.length) => ({
  content,
  totalElements,
  totalPages: totalElements ? Math.ceil(totalElements / size) : 0,
  number: 0,
  size,
  first: true,
  last: true,
  empty: content.length === 0,
})

test.describe('F23 · partner booking data updates without F5', () => {
  test.setTimeout(90_000)

  test('new booking appears in the inbox and unread badge while the page stays mounted', async ({
    page, context, guards,
  }) => {
    void guards
    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    let bookingArrived = false
    let inboxGets = 0
    let unreadGets = 0

    await context.route('**/api/v1/partner/notifications**', async (route) => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() !== 'GET') {
        await route.abort()
        return
      }
      if (path.endsWith('/unread-count')) {
        unreadGets += 1
        await route.fulfill({ json: { unread: bookingArrived ? 1 : 0 } })
      } else {
        inboxGets += 1
        const notifications = bookingArrived
          ? [{
              id: 23001, type: 'RIDE_REQUESTED', rideId: 23001,
              message: 'A passenger requested ride #23001.',
              read: false, createdAt: '2026-10-16T10:00:00Z',
            }]
          : []
        await route.fulfill({ json: pageOf(notifications) })
      }
    })

    await page.goto('/partner/notifications')
    await expect(page.getByRole('heading', { name: 'Notifications' })).toBeVisible()
    await expect(page.getByText('No notifications yet')).toBeVisible()
    await expect.poll(() => inboxGets).toBeGreaterThan(0)
    await expect.poll(() => unreadGets).toBeGreaterThan(0)

    // A DOM marker survives React Query updates, but not a full page reload.
    await page.evaluate(() => { document.documentElement.dataset.f23 = 'alive' })
    bookingArrived = true

    await expect(page.getByTestId('partner-notification-23001')).toBeVisible({ timeout: 12_000 })
    await expect(page.getByTestId('partner-notifications-unread')).toHaveText('1', { timeout: 12_000 })
    expect(await page.evaluate(() => document.documentElement.dataset.f23)).toBe('alive')
    expect(inboxGets).toBeGreaterThan(1)
    expect(unreadGets).toBeGreaterThan(1)
  })

  test('a new request appears in the active Rides queue without navigating or reloading', async ({
    page, context, guards,
  }) => {
    void guards
    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    let bookingArrived = false
    let queueGets = 0
    await context.route('**/api/v1/partner/rides**', async (route) => {
      const url = new URL(route.request().url())
      if (url.pathname !== '/api/v1/partner/rides' || route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      queueGets += 1
      const rides = bookingArrived
        ? [{
            rideId: 23002, customerFirstName: 'Live', customerLastName: 'BookingF23',
            customerPhone: '+355690000999', pickupLatitude: 41.3275,
            pickupLongitude: 19.8187, pickupAddress: 'Tirana',
            destinationLatitude: 41.32, destinationLongitude: 19.83,
            destinationAddress: 'City Center', status: 'REQUESTED',
            requestedAt: '2026-10-16T10:00:00Z',
          }]
        : []
      await route.fulfill({ json: pageOf(rides, 10) })
    })

    await page.goto('/partner/rides')
    await expect(page.getByRole('combobox', { name: 'Status' })).toHaveValue('ACTIVE')
    await expect.poll(() => queueGets).toBeGreaterThan(0)
    await expect(page.getByTestId('partner-ride-23002')).toHaveCount(0)

    bookingArrived = true
    await expect(page.getByTestId('partner-ride-23002')).toBeVisible({ timeout: 12_000 })
    expect(queueGets).toBeGreaterThan(1)
  })

  test('dashboard awaiting-response counter changes without F5', async ({
    page, context, guards,
  }) => {
    void guards
    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    let count = 0
    let countGets = 0
    await context.route('**/api/v1/partner/rides**', async (route) => {
      const url = new URL(route.request().url())
      if (url.pathname !== '/api/v1/partner/rides' || route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      countGets += 1
      await route.fulfill({ json: pageOf([], 1, count) })
    })

    await page.goto('/partner')
    const awaiting = page.getByText('Awaiting response', { exact: true })
      .locator('..').locator('..').locator('p')
    await expect(awaiting).toHaveText('0', { timeout: 30_000 })
    await expect.poll(() => countGets).toBeGreaterThan(0)

    count = 2
    await expect(awaiting).toHaveText('2', { timeout: 12_000 })
    expect(countGets).toBeGreaterThan(1)
  })
})
