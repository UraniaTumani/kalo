import { useEffect } from 'react'
import { MapContainer, Marker, Popup, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import markerIcon from 'leaflet/dist/images/marker-icon.png'
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png'
import markerShadow from 'leaflet/dist/images/marker-shadow.png'

// Leaflet's default icon URLs assume a webpack-style asset layout; under Vite
// they resolve to 404s unless pointed at the bundled assets explicitly.
const defaultIcon = L.icon({
  iconUrl: markerIcon,
  iconRetinaUrl: markerIcon2x,
  shadowUrl: markerShadow,
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
})

L.Marker.prototype.options.icon = defaultIcon

export const TIRANA: LatLng = { lat: 41.3275, lng: 19.8187 }

export interface LatLng {
  lat: number
  lng: number
}

export interface MapMarker {
  position: LatLng
  label?: string
  tone?: 'pickup' | 'destination' | 'driver'
}

const toneColors: Record<NonNullable<MapMarker['tone']>, string> = {
  pickup: '#1d71f1',
  destination: '#059669',
  driver: '#d97706',
}

function coloredIcon(tone: NonNullable<MapMarker['tone']>) {
  const color = toneColors[tone]

  return L.divIcon({
    className: '',
    html: `<span style="display:block;width:18px;height:18px;border-radius:9999px;background:${color};border:3px solid white;box-shadow:0 1px 4px rgba(0,0,0,.4)"></span>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  })
}

function ClickHandler({ onPick }: { onPick: (position: LatLng) => void }) {
  useMapEvents({
    click: (event) => onPick({ lat: event.latlng.lat, lng: event.latlng.lng }),
  })
  return null
}

/** Keeps every marker in view as they change. */
function FitBounds({ markers }: { markers: MapMarker[] }) {
  const map = useMap()

  useEffect(() => {
    if (markers.length === 0) return

    /*
     * Not animated, deliberately.
     *
     * Leaflet animates both of these by default, and an animation still
     * running when the map goes away reads a position off an element that has
     * been removed: "Cannot read properties of undefined (reading
     * '_leaflet_pos')", uncaught, in the browser. Opening the cancel dialog
     * over a ride map is enough to trigger it.
     *
     * The animation was never worth anything here either. This is the map
     * framing its own markers as the data arrives, not a person panning — the
     * markers should simply be in view, and a tween between two states the
     * user did not ask for is just a chance to be interrupted.
     */
    if (markers.length === 1) {
      map.setView([markers[0].position.lat, markers[0].position.lng], 14, {
        animate: false,
      })
      return
    }

    map.fitBounds(
      L.latLngBounds(markers.map((marker) => [marker.position.lat, marker.position.lng])),
      { padding: [40, 40], maxZoom: 15, animate: false },
    )
  }, [map, markers])

  return null
}

export function MapView({
  markers = [],
  center = TIRANA,
  zoom = 13,
  onPick,
  className = 'h-72 w-full rounded-lg',
}: {
  markers?: MapMarker[]
  center?: LatLng
  zoom?: number
  onPick?: (position: LatLng) => void
  className?: string
}) {
  return (
    <MapContainer
      center={[center.lat, center.lng]}
      zoom={zoom}
      scrollWheelZoom
      className={className}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />

      {onPick && <ClickHandler onPick={onPick} />}
      <FitBounds markers={markers} />

      {markers.map((marker, index) => (
        <Marker
          key={`${marker.position.lat}-${marker.position.lng}-${index}`}
          position={[marker.position.lat, marker.position.lng]}
          icon={marker.tone ? coloredIcon(marker.tone) : defaultIcon}
        >
          {marker.label && <Popup>{marker.label}</Popup>}
        </Marker>
      ))}
    </MapContainer>
  )
}
