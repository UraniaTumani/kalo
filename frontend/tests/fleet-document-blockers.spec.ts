import { test, expect } from '@playwright/test'

for (const role of ['DRIVER', 'PARTNER'] as const) for (const language of ['en', 'sq'] as const) for (const width of [375, 1280]) {
  test(`${role}: safe document explanations in ${language} at ${width}px`, async ({ page, context }) => {
    await page.setViewportSize({ width, height: 900 })
    await context.grantPermissions(['geolocation'])
    await context.setGeolocation({ latitude: 41.33, longitude: 19.82 })
    await page.addInitScript(({ language }) => {
      localStorage.setItem('kalo.language', language)
      localStorage.setItem('kalo.token', 'fixture-token')
    }, { language })
    let mutations = 0
    let malformed = false
    await page.route('**/api/**', async route => {
      const req = route.request(), path = new URL(req.url()).pathname
      if (path === '/api/v1/me') return route.fulfill({ json: { id: 1, role, firstName: 'Test', lastName: 'Account', phone: '+355690000004', status: 'ACTIVE', phoneVerified: true } })
      if (path === '/api/v1/driver/me') return route.fulfill({ json: { availabilityStatus: 'OFFLINE' } })
      if (path === '/api/v1/driver/me/ride') return route.fulfill({ status: 204 })
      if (path.endsWith('/location')) return route.fulfill({ json: { driverId: 7, latitude: 41.33, longitude: 19.82 } })
      if (path.endsWith('/availability')) {
        mutations++
        return route.fulfill({ status: 400, json: { status: 400, code: 'FLEET_DOCUMENTS_REQUIRED', message: 'PRIVATE_BACKEND_DETAIL',
          documentIssues: malformed ? [{ ownerType: 'DRIVER', documentType: 'PRIVATE_DOCUMENT', reason: 'PRIVATE_REASON' }] : [
            { ownerType: 'DRIVER', documentType: 'DRIVING_LICENSE', reason: 'MISSING' },
            { ownerType: 'VEHICLE', documentType: 'INSURANCE', reason: 'PENDING' },
          ] } })
      }
      if (path === '/api/v1/partner/drivers') return route.fulfill({ json: {
        content: [{ id: 7, firstName: 'Driver', lastName: 'Seven', phone: '+355690000004', licenseNumber: 'L7', licenseExpiryDate: '2099-01-01', status: 'ACTIVE', availabilityStatus: 'OFFLINE' }],
        number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
      } })
      return route.fulfill({ json: {} })
    })
    await page.goto(role === 'DRIVER' ? '/driver' : '/partner/drivers')
    const online = language === 'en' ? 'Go online' : role === 'DRIVER' ? 'Kalo online' : 'Kalo në linjë'
    await page.getByRole('button', { name: online, exact: language === 'en' }).first().click()
    const panel = page.getByRole('region', { name: language === 'en' ? 'Documents blocking new work' : 'Dokumentet që bllokojnë punë të reja', exact: true })
    await expect(panel.locator('li')).toHaveCount(2)
    await expect(panel).toContainText(language === 'en' ? 'Missing.' : 'Mungon.')
    await expect(panel).toContainText(language === 'en' ? 'awaiting administrator review' : 'pret shqyrtimin nga administratori')
    await expect(panel).toContainText(role === 'DRIVER'
      ? language === 'en' ? 'Contact your taxi company' : 'Kontakto kompaninë tënde'
      : language === 'en' ? 'Open Documents' : 'Hap Dokumentet')
    expect(mutations).toBe(1)
    await expect(page.locator('body')).not.toContainText('PRIVATE_BACKEND_DETAIL')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    malformed = true
    // Reload clears the old refusal; another deliberate action tests unknown codes.
    await page.reload()
    await expect(panel).toHaveCount(0)
    await page.getByRole('button', { name: online, exact: language === 'en' }).first().click()
    await expect(panel).toBeVisible()
    await expect(panel.locator('li')).toHaveCount(0)
    await expect(page.locator('body')).not.toContainText('PRIVATE_REASON')
    await expect(page.locator('body')).not.toContainText('PRIVATE_DOCUMENT')
    await expect(page.locator('body')).not.toContainText('PRIVATE_BACKEND_DETAIL')
    expect(mutations).toBe(2)
  })
}
