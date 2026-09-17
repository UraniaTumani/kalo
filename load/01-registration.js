import { check } from 'k6'
import { baseThresholds, registerCustomer, track, uniquePhone } from './lib/common.js'

/**
 * Scenario 1 — customer registration.
 *
 * Registers TARGET accounts, ramping rather than arriving all at once: a pilot
 * launch is a curve, not a step, and a step would measure how the JVM behaves
 * before it has warmed up rather than how it serves people.
 *
 * Registration is the most expensive unauthenticated write in the application —
 * bcrypt on every call, by design — so this is the scenario most likely to be
 * CPU-bound, and the one whose ceiling matters most on launch day.
 */
const TARGET = Number(__ENV.TARGET || 100)

/* Kept modest: bcrypt means a handful of concurrent registrations already
 * saturates a core, and more virtual users past that only queue. */
const VUS = Math.min(Number(__ENV.VUS || 20), 50)

export const options = {
  scenarios: {
    registration: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: Math.ceil(VUS / 2) },
        { duration: '20s', target: VUS },
        { duration: __ENV.HOLD || '60s', target: VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    ...baseThresholds,
    'http_req_duration{endpoint:register}': ['p(95)<3000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

let registered = 0

export default function () {
  if (registered >= TARGET) return

  const response = registerCustomer(uniquePhone())
  const outcome = track(response, 'register')

  if (outcome === 'ok') registered += 1

  check(response, {
    'registration accepted or refused cleanly': (r) =>
      r.status === 201 || r.status === 409 || r.status === 429 || r.status === 400,
    'no server error': (r) => r.status < 500,
  })
}
