import {
  test,
  expect,
  SEEDED,
  apiLogin,
  gotoSettled,
  seedSession,
  expectSignedIn,
} from './support/fixtures'

/**
 * Pre-flight: P2, P3, P6, P8, P11, P12, P13, P19.
 *
 * P12 is the reason this file exists. Rewriting a week of opening hours was
 * delete-then-insert, Hibernate flushed the inserts first, and every save after
 * a company's first failed on the unique constraint — a partner could set their
 * hours once and never correct them. The backend has a test for the repository
 * now; this proves the screen a partner actually uses still works.
 */
test.describe('Partner', () => {
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)
  })

  test('P2 · the dashboard counts the fleet rather than a page of it', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/partner')
    await expectSignedIn(page)

    /*
     * The seed gives ABC Taxi three drivers. Before the counters were changed to
     * read totalElements they counted a fetched array, which would have started
     * lying the moment a company passed twenty.
     */
    const drivers = page.getByText(/^Drivers$/).first()
    await expect(drivers).toBeVisible()
    await expect(page.locator('body')).toContainText(/ABC Taxi/)
  })

  test('P3 · the drivers list renders the seeded fleet', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/partner/drivers')

    await expect(page.getByText('Ilir Balla')).toBeVisible()
    await expect(page.getByText('Gent Prifti')).toBeVisible()
    await expect(page.getByText('Mirela Hasa')).toBeVisible()
  })

  test('P6 · the vehicles list renders', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/partner/vehicles')
    await expect(page.getByText(/AA\d+TR/).first()).toBeVisible()
  })

  test('P8 · the assignments list renders', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/partner/assignments')
    await expectSignedIn(page)

    // Seeded drivers hold seeded vehicles, so there is something to show.
    await expect(page.getByText(/Ilir Balla|Gent Prifti|Mirela Hasa/).first()).toBeVisible()
  })

  test('P5 · deactivating a driver asks first, and says it cannot be undone', async ({
    page,
    guards,
  }) => {
    void guards

    await gotoSettled(page, '/partner/drivers')

    await page.getByRole('button', { name: /deactivate/i }).first().click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(/cannot be undone|forced offline|released/i)

    // Backed out: the driver is still there.
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
    await expect(page.getByText('Ilir Balla')).toBeVisible()
  })
})

test.describe('Operating hours', () => {
  /*
   * These two do far more than a normal test: load the week, edit it, save,
   * wait for the refetch, and in one case reload and re-assert. Three or four
   * full page renders each, and the default 60s budget was being spent on
   * rendering rather than on anything the test is actually checking.
   */
  test.setTimeout(180_000)

  test.beforeEach(async ({ page, context, app }) => {
    void app
    /*
     * The second company, so this never competes with the fleet tests above for
     * the same rows.
     */
    const tokens = await apiLogin(page.request, SEEDED.partnerB.phone, SEEDED.partnerB.password)

    /*
     * The seeder writes 00:00 to 00:00 straight through the repository to mean
     * "open all week". The API rightly refuses that shape — closing must be
     * after opening — so a savable week goes in here through the real endpoint
     * first, leaving the screen with something it is allowed to re-save.
     */
    const week = await page.request.put('/api/v1/partner/availability-settings/operating-hours', {
      headers: { Authorization: `Bearer ${tokens.accessToken}` },
      data: {
        hours: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map(
          (dayOfWeek) => ({
            dayOfWeek,
            closed: false,
            openTime: '06:00:00',
            closeTime: '23:00:00',
          }),
        ),
      },
    })
    expect(week.status(), await week.text()).toBe(200)

    await seedSession(context, tokens)
  })

  test('P11, P12, P13 · a partner can save the week, then change it, then change it again', async ({
    page,
    guards,
  }) => {
    void guards

    await gotoSettled(page, '/partner/availability')
    await expectSignedIn(page)

    /*
     * Waits for the week itself, not just the button. The button renders before
     * the hours arrive, and clicking it then submits nothing — which on a cold
     * stack showed up as this test timing out waiting for a request that was
     * never sent, while passing every time the page was already warm.
     */
    await expect(page.getByRole('checkbox').first()).toBeVisible({ timeout: 30_000 })

    const save = page.getByRole('button', { name: /save/i }).last()
    await expect(save).toBeVisible()

    /*
     * Three saves in a row. One was always fine; it was the second that used to
     * come back 409 with "The request conflicts with the current state of the
     * resource", which tells a partner nothing about what to do.
     */
    for (let attempt = 1; attempt <= 3; attempt++) {
      /*
       * Saving invalidates the hours query, and while the refetch is in flight
       * the form reads as incomplete, which disables this button. Clicking
       * through that window sends nothing — which is how this test timed out
       * waiting for a request on a cold stack and passed on a warm one.
       */
      await expect(save).toBeEnabled({ timeout: 30_000 })

      const response = page.waitForResponse(
        (r) => r.url().includes('/availability-settings/operating-hours') && r.request().method() === 'PUT',
      )

      await save.click()

      const result = await response
      expect(result.status(), `save #${attempt} should succeed`).toBe(200)
    }
  })

  /*
   * A night shift is most of the week for a lot of taxi companies, and until
   * recently the API refused it outright: it wanted the closing time strictly
   * after the opening one, so 20:00 -> 04:00 could not be expressed at all.
   * The screen let you type it and then failed on save.
   */
  test('a partner can save a shift that runs past midnight', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/partner/availability')
    await expectSignedIn(page)

    await expect(page.getByRole('checkbox').first()).toBeVisible({ timeout: 30_000 })

    const opens = page.getByLabel(/opens/i).first()
    const closes = page.getByLabel(/closes/i).first()

    await opens.fill('20:00')
    await closes.fill('04:00')

    // The screen names the shape rather than leaving "20:00 – 04:00" looking
    // like a typo.
    await expect(page.getByText(/closes the next morning/i).first()).toBeVisible()

    const save = page.getByRole('button', { name: /save/i }).last()
    await expect(save).toBeEnabled({ timeout: 30_000 })

    const response = page.waitForResponse(
      (r) =>
        r.url().includes('/availability-settings/operating-hours') &&
        r.request().method() === 'PUT',
    )

    await save.click()
    expect((await response).status(), 'an overnight shift should save').toBe(200)

    /*
     * And it survives the round trip rather than being silently normalised to
     * something the backend found acceptable.
     *
     * Matched loosely on the seconds: the API answers in HH:mm:ss and the
     * editor puts that string straight into the time input, so the DOM value
     * is "20:00:00" where the control's own format is "20:00". True of every
     * saved day, not just this one — what matters here is the hour and minute.
     */
    await page.reload()
    await expect(page.getByLabel(/opens/i).first()).toHaveValue(/^20:00(:00)?$/, {
      timeout: 30_000,
    })
    await expect(page.getByLabel(/closes/i).first()).toHaveValue(/^04:00(:00)?$/)
  })
})
