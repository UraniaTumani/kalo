import { test as base, expect, type Page, type BrowserContext, type APIRequestContext } from '@playwright/test'

/* ------------------------------------------------------------- accounts */

/**
 * Seeded by DevDataSeeder under the dev profile, at fixed phone numbers. These
 * are deterministic and recreated on every fresh database, so tests may rely on
 * them — but only for reading. Anything a test suspends, deactivates or deletes
 * gets a throwaway account of its own, so a run never depends on what the
 * previous run left behind.
 */
export const SEEDED = {
  admin: { phone: '+355690000001', password: 'Admin123!' },
  customer: { phone: '+355690000002', password: 'Customer123!' },
  customerB: { phone: '+355690000005', password: 'Customer123!' },
  partner: { phone: '+355690000003', password: 'Partner123!' },
  partnerB: { phone: '+355690000004', password: 'Partner123!' },
} as const

export const STORAGE = {
  token: 'kalo.token',
  refresh: 'kalo.refresh',
  language: 'kalo.language',
} as const

/** Tirana, matching the seeded companies' service area. */
export const TIRANA = { lat: 41.3275, lng: 19.8187 }

/**
 * How long the suite waits for the app to finish rendering something.
 *
 * Matches the config's actionTimeout deliberately. Every false failure in this
 * suite has been a page that had not finished -- the shell, a lazy route, a
 * form field -- rather than a wrong assertion, and a helper that hardcodes a
 * shorter wait silently opts out of that budget.
 */
export const RENDER_TIMEOUT = 30_000

let phoneCounter = 0

/**
 * A phone nobody else holds. Albanian mobiles are +355 followed by nine digits;
 * the seeded accounts occupy 69000000x and 6910000xx, so this stays clear of
 * both.
 */
export function uniquePhone(): string {
  phoneCounter += 1
  const tail = String(Date.now() % 10_000_000).padStart(7, '0')
  return `+3556${(phoneCounter % 9) + 1}${tail}`
}

export function uniquePlate(): string {
  return `AA${String(Date.now() % 100_000).padStart(5, '0')}T`
}

/* -------------------------------------------------------------- guards */

/**
 * Console noise that is not the app failing.
 *
 * The browser logs every non-2xx fetch as a console error, so a 401 on a wrong
 * password or a 409 on a duplicate phone would otherwise fail the very tests
 * that exist to provoke them. Server failures are caught separately by the
 * response listener, where they cannot be mistaken for one of these.
 */
const IGNORED_CONSOLE: RegExp[] = [
  /\[vite\]/i,
  /Download the React DevTools/i,
  /favicon/i,
  /tile\.openstreetmap/i,
  /ERR_INTERNET_DISCONNECTED/i,
  /Failed to load resource/i,
]

export type Guards = {
  consoleErrors: string[]
  serverErrors: string[]
  /** Stop failing on a 5xx from this URL fragment — for tests that force one. */
  allowServerError: (fragment: string) => void
}

/* ------------------------------------------------------------ API calls */

/**
 * Signs in through the real endpoint and returns both halves of the session.
 * Used to put a browser straight into a role without driving the login form —
 * the form has its own tests and does not need re-driving thirty times.
 */
export async function apiLogin(
  request: APIRequestContext,
  phone: string,
  password: string,
): Promise<{ accessToken: string; refreshToken: string }> {
  const res = await request.post('/api/v1/auth/login', { data: { phone, password } })

  expect(res.ok(), `login failed for ${phone}: ${res.status()}`).toBeTruthy()

  const body = await res.json()
  return { accessToken: body.accessToken, refreshToken: body.refreshToken }
}

/** Registers a throwaway customer through the real registration endpoint. */
export async function registerCustomer(
  request: APIRequestContext,
  overrides: Partial<{ firstName: string; lastName: string; phone: string; password: string }> = {},
) {
  const account = {
    firstName: 'E2E',
    lastName: 'Rider',
    phone: uniquePhone(),
    password: 'Customer123!',
    ...overrides,
  }

  const res = await request.post('/api/v1/auth/register/customer', { data: account })
  expect(res.status(), `register failed: ${await res.text()}`).toBe(201)

  return account
}

/**
 * Puts a session into the browser before the app boots, so the first render is
 * already signed in. `addInitScript` runs before page scripts on every
 * navigation in the context, which is what makes this survive reloads.
 */
export async function seedSession(
  context: BrowserContext,
  tokens: { accessToken: string; refreshToken: string },
  language: 'en' | 'sq' = 'en',
) {
  await context.addInitScript(
    ([keys, values, lang]) => {
      try {
        localStorage.setItem(keys.token, values.accessToken)
        localStorage.setItem(keys.refresh, values.refreshToken)
        localStorage.setItem(keys.language, lang)
      } catch {
        /* private window */
      }
    },
    [STORAGE, tokens, language] as const,
  )
}

/** English by default: assertions on visible text need one known language. */
export async function forceLanguage(context: BrowserContext, language: 'en' | 'sq' = 'en') {
  await context.addInitScript(
    ([key, lang]) => {
      try {
        localStorage.setItem(key, lang)
      } catch {
        /* private window */
      }
    },
    [STORAGE.language, language] as const,
  )
}

/**
 * Nothing is stubbed any more.
 *
 * Address lookup used to go from the browser straight to OpenStreetMap, so this
 * suite intercepted it — hammering a service whose policy asks people not to
 * would have been both unreliable and rude. Now the lookup goes through our own
 * endpoint, and the E2E backend runs a provider of fixed Tirana landmarks
 * (`GEOCODING_PROVIDER=static`).
 *
 * That is strictly better than the interception it replaces: the controller, the
 * cache, the validation and the authentication are all exercised for real, and
 * only the last hop to a third party is replaced — on the server, where it
 * belongs.
 */
export async function stubGeocoder(_context: BrowserContext) {
  /* Intentionally empty; kept so specs read the same either way. */
}

/* ------------------------------------------------------------- the test */

type Fixtures = {
  guards: Guards
  /** A context with English forced and the geocoder stubbed. */
  app: BrowserContext
}

export const test = base.extend<Fixtures>({
  guards: async ({ page }, use) => {
    const consoleErrors: string[] = []
    const serverErrors: string[] = []
    const allowed: string[] = []

    page.on('console', (message) => {
      if (message.type() !== 'error') return
      const text = message.text()
      if (IGNORED_CONSOLE.some((pattern) => pattern.test(text))) return
      consoleErrors.push(text)
    })

    page.on('pageerror', (error) => {
      consoleErrors.push(`uncaught: ${error.message}`)
    })

    page.on('response', (response) => {
      if (response.status() < 500) return
      const url = response.url()
      if (allowed.some((fragment) => url.includes(fragment))) return
      serverErrors.push(`${response.status()} ${url}`)
    })

    const guards: Guards = {
      consoleErrors,
      serverErrors,
      allowServerError: (fragment: string) => allowed.push(fragment),
    }

    await use(guards)

    /*
     * G7 and G8 from the pre-flight list, enforced on every test rather than as
     * two checks somebody remembers to do once.
     */
    expect(consoleErrors, 'unexpected console errors').toEqual([])
    expect(serverErrors, 'unexpected 5xx responses').toEqual([])
  },

  app: async ({ context }, use) => {
    await forceLanguage(context, 'en')
    await stubGeocoder(context)
    await use(context)
  },
})

export { expect }

/* ------------------------------------------------------------- helpers */

/** Signs in through the actual form. */
export async function loginThroughUi(page: Page, phone: string, password: string) {
  await page.goto('/login')
  await page.getByLabel(/phone/i).fill(phone)
  await page.getByLabel(/password/i).fill(password)
  await page.getByRole('button', { name: /^sign in$/i }).click()
}

/** Waits for the app shell rather than a specific screen. */
export async function expectSignedIn(page: Page) {
  await expect(page.getByRole('button', { name: /sign out/i })).toBeVisible({
    timeout: RENDER_TIMEOUT,
  })
}

export async function readStorage(page: Page, key: string): Promise<string | null> {
  return page.evaluate((k) => localStorage.getItem(k), key)
}

export async function clearStorage(page: Page, key: string) {
  await page.evaluate((k) => localStorage.removeItem(k), key)
}

/**
 * Makes the stored access token stop working, which is what expiry looks like to
 * the client: a token is still sent, the API rejects it, and the refresh path
 * runs. Deleting the token instead simulates something else entirely — the app
 * sends no request at all and simply boots signed out.
 */
export async function expireAccessToken(page: Page) {
  await page.evaluate((k) => localStorage.setItem(k, 'expired.access.token'), STORAGE.token)
}

/* ------------------------------------------------------- settled navigation */

/**
 * The list each screen fetches when it opens.
 *
 * A route that is not here either fetches nothing on arrival or is reached
 * expecting a redirect, and {@link gotoSettled} falls back to waiting for the
 * shell and for every spinner to clear.
 */
const ROUTE_LIST: Record<string, { path: string; answered?: number[] }> = {
  /*
   * 404 is one of this endpoint's two real answers: a customer with no ride in
   * progress has not hit an error, they simply have no ride. Several tests
   * open this screen precisely to prove nothing is there.
   */
  '/ride/current': { path: '/api/v1/rides/current', answered: [200, 404] },
  '/ride/history': { path: '/api/v1/rides/history' },
  '/support': { path: '/api/v1/support/requests' },
  '/profile': { path: '/api/v1/me' },
  '/partner/rides': { path: '/api/v1/partner/rides' },
  '/partner/drivers': { path: '/api/v1/partner/drivers' },
  '/partner/vehicles': { path: '/api/v1/partner/vehicles' },
  '/partner/assignments': { path: '/api/v1/partner/driver-vehicle-assignments' },
  '/partner/documents': { path: '/api/v1/partner/documents' },
  '/partner/settings': { path: '/api/v1/partner/me' },
  '/partner/availability': { path: '/api/v1/partner/availability-settings/service-area' },
  '/partner': { path: '/api/v1/partner/me' },
  '/admin': { path: '/api/v1/admin/partners' },
  '/admin/companies': { path: '/api/v1/admin/partners' },
  '/admin/users': { path: '/api/v1/admin/users' },
  '/admin/rides': { path: '/api/v1/admin/rides' },
  '/admin/support': { path: '/api/v1/admin/support/requests' },
  '/admin/password-resets': { path: '/api/v1/admin/password-resets' },
}

/**
 * Opens a screen and waits until it has the data it is about to be judged on.
 *
 * Nearly every test in this suite used to navigate and assert in the next
 * breath, which is a race in both directions. Something that should be on the
 * page has not rendered yet — that is the noisy half, and it failed one test
 * per full run for six runs, in three different files, always whichever one
 * happened to be running while the machine was busiest.
 *
 * The quiet half is worse. "This customer's ticket is not here", "no
 * untranslated support.* key", "no table on a phone" are all true of a page
 * showing a spinner, so those assertions passed without having looked at
 * anything. Two of them were hollow for exactly that reason, and the leak check
 * in supportarea.spec.ts was saved only by an unrelated assertion above it.
 *
 * Waiting for the screen's own request — and asserting it answered, rather
 * than only that it answered well — makes both halves mean what they say. A
 * failing API now names itself instead of surfacing as a timeout.
 */
export async function gotoSettled(page: Page, route: string) {
  const entry = ROUTE_LIST[route]

  const listed = entry
    ? page.waitForResponse(
        (response) =>
          response.url().includes(entry.path) && response.request().method() === 'GET',
        { timeout: RENDER_TIMEOUT },
      )
    : null

  /*
   * domcontentloaded, not load.
   *
   * `load` waits for every subresource — fonts, lazy chunks, map tiles — none
   * of which is the thing a test is about to assert on, and any of which can
   * hang. One partner run spent its whole three-minute budget inside goto
   * waiting for load on /support. What follows is a better readiness signal
   * anyway: the screen's own request, answered, and the spinners gone.
   */
  await page.goto(route, { waitUntil: 'domcontentloaded' })

  if (entry && listed) {
    const response = await listed
    const answered = entry.answered ?? [200]

    expect(answered, `GET ${entry.path} while opening ${route}`).toContain(response.status())
  }

  await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: RENDER_TIMEOUT })
}
