/**
 * Unit tests for the contention scenario's decision logic.
 *
 *   node scripts/test-contention-logic.mjs
 *
 * load/lib/contention.js holds the two judgements that can be wrong in a way no
 * stack would reveal: whether a response means the race was lost or the system
 * broke, and whether a set of accepts actually overlapped. Both are pure, so
 * both are testable here rather than only in a live run.
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  assessCancelRace,
  assessIntervalOverlap,
  classifyAccept,
  classifyCancel,
  classifyDuplicate,
  reconcileAccepts,
} from '../load/lib/contention.js'

let passed = 0
const check = (label, fn) => {
  try {
    fn()
    passed += 1
    console.log(`  ok   ${label}`)
  } catch (error) {
    console.log(`  FAIL ${label}\n       ${error.message}`)
    process.exitCode = 1
  }
}

console.log('contention decision logic')

/* ------------------------------------------------------------ classify */

check('a 200 is a win', () => {
  assert.equal(classifyAccept({ status: 200 }), 'won')
})

check('a 409 is a loss without reading the body', () => {
  assert.equal(classifyAccept({ status: 409 }), 'lost')
})

check('the driver already taken is a loss', () => {
  assert.equal(
    classifyAccept({ status: 400, body: '{"message":"Driver must be online and available"}' }),
    'lost',
  )
})

check('a ride no longer REQUESTED is a loss', () => {
  assert.equal(
    classifyAccept({ status: 400, body: '{"message":"Only a requested ride can be accepted"}' }),
    'lost',
  )
})

check('the active-ride constraint is a loss', () => {
  assert.equal(
    classifyAccept({ status: 400, body: '{"message":"Driver already has an active ride"}' }),
    'lost',
  )
})

/*
 * The distinction the whole scenario rests on. A 500 is a unique-index
 * violation reaching the client as a crash, and counting it as a loss would
 * report the worst outcome available here as correct concurrency handling.
 */
check('a 500 is never a loss', () => {
  assert.equal(classifyAccept({ status: 500, body: 'boom' }), 'unexpected')
})

check('an unexplained 400 is not quietly a loss', () => {
  assert.equal(
    classifyAccept({ status: 400, body: '{"message":"Final amount must be positive"}' }),
    'unexpected',
  )
})

check('a 429 is not a loss — the limiter is not the race', () => {
  assert.equal(classifyAccept({ status: 429 }), 'unexpected')
})

check('a malformed response is unexpected, not a win', () => {
  assert.equal(classifyAccept(null), 'unexpected')
  assert.equal(classifyAccept({}), 'unexpected')
})


/* ------------------------------------------------------------- overlap */

/* The window a healthy race produces: starts bunched, ends well after. */
const window = (over = {}) => ({
  earliestStartMs: 0,
  latestStartMs: 40,
  earliestEndMs: 120,
  latestEndMs: 200,
  samples: 30,
  ...over,
})

check('the last accept opening before the first closes is full overlap', () => {
  const r = assessIntervalOverlap(window())
  assert.equal(r.overlapped, true)
  assert.equal(r.allOverlapped, true)
  assert.match(r.reason, /every accept was in flight at once/)
})

check('one millisecond of overlap still counts', () => {
  assert.equal(assessIntervalOverlap(window({ latestStartMs: 119 })).allOverlapped, true)
})

check('the last accept opening exactly as the first closes is not overlap', () => {
  const r = assessIntervalOverlap(window({ latestStartMs: 120 }))
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /queued rather than raced/)
})

/*
 * The vacuous branch this rule used to have.
 *
 * `latestStart < latestEnd` reads like "at least two accepts overlapped" and
 * is true of any run at all, because max(end) is normally the end of the very
 * accept that started at max(start). Sixty seconds of strictly sequential
 * requests satisfied it. There is no partial verdict any more, and this case
 * exists to keep one from coming back.
 */
check('a minute of sequential accepts is not overlap, partial or otherwise', () => {
  const r = assessIntervalOverlap(
    window({ latestStartMs: 60000, earliestEndMs: 120, latestEndMs: 60080 }),
  )
  assert.equal(r.overlapped, false)
  assert.equal(r.allOverlapped, false)
  assert.match(r.reason, /queued rather than raced/)
})

check('starts spread wider than an accept lasts is not overlap', () => {
  assert.equal(
    assessIntervalOverlap(window({ latestStartMs: 150, earliestEndMs: 100, latestEndMs: 250 }))
      .overlapped,
    false,
  )
})

/* ------------------------------------------- overlap: degenerate readings */

/*
 * The shape the first real run actually produced. Every sample identical,
 * which the old rule read as a spread of zero and therefore as flawless
 * synchronisation. Thirty independent clock reads do not agree to the
 * millisecond; a constant does.
 */
check('identical timings on every accept are a constant, not synchronisation', () => {
  const r = assessIntervalOverlap(
    window({ earliestStartMs: 500, latestStartMs: 500, earliestEndMs: 600, latestEndMs: 600 }),
  )
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /a constant, not a measurement/)
})

check('timestamps measured from setup cannot be negative', () => {
  const r = assessIntervalOverlap(window({ earliestStartMs: -20 }))
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /instrumentation is broken/)
})

check('an accept cannot close before any opened', () => {
  const r = assessIntervalOverlap(
    window({ earliestStartMs: 900, latestStartMs: 950, earliestEndMs: 100, latestEndMs: 200 }),
  )
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /disagrees with itself/)
})

check('missing timings are not overlap', () => {
  assert.equal(assessIntervalOverlap(undefined).overlapped, false)
  assert.equal(assessIntervalOverlap({}).overlapped, false)
  assert.equal(assessIntervalOverlap(window({ latestStartMs: undefined })).overlapped, false)
  assert.equal(assessIntervalOverlap(window({ earliestEndMs: NaN })).overlapped, false)
})

check('one sample has nothing to overlap with', () => {
  const r = assessIntervalOverlap(window({ samples: 1 }))
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /nothing to overlap with/)
})

check('a missing sample count is not taken on trust', () => {
  assert.equal(assessIntervalOverlap(window({ samples: undefined })).overlapped, false)
})

/* --------------------------------------------------------- reconciliation */

check('a race in which everyone took part reconciles', () => {
  const r = reconcileAccepts({
    entered: 30,
    contested: 30,
    won: 1,
    lost: 29,
    unexpected: 0,
    bailed: { no_booking: 0 },
  })
  assert.equal(r.balanced, true)
  assert.deepEqual(r.problems, [])
})

/*
 * The first real run: thirty users entered, twenty-nine reached an accept, and
 * nothing said what became of the thirtieth.
 */
check('a participant that vanished without a reason is named', () => {
  const r = reconcileAccepts({
    entered: 30,
    contested: 29,
    won: 1,
    lost: 28,
    unexpected: 0,
    bailed: { no_booking: 0 },
  })
  assert.equal(r.balanced, false)
  assert.match(r.problems[0], /1 attempt\(s\) vanished without explanation/)
})

check('a participant that bailed for a recorded reason reconciles', () => {
  const r = reconcileAccepts({
    entered: 30,
    contested: 29,
    won: 1,
    lost: 28,
    unexpected: 0,
    bailed: { no_booking: 1 },
  })
  assert.equal(r.balanced, true)
})

check('the reason is reported alongside the shortfall', () => {
  const r = reconcileAccepts({
    entered: 30,
    contested: 28,
    won: 1,
    lost: 27,
    unexpected: 0,
    bailed: { no_booking: 1 },
  })
  assert.equal(r.balanced, false)
  assert.match(r.problems[0], /no_booking=1/)
})

check('outcomes that do not sum to the attempts are named separately', () => {
  const r = reconcileAccepts({
    entered: 30,
    contested: 30,
    won: 1,
    lost: 20,
    unexpected: 0,
    bailed: {},
  })
  assert.equal(r.balanced, false)
  assert.match(r.problems[0], /outcomes are unaccounted for/)
})

check('both sums can fail at once and both are reported', () => {
  const r = reconcileAccepts({
    entered: 30,
    contested: 25,
    won: 1,
    lost: 20,
    unexpected: 0,
    bailed: {},
  })
  assert.equal(r.balanced, false)
  assert.equal(r.problems.length, 2)
})

check('an empty tally does not read as balanced by accident', () => {
  /* Zero entered and zero contested genuinely balances; the gate refuses it
   * for having no participants, which is a different complaint. */
  assert.equal(reconcileAccepts({}).balanced, true)
  assert.equal(reconcileAccepts({ entered: 30 }).balanced, false)
})


/* ---------------------------------------------------------- cancel side */

/*
 * RideServiceImpl.cancelRide refuses exactly five states: IN_PROGRESS,
 * COMPLETED, CANCELLED, DECLINED and NO_RESPONSE. All five are legal API
 * behaviour, and none of them is reachable in the accept-versus-cancel race —
 * which is what makes a refused cancel there a finding rather than a tie.
 */
check('a 200 means the ride was cancelled', () => {
  assert.equal(classifyCancel({ status: 200 }), 'cancelled')
})

check('a refusal naming a non-cancellable state is a refusal', () => {
  for (const message of [
    'Ride cannot be cancelled after it has started',
    'Completed ride cannot be cancelled',
    'Ride is already cancelled',
    'Declined ride cannot be cancelled',
    'Ride is no longer active',
  ]) {
    assert.equal(
      classifyCancel({ status: 400, body: JSON.stringify({ message }) }),
      'refused',
      message,
    )
  }
})

check('a 500 on cancel is never a refusal', () => {
  assert.equal(classifyCancel({ status: 500, body: 'boom' }), 'unexpected')
})

check('an unexplained 400 on cancel is not quietly a refusal', () => {
  assert.equal(
    classifyCancel({ status: 400, body: '{"message":"Something else"}' }),
    'unexpected',
  )
})

check('a malformed cancel response is unexpected, not success', () => {
  assert.equal(classifyCancel(null), 'unexpected')
  assert.equal(classifyCancel({}), 'unexpected')
})

/* ------------------------------------------------ the pair, against the law */

check('the cancel landing first and refusing the accept is legal', () => {
  const r = assessCancelRace('lost', 'cancelled')
  assert.equal(r.legal, true)
  assert.equal(r.leavesAssigned, false)
})

/*
 * The ordering that matters most, and the one that corrected the gate: the
 * accept lands, DRIVER_ASSIGNED is cancellable, so the cancel succeeds anyway
 * and frees the driver. Nobody is left holding the ride, which is why the
 * gate's assigned-driver count is exact rather than allowing for this.
 */
check('the accept landing first and then being cancelled is legal', () => {
  const r = assessCancelRace('won', 'cancelled')
  assert.equal(r.legal, true)
  assert.equal(r.leavesAssigned, false)
  assert.match(r.reason, /undid it/)
})

check('a refused cancel is illegal whichever way the accept went', () => {
  for (const accept of ['won', 'lost']) {
    const r = assessCancelRace(accept, 'refused')
    assert.equal(r.legal, false, accept)
    assert.match(r.reason, /every state the accept can leave the ride in is cancellable/)
  }
})

check('a cancel that failed for an unclassified reason is illegal', () => {
  assert.equal(assessCancelRace('won', 'unexpected').legal, false)
  assert.equal(assessCancelRace('lost', 'unexpected').legal, false)
})

check('an unclassified accept is illegal even when the cancel succeeded', () => {
  const r = assessCancelRace('unexpected', 'cancelled')
  assert.equal(r.legal, false)
  assert.match(r.reason, /the accept failed for an unclassified reason/)
})

/* ------------------------------------------------------ duplicate side */

/*
 * Selecting an offer moves the request from SEARCHING to SELECTED, so the
 * second select is refused with "Ride request is not available for taxi
 * selection" as a 400. That one refusal is the evidence the scenario exists
 * for.
 */
check('the SELECTED-request refusal is a refused duplicate', () => {
  assert.equal(
    classifyDuplicate({
      status: 400,
      body: '{"message":"Ride request is not available for taxi selection"}',
    }),
    'refused',
  )
})

check('a conflict is a refused duplicate without reading the body', () => {
  assert.equal(classifyDuplicate({ status: 409 }), 'refused')
})

check('a second ride created from one request is the defect', () => {
  assert.equal(classifyDuplicate({ status: 201 }), 'accepted')
})

/*
 * The inversion this replaced. The scenario judged the second submission by
 * `status >= 300`, so a 500 counted as a refusal and therefore as correct
 * duplicate handling: the server crashing on a retry read as the behaviour
 * under test working.
 */
check('a 500 on the duplicate is never a refusal', () => {
  assert.equal(classifyDuplicate({ status: 500, body: 'boom' }), 'unexpected')
})

check('a rate-limited duplicate is not duplicate handling', () => {
  assert.equal(classifyDuplicate({ status: 429 }), 'unexpected')
})

check('an unexplained 400 on the duplicate is not quietly a refusal', () => {
  assert.equal(
    classifyDuplicate({ status: 400, body: '{"message":"Final amount must be positive"}' }),
    'unexpected',
  )
})

check('a malformed duplicate response is unexpected, not refused', () => {
  assert.equal(classifyDuplicate(null), 'unexpected')
  assert.equal(classifyDuplicate({}), 'unexpected')
})

/* ------------------------------------- the scenario's customer reservation */

/*
 * Static assertions on load/06-contention.js.
 *
 * The customer each virtual user books as cannot be unit-tested here -- it
 * needs k6's execution context -- but the one thing it must not do is
 * checkable from the source, and it is worth checking because the obvious
 * implementation is wrong.
 *
 * `(__VU - 1) % bandSize` looks correct and is not. k6 numbers __VU across the
 * whole test and does not give a scenario a contiguous block: measured with
 * k6 itself, an eight-user race beside two other scenarios got __VU 1-7 and 9,
 * with 8 going to the cancel scenario. Both 1 and 9 then map to slot 0, two
 * racing users draw the same customer, and the second booking is refused by
 * the one-active-ride-per-customer index -- a lost participant, which fails
 * the whole run.
 *
 * exec.scenario.iterationInTest is scenario-local and contiguous, so that is
 * what has to be used.
 */
const scenarioSource = readFileSync(
  new URL('../load/06-contention.js', import.meta.url),
  'utf8',
)

/*
 * Comments stripped before anything is matched against the source.
 *
 * These checks assert what the scenario DOES, and its comments quote what it
 * used to do -- including the `second.status >= 300` test that counted a 500
 * as a refusal. Matching the raw file found that sentence and reported the bug
 * as still present. Explaining a fix must not be mistaken for not having
 * applied it.
 */
const BLOCK_COMMENT = new RegExp('/\\*[\\s\\S]*?\\*/', 'g')
const LINE_COMMENT = new RegExp('^[ \\t]*//.*$', 'gm')

const scenario = scenarioSource.replace(BLOCK_COMMENT, '').replace(LINE_COMMENT, '')

const reservation = scenario.slice(
  scenario.indexOf('function reservedCustomer'),
  scenario.indexOf('function reservedCustomer') + 1400,
)

check('the customer reservation exists at all', () => {
  assert.ok(
    reservation.includes('function reservedCustomer'),
    'reservedCustomer not found — these checks would pass vacuously',
  )
})

check('it takes its slot from the scenario-local iteration counter', () => {
  assert.match(reservation, /exec\.scenario\.iterationInTest\s*%\s*bandSize/)
})

check('it does not take its slot from __VU, which is not scenario-local', () => {
  const slotLine = reservation
    .split('\n')
    .find((l) => l.includes('const slot ='))
  assert.ok(slotLine, 'no slot assignment found')
  assert.ok(
    !slotLine.includes('__VU'),
    `the slot is derived from __VU, which k6 numbers across the whole test: ${slotLine.trim()}`,
  )
})

check('k6/execution is imported, or the slot above cannot work', () => {
  assert.match(scenario, /import exec from 'k6\/execution'/)
})

check('the bands are laid out from RACE_VUS so they cannot overlap', () => {
  assert.match(scenario, /const DUPLICATE_BAND = RACE_VUS/)
  assert.match(scenario, /const CANCEL_BAND = RACE_VUS \+ DUPLICATE_VUS/)
})

check('every scenario draws from a band rather than an ad-hoc offset', () => {
  /* The offsets this replaced: users[(__VU + 100) % users.length] and friends,
   * whose ranges overlap for any realistic fixture length. */
  assert.ok(
    !/users\[\(__VU \+ \d+\) % users\.length\]/.test(scenario),
    'an ad-hoc customer offset is back in the scenario',
  )
})


/* ------------------------ the scenarios send what they claim to send */

/*
 * Proof from the source that the duplicate scenario submits the SAME logical
 * operation twice, rather than two bookings that happen to look alike.
 *
 * The API's semantics decide what "the same" means here: selecting an offer
 * moves the ride request from SEARCHING to SELECTED, so a duplicate is a
 * second select naming the same request and the same offer. Two selects with
 * different request ids would be two bookings and would prove nothing.
 */
const duplicateBody = scenario.slice(
  scenario.indexOf('export function duplicateRequests'),
  scenario.indexOf('export function acceptVersusCancel'),
)

check('the duplicate scenario exists where these checks look', () => {
  assert.ok(duplicateBody.length > 200, 'duplicateRequests body not found')
})

check('both submissions go to the select endpoint for one request id', () => {
  const posts = [
    ...duplicateBody.matchAll(
      /http\.post\(\s*`\$\{BASE\}\/api\/v1\/rides\/requests\/\$\{requestId\}\/select`/g,
    ),
  ]
  assert.equal(posts.length, 2, `expected two selects for ${'${requestId}'}, found ${posts.length}`)
})

check('both submissions send the same body and the same credentials', () => {
  /* One `body` and one `headers`, built once and passed to both calls. */
  assert.match(duplicateBody, /const body = JSON\.stringify\(\{ offerId \}\)/)
  assert.match(duplicateBody, /const headers = authHeaders\(token\)/)
  const bodyArgs = [...duplicateBody.matchAll(/select`,\s*body,/g)]
  assert.equal(bodyArgs.length, 2, 'the two selects do not both send `body`')
})

check('the second submission is judged by classifyDuplicate, not by a status range', () => {
  assert.match(duplicateBody, /classifyDuplicate\(second\)/)
  assert.ok(
    !/second\.status >= 300/.test(duplicateBody),
    'the second submission is judged by `status >= 300` again, which counts a 500 as a refusal',
  )
})

check('the first submission must be a 201 before the duplicate means anything', () => {
  assert.match(duplicateBody, /first\.status !== 201/)
})

/*
 * And the cancel race classifies BOTH sides. Only the accept was classified
 * before, so a refused or crashing cancel left no trace unless it happened to
 * arrive as a 5xx.
 */
const cancelBody = scenario.slice(
  scenario.indexOf('export function acceptVersusCancel'),
  scenario.indexOf('export function acceptVersusTimeout'),
)

check('the cancel scenario exists where these checks look', () => {
  assert.ok(cancelBody.length > 200, 'acceptVersusCancel body not found')
})

check('the cancel race classifies the accept and the cancel', () => {
  assert.match(cancelBody, /classifyAccept\(responses\[0\]\)/)
  assert.match(cancelBody, /classifyCancel\(responses\[1\]\)/)
})

check('the cancel race checks the pair against the lifecycle', () => {
  assert.match(cancelBody, /assessCancelRace\(acceptOutcome, cancelOutcome\)/)
})

check('both requests go out together rather than one after the other', () => {
  assert.match(cancelBody, /http\.batch\(\[/)
})

console.log(`\n  ${passed} passed${process.exitCode ? ', some failed' : ', 0 failed'}`)
