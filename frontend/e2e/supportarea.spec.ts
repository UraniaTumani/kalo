import type { Page } from '@playwright/test'
import {
  test,
  expect,
  SEEDED,
  apiLogin,
  seedSession,
  registerCustomer,
} from './support/fixtures'

/**
 * Help & Support, end to end: a customer writes, an admin answers.
 *
 * The request is filed by a throwaway account registered for this test, so the
 * assertions never depend on what an earlier run left behind — and the admin
 * queue is read by subject, not by position, because every run adds a row.
 */

const SUBJECT = () => `E2E ticket ${Date.now()}-${Math.floor(Math.random() * 1000)}`

/**
 * Opens a support screen and waits for its list to have actually arrived.
 *
 * Every assertion in this file is about which tickets are on a page, and each
 * one used to be made the instant navigation finished. That is a race in both
 * directions: a ticket that should be there has not rendered yet, and a ticket
 * that must NOT be there is absent for the most boring possible reason — which
 * makes the isolation check pass without having looked at anything.
 *
 * Three different tests in this file failed this way on a loaded machine, each
 * reporting something alarming about support when the page simply said
 * "Loading". Waiting for the response and the spinner makes both kinds of
 * assertion mean what they say.
 */
async function gotoSettledSupport(page: Page, route: string, apiPath: string) {
  /*
   * Any answer, not only a good one.
   *
   * Requiring 200 here made this helper lie. The query client retries a 5xx
   * twice with backoff and keeps the list on "Loading" throughout, so a failing
   * API surfaced as "timed out waiting for a response" — which reads like a
   * slow machine and hides that the request was answered, badly. Matching any
   * response and asserting the status afterwards means the failure names
   * itself.
   */
  const listed = page.waitForResponse(
    (response) => response.url().includes(apiPath) && response.request().method() === 'GET',
    { timeout: 30_000 },
  )

  await page.goto(route)

  const response = await listed
  expect(response.status(), `GET ${apiPath} while loading ${route}`).toBe(200)

  await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 30_000 })
}

const MINE = '/api/v1/support/requests'
const QUEUE = '/api/v1/admin/support/requests'

test.describe('Help & Support', () => {
  test('a customer writes in and sees their own request', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const customer = await registerCustomer(page.request)
    const tokens = await apiLogin(page.request, customer.phone, customer.password)
    await seedSession(context, tokens)

    const subject = SUBJECT()

    await gotoSettledSupport(page, '/support', MINE)

    // The FAQ is the first thing offered, before the form.
    await expect(page.getByRole('heading', { name: /common questions/i })).toBeVisible()

    await page.getByLabel(/^subject/i).fill(subject)
    await page.getByLabel(/^message/i).fill('The fare was higher than the estimate.')
    await page.getByRole('button', { name: /send request/i }).click()

    await expect(page.getByText(/your request has been sent/i)).toBeVisible()

    // It appears in their own list, open and waiting.
    await expect(page.getByRole('article', { name: subject })).toContainText(/open/i)
  })

  test("a customer never sees another customer's request", async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const author = await registerCustomer(page.request)
    const authorTokens = await apiLogin(page.request, author.phone, author.password)

    const subject = SUBJECT()

    const filed = await page.request.post('/api/v1/support/requests', {
      headers: { Authorization: `Bearer ${authorTokens.accessToken}` },
      data: { category: 'PAYMENT', subject, message: 'Charged twice for one ride.' },
    })
    expect(filed.status()).toBe(201)

    // A different account, through the real screen.
    const other = await registerCustomer(page.request)
    const otherTokens = await apiLogin(page.request, other.phone, other.password)
    await seedSession(context, otherTokens)

    /*
     * The wait is the point here, not a formality. "The other customer's
     * subject is not on this page" is only worth asserting once the page has
     * been told what is on it — otherwise the leak check passes without having
     * looked, and the empty-state line below is all that stands between this
     * test and a hollow one.
     */
    await gotoSettledSupport(page, '/support', MINE)

    await expect(page.getByText(/you have not written to us yet/i)).toBeVisible()
    await expect(page.getByText(subject)).toHaveCount(0)
  })

  test('an admin sees the request and can resolve it', async ({
    page,
    context,
    browser,
    baseURL,
    app,
    guards,
  }) => {
    void app
    void guards

    const customer = await registerCustomer(page.request)
    const customerTokens = await apiLogin(page.request, customer.phone, customer.password)

    const subject = SUBJECT()

    const filed = await page.request.post('/api/v1/support/requests', {
      headers: { Authorization: `Bearer ${customerTokens.accessToken}` },
      data: { category: 'RIDE_ISSUE', subject, message: 'The driver took a long route.' },
    })
    expect(filed.status()).toBe(201)

    const adminTokens = await apiLogin(page.request, SEEDED.admin.phone, SEEDED.admin.password)
    await seedSession(context, adminTokens)

    await gotoSettledSupport(page, '/admin/support', QUEUE)

    const card = page.getByRole('article', { name: subject })
    await expect(card).toBeVisible()

    // The admin view is the only one carrying who wrote it.
    await expect(card).toContainText(customer.phone)

    await card.getByRole('button', { name: /mark resolved/i }).click()

    await expect(card).toContainText(/resolved/i)

    /*
     * And the customer sees the decision on their own request — in a context
     * of their own, not a second tab wearing the admin's session.
     *
     * seedSession works by addInitScript, and init scripts accumulate: seeding
     * the admin and then the customer on one context leaves both scripts
     * running on every page it opens afterwards, and the tab's identity comes
     * down to which one wrote localStorage last. That is a coin toss dressed
     * as a test. When it lands wrong the page is an admin on a customer-only
     * route, ProtectedRoute redirects, the support list is never fetched, and
     * the wait for it times out sixty seconds later — which is how this failed
     * twenty-two minutes into a suite while passing alone in three seconds.
     *
     * A separate context has one session in it and cannot be ambiguous.
     */
    /* baseURL is not inherited by a hand-made context, so it is passed on. */
    const theirContext = await browser.newContext({ baseURL })

    try {
      await seedSession(theirContext, customerTokens)

      const theirs = await theirContext.newPage()
      await gotoSettledSupport(theirs, '/support', MINE)
      await expect(theirs.getByRole('article', { name: subject })).toContainText(/resolved/i)
    } finally {
      await theirContext.close()
    }
  })

  test('a customer cannot reach the admin queue', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await page.goto('/admin/support')

    // ProtectedRoute sends them back to their own surface rather than showing it.
    await expect(page).not.toHaveURL(/\/admin\/support/)
  })

  test('the support page is fully translated', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens, 'sq')

    /*
     * Settled before asserting, because the last assertion here is another
     * absence: a missing key renders as "support.faqTitle", and a page still
     * loading has no such text for reasons that have nothing to do with i18n.
     */
    await gotoSettledSupport(page, '/support', MINE)

    await expect(page.getByRole('heading', { name: /ndihmë & mbështetje/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /dërgo kërkesën/i })).toBeVisible()

    // A missing key renders as "support.faqTitle"; nothing on the page may.
    await expect(page.getByText(/support\.[a-zA-Z]/)).toHaveCount(0)
  })
})
