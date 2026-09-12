/**
 * Thin promise wrapper over the browser Geolocation API.
 *
 * Real device positions only — nothing here invents or defaults a coordinate,
 * because a wrong position silently sends a taxi to the wrong street.
 */

export interface Position {
  lat: number
  lng: number
  /** Metres of uncertainty reported by the device. */
  accuracy: number
  /** When the device took the fix, not when we received it. */
  timestamp: number
}

export class GeolocationError extends Error {
  readonly kind: 'unsupported' | 'denied' | 'unavailable' | 'timeout'

  constructor(kind: GeolocationError['kind'], message: string) {
    super(message)
    this.name = 'GeolocationError'
    this.kind = kind
  }
}

export const isGeolocationSupported = () =>
  typeof navigator !== 'undefined' && 'geolocation' in navigator

function toGeolocationError(error: GeolocationPositionError): GeolocationError {
  switch (error.code) {
    case error.PERMISSION_DENIED:
      return new GeolocationError(
        'denied',
        'Location permission was denied. Allow location access in your browser to continue.',
      )
    case error.POSITION_UNAVAILABLE:
      return new GeolocationError(
        'unavailable',
        'Your device could not determine a position. Check that location services are on.',
      )
    default:
      return new GeolocationError('timeout', 'Timed out while getting your position.')
  }
}

const toPosition = (position: GeolocationPosition): Position => ({
  lat: position.coords.latitude,
  lng: position.coords.longitude,
  accuracy: position.coords.accuracy,
  timestamp: position.timestamp,
})

export function getCurrentPosition(
  options: PositionOptions = { enableHighAccuracy: true, timeout: 15_000, maximumAge: 10_000 },
): Promise<Position> {
  if (!isGeolocationSupported()) {
    return Promise.reject(
      new GeolocationError('unsupported', 'This browser does not support location access.'),
    )
  }

  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve(toPosition(position)),
      (error) => reject(toGeolocationError(error)),
      options,
    )
  })
}

/**
 * Continuous tracking. Returns an unsubscribe function.
 *
 * watchPosition is used rather than a polling loop because the device only
 * wakes the GPS when it has something new, which is much cheaper on a phone.
 */
export function watchPosition(
  onPosition: (position: Position) => void,
  onError?: (error: GeolocationError) => void,
  options: PositionOptions = { enableHighAccuracy: true, timeout: 30_000, maximumAge: 5_000 },
): () => void {
  if (!isGeolocationSupported()) {
    onError?.(new GeolocationError('unsupported', 'This browser does not support location access.'))
    return () => {}
  }

  const id = navigator.geolocation.watchPosition(
    (position) => onPosition(toPosition(position)),
    (error) => onError?.(toGeolocationError(error)),
    options,
  )

  return () => navigator.geolocation.clearWatch(id)
}
