import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { rideApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Card, EmptyState, Table, Td, Th } from '@/components/ui'
import { formatCurrency, formatDateTime } from '@/lib/utils'

export function RideHistoryPage() {
  const [page, setPage] = useState(0)

  const { data, isLoading, error } = useQuery({
    queryKey: ['ride', 'history', page],
    queryFn: () => rideApi.history({ page, size: 10 }),
  })

  return (
    <>
      <PageHeader title="Ride history" description="Every ride you have requested." />

      {error && <ErrorMessage error={error} />}

      <Card>
        {isLoading && (
          <div className="p-5">
            <Spinner />
          </div>
        )}

        {data && data.empty && (
          <EmptyState title="No rides yet" description="Your completed rides will appear here." />
        )}

        {data && !data.empty && (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Requested</Th>
                  <Th>Company</Th>
                  <Th>Route</Th>
                  <Th>Status</Th>
                  <Th className="text-right">Fare</Th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((ride) => (
                  <tr key={ride.rideId}>
                    <Td>{formatDateTime(ride.requestedAt)}</Td>
                    <Td className="font-medium">{ride.companyName}</Td>
                    <Td className="text-xs text-ink-500">
                      {ride.pickupAddress ?? 'Map point'} → {ride.destinationAddress ?? 'Map point'}
                    </Td>
                    <Td>
                      <RideStatusBadge status={ride.status} />
                    </Td>
                    <Td className="text-right">{formatCurrency(ride.finalAmount)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={data} onPageChange={setPage} />
          </>
        )}
      </Card>
    </>
  )
}
