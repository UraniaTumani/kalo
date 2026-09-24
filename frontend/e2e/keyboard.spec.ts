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
 * What axe cannot see.
 *
 * Automated rules catch missing names and bad contrast; they say nothing about
 * whether a keyboard can actually operate the screen. These drive it.
 */

test.describe('Keyboard operation', () => {
  test.beforeEach(async ({ page, context, app }) => {
    void app
    const tokens = await apiLogin(page.request, SEEDED.admin.phone, SEEDED.admin.password)
    await seedSession(context, tokens)
  })

  /**
   * The defect this covers: `aria-modal` hides the background from a screen
   * reader but does nothing to the tab order, so Tab from the last button
   * walked into the page behind the overlay — on a destructive confirmation,
   * into a form the user could no longer see.
   */
  test('a confirmation dialog keeps the keyboard inside it', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/admin/companies')
    await expectSignedIn(page)

    await page.getByRole('button', { name: /^suspend$/i }).first().click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    /* Focus starts on Cancel: one Enter away from backing out, not from going through. */
    await expect(dialog.getByRole('button', { name: /keep|cancel/i })).toBeFocused()

    /*
     * Tab all the way round twice. Every stop must still be inside the panel —
     * before the fix this escaped after the last button.
     */
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab')

      const insideDialog = await page.evaluate(() => {
        const panel = document.querySelector('[role="dialog"] > div:last-of-type')
        return panel ? panel.contains(document.activeElement) : false
      })

      expect(insideDialog, `focus escaped the dialog after ${i + 1} Tab press(es)`).toBe(true)
    }

    /* Shift+Tab wraps backwards just as well. */
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press('Shift+Tab')

      const insideDialog = await page.evaluate(() => {
        const panel = document.querySelector('[role="dialog"] > div:last-of-type')
        return panel ? panel.contains(document.activeElement) : false
      })

      expect(insideDialog, 'focus escaped backwards').toBe(true)
    }

    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
  })

  /**
   * Dismissing used to drop focus on <body>, so the next Tab started again at
   * the top of the page — a keyboard user had to travel the whole screen to
   * get back to the row they were working on.
   */
  test('closing a dialog returns focus to what opened it', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/admin/companies')
    await expectSignedIn(page)

    const trigger = page.getByRole('button', { name: /^suspend$/i }).first()
    await trigger.click()

    await expect(page.getByRole('dialog')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeHidden()

    await expect(trigger).toBeFocused()
  })

  test('every interactive control on a page is reachable by Tab', async ({ page, guards }) => {
    void guards

    await gotoSettled(page, '/admin/users')
    await expectSignedIn(page)
    await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 30_000 })

    /*
     * Walks the page and checks that focus actually lands somewhere visible
     * each time — a control with no focus style or a container that swallows
     * the keyboard shows up here as focus staying on <body>.
     */
    const reached = new Set<string>()

    for (let i = 0; i < 25; i++) {
      await page.keyboard.press('Tab')

      const here = await page.evaluate(() => {
        const el = document.activeElement
        if (!el || el === document.body) return null
        return `${el.tagName.toLowerCase()}:${(el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 30)}`
      })

      if (here) reached.add(here)
    }

    expect(reached.size, 'tabbing reached almost nothing — the keyboard cannot drive this page')
      .toBeGreaterThan(5)
  })
})

test.describe('Ride memory', () => {
  /**
   * The id of the last ride is remembered so a ride that has just finished
   * still shows its outcome, because /rides/current only returns live rides.
   * It was written on every ride and never cleared, so weeks later an empty
   * Current ride page would fetch a long-completed ride and present it as
   * though it were live.
   */
  test('a stale remembered ride is not resurrected', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    /* A ride remembered two days ago, in the current storage shape. */
    await context.addInitScript(() => {
      try {
        localStorage.setItem(
          'kalo.lastRideId',
          JSON.stringify({ rideId: 1, at: Date.now() - 48 * 60 * 60 * 1000 }),
        )
      } catch {
        /* private window */
      }
    })

    await gotoSettled(page, '/ride/current')
    await expectSignedIn(page)

    /* The empty state, not somebody's ride from two days ago. */
    await expect(page.getByText(/no active ride|book a taxi|nothing/i).first()).toBeVisible({
      timeout: 30_000,
    })

    const stored = await page.evaluate(() => localStorage.getItem('kalo.lastRideId'))
    expect(stored, 'an expired entry should be cleared on read').toBeNull()
  })

  test('signing out forgets the remembered ride', async ({ page, context, app, guards }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.customer.phone, SEEDED.customer.password)
    await seedSession(context, tokens)

    await context.addInitScript(() => {
      try {
        localStorage.setItem(
          'kalo.lastRideId',
          JSON.stringify({ rideId: 99, at: Date.now() }),
        )
      } catch {
        /* private window */
      }
    })

    await page.goto('/ride')
    await expectSignedIn(page)

    await page.getByRole('button', { name: /sign out/i }).click()
    await expect(page).toHaveURL(/\/login/, { timeout: 30_000 })

    const stored = await page.evaluate(() => localStorage.getItem('kalo.lastRideId'))
    expect(stored, "the previous session's ride must not outlive it").toBeNull()
  })
})
