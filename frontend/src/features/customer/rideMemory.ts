const KEY = 'kalo.lastRideId'

/**
 * How long a finished ride is still "the ride that just finished".
 *
 * The point of remembering an id is that GET /api/v1/rides/current only returns
 * non-terminal rides, so the moment a company completes or declines one the
 * customer would lose sight of it mid-journey. That need lasts hours, not
 * weeks — a passenger coming back the next day is starting a new trip, not
 * still looking at the last one.
 */
const WINDOW_MS = 6 * 60 * 60 * 1000

interface Remembered {
  rideId: number
  at: number
}

/**
 * Remembers the ride currently on screen, with the time it was seen.
 *
 * The timestamp is the whole fix. Without it the key was written on every ride
 * and never removed, so weeks later an empty Current ride page would fetch a
 * long-completed ride by id and present it as though it were live.
 */
export function rememberRide(rideId: number) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ rideId, at: Date.now() } satisfies Remembered))
  } catch {
    /* Private window, or storage full. Losing the fallback is not fatal. */
  }
}

export function recallRide(): number | null {
  let raw: string | null = null

  try {
    raw = localStorage.getItem(KEY)
  } catch {
    return null
  }

  if (!raw) return null

  /*
   * Anything that is not a fresh, well-formed record is dropped, including the
   * bare number this used to store. A stale value is exactly what this is here
   * to stop, so an unreadable one is treated the same way.
   */
  try {
    const parsed = JSON.parse(raw) as Partial<Remembered>

    if (
      typeof parsed?.rideId !== 'number' ||
      !Number.isFinite(parsed.rideId) ||
      typeof parsed?.at !== 'number' ||
      Date.now() - parsed.at > WINDOW_MS
    ) {
      forgetRide()
      return null
    }

    return parsed.rideId
  } catch {
    forgetRide()
    return null
  }
}

export function forgetRide() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* Nothing to do; the value expires on read anyway. */
  }
}
