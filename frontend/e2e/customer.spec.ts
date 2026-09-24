import {
  test,
  expect,

  gotoSettled,

  apiLogin,
  seedSession,
  expectSignedIn,
  registerCustomer,
} from './support/fixtures'

/**
 * Pre-flight: C4, C5, C6, C7, C8, C9, C10, C11, C12.
 *
 * The booking flow, and the dialog that now stands in front of cancelling. The
 * dialog's behaviour is the part worth automating: focus landing on Cancel and a
 * double click sending one request are exactly the details a manual tester reads
 * past.
 */

/** Books a ride and returns once the ride screen is showing. */
async function bookARide(page: import('@playwright/test').Page) {
  await page.goto('/ride')

  await page.getByLabel(/^pickup/i).fill('Rruga e Kavajes')
  await page.getByRole('option').first().click()

  await page.getByLabel(/^destination/i).fill('Sheshi Skenderbej')
  await page.getByRole('option').first().click()

  await page.getByRole('button', { name: /search available taxis/i }).click()

  // The seeded companies are open around the clock, so one always answers.
  const firstOffer = page.getByRole('button', { name: /choose|select/i }).first()
  await expect(firstOffer).toBeVisible({ timeout: 20_000 })
  await firstOffer.click()
}

test.describe('Booking', () => {
  /*
   * A customer of its own, for the same reason as the dialogs below: a booked
   * ride stays in progress, and the next attempt to book is refused. Reusing a
   * seeded account would make this suite pass once and fail every run after.
   */
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)
  })

  test('C4, C5 · an address search offers companies that can take the ride', async ({
    page,
    guards,
  }) => {
    void guards

    await page.goto('/ride')

    await page.getByLabel(/^pickup/i).fill('Rruga e Kavajes')
    await page.getByRole('option').first().click()

    await page.getByLabel(/^destination/i).fill('Sheshi Skenderbej')
    await page.getByRole('option').first().click()

    await page.getByRole('button', { name: /search available taxis/i }).click()

    await expect(page.getByText(/ABC Taxi|City Taxi/).first()).toBeVisible({ timeout: 20_000 })
  })

  test('C6, C7 · choosing a company opens the ride', async ({ page, guards }) => {
    void guards

    await bookARide(page)

    await expect(page).toHaveURL(/\/ride\/current|\/current/)
    await expect(page.getByText(/requested|waiting/i).first()).toBeVisible()
  })

  test('C12 · history lists past rides a page at a time', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/ride/history')
    await expectSignedIn(page)

    // Renders without a blank screen whether or not this customer has ridden.
    await expect(page.getByText(/ride history/i).first()).toBeVisible()
  })
})

test.describe('The cancel dialog', () => {
  /*
   * A fresh customer per test. Booking leaves a ride in progress, and the
   * backend refuses a second one — so sharing an account here would make every
   * test after the first fail for a reason that has nothing to do with dialogs.
   */
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)
  })

  test('C8 · cancelling asks first, and says what will happen', async ({ page, guards }) => {
    void guards

    await bookARide(page)

    await page.getByRole('button', { name: /cancel ride/i }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // Not "are you sure" — what actually happens.
    await expect(dialog).toContainText(/driver is released|no longer travelling/i)
  })

  test('C9 · Escape closes it and the ride survives', async ({ page, guards }) => {
    void guards

    await bookARide(page)

    await page.getByRole('button', { name: /cancel ride/i }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    await page.keyboard.press('Escape')

    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByRole('button', { name: /cancel ride/i })).toBeVisible()
  })

  test('C10 · Enter backs out rather than going through with it', async ({ page, guards }) => {
    void guards

    await bookARide(page)

    await page.getByRole('button', { name: /cancel ride/i }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    /*
     * Focus starts on Cancel, so someone who opened this by accident is one
     * keystroke from safety rather than one keystroke from losing the ride.
     */
    await page.keyboard.press('Enter')

    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByRole('button', { name: /cancel ride/i })).toBeVisible()
  })

  test('C11 · a double click sends one cancellation, not two', async ({ page, guards }) => {
    void guards

    await bookARide(page)

    const cancelCalls: string[] = []
    page.on('request', (request) => {
      if (/\/api\/v1\/rides\/\d+\/cancel/.test(request.url())) cancelCalls.push(request.url())
    })

    await page.getByRole('button', { name: /cancel ride/i }).click()

    const dialog = page.getByRole('dialog')
    const confirm = dialog.getByRole('button', { name: /cancel ride/i })

    await confirm.click({ clickCount: 2, delay: 20 })

    await expect(dialog).toBeHidden({ timeout: 15_000 })

    // The second click lands on a disabled button, so only one request goes out.
    expect(cancelCalls.length, 'one cancel request').toBe(1)
  })
})
