import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Loader2, MapPin, Star } from 'lucide-react'
import { rideApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import type { RideSearchResponse, TaxiOptionResponse } from '@/lib/api/types'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Alert, Badge, Button, Card, CardBody, CardHeader, Field, Input } from '@/components/ui'
import { cn, formatDistance, humanise } from '@/lib/utils'
import { rememberRide } from './rideMemory'

type Picking = 'pickup' | 'destination'

export function BookRidePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [picking, setPicking] = useState<Picking>('pickup')
  const [pickup, setPickup] = useState<LatLng | null>(null)
  const [destination, setDestination] = useState<LatLng | null>(null)
  const [pickupAddress, setPickupAddress] = useState('')
  const [destinationAddress, setDestinationAddress] = useState('')
  const [search, setSearch] = useState<RideSearchResponse | null>(null)

  const searchMutation = useMutation({
    mutationFn: () =>
      rideApi.search({
        pickupLatitude: pickup!.lat,
        pickupLongitude: pickup!.lng,
        pickupAddress: pickupAddress.trim() || null,
        destinationLatitude: destination!.lat,
        destinationLongitude: destination!.lng,
        destinationAddress: destinationAddress.trim() || null,
      }),
    onSuccess: setSearch,
  })

  const selectMutation = useMutation({
    mutationFn: (offer: TaxiOptionResponse) =>
      rideApi.selectOffer(search!.rideRequestId, offer.offerId),
    onSuccess: (ride) => {
      rememberRide(ride.rideId)
      queryClient.invalidateQueries({ queryKey: ['ride', 'current'] })
      navigate('/ride/current')
    },
  })

  function handleMapPick(position: LatLng) {
    if (picking === 'pickup') {
      setPickup(position)
      setPicking('destination')
    } else {
      setDestination(position)
    }
    setSearch(null)
  }

  const canSearch = Boolean(pickup && destination) && !searchMutation.isPending

  const searchError = searchMutation.error
  const hasActiveRideConflict =
    searchError instanceof ApiError && searchError.status === 409

  return (
    <>
      <PageHeader
        title="Book a taxi"
        description="Pick your start and destination, then choose a taxi company."
      />

      {hasActiveRideConflict && (
        <div className="mb-4">
          <Alert tone="warning" title="You already have a ride in progress">
            <div className="mt-2">
              <Button size="sm" variant="secondary" onClick={() => navigate('/ride/current')}>
                Go to current ride
              </Button>
            </div>
          </Alert>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <Card>
          <CardHeader
            title="Where are you going?"
            description={
              picking === 'pickup'
                ? 'Click the map to set your pickup point.'
                : 'Now click the map to set your destination.'
            }
          />
          <CardBody className="space-y-3">
            <MapView
              center={pickup ?? TIRANA}
              onPick={handleMapPick}
              className="h-80 w-full rounded-lg"
              markers={[
                ...(pickup ? [{ position: pickup, label: 'Pickup', tone: 'pickup' as const }] : []),
                ...(destination
                  ? [{ position: destination, label: 'Destination', tone: 'destination' as const }]
                  : []),
              ]}
            />

            <div className="grid gap-2 sm:grid-cols-2">
              <PointButton
                active={picking === 'pickup'}
                label="Pickup"
                point={pickup}
                tone="pickup"
                onClick={() => setPicking('pickup')}
              />
              <PointButton
                active={picking === 'destination'}
                label="Destination"
                point={destination}
                tone="destination"
                onClick={() => setPicking('destination')}
              />
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Pickup address (optional)">
                <Input
                  value={pickupAddress}
                  onChange={(event) => setPickupAddress(event.target.value)}
                  placeholder="Rruga e Durresit 45"
                  maxLength={500}
                />
              </Field>
              <Field label="Destination address (optional)">
                <Input
                  value={destinationAddress}
                  onChange={(event) => setDestinationAddress(event.target.value)}
                  placeholder="Sheshi Skenderbej"
                  maxLength={500}
                />
              </Field>
            </div>

            {searchError && !hasActiveRideConflict && <ErrorMessage error={searchError} />}

            <Button
              onClick={() => searchMutation.mutate()}
              disabled={!canSearch}
              loading={searchMutation.isPending}
              className="w-full"
            >
              Search available taxis
            </Button>
          </CardBody>
        </Card>

        <div className="space-y-4">
          <Card>
            <CardHeader
              title="Taxi companies"
              description={
                search
                  ? `${search.taxiOptions.length} available near your pickup`
                  : 'Run a search to see who is available.'
              }
            />
            <CardBody className="space-y-3">
              {!search && (
                <p className="text-sm text-ink-500">
                  You choose the company. They assign one of their drivers.
                </p>
              )}

              {search && search.taxiOptions.length === 0 && (
                <Alert tone="warning" title="No taxis available right now">
                  No company has an online driver near your pickup point. Try again shortly.
                </Alert>
              )}

              {selectMutation.error && <ErrorMessage error={selectMutation.error} />}

              {search?.taxiOptions.map((option) => (
                <OfferCard
                  key={option.offerId}
                  option={option}
                  disabled={selectMutation.isPending}
                  pending={
                    selectMutation.isPending &&
                    selectMutation.variables?.offerId === option.offerId
                  }
                  onSelect={() => selectMutation.mutate(option)}
                />
              ))}
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  )
}

function PointButton({
  active,
  label,
  point,
  tone,
  onClick,
}: {
  active: boolean
  label: string
  point: LatLng | null
  tone: 'pickup' | 'destination'
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex items-center gap-2 rounded-lg border px-3 py-2 text-left text-sm transition',
        active ? 'border-brand-400 bg-brand-50' : 'border-ink-200 bg-white hover:bg-ink-50',
      )}
    >
      <MapPin
        className={cn('size-4', tone === 'pickup' ? 'text-brand-600' : 'text-emerald-600')}
        aria-hidden
      />
      <span>
        <span className="block text-xs font-medium text-ink-700">{label}</span>
        <span className="block text-xs text-ink-500">
          {point ? `${point.lat.toFixed(4)}, ${point.lng.toFixed(4)}` : 'Not set'}
        </span>
      </span>
    </button>
  )
}

function OfferCard({
  option,
  onSelect,
  disabled,
  pending,
}: {
  option: TaxiOptionResponse
  onSelect: () => void
  disabled: boolean
  pending: boolean
}) {
  return (
    <div className="rounded-lg border border-ink-200 p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-ink-900">{option.companyName}</p>
          <p className="mt-0.5 flex items-center gap-1 text-xs text-ink-500">
            {option.companyRating ? (
              <>
                <Star className="size-3 fill-amber-400 text-amber-400" aria-hidden />
                {option.companyRating.toFixed(1)}
                <span className="text-ink-400">({option.companyRatingCount ?? 0})</span>
              </>
            ) : (
              'Not rated yet'
            )}
          </p>
        </div>
        <Badge tone="info">{formatDistance(option.distanceKm)} away</Badge>
      </div>

      <p className="mt-2 text-xs text-ink-600">
        {option.vehicleBrand} {option.vehicleModel} · {option.plateNumber}
      </p>

      <div className="mt-2 flex flex-wrap gap-1">
        {option.paymentMethods.map((method) => (
          <Badge key={method}>{humanise(method)}</Badge>
        ))}
      </div>

      <p className="mt-2 text-xs text-ink-500">{option.pricingNote}</p>

      <Button
        size="sm"
        className="mt-3 w-full"
        onClick={onSelect}
        disabled={disabled}
        loading={pending}
      >
        {pending ? <Loader2 className="size-3.5 animate-spin" aria-hidden /> : null}
        Choose this company
      </Button>
    </div>
  )
}
