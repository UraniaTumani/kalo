import AxeBuilder from '@axe-core/playwright'
import { test, expect, SEEDED, apiLogin, seedSession } from './support/fixtures'
import type { Page } from '@playwright/test'

/**
 * An automated accessibility pass over every signed-in screen.
 *
 * axe finds a particular class of problem very well — unlabelled controls,
 * insufficient contrast, broken heading order, ARIA that does not resolve —
 * and is silent about everything that needs a human. So this is a floor, not a
 * certificate: the keyboard tests below it cover what axe cannot see.
 *
 * Scoped to WCAG 2.1 A and AA, which is the bar a public service is normally
 * held to.
 */

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

const ROUTES = {
  customer: ['/ride', '/ride/current', '/ride/history', '/support', '/profile'],
  partner: [
    '/partner',
    '/partner/rides',
    '/partner/drivers',
    '/partner/vehicles',
    '/partner/assignments',
    '/partner/documents',
    '/partner/settings',
    '/partner/availability',
  ],
  admin: [
    '/admin',
    '/admin/companies',
    '/admin/users',
    '/admin/rides',
    '/admin/support',
    '/admin/password-resets',
  ],
} as const

async function scan(page: Page, route: string) {
  await page.goto(route)

  await expect(page.getByRole('button', { name: /sign out/i })).toBeVisible({ timeout: 30_000 })

  /* Let the lists settle so axe sees the real screen, not a spinner. */
  await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 30_000 })

  const results = await new AxeBuilder({ page })
    .withTags([...TAGS])
    /*
     * Leaflet renders its own controls and attribution, which we do not own
     * and cannot fix from here. Excluded so its findings do not drown ours.
     */
    .exclude('.leaflet-container')
    .analyze()

  const serious = results.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  )

  const detail = serious
    .map(
      (violation) =>
        `\n  [${violation.impact}] ${violation.id}: ${violation.help}\n` +
        violation.nodes
          .slice(0, 3)
          .map((node) => `      ${node.html.slice(0, 120)}`)
          .join('\n'),
    )
    .join('')

  expect(serious, `${route} has serious accessibility violations:${detail}`).toEqual([])
}

for (const [role, routes] of Object.entries(ROUTES)) {
  test.describe(`axe · ${role}`, () => {
    test.setTimeout(180_000)

    test(`every ${role} screen passes WCAG A and AA`, async ({ page, context, app, guards }) => {
      void app
      void guards

      const credentials = SEEDED[role as keyof typeof SEEDED]
      const tokens = await apiLogin(page.request, credentials.phone, credentials.password)
      await seedSession(context, tokens)

      for (const route of routes) {
        await scan(page, route)
      }
    })
  })
}

test.describe('axe · signed out', () => {
  test('the login and registration screens pass WCAG A and AA', async ({ page, app, guards }) => {
    void app
    void guards

    for (const route of ['/login', '/register', '/forgot-password', '/reset-password', '/']) {
      await page.goto(route)
      await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 30_000 })

      const results = await new AxeBuilder({ page })
        .withTags([...TAGS])
        .exclude('.leaflet-container')
        .analyze()

      const serious = results.violations.filter(
        (v) => v.impact === 'serious' || v.impact === 'critical',
      )

      expect(
        serious,
        `${route}: ${serious.map((v) => `${v.id} — ${v.help}`).join('; ')}`,
      ).toEqual([])
    }
  })
})
