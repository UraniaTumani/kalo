import { test, expect } from '@playwright/test'

// Real HTTP and DB throughout. Only the server's SMS transport is simulated.
test('partner invitation activates exactly one driver who can log in', async ({ page, request, browser }) => {
  // Verify both routes before writing fixtures: neither may reach a developer backend.
  for (const base of ['http://127.0.0.1:18881', 'http://localhost:5177']) {
    const probe = await request.get(`${base}/api/v1/auth/e2e-invitation-mailbox`, {
      params: { destination: 'fixture-routing-check' },
    })
    expect(probe.headers()['x-kalo-invitation-fixture'],
      `Expected isolated invitation backend via ${base}; received HTTP ${probe.status()}: ${await probe.text()}`,
    ).toBe('isolated-compose')
    expect(probe.status()).toBe(204)
  }
  await page.addInitScript(() => localStorage.setItem('kalo.language', 'en'))
  await page.goto('/login')
  await page.locator('input[type="tel"]').fill('+355690000003')
  await page.locator('input[type="password"]').fill('Partner123!')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/partner$/)
  const partnerToken = await page.evaluate(() => localStorage.getItem('kalo.token'))
  const headers = { Authorization: `Bearer ${partnerToken}` }
  const driverResponse = await request.post('http://127.0.0.1:18881/api/v1/partner/drivers', {
    headers, data: { firstName: 'Invitation', lastName: 'Browser', phone: '+355699999991',
      licenseNumber: 'E2E-INVITATION-ONLY', licenseExpiryDate: '2035-01-01', dateOfBirth: '1990-01-01' },
  })
  expect(driverResponse.status(), `Create driver response: ${await driverResponse.text()}`).toBe(201)
  const driver = await driverResponse.json()
  await page.goto('/partner/drivers')
  const row = page.getByRole('row').filter({ hasText: 'E2E-INVITATION-ONLY' })
  await row.getByRole('button', { name: 'Invite driver', exact: true }).click()
  const queued = page.waitForResponse(response => response.url().endsWith(`/drivers/${driver.id}/invitations`) && response.request().method() === 'POST')
  await page.getByRole('dialog').getByRole('button', { name: 'Invite driver', exact: true }).click()
  const queuedResponse = await queued
  expect(queuedResponse.status(), `Invitation response from ${queuedResponse.url()}: ${await queuedResponse.text()}`).toBe(202)
  await expect(page.getByText('Invitation queued. SMS delivery is not yet confirmed.', { exact: true })).toBeVisible()

  let token = ''
  await expect.poll(async () => {
    const response = await request.get('http://127.0.0.1:18881/api/v1/auth/e2e-invitation-mailbox', { params: { destination: driver.phone } })
    if (response.status() === 200) token = (await response.json()).token
    return Boolean(token)
  }, { timeout: 20_000, message: 'Simulated transport should capture the queued invitation' }).toBe(true)

  const context = await browser.newContext({
    permissions: ['geolocation'], geolocation: { latitude: 41.33, longitude: 19.82 },
  })
  try {
    await context.addInitScript(() => localStorage.setItem('kalo.language', 'en'))
    const driverPage = await context.newPage()
    await driverPage.goto(`http://localhost:5177/driver/activate#invite=${token}`)
    await expect(driverPage).toHaveURL('http://localhost:5177/driver/activate')
    const passwords = driverPage.locator('input[type="password"]')
    await passwords.nth(0).fill('DriverBrowser123!')
    await passwords.nth(1).fill('DriverBrowser123!')
    const activated = driverPage.waitForResponse(response => response.url().endsWith('/auth/driver/activate'))
    await driverPage.getByRole('button', { name: 'Activate account', exact: true }).click()
    expect((await activated).status()).toBe(204)
    await expect(driverPage.getByRole('status')).toContainText('Your driver account is active.')
    const reused = await request.post('http://127.0.0.1:18881/api/v1/auth/driver/activate', { data: { token, password: 'DriverBrowser123!' } })
    expect(reused.status()).toBe(400)
    token = ''
    await driverPage.getByRole('link', { name: 'Sign in', exact: true }).click()
    await driverPage.locator('input[type="tel"]').fill(driver.phone)
    await driverPage.locator('input[type="password"]').fill('DriverBrowser123!')
    await driverPage.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(driverPage).toHaveURL('http://localhost:5177/driver')
    await expect(driverPage.getByRole('heading', { name: 'Driver account', exact: true })).toBeVisible()
    // Browser location is simulated; HTTP, authorization and database are real.
    const gpsUpload = driverPage.waitForResponse(response =>
      response.url().endsWith('/api/v1/driver/me/location') && response.request().method() === 'PUT')
    await driverPage.getByRole('button', { name: 'Start sharing location' }).click()
    expect((await gpsUpload).status()).toBe(200)
    await expect(driverPage.getByRole('status')).toContainText('Location sent at')
    const savedLocation = await request.get(`http://127.0.0.1:18881/api/v1/partner/drivers/${driver.id}/location`, { headers })
    expect(savedLocation.status()).toBe(200)
    expect(await savedLocation.json()).toMatchObject({ driverId: driver.id, latitude: 41.33, longitude: 19.82 })
    await driverPage.getByRole('button', { name: 'Stop sharing location' }).click()
    await expect(driverPage.getByRole('status')).toContainText('Location sharing is stopped.')
    const accessToken = await driverPage.evaluate(() => localStorage.getItem('kalo.token'))
    const forbidden = await request.get('http://127.0.0.1:18881/api/v1/partner/drivers', { headers: { Authorization: `Bearer ${accessToken}` } })
    expect(forbidden.status()).toBe(403)
    await driverPage.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(driverPage).toHaveURL('http://localhost:5177/login')
  } finally { await context.close() }
})
