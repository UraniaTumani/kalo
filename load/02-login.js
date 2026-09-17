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
  /*
   * One virtual user is one person: the same account, and — through
   * clientHeaders — the same address for the life of the run.
   */
  const user = users[__VU % users.length]

  const response = login(user.phone)
  const outcome = track(response, 'login')

  check(response, {
    'login succeeded or was throttled': (r) => r.status === 200 || r.status === 429,
    'no server error': (r) => r.status < 500,
  })

  if (outcome === 'ok') {
    check(tokenFrom(response), { 'token returned': (t) => t !== null })
  }

  /*
   * A session length, not a tight loop.
   *
   * Signing in every second from one address is not something a person does,
   * and KALO caps login at ten a minute per client precisely to stop it. A
   * loop that ignores that spends the run collecting 429s and measuring the
   * limiter — which is what the first attempt did, 70% of responses throttled.
   *
   * Twenty to forty seconds is a person finishing a trip and coming back. At
   * 250 concurrent people that is still a sustained few sign-ins a second,
   * which is the thing worth measuring: latency at that concurrency, not how
   * fast a script can be refused.
   */
  sleep(Math.random() * 20 + 20)
}
