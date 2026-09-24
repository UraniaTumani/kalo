import { test, expect, SEEDED, apiLogin, seedSession } from './support/fixtures'
import type { Page } from '@playwright/test'

/**
 * The phone, and the promise that the phone is not an afterthought.
 *
 * Nothing automated covered small screens before this: every layout claim was
 * made by reading Tailwind classes, which is how a 20px overflow survived a
 * whole redesign — `1fr` grid tracks are `min-width: auto`, so a wide table
 * pushes its own container past the viewport and no amount of `overflow-x`
 * inside it helps. That is invisible in code review and obvious in one
 * measurement, which is what this file does.
 *
 * Desktop is checked alongside, because a fix for one width that breaks the
 * other is not a fix.
 */

const PHONE = { width: 375, height: 812 }
const DESKTOP = { width: 1280, height: 900 }

const CUSTOMER_ROUTES = ['/ride', '/ride/current', '/ride/history', '/support', '/profile']

const PARTNER_ROUTES = [
  '/partner',
  '/partner/rides',
  '/partner/drivers',
  '/partner/vehicles',
  '/partner/assignments',
  '/partner/documents',
  '/partner/settings',
  '/partner/availability',
  '/support',
]

const ADMIN_ROUTES = ['/admin', '/admin/companies', '/admin/users', '/admin/rides', '/admin/support']

/**
 * The body must never scroll sideways.
 *
 * Measured against documentElement rather than a screenshot: a horizontal
 * scrollbar is a number, and comparing numbers does not go stale the way an
 * image does. One pixel of slack absorbs sub-pixel rounding at fractional
 * device ratios; anything beyond that is a real overflow.
 */
async function expectNoHorizontalOverflow(page: Page, route: string) {
  const overflow = await page.evaluate(() => {
    const doc = document.documentElement
    return {
      scrollWidth: doc.scrollWidth,
      clientWidth: doc.clientWidth,
      /*
       * Named so a failure says which element to go and look at.
       *
       * Sorted by how far each one reaches, not by document order, and
       * elements inside their own horizontal scroll container are skipped:
       * a wide table inside `overflow-x: auto` is working as designed and
       * reporting it buries the element that is actually pushing the page.
       */
      widest: Array.from(document.querySelectorAll<HTMLElement>('body *'))
        .filter((el) => {
          if (el.getBoundingClientRect().right <= doc.clientWidth + 1) return false

          for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
            const overflowX = getComputedStyle(p).overflowX
            if (overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'hidden') {
              return false
            }
          }
          return true
        })
        .sort((a, b) => b.getBoundingClientRect().right - a.getBoundingClientRect().right)
        .slice(0, 4)
        .map((el) => {
          const r = el.getBoundingClientRect()
          return `<${el.tagName.toLowerCase()} class="${el.className?.toString().slice(0, 70)}"> reaches ${Math.round(r.right)}px (width ${Math.round(r.width)})`
        }),
    }
  })

  expect(
    overflow.scrollWidth,
    `${route} overflows horizontally by ${overflow.scrollWidth - overflow.clientWidth}px. ` +
      `Widest offenders: ${overflow.widest.join(' | ') || 'none identified'}`,
  ).toBeLessThanOrEqual(overflow.clientWidth + 1)
}

/** Waits for the shell, so a measurement is never taken mid-suspense. */
async function ready(page: Page) {
  await expect(page.getByRole('navigation').or(page.locator('aside')).first()).toBeVisible({
    timeout: 30_000,
  })
}

/**
 * Waits for a list screen to have actually finished loading its rows.
 *
 * `ready` only proves the shell is up, which is enough to measure the page but
 * not enough to assert that something is *absent*. Asserting "there is no
 * table here" while the query is still in flight passes for the wrong reason —
 * and did: the mobile-card test was green for three runs against a page that
 * renders a table, because the table had not arrived yet.
 *
 * Waiting for the spinner to go and for real content to appear makes the
 * absence mean what it says.
 */
async function gotoSettledList(page: Page, route: string, apiPath: string) {
  const loaded = page.waitForResponse(
    (response) => response.url().includes(apiPath) && response.status() === 200,
    { timeout: 30_000 },
  )

  await page.goto(route)
  await ready(page)

  /* The data is in the browser... */
  await loaded

  /* ...and React has finished putting it on the screen. */
  await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 30_000 })
}

function suite(
  role: 'customer' | 'partner' | 'admin',
  routes: string[],
  credentials: { phone: string; password: string },
) {
  test.describe(`${role} at 375px`, () => {
    test.use({ viewport: PHONE })

    /*
     * One test here visits up to nine routes, so it legitimately needs several
     * times the budget of a test that looks at one screen. The default 60s was
     * tight enough that a loaded machine failed it on the shell rather than on
     * a measurement.
     */
    test.setTimeout(180_000)

    test(`every ${role} page fits a phone screen`, async ({ page, context, app, guards }) => {
      void app
      void guards

      const tokens = await apiLogin(page.request, credentials.phone, credentials.password)
      await seedSession(context, tokens)

      for (const route of routes) {
        await page.goto(route)
        await ready(page)
        await expectNoHorizontalOverflow(page, route)
      }
    })
  })

  test.describe(`${role} on desktop`, () => {
    test.use({ viewport: DESKTOP })

    test.setTimeout(180_000)

    test(`every ${role} page fits a desktop screen`, async ({ page, context, app, guards }) => {
      void app
      void guards

      const tokens = await apiLogin(page.request, credentials.phone, credentials.password)
      await seedSession(context, tokens)

      for (const route of routes) {
        await page.goto(route)
        await ready(page)
        await expectNoHorizontalOverflow(page, route)
      }
    })
  })
}

suite('customer', CUSTOMER_ROUTES, SEEDED.customer)
suite('partner', PARTNER_ROUTES, SEEDED.partner)
suite('admin', ADMIN_ROUTES, SEEDED.admin)

/**
 * The schedule editor's row, measured rather than assumed.
 *
 * A generic page-overflow check caught this once and I fixed the wrong thing:
 * the grid looked guilty, so the grid got changed, the suite went green on one
 * run, and the real cause — a row forbidden from wrapping inside a column too
 * narrow for it — was still there. This asserts the row itself fits its
 * container, which is the fact that was false.
 */
test.describe('The weekly schedule editor fits its column', () => {
  for (const [name, viewport] of [
    ['desktop', DESKTOP],
    ['phone', PHONE],
  ] as const) {
    test(`every day row fits on ${name}`, async ({ page, context, app, guards }) => {
      void app
      void guards

      await page.setViewportSize(viewport)

      const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
      await seedSession(context, tokens)

      await page.goto('/partner/availability')
      await ready(page)

      await expect(page.getByRole('checkbox').first()).toBeVisible({ timeout: 30_000 })

      const spill = await page.evaluate(() => {
        const rows = Array.from(
          document.querySelectorAll<HTMLElement>('input[type="time"]'),
        ).map((input) => input.closest('div.flex') as HTMLElement | null)

        return rows
          .filter((row): row is HTMLElement => row !== null)
          .map((row) => ({
            width: Math.round(row.getBoundingClientRect().width),
            needs: row.scrollWidth,
          }))
          .filter((row) => row.needs > row.width + 1)
      })

      expect(
        spill,
        'a schedule row needs more width than its column gives it',
      ).toEqual([])

      await expectNoHorizontalOverflow(page, `/partner/availability at ${name}`)
    })
  }
})

test.describe('Phone layout behaviour', () => {
  test.use({ viewport: PHONE })

  /**
   * The tables become cards below `sm`, and only one of the two renders —
   * `sm:hidden` / `hidden sm:block` leaves every row in the document twice,
   * which is what broke two partner tests once. Asserting there is no table is
   * asserting that the hook, not the class pair, is doing the work.
   */
  test('wide tables become cards rather than a sideways scroll', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    /*
     * Four routes, each waited on until its rows have settled, against a
     * default budget sized for one. gotoSettledList spends up to thirty
     * seconds apiece on the shell, the response and the spinner, so on a busy
     * machine the loop can pass sixty before it has finished looking — and the
     * run then reports a timeout rather than anything about tables or cards.
     * That is what happened in an eighteen-minute suite sharing eight cores
     * with Postgres and the backend; alone the same test takes five seconds.
     */
    test.slow()

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    const lists = [
      ['/partner/drivers', '/api/v1/partner/drivers'],
      ['/partner/vehicles', '/api/v1/partner/vehicles'],
      ['/partner/assignments', '/api/v1/partner/driver-vehicle-assignments'],
      ['/partner/rides', '/api/v1/partner/rides'],
    ] as const

    for (const [route, api] of lists) {
      await gotoSettledList(page, route, api)

      /*
       * Only meaningful once the rows are on the screen. This assertion used
       * to run against a list that was still loading, so "no table" was true
       * for the wrong reason and stayed green for three runs against a page
       * that renders one.
       */
      await expect(
        page.locator('table'),
        `${route} should render cards on a phone, not a table`,
      ).toHaveCount(0)
    }
  })

  test('the admin lists become cards too', async ({ page, context, app, guards }) => {
    void app
    void guards

    /* Same shape as the partner loop above, and the same reason. */
    test.slow()

    const tokens = await apiLogin(page.request, SEEDED.admin.phone, SEEDED.admin.password)
    await seedSession(context, tokens)

    for (const route of [
      '/admin/users',
      '/admin/companies',
      '/admin/rides',
      '/admin/password-resets',
    ]) {
      await page.goto(route)
      await ready(page)

      await expect(page.locator('table'), `${route} should render cards on a phone`).toHaveCount(0)
    }
  })

  /**
   * A dialog that overflows a phone is a dialog whose confirm button cannot be
   * reached, which on a destructive action is worse than not offering it.
   */
  test('a destructive dialog is fully usable on a phone', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.admin.phone, SEEDED.admin.password)
    await seedSession(context, tokens)

    await page.goto('/admin/companies')
    await ready(page)

    await page.getByRole('button', { name: /^suspend$/i }).first().click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const box = await dialog.boundingBox()
    expect(box, 'the dialog should have a measurable box').not.toBeNull()
    expect(box!.width, 'the dialog is wider than the phone').toBeLessThanOrEqual(375)
    expect(box!.x, 'the dialog starts off the left edge').toBeGreaterThanOrEqual(-1)

    /* Both choices have to be reachable, not just present in the DOM. */
    for (const name of [/keep/i, /suspend/i]) {
      const button = dialog.getByRole('button', { name }).first()
      await expect(button).toBeVisible()
      await expect(button).toBeInViewport()
    }

    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()

    await expectNoHorizontalOverflow(page, '/admin/companies with a dialog open')
  })

  /**
   * The sidebar becomes a scrolling strip of tabs rather than hiding behind a
   * menu button, so a partner watching for incoming rides never has to open
   * anything. Every destination must still be reachable.
   */
  test('navigation stays reachable without opening a menu', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    await page.goto('/partner')
    await ready(page)

    for (const label of [/^rides$/i, /^drivers$/i, /^vehicles$/i, /help & support/i]) {
      await expect(page.getByRole('link', { name: label }).first()).toBeVisible()
    }

    /* And one of them actually navigates from the strip. */
    await page.getByRole('link', { name: /^drivers$/i }).first().click()
    await expect(page).toHaveURL(/\/partner\/drivers/)
  })

  test('the booking form is usable on a phone', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await page.goto('/ride')
    await ready(page)

    const pickup = page.getByLabel(/^pickup/i)
    await expect(pickup).toBeVisible()
    await expect(pickup).toBeInViewport()

    const box = await pickup.boundingBox()
    expect(box!.width, 'the pickup field is wider than the screen').toBeLessThanOrEqual(375)
  })
})
