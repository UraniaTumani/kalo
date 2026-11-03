import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

// No HTTP mocks: real authorization, PostgreSQL and private local test storage.
for (const owner of ['drivers', 'vehicles'] as const) {
  test(`${owner}: rejected document renewed through UI preserves both files`, async ({ page, request }) => {
    for (const base of ['http://127.0.0.1:18881', 'http://localhost:5177']) {
      const probe = await request.get(`${base}/api/v1/auth/e2e-invitation-mailbox?destination=fixture-routing-check`)
      expect(probe.status()).toBe(204)
      expect(probe.headers()['x-kalo-invitation-fixture']).toBe('isolated-compose')
    }
    const base = 'http://127.0.0.1:18881/api/v1'
    const login = await request.post(`${base}/auth/login`, { data: { phone: '+355690000001', password: 'Admin123!' } })
    expect(login.status()).toBe(200)
    const admin = { Authorization: `Bearer ${(await login.json()).accessToken}` }
    const administratorResponse = await request.get(`${base}/me`, { headers: admin })
    expect(administratorResponse.status()).toBe(200)
    const administrator = await administratorResponse.json()

    await page.addInitScript(() => localStorage.setItem('kalo.language', 'en'))
    await page.goto('/login')
    await page.locator('input[type="tel"]').fill('+355690000003')
    await page.locator('input[type="password"]').fill('Partner123!')
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(page).toHaveURL(/\/partner$/)
    const token = await page.evaluate(() => localStorage.getItem('kalo.token'))
    expect(token).toBeTruthy()
    const headers = { Authorization: `Bearer ${token}` }
    const profile = await request.get(`${base}/partner/me`, { headers })
    expect(profile.status()).toBe(200)
    const { companyId } = await profile.json()
    const marker = owner === 'drivers' ? 'E2E-RENEW-LIC' : 'E2E-RENEW-CAR'
    const created = await request.post(`${base}/partner/${owner}`, { headers, data: owner === 'drivers'
      ? { firstName: 'Renewal', lastName: 'Browser', phone: '+355699999992', licenseNumber: marker, licenseExpiryDate: '2099-12-31', dateOfBirth: '1990-01-01' }
      : { plateNumber: marker, brand: 'Skoda', model: 'Octavia', manufactureYear: 2024, seats: 4, vehicleType: 'STANDARD', registrationExpiryDate: '2099-12-31', insuranceExpiryDate: '2099-12-31', technicalInspectionExpiryDate: '2099-12-31' } })
    expect(created.status(), await created.text()).toBe(201)
    const { id } = await created.json()
    const docs = `${base}/partner/${owner}/${id}/documents`
    const oldBytes = Buffer.from('%PDF-1.7\noriginal renewal evidence\n%%EOF')
    const newBytes = Buffer.from('%PDF-1.7\nreplacement renewal evidence\n%%EOF')
    const uploaded = await request.post(`${docs}/upload`, { headers, multipart: {
      documentType: owner === 'drivers' ? 'DRIVING_LICENSE' : 'VEHICLE_REGISTRATION', documentNumber: 'ORIGINAL', expiresAt: '2099-01-01',
      file: { name: 'original.pdf', mimeType: 'application/pdf', buffer: oldBytes },
    } })
    expect(uploaded.status(), await uploaded.text()).toBe(201)
    const old = await uploaded.json()
    const review = `${base}/admin/partners/${companyId}/fleet/${owner === 'drivers' ? 'DRIVER' : 'VEHICLE'}/${id}/documents`
    const rejected = await request.post(`${review}/${old.id}/reject`, { headers: admin, data: { reason: 'Replacement scan required' } })
    expect(rejected.status(), await rejected.text()).toBe(200)
    await page.goto(`/partner/${owner}`)
    await page.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: 'Documents', exact: true }).click()
    const panel = page.getByRole('region', { name: 'Document renewal and history', exact: true })
    await panel.getByRole('button', { name: 'Renew document', exact: true }).click()
    await panel.getByLabel('Replacement file', { exact: true }).setInputFiles({ name: 'replacement.pdf', mimeType: 'application/pdf', buffer: newBytes })
    await panel.getByLabel('New document number', { exact: true }).fill('REPLACEMENT')
    await panel.getByLabel('New expiry date', { exact: true }).fill('2099-12-31')
    await panel.getByRole('button', { name: 'Send for review', exact: true }).click()
    const renewed = page.waitForResponse(r => r.url().endsWith(`/documents/${old.id}/renew`) && r.request().method() === 'POST')
    await page.getByRole('dialog').getByRole('button', { name: 'Send for review', exact: true }).click()
    expect((await renewed).status()).toBe(201)
    await expect(panel.getByText('New document submitted for review.', { exact: true })).toBeVisible()
    const current = await request.get(docs, { headers })
    expect(current.status()).toBe(200)
    const versions = await current.json()
    expect(versions).toHaveLength(1)
    const replacement = versions[0]
    expect(replacement.id).not.toBe(old.id)
    expect(replacement.verificationStatus).toBe('PENDING')
    const approved = await request.post(`${review}/${replacement.id}/approve`, { headers: admin })
    expect(approved.status(), await approved.text()).toBe(200)
    const audited = await approved.json()
    expect(audited.reviewedByUserId).toBe(administrator.id)
    expect(Number.isFinite(Date.parse(audited.reviewedAt))).toBe(true)
    const auditHistory = await request.get(`${review}/history`, { headers: admin })
    expect(auditHistory.status()).toBe(200)
    const auditedVersions = await auditHistory.json()
    expect(auditedVersions).toHaveLength(2)
    expect(auditedVersions.find((d: { id: number }) => d.id === old.id)).toMatchObject({ verificationStatus: 'REJECTED', reviewedByUserId: administrator.id })
    expect(auditedVersions.find((d: { id: number }) => d.id === replacement.id)).toMatchObject({ verificationStatus: 'APPROVED', reviewedByUserId: administrator.id })

    await page.reload()
    await page.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: 'Documents', exact: true }).click()
    await panel.getByRole('button', { name: 'Show document history', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Download this version', exact: true })).toHaveCount(2)
    const browserDownload = page.waitForEvent('download')
    await panel.locator('li').filter({ hasText: 'ORIGINAL' }).getByRole('button', { name: 'Download this version', exact: true }).click()
    const downloaded = await browserDownload
    expect(downloaded.suggestedFilename()).toBe(`document-${old.id}.pdf`)
    const downloadedPath = await downloaded.path()
    expect(downloadedPath).not.toBeNull()
    expect(await readFile(downloadedPath!)).toEqual(oldBytes)
    const historyResponse = await request.get(`${docs}/history`, { headers })
    expect(historyResponse.status()).toBe(200)
    const history = await historyResponse.json()
    expect(history).toHaveLength(2)
    expect(history.find((d: { id: number }) => d.id === old.id)).toMatchObject({ verificationStatus: 'REJECTED', documentNumber: 'ORIGINAL' })
    expect(history.find((d: { id: number }) => d.id === replacement.id)).toMatchObject({ verificationStatus: 'APPROVED', documentNumber: 'REPLACEMENT' })
    for (const [documentId, bytes] of [[old.id, oldBytes], [replacement.id, newBytes]] as const) {
      const file = await request.get(`${docs}/${documentId}/file`, { headers })
      expect(file.status()).toBe(200)
      expect(file.headers()['cache-control']).toBe('no-store')
      expect(await file.body()).toEqual(bytes)
      const anonymous = await request.get(`${docs}/${documentId}/file`)
      expect(anonymous.status()).toBe(401)
    }
    const superseded = await request.post(`${review}/${old.id}/approve`, { headers: admin })
    expect(superseded.status()).toBe(400)
  })
}
