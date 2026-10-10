import {
  test,
  expect,
  SEEDED,
  TIRANA,
  gotoSettled,
  apiLogin,
  seedSession,
  registerCustomer,
  expectSignedIn,
  uniquePhone,
} from './support/fixtures'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * F20 — does the availability editor show what the system actually believes?
 *
 * The bug: a company with no operating-hours rows was shown a full open week,
 * 08:00–22:00 every day, while the backend reads the absence of those rows as
 * "closed all week" and excludes the company from every customer search. The
 * screen stated the exact opposite of the truth, for every newly approved
 * company — because none has hours until its partner sets them.
 *
 * WHY THE FIRST TEST REGISTERS AND APPROVES A WHOLE COMPANY rather than
 * clearing an existing one's hours: it cannot be done the short way.
 * UpdateOperatingHoursRequest.hours is @NotEmpty, so the API refuses an empty
 * week — the zero-rows state is reachable only by never having configured
 * hours, never by clearing them. Saving seven closed days is a different state
 * and would not exercise the defect. The long way round is the only faithful
 * one, and it also happens to be the exact population the finding describes.
 *
 * ON NON-VACUITY, which this suite has got wrong twice: "every day shows
 * Closed" is an absence-shaped assertion that a page which failed to render
 * would satisfy just as well. So every test here first proves the editor is
 * populated — seven day toggles present — and the second test asserts the
 * positive case on the same control, so a blank page cannot pass both.
 */

type Tokens = { accessToken: string; refreshToken: string }

const auth = (t: Tokens) => ({ Authorization: `Bearer ${t.accessToken}` })

const WEEK = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const

const NOTICE = /have not set your operating hours/i

/** Every day's open/closed toggle. A real checkbox, so it has a role. */
const dayToggles = (page: Page) => page.getByRole('checkbox')

async function expectEditorPopulated(page: Page) {
  await expect(dayToggles(page)).toHaveCount(WEEK.length, { timeout: 30_000 })
}

/**
 * Registers a partner, completes verification and has an admin approve it.
 *
 * The result is a company in the state every company passes through: approved,
 * active, and with no operating hours at all.
 */
async function newlyApprovedCompany(request: APIRequestContext): Promise<{
  tokens: Tokens
  companyId: number
}> {
  const phone = uniquePhone()
  const password = 'Partner123!'
  const nipt = `K${Date.now() % 100_000_000}B`

  const registered = await request.post('/api/v1/auth/register/partner', {
    data: {
      firstName: 'E2E',
      lastName: 'Hours',
      phone,
      email: `hours${Date.now() % 1_000_000}@kalo.test`,
      password,
      legalName: `E2E Hours ${nipt} SHPK`,
      displayName: `E2E Hours ${nipt}`,
      nipt,
      address: 'Rruga e Kavajes 1, Tirane',
    },
  })
  expect(registered.status(), await registered.text()).toBe(201)

  const tokens = await apiLogin(request, phone, password)

  /* Registration does not collect the licence, and verification requires it. */
  const profile = await request.put('/api/v1/partner/me', {
    headers: auth(tokens),
    data: {
      legalName: `E2E Hours ${nipt} SHPK`,
      displayName: `E2E Hours ${nipt}`,
      phone,
      email: `hours${Date.now() % 1_000_000}@kalo.test`,
      address: 'Rruga e Kavajes 1, Tirane',
      licenseNumber: `TAXI-${nipt}`,
      licenseExpiryDate: '2030-12-31',
    },
  })
  expect(profile.ok(), `profile: ${await profile.text()}`).toBeTruthy()

  for (const documentType of ['BUSINESS_REGISTRATION', 'TAXI_LICENSE']) {
    const document = await request.post('/api/v1/partner/documents', {
      headers: auth(tokens),
      data: {
        documentType,
        documentNumber: `DOC-${documentType}-${nipt}`,
        fileUrl: `https://example.test/${documentType}.pdf`,
        expiresAt: '2030-12-31',
      },
    })
    expect(document.ok(), `${documentType}: ${await document.text()}`).toBeTruthy()
  }

  const submitted = await request.post('/api/v1/partner/submit-verification', {
    headers: auth(tokens),
  })
  expect(submitted.ok(), `submit: ${await submitted.text()}`).toBeTruthy()

  const companyId = await companyIdOf(request, tokens)

  const adminTokens = await apiLogin(request, SEEDED.admin.phone, SEEDED.admin.password)

  const approved = await request.post(`/api/v1/admin/partners/${companyId}/approve`, {
    headers: auth(adminTokens),
  })
  expect(approved.ok(), `approve: ${await approved.text()}`).toBeTruthy()

  return { tokens, companyId }
}

async function putWeek(
  request: APIRequestContext,
  tokens: Tokens,
  hours: Array<{ dayOfWeek: string; openTime: string | null; closeTime: string | null; closed: boolean }>,
) {
  const response = await request.put('/api/v1/partner/availability-settings/operating-hours', {
    headers: auth(tokens),
    data: { hours },
  })

  expect(response.ok(), `operating hours: ${await response.text()}`).toBeTruthy()
}

const wholeWeek = (closed: boolean) =>
  WEEK.map((dayOfWeek) => ({
    dayOfWeek,
    openTime: closed ? null : '00:00',
    closeTime: closed ? null : '00:00',
    closed,
  }))

test.describe('F20 · the availability editor reflects persisted hours', () => {
  test.setTimeout(240_000)

  test('a newly approved company is shown as closed, and told why', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const { tokens } = await newlyApprovedCompany(page.request)
    await seedSession(context, tokens)

    await gotoSettled(page, '/partner/availability')
    await expectSignedIn(page)

    /* The editor is really there, so what follows means something. */
    await expectEditorPopulated(page)

    /*
     * Before the fix all seven of these were checked, and the company was
     * invisible to customers the whole time.
     */
    await expect(dayToggles(page).and(page.getByRole('checkbox', { checked: true }))).toHaveCount(0)

    /*
     * The other half of the fix. A correct but silent screen still leaves the
     * partner wondering why nobody books them.
     */
    await expect(page.getByText(NOTICE)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(/will not see your company/i)).toBeVisible()
  })

  test('a fully open week is shown as open, and the notice is gone', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    await putWeek(page.request, tokens, wholeWeek(false))

    await gotoSettled(page, '/partner/availability')
    await expectSignedIn(page)
    await expectEditorPopulated(page)

    /*
     * The positive half of the pair above. A page that rendered no toggles at
     * all would satisfy "nothing is checked"; it cannot satisfy this.
     */
    await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(WEEK.length)

    await expect(page.getByText(NOTICE)).toBeHidden()
  })

  test('a partially configured week shows the saved days and closes the rest', async ({
    page,
    context,
    app,
    guards,
  }) => {
    void app
    void guards

    const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
    await seedSession(context, tokens)

    await putWeek(page.request, tokens, [
      { dayOfWeek: 'MONDAY', openTime: '08:00', closeTime: '22:00', closed: false },
      { dayOfWeek: 'TUESDAY', openTime: '08:00', closeTime: '22:00', closed: false },
    ])

    await gotoSettled(page, '/partner/availability')
    await expectSignedIn(page)
    await expectEditorPopulated(page)

    /*
     * Behaviour that was already correct and must stay so: unconfigured days
     * are completed as closed, not open. This branch of the component was never
     * broken, which is exactly why the empty case went unnoticed for so long.
     */
    await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(2)

    /* Hours exist, so the nothing-configured notice must not appear. */
    await expect(page.getByText(NOTICE)).toBeHidden()
  })

  test('closing and opening the week changes customer search immediately', async ({
    page,
    app,
    guards,
  }) => {
    void app
    void guards

    const partnerTokens = await apiLogin(
      page.request,
      SEEDED.partner.phone,
      SEEDED.partner.password,
    )
    const companyId = await companyIdOf(page.request, partnerTokens)

    const customer = await registerCustomer(page.request)
    const customerTokens = await apiLogin(page.request, customer.phone, customer.password)

    await putWeek(page.request, partnerTokens, wholeWeek(true))

    expect(await companyIdsOffered(page.request, customerTokens))
      .not.toContain(companyId)

    await putWeek(page.request, partnerTokens, wholeWeek(false))

    /*
     * No reload and no cache to wait out: the search reads operating hours on
     * every request, batched into one query for all candidate companies.
     */
    expect(await companyIdsOffered(page.request, customerTokens)).toContain(companyId)
  })
})

/* ----------------------------------------------------------------- helpers */

async function companyIdOf(request: APIRequestContext, tokens: Tokens): Promise<number> {
  const response = await request.get('/api/v1/partner/me', { headers: auth(tokens) })
  expect(response.ok(), await response.text()).toBeTruthy()

  const body = await response.json()
  const id = body.companyId ?? body.id

  expect(id, `no company id in partner profile: ${JSON.stringify(body)}`).toBeTruthy()

  return Number(id)
}

async function companyIdsOffered(
  request: APIRequestContext,
  tokens: Tokens,
): Promise<number[]> {
  const response = await request.post('/api/v1/rides/search', {
    headers: auth(tokens),
    data: {
      pickupLatitude: TIRANA.lat,
      pickupLongitude: TIRANA.lng,
      destinationLatitude: 41.32,
      destinationLongitude: 19.83,
    },
  })

  expect(response.status(), await response.text()).toBe(201)

  const body = await response.json()

  /* Number(), because comparing an id across JSON and JS is a trap this suite
   * has already fallen into once: JsonPath-style reads give Integer-like
   * values that never equal a Long. */
  return (body.taxiOptions ?? []).map((option: { companyId: number }) => Number(option.companyId))
}
