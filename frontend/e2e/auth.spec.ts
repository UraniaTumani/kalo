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


/**
 * Password recovery, from the screens a person actually uses.
 *
 * This block exists because of a real report: the page answered "Resource not
 * found" on a valid number. The endpoint was never wrong — a backend one deploy
 * behind this frontend has no such route, answers 404, and the page repeated the
 * server's words under a form asking for a phone number, where they read as a
 * verdict on the number rather than on the deployment. That is still pinned
 * below.
 *
 * What the flow does has changed: a one-time code is texted to the number on the
 * account rather than read out by an administrator, so there is no confirmation
 * screen to wait on any more and the person goes straight to the code field.
 *
 * The code itself is deliberately unreachable from here. Nothing in the SMS path
 * returns it and nothing logs it where a browser could look, which is the point —
 * so the redemption is exercised against a stubbed 204 and the real code is
 * proved by PasswordResetIntegrationTest, which can read the message. What these
 * tests own is the journey: two visible steps, one request, and a refusal that
 * says the same thing however it failed.
 */
test.describe('Password recovery', () => {
  /**
   * The response body, when the browser will still part with it.
   *
   * An assertion's message argument is evaluated on every run, not only when the
   * assertion fails, so reading the body inline runs it against a response the
   * page may already have moved on from. WebKit refuses at that point —
   * "response body is not available for a response that was navigated away
   * from" — and the read throws before the status is ever compared. Chromium
   * keeps the body retrievable, so a diagnostic added to make failures legible
   * passed locally and failed only on Mobile Safari.
   *
   * The body is worth having when a status is unexpected, so it is still
   * fetched; it just stops being able to fail the test on its own.
   */
  async function bodyForDiagnosis(
    response: import('@playwright/test').Response,
  ): Promise<string> {
    try {
      return await response.text()
    } catch {
      return '<response body no longer retrievable>'
    }
  }

  /** Every call the page makes to the recovery endpoint, as the browser sees it. */
  function watchForgotCalls(page: import('@playwright/test').Page) {
    const calls: { url: string; method: string; status: number }[] = []

    page.on('response', (response) => {
      if (!response.url().includes('/auth/password/forgot')) return
      calls.push({
        url: response.url(),
        method: response.request().method(),
        status: response.status(),
      })
    })

    return calls
  }

  /** Asks for a code and waits for the answer, from the first screen. */
  async function requestCode(page: import('@playwright/test').Page, phone: string) {
    await page.goto('/forgot-password')
    await page.getByLabel(/^phone/i).fill(phone)

    const answered = page.waitForResponse((r) => r.url().includes('/auth/password/forgot'))
    await page.getByRole('button', { name: /send code/i }).click()

    return answered
  }

  test('a registered number reaches the right endpoint and lands on the code step', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    const calls = watchForgotCalls(page)

    const response = await requestCode(page, SEEDED.customer.phone)

    /* Status first: a failure here names what went wrong rather than only
     * reporting that a field never appeared. */
    expect(response.status(), await bodyForDiagnosis(response)).toBe(202)

    await expect(page).toHaveURL(/\/reset-password/)
    await expect(page.getByLabel(/^code/i)).toBeVisible()

    /*
     * The mapping, asserted from the browser rather than from the source: a POST
     * to /api/v1/auth/password/forgot, accepted. A 404 here is the reported bug,
     * and it would be a stale backend rather than a wrong path.
     */
    expect(calls, 'the page called the recovery endpoint').toHaveLength(1)
    expect(calls[0].url).toContain('/api/v1/auth/password/forgot')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].status, 'accepted, not 404 or 500').toBe(202)
  })

  test('an unknown number ends at exactly the same place', async ({ page, app, guards }) => {
    void app
    void guards

    const calls = watchForgotCalls(page)

    /* Correctly formed, belongs to nobody. */
    const response = await requestCode(page, '+355699999999')

    expect(response.status(), await bodyForDiagnosis(response)).toBe(202)

    expect(calls[0].status, 'accepted, exactly as for a real account').toBe(202)

    /*
     * The point of the whole design. Nothing on this screen may hint that the
     * number is unknown — not a different message, not a different tone, not an
     * error alert, and not being kept on the previous page.
     */
    await expect(page).toHaveURL(/\/reset-password/)
    await expect(page.getByLabel(/^code/i)).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
  })

  test('the phone number is not put in the URL', async ({ page, app, guards }) => {
    void app
    void guards

    await requestCode(page, SEEDED.customer.phone)
    await expect(page).toHaveURL(/\/reset-password/)

    /*
     * Carried in router state instead. A phone number in a query string ends up
     * in browser history, in any analytics this page ever gains, and in a link
     * somebody pastes into a chat.
     */
    expect(page.url()).not.toContain(SEEDED.customer.phone)
    expect(page.url()).not.toContain('355')
  })

  test('the code field is set up for a texted code', async ({ page, app, guards }) => {
    void app
    void guards

    await requestCode(page, SEEDED.customer.phone)

    const code = page.getByLabel(/^code/i)

    /*
     * These two attributes are most of the usability of an SMS code on a phone:
     * the numeric keypad instead of a full keyboard, and one-time-code so iOS and
     * Android offer the digits straight from the message. Asserted because they
     * are invisible — nothing about the rendered page looks wrong when they are
     * missing, and the difference for the person is two taps against reading a
     * number off a notification and typing it.
     */
    await expect(code).toHaveAttribute('inputmode', 'numeric')
    await expect(code).toHaveAttribute('autocomplete', 'one-time-code')
  })

  test('the whole journey: code, then password, then signed out everywhere', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    let submitted: Record<string, unknown> | null = null

    /*
     * Stubbed, because the real code exists only in a text message this browser
     * cannot read. What is being tested is the shape of the request the two-step
     * form produces — one call carrying both the code and the password, which is
     * the decision that let this flow avoid a second secret.
     */
    await page.route('**/api/v1/auth/password/reset', async (route) => {
      submitted = JSON.parse(route.request().postData() ?? '{}')
      await route.fulfill({ status: 204 })
    })

    await requestCode(page, SEEDED.customer.phone)

    /* Step one: the code, alone. The password fields are not on screen yet. */
    await expect(page.getByLabel(/new password/i)).toBeHidden()

    await page.getByLabel(/^code/i).fill('428913')
    await page.getByRole('button', { name: /^continue$/i }).click()

    /* Step two: the password, and the code field has gone. */
    await expect(page.getByLabel(/new password/i)).toBeVisible()
    await expect(page.getByLabel(/^code/i)).toBeHidden()

    await page.getByLabel(/new password/i).fill('BrandNewPass456!')
    await page.getByLabel(/confirm password/i).fill('BrandNewPass456!')
    await page.getByRole('button', { name: /reset password/i }).click()

    await expect(page.getByText(/password changed/i)).toBeVisible()

    /* One request, both values. */
    expect(submitted).toMatchObject({
      phone: SEEDED.customer.phone,
      code: '428913',
      newPassword: 'BrandNewPass456!',
    })
  })

  test('a mistyped confirmation is caught before anything is sent', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    let called = false
    await page.route('**/api/v1/auth/password/reset', async (route) => {
      called = true
      await route.fulfill({ status: 204 })
    })

    await requestCode(page, SEEDED.customer.phone)

    await page.getByLabel(/^code/i).fill('428913')
    await page.getByRole('button', { name: /^continue$/i }).click()

    await page.getByLabel(/new password/i).fill('BrandNewPass456!')
    await page.getByLabel(/confirm password/i).fill('BrandNewPass457!')
    await page.getByRole('button', { name: /reset password/i }).click()

    await expect(page.getByText(/do not match/i)).toBeVisible()

    /*
     * Not sent. A mismatch spent against the server would burn one of the five
     * attempts the code survives, which for a six-digit code is a fifth of the
     * person's margin gone to a typo.
     */
    expect(called, 'a mismatched confirmation must not reach the server').toBe(false)
  })

  test('a refused code comes back generic, and returns to the code step', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    await page.route('**/api/v1/auth/password/reset', (route) =>
      route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 400,
          error: 'Bad Request',
          message: 'This code is not valid or has expired',
          path: '/api/v1/auth/password/reset',
        }),
      }),
    )

    await requestCode(page, SEEDED.customer.phone)

    await page.getByLabel(/^code/i).fill('000000')
    await page.getByRole('button', { name: /^continue$/i }).click()

    await page.getByLabel(/new password/i).fill('BrandNewPass456!')
    await page.getByLabel(/confirm password/i).fill('BrandNewPass456!')
    await page.getByRole('button', { name: /reset password/i }).click()

    await expect(page.getByText(/not valid or has expired/i)).toBeVisible()

    /*
     * Back to the code. The refusal is indistinguishable by design — a wrong
     * code, an expired one and a number belonging to nobody all say this — but a
     * wrong code is overwhelmingly the likeliest cause, and leaving somebody on
     * the password step with a generic error gives them nothing to change.
     */
    await expect(page.getByLabel(/^code/i)).toBeVisible()

    /* And nothing on screen narrows down which of those it was. */
    await expect(page.getByText(/expired/i)).toHaveCount(1)
    await expect(page.getByText(/no account|not registered|suspended/i)).toHaveCount(0)
  })

  test('resending waits out a cooldown', async ({ page, app, guards }) => {
    void app
    void guards

    await requestCode(page, SEEDED.customer.phone)

    /*
     * A code has just gone out, so the button is a countdown rather than a link.
     * The server holds the same sixty seconds, and a countdown that finished
     * first would hand somebody a button that silently does nothing — which is
     * worse than one that is visibly not ready yet.
     */
    await expect(page.getByText(/new code in \d+s/i)).toBeVisible()
    await expect(page.getByRole('button', { name: /send a new code/i })).toHaveCount(0)
  })

  test('arriving at the code page directly can ask for a code straight away', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    /*
     * No send has been spent, so there is nothing to count down from. Somebody
     * who reads the message on another device and opens this page cold must not
     * be made to wait a minute before they can ask for anything.
     */
    await page.goto('/reset-password')

    await expect(page.getByRole('button', { name: /send a new code/i })).toBeVisible()
    await expect(page.getByText(/new code in \d+s/i)).toHaveCount(0)
  })

  test('a server failure does not repeat the server to the user', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    /*
     * The reported bug, forced: the exact 404 body a backend without this route
     * returns. What the person must not see is "Resource not found" under a
     * phone field, and what they must not be told is that a code is on its way
     * when none is.
     */
    await page.route('**/api/v1/auth/password/forgot', (route) =>
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 404,
          error: 'Not Found',
          message: 'Resource not found',
          path: '/api/v1/auth/password/forgot',
        }),
      }),
    )

    await page.goto('/forgot-password')

    await page.getByLabel(/^phone/i).fill(SEEDED.customer.phone)
    await page.getByRole('button', { name: /send code/i }).click()

    await expect(page.getByText(/could not submit your request/i)).toBeVisible()
    await expect(page.getByText(/resource not found/i)).toHaveCount(0)

    /* And not moved on to a code that was never sent. */
    await expect(page).toHaveURL(/\/forgot-password/)
  })

  test('the normal flow no longer promises a telephone call', async ({ page, app, guards }) => {
    void app
    void guards

    await page.goto('/forgot-password')

    /*
     * The old copy told everybody an administrator would ring them, which was
     * true then and is now a promise nobody is going to keep. Pinned as an
     * absence because a stale string here sends people to wait by the phone.
     */
    await expect(page.getByText(/administrator will call you/i)).toHaveCount(0)
    await expect(page.getByText(/i have a code/i)).toHaveCount(0)
  })
})
