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
import {
  classifyAccept,
  assessIntervalOverlap,
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

console.log(`\n  ${passed} passed${process.exitCode ? ', some failed' : ', 0 failed'}`)
