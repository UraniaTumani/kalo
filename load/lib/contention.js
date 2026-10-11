/**
 * The decision that turns an HTTP response into evidence.
 *
 * Kept free of k6 imports on purpose: this is the part of the contention
 * scenario that can be wrong in a way no stack would reveal, so it has to be
 * testable by plain node. Classify a legitimate refusal as an error and the run
 * fails for the wrong reason; classify an error as a refusal and a crash gets
 * counted as correct concurrency handling.
 */

/**
 * Which refusals mean "the race resolved against this caller".
 *
 * Taken from what RideServiceImpl actually throws, not from what would be
 * convenient:
 *
 *   "Driver must be online and available"      the winner already took the
 *                                              driver, who is now BUSY
 *   "Only a requested ride can be accepted"    this ride was cancelled or swept
 *                                              while the accept was in flight
 *   anything naming an active ride             the partial unique index from
 *                                              migration 017 surfacing
 *
 * All three are the system working. None is an error.
 */
const LOSS_PHRASES = [
  'must be online and available',
  'Only a requested ride can be accepted',
  'active ride',
]

/**
 * @param {{status: number, body?: string}} response
 * @returns {'won'|'lost'|'unexpected'}
 */
export function classifyAccept(response) {
  if (!response || typeof response.status !== 'number') return 'unexpected'

  if (response.status === 200) return 'won'

  /* A conflict is the constraint speaking; no body inspection needed. */
  if (response.status === 409) return 'lost'

  if (response.status === 400) {
    const body = response.body || ''
    if (LOSS_PHRASES.some((phrase) => body.includes(phrase))) return 'lost'
  }

  /*
   * Everything else is counted on its own. A 500 is a unique-index violation
   * reaching the client as a crash, and a 400 nobody predicted is a finding —
   * folding either into "lost" would report the worst outcome available here
   * as correct behaviour.
   */
  return 'unexpected'
}

/**
 * Whether a set of accepts demonstrably overlapped in flight.
 *
 * This replaces an earlier barrier-offset test that was unsound in a way the
 * first real run exposed. That version compared a SPREAD of issue times
 * against a request DURATION and inferred overlap from the two. Both numbers
 * came from separate metrics, so a spread of zero — which is what a broken
 * offset metric reports — satisfied `spread < duration` perfectly and the gate
 * called it flawless synchronisation. The strongest possible evidence and no
 * evidence at all were indistinguishable.
 *
 * Intervals remove the inference. Each accept contributes the instant it was
 * issued and the instant it came back, both measured by the same clock in the
 * same virtual user. There is then exactly ONE passing verdict:
 *
 *   latestStart < earliestEnd    every accept was still open when the last one
 *                                was issued, so all of them were in flight at
 *                                once
 *
 * and anything else queued. No threshold, no tolerance, no second metric to
 * agree with. A degenerate input cannot read as success, because success
 * requires two DIFFERENT instants to sit in a particular order.
 *
 * Nothing weaker is offered, and the body explains at length why a
 * partial-overlap verdict cannot be drawn from these four numbers. Both
 * returned booleans are therefore always equal; `allOverlapped` is kept
 * because it names what was actually established.
 *
 * @param {{latestStartMs:number, earliestEndMs:number, earliestStartMs:number,
 *          latestEndMs:number, samples:number}} window
 * @returns {{overlapped:boolean, allOverlapped:boolean, reason:string,
 *            spreadMs:number|null}}
 */
export function assessIntervalOverlap(window) {
  const no = (reason) => ({
    overlapped: false,
    allOverlapped: false,
    reason,
    spreadMs: null,
  })

  if (!window || typeof window !== 'object') {
    return no('no accept timings were recorded')
  }

  const { latestStartMs, earliestEndMs, earliestStartMs, latestEndMs, samples } = window

  const finite = (n) => typeof n === 'number' && Number.isFinite(n)

  if (![latestStartMs, earliestEndMs, earliestStartMs, latestEndMs].every(finite)) {
    return no('accept timings are missing or not numeric — overlap is unverifiable')
  }

  if (!finite(samples) || samples < 2) {
    const timed = finite(samples) ? samples : 0
    return no(`only ${timed} timed accept(s) — nothing to overlap with`)
  }

  /*
   * Impossibility checks, which exist because the metric that failed in the
   * first real run failed in a way that was physically impossible rather than
   * merely wrong. Timestamps are measured relative to an instant in setup, so
   * every one of them must be positive and an accept cannot close before it
   * opened. A value breaking either rule is a broken measurement, and a broken
   * measurement must never be read as a timing fact.
   */
  if (earliestStartMs < 0 || earliestEndMs < 0) {
    return no(
      `accept timestamps are negative (earliest start ${earliestStartMs}ms, ` +
        `earliest end ${earliestEndMs}ms) — measured from setup, they cannot ` +
        'be, so the instrumentation is broken',
    )
  }

  if (latestEndMs < earliestStartMs) {
    return no(
      `the last accept closed at ${latestEndMs}ms before the first opened ` +
        `at ${earliestStartMs}ms — the clock disagrees with itself, so the ` +
        'instrumentation is broken',
    )
  }

  /*
   * A set of identical timestamps. Thirty virtual users on thirty goroutines
   * do not read the same millisecond thirty times; a constant does. This is
   * the exact shape the first run produced, and it must fail rather than look
   * like perfect synchronisation.
   */
  if (latestStartMs === earliestStartMs && latestEndMs === earliestEndMs) {
    return no(
      `all ${samples} accepts report identical start (${earliestStartMs}ms) ` +
        `and end (${earliestEndMs}ms) — a constant, not a measurement`,
    )
  }

  const spreadMs = latestStartMs - earliestStartMs

  if (latestStartMs < earliestEndMs) {
    return {
      overlapped: true,
      allOverlapped: true,
      spreadMs,
      reason:
        `every accept was in flight at once: the last was issued at ` +
        `${latestStartMs}ms, before the first to close did so at ` +
        `${earliestEndMs}ms`,
    }
  }

  /*
   * There is deliberately NO weaker branch here.
   *
   * "At least two accepts overlapped" looks like a reasonable fallback and
   * cannot be concluded from these inputs. The tempting test is
   * latestStart < latestEnd, and it is satisfied by the last accept's OWN
   * interval: max(end) is normally the end of the very accept that started at
   * max(start), so the comparison reduces to "the last accept had a positive
   * duration" and is true of any run whatsoever, including one in which every
   * request was a minute apart.
   *
   * It was written that way first, and the test that proved it wrong is in
   * scripts/test-load-contention.sh: accepts spread over sixty seconds passed
   * as "at least two accepts were in flight at once". Four aggregate numbers
   * can support the all-overlap claim and nothing weaker, so nothing weaker is
   * offered -- demonstrating partial overlap would need per-sample intervals.
   */
  return no(
    `no accept was still open when the last one was issued: the last started ` +
      `at ${latestStartMs}ms and the first to close did so at ` +
      `${earliestEndMs}ms, so they queued rather than raced (starts spread ` +
      `over ${spreadMs}ms against accepts lasting ` +
      `${earliestEndMs - earliestStartMs}ms)`,
  )
}

/**
 * Whether every attempt the race started is accounted for.
 *
 * The first real run put 30 virtual users in and recorded 29 contested
 * accepts. One booking failed, the scenario moved on, and both k6 and the gate
 * reported the remaining 29 as the whole race. A missing participant is not a
 * rounding error: it is an attempt whose outcome nobody knows, and it has to
 * be named rather than absorbed.
 *
 * Two sums have to balance:
 *
 *   entered   = contested + every recorded reason for not reaching the accept
 *   contested = won + lost + unexpected
 *
 * @param {{entered:number, contested:number, won:number, lost:number,
 *          unexpected:number, bailed:Record<string, number>}} tally
 * @returns {{balanced:boolean, problems:string[]}}
 */
export function reconcileAccepts(tally) {
  const problems = []
  const n = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : 0)

  const entered = n(tally && tally.entered)
  const contested = n(tally && tally.contested)
  const won = n(tally && tally.won)
  const lost = n(tally && tally.lost)
  const unexpected = n(tally && tally.unexpected)
  const bailed = (tally && tally.bailed) || {}

  const bailedTotal = Object.values(bailed).reduce((sum, v) => sum + n(v), 0)
  const bailedDetail =
    Object.entries(bailed)
      .filter(([, v]) => n(v) > 0)
      .map(([k, v]) => `${k}=${v}`)
      .join(', ') || 'none recorded'

  if (entered !== contested + bailedTotal) {
    problems.push(
      `${entered} virtual user(s) entered the race but ${contested} reached ` +
        `an accept and ${bailedTotal} recorded a reason for not doing so ` +
        `(${bailedDetail}) — ${entered - contested - bailedTotal} attempt(s) ` +
        'vanished without explanation',
    )
  }

  if (contested !== won + lost + unexpected) {
    problems.push(
      `${contested} accept(s) were made but ${won} won, ${lost} lost and ` +
        `${unexpected} were unclassified, which totals ` +
        `${won + lost + unexpected} — outcomes are unaccounted for`,
    )
  }

  return { balanced: problems.length === 0, problems }
}
