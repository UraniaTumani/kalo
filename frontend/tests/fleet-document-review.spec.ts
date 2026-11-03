import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page) {
  const state = { posts: 0, status: 'DRAFT', failList: false, loseReply: false }
  await page.addInitScript(() => {
    localStorage.setItem('kalo.language', 'en')
    localStorage.setItem('kalo.token', 'admin-fixture')
  })
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    if (path === '/api/v1/me') return route.fulfill({ json: { id: 1, role: 'ADMIN', firstName: 'Admin', lastName: 'Test', status: 'ACTIVE', phoneVerified: true } })
    if (path === '/api/v1/admin/partners') return route.fulfill({ json: { content: [{ companyId: 3, displayName: 'Test fleet', legalName: 'Test fleet', nipt: 'TEST', verificationStatus: 'APPROVED', companyStatus: 'ACTIVE' }], number: 0, totalPages: 1, totalElements: 1, size: 10, first: true, last: true } })
    if (path.endsWith('/fleet/owners')) return route.fulfill({ json: { items: [{ id: 7, label: url.searchParams.get('ownerType') === 'DRIVER' ? 'Driver Seven (L7)' : 'AA007BB - Skoda Octavia' }], hasMore: false } })
    if (path.endsWith('/documents/history') && req.method() === 'GET') {
      if (state.failList) return route.fulfill({ status: 500, json: { message: 'PRIVATE_INTERNAL_DETAIL' } })
      return route.fulfill({ json: [
        { id: 11, documentType: 'DRIVING_LICENSE', documentNumber: 'L7', issuedAt: '2025-01-01', expiresAt: '2035-01-01', verificationStatus: state.status,
          rejectionReason: null, reviewedByUserId: 42, reviewedAt: '2026-11-03T09:00:00Z' },
        { id: 10, documentType: 'DRIVING_LICENSE', documentNumber: 'OLD', issuedAt: null, expiresAt: '2020-01-01', verificationStatus: 'REJECTED', rejectionReason: 'Old scan', reviewedByUserId: null, reviewedAt: null },
        { id: 9, documentType: 'PROFESSIONAL_CERTIFICATE', documentNumber: null, issuedAt: null, expiresAt: null, verificationStatus: 'DRAFT', rejectionReason: null },
      ] })
    }
    if (path.endsWith('/documents') && req.method() === 'GET') {
      if (state.failList) return route.fulfill({ status: 500, json: { message: 'PRIVATE_INTERNAL_DETAIL' } })
      return route.fulfill({ json: [{ id: 11, documentType: 'DRIVING_LICENSE', documentNumber: 'L7', issuedAt: '2025-01-01', expiresAt: '2035-01-01', verificationStatus: state.status, rejectionReason: state.status === 'REJECTED' ? 'Unreadable scan' : null, reviewedByUserId: state.status === 'DRAFT' ? null : 42, reviewedAt: state.status === 'DRAFT' ? null : '2026-11-03T09:00:00Z' }] })
    }
    if (path.endsWith('/file')) return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4\nfixture' })
    if (req.method() === 'POST' && /\/(approve|reject)$/.test(path)) {
      state.posts++
      state.status = path.endsWith('/approve') ? 'APPROVED' : 'REJECTED'
      if (state.status === 'REJECTED') expect(req.postDataJSON()).toEqual({ reason: 'Unreadable scan' })
      if (state.loseReply) return route.abort()
      return route.fulfill({ json: {} })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/admin/companies')
  await page.getByRole('button', { name: 'Fleet documents', exact: true }).click()
  await page.getByLabel('Driver or vehicle', { exact: true }).selectOption('7')
  await expect(page.getByRole('button', { name: 'Approve document', exact: true })).toBeVisible()
  return state
}
for (const width of [375, 1280]) test.describe(`${width}px`, () => {
  test.beforeEach(async ({ page }) => { await page.setViewportSize({ width, height: 900 }) })
  test('approval is confirmed once and downloads are private', async ({ page }) => {
    const state = await fixture(page)
    const downloaded = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download document', exact: true }).click()
    expect((await downloaded).suggestedFilename()).toBe('document-11.pdf')
    await page.getByRole('button', { name: 'Approve document', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click()
    expect(state.posts).toBe(0)
    await page.getByRole('button', { name: 'Approve document', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Approve document', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Approve document', exact: true })).toHaveCount(0)
    expect(state.posts).toBe(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  })
  test('rejection needs a reason and a lost reply rereads the decision', async ({ page }) => {
    const state = await fixture(page)
    await expect(page.getByRole('button', { name: 'Reject document', exact: true })).toBeDisabled()
    await page.getByLabel('Rejection reason', { exact: true }).fill('  Unreadable scan  ')
    state.loseReply = true
    await page.getByRole('button', { name: 'Reject document', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Reject document', exact: true }).click()
    await expect(page.getByText('Unreadable scan', { exact: false }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Reject document', exact: true })).toHaveCount(0)
    expect(state.posts).toBe(1)
    await expect(page.getByRole('alert')).toContainText('Could not confirm')
  })
  test('failed refresh hides decisions and switching owner resets the form', async ({ page }) => {
    const state = await fixture(page)
    await page.getByLabel('Rejection reason', { exact: true }).fill('Old owner text')
    state.failList = true
    await page.getByRole('button', { name: 'Refresh documents', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('Could not load')
    await expect(page.getByRole('button', { name: 'Approve document', exact: true })).toHaveCount(0)
    await expect(page.getByText('PRIVATE_INTERNAL_DETAIL')).toHaveCount(0)
    state.failList = false
    await page.getByLabel('Fleet category', { exact: true }).selectOption('VEHICLE')
    await expect(page.getByRole('option', { name: 'AA007BB - Skoda Octavia', exact: true })).toHaveCount(1)
    await page.getByLabel('Driver or vehicle', { exact: true }).selectOption('7')
    await expect(page.getByLabel('Rejection reason', { exact: true })).toHaveValue('')
  })
})

for (const width of [375, 1280]) {
  test(`review history shows recorded and unknown attribution without decision controls at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await fixture(page)
    await page.getByRole('button', { name: 'Approve document', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Approve document', exact: true }).click()
    await expect(page.getByText('Administrator #42', { exact: false })).toBeVisible()
    await expect(page.locator('time')).toHaveAttribute('datetime', '2026-11-03T09:00:00Z')
    await page.getByRole('button', { name: 'Show review history', exact: true }).click()
    await expect(page.getByText('Document version #10', { exact: true })).toBeVisible()
    await expect(page.getByText('Reviewer and time were not recorded for this older decision.', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Approve document', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Reject document', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Download document', exact: true })).toHaveCount(3)
    expect(state.posts).toBe(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    state.failList = true
    await page.getByRole('button', { name: 'Refresh documents', exact: true }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.locator('article')).toHaveCount(0)
    await expect(page.locator('body')).not.toContainText('PRIVATE_INTERNAL_DETAIL')
    state.failList = false
    await page.getByRole('button', { name: 'Show current documents', exact: true }).click()
    await expect(page.getByText('Document version #11', { exact: true })).toBeVisible()
    await expect(page.getByText('Document version #10', { exact: true })).toHaveCount(0)
  })
}
