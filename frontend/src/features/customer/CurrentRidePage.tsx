import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { rideApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import type { RideResponse, RideStatus } from '@/lib/api/types'
import { isActiveRide } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader, EmptyState } from '@/components/ui'
import { cn, formatCurrency, formatTime } from '@/lib/utils'
import { recallRide, rememberRide } from './rideMemory'
import { RatingForm } from './RatingForm'

/** The happy path, in the order the partner drives it. */
const TIMELINE: { status: RideStatus; label: string; at: keyof RideResponse }[] = [
  { status: 'REQUESTED', label: 'Request sent to company', at: 'requestedAt' },
  { status: 'DRIVER_ASSIGNED', label: 'Driver assigned', at: 'acceptedAt' },
  { status: 'DRIVER_ARRIVING', label: 'Driver on the way', at: 'driverArrivingAt' },
  { status: 'DRIVER_ARRIVED', label: 'Driver arrived', at: 'driverArrivedAt' },
  { status: 'IN_PROGRESS', label: 'Ride started', at: 'startedAt' },
  { status: 'COMPLETED', label: 'Completed', at: 'completedAt' },
]

export function CurrentRidePage() {
  const queryClient = useQueryClient()
  const rememberedRideId = recallRide()

  const currentQuery = useQuery({
    queryKey: ['ride', 'current'],
    queryFn: () => rideApi.current(),
    // No WebSockets on the backend, so the client polls while a ride is live.
    refetchInterval: (query) => {
      const ride = query.state.data
      return ride && isActiveRide(ride.status) ? 5000 : false
    },
    retry: (failureCount, error) =>
      error instanceof ApiError && error.status === 404 ? false : failureCount < 2,
  })

  const noActiveRide = currentQuery.error instanceof ApiError && currentQuery.error.status === 404

  // Falls back to the last ride we saw, so a ride that just finished (or was
  // declined) still shows its outcome instead of an empty page.
  const lastRideQuery = useQuery({
    queryKey: ['ride', rememberedRideId],
    queryFn: () => rideApi.byId(rememberedRideId!),
    enabled: noActiveRide && rememberedRideId !== null,
  })

  const ride = currentQuery.data ?? (noActiveRide ? lastRideQuery.data : undefined)

  useEffect(() => {
    if (currentQuery.data) rememberRide(currentQuery.data.rideId)
  }, [currentQuery.data])

  const cancelMutation = useMutation({
    mutationFn: (rideId: number) => rideApi.cancel(rideId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ride'] })
    },
  })

  if (currentQuery.isLoading || (noActiveRide && lastRideQuery.isLoading)) {
    return <Spinner label="Checking for an active ride" />
  }

  if (!ride) {
    return (
      <>
        <PageHeader title="Current ride" />
        <Card>
          <EmptyState
            title="No active ride"
            description="Book a taxi and it will show up here with live status."
            action={
              <Link to="/ride">
                <Button size="sm">Book a taxi</Button>
              </Link>
            }
          />
        </Card>
      </>
    )
  }

  const active = isActiveRide(ride.status)
  const cancellable = active && ride.status !== 'IN_PROGRESS'

  return (
    <>
      <PageHeader
        title={active ? 'Current ride' : 'Your last ride'}
        description={`${ride.companyName} · ride #${ride.rideId}`}
        action={<RideStatusBadge status={ride.status} />}
      />

      {ride.status === 'REQUESTED' && (
        <div className="mb-4">
          <Alert tone="info" title="Waiting for the company to respond">
            If they do not answer in time, the request returns to searching and you can pick
            another company.
          </Alert>
        </div>
      )}

      {(ride.status === 'DECLINED' || ride.status === 'NO_RESPONSE') && (
        <div className="mb-4">
          <Alert tone="warning" title={
            ride.status === 'DECLINED'
              ? 'The company declined this ride'
              : 'The company did not respond in time'
          }>
            <div className="mt-2">
              <Link to="/ride">
                <Button size="sm" variant="secondary">
                  Choose another company
                </Button>
              </Link>
            </div>
          </Alert>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <Card>
          <CardHeader title="Progress" />
          <CardBody>
            <ol className="space-y-3">
              {TIMELINE.map((step) => {
                const timestamp = ride[step.at] as string | null
                const done = Boolean(timestamp)

                return (
                  <li key={step.status} className="flex items-start gap-3">
                    <span
                      className={cn(
                        'mt-0.5 grid size-5 shrink-0 place-items-center rounded-full text-white',
                        done ? 'bg-brand-600' : 'bg-ink-200',
                      )}
                    >
                      {done && <Check className="size-3" aria-hidden />}
                    </span>
                    <span className="flex-1">
                      <span
                        className={cn(
                          'block text-sm',
                          done ? 'font-medium text-ink-900' : 'text-ink-400',
                        )}
                      >
                        {step.label}
                      </span>
                      {done && (
                        <span className="block text-xs text-ink-500">
                          {formatTime(timestamp)}
                        </span>
                      )}
                    </span>
                  </li>
                )
              })}
            </ol>

            {cancelMutation.error && (
              <div className="mt-4">
                <ErrorMessage error={cancelMutation.error} />
              </div>
            )}

            {cancellable && (
              <Button
                variant="danger"
                size="sm"
                className="mt-4"
                loading={cancelMutation.isPending}
                onClick={() => cancelMutation.mutate(ride.rideId)}
              >
                Cancel ride
              </Button>
            )}
          </CardBody>
        </Card>

        <div className="space-y-4">
          <Card>
            <CardHeader title="Trip" />
            <CardBody className="space-y-2 text-sm">
              <Detail label="From" value={ride.pickupAddress ?? 'Point on map'} />
              <Detail label="To" value={ride.destinationAddress ?? 'Point on map'} />
              <Detail label="Company" value={ride.companyName} />
              <Detail
                label="Fare"
                value={
                  ride.finalAmount === null
                    ? 'By taximeter'
                    : formatCurrency(ride.finalAmount)
                }
              />
            </CardBody>
          </Card>

          {ride.status === 'COMPLETED' && <RatingForm rideId={ride.rideId} />}
        </div>
      </div>
    </>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-ink-500">{label}</span>
      <span className="text-right font-medium text-ink-900">{value}</span>
    </div>
  )
}
