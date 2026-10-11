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
 * fires. That is how contention is PRODUCED.
 *
 * How it is PROVED is separate and deliberately so. Each accept records the
 * instant it opened and the instant it closed, and overlap is read off those
 * directly: max(start) < min(end) means every accept was in flight when the
 * last one was issued. Nothing infers overlap from the barrier any more.
 *
 * That separation is the lesson of the first real run, which recorded a single
 * barrier offset per accept and reported a constant -1150ms for all of them --
 * an impossible value, since waitForBarrier returns only once the barrier has
 * passed. The gate's rule at the time was spread < duration, and a constant
 * has a spread of zero, so the broken metric read as perfect synchronisation.
 * Evidence that cannot distinguish itself from its own absence is not
 * evidence, so the barrier is now used to cause the race and measured values
 * are used to judge it.
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
 * When each accept opened and closed, in milliseconds since the run epoch.
 *
 * THIS IS THE EVIDENCE THE SCENARIO EXISTS TO PRODUCE, and it is recorded as
 * two absolute instants rather than as one derived number because the derived
 * number failed.
 *
 * The first real run recorded a single barrier OFFSET per accept and reported
 * avg=min=med=max=-1150ms across every sample. That value is not merely wrong,
 * it is impossible: waitForBarrier() returns only once Date.now() has reached
 * the barrier, so the offset it feeds this metric cannot be negative. A
 * constant impossible number is the signature of a measurement that was never
 * really taken -- and because k6's summary export carries no sample count for
 * a Trend, nothing downstream could tell one bad sample from twenty-nine.
 *
 * Two instants fix both halves of that. Overlap becomes a comparison between
 * measured values instead of an inference from a spread:
 *
 *   max(start) < min(end)   every accept was open when the last was issued
 *
 * and because both are measured from an instant captured in setup, every
 * sample must be positive and every end must follow its start. A degenerate or
 * impossible reading now contradicts itself instead of looking like perfect
 * synchronisation. load/lib/contention.js:assessIntervalOverlap does the
 * judging; the gate refuses the run when it cannot.
 */
const acceptStartMs = new Trend('kalo_accept_start_ms')
const acceptEndMs = new Trend('kalo_accept_end_ms')

/**
 * How many samples each timing Trend actually received.
 *
 * A Counter, because a Counter is the only thing k6's summary export reports a
 * count for. Without this the gate cannot distinguish a Trend holding one
 * sample from a Trend holding thirty identical ones, which is precisely the
 * ambiguity that let the first run's broken offset metric pass as evidence.
 */
const acceptTimingSamples = new Counter('kalo_accept_timing_samples')

/**
 * The barrier offset, kept only as a diagnostic.
 *
 * No longer load-bearing: nothing decides overlap from it. It stays because if
 * it ever again reports an impossible value, that is worth seeing next to the
 * timings that replaced it. The gate asserts it is non-negative and refuses
 * the run if it is not, since a negative reading there means the clock or the
 * barrier is lying and the other timings share both.
 */
const acceptOffsetMs = new Trend('kalo_accept_offset_ms')
const acceptOffsetSamples = new Counter('kalo_accept_offset_samples')

/**
 * Every virtual user that entered the race, and every reason one did not reach
 * an accept.
 *
 * The first real run put thirty users in and recorded twenty-nine contested
 * accepts. One booking failed, the scenario returned quietly, and the run was
 * reported on twenty-nine. An attempt that disappears is an outcome nobody
 * knows, so each reason is now counted separately and the gate requires the
 * arithmetic to close: entered == contested + the reasons.
 */
const raceEntered = new Counter('kalo_race_entered')
const raceNoFleet = new Counter('kalo_race_no_fleet')
const raceNoCustomerToken = new Counter('kalo_race_no_customer_token')
const raceNoBooking = new Counter('kalo_race_no_booking')
const raceNoPartnerToken = new Counter('kalo_race_no_partner_token')

/**
 * Attempts made by the other two scheduled scenarios.
 *
 * Both recorded nothing at all in the first real run: with one raced driver
 * they targeted the same company, whose only driver was BUSY by the time they
 * started, so every virtual user searched, found no offer and returned. The
 * gate called that a note and passed. These counters make the absence
 * measurable, and the gate now fails a scheduled scenario that attempted
 * nothing.
 */
const duplicateAttempts = new Counter('kalo_duplicate_attempts')
const cancelAttempts = new Counter('kalo_cancel_attempts')

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
    /*
     * Six users, one company, one driver -- and all six can genuinely run.
     *
     * Nothing here accepts a ride, so the driver stays ONLINE and keeps
     * appearing in search for the whole scenario; each user books as a
     * different customer and submits its own request twice. Capacity is not
     * the constraint it is for accept_vs_cancel below.
     */
    duplicates: {
      executor: 'per-vu-iterations',
      exec: 'duplicateRequests',
      vus: 6,
      iterations: 1,
      startTime: '3m',
      maxDuration: '2m',
    },
    /*
     * ONE user per cancel driver, because one is all a driver can support.
     *
     * This scenario does accept rides. The first user to win leaves the
     * driver BUSY for the rest of the run, so every later user searches,
     * finds no offer and returns with nothing -- and six users against one
     * driver schedules five attempts that cannot succeed. That is the shape
     * the whole batch exists to remove: work that is certain to be skipped,
     * counted as if it had been tried.
     *
     * One decisive race is enough for what this tests. The hazards are a
     * cancelled ride whose driver was left BUSY and an assigned ride whose
     * request went back to SEARCHING, and the SQL invariants catch either
     * from a single occurrence. More races need more cancel companies, not
     * more users: seed RACE_DRIVERS + 1 + n companies and raise this to n.
     */
    accept_vs_cancel: {
      executor: 'per-vu-iterations',
      exec: 'acceptVersusCancel',
      vus: 1,
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

    /*
     * EXACT, not a floor.
     *
     * This was `count>=RACE_VUS` and the first real run crossed it at 29 of
     * 30 -- one booking had failed. k6 reported the threshold breach and the
     * validity gate, which had its own floor of two, passed the run anyway.
     * Both numbers now come from RACE_VUS and both are equalities, so a
     * missing participant fails the run instead of shrinking it.
     */
    kalo_race_entered: [`count==${RACE_VUS}`],
    kalo_contested_accepts: [`count==${RACE_VUS}`],

    /*
     * One timing sample per accept. A Trend with fewer samples than there were
     * accepts is a Trend whose min and max describe part of the run, which is
     * how a single impossible reading passed for a measurement of thirty.
     */
    kalo_accept_timing_samples: [`count==${RACE_VUS}`],

    /*
     * Positive evidence from every scheduled scenario. Both of these recorded
     * nothing whatsoever in the first real run and the run was still reported
     * as valid; a scheduled scenario that attempted nothing has to fail.
     */
    kalo_duplicate_attempts: ['count>0'],
    kalo_cancel_attempts: ['count>0'],
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

/**
 * The partner's own company id.
 *
 * Asked for rather than read from partners.json, which carries only a phone —
 * the first live run of this scenario bailed on every virtual user because
 * `partner.companyId` was undefined, so no offer ever matched the company and
 * nothing was ever booked. The gate would have caught it as zero contested
 * accepts, but depending on an undocumented field in a fixture written by a
 * different script was the actual mistake.
 *
 * The scenario already logs in as this partner, so one more call costs nothing
 * and the script becomes self-sufficient.
 */
function companyIdFor(token) {
  const response = http.get(`${BASE}/api/v1/partner/me`, {
    headers: authHeaders(token),
    tags: { endpoint: 'partner_me' },
  })

  try {
    return response.json('companyId')
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

    const companyId = companyIdFor(token)
    if (!companyId) continue

    groups.push({
      companyId,
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
   * Disjoint fleets, one per scenario.
   *
   * The first real run gave every scenario the same trimmed fleet, and with
   * one raced driver that meant all three scenarios targeted one company. By
   * the time `duplicates` started, the race had already made that company's
   * only driver BUSY, so every virtual user searched, found no offer and
   * returned. Both later scenarios recorded zero attempts and the gate passed
   * the run anyway.
   *
   * The race takes the first RACE_DRIVERS companies; the other scenarios take
   * one company each from what is left, so the race cannot starve them. A
   * shortfall is reported in the setup data rather than thrown, because k6's
   * setup cannot fail a run cleanly -- the gate refuses a run whose scenarios
   * had no fleet to work with.
   */
  const racing = groups.slice(0, Math.max(1, Math.min(RACE_DRIVERS, groups.length)))
  const spare = groups.slice(racing.length)

  const duplicateGroups = spare.slice(0, 1)
  const cancelGroups = spare.slice(1, 2)

  console.log(
    `contention: racing ${racing.length} driver(s) of ${groups.length} available; ` +
      `expect exactly ${racing.length} winner(s) from ${RACE_VUS} accepts`,
  )
  console.log(
    `contention fleets: race=[${racing.map((g) => g.driverId).join(', ')}] ` +
      `duplicates=[${duplicateGroups.map((g) => g.driverId).join(', ')}] ` +
      `cancel=[${cancelGroups.map((g) => g.driverId).join(', ')}]`,
  )

  if (duplicateGroups.length === 0 || cancelGroups.length === 0) {
    console.log(
      `contention WARNING: ${groups.length} company(ies) available but ` +
        `${racing.length} raced leaves too few for the duplicate and cancel ` +
        'scenarios; seed at least RACE_DRIVERS + 2 companies',
    )
  }

  /*
   * The instant every timing is measured from.
   *
   * Captured once here so each virtual user reports small positive numbers
   * instead of epoch milliseconds, and -- the reason it matters -- so that a
   * negative reading is provably impossible rather than merely surprising.
   * Setup finishes before any iteration starts, so no accept can open before
   * this instant. The gate treats a negative timing as broken instrumentation.
   */
  const epochMs = Date.now()

  return {
    raceGroups: racing,
    duplicateGroups,
    cancelGroups,
    driversAvailable: groups.length,
    epochMs,
    barrierAt: epochMs + BARRIER_DELAY_MS,
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
  /*
   * Counted before anything can go wrong, so the gate has a denominator.
   *
   * Every `return` below records why this virtual user never reached an
   * accept. The first real run had none of this: one booking failed, the
   * function returned, and a thirty-user race was reported on twenty-nine
   * attempts with nothing naming the thirtieth.
   */
  raceEntered.add(1)

  if (!data.raceGroups || data.raceGroups.length === 0) {
    raceNoFleet.add(1)
    sleep(1)
    return
  }

  const group = data.raceGroups[__VU % data.raceGroups.length]
  const user = users[__VU % users.length]

  const token = customerToken(user)
  if (!token) {
    raceNoCustomerToken.add(1)
    sleep(1)
    return
  }

  const rideId = bookAgainst(token, group.companyId)
  if (!rideId) {
    raceNoBooking.add(1)
    sleep(1)
    return
  }

  const partnerToken = partnerLogin(group.phone)
  if (!partnerToken) {
    raceNoPartnerToken.add(1)
    sleep(1)
    return
  }

  waitForBarrier(data.barrierAt)

  const driver = String(group.driverId)

  /*
   * The two instants that decide whether this run proved anything, both read
   * from the same clock in the same virtual user, either side of the one call
   * that matters. Everything the gate concludes about overlap comes from
   * comparing these across users: max(start) < min(end) means every accept was
   * open at the moment the last one was issued.
   *
   * RESOLUTION. Date.now() is whole milliseconds, and k6 exposes no
   * higher-resolution clock to a script. That is ample here and worth stating
   * so nobody has to work it out again: an accept takes 100-170ms against the
   * load stack, and the barrier puts the starts tens of milliseconds apart at
   * most, so the quantity being compared is an order of magnitude larger than
   * the measurement step. Sub-millisecond detail is available where it exists
   * anyway -- accept.timings.duration is a float and is recorded as one.
   */
  const startedAt = Date.now()

  const accept = http.post(
    `${BASE}/api/v1/partner/rides/${rideId}/accept`,
    JSON.stringify({ driverId: group.driverId }),
    { headers: authHeaders(partnerToken), tags: { endpoint: 'accept' } },
  )

  const endedAt = Date.now()

  /*
   * Recorded relative to the run epoch from setup, which keeps the numbers
   * small and makes a negative one impossible: setup completes before any
   * iteration begins.
   */
  acceptStartMs.add(startedAt - data.epochMs, { driver })
  acceptEndMs.add(endedAt - data.epochMs, { driver })
  acceptTimingSamples.add(1, { driver })

  /* Diagnostic only. Nothing decides overlap from this any more. */
  acceptOffsetMs.add(startedAt - data.barrierAt, { driver })
  acceptOffsetSamples.add(1, { driver })

  /*
   * Tagged with the driver so the gate can see these accepts named one driver
   * rather than merely one company.
   */
  contestedAccepts.add(1, { driver })
  acceptDurationMs.add(accept.timings.duration, { driver })

  /*
   * The raw numbers for a handful of users, in the log where a human can read
   * them. The aggregate metrics tell you the shape of the run; these tell you
   * whether the aggregate is believable. Had the first run printed these, the
   * impossible offset would have been obvious in the log rather than taking a
   * second pass over the summary to find.
   */
  if (__VU <= 5) {
    console.log(
      `race VU=${__VU} driver=${driver} start=${startedAt - data.epochMs}ms ` +
        `end=${endedAt - data.epochMs}ms ` +
        `dur=${accept.timings.duration.toFixed(2)}ms ` +
        `offset=${startedAt - data.barrierAt}ms status=${accept.status}`,
    )
  }

  const outcome = classifyAccept(accept)

  if (outcome === 'won') acceptWon.add(1, { driver })
  else if (outcome === 'lost') acceptLost.add(1, { driver })
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
  /*
   * Its OWN fleet, not the raced one.
   *
   * Sharing the race's fleet is why this scenario recorded nothing in the
   * first real run: the race had already taken that company's only driver, so
   * no offer ever matched and every virtual user returned before attempting a
   * duplicate. The gate noted it and passed the run.
   */
  const fleet = data.duplicateGroups

  if (!fleet || fleet.length === 0) {
    sleep(1)
    return
  }

  /*
   * Counted before the first call that can fail, so the gate can tell "this
   * scenario was never given a chance to run" from "this scenario ran and
   * found nothing wrong". Those two look identical in a counter that only
   * records outcomes.
   */
  duplicateAttempts.add(1)

  const group = fleet[__VU % fleet.length]
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
  /* Its own fleet, for the same reason as duplicateRequests above. */
  const fleet = data.cancelGroups

  if (!fleet || fleet.length === 0) {
    sleep(1)
    return
  }

  cancelAttempts.add(1)

  const group = fleet[__VU % fleet.length]
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
 * NOT VERIFIED. This function has never been executed against a stack. It is
 * exported but deliberately absent from options.scenarios, so nothing below is
 * evidence of anything -- the accept-versus-timeout interleaving remains an
 * untested claim and must be reported as such until a run schedules it.
 *
 * Unscheduled because it has to sit on the boundary of the company response
 * window, which is two minutes in production configuration, so a meaningful
 * run of it costs minutes per iteration. Scheduling it is a deliberate
 * decision for an environment whose window is known, not a default.
 *
 * It takes the cancel scenario's fleet when it is scheduled, since the two
 * never run together, and the gate prints it as NOT VERIFIED whenever its
 * counter is absent rather than treating the absence as a pass.
 */
export function acceptVersusTimeout(data) {
  const fleet = data.cancelGroups

  if (!fleet || fleet.length === 0) {
    sleep(1)
    return
  }

  const group = fleet[__VU % fleet.length]
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
