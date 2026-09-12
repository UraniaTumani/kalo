import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { rideApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Button, Card, EmptyState, Table, Td, Th } from '@/components/ui'
import { RatingForm } from './RatingForm'
import { formatCurrency, formatDateTime } from '@/lib/utils'

export function RideHistoryPage() {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)

  /*
   * Rating used to be reachable only from the current-ride page, which finds a
   * finished ride through a remembered id in localStorage. That left a
   * passenger unable to rate at all after clearing storage, switching device or
   * simply coming back later, even though the backend accepts a rating for any
   * completed ride. History is the durable place for it.
   */
  const [ratingRideId, setRatingRideId] = useState<number | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['ride', 'history', page],
    queryFn: () => rideApi.history({ page, size: 10 }),
  })

  return (
    <>
      <PageHeader title={t('ride.historyTitle')} description={t('ride.historySubtitle')} />

      {error && <ErrorMessage error={error} />}

      <Card>
        {isLoading && (
          <div className="p-5">
            <Spinner />
          </div>
        )}

        {data && data.empty && (
          <EmptyState title={t('ride.noRides')} description={t('ride.noRidesHint')} />
        )}

        {data && !data.empty && (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>{t('ride.requested')}</Th>
                  <Th>{t('ride.company')}</Th>
                  <Th>{t('ride.route')}</Th>
                  <Th>{t('ride.status')}</Th>
                  <Th className="text-right">{t('ride.fare')}</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {data.content.map((ride) => (
                  <tr key={ride.rideId}>
                    <Td>{formatDateTime(ride.requestedAt)}</Td>
                    <Td className="font-medium">{ride.companyName}</Td>
                    <Td className="text-xs text-ink-500">
                      {ride.pickupAddress ?? t('ride.mapPoint')} → {ride.destinationAddress ?? t('ride.mapPoint')}
                    </Td>
                    <Td>
                      <RideStatusBadge status={ride.status} />
                    </Td>
                    <Td className="text-right">{formatCurrency(ride.finalAmount)}</Td>
                    <Td>
                      {ride.status === 'COMPLETED' && (
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() =>
                            setRatingRideId(ratingRideId === ride.rideId ? null : ride.rideId)
                          }
                        >
                          {ratingRideId === ride.rideId ? t('common.close') : t('rating.rate')}
                        </Button>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={data} onPageChange={setPage} />
          </>
        )}
      </Card>

      {ratingRideId !== null && (
        <div className="mt-4 max-w-md">
          <RatingForm rideId={ratingRideId} />
        </div>
      )}
    </>
  )
}
