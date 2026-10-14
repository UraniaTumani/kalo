import { test, expect, SEEDED, apiLogin, seedSession } from './support/fixtures'

/**
 * F1: only authentication touches the isolated E2E backend.
 * All notification GET/POST calls are browser mocks, never DB writes.
 */
type Notification = {
  id: number
  type: 'RIDE_RATED'
  rideId: number
  message: string
  read: boolean
  createdAt: string
}

const initial: Notification[] = [
  { id: 101, type: 'RIDE_RATED', rideId: 42, message: 'A passenger rated ride #42: driver 5/5, company 4/5.', read: false, createdAt: '2026-10-10T10:00:00Z' },
  { id: 102, type: 'RIDE_RATED', rideId: 38, message: 'A passenger rated ride #38: driver 4/5, company 5/5.', read: false, createdAt: '2026-10-09T10:00:00Z' },
]

test('F1 · unread badge, inbox and mark-as-read stay in sync', async ({ page, context, guards }) => {
  void guards
  const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
  await seedSession(context, tokens)

  let items = initial.map((n) => ({ ...n }))
  const marked: number[] = []

  await context.route('**/api/v1/partner/notifications**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/unread-count')) {
      await route.fulfill({ json: { unread: items.filter((n) => !n.read).length } })
    } else if (route.request().method() === 'POST' && path.endsWith('/read')) {
      const id = Number(path.split('/').at(-2))
      const item = items.find((n) => n.id === id)
      if (!item) {
        await route.fulfill({ status: 404, json: { message: 'Not found' } })
        return
      }
      marked.push(id)
      items = items.map((n) => n.id === id ? { ...n, read: true } : n)
      await route.fulfill({ json: { ...item, read: true } })
    } else if (route.request().method() === 'GET') {
      await route.fulfill({ json: {
        content: items, totalElements: items.length, totalPages: 1,
        number: 0, size: 20, first: true, last: true, empty: items.length === 0,
      } })
    } else {
      await route.abort()
    }
  })

  await page.goto('/partner/notifications')
  await expect(page.getByRole('heading', { name: 'Notifications' })).toBeVisible()
  await expect(page.getByTestId('partner-notifications-unread')).toHaveText('2')
  await expect(page.getByTestId('partner-notification-101')).toContainText('driver 5/5')
  await expect(page.getByTestId('partner-notification-102')).toContainText('driver 4/5')

  await page.getByTestId('partner-notification-101').getByRole('button', { name: 'Mark as read' }).click()
  await expect(page.getByTestId('partner-notifications-unread')).toHaveText('1')
  await expect(page.getByTestId('partner-notification-101')).toContainText('Read')
  await expect(page.getByTestId('partner-notification-101').getByRole('button', { name: 'Mark as read' })).toHaveCount(0)
  expect(marked).toEqual([101])
})

test('F1 · empty inbox and Albanian translation', async ({ page, context, guards }) => {
  void guards
  const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
  await seedSession(context, tokens, 'sq')
  await context.route('**/api/v1/partner/notifications**', async (route) => {
    if (new URL(route.request().url()).pathname.endsWith('/unread-count')) {
      await route.fulfill({ json: { unread: 0 } })
    } else {
      await route.fulfill({ json: {
        content: [], totalElements: 0, totalPages: 0,
        number: 0, size: 20, first: true, last: true, empty: true,
      } })
    }
  })

  await page.goto('/partner/notifications')
  await expect(page.getByRole('heading', { name: 'Njoftimet' })).toBeVisible()
  await expect(page.getByText('Ende nuk ka njoftime')).toBeVisible()
  await expect(page.getByTestId('partner-notifications-unread')).toHaveCount(0)
})

test('F1 · customers never fetch or see partner notifications', async ({ page, context, guards }) => {
  void guards
  const tokens = await apiLogin(page.request, SEEDED.customerB.phone, SEEDED.customerB.password)
  await seedSession(context, tokens)
  let calls = 0
  await context.route('**/api/v1/partner/notifications**', async (route) => {
    calls += 1
    await route.fulfill({ status: 403, json: { message: 'Forbidden' } })
  })

  await page.goto('/ride')
  await expect(page.getByRole('link', { name: 'Book a taxi' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Notifications' })).toHaveCount(0)
  await page.goto('/partner/notifications')
  await expect(page).not.toHaveURL(/\/partner\/notifications$/)
  expect(calls).toBe(0)
})
