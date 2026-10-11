import { test, expect, SEEDED, STORAGE, apiLogin, seedSession, readStorage } from './support/fixtures'

/**
 * Pre-flight: C2, C3, G3.
 *
 * The app is Albanian first. The failure this guards against is the one that
 * actually happened here: a switch that changed the shell but left the content
 * in the other language, which looks fine on the screen somebody was testing.
 */
test.describe('Language', () => {
  test('C2 · switching changes the navigation and the page content together', async ({
    page,
    context,
    guards,
  }) => {
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens, 'sq')

    await page.goto('/ride')

    // Albanian first, both halves of the screen.
    await expect(page.getByRole('link', { name: 'Rezervo taksi' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Rezervo taksi' })).toBeVisible()

    await page.getByRole('button', { name: 'EN', exact: true }).click()

    /*
     * Both assertions matter. Checking only the sidebar is how a half-translated
     * app passes a manual test.
     */
    await expect(page.getByRole('link', { name: 'Book a taxi' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Book a taxi' })).toBeVisible()

    await expect(page.getByText('Rezervo taksi')).toHaveCount(0)
  })

  test('C3 · the choice survives a reload', async ({ page, context, guards }) => {
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens, 'sq')

    await page.goto('/ride')
    await page.getByRole('button', { name: 'EN', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Book a taxi' })).toBeVisible()

    expect(await readStorage(page, STORAGE.language)).toBe('en')
  })

  test('the document language attribute follows the switch', async ({ page, context, guards }) => {
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens, 'sq')

    await page.goto('/ride')
    await page.getByRole('button', { name: 'EN', exact: true }).click()

    // Screen readers and hyphenation read this, not the toggle.
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  })

  test('G3 · no raw translation keys reach the screen', async ({ page, context, guards }) => {
    void guards

    /*
     * Seven screens in one test, each lazily loaded and compiled on first visit.
     * Worth the time — this is the sweep that would have caught the half-Albanian
     * app — but it needs more than the default budget on a cold dev server.
     */
    test.slow()

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens, 'sq')

    /*
     * A missing entry shows as `partner.someKey` rather than a sentence. Walking
     * the partner screens in Albanian is where that surfaces, since Albanian is
     * the translation most likely to fall behind.
     */
    const screens = [
      '/partner',
      '/partner/drivers',
      '/partner/vehicles',
      '/partner/assignments',
      '/partner/documents',
      '/partner/settings',
      '/partner/availability',
    ]

    const keyShaped = /\b(common|nav|auth|partner|admin|ride|booking|errors|confirm|validation)\.[a-zA-Z]+/

    for (const screen of screens) {
      await page.goto(screen)

      /*
       * Not `networkidle`: the dashboard polls for open rides, so the network
       * never goes quiet and the wait would sit there until the test times out.
       * The navigation rendering is the honest signal that the screen is up.
       */
      /*
       * A generous wait, once per screen: these routes are lazily loaded, and on
       * a cold dev server the first visit to each one pays for compiling it.
       * The signed-in partner's name is on every screen and is not translated,
       * which makes it the one reliable marker in a test about translation.
       */
      await expect(page.getByText('Arben Marku').first()).toBeVisible({ timeout: 30_000 })

      const text = await page.locator('body').innerText()
      const leaked = text.match(keyShaped)

      expect(leaked, `untranslated key on ${screen}: ${leaked?.[0]}`).toBeNull()
    }
  })

  /**
   * F39 · a refused rule reads in the customer's language.
   *
   * The backend answers a refused business rule with a finished English
   * sentence, and the frontend used to show it as-is — so an Albanian-speaking
   * customer was told "Pickup and destination cannot be the same" in English,
   * inside an otherwise Albanian screen. That was a documented trade-off, and
   * the scale is what made it a finding: the server throws such a sentence in
   * roughly 126 places.
   *
   * This covers the subset a customer can reach. The rule used here is chosen
   * because exactly ONE applies: RideSearchServiceImpl compares the two
   * coordinate pairs and refuses when they are identical, so picking the same
   * address twice reaches that rule and no other. A failure whose message
   * depends on which of several rules fires first would make this test about
   * validation ordering rather than about translation.
   */
  test('F39 · a backend rule refusal is shown in Albanian, not English', async ({
    page,
    context,
    guards,
  }) => {
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens, 'sq')

    await page.goto('/ride')

    /* The same place twice, which is what the rule refuses. */
    await page.getByLabel(/^vendi i nisjes/i).fill('Rruga e Kavajes')
    await page.getByRole('option').first().click()

    await page.getByLabel(/^destinacioni/i).fill('Rruga e Kavajes')
    await page.getByRole('option').first().click()

    const refused = page.waitForResponse(
      (r) => r.url().includes('/api/v1/rides/search') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: 'Kërko taksi të disponueshme' }).click()

    const response = await refused
    expect(response.status(), 'the rule must refuse this search').toBe(400)
    expect(
      (await response.text()).includes('Pickup and destination cannot be the same'),
      'the server is expected to answer in English — that is the premise of this test',
    ).toBeTruthy()

    /*
     * Both halves. The Albanian sentence present, and the English one the
     * server actually sent absent from the page — either alone would pass for
     * a screen that showed both, or neither.
     */
    const alert = page.getByRole('alert')
    await expect(alert).toContainText('Nisja dhe destinacioni nuk mund të jenë i njëjti vend.')
    await expect(page.getByText('Pickup and destination cannot be the same')).toHaveCount(0)
  })

})
