import { test, expect, uniquePhone, registerCustomer } from './support/fixtures'

/**
 * Pre-flight: G1, G2.
 *
 * The backend already proves phone normalisation and uniqueness thoroughly. What
 * it cannot show is whether a person typing the same number a different way is
 * told something useful, or is left staring at a form that looks like it worked.
 */
test.describe('Registration', () => {
  test('G1 · the same number in another spelling is refused, in words', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    const taken = await registerCustomer(page.request)

    // 06X XXX XXXX is how an Albanian writes the number the API stores as +3556…
    const nationalForm = taken.phone.replace('+355', '0')

    await page.goto('/register')

    await page.getByLabel(/first name/i).fill('Second')
    await page.getByLabel(/last name/i).fill('Claimant')
    await page.getByLabel(/phone/i).fill(nationalForm)
    await page.getByLabel(/password/i).first().fill('Customer123!')

    await page.getByRole('button', { name: /create passenger account/i }).click()

    /*
     * A readable sentence, not a translation key and not a silent failure. The
     * two spellings are the same person, and the app has to say so.
     */
    await expect(page.getByText(/already|taken|registered|in use/i).first()).toBeVisible({
      timeout: 15_000,
    })

    await expect(page).toHaveURL(/\/register/)
  })

  test('G2 · a malformed phone is rejected before it reaches the API', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    const registerCalls: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/auth/register')) registerCalls.push(request.url())
    })

    await page.goto('/register')

    await page.getByLabel(/first name/i).fill('Wrong')
    await page.getByLabel(/last name/i).fill('Number')
    await page.getByLabel(/phone/i).fill('12345')
    await page.getByLabel(/password/i).first().fill('Customer123!')

    await page.getByRole('button', { name: /create passenger account/i }).click()

    await expect(page.getByText(/phone/i).first()).toBeVisible()
    await expect(page).toHaveURL(/\/register/)

    // Caught in the browser: no point spending a round trip on five digits.
    expect(registerCalls.length, 'no request for an obviously invalid number').toBe(0)
  })

  test('a genuinely new number is accepted', async ({ page, app, guards }) => {
    void app
    void guards

    await page.goto('/register')

    await page.getByLabel(/first name/i).fill('Brand')
    await page.getByLabel(/last name/i).fill('New')
    await page.getByLabel(/phone/i).fill(uniquePhone())
    await page.getByLabel(/password/i).first().fill('Customer123!')

    await page.getByRole('button', { name: /create passenger account/i }).click()

    // Straight into the app, or to the login screen — either way, not an error.
    await expect(page).not.toHaveURL(/\/register$/, { timeout: 15_000 })
  })
})
