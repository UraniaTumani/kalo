import { test, expect, type Page } from '@playwright/test'

const token = 'A'.repeat(43)
const password = 'TestPass123!'
const activationUrl = `/driver/activate#invite=${token}`
const phone = '+355690000004'

async function fixtures(page: Page, partner = false) {
  const state = { queueStatus: 202, activationStatus: 204, role: partner ? 'PARTNER' : 'DRIVER',
    queues: 0, activations: 0, logins: 0, refreshes: 0, logout: 0 }
  await page.addInitScript((signedIn) => {
    localStorage.setItem('kalo.language', 'en')
    if (signedIn) localStorage.setItem('kalo.token', 'fixture-partner')
  }, partner)
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/driver/activate') {
      state.activations++
      expect(request.method()).toBe('POST')
      expect(request.headers().authorization).toBeUndefined()
      expect(new URL(request.url()).search).toBe('')
      expect(request.postDataJSON()).toEqual({ token, password })
      return route.fulfill(state.activationStatus === 204 ? { status: 204 } :
        { status: state.activationStatus, json: { message: 'SENSITIVE_PROVIDER_DETAIL', status: state.activationStatus } })
    }
    if (path === '/api/v1/partner/drivers/7/invitations') {
      state.queues++
      expect(request.method()).toBe('POST')
      expect(request.headers().authorization).toBe('Bearer fixture-partner')
      expect(request.postData()).toBeNull()
      return route.fulfill(state.queueStatus === 202 ? { status: 202, body: '' } :
        { status: state.queueStatus, json: { message: 'SENSITIVE_PROVIDER_DETAIL', status: state.queueStatus } })
    }
    if (path === '/api/v1/auth/login') {
      state.logins++
      expect(request.postDataJSON()).toEqual({ phone, password })
      return route.fulfill({ json: { accessToken: 'fixture-driver', refreshToken: 'fixture-refresh', tokenType: 'Bearer', expiresIn: 900 } })
    }
    if (path === '/api/v1/auth/refresh') { state.refreshes++; return route.fulfill({ status: 401 }) }
    if (path === '/api/v1/auth/logout') { state.logout++; return route.fulfill({ status: 204 }) }
    if (path === '/api/v1/me') return route.fulfill({ json: {
      id: 1, role: state.role, firstName: 'Test', lastName: 'Account', phone,
      email: null, status: 'ACTIVE', phoneVerified: true,
    } })
    if (path === '/api/v1/partner/drivers') return route.fulfill({ json: {
      content: [{ id: 7, firstName: 'Driver', lastName: 'Seven', phone, licenseNumber: 'L7',
        licenseExpiryDate: '2030-01-01', status: 'ACTIVE', availabilityStatus: 'OFFLINE' }],
      number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
    } })
    // Auxiliary read-only dashboard data is irrelevant to this scenario.
    expect(request.method()).toBe('GET')
    return route.fulfill({ json: {} })
  })
  return state
}

async function fillPasswords(page: Page, confirmation = password) {
  await page.locator('input[type="password"]').nth(0).fill(password)
  await page.locator('input[type="password"]').nth(1).fill(confirmation)
}

for (const width of [375, 1280]) {
  test.describe(`${width}px`, () => {
    test.beforeEach(async ({ page }) => { await page.setViewportSize({ width, height: 900 }) })

    test('cancel invitation does not submit a request', async ({ page }) => {
      const state = await fixtures(page, true)
      await page.goto('/partner/drivers')
      await page.getByRole('button', { name: 'Invite driver', exact: true }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toContainText(phone)
      await dialog.getByRole('button', { name: /cancel/i }).click()
      await expect(dialog).not.toBeVisible()
      expect(state.queues).toBe(0)
    })

    test('queue success says queued rather than delivered', async ({ page }) => {
      const state = await fixtures(page, true)
      await page.goto('/partner/drivers')
      await page.getByRole('button', { name: 'Invite driver', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: 'Invite driver', exact: true }).click()
      await expect(page.getByRole('status')).toContainText('Invitation queued. SMS delivery is not yet confirmed.')
      expect(state.queues).toBe(1)
    })

    test('queue refusal shows safe feedback without automatic retry', async ({ page }) => {
      const state = await fixtures(page, true)
      state.queueStatus = 400
      await page.goto('/partner/drivers')
      await page.getByRole('button', { name: 'Invite driver', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: 'Invite driver', exact: true }).click()
      await expect(page.getByRole('status')).toContainText('Cannot invite this driver now.')
      await expect(page.locator('body')).not.toContainText('SENSITIVE_PROVIDER_DETAIL')
      expect(state.queues).toBe(1)
    })

    test('activation success then separate driver login and sign-out', async ({ page }) => {
      const state = await fixtures(page)
      await page.goto(activationUrl)
      await expect(page).toHaveURL('/driver/activate')
      const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
      expect(fits).toBe(true)
      await fillPasswords(page)
      await page.getByRole('button', { name: 'Activate account', exact: true }).click()
      await expect(page.getByRole('status')).toContainText('Your driver account is active.')
      expect(state.activations).toBe(1)
      expect(state.logins).toBe(0)
      const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))
      expect(stored).not.toContain(token)
      expect(stored).not.toContain(password)
      await expect(page.locator('input[type="password"]')).toHaveCount(0)
      await page.getByRole('link', { name: 'Sign in', exact: true }).click()
      await page.locator('input[type="tel"]').fill(phone)
      await page.locator('input[type="password"]').fill(password)
      await page.getByRole('button', { name: 'Sign in', exact: true }).click()
      await expect(page).toHaveURL('/driver')
      await expect(page.getByRole('heading', { name: 'Driver account', exact: true })).toBeVisible()
      await page.getByRole('button', { name: 'Sign out', exact: true }).click()
      await expect(page).toHaveURL('/login')
      await expect.poll(() => state.logout).toBe(1)
    })

    test('activation failure and rate limit never expose raw API detail', async ({ page }) => {
      const state = await fixtures(page, true)
      state.activationStatus = 400
      await page.goto(activationUrl)
      await fillPasswords(page)
      await page.getByRole('button', { name: 'Activate account', exact: true }).click()
      await expect(page.getByRole('alert')).toContainText('Activation could not be confirmed.')
      state.activationStatus = 429
      await page.getByRole('button', { name: 'Activate account', exact: true }).click()
      await expect(page.getByRole('alert')).toContainText('Too many attempts.')
      await expect(page.locator('body')).not.toContainText('SENSITIVE_PROVIDER_DETAIL')
      expect(state.activations).toBe(2)
      expect(state.refreshes).toBe(0)
    })

    test('mismatch is local and refreshing loses the in-memory invitation', async ({ page }) => {
      const state = await fixtures(page)
      await page.goto(activationUrl)
      await fillPasswords(page, 'Different123!')
      await page.getByRole('button', { name: 'Activate account', exact: true }).click()
      await expect(page.getByRole('alert')).toHaveText('Passwords do not match.')
      expect(state.activations).toBe(0)
      await page.reload()
      await expect(page.getByRole('alert')).toContainText('This invitation link is missing or invalid.')
      await expect(page.locator('input[type="password"]')).toHaveCount(0)
    })
  })
}


test('driver can start and stop location sharing', async ({ page, context }) => {
  await fixtures(page)
  await context.grantPermissions(['geolocation'])
  await context.setGeolocation({ latitude: 41.33, longitude: 19.82 })
  let uploads = 0
  await page.route('**/api/v1/driver/me/location', async route => {
    uploads++
    expect(route.request().method()).toBe('PUT')
    expect(route.request().headers().authorization).toBe('Bearer fixture-driver')
    expect(route.request().postDataJSON()).toEqual({ latitude: 41.33, longitude: 19.82 })
    await route.fulfill({ json: { driverId: 1, latitude: 41.33, longitude: 19.82, locationUpdatedAt: new Date().toISOString() } })
  })
  await page.goto('/login')
  await page.locator('input[type="tel"]').fill(phone)
  await page.locator('input[type="password"]').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/driver$/)
  expect(uploads).toBe(0)
  await page.getByRole('button', { name: 'Start sharing location' }).click()
  await expect(page.getByRole('status')).toContainText('Location sent at')
  expect(uploads).toBe(1)
  await page.getByRole('button', { name: 'Stop sharing location' }).click()
  await expect(page.getByRole('status')).toContainText('Location sharing is stopped.')
})
