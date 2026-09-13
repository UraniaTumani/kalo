import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Car, CreditCard, MapPin, Search, Star } from 'lucide-react'
import { rideApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import type { RideSearchResponse, TaxiOptionResponse } from '@/lib/api/types'
import { MapView, TIRANA } from '@/components/MapPicker'
import { AddressInput, type SelectedPlace } from '@/components/AddressInput'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { useStatusLabel } from '@/components/StatusBadge'
import { Alert, Badge, Button, Card, CardBody, EmptyState } from '@/components/ui'
import { formatDistance } from '@/lib/utils'
import { rememberRide } from './rideMemory'

export function BookRidePage() {
  const { t } = useTranslation()
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
  const hasActiveRideConflict = searchError instanceof ApiError && searchError.status === 409

  return (
    <>
      <PageHeader title={t('booking.title')} description={t('booking.subtitle')} />

      {hasActiveRideConflict && (
        <div className="mb-5">
          <Alert tone="warning" title={t('booking.activeRideTitle')}>
            <div className="mt-2">
              <Button size="sm" variant="secondary" onClick={() => navigate('/ride/current')}>
                {t('booking.goToCurrentRide')}
                <ArrowRight className="size-3.5" aria-hidden />
              </Button>
            </div>
          </Alert>
        </div>
      )}

      {/*
        The booking card owns the page rather than sharing a row with the
        results. Where somebody is going is the whole question on this screen;
        the map only confirms the answer, so it sits beside the inputs at a size
        that says "check this" rather than "use this".
      */}
      <Card className="overflow-hidden">
        <CardBody className="p-5 sm:p-6">
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_18rem]">
            <div className="space-y-4">
              <div>
                <h2 className="text-base font-bold tracking-tight text-ink-900">
                  {t('booking.whereTo')}
                </h2>
                <p className="mt-0.5 text-sm text-ink-500">{t('booking.searchHint')}</p>
              </div>

              {/*
                The rail ties the two fields into one journey, the way a ticket
                shows an origin and a destination rather than two unrelated
                questions.
              */}
              <div className="relative space-y-3 pl-7">
                <span
                  aria-hidden
                  className="absolute left-[7px] top-3 bottom-3 w-px bg-ink-200"
                />
                <span
                  aria-hidden
                  className="absolute left-0 top-[10px] size-3.5 rounded-full border-2 border-good-500 bg-white"
                />
                <span
                  aria-hidden
                  className="absolute bottom-[10px] left-0 size-3.5 rounded-sm border-2 border-brand-500 bg-white"
                />

                <AddressInput
                  label={t('booking.pickup')}
                  required
                  showUseMyLocation
                  placeholder={t('booking.pickupPlaceholder')}
                  value={pickup}
                  onChange={(place) => {
                    setPickup(place)
                    setSearch(null)
                  }}
                />

                <AddressInput
                  label={t('booking.destination')}
                  required
                  placeholder={t('booking.destinationPlaceholder')}
                  value={destination}
                  onChange={(place) => {
                    setDestination(place)
                    setSearch(null)
                  }}
                />
              </div>

              {searchError && !hasActiveRideConflict && <ErrorMessage error={searchError} />}

              <Button
                size="lg"
                onClick={() => searchMutation.mutate()}
                disabled={!canSearch}
                loading={searchMutation.isPending}
                className="w-full"
              >
                {!searchMutation.isPending && <Search className="size-4" aria-hidden />}
                {t('booking.searchTaxis')}
              </Button>
            </div>

            {/* Confirmation only — the addresses above are the real input. */}
            <MapView
              center={pickup ? { lat: pickup.lat, lng: pickup.lng } : TIRANA}
              className="h-52 w-full rounded-xl lg:h-full lg:min-h-[19rem]"
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
          </div>
        </CardBody>
      </Card>

      {/*
        Full width, and a grid rather than a narrow column. Choosing a company is
        a comparison — ratings, distance, car, how they take payment — and three
        cards squeezed into 20rem made that impossible to do at a glance.
      */}
      <section className="mt-6">
        <div className="mb-3 flex items-baseline justify-between gap-3">
          <h2 className="text-base font-bold tracking-tight text-ink-900">
            {t('booking.companies')}
          </h2>
          <p className="text-sm text-ink-500">
            {search
              ? t('booking.availableNear', { count: search.taxiOptions.length })
              : t('booking.youChoose')}
          </p>
        </div>

        {selectMutation.error && (
          <div className="mb-3">
            <ErrorMessage error={selectMutation.error} />
          </div>
        )}

        {!search && (
          <Card>
            <EmptyState
              icon={<Car className="size-5" aria-hidden />}
              title={t('booking.runSearch')}
              description={t('booking.youChoose')}
            />
          </Card>
        )}

        {search && search.taxiOptions.length === 0 && (
          <Alert tone="warning" title={t('booking.noneAvailable')}>
            {t('booking.noneAvailableHint')}
          </Alert>
        )}

        {search && search.taxiOptions.length > 0 && (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {search.taxiOptions.map((option) => (
              <OfferCard
                key={option.offerId}
                option={option}
                disabled={selectMutation.isPending}
                pending={
                  selectMutation.isPending && selectMutation.variables?.offerId === option.offerId
                }
                onSelect={() => selectMutation.mutate(option)}
              />
            ))}
          </div>
        )}
      </section>
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
  const { t } = useTranslation()
  const label = useStatusLabel()

  return (
    <div className="flex flex-col rounded-2xl border border-ink-200/60 bg-white p-4 shadow-[var(--shadow-card)] transition hover:border-ink-300 hover:shadow-[var(--shadow-raised)]">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-bold tracking-tight text-ink-900">
            {option.companyName}
          </p>

          {/*
            A number and a count, not a row of five stars: at a glance somebody
            wants "4.5 out of 12 rides", and five outlines take more space to say
            less.
          */}
          <p className="mt-1 flex items-center gap-1.5 text-xs text-ink-500">
            {option.companyRating ? (
              <>
                <Star className="size-3.5 fill-brand-400 text-brand-400" aria-hidden />
                <span className="tnum font-semibold text-ink-800">
                  {option.companyRating.toFixed(1)}
                </span>
                <span className="text-ink-400">({option.companyRatingCount ?? 0})</span>
              </>
            ) : (
              t('booking.notRated')
            )}
          </p>
        </div>

        <Badge tone="brand">
          {t('booking.away', { distance: formatDistance(option.distanceKm) })}
        </Badge>
      </div>

      <div className="mt-3 flex items-start gap-2 rounded-xl bg-ink-50 px-3 py-2">
        <Car className="mt-0.5 size-3.5 shrink-0 text-ink-400" aria-hidden />
        <div className="min-w-0 text-xs">
          <p className="truncate font-medium text-ink-800">
            {option.vehicleBrand} {option.vehicleModel}
          </p>
          <p className="tnum truncate text-ink-500">{option.plateNumber}</p>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap gap-1.5">
        <Badge>{label('vehicleType', option.vehicleType)}</Badge>
        {option.paymentMethods.map((method) => (
          <Badge key={method} tone="neutral">
            <CreditCard className="size-3" aria-hidden />
            {label('paymentMethod', method)}
          </Badge>
        ))}
      </div>

      {/* Pushed to the bottom so every card's button sits on one line. */}
      <div className="mt-auto pt-4">
        <p className="mb-2 flex items-start gap-1.5 text-[11px] leading-relaxed text-ink-500">
          <MapPin className="mt-0.5 size-3 shrink-0" aria-hidden />
          {t('booking.pricingNote')}
        </p>

        <Button className="w-full" onClick={onSelect} disabled={disabled} loading={pending}>
          {t('booking.chooseCompany')}
        </Button>
      </div>
    </div>
  )
}
