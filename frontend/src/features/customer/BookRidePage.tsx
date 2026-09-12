import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Loader2, Star } from 'lucide-react'
import { rideApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import type { RideSearchResponse, TaxiOptionResponse } from '@/lib/api/types'
import { MapView, TIRANA } from '@/components/MapPicker'
import { AddressInput, type SelectedPlace } from '@/components/AddressInput'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Alert, Badge, Button, Card, CardBody, CardHeader } from '@/components/ui'
import { formatDistance, humanise } from '@/lib/utils'
import { rememberRide } from './rideMemory'

export function BookRidePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [pickup, setPickup] = useState<SelectedPlace | null>(null)
  const [destination, setDestination] = useState<SelectedPlace | null>(null)
  const [search, setSearch] = useState<RideSearchResponse | null>(null)

  const searchMutation = useMutation({
    mutationFn: () =>
      rideApi.search({
        pickupLatitude: pickup!.lat,
        pickupLongitude: pickup!.lng,
        pickupAddress: pickup!.label,
        destinationLatitude: destination!.lat,
        destinationLongitude: destination!.lng,
        destinationAddress: destination!.label,
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
            description="Search for an address, or use your current location."
          />
          <CardBody className="space-y-3">
            <AddressInput
              label="Pickup"
              required
              showUseMyLocation
              placeholder="Search an address or place"
              value={pickup}
              onChange={(place) => {
                setPickup(place)
                setSearch(null)
              }}
            />

            <AddressInput
              label="Destination"
              required
              placeholder="Where to?"
              value={destination}
              onChange={(place) => {
                setDestination(place)
                setSearch(null)
              }}
            />

            {/* Confirmation only — the addresses above are the real input. */}
            <MapView
              center={pickup ? { lat: pickup.lat, lng: pickup.lng } : TIRANA}
              className="h-64 w-full rounded-lg"
              markers={[
                ...(pickup
                  ? [
                      {
                        position: { lat: pickup.lat, lng: pickup.lng },
                        label: pickup.label,
                        tone: 'pickup' as const,
                      },
                    ]
                  : []),
                ...(destination
                  ? [
                      {
                        position: { lat: destination.lat, lng: destination.lng },
                        label: destination.label,
                        tone: 'destination' as const,
                      },
                    ]
                  : []),
              ]}
            />

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
