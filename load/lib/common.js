import http from 'k6/http'
import { Counter, Trend } from 'k6/metrics'

export const BASE = __ENV.BASE_URL || 'http://frontend:80'

/** Every account this suite creates uses the same password. */
export const PASSWORD = 'LoadTest123!'

/* Shared counters, so every scenario reports the same shape. */
export const rateLimited = new Counter('kalo_rate_limited')
export const serverErrors = new Counter('kalo_server_errors')
export const clientErrors = new Counter('kalo_client_errors')
export const businessRejections = new Counter('kalo_business_rejections')
export const apiLatency = new Trend('kalo_api_latency', true)

/**
 * A distinct client address per virtual user.
 *
 * KALO rate-limits per client IP, and nginx passes the forwarded address
 * through. Without this every virtual user would share one bucket and the run
 * would measure the rate limiter rather than the application — a thousand
 * people in a pilot arrive from a thousand addresses, not from one.
 *
 * The limiter still applies in full; it simply sees the right number of
 * clients. The baseline run turns it off altogether and says so.
 */
export function clientHeaders(extra = {}) {
  const vu = __VU || 0
  const a = 10 + ((vu >> 16) & 0xff)
  const b = (vu >> 8) & 0xff
  const c = vu & 0xff

  return {
    'Content-Type': 'application/json',
    'X-Forwarded-For': `${a}.${b}.${c}.1`,
    ...extra,
  }
}

export function authHeaders(token, extra = {}) {
  return clientHeaders({ Authorization: `Bearer ${token}`, ...extra })
}

/**
 * Records a response against the shared counters.
 *
 * Deliberately separates the three kinds of non-success, because they mean
 * completely different things for a capacity decision:
 *
 *   429 — the limiter working as designed, not a failure
 *   4xx — a request the application refused on its merits
 *   5xx — the application breaking, which is the only one that is never fine
 */
export function track(response, label) {
  apiLatency.add(response.timings.duration, { endpoint: label })

  if (response.status === 429) {
    rateLimited.add(1, { endpoint: label })
    return 'rate_limited'
  }
  if (response.status >= 500) {
    serverErrors.add(1, { endpoint: label })
    return 'server_error'
  }
  if (response.status >= 400) {
    clientErrors.add(1, { endpoint: label })
    return 'client_error'
  }
  return 'ok'
}

/** A phone number no other virtual user or run will produce. */
export function uniquePhone(salt = '') {
  const vu = String(__VU || 0).padStart(3, '0').slice(-3)
  const iter = String(__ITER || 0).padStart(3, '0').slice(-3)
  const run = String(__ENV.RUN_ID || Date.now() % 1000).padStart(3, '0').slice(-3)
  return `+3556${run}${vu}${iter}${salt}`.slice(0, 16)
}

export function registerCustomer(phone) {
  return http.post(
    `${BASE}/api/v1/auth/register/customer`,
    JSON.stringify({
      firstName: 'Load',
      lastName: 'Test',
      phone,
      password: PASSWORD,
    }),
    { headers: clientHeaders(), tags: { endpoint: 'register' } },
  )
}

export function login(phone, password = PASSWORD) {
  return http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ phone, password }),
    { headers: clientHeaders(), tags: { endpoint: 'login' } },
  )
}

export function tokenFrom(response) {
  if (response.status !== 200) return null
  try {
    return response.json('accessToken')
  } catch {
    return null
  }
}

/*
 * One sign-in per virtual user, kept for the life of the run.
 *
 * Logging in on every iteration is both unrealistic and self-defeating: a
 * passenger signs in once and then searches repeatedly, and KALO caps login at
 * ten per minute per client — so a loop that re-authenticates every time spends
 * the run measuring the rate limiter instead of the endpoint under test. At
 * five virtual users that alone produced a hundred 429s.
 *
 * Module scope in k6 is per virtual user, so this is a per-VU cache without any
 * sharing between them.
 */
let cachedToken = null
let cachedPhone = null

export function tokenFor(user) {
  if (cachedToken && cachedPhone === user.phone) return cachedToken

  const response = login(user.phone)
  const token = tokenFrom(response)

  if (token) {
    cachedToken = token
    cachedPhone = user.phone
  } else {
    track(response, 'login')
  }

  return token
}

/* Tirana, inside every seeded company's service area. */
export const PICKUP = { latitude: 41.3275, longitude: 19.8187 }
export const DESTINATION = { latitude: 41.32, longitude: 19.83 }

export function searchRides(token) {
  return http.post(
    `${BASE}/api/v1/rides/search`,
    JSON.stringify({
      pickupLatitude: PICKUP.latitude,
      pickupLongitude: PICKUP.longitude,
      destinationLatitude: DESTINATION.latitude,
      destinationLongitude: DESTINATION.longitude,
    }),
    { headers: authHeaders(token), tags: { endpoint: 'search' } },
  )
}

/**
 * Thresholds shared by every scenario.
 *
 * A 5xx budget of zero is the point of the exercise: latency under load is a
 * capacity question, but the application returning 500 is a defect whatever the
 * load. 429s are counted, never failed — they are the limiter doing its job.
 */
export const baseThresholds = {
  kalo_server_errors: ['count==0'],
  http_req_failed: ['rate<0.05'],
}
