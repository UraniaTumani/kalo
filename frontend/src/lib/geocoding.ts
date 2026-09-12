/**
 * Turning text into coordinates, and coordinates back into text.
 *
 * Behind a provider interface on purpose: the MVP uses Nominatim because it
 * needs no API key or billing account, but it is rate limited and not meant
 * for production traffic. Swapping in Google Places or Mapbox later means
 * writing one more object with this shape — no page has to change.
 *
 * For production the sensible move is to put geocoding behind our own backend
 * endpoint instead, so the provider key never reaches the browser and rate
 * limiting is ours to control.
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

const NOMINATIM = 'https://nominatim.openstreetmap.org'

/** Biases results towards Albania; KALO does not operate elsewhere yet. */
const COUNTRY_CODES = 'al'

interface NominatimPlace {
  place_id: number
  lat: string
  lon: string
  name?: string
  display_name: string
}

export const nominatimProvider: GeocodingProvider = {
  async search(query, signal) {
    const trimmed = query.trim()
    if (trimmed.length < 3) return []

    const url = new URL(`${NOMINATIM}/search`)
    url.searchParams.set('q', trimmed)
    url.searchParams.set('format', 'jsonv2')
    url.searchParams.set('limit', '6')
    url.searchParams.set('countrycodes', COUNTRY_CODES)
    url.searchParams.set('addressdetails', '0')

    const response = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!response.ok) throw new Error('Address lookup failed')

    const results = (await response.json()) as NominatimPlace[]

    return results.map((result) => ({
      id: String(result.place_id),
      label: result.name?.trim() || result.display_name.split(',')[0],
      description: result.display_name,
      lat: Number(result.lat),
      lng: Number(result.lon),
    }))
  },

  async reverse(lat, lng, signal) {
    const url = new URL(`${NOMINATIM}/reverse`)
    url.searchParams.set('lat', String(lat))
    url.searchParams.set('lon', String(lng))
    url.searchParams.set('format', 'jsonv2')

    const response = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!response.ok) return null

    const result = (await response.json()) as NominatimPlace
    return result.display_name ?? null
  },
}

export const geocoding: GeocodingProvider = nominatimProvider
