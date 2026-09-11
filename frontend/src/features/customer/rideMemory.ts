const KEY = 'kalo.lastRideId'

/**
 * GET /api/v1/rides/current only returns non-terminal rides, so the moment the
 * company completes or declines a ride the customer would lose sight of it.
 * Remembering the id lets the page fall back to GET /rides/{id} and show the
 * outcome (and the rating prompt) instead of an empty screen.
 */
export function rememberRide(rideId: number) {
  localStorage.setItem(KEY, String(rideId))
}

export function recallRide(): number | null {
  const raw = localStorage.getItem(KEY)
  if (!raw) return null

  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

export function forgetRide() {
  localStorage.removeItem(KEY)
}
