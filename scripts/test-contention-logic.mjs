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
import { classifyAccept, assessOverlap } from '../load/lib/contention.js'

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

check('a spread inside the fastest accept means all overlapped', () => {
  const r = assessOverlap(10, 80, 30)
  assert.equal(r.overlapped, true)
  assert.equal(r.allOverlapped, true)
})

check('a spread inside the slowest accept means some overlapped', () => {
  const r = assessOverlap(50, 80, 30)
  assert.equal(r.overlapped, true)
  assert.equal(r.allOverlapped, false)
})

check('a spread beyond the slowest accept is a queue, not a race', () => {
  const r = assessOverlap(200, 80, 30)
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /queued rather than raced/)
})

check('a spread exactly equal to the slowest accept does not count', () => {
  assert.equal(assessOverlap(80, 80, 30).overlapped, false)
})

check('missing timings are not overlap', () => {
  assert.equal(assessOverlap(undefined, 80, 30).overlapped, false)
  assert.equal(assessOverlap(10, undefined, 30).overlapped, false)
  assert.equal(assessOverlap(NaN, 80, 30).overlapped, false)
})

check('no durations at all is not overlap', () => {
  const r = assessOverlap(0, 0, 0)
  assert.equal(r.overlapped, false)
  assert.match(r.reason, /no request durations/)
})

console.log(`\n  ${passed} passed${process.exitCode ? ', some failed' : ', 0 failed'}`)
