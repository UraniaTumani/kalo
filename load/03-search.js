import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'
import { Counter } from 'k6/metrics'
import { baseThresholds, searchRides, tokenFor, track } from './lib/common.js'

/**
 * Scenario 3 — taxi search.
 *
 * The heaviest read in the application. One search walks every approved company,
 * checks verification, company status, booking flag, payment methods, operating
 * hours in the company's own timezone, service-area distance, then every
 * candidate driver's status, availability, vehicle assignment and the age of
 * their last position — before ranking what is left. It is the query a pilot
 * will run most often and the one most likely to become the bottleneck.
 *
 * Correctness is checked alongside latency, because a search that gets fast by
 * returning nothing has not got faster. Every seeded company is bookable from
 * the pickup point, so an empty result under load is a real failure.
 */
const users = new SharedArray('load users', () =>
  JSON.parse(open(__ENV.USERS_FILE || '/data/users.json')),
)

const VUS = Number(__ENV.VUS || 50)

const emptyResults = new Counter('kalo_search_empty')
const offersSeen = new Counter('kalo_search_offers')

export const options = {
  scenarios: {
    search: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: Math.ceil(VUS / 3) },
        { duration: '20s', target: Math.ceil((VUS * 2) / 3) },
        { duration: '20s', target: VUS },
        { duration: __ENV.HOLD || '90s', target: VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    ...baseThresholds,
    'http_req_duration{endpoint:search}': ['p(95)<3000'],
    /* Offers must keep coming back. Correctness is not negotiable under load. */
    kalo_search_empty: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

export function setup() {
  return {}
}

export default function () {
  const user = users[__VU % users.length]

  const token = tokenFor(user)
  if (!token) {
    sleep(1)
    return
  }

  const response = searchRides(token)
  const outcome = track(response, 'search')

  check(response, {
    'search answered or was refused cleanly': (r) =>
      r.status === 201 || r.status === 409 || r.status === 429,
    'no server error': (r) => r.status < 500,
  })

  /*
   * 409 is legitimate here: a customer with a ride already running cannot
   * search again, and this scenario reuses accounts.
   */
  if (outcome === 'ok' && response.status === 201) {
    let options_ = []
    try {
      options_ = response.json('taxiOptions') || []
    } catch {
      options_ = []
    }

    if (options_.length === 0) {
      emptyResults.add(1)
    } else {
      offersSeen.add(options_.length)
    }

    check(options_, {
      'at least one company offered': (o) => o.length > 0,
    })
  }

  /* A passenger reads the offers before doing anything else. */
  sleep(Math.random() * 3 + 1)
}
