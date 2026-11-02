import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page, owner: 'drivers' | 'vehicles') {
  const documentType = owner === 'drivers' ? 'DRIVING_LICENSE' : 'VEHICLE_REGISTRATION'
  const old = { id: 91, documentType, documentNumber: 'OLD', fileUrl: 'local:old.pdf', issuedAt: '2020-01-01',
    expiresAt: owner === 'vehicles' ? '2020-12-31' : '2099-01-01', verificationStatus: owner === 'vehicles' ? 'APPROVED' : 'REJECTED', rejectionReason: owner === 'drivers' ? 'Unreadable scan' : null }
  const state = { posts: 0, lost: false, current: old, history: [old] }
  await page.addInitScript(() => { localStorage.setItem('kalo.token', 'partner-fixture'); localStorage.setItem('kalo.language', 'en') })
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (path === '/api/v1/me') return route.fulfill({ json: { id: 1, role: 'PARTNER', firstName: 'Partner', lastName: 'Test', phone: '+355690000004', status: 'ACTIVE' } })
    if (path.endsWith('/renew')) {
      expect(path).toBe(`/api/v1/partner/${owner}/1/documents/91/renew`)
      expect(req.headers().authorization).toBe('Bearer partner-fixture')
      expect(req.headers()['content-type']).toContain('multipart/form-data')
      expect(req.postData()).toContain('replacement.pdf')
      expect(req.postData()).toContain('2099-12-31')
      state.posts++
      state.current = { ...old, id: 92, documentNumber: 'NEW', fileUrl: 'local:new.pdf', expiresAt: '2099-12-31', verificationStatus: 'PENDING', rejectionReason: null }
      state.history = [state.current, old]
      if (state.lost) return route.abort()
      return route.fulfill({ status: 201, json: state.current })
    }
    if (path.endsWith('/history')) return route.fulfill({ json: state.history })
    if (path.endsWith('/documents')) return route.fulfill({ json: [state.current] })
    if (path.endsWith('/file')) return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4\nhistorical' })
    if (path.endsWith(`/${owner}`)) return route.fulfill({ json: {
      content: [owner === 'drivers' ? { id: 1, firstName: 'Driver1', lastName: 'Test', phone: '+355690000004', licenseNumber: 'L1', licenseExpiryDate: '2099-01-01', status: 'ACTIVE', availabilityStatus: 'OFFLINE' }
        : { id: 1, plateNumber: 'AA001TR', brand: 'Skoda', model: 'Octavia', manufactureYear: 2024, seats: 4, vehicleType: 'STANDARD', status: 'ACTIVE' }],
      number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
    } })
    return route.fulfill({ json: {} })
  })
  await page.goto(`/partner/${owner}`)
  await page.getByRole('button', { name: 'Documents', exact: true }).first().click()
  const panel = page.getByRole('region', { name: 'Document renewal and history', exact: true })
  await panel.getByRole('button', { name: 'Renew document', exact: true }).click()
  return { state, panel }
}
for (const owner of ['drivers', 'vehicles'] as const) for (const width of [375, 1280]) {
  test(`${owner}: confirmed renewal retains downloadable history at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const { state, panel } = await fixture(page, owner)
    await panel.getByLabel('Replacement file', { exact: true }).setInputFiles({ name: 'bad.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('bad') })
    await expect(panel.getByRole('alert')).toBeVisible()
    expect(state.posts).toBe(0)
    await panel.getByLabel('Replacement file', { exact: true }).setInputFiles({ name: 'replacement.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\nreplacement') })
    await panel.getByLabel('New document number', { exact: true }).fill('NEW')
    await panel.getByLabel('New expiry date', { exact: true }).fill('2099-12-31')
    await panel.getByRole('button', { name: 'Send for review', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click()
    expect(state.posts).toBe(0)
    await panel.getByRole('button', { name: 'Send for review', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Send for review', exact: true }).click()
    await expect(panel.getByText('New document submitted for review.', { exact: true })).toBeVisible()
    await expect(panel.getByRole('button', { name: 'Renew document', exact: true })).toHaveCount(0)
    expect(state.posts).toBe(1)
    await panel.getByRole('button', { name: 'Show document history', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Download this version', exact: true })).toHaveCount(2)
    const download = page.waitForEvent('download')
    await panel.getByRole('button', { name: 'Download this version', exact: true }).nth(1).click()
    expect((await download).suggestedFilename()).toBe('document-91.pdf')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
  test(`${owner}: lost renewal reply rereads current state without retry at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const { state, panel } = await fixture(page, owner)
    state.lost = true
    await panel.getByLabel('Replacement file', { exact: true }).setInputFiles({ name: 'replacement.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\nreplacement') })
    await panel.getByLabel('New expiry date', { exact: true }).fill('2099-12-31')
    await panel.getByRole('button', { name: 'Send for review', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Send for review', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('Could not confirm')
    await expect(panel.getByRole('button', { name: 'Renew document', exact: true })).toHaveCount(0)
    expect(state.posts).toBe(1)
    await panel.getByRole('button', { name: 'Show document history', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Download this version', exact: true })).toHaveCount(2)
  })
}
