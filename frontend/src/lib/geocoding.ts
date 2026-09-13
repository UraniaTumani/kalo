/**
 * Turning text into coordinates, and coordinates back into text.
 *
 * The lookup itself moved to the backend. The browser used to call OpenStreetMap
 * directly, which meant any paid provider's key would have had to ship with the
 * bundle, the requests could not carry the User-Agent Nominatim's policy asks
 * for, and every visitor re-asked a question somebody else had just asked.
 *
 * Changing provider is now a backend change — see GeocodingProvider there.
 * Nothing on this side knows which one is answering.
 */

export interface Place {
  /** Stable id from the provider, used as a React key. */
  id: string
  /** Short name, e.g. "Sheshi Skenderbej". */
  label: string
  /** Full address for disambiguation. */
  description: string
  lat: number
  lng: number
}

export interface GeocodingProvider {
  search(query: string, signal?: AbortSignal): Promise<Place[]>
  reverse(lat: number, lng: number, signal?: AbortSignal): Promise<string | null>
}

/** What the backend returns; latitude/longitude rather than lat/lng. */
interface PlaceResponse {
  id: string
  label: string
  description: string
  latitude: number
  longitude: number
}

/** Matches the backend's own minimum, so two letters never leave the browser. */
const MIN_QUERY_LENGTH = 3

/**
 * Goes through the same origin as every other call, so there is no third party
 * in the page's connect-src and nothing to configure per deployment.
 */
export const backendProvider: GeocodingProvider = {
  async search(query, signal) {
    const trimmed = query.trim()
    if (trimmed.length < MIN_QUERY_LENGTH) return []

    const url = new URL('/api/v1/geocoding/search', window.location.origin)
    url.searchParams.set('q', trimmed)

    const response = await fetch(url, {
      signal,
      headers: {
        Accept: 'application/json',
        ...authorization(),
      },
    })

    if (!response.ok) throw new Error('Address lookup failed')

    const results = (await response.json()) as PlaceResponse[]

    return results.map((result) => ({
      id: result.id,
      label: result.label,
      description: result.description,
      lat: result.latitude,
      lng: result.longitude,
    }))
  },

  async reverse(lat, lng, signal) {
    const url = new URL('/api/v1/geocoding/reverse', window.location.origin)
    url.searchParams.set('lat', String(lat))
    url.searchParams.set('lng', String(lng))

    const response = await fetch(url, {
      signal,
      headers: {
        Accept: 'application/json',
        ...authorization(),
      },
    })

    if (!response.ok) return null

    const result = (await response.json()) as { address: string | null }

    return result.address ?? null
  },
}

/**
 * Read at call time rather than imported from the API client: this module is
 * loaded by the map picker, and pulling the client in would drag the whole
 * request pipeline — including its refresh logic — into that chunk.
 */
function authorization(): Record<string, string> {
  try {
    const token = localStorage.getItem('kalo.token')
    return token ? { Authorization: `Bearer ${token}` } : {}
  } catch {
    return {}
  }
}

export const geocoding: GeocodingProvider = backendProvider
