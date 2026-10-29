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
    if (path === '/api/v1/driver/me/ride') return route.fulfill({ status: 204 })
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

test('driver can change availability and recover from an unconfirmed update', async ({ page }) => {
  await fixtures(page)
  let current = 'OFFLINE'
  let fail = false
  await page.route('**/api/v1/driver/me', route => route.fulfill({ json: { availabilityStatus: current } }))
  await page.route('**/api/v1/driver/me/availability', async route => {
    expect(route.request().method()).toBe('PUT')
    expect(route.request().headers().authorization).toBe('Bearer fixture-driver')
    if (fail) return route.fulfill({ status: 400, json: { message: 'secret detail' } })
    current = route.request().postDataJSON().availabilityStatus
    return route.fulfill({ json: { availabilityStatus: current } })
  })
  await page.goto('/login')
  await page.locator('input[type="tel"]').fill(phone)
  await page.locator('input[type="password"]').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Go online' })).toBeEnabled()
  await page.getByRole('button', { name: 'Go online' }).click()
  await expect(page.getByRole('button', { name: 'Go offline' })).toBeEnabled()
  fail = true
  await page.getByRole('button', { name: 'Go offline' }).click()
  await expect(page.getByRole('alert')).toContainText('Could not confirm')
  await expect(page.locator('body')).not.toContainText('secret detail')
  await expect(page.getByRole('button', { name: 'Go offline' })).toBeDisabled()
  await page.getByRole('button', { name: 'Refresh status' }).click()
  await expect(page.getByRole('button', { name: 'Go offline' })).toBeEnabled()
  current = 'BUSY'
  await page.reload()
  await expect(page.getByText('Busy on a ride')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Go online' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Go offline' })).toBeDisabled()
})

for (const width of [375, 1280]) {
  test(`current ride handles assignment, failure and cancellation at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await fixtures(page)
    let response: 'empty' | 'ride' | 'error' = 'empty'
    await page.route('**/api/v1/driver/me/ride', async route => {
      expect(route.request().method()).toBe('GET')
      expect(route.request().headers().authorization).toBe('Bearer fixture-driver')
      if (response === 'empty') return route.fulfill({ status: 204 })
      if (response === 'error') return route.fulfill({ status: 500, json: { message: 'SECRET_DATABASE_DETAIL' } })
      return route.fulfill({ json: {
        rideId: 17, status: 'DRIVER_ASSIGNED', vehicleId: 3,
        pickupAddress: 'Pickup test address', pickupLatitude: 41.33, pickupLongitude: 19.82,
        destinationAddress: null, destinationLatitude: 41.34, destinationLongitude: 19.83,
      } })
    })
    await page.goto('/login')
    await page.locator('input[type="tel"]').fill(phone)
    await page.locator('input[type="password"]').fill(password)
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    const card = page.getByRole('region', { name: 'Current ride', exact: true })
    await expect(card).toContainText('You have no active assigned ride.')
    response = 'ride'
    await card.getByRole('button', { name: 'Refresh ride' }).click()
    await expect(card).toContainText('Ride assigned')
    await expect(card).toContainText('Pickup test address')
    await expect(card).toContainText('41.34, 19.83')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    response = 'error'
    await card.getByRole('button', { name: 'Refresh ride' }).click()
    await expect(card.getByRole('alert')).toContainText('Could not confirm your current ride.')
    await expect(card).not.toContainText('Pickup test address')
    await expect(page.locator('body')).not.toContainText('SECRET_DATABASE_DETAIL')
    response = 'empty'
    await card.getByRole('button', { name: 'Refresh ride' }).click()
    await expect(card).toContainText('You have no active assigned ride.')
    await expect(card.getByRole('alert')).toHaveCount(0)
  })
}

for (const width of [375, 1280]) {
  test(`driver ride actions require confirmation and follow server state at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await fixtures(page)
    let status = 'DRIVER_ASSIGNED'
    let posts = 0
    let fail = false
    await page.route('**/api/v1/driver/me/ride', route => route.fulfill({ json: {
      rideId: 17, status, pickupAddress: 'Pickup', destinationAddress: 'Destination',
      pickupLatitude: 41.3, pickupLongitude: 19.8, destinationLatitude: 41.4, destinationLongitude: 19.9,
    } }))
    await page.route('**/api/v1/driver/me/rides/17/*', async route => {
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers().authorization).toBe('Bearer fixture-driver')
      posts++
      if (fail) return route.fulfill({ status: 409, json: { message: 'SECRET_DETAIL' } })
      const action = new URL(route.request().url()).pathname.split('/').pop()
      const expected = { 'driver-arriving': 'DRIVER_ASSIGNED', 'driver-arrived': 'DRIVER_ARRIVING', start: 'DRIVER_ARRIVED' }
      expect(status).toBe(expected[action as keyof typeof expected])
      status = { 'driver-arriving': 'DRIVER_ARRIVING', 'driver-arrived': 'DRIVER_ARRIVED', start: 'IN_PROGRESS' }[action as keyof typeof expected]
      await route.fulfill({ json: { rideId: 17, status } })
    })
    await page.goto('/login')
    await page.locator('input[type="tel"]').fill(phone)
    await page.locator('input[type="password"]').fill(password)
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    const card = page.getByRole('region', { name: 'Current ride', exact: true })
    await card.getByRole('button', { name: 'On my way', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click()
    expect(posts).toBe(0)
    fail = true
    await card.getByRole('button', { name: 'On my way', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'On my way', exact: true }).click()
    await expect(card.getByRole('alert')).toContainText('could not be confirmed')
    await expect(card.getByRole('button', { name: 'On my way', exact: true })).toBeVisible()
    expect(posts).toBe(1)
    await expect(card).not.toContainText('SECRET_DETAIL')
    fail = false
    for (const label of ['On my way', 'I have arrived', 'Start ride']) {
      await card.getByRole('button', { name: label, exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: label, exact: true }).click()
      await expect(page.getByRole('dialog')).toHaveCount(0)
    }
    await expect(card).toContainText('Ride in progress')
    await expect(card.getByRole('button', { name: 'Start ride', exact: true })).toHaveCount(0)
    expect(posts).toBe(4)
  })
}

for (const width of [375, 1280]) {
  test(`driver validates and confirms taximeter amount at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await fixtures(page)
    let completed = false
    let posts = 0
    await page.route('**/api/v1/driver/me/ride', route => route.fulfill(completed ? { status: 204 } : { json: {
      rideId: 17, status: 'IN_PROGRESS', pickupAddress: 'Pickup', destinationAddress: 'Destination',
      pickupLatitude: 41.3, pickupLongitude: 19.8, destinationLatitude: 41.4, destinationLongitude: 19.9,
    } }))
    await page.route('**/api/v1/driver/me/rides/17/complete', async route => {
      posts++
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers().authorization).toBe('Bearer fixture-driver')
      expect(route.request().postDataJSON()).toEqual({ finalAmount: 800.5 })
      completed = true
      await route.fulfill({ json: { rideId: 17, status: 'COMPLETED' } })
    })
    await page.goto('/login')
    await page.locator('input[type="tel"]').fill(phone)
    await page.locator('input[type="password"]').fill(password)
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    const card = page.getByRole('region', { name: 'Current ride', exact: true })
    const amount = card.getByLabel('Taximeter amount (ALL)', { exact: true })
    for (const invalid of ['', '0', '-1', '1.234', '10000000000', 'abc']) {
      await amount.fill(invalid)
      await card.getByRole('button', { name: 'Complete ride', exact: true }).click()
      await expect(card.getByRole('alert')).toContainText('Enter a positive amount')
      expect(posts).toBe(0)
    }
    await amount.fill('800,50')
    await card.getByRole('button', { name: 'Complete ride', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText('800.50 ALL')
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click()
    expect(posts).toBe(0)
    await card.getByRole('button', { name: 'Complete ride', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Complete ride', exact: true }).click()
    await expect(card).toContainText('You have no active assigned ride.')
    expect(posts).toBe(1)
  })
}
