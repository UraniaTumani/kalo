import {
  test,
  expect,
  SEEDED,
  apiLogin,
  seedSession,
  expectSignedIn,
  registerCustomer,
  loginThroughUi,
} from './support/fixtures'

/**
 * Pre-flight: A7, A8, A9, A10, A11, A13, A14, A15.
 *
 * Suspension is the only lever the platform has over someone misusing it, and
 * every test here that suspends somebody suspends an account it created for the
 * purpose. Suspending a seeded one would leave the next run unable to sign in —
 * which is exactly how the developer database ended up unusable.
 */
test.describe('Admin', () => {
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const tokens = await apiLogin(page.request, SEEDED.admin.phone, SEEDED.admin.password)
    await seedSession(context, tokens)
  })

  test('A15 · the rides list renders', async ({ page, guards }) => {
    void guards

    await page.goto('/admin/rides')
    await expectSignedIn(page)
    await expect(page.getByText(/rides/i).first()).toBeVisible()
  })

  test('A9 · users can be filtered by role', async ({ page, guards }) => {
    void guards

    await page.goto('/admin/users')

    // Scoped to the table: the signed-in admin's own phone is in the sidebar too.
    const table = page.locator('table')
    await expect(table).toBeVisible()

    /*
     * Asserted only after filtering. Every run registers throwaway customers and
     * the list is newest first, so whether the seeded admin happens to be on the
     * first page is a fact about how often this suite has run, not about the
     * filter.
     */
    await page.getByRole('combobox').first().selectOption('ADMIN')

    await expect(table.getByText(SEEDED.admin.phone)).toBeVisible()
    await expect(table.getByText(SEEDED.partner.phone)).toHaveCount(0)
  })

  test('A7 · suspending a company asks first and names it', async ({ page, guards }) => {
    void guards

    await page.goto('/admin/companies')
    await expect(page.getByText(/ABC Taxi/).first()).toBeVisible()

    await page.getByRole('button', { name: /^suspend$/i }).first().click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(/Taxi/)
    await expect(dialog).toContainText(/search/i)

    // Backed out — the company is untouched for the tests that follow.
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
  })

  test('A10, A11, A13 · a suspended user is locked out until reactivated', async ({
    page,
    context,
    guards,
  }) => {
    void guards

    const victim = await registerCustomer(page.request)

    await page.goto('/admin/users')

    const row = page.locator('tr', { hasText: victim.phone })
    await expect(row).toBeVisible()

    await row.getByRole('button', { name: /suspend/i }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(/signed out|sign in/i)

    await dialog.getByRole('button', { name: /suspend/i }).click()
    await expect(dialog).toBeHidden()

    await expect(row).toContainText(/suspended/i)

    /*
     * The lockout is the point, not the badge. A fresh browser, so nothing about
     * the admin's session is doing the work.
     */
    const visitor = await context.newPage()
    await loginThroughUi(visitor, victim.phone, victim.password)
    await expect(visitor.getByText(/invalid phone or password|authentication failed/i)).toBeVisible()
    await visitor.close()

    // Reactivating has no dialog: putting something back is not destructive.
    await row.getByRole('button', { name: /reactivate/i }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(row).toContainText(/active/i)

    const returning = await context.newPage()
    await loginThroughUi(returning, victim.phone, victim.password)
    await expect(returning.getByRole('button', { name: /sign out/i })).toBeVisible({ timeout: 15_000 })
    await returning.close()
  })

  test('A14 · an admin cannot suspend themselves', async ({ page, guards }) => {
    void guards

    await page.goto('/admin/users')

    /*
     * Filtered rather than hunted for: every run adds throwaway customers, and
     * the list is newest first, so the seeded admin drifts onto a later page.
     */
    await page.getByRole('combobox').first().selectOption('ADMIN')

    const own = page.locator('tr', { hasText: SEEDED.admin.phone })
    await expect(own).toBeVisible()

    await own.getByRole('button', { name: /suspend/i }).click()

    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: /suspend/i }).click()

    /*
     * A suspended admin cannot sign in to undo it, including on themselves. The
     * backend refuses; what matters here is that the screen says so instead of
     * appearing to work.
     */
    await expect(page.getByText(/cannot suspend your own account/i)).toBeVisible()
    await expect(own).toContainText(/active/i)
  })
})
