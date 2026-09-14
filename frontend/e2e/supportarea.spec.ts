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

    await page.goto('/support')

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

    await page.goto('/support')

    await expect(page.getByText(/you have not written to us yet/i)).toBeVisible()
    await expect(page.getByText(subject)).toHaveCount(0)
  })

  test('an admin sees the request and can resolve it', async ({
    page,
    context,
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

    await page.goto('/admin/support')

    const card = page.getByRole('article', { name: subject })
    await expect(card).toBeVisible()

    // The admin view is the only one carrying who wrote it.
    await expect(card).toContainText(customer.phone)

    await card.getByRole('button', { name: /mark resolved/i }).click()

    await expect(card).toContainText(/resolved/i)

    // And the customer sees the decision on their own request.
    await seedSession(context, customerTokens)
    const theirs = await context.newPage()
    await theirs.goto('/support')
    await expect(theirs.getByRole('article', { name: subject })).toContainText(/resolved/i)
    await theirs.close()
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

    await page.goto('/support')

    await expect(page.getByRole('heading', { name: /ndihmë & mbështetje/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /dërgo kërkesën/i })).toBeVisible()

    // A missing key renders as "support.faqTitle"; nothing on the page may.
    await expect(page.getByText(/support\.[a-zA-Z]/)).toHaveCount(0)
  })
})
