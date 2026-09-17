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

/*
 * shared-iterations, not ramping-vus with a counter.
 *
 * "Register a thousand people" is a fixed amount of work shared between
 * however many virtual users are doing it, which is exactly this executor.
 * The first version ramped and stopped each virtual user once a counter
 * reached the target — but module scope in k6 is per virtual user, so ten of
 * them each counted to a hundred and the run did ten times the work it
 * claimed. Worse, the early return spun: 10.1 million no-op iterations,
 * burning the same cores the application was being measured on.
 */
export const options = {
  scenarios: {
    registration: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: TARGET,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {
    ...baseThresholds,
    'http_req_duration{endpoint:register}': ['p(95)<3000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const response = registerCustomer(uniquePhone())
  track(response, 'register')

  check(response, {
    'registration accepted or refused cleanly': (r) =>
      r.status === 201 || r.status === 409 || r.status === 429 || r.status === 400,
    'no server error': (r) => r.status < 500,
  })
}
