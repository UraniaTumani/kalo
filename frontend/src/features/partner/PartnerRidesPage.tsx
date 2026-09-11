import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import type { PartnerRideResponse, RideStatus } from '@/lib/api/types'
import { isActiveRide } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { MapView } from '@/components/MapPicker'
import { Spinner } from '@/components/ui/Spinner'
import {
  Alert,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Field,
  Input,
  Select,
  Table,
  Td,
  Th,
} from '@/components/ui'
import { formatDateTime, humanise } from '@/lib/utils'

const FILTERS: (RideStatus | 'ALL')[] = [
  'ALL',
  'REQUESTED',
  'DRIVER_ASSIGNED',
  'DRIVER_ARRIVING',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
  'COMPLETED',
  'DECLINED',
  'NO_RESPONSE',
  'CANCELLED',
]

export function PartnerRidesPage() {
  const [status, setStatus] = useState<RideStatus | 'ALL'>('ALL')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<PartnerRideResponse | null>(null)

  const ridesQuery = useQuery({
    queryKey: ['partner', 'rides', status, page],
    queryFn: () =>
      partnerApi.rides({
        page,
        size: 10,
        status: status === 'ALL' ? undefined : status,
      }),
    // New ride requests arrive without any push channel, and REQUESTED rides
    // time out server-side, so the queue is polled.
    refetchInterval: 10_000,
  })

  const active = selected
    ? ridesQuery.data?.content.find((ride) => ride.rideId === selected.rideId) ?? selected
    : null

  return (
    <>
      <PageHeader
        title="Rides"
        description="Accept a request, assign a driver, and drive it to completion."
        action={
          <Select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as RideStatus | 'ALL')
              setPage(0)
            }}
            className="w-48"
          >
            {FILTERS.map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? 'All statuses' : humanise(value)}
              </option>
            ))}
          </Select>
        }
      />

      {ridesQuery.error && <ErrorMessage error={ridesQuery.error} />}

      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <Card>
          {ridesQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {ridesQuery.data?.empty && (
            <EmptyState
              title="No rides"
              description="Requests from passengers who chose your company appear here."
            />
          )}

          {ridesQuery.data && !ridesQuery.data.empty && (
            <>
              <Table>
                <thead>
                  <tr>
                    <Th>Requested</Th>
                    <Th>Passenger</Th>
                    <Th>Route</Th>
                    <Th>Status</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody>
                  {ridesQuery.data.content.map((ride) => (
                    <tr key={ride.rideId}>
                      <Td className="whitespace-nowrap">{formatDateTime(ride.requestedAt)}</Td>
                      <Td>
                        <span className="font-medium">
                          {ride.customerFirstName} {ride.customerLastName}
                        </span>
                        <span className="block text-xs text-ink-500">{ride.customerPhone}</span>
                      </Td>
                      <Td className="text-xs text-ink-500">
                        {ride.pickupAddress ?? 'Map point'} →{' '}
                        {ride.destinationAddress ?? 'Map point'}
                      </Td>
                      <Td>
                        <RideStatusBadge status={ride.status} />
                      </Td>
                      <Td>
                        <Button size="sm" variant="secondary" onClick={() => setSelected(ride)}>
                          Manage
                        </Button>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={ridesQuery.data} onPageChange={setPage} />
            </>
          )}
        </Card>

        <div>
          {active ? (
            <RideActions ride={active} onDone={() => ridesQuery.refetch()} />
          ) : (
            <Card>
              <EmptyState
                title="No ride selected"
                description="Pick a ride from the list to act on it."
              />
            </Card>
          )}
        </div>
      </div>
    </>
  )
}

function RideActions({ ride, onDone }: { ride: PartnerRideResponse; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [driverId, setDriverId] = useState('')
  const [finalAmount, setFinalAmount] = useState('')

  // Only ONLINE drivers can be assigned; the backend rejects anything else.
  const driversQuery = useQuery({
    queryKey: ['partner', 'drivers'],
    queryFn: () => partnerApi.drivers(),
    enabled: ride.status === 'REQUESTED',
  })

  const assignableDrivers =
    driversQuery.data?.filter(
      (driver) => driver.status === 'ACTIVE' && driver.availabilityStatus === 'ONLINE',
    ) ?? []

  function runAction<T>(action: () => Promise<T>) {
    return action().then((result) => {
      queryClient.invalidateQueries({ queryKey: ['partner'] })
      onDone()
      return result
    })
  }

  const accept = useMutation({
    mutationFn: () => runAction(() => partnerApi.accept(ride.rideId, Number(driverId))),
  })
  const decline = useMutation({
    mutationFn: () => runAction(() => partnerApi.decline(ride.rideId)),
  })
  const arriving = useMutation({
    mutationFn: () => runAction(() => partnerApi.driverArriving(ride.rideId)),
  })
  const arrived = useMutation({
    mutationFn: () => runAction(() => partnerApi.driverArrived(ride.rideId)),
  })
  const start = useMutation({
    mutationFn: () => runAction(() => partnerApi.start(ride.rideId)),
  })
  const complete = useMutation({
    mutationFn: () => runAction(() => partnerApi.complete(ride.rideId, Number(finalAmount))),
  })

  const error =
    accept.error ??
    decline.error ??
    arriving.error ??
    arrived.error ??
    start.error ??
    complete.error

  return (
    <Card>
      <CardHeader
        title={`Ride #${ride.rideId}`}
        description={`${ride.customerFirstName} ${ride.customerLastName} · ${ride.customerPhone}`}
        action={<RideStatusBadge status={ride.status} />}
      />
      <CardBody className="space-y-4">
        <MapView
          className="h-44 w-full rounded-lg"
          markers={[
            {
              position: { lat: ride.pickupLatitude, lng: ride.pickupLongitude },
              label: ride.pickupAddress ?? 'Pickup',
              tone: 'pickup',
            },
            {
              position: { lat: ride.destinationLatitude, lng: ride.destinationLongitude },
              label: ride.destinationAddress ?? 'Destination',
              tone: 'destination',
            },
          ]}
        />

        {error && <ErrorMessage error={error} />}

        {ride.status === 'REQUESTED' && (
          <div className="space-y-3">
            {driversQuery.data && assignableDrivers.length === 0 ? (
              <Alert tone="warning" title="No driver available">
                A driver must be ACTIVE, ONLINE and hold an active vehicle assignment before you
                can accept.
              </Alert>
            ) : (
              <Field label="Assign driver" required>
                <Select value={driverId} onChange={(event) => setDriverId(event.target.value)}>
                  <option value="">Select a driver…</option>
                  {assignableDrivers.map((driver) => (
                    <option key={driver.id} value={driver.id}>
                      {driver.firstName} {driver.lastName} · {driver.phone}
                    </option>
                  ))}
                </Select>
              </Field>
            )}

            <div className="flex gap-2">
              <Button
                variant="success"
                className="flex-1"
                disabled={!driverId}
                loading={accept.isPending}
                onClick={() => accept.mutate()}
              >
                Accept
              </Button>
              <Button
                variant="danger"
                className="flex-1"
                loading={decline.isPending}
                onClick={() => decline.mutate()}
              >
                Decline
              </Button>
            </div>
          </div>
        )}

        {ride.status === 'DRIVER_ASSIGNED' && (
          <Button className="w-full" loading={arriving.isPending} onClick={() => arriving.mutate()}>
            Driver is on the way
          </Button>
        )}

        {ride.status === 'DRIVER_ARRIVING' && (
          <Button className="w-full" loading={arrived.isPending} onClick={() => arrived.mutate()}>
            Driver has arrived
          </Button>
        )}

        {ride.status === 'DRIVER_ARRIVED' && (
          <Button className="w-full" loading={start.isPending} onClick={() => start.mutate()}>
            Start ride
          </Button>
        )}

        {ride.status === 'IN_PROGRESS' && (
          <div className="space-y-3">
            <Field
              label="Taximeter total"
              required
              hint="The real amount the passenger pays the driver"
            >
              <Input
                type="number"
                min="0.01"
                step="0.01"
                value={finalAmount}
                onChange={(event) => setFinalAmount(event.target.value)}
                placeholder="850.00"
              />
            </Field>
            <Button
              variant="success"
              className="w-full"
              disabled={!finalAmount || Number(finalAmount) <= 0}
              loading={complete.isPending}
              onClick={() => complete.mutate()}
            >
              Complete ride
            </Button>
          </div>
        )}

        {!isActiveRide(ride.status) && (
          <Alert tone="neutral">
            This ride is finished ({humanise(ride.status)}). No further action is possible.
          </Alert>
        )}
      </CardBody>
    </Card>
  )
}
