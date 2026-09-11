import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import type { RideStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { MapView } from '@/components/MapPicker'
import { Spinner } from '@/components/ui/Spinner'
import {
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Select,
  Table,
  Td,
  Th,
} from '@/components/ui'
import { formatCurrency, formatDateTime, humanise } from '@/lib/utils'

const FILTERS: (RideStatus | 'ALL')[] = [
  'ALL',
  'REQUESTED',
  'DRIVER_ASSIGNED',
  'DRIVER_ARRIVING',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
  'COMPLETED',
  'DECLINED',
  'CANCELLED',
  'NO_RESPONSE',
]

export function AdminRidesPage() {
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<RideStatus | 'ALL'>('ALL')
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const ridesQuery = useQuery({
    queryKey: ['admin', 'rides', status, page],
    queryFn: () =>
      adminApi.rides({
        page,
        size: 10,
        status: status === 'ALL' ? undefined : status,
      }),
  })

  return (
    <>
      <PageHeader
        title="Rides"
        description="Every ride across all companies."
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

          {ridesQuery.data?.empty && <EmptyState title="No rides match this filter" />}

          {ridesQuery.data && !ridesQuery.data.empty && (
            <>
              <Table>
                <thead>
                  <tr>
                    <Th>Requested</Th>
                    <Th>Passenger</Th>
                    <Th>Company</Th>
                    <Th>Status</Th>
                    <Th className="text-right">Fare</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody>
                  {ridesQuery.data.content.map((ride) => (
                    <tr key={ride.rideId}>
                      <Td className="whitespace-nowrap text-xs">
                        {formatDateTime(ride.requestedAt)}
                      </Td>
                      <Td className="font-medium">{ride.customerName}</Td>
                      <Td>
                        {ride.companyName}
                        {ride.driverName && (
                          <span className="block text-xs text-ink-500">{ride.driverName}</span>
                        )}
                      </Td>
                      <Td>
                        <RideStatusBadge status={ride.status} />
                      </Td>
                      <Td className="text-right">{formatCurrency(ride.finalAmount)}</Td>
                      <Td>
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => setSelectedId(ride.rideId)}
                        >
                          View
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
          {selectedId ? (
            <RideDetail rideId={selectedId} onClose={() => setSelectedId(null)} />
          ) : (
            <Card>
              <EmptyState title="No ride selected" description="Pick a ride to see its detail." />
            </Card>
          )}
        </div>
      </div>
    </>
  )
}

function RideDetail({ rideId, onClose }: { rideId: number; onClose: () => void }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'ride', rideId],
    queryFn: () => adminApi.ride(rideId),
  })

  if (isLoading) {
    return (
      <Card>
        <CardBody>
          <Spinner />
        </CardBody>
      </Card>
    )
  }

  if (error) {
    return (
      <Card>
        <CardBody>
          <ErrorMessage error={error} />
        </CardBody>
      </Card>
    )
  }

  const ride = data!

  return (
    <Card>
      <CardHeader
        title={`Ride #${ride.rideId}`}
        description={ride.companyName}
        action={
          <Button size="sm" variant="ghost" onClick={onClose}>
            Close
          </Button>
        }
      />
      <CardBody className="space-y-3">
        <MapView
          className="h-40 w-full rounded-lg"
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

        <dl className="space-y-1.5 text-sm">
          <Row label="Status" value={humanise(ride.status)} />
          <Row label="Passenger" value={`${ride.customerName} · ${ride.customerPhone}`} />
          <Row label="Driver" value={ride.driverName ?? 'Not assigned'} />
          <Row label="Vehicle" value={ride.vehiclePlateNumber ?? '—'} />
          <Row label="Fare" value={formatCurrency(ride.finalAmount)} />
        </dl>

        <div className="border-t border-ink-200 pt-3">
          <p className="mb-1.5 text-xs font-medium text-ink-700">Timeline</p>
          <dl className="space-y-1 text-xs">
            <Row label="Requested" value={formatDateTime(ride.requestedAt)} />
            <Row label="Accepted" value={formatDateTime(ride.acceptedAt)} />
            <Row label="Declined" value={formatDateTime(ride.declinedAt)} />
            <Row label="Cancelled" value={formatDateTime(ride.cancelledAt)} />
            <Row label="Arriving" value={formatDateTime(ride.driverArrivingAt)} />
            <Row label="Arrived" value={formatDateTime(ride.driverArrivedAt)} />
            <Row label="Started" value={formatDateTime(ride.startedAt)} />
            <Row label="Completed" value={formatDateTime(ride.completedAt)} />
          </dl>
        </div>
      </CardBody>
    </Card>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-500">{label}</dt>
      <dd className="text-right font-medium text-ink-900">{value}</dd>
    </div>
  )
}
