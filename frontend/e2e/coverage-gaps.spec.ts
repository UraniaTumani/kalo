import {
  test,
  expect,
  SEEDED,
  apiLogin,
  seedSession,
  expectSignedIn,
} from './support/fixtures'

/**
 * Screens and flows the browser suite had never opened.
 *
 * The backend covers most of this logic thoroughly, so these are deliberately
 * shallow: they prove each screen renders against the real API, in the real
 * shell, with no console error and no 5xx — the `guards` fixture asserts both
 * on every test here. That is the class of failure a backend test cannot see,
 * and the reason a page that returns perfect JSON can still be blank.
 */

test.describe('Partner screens with no previous coverage', () => {
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
  })

  test('the documents page renders the company paperwork', async ({ page, guards }) => {
    void guards

    await page.goto('/partner/documents')
    await expectSignedIn(page)

    await expect(page.getByRole('heading', { name: /documents/i }).first()).toBeVisible()
  })

  test('the settings page loads the company profile into the form', async ({ page, guards }) => {
    void guards

    await page.goto('/partner/settings')
    await expectSignedIn(page)

    /*
     * Asserted on a filled field rather than the heading: the form renders
     * before the profile arrives, so an empty input here would mean the read
     * failed while the page still looked fine.
     */
    const legalName = page.getByLabel(/legal name/i)
    await expect(legalName).toBeVisible({ timeout: 30_000 })
    await expect(legalName).not.toHaveValue('')
  })

  test('the ride queue renders and separates waiting requests', async ({ page, guards }) => {
    void guards

    await page.goto('/partner/rides')
    await expectSignedIn(page)

    await expect(page.getByRole('combobox').first()).toBeVisible({ timeout: 30_000 })

    /* The status filter is what makes the queue workable once it has volume. */
    await page.getByRole('combobox').first().selectOption('REQUESTED')
    await expect(page).toHaveURL(/\/partner\/rides/)
  })

  test('a partner reaches Help & Support from their own navigation', async ({ page, guards }) => {
    void guards

    await page.goto('/partner')
    await expectSignedIn(page)

    await page.getByRole('link', { name: /help & support/i }).first().click()

    await expect(page).toHaveURL(/\/support/)
    await expect(page.getByRole('heading', { name: /common questions/i })).toBeVisible()

    /* The FAQ is role-aware: a partner is shown partner questions. */
    await expect(page.getByText(/verification take/i).first()).toBeVisible()
  })
})

test.describe('Admin screens with no previous coverage', () => {
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const tokens = await apiLogin(page.request, SEEDED.admin.phone, SEEDED.admin.password)
    await seedSession(context, tokens)
  })

  test('the verification queue renders and opens a company for review', async ({
    page,
    guards,
  }) => {
    void guards

    await page.goto('/admin')
    await expectSignedIn(page)

    await expect(page.getByRole('heading', { name: /partner verification/i })).toBeVisible()

    /*
     * The queue is empty whenever the seeded companies are already approved,
     * which is the normal state — so the empty state is as valid an outcome as
     * a row, and both are asserted rather than assuming one.
     */
    const review = page.getByRole('button', { name: /^review$/i }).first()
    const empty = page.getByText(/nothing to review|no companies/i).first()

    await expect(review.or(empty)).toBeVisible({ timeout: 30_000 })

    if (await review.isVisible().catch(() => false)) {
      await review.click()

      /* Rejecting is gated behind a reason, so the button starts unusable. */
      const reject = page.getByRole('button', { name: /^reject$/i })
      await expect(reject).toBeVisible()
      await expect(reject).toBeDisabled()

      await page.getByLabel(/reason/i).fill('E2E check — not a real rejection')
      await expect(reject).toBeEnabled()

      /* Left unclicked: this suite must not reject a seeded company. */
      await page.getByRole('button', { name: /^close$/i }).first().click()
    }
  })

  test('the users list pages through rather than showing everything at once', async ({
    page,
    guards,
  }) => {
    void guards

    await page.goto('/admin/users')
    await expectSignedIn(page)

    await expect(page.locator('table')).toBeVisible({ timeout: 30_000 })

    const next = page.getByRole('button', { name: /next/i })

    /*
     * Pagination only appears past one page, and how many users exist depends
     * on how often this suite has run. Both outcomes are legitimate; what must
     * hold is that when the control is there it works and the range moves.
     */
    if (await next.isVisible().catch(() => false)) {
      const range = page.getByText(/showing/i).first()
      const before = await range.textContent()

      await next.click()

      await expect(range).not.toHaveText(before ?? '', { timeout: 15_000 })
      await expect(page.getByRole('button', { name: /previous/i })).toBeEnabled()
    }
  })

  test('the rides list filters by status', async ({ page, guards }) => {
    void guards

    await page.goto('/admin/rides')
    await expectSignedIn(page)

    const filter = page.getByRole('combobox').first()
    await expect(filter).toBeVisible({ timeout: 30_000 })

    await filter.selectOption('COMPLETED')

    /* The page must survive a filter that matches nothing. */
    await expect(page.getByRole('heading', { name: /rides/i }).first()).toBeVisible()
  })

  test('the support queue filters by status', async ({ page, guards }) => {
    void guards

    await page.goto('/admin/support')
    await expectSignedIn(page)

    const filter = page.getByRole('combobox').first()
    await expect(filter).toBeVisible({ timeout: 30_000 })

    await filter.selectOption('RESOLVED')
    await expect(page.getByRole('heading', { name: /help & support/i }).first()).toBeVisible()
  })
})

test.describe('Customer rating', () => {
  /**
   * The rating form, opened from history.
   *
   * Whether a ratable ride exists depends on the seed and on what earlier tests
   * completed, so this asserts the control's behaviour where it is offered
   * rather than manufacturing a completed ride through the UI — which would
   * take a full booking, acceptance and completion across two roles for a form
   * the backend already covers in five tests.
   */
  test('history offers a rating and the form opens', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await page.goto('/ride/history')
    await expectSignedIn(page)

    const rate = page.getByRole('button', { name: /^rate$/i }).first()

    if (await rate.isVisible().catch(() => false)) {
      await rate.click()

      await expect(page.getByText(/rate this ride/i).first()).toBeVisible()
      await expect(
        page.getByRole('button', { name: /submit rating/i }),
      ).toBeVisible()

      /* Closed rather than submitted: a rating cannot be undone. */
      await page.getByRole('button', { name: /^close$/i }).first().click()
    } else {
      /* No completed ride to rate is a legitimate state; the page still works. */
      await expect(page.getByRole('heading', { name: /history/i }).first()).toBeVisible()
    }
  })
})

test.describe('Geocoding goes through KALO', () => {
  /**
   * The frontend must never call a third-party geocoder itself.
   *
   * Doing so would leak every passenger's pickup address, with their IP, to a
   * service KALO does not control and cannot hold to a privacy policy. The
   * provider lives behind /api/v1/geocoding so the only party talking to it is
   * the server.
   */
  test('typing an address never reaches a third-party geocoder', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const thirdParty: string[] = []

    page.on('request', (request) => {
      const url = request.url()

      /*
       * Only requests that leave KALO's own origin count. The app's own
       * /api/v1/geocoding endpoint is exactly what should be called — it is
       * the abstraction that keeps the provider server-side — so matching on
       * the word "geocoding" alone flags the correct behaviour as a failure.
       */
      let host: string
      try {
        host = new URL(url).host
      } catch {
        return
      }

      if (host === new URL(page.url()).host) return

      /* Map tiles are a separate concern; this is about address lookups. */
      if (/tile\./i.test(url)) return

      if (/nominatim|openstreetmap|mapbox|google|geocod|photon|pelias/i.test(url)) {
        thirdParty.push(url)
      }
    })

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await page.goto('/ride')
    await expectSignedIn(page)

    await page.getByLabel(/^pickup/i).fill('Rruga e Kavajes')
    await expect(page.getByRole('option').first()).toBeVisible({ timeout: 30_000 })

    expect(
      thirdParty,
      'the browser must not call a geocoding provider directly',
    ).toEqual([])
  })
})
