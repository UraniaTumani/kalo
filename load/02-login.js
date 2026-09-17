import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'
import { baseThresholds, login, track, tokenFrom } from './lib/common.js'

/**
 * Scenario 2 — login against pre-created accounts.
 *
 * Reads the accounts the seeding step made rather than registering its own, so
 * this measures authentication and nothing else. Like registration it is
 * bcrypt-bound, but unlike registration it also does a lookup and issues two
 * tokens, so it is the closest thing KALO has to a steady-state read-write mix.
 */
const users = new SharedArray('load users', () =>
  JSON.parse(open(__ENV.USERS_FILE || '/data/users.json')),
)

const VUS = Number(__ENV.VUS || 50)

export const options = {
  scenarios: {
    login: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: Math.ceil(VUS / 3) },
        { duration: '20s', target: Math.ceil((VUS * 2) / 3) },
        { duration: '20s', target: VUS },
        { duration: __ENV.HOLD || '60s', target: VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    ...baseThresholds,
    'http_req_duration{endpoint:login}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const user = users[(__VU + __ITER) % users.length]

  const response = login(user.phone)
  const outcome = track(response, 'login')

  check(response, {
    'login succeeded or was throttled': (r) => r.status === 200 || r.status === 429,
    'no server error': (r) => r.status < 500,
  })

  if (outcome === 'ok') {
    check(tokenFrom(response), { 'token returned': (t) => t !== null })
  }

  /* A person signing in does not immediately sign in again. */
  sleep(Math.random() * 2 + 0.5)
}
