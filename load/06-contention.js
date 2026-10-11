import http from 'k6/http'
import { sleep } from 'k6'
import { SharedArray } from 'k6/data'
import exec from 'k6/execution'
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
 * Which side won each accept-versus-cancel race.
 *
 * Both outcomes are legitimate -- a customer may cancel while a company is
 * accepting, and either may land first -- so these are counted, not judged.
 * They exist because the outcome has a consequence the gate has to know: an
 * accept that wins leaves a driver holding a ride, which is a driver the race
 * did not assign. Without these counters the gate's cross-check between k6's
 * winners and the database's assigned drivers has no way to allow for it.
 */
const cancelAcceptWon = new Counter('kalo_cancel_accept_won')
const cancelAcceptRefused = new Counter('kalo_cancel_accept_refused')
const cancelAcceptUnexpected = new Counter('kalo_cancel_accept_unexpected')

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

/**
 * The customer bands, declared here because options below is built from them.
 *
 * Each scenario draws its customers from a slice no other scenario touches. A
 * customer may hold only one active ride, so overlapping slices let one
 * scenario's leftovers refuse another's bookings -- and for accept_vs_cancel,
 * which gets one virtual user and one chance, that is the difference between a
 * recorded race and a failed run.
 *
 *   race         [0, RACE_VUS)
 *   duplicates   [RACE_VUS, RACE_VUS + DUPLICATE_VUS)
 *   cancel       [RACE_VUS + DUPLICATE_VUS, ... + CANCEL_VUS)
 *   timeout      after those, for when it is ever scheduled
 *
 * Laid out from RACE_VUS rather than from __VU, which k6 numbers across the
 * whole test rather than per scenario, so the bands cannot drift into each
 * other whatever ids it hands out.
 */
const DUPLICATE_VUS = 6
const CANCEL_VUS = 1

const RACE_BAND = 0
const DUPLICATE_BAND = RACE_VUS
const CANCEL_BAND = RACE_VUS + DUPLICATE_VUS
const TIMEOUT_BAND = RACE_VUS + DUPLICATE_VUS + CANCEL_VUS

/**
 * How many customers the fixture must hold for the bands not to wrap onto each
 * other. scripts/load-contention-run.sh refuses a shorter fixture.
 */
const CUSTOMERS_NEEDED = TIMEOUT_BAND + 1

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
      vus: DUPLICATE_VUS,
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
      vus: CANCEL_VUS,
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

/**
 * A customer reserved for one scenario, from the band set out above.
 *
 * The scenarios used to pick with arbitrary offsets -- `__VU`, `__VU + 100`,
 * `__VU + 200` -- taken modulo the fixture length, and those bands overlap for
 * any realistic fixture size. That is not a tidiness problem. A customer may
 * hold only one active ride, so if accept_vs_cancel drew the customer that had
 * just won the main race, its booking would be refused by the unique index, it
 * would return having attempted nothing, and the gate would fail the whole run
 * for a scenario that recorded no race. One virtual user means one chance.
 *
 * Bands are laid out from RACE_VUS so they cannot overlap whatever k6 chooses
 * for __VU, which is numbered across the whole test and not per scenario:
 *
 *   race         [0, RACE_VUS)
 *   duplicates   [RACE_VUS, RACE_VUS + DUPLICATE_VUS)
 *   cancel       [RACE_VUS + DUPLICATE_VUS, ... + CANCEL_VUS)
 *   timeout      after those, for when it is ever scheduled
 *
 * The fixture has to be long enough to hold them all; the runner refuses a
 * shorter one rather than letting the bands wrap round onto each other.
 */
function reservedCustomer(bandStart, bandSize) {
  /*
   * The slot comes from exec.scenario.iterationInTest, NOT from __VU.
   *
   * __VU is numbered across the whole test and k6 does not hand a scenario a
   * contiguous block of ids. Measured, not assumed: in an eight-user race
   * beside two other scenarios, k6 gave the race __VU 1-7 and 9, and gave 8 to
   * the cancel scenario. `(__VU - 1) % 8` then maps both 1 and 9 to slot 0, so
   * two racing users would have drawn the same customer -- and a customer may
   * hold only one active ride, so the second booking would be refused by the
   * unique index from migration 017. That is one lost participant, which the
   * gate rightly fails the whole run for.
   *
   * iterationInTest is scenario-local and contiguous: with one iteration per
   * virtual user it runs 0..vus-1 within each scenario, whatever ids k6 chose.
   * The modulo is kept only so a future run with more iterations per user
   * stays inside its band rather than reaching into the next one's.
   */
  const slot = bandSize > 0 ? exec.scenario.iterationInTest % bandSize : 0
  return users[(bandStart + slot) % users.length]
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
   * The customer bands have to fit inside the fixture.
   *
   * Said here as well as in the runner's preconditions because this is the
   * only place that knows how the bands are laid out, and a fixture too short
   * makes them wrap onto each other -- at which point one scenario's active
   * ride silently refuses another's booking. Reported rather than thrown,
   * since k6's setup cannot fail a run cleanly; the shortfall shows up as a
   * scenario with no attempts, which the gate refuses.
   */
  if (users.length < CUSTOMERS_NEEDED) {
    console.log(
      `contention WARNING: ${users.length} customers in the fixture but ` +
        `${CUSTOMERS_NEEDED} are needed to keep the scenarios' customer bands ` +
        'apart; they will overlap and bookings will be refused by the ' +
        'one-active-ride-per-customer index',
    )
  } else {
    console.log(
      `contention: ${users.length} customers, ${CUSTOMERS_NEEDED} needed; ` +
        `bands race[0,${RACE_VUS}) duplicates[${DUPLICATE_BAND},` +
        `${DUPLICATE_BAND + DUPLICATE_VUS}) cancel[${CANCEL_BAND},` +
        `${CANCEL_BAND + CANCEL_VUS})`,
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
  const user = reservedCustomer(RACE_BAND, RACE_VUS)

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
  const user = reservedCustomer(DUPLICATE_BAND, DUPLICATE_VUS)

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
 * strands somebody. The SQL invariants decide that; the counters here say the
 * race happened and which side took it.
 *
 * ONE virtual user, and therefore ONE race per run. What that is worth is
 * worth being exact about, because a single sample is easy to over-read.
 *
 * WHAT IT ESTABLISHES
 *   an accept and a cancel for the same ride were issued concurrently --
 *     http.batch dispatches both in parallel, so this does not depend on a
 *     barrier the way the main race does
 *   the pair was decided, and the gate requires the outcomes to sum to the
 *     number of races, so a race nobody classified fails the run
 *   neither side answered 5xx
 *   afterwards no driver holds two active rides, no customer does, no driver
 *     is BUSY without one, and no assigned ride has a request back to
 *     SEARCHING
 *   any driver this leaves assigned is accounted for in the gate's bound on
 *     assigned drivers, rather than being mistaken for a second race winner
 *
 * WHAT IT DOES NOT
 *   which side ought to win -- both are legitimate and nothing asserts either
 *   anything statistical: one interleaving is one interleaving, and correct
 *     handling is indistinguishable from a lucky ordering at n=1. More races
 *     need more cancel companies (seed RACE_DRIVERS + 1 + n and raise the VUs
 *     to n), not more users against the one driver, which cannot work
 *   overlap is not MEASURED here. The main race records the instant each
 *     accept opened and closed; this records nothing of the kind, so the
 *     concurrency rests on http.batch's semantics rather than on evidence
 *   the cancel's own response is not classified -- only the accept's. A cancel
 *     refused for an unexpected reason is invisible unless it is a 5xx
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
  const user = reservedCustomer(CANCEL_BAND, CANCEL_VUS)

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

  /*
   * Which side the race fell to, which until now was not recorded at all.
   *
   * It has to be, because this scenario can leave a driver holding a ride. The
   * gate cross-checks the race's winners against the drivers the database
   * actually assigned, and that check read every driver assigned during the
   * run -- so an accept winning here would have added a second assigned driver
   * to a race that expected one, and the gate would have reported a violation
   * that had not happened. It never surfaced on the first real run only
   * because this scenario was starved and recorded nothing.
   *
   * Either side winning is legitimate, so this is counted rather than judged.
   * Only an outcome that is neither is a finding.
   */
  const outcome = classifyAccept(responses[0])

  if (outcome === 'won') cancelAcceptWon.add(1)
  else if (outcome === 'lost') cancelAcceptRefused.add(1)
  else {
    cancelAcceptUnexpected.add(1, { status: String(responses[0].status) })
    track(responses[0], 'cancel_race')
  }

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
  const user = reservedCustomer(TIMEOUT_BAND, 1)

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
