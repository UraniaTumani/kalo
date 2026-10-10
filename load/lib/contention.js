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
 * Whether a set of accepts can be said to have overlapped in flight.
 *
 * Barrier offsets alone are not enough, which is the point of this function.
 * Thirty accepts issued across two seconds did not contend if each took forty
 * milliseconds — they queued. Overlap needs the spread between the first and
 * last issue to be smaller than how long a request stayed open.
 *
 * @param {number} spreadMs      last issue minus first issue
 * @param {number} maxDurationMs the longest accept
 * @param {number} minDurationMs the shortest accept
 * @returns {{overlapped: boolean, allOverlapped: boolean, reason: string}}
 */
export function assessOverlap(spreadMs, maxDurationMs, minDurationMs) {
  if (
    typeof spreadMs !== 'number' ||
    typeof maxDurationMs !== 'number' ||
    Number.isNaN(spreadMs) ||
    Number.isNaN(maxDurationMs)
  ) {
    return { overlapped: false, allOverlapped: false, reason: 'timings missing' }
  }

  /* One sample has nothing to overlap with. */
  if (maxDurationMs <= 0) {
    return { overlapped: false, allOverlapped: false, reason: 'no request durations' }
  }

  const overlapped = spreadMs < maxDurationMs
  const allOverlapped =
    typeof minDurationMs === 'number' && !Number.isNaN(minDurationMs) && spreadMs < minDurationMs

  return {
    overlapped,
    allOverlapped,
    reason: overlapped
      ? allOverlapped
        ? 'every accept was in flight at once'
        : 'at least two accepts were in flight at once'
      : `spread ${spreadMs}ms exceeds the longest accept ${maxDurationMs}ms, so they queued rather than raced`,
  }
}
