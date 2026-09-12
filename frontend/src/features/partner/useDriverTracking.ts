import { useCallback, useEffect, useRef, useState } from 'react'
import { partnerApi } from '@/lib/api/endpoints'
import { watchPosition, type Position } from '@/lib/geolocation'

/** Taxi search ignores positions older than two minutes, so stay well inside it. */
const UPLOAD_INTERVAL_MS = 25_000

interface TrackingState {
  lastSentAt: number | null
  lastPosition: Position | null
  error: string | null
}

/**
 * Streams this device's real GPS to the backend for whichever drivers are
 * currently online.
 *
 * The device watches its position continuously but only uploads on an
 * interval: watchPosition can fire several times a second while a phone
 * refines its fix, and the backend only needs a position fresh enough to pass
 * the search window.
 *
 * Tracking is per driver id, so one device can carry more than one driver —
 * which is what a single-car owner-operator needs, and is also why the ids are
 * held in a set rather than a single value.
 */
export function useDriverTracking() {
  const [trackedDriverIds, setTrackedDriverIds] = useState<number[]>([])
  const [state, setState] = useState<TrackingState>({
    lastSentAt: null,
    lastPosition: null,
    error: null,
  })

  // Held in refs so the watch callback never needs re-subscribing; restarting
  // the watch would make the device re-acquire a fix each time.
  const latestPosition = useRef<Position | null>(null)
  const trackedRef = useRef<number[]>([])

  trackedRef.current = trackedDriverIds

  useEffect(() => {
    if (trackedDriverIds.length === 0) {
      latestPosition.current = null
      return
    }

    const stopWatching = watchPosition(
      (position) => {
        latestPosition.current = position
        setState((previous) => ({ ...previous, lastPosition: position, error: null }))
      },
      (error) => setState((previous) => ({ ...previous, error: error.message })),
    )

    async function upload() {
      const position = latestPosition.current
      if (!position) return

      const results = await Promise.allSettled(
        trackedRef.current.map((driverId) =>
          partnerApi.updateDriverLocation(driverId, {
            latitude: position.lat,
            longitude: position.lng,
          }),
        ),
      )

      const failed = results.some((result) => result.status === 'rejected')

      setState((previous) => ({
        ...previous,
        lastSentAt: failed ? previous.lastSentAt : Date.now(),
        error: failed ? 'Could not send the latest position to the server.' : null,
      }))
    }

    // Send the first fix as soon as one arrives rather than waiting a full
    // interval, otherwise a driver is invisible to search for 25 seconds.
    const firstFix = setInterval(() => {
      if (latestPosition.current) {
        clearInterval(firstFix)
        void upload()
      }
    }, 1_000)

    const timer = setInterval(() => void upload(), UPLOAD_INTERVAL_MS)

    return () => {
      clearInterval(firstFix)
      clearInterval(timer)
      stopWatching()
    }
  }, [trackedDriverIds])

  const startTracking = useCallback((driverId: number) => {
    setTrackedDriverIds((previous) =>
      previous.includes(driverId) ? previous : [...previous, driverId],
    )
  }, [])

  const stopTracking = useCallback((driverId: number) => {
    setTrackedDriverIds((previous) => previous.filter((id) => id !== driverId))
  }, [])

  return {
    trackedDriverIds,
    isTracking: trackedDriverIds.length > 0,
    lastSentAt: state.lastSentAt,
    lastPosition: state.lastPosition,
    error: state.error,
    startTracking,
    stopTracking,
  }
}
