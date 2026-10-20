import { test, expect } from '@playwright/test'

for (const owner of ['drivers', 'vehicles'] as const) {
  for (const width of [375, 1280]) {
    test(`${owner}: upload, private download and owner isolation at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.addInitScript(() => {
        localStorage.setItem('kalo.token', 'fixture-token')
        localStorage.setItem('kalo.language', 'en')
      })
      const type = owner === 'drivers' ? 'DRIVING_LICENSE' : 'VEHICLE_REGISTRATION'
      const documents: object[] = []
      let uploads = 0
      await page.route('**/api/**', async (route) => {
        const req = route.request()
        const path = new URL(req.url()).pathname
        if (path.endsWith('/file')) {
          expect(req.headers().authorization).toBe('Bearer fixture-token')
          return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.7\nfixture' })
        }
        if (path.endsWith('/upload')) {
          expect(req.headers().authorization).toBe('Bearer fixture-token')
          expect(req.headers()['content-type']).toContain('multipart/form-data; boundary=')
          expect(req.postData()).toContain(type)
          uploads++
          const item = { id: 91, documentType: type, documentNumber: 'ABC', fileUrl: 'local:private.pdf', issuedAt: null, expiresAt: null, verificationStatus: 'DRAFT', rejectionReason: null }
          documents.push(item)
          return route.fulfill({ status: 201, json: item })
        }
        if (path.endsWith('/documents')) return route.fulfill({ json: path.includes('/1/') ? documents : [] })
        if (path === '/api/v1/me') return route.fulfill({ json: { id: 1, role: 'PARTNER', firstName: 'Partner', lastName: 'Test', phone: '+355690000004', status: 'ACTIVE' } })
        if (path.endsWith(`/${owner}`)) {
          const content = [1, 2].map((id) => owner === 'drivers'
            ? { id, firstName: `Driver${id}`, lastName: 'Test', phone: '+355690000004', licenseNumber: `L${id}`, licenseExpiryDate: '2030-01-01', status: 'ACTIVE', availabilityStatus: 'OFFLINE' }
            : { id, plateNumber: `AA00${id}TR`, brand: 'Skoda', model: 'Octavia', manufactureYear: 2024, seats: 4, vehicleType: 'STANDARD', status: 'ACTIVE' })
          return route.fulfill({ json: { content, number: 0, size: 20, totalElements: 2, totalPages: 1, first: true, last: true } })
        }
        return route.fulfill({ json: {} })
      })
      await page.goto(`/partner/${owner}`)
      await page.getByRole('button', { name: 'Documents', exact: true }).first().click()
      const form = page.locator('form').filter({ has: page.locator('input[type="file"]') })
      const fileInput = form.locator('input[type="file"]')
      await expect(form.getByRole('button', { name: 'Add a document' })).toBeDisabled()
      await fileInput.setInputFiles({ name: 'bad.txt', mimeType: 'text/plain', buffer: Buffer.from('bad') })
      await expect(form.getByText('Choose a non-empty PDF, JPG or PNG file.')).toBeVisible()
      await fileInput.setInputFiles({ name: 'proof.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nfixture') })
      await form.locator('[name="documentNumber"]').fill('ABC')
      await form.getByRole('button', { name: 'Add a document' }).click()
      await expect(page.getByRole('button', { name: 'Download document' })).toBeVisible()
      expect(uploads).toBe(1)
      await expect(form.getByRole('button', { name: 'Add a document' })).toBeDisabled()
      const downloaded = page.waitForEvent('download')
      await page.getByRole('button', { name: 'Download document' }).click()
      expect((await downloaded).suggestedFilename()).toBe('document-91.pdf')
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.getByRole('button', { name: 'Documents', exact: true }).nth(1).click()
      await expect(page.getByRole('button', { name: 'Download document' })).toHaveCount(0)
      await expect(page.locator('input[type="file"]')).toHaveValue('')
    })
  }
}
