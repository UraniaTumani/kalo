import { useEffect, useId, useRef, useState } from 'react'
import { LocateFixed, MapPin, X } from 'lucide-react'
import { geocoding, type Place } from '@/lib/geocoding'
import { getCurrentPosition, GeolocationError, isGeolocationSupported } from '@/lib/geolocation'
import { Button, Field, Input } from './ui'
import { cn } from '@/lib/utils'

export interface SelectedPlace {
  label: string
  lat: number
  lng: number
}

/**
 * Address-first location picker: a passenger types a place name or uses their
 * device position. Coordinates are resolved behind the scenes and never asked
 * for directly.
 */
export function AddressInput({
  label,
  placeholder,
  value,
  onChange,
  showUseMyLocation = false,
  required = false,
}: {
  label: string
  placeholder?: string
  value: SelectedPlace | null
  onChange: (place: SelectedPlace | null) => void
  showUseMyLocation?: boolean
  required?: boolean
}) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Place[]>([])
  const [open, setOpen] = useState(false)
  const [searching, setSearching] = useState(false)
  const [locating, setLocating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const listId = useId()
  const containerRef = useRef<HTMLDivElement>(null)

  // Debounced so typing does not fire a request per keystroke; Nominatim asks
  // for at most one call a second.
  useEffect(() => {
    if (value || query.trim().length < 3) {
      setResults([])
      return
    }

    const controller = new AbortController()
    const timer = setTimeout(() => {
      setSearching(true)
      setError(null)

      geocoding
        .search(query, controller.signal)
        .then((places) => {
          setResults(places)
          setOpen(true)
        })
        .catch((caught: unknown) => {
          if (caught instanceof Error && caught.name === 'AbortError') return
          setError('Could not search addresses right now.')
        })
        .finally(() => setSearching(false))
    }, 400)

    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [query, value])

  useEffect(() => {
    function onClickAway(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false)
    }

    document.addEventListener('mousedown', onClickAway)
    return () => document.removeEventListener('mousedown', onClickAway)
  }, [])

  function select(place: Place) {
    onChange({ label: place.description, lat: place.lat, lng: place.lng })
    setQuery('')
    setResults([])
    setOpen(false)
  }

  async function useMyLocation() {
    setLocating(true)
    setError(null)

    try {
      const position = await getCurrentPosition()
      // Best effort: a readable address is nicer, but the coordinates are
      // what the booking actually needs, so a failed lookup is not fatal.
      const address = await geocoding.reverse(position.lat, position.lng).catch(() => null)

      onChange({
        label: address ?? `${position.lat.toFixed(5)}, ${position.lng.toFixed(5)}`,
        lat: position.lat,
        lng: position.lng,
      })
      setQuery('')
    } catch (caught) {
      setError(
        caught instanceof GeolocationError ? caught.message : 'Could not get your location.',
      )
    } finally {
      setLocating(false)
    }
  }

  return (
    <div ref={containerRef} className="relative">
      <Field label={label} required={required} error={error ?? undefined}>
        {value ? (
          <div className="flex items-center gap-2 rounded-lg bg-white px-3 py-2 text-sm shadow-sm ring-1 ring-inset ring-ink-200">
            <MapPin className="size-4 shrink-0 text-brand-600" aria-hidden />
            <span className="min-w-0 flex-1 truncate text-ink-900">{value.label}</span>
            <button
              type="button"
              onClick={() => onChange(null)}
              aria-label={`Clear ${label.toLowerCase()}`}
              className="rounded p-0.5 text-ink-400 hover:bg-ink-100 hover:text-ink-700"
            >
              <X className="size-4" aria-hidden />
            </button>
          </div>
        ) : (
          <Input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onFocus={() => results.length > 0 && setOpen(true)}
            placeholder={placeholder}
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            autoComplete="off"
          />
        )}
      </Field>

      {showUseMyLocation && !value && isGeolocationSupported() && (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="mt-1 -ml-2"
          loading={locating}
          onClick={useMyLocation}
        >
          <LocateFixed className="size-3.5" aria-hidden />
          Use my current location
        </Button>
      )}

      {open && !value && (results.length > 0 || searching) && (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-ink-200 bg-white py-1 shadow-lg"
        >
          {searching && results.length === 0 && (
            <li className="px-3 py-2 text-xs text-ink-500">Searching…</li>
          )}

          {results.map((place) => (
            <li key={place.id}>
              <button
                type="button"
                role="option"
                aria-selected={false}
                onClick={() => select(place)}
                className={cn(
                  'flex w-full items-start gap-2 px-3 py-2 text-left text-sm',
                  'hover:bg-brand-50 focus:bg-brand-50 focus:outline-none',
                )}
              >
                <MapPin className="mt-0.5 size-4 shrink-0 text-ink-400" aria-hidden />
                <span className="min-w-0">
                  <span className="block truncate font-medium text-ink-900">{place.label}</span>
                  <span className="block truncate text-xs text-ink-500">{place.description}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
