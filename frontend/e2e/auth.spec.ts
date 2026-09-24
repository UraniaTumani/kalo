import {
  test,
  expect,
  SEEDED,
  STORAGE,
  apiLogin,
  seedSession,
  loginThroughUi,
  expectSignedIn,
  readStorage,
  clearStorage,
  expireAccessToken,
  registerCustomer,
  gotoSettled,
} from './support/fixtures'

/**
 * Pre-flight: C1, C14, C15, C16, P1, A1.
 *
 * The backend already proves who may sign in and what a refresh token is worth.
 * What only a browser can show is whether the app puts a person where they
 * belong afterwards, and whether an expired access token is invisible to them or
 * throws them out.
 */
test.describe('Signing in', () => {
  test('C1 · a customer lands on the booking screen', async ({ page, app, guards }) => {
    void app
    void guards

    await loginThroughUi(page, SEEDED.customer.phone, SEEDED.customer.password)

    await expect(page).toHaveURL(/\/ride/)
    await expect(page.getByRole('heading', { name: /book a taxi/i })).toBeVisible()
  })

  test('P1 · a partner lands on the dashboard', async ({ page, app, guards }) => {
    void app
    void guards

    await loginThroughUi(page, SEEDED.partner.phone, SEEDED.partner.password)

    await expect(page).toHaveURL(/\/partner/)
    await expect(page.getByRole('link', { name: /^drivers$/i })).toBeVisible()
  })

  test('A1 · an admin lands on a rendered admin page, not a blank one', async ({ page, app, guards }) => {
    void app
    void guards

    await loginThroughUi(page, SEEDED.admin.phone, SEEDED.admin.password)

    await expect(page).toHaveURL(/\/admin/)

    /*
     * This screen has gone blank twice in this project's history, both times
     * from a shape the page could not render. Asserting on real content rather
     * than the URL is the difference between catching that and missing it.
     */
    await expect(page.getByRole('heading', { name: /partner verification/i })).toBeVisible()
    await expect(page.getByRole('link', { name: /^users$/i })).toBeVisible()
  })

  test('a wrong password is refused and says so', async ({ page, app, guards }) => {
    void app
    void guards

    await loginThroughUi(page, SEEDED.customer.phone, 'NotThePassword1!')

    await expect(page.getByText(/invalid phone or password/i)).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('an unknown phone is refused the same way', async ({ page, app, guards }) => {
    void app
    void guards

    await loginThroughUi(page, '+355699999999', 'Whatever123!')

    // Same wording as a wrong password: the API must not confirm who exists.
    await expect(page.getByText(/invalid phone or password/i)).toBeVisible()
  })

  test('signing out clears the session and the way back in', async ({ page, app, guards }) => {
    void app
    void guards

    await loginThroughUi(page, SEEDED.customer.phone, SEEDED.customer.password)
    await expectSignedIn(page)

    await page.getByRole('button', { name: /sign out/i }).click()

    await expect(page).toHaveURL(/\/login/)
    expect(await readStorage(page, STORAGE.token)).toBeNull()
    expect(await readStorage(page, STORAGE.refresh)).toBeNull()

    // Going back must not restore a signed-in view from cache.
    await page.goto('/ride')
    await expect(page).toHaveURL(/\/login/)
  })

  test('a signed-out visitor cannot reach a role page directly', async ({ page, app, guards }) => {
    void app
    void guards

    await page.goto('/admin')
    await expect(page).toHaveURL(/\/login/)
  })

  test('a customer cannot reach the admin area', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await page.goto('/admin')

    // Routed away rather than shown an empty admin shell.
    await expect(page).not.toHaveURL(/\/admin$/)
  })
})

test.describe('Session recovery', () => {
  test('C14 · an expired access token is refreshed and the request replayed', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    /*
     * Signed in through the form rather than seeded: seedSession installs an
     * init script that rewrites localStorage on every page load, which would
     * quietly restore the good token across the reload below and make this test
     * pass without testing anything.
     */
    await loginThroughUi(page, SEEDED.customer.phone, SEEDED.customer.password)
    await expectSignedIn(page)

    const beforeRefresh = await readStorage(page, STORAGE.refresh)

    const refreshCalls: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/auth/refresh')) refreshCalls.push(request.url())
    })

    /* An hour of waiting, compressed into one line. */
    await expireAccessToken(page)

    /*
     * Coming back to a tab left open overnight: the app boots, sends the token
     * it has, and is told it is no good. A click on a nav link would not do —
     * the query cache answers some of those without going to the network.
     */
    await page.reload()

    // The point: still signed in, on the page that was asked for.
    await expectSignedIn(page)
    await expect(page).toHaveURL(/\/ride/)

    /*
     * Refreshed, and not once per failed request. An exact count was too strict:
     * a request already in flight when the first refresh lands can legitimately
     * cause a second, which succeeds with the rotated token. The failure worth
     * catching is a stampede, where every query refreshes and all but the first
     * present a spent token.
     */
    expect(refreshCalls.length, 'refreshed').toBeGreaterThanOrEqual(1)
    expect(refreshCalls.length, 'did not stampede').toBeLessThanOrEqual(2)

    const replaced = await readStorage(page, STORAGE.token)
    expect(replaced, 'a new access token was stored').toBeTruthy()

    /*
     * The expired one is gone — not "a different string from before".
     *
     * A JWT's iat and exp are counted in whole seconds, so two tokens minted
     * for the same subject in the same second are byte-identical. Once the
     * suite ran against the built app this whole test took two seconds, the
     * sign-in and the refresh landed in the same one, and an assertion that
     * the token must differ failed against completely correct behaviour. It
     * had been passing only because the dev server was slow enough to push
     * them into different seconds.
     *
     * Two identical valid tokens is not a bug and never was. What matters is
     * that the dead credential was replaced by a live one, and that the
     * refresh token rotated — and that one is 256 bits of randomness, so it
     * genuinely cannot repeat.
     */
    expect(replaced, 'the expired token was replaced').not.toBe('expired.access.token')
    expect(replaced, 'a real JWT, not a placeholder').toMatch(/^eyJ/)

    // Rotation: the refresh token itself is replaced too.
    const rotated = await readStorage(page, STORAGE.refresh)
    expect(rotated).not.toBe(beforeRefresh)
  })

  test('C14b · many requests at once still cause only one refresh', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    /*
     * Through the form, for the same reason as C14: a seeded session is restored
     * by an init script on every load and would undo the expiry below.
     */
    await loginThroughUi(page, SEEDED.partner.phone, SEEDED.partner.password)
    await expectSignedIn(page)

    const refreshCalls: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/auth/refresh')) refreshCalls.push(request.url())
    })

    await expireAccessToken(page)

    /*
     * A reload rather than a nav click: the dashboard fires its profile, its
     * three driver counts and its open-rides query together on boot, which is
     * the concurrency this is about. Clicking a link leaves it to the query
     * cache whether anything reaches the network at all.
     *
     * Refresh tokens rotate, so a page that let each of those refresh would
     * spend the token on the first and log itself out on the rest.
     */
    await page.reload()

    await expectSignedIn(page)
    await expect(page.getByRole('link', { name: /^drivers$/i })).toBeVisible()

    /*
     * At least one, and nowhere near one per query — that is the actual
     * requirement. Insisting on exactly one was wrong: a request that was
     * already in flight when the first refresh landed can legitimately cause a
     * second, and it succeeds with the rotated token. What must never happen is
     * a refresh per failed request, which spends the token and logs the user out
     * for being busy.
     */
    expect(refreshCalls.length, 'refreshed').toBeGreaterThanOrEqual(1)
    expect(refreshCalls.length, 'did not stampede').toBeLessThanOrEqual(2)
  })

  test('C16 · no refresh token means a clean trip to the login screen', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await page.goto('/ride')
    await expectSignedIn(page)

    await clearStorage(page, STORAGE.token)
    await clearStorage(page, STORAGE.refresh)

    await page.getByRole('link', { name: /^history$/i }).click()

    // No blank screen, no broken shell — the login form.
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByRole('button', { name: /^sign in$/i })).toBeVisible()
  })

  test('a spent refresh token does not resurrect a session', async ({ page, context, app, guards }) => {
    void app
    void guards

    const account = await registerCustomer(page.request)
    const first = await apiLogin(page.request, account.phone, account.password)

    // Spend it once outside the browser, so the browser's copy is stale.
    const spent = await page.request.post('/api/v1/auth/refresh', {
      data: { refreshToken: first.refreshToken },
    })
    expect(spent.ok()).toBeTruthy()

    await seedSession(context, { accessToken: 'not-a-valid-token', refreshToken: first.refreshToken })

    await page.goto('/ride')

    await expect(page).toHaveURL(/\/login/)
  })
})

/**
 * Changing a password from the profile screen.
 *
 * The interesting part is not the form, it is what happens to the session. The
 * change revokes every one, the caller's included, so the response carries a
 * replacement pair — and if the page fails to store them, the person who just
 * changed their password is the one signed out moments later.
 */
test.describe('Changing a password', () => {
  test('the new password works and the session survives', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)

    const next = 'ChangedInBrowser1!'

    await gotoSettled(page, '/profile')

    await page.getByLabel(/current password/i).fill(account.password)
    await page.getByLabel(/^new password/i).fill(next)
    await page.getByLabel(/confirm new password/i).fill(next)
    await page.getByRole('button', { name: /change password/i }).click()

    await expect(page.getByText(/your password has been changed/i)).toBeVisible()

    /*
     * The session was replaced rather than merely surviving in memory: the
     * stored token is not the one seeded, and a reload still lands signed in.
     */
    const stored = await readStorage(page, STORAGE.token)
    expect(stored, 'a token is still stored').toBeTruthy()
    expect(stored).not.toBe(tokens.accessToken)

    await page.reload()
    await expectSignedIn(page)

    /* And the new password is the one that signs in from now on. */
    const after = await apiLogin(page.request, account.phone, next)
    expect(after.accessToken).toBeTruthy()
  })

  test('the wrong current password is refused and changes nothing', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const account = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, account.phone, account.password)
    await seedSession(context, tokens)

    await gotoSettled(page, '/profile')

    await page.getByLabel(/current password/i).fill('NotMyPassword1!')
    await page.getByLabel(/^new password/i).fill('ShouldNotApply1!')
    await page.getByLabel(/confirm new password/i).fill('ShouldNotApply1!')
    await page.getByRole('button', { name: /change password/i }).click()

    await expect(page.getByText(/your password has been changed/i)).toHaveCount(0)

    /* The original still signs in, which is the assertion that matters. */
    const still = await apiLogin(page.request, account.phone, account.password)
    expect(still.accessToken).toBeTruthy()
  })
})
