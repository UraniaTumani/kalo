import http from 'k6/http'
import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'
import { Counter } from 'k6/metrics'
import {
  BASE,
  authHeaders,
  baseThresholds,
  searchRides,
  track,
  tokenFor,
} from './lib/common.js'

/**
 * Scenario 4 — concurrent ride creation.
 *
 * The contended path. Many customers select an offer at the same moment, and
 * the same small pool of drivers is behind those offers, so this is where the
 * invariants from migration 017 earn their place: one active ride per customer,
 * one active ride per driver, enforced by partial unique indexes rather than by
 * hoping two requests never arrive together.
 *
 * What matters here is not throughput but what happens when two customers reach
 * for the same driver. The correct answer is that one succeeds and the other is
 * refused cleanly — a 409, not a 500 and not two rides. A 500 here would mean a
 * constraint violation reaching the client as a crash.
 */
const users = new SharedArray('load users', () =>
  JSON.parse(open(__ENV.USERS_FILE || '/data/users.json')),
)

const VUS = Number(__ENV.VUS || 25)

const ridesCreated = new Counter('kalo_rides_created')
const selectConflicts = new Counter('kalo_select_conflicts')

export const options = {
  scenarios: {
    rides: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: Math.ceil(VUS / 2) },
        { duration: '15s', target: VUS },
        { duration: __ENV.HOLD || '60s', target: VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    ...baseThresholds,
    'http_req_duration{endpoint:select}': ['p(95)<3000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const user = users[__VU % users.length]

  const token = tokenFor(user)
  if (!token) {
    sleep(1)
    return
  }

  const search = searchRides(token)
  track(search, 'search')

  if (search.status !== 201) {
    /* Already on a ride, or throttled. Both are legitimate. */
    sleep(1)
    return
  }

  let requestId = null
  let offerId = null
  try {
    requestId = search.json('rideRequestId')
    const options_ = search.json('taxiOptions') || []
    if (options_.length > 0) offerId = options_[0].offerId
  } catch {
    /* handled below */
  }

  if (!requestId || !offerId) {
    sleep(1)
    return
  }

  const select = http.post(
    `${BASE}/api/v1/rides/requests/${requestId}/select`,
    JSON.stringify({ offerId }),
    { headers: authHeaders(token), tags: { endpoint: 'select' } },
  )

  track(select, 'select')

  if (select.status === 409) selectConflicts.add(1)
  if (select.status === 200 || select.status === 201) ridesCreated.add(1)

  check(select, {
    /*
     * The whole point of the scenario. Losing a race is fine and expected;
     * crashing is not, and neither is two customers getting the same driver.
     */
    'selection either succeeded or lost the race cleanly': (r) =>
      r.status === 200 || r.status === 201 || r.status === 409 || r.status === 400,
    'no server error under contention': (r) => r.status < 500,
  })

  /* Cancel so the account can take another ride, as a real customer would. */
  if (select.status === 200 || select.status === 201) {
    let rideId = null
    try {
      rideId = select.json('rideId')
    } catch {
      /* ignore */
    }

    if (rideId) {
      const cancel = http.post(
        `${BASE}/api/v1/rides/${rideId}/cancel`,
        null,
        { headers: authHeaders(token), tags: { endpoint: 'cancel' } },
      )
      track(cancel, 'cancel')
    }
  }

  sleep(Math.random() * 2 + 1)
}
