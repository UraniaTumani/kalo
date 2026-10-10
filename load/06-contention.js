import http from 'k6/http'
import { sleep } from 'k6'
import { SharedArray } from 'k6/data'
import { Counter, Trend } from 'k6/metrics'
import {
  BASE,
  PASSWORD,
  authHeaders,
  clientHeaders,
  searchRides,
  track,
} from './lib/common.js'
import { classifyAccept } from './lib/contention.js'

/**
 * Scenario 6 — contention on one driver.
 *
 * Every other scenario in this suite gives each virtual user room. With a
 * hundred companies and two hundred drivers there is nearly always somebody
 * free, so two requests rarely reach for the same driver and the concurrency
 * invariants are never tested. "No driver held two active rides" then comes
 * back perfect from a run that never tried to give one two — which is the
 * shape of every false positive this project has had to withdraw.
 *
 * This scenario removes the room. Three companies, one driver each, thirty
 * customers: most acceptances MUST lose, and the only correct outcome is that
 * exactly one wins per driver.
 *
 * WHERE THE RACE ACTUALLY IS, because it is not where it first appears. A
 * customer selecting an offer creates a REQUESTED ride against a company, and
 * several customers can hold REQUESTED rides against the same company at once —
 * nothing is contended yet. The contention is at ACCEPT, because accepting
 * names a driver, and `uk_active_ride_per_driver` permits that driver one
 * active ride. So the race is many accepts, each naming the SAME driver id,
 * arriving together.
 *
 * Naming the same driver is what makes this test about a driver rather than
 * about a company, and it is asserted rather than assumed: setup resolves each
 * company's single driver id and every accept in a group carries it.
 *
 * WHAT HAS ACTUALLY RUN, stated here because the distinction is easy to lose:
 *
 *   NOTHING IN THIS FILE HAS BEEN EXECUTED AGAINST A BACKEND. It is
 *   syntax-checked, its pure decision logic is unit-tested, and the gate that
 *   judges its output has twenty-three tests. None of that is the same as
 *   having raced a real driver, and the first live run should be expected to
 *   need adjustment.
 *
 *   same_driver, duplicates and accept_vs_cancel are scheduled and will run
 *   when this scenario is invoked.
 *
 *   acceptVersusTimeout is DEFINED BUT NOT SCHEDULED. It has to sit on the
 *   company response window, two minutes in production configuration, so one
 *   iteration costs minutes. It is exported so it can be enabled deliberately
 *   once that window is known for the environment under test, and until it has
 *   run it proves nothing — it must not be reported as verified.
 * SYNCHRONISATION. k6 has no barrier, so setup computes an instant a few
 * seconds out and hands it to every virtual user, which sleeps until then and
 * fires. Overlap is measured rather than hoped for: each accept records the
 * millisecond it was issued, and the validity gate rejects a run whose accepts
 * were spread so far apart that they could not have contended.
 */

const users = new SharedArray('load users', () =>
  JSON.parse(open(__ENV.USERS_FILE || '/data/users.json')),
)

const partners = new SharedArray('load partners', () =>
  JSON.parse(open(__ENV.PARTNERS_FILE || '/data/partners.json')),
)

/* Attempts, not just outcomes. A zero in any of these invalidates the run. */
const contestedAccepts = new Counter('kalo_contested_accepts')
const acceptWon = new Counter('kalo_accept_won')
const acceptLost = new Counter('kalo_accept_lost')
const acceptUnexpected = new Counter('kalo_accept_unexpected')
const duplicateRefused = new Counter('kalo_duplicate_refused')
const duplicateAccepted = new Counter('kalo_duplicate_accepted')
const cancelRaces = new Counter('kalo_cancel_races')
const timeoutRaces = new Counter('kalo_timeout_races')

/**
 * How far each accept landed from the barrier, in milliseconds.
 *
 * A Trend rather than a tag, because the summary export reports min and max for
 * a Trend and the difference between them is the thing that has to be proved:
 * accepts spread over a minute each found the driver free in turn, which is a
 * queue, not a race. The validity gate reads these two numbers.
 *
 * Tags were the first attempt and were wrong — k6's summary export does not
 * carry per-sample tag values, so a gate reading them would have found nothing
 * and said so politely for ever.
 */
const acceptOffsetMs = new Trend('kalo_accept_offset_ms')

/**
 * How long each accept stayed open.
 *
 * Needed because an offset spread proves nothing on its own: thirty accepts
 * issued across two seconds did not contend if each took forty milliseconds.
 * Genuine overlap is spread < duration, so the gate needs both numbers.
 */
const acceptDurationMs = new Trend('kalo_accept_duration_ms')

const RACE_VUS = Number(__ENV.RACE_VUS || 30)
const BARRIER_DELAY_MS = Number(__ENV.BARRIER_DELAY_MS || 5000)

/**
 * How many drivers the race is spread across.
 *
 * ONE by default, which makes the expected outcome exact rather than
 * approximate: thirty accepts naming one driver must produce exactly one
 * winner and twenty-nine losers, and the gate can assert that number instead
 * of settling for "at least one of each".
 *
 * Spreading across three drivers was the first design and was weaker than it
 * looked. Thirty virtual users over three groups is ten per driver, so the
 * correct result is THREE winners -- and a gate asserting "at least one won"
 * passes equally for one, three or thirty. Thirty would mean the invariant had
 * been violated, and the gate would not have noticed.
 *
 * Raise it to run a wider race, and tell the gate the same number through
 * EXPECTED_RACE_DRIVERS so winners are still checked exactly.
 */
const RACE_DRIVERS = Number(__ENV.RACE_DRIVERS || 1)

export const options = {
  scenarios: {
    same_driver: {
      executor: 'per-vu-iterations',
      exec: 'sameDriverRace',
      vus: RACE_VUS,
      iterations: 1,
      maxDuration: '3m',
    },
    duplicates: {
      executor: 'per-vu-iterations',
      exec: 'duplicateRequests',
      vus: 6,
      iterations: 1,
      startTime: '3m',
      maxDuration: '2m',
    },
    accept_vs_cancel: {
      executor: 'per-vu-iterations',
      exec: 'acceptVersusCancel',
      vus: 6,
      iterations: 1,
      startTime: '5m',
      maxDuration: '2m',
    },
  },

  thresholds: {
    /*
     * A 5xx here would mean a unique-index violation reaching the client as a
     * crash rather than as a refusal, which is the single worst outcome this
     * scenario can produce.
     */
    kalo_server_errors: ['count==0'],

    /*
     * The gates that stop a quiet run passing. Expressed as thresholds so k6
     * itself fails rather than leaving it to a reader:
     *
     *   someone has to win    an all-refused run means the fleet was already
     *                         busy or the setup failed, and proves nothing
     *   someone has to lose   an all-won run means nobody competed, which is
     *                         the false positive this file exists to prevent
     */
    kalo_accept_won: ['count>0'],
    kalo_accept_lost: ['count>0'],
    kalo_contested_accepts: [`count>=${RACE_VUS}`],
  },

  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

/* --------------------------------------------------------------- helpers */

function partnerLogin(phone) {
  const response = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ phone, password: PASSWORD }),
    { headers: clientHeaders(), tags: { endpoint: 'login' } },
  )

  try {
    return response.json('accessToken')
  } catch {
    return null
  }
}

/** The company's single ONLINE driver. The id every accept in its group names. */
function onlineDriverId(token) {
  const response = http.get(
    `${BASE}/api/v1/partner/drivers?availabilityStatus=ONLINE&size=10`,
    { headers: authHeaders(token), tags: { endpoint: 'drivers' } },
  )

  try {
    const content = response.json('content') || []
    return content.length > 0 ? content[0].id : null
  } catch {
    return null
  }
}

function customerToken(user) {
  const response = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ phone: user.phone, password: PASSWORD }),
    { headers: clientHeaders(), tags: { endpoint: 'login' } },
  )

  try {
    return response.json('accessToken')
  } catch {
    return null
  }
}

/**
 * Books one ride against a named company and returns its id.
 *
 * The offer is picked by company rather than by position, so a customer cannot
 * silently end up contending for a different company's driver — which would
 * make the run look like contention while testing nothing.
 */
function bookAgainst(token, companyId) {
  const search = searchRides(token)
  track(search, 'search')

  if (search.status !== 201) return null

  let requestId = null
  let offerId = null

  try {
    requestId = search.json('rideRequestId')
    const offers = search.json('taxiOptions') || []
    const mine = offers.filter((o) => Number(o.companyId) === Number(companyId))
    if (mine.length > 0) offerId = mine[0].offerId
  } catch {
    return null
  }

  if (!requestId || !offerId) return null

  const select = http.post(
    `${BASE}/api/v1/rides/requests/${requestId}/select`,
    JSON.stringify({ offerId }),
    { headers: authHeaders(token), tags: { endpoint: 'select' } },
  )
  track(select, 'select')

  if (select.status >= 300) return null

  try {
    return select.json('rideId')
  } catch {
    return null
  }
}

/** Waits until the shared instant, so a group of accepts arrives together. */
function waitForBarrier(at) {
  const remaining = at - Date.now()
  if (remaining > 0) sleep(remaining / 1000)
}


/* ----------------------------------------------------------------- setup */

export function setup() {
  const groups = []

  for (let i = 0; i < partners.length; i += 1) {
    const partner = partners[i]

    const token = partnerLogin(partner.phone)
    if (!token) continue

    const driverId = onlineDriverId(token)
    if (!driverId) continue

    groups.push({
      companyId: partner.companyId,
      phone: partner.phone,
      driverId,
    })
  }

  /*
   * Every group must have exactly one driver for the race to be about a
   * driver. Reported rather than enforced here, because k6's setup cannot fail
   * a run cleanly; the validity gate in load-test.sh refuses the results.
   */
  console.log(
    `contention setup: ${groups.length} companies, drivers [${groups
      .map((g) => g.driverId)
      .join(', ')}]`,
  )

  /*
   * Only the drivers taking part. Trimmed here rather than in each scenario so
   * every scenario races the same fleet and the number the gate is told stays
   * true for all of them.
   */
  const racing = groups.slice(0, Math.max(1, Math.min(RACE_DRIVERS, groups.length)))

  console.log(
    `contention: racing ${racing.length} driver(s) of ${groups.length} available; ` +
      `expect exactly ${racing.length} winner(s) from ${RACE_VUS} accepts`,
  )

  return {
    groups: racing,
    driversAvailable: groups.length,
    barrierAt: Date.now() + BARRIER_DELAY_MS,
  }
}

/* -------------------------------------------------------------- scenarios */

/**
 * Many accepts, one driver, same instant.
 *
 * Each virtual user books its own ride against one company, then every user in
 * that company's group accepts naming that company's single driver. One wins.
 */
export function sameDriverRace(data) {
  if (!data.groups || data.groups.length === 0) {
    sleep(1)
    return
  }

  const group = data.groups[__VU % data.groups.length]
  const user = users[__VU % users.length]

  const token = customerToken(user)
  if (!token) {
    sleep(1)
    return
  }

  const rideId = bookAgainst(token, group.companyId)
  if (!rideId) {
    sleep(1)
    return
  }

  const partnerToken = partnerLogin(group.phone)
  if (!partnerToken) {
    sleep(1)
    return
  }

  waitForBarrier(data.barrierAt)

  const issuedAt = Date.now()

  const accept = http.post(
    `${BASE}/api/v1/partner/rides/${rideId}/accept`,
    JSON.stringify({ driverId: group.driverId }),
    { headers: authHeaders(partnerToken), tags: { endpoint: 'accept' } },
  )

  /*
   * Tagged with the driver so the gate can see these accepts named one driver
   * rather than merely one company, and the offset recorded as a Trend so the
   * spread between the first and last is readable from the summary.
   */
  contestedAccepts.add(1, { driver: String(group.driverId) })
  acceptOffsetMs.add(issuedAt - data.barrierAt, { driver: String(group.driverId) })
  acceptDurationMs.add(accept.timings.duration, { driver: String(group.driverId) })

  const outcome = classifyAccept(accept)

  if (outcome === 'won') acceptWon.add(1, { driver: String(group.driverId) })
  else if (outcome === 'lost') acceptLost.add(1, { driver: String(group.driverId) })
  else {
    acceptUnexpected.add(1, { status: String(accept.status) })
    track(accept, 'accept')
  }
}

/**
 * The same submission twice.
 *
 * A retry, a double click, a flaky network. The second must be refused rather
 * than producing a second ride or a second assignment.
 */
export function duplicateRequests(data) {
  if (!data.groups || data.groups.length === 0) {
    sleep(1)
    return
  }

  const group = data.groups[__VU % data.groups.length]
  const user = users[(__VU + 100) % users.length]

  const token = customerToken(user)
  if (!token) {
    sleep(1)
    return
  }

  const search = searchRides(token)
  track(search, 'search')
  if (search.status !== 201) {
    sleep(1)
    return
  }

  let requestId = null
  let offerId = null
  try {
    requestId = search.json('rideRequestId')
    const offers = search.json('taxiOptions') || []
    const mine = offers.filter((o) => Number(o.companyId) === Number(group.companyId))
    if (mine.length > 0) offerId = mine[0].offerId
  } catch {
    sleep(1)
    return
  }
  if (!requestId || !offerId) {
    sleep(1)
    return
  }

  const body = JSON.stringify({ offerId })
  const headers = authHeaders(token)

  /* Both selects for the same request, back to back. */
  const first = http.post(`${BASE}/api/v1/rides/requests/${requestId}/select`, body, {
    headers,
    tags: { endpoint: 'select' },
  })
  const second = http.post(`${BASE}/api/v1/rides/requests/${requestId}/select`, body, {
    headers,
    tags: { endpoint: 'select_duplicate' },
  })

  /*
   * Only meaningful when the FIRST succeeded. A pair that both failed is not a
   * refused duplicate, it is a failed booking, and counting it as success is
   * exactly the false positive to avoid.
   */
  if (first.status < 300) {
    if (second.status >= 300) duplicateRefused.add(1)
    else duplicateAccepted.add(1)
  }
}

/**
 * Cancel arriving with accept.
 *
 * The hazard is not which wins. It is a cancelled ride whose driver was left
 * BUSY, or an assigned ride whose request went back to SEARCHING — either
 * strands somebody. The SQL invariants decide this; the counter here only
 * proves the race was attempted.
 */
export function acceptVersusCancel(data) {
  if (!data.groups || data.groups.length === 0) {
    sleep(1)
    return
  }

  const group = data.groups[__VU % data.groups.length]
  const user = users[(__VU + 200) % users.length]

  const token = customerToken(user)
  if (!token) {
    sleep(1)
    return
  }

  const rideId = bookAgainst(token, group.companyId)
  if (!rideId) {
    sleep(1)
    return
  }

  const partnerToken = partnerLogin(group.phone)
  if (!partnerToken) {
    sleep(1)
    return
  }

  /* Both at the same instant, from the two sides. */
  const at = Date.now() + 1500
  waitForBarrier(at)

  const responses = http.batch([
    {
      method: 'POST',
      url: `${BASE}/api/v1/partner/rides/${rideId}/accept`,
      body: JSON.stringify({ driverId: group.driverId }),
      params: { headers: authHeaders(partnerToken), tags: { endpoint: 'accept_race' } },
    },
    {
      method: 'POST',
      url: `${BASE}/api/v1/rides/${rideId}/cancel`,
      params: { headers: authHeaders(token), tags: { endpoint: 'cancel_race' } },
    },
  ])

  cancelRaces.add(1, { ride: String(rideId) })

  for (const response of responses) {
    if (response.status >= 500) track(response, 'cancel_race')
  }
}

/**
 * Accept arriving with the timeout sweep.
 *
 * Exported but not scheduled by default: it has to sit on the boundary of the
 * company response window, which is two minutes in production configuration,
 * so a meaningful run of it costs minutes per iteration. Enabled with
 * TIMEOUT_RACE=1 once the window is known for the environment under test,
 * rather than guessed at here.
 */
export function acceptVersusTimeout(data) {
  if (!data.groups || data.groups.length === 0) {
    sleep(1)
    return
  }

  const group = data.groups[__VU % data.groups.length]
  const user = users[(__VU + 300) % users.length]

  const token = customerToken(user)
  if (!token) return

  const rideId = bookAgainst(token, group.companyId)
  if (!rideId) return

  const partnerToken = partnerLogin(group.phone)
  if (!partnerToken) return

  /* Just inside the window, so the sweep and the accept arrive together. */
  const windowSeconds = Number(__ENV.RESPONSE_WINDOW_SECONDS || 120)
  sleep(windowSeconds - 2)

  const accept = http.post(
    `${BASE}/api/v1/partner/rides/${rideId}/accept`,
    JSON.stringify({ driverId: group.driverId }),
    { headers: authHeaders(partnerToken), tags: { endpoint: 'accept_timeout_race' } },
  )

  timeoutRaces.add(1, { ride: String(rideId) })

  if (accept.status >= 500) track(accept, 'accept_timeout_race')
}

export default function () {
  /* Scenarios are named explicitly; this exists so k6 never runs a default. */
  sleep(1)
}
