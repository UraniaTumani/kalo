import type { SelectedPlace } from '@/components/AddressInput'

const KEY = 'kalo.retryBookingTrip'
const MAX_AGE_MS = 6 * 60 * 60 * 1000

interface SavedTrip {
  rideId: number
  pickup: SelectedPlace
  destination: SelectedPlace
  savedAt: number
}

function isPlace(value: unknown): value is SelectedPlace {
  if (!value || typeof value !== 'object') return false
  const place = value as Partial<SelectedPlace>
  return (
    typeof place.label === 'string' &&
    place.label.trim().length > 0 &&
    typeof place.lat === 'number' &&
    Number.isFinite(place.lat) &&
    Math.abs(place.lat) <= 90 &&
    typeof place.lng === 'number' &&
    Number.isFinite(place.lng) &&
    Math.abs(place.lng) <= 180
  )
}

/**
 * RideResponse has human-readable addresses but not coordinates. Remember the
 * actual selected places only after a booking succeeds, so a declined ride can
 * start a new search without asking the passenger to geocode both addresses
 * again. Session storage keeps the route in this tab, not across devices.
 */
export function rememberRetryTrip(
  rideId: number,
  pickup: SelectedPlace,
  destination: SelectedPlace,
) {
  try {
    sessionStorage.setItem(
      KEY,
      JSON.stringify({ rideId, pickup, destination, savedAt: Date.now() } satisfies SavedTrip),
    )
  } catch {
    // Storage can be disabled; booking must still work without retry convenience.
  }
}

/** Never apply an older trip to another ride or a different customer. */
export function recallRetryTrip(
  rideId: number,
): { pickup: SelectedPlace; destination: SelectedPlace } | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null

    const saved = JSON.parse(raw) as Partial<SavedTrip>
    if (
      saved.rideId !== rideId ||
      typeof saved.savedAt !== 'number' ||
      saved.savedAt > Date.now() ||
      Date.now() - saved.savedAt > MAX_AGE_MS ||
      !isPlace(saved.pickup) ||
      !isPlace(saved.destination)
    ) {
      return null
    }

    return { pickup: saved.pickup, destination: saved.destination }
  } catch {
    return null
  }
}
