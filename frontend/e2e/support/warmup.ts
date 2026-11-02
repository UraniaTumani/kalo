import { chromium, request as playwrightRequest, type FullConfig } from '@playwright/test'
import { SEEDED, STORAGE } from './fixtures'

/**
 * Visits every route once before the suite starts.
 *
 * Each route is lazily loaded, and the dev server compiles a route the first
 * time a browser asks for it — several seconds, once. Without this the cost
 * lands on whichever test happens to reach a screen first, which is a different
 * test on every run: four cold runs here failed four different tests, none of
 * them for a reason that had anything to do with what they were testing.
 *
 * Paying it here makes the suite honest about what it is measuring, and the
 * total run time barely moves — the work happens either way.
 */
async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'http://localhost:5174'

  const api = await playwrightRequest.newContext({ baseURL })

  async function tokensFor(phone: string, password: string) {
    const res = await api.post('/api/v1/auth/login', { data: { phone, password } })
    if (!res.ok()) throw new Error(`warm-up could not sign in as ${phone}: ${res.status()}`)
    return res.json() as Promise<{ accessToken: string; refreshToken: string }>
  }

  const [customer, partner, admin] = await Promise.all([
    tokensFor(SEEDED.customer.phone, SEEDED.customer.password),
    tokensFor(SEEDED.partner.phone, SEEDED.partner.password),
    tokensFor(SEEDED.admin.phone, SEEDED.admin.password),
  ])

  // The disposable browser fixture must meet the same evidence policy as real fleets.
  // Upload and review real files through the APIs; never disable the policy in tests.
  const adminHeaders = { Authorization: `Bearer ${admin.accessToken}` }
  for (const account of [SEEDED.partner, SEEDED.partnerB]) {
    const tokens = account === SEEDED.partner ? partner : await tokensFor(account.phone, account.password)
    const headers = { Authorization: `Bearer ${tokens.accessToken}` }
    const profile = await api.get('/api/v1/partner/me', { headers })
    if (!profile.ok()) throw new Error(`Fleet fixture profile failed: ${profile.status()}`)
    const { companyId } = await profile.json()
    for (const owner of ['drivers', 'vehicles'] as const) {
      const owners = await api.get(`/api/v1/partner/${owner}?size=100`, { headers })
      if (!owners.ok()) throw new Error(`Fleet fixture owner list failed: ${owners.status()}`)
      for (const item of (await owners.json()).content) {
        const base = `/api/v1/partner/${owner}/${item.id}/documents`
        const listed = await api.get(base, { headers })
        if (!listed.ok()) throw new Error(`Fleet fixture documents failed: ${listed.status()}`)
        const current = await listed.json()
        for (const documentType of owner === 'drivers' ? ['DRIVING_LICENSE'] : ['VEHICLE_REGISTRATION', 'INSURANCE', 'TECHNICAL_INSPECTION']) {
          let document = current.find((d: { documentType: string }) => d.documentType === documentType)
          if (!document) {
            const upload = await api.post(`${base}/upload`, { headers, multipart: { documentType, expiresAt: '2099-12-31',
              file: { name: 'e2e-evidence.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\ne2e fixture evidence\n%%EOF') } } })
            if (upload.status() !== 201) throw new Error(`Fleet fixture upload failed: ${upload.status()}`)
            document = await upload.json()
          }
          if (document.verificationStatus === 'APPROVED') continue
          const reviewed = await api.post(`/api/v1/admin/partners/${companyId}/fleet/${owner === 'drivers' ? 'DRIVER' : 'VEHICLE'}/${item.id}/documents/${document.id}/approve`, { headers: adminHeaders })
          if (!reviewed.ok()) throw new Error(`Fleet fixture approval failed: ${reviewed.status()}`)
        }
      }
    }
  }

  await api.dispose()

  const browser = await chromium.launch({ channel: process.env.E2E_CHANNEL ?? 'chrome' })

  try {
    for (const [tokens, routes] of [
      [customer, ['/ride', '/ride/current', '/ride/history', '/support', '/profile']],
      [
        partner,
        [
          '/partner',
          '/partner/rides',
          '/partner/drivers',
          '/partner/vehicles',
          '/partner/assignments',
          '/partner/documents',
          '/partner/settings',
          '/partner/availability',
          '/support',
        ],
      ],
      [admin, ['/admin', '/admin/companies', '/admin/users', '/admin/rides', '/admin/support']],
    ] as const) {
      const context = await browser.newContext({ baseURL })

      await context.addInitScript(
        ([keys, values]) => {
          try {
            localStorage.setItem(keys.token, values.accessToken)
            localStorage.setItem(keys.refresh, values.refreshToken)
            localStorage.setItem(keys.language, 'en')
          } catch {
            /* private window */
          }
        },
        [STORAGE, tokens] as const,
      )

      const page = await context.newPage()

      for (const route of routes) {
        // Failures here are not the suite's business: a route that will not load
        // is something the tests themselves should report, with their own names.
        await page.goto(route, { waitUntil: 'domcontentloaded' }).catch(() => {})
        await page.waitForTimeout(250)
      }

      await context.close()
    }

    // The login and registration screens, which no signed-in session reaches.
    const anonymous = await browser.newContext({ baseURL })
    const page = await anonymous.newPage()
    for (const route of ['/login', '/register']) {
      await page.goto(route, { waitUntil: 'domcontentloaded' }).catch(() => {})
    }
    await anonymous.close()
  } finally {
    await browser.close()
  }
}

export default globalSetup
