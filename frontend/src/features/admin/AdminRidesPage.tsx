import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { RideStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge, useStatusLabel } from '@/components/StatusBadge'
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
  Field,
  FilterBar,
  RowActions,
  Select,
  Table,
  Td,
  Th,
} from '@/components/ui'
import { cn, formatCurrency, formatDateTime } from '@/lib/utils'
import { useIsCompact } from '@/lib/useIsCompact'

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
  const { t } = useTranslation()
  const label = useStatusLabel()
  const compact = useIsCompact()
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

  const { rows, page: pageData, isEmpty } = readPage(ridesQuery.data)

  return (
    <>
      <PageHeader title={t('admin.ridesTitle')} description={t('admin.ridesSubtitle')} />

      <FilterBar className="mb-4">
        <Field label={t('ride.status')}>
          <Select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as RideStatus | 'ALL')
              setPage(0)
            }}
            className="w-full sm:w-52"
          >
            {FILTERS.map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? t('partner.allStatuses') : label('rideStatus', value)}
              </option>
            ))}
          </Select>
        </Field>
      </FilterBar>

      {ridesQuery.error && <ErrorMessage error={ridesQuery.error} />}

      <div className="grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <Card>
          {ridesQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {ridesQuery.isError && !ridesQuery.isLoading && (
            <EmptyState
              title={t('errors.loadFailed')}
              description={t('admin.loadFailedHint')}
            />
          )}

          {!ridesQuery.isLoading && !ridesQuery.isError && isEmpty && (
            <EmptyState
              title={t('admin.noRidesMatch')}
              description={t('admin.noRidesMatchHint')}
            />
          )}

          {rows.length > 0 && (
            <>
              {compact ? (
                <div className="space-y-2 p-4">
                  {rows.map((ride) => (
                    <button
                      key={ride.rideId}
                      type="button"
                      onClick={() => setSelectedId(ride.rideId)}
                      className={cn(
                        'block w-full rounded-xl border p-3 text-left transition',
                        selectedId === ride.rideId
                          ? 'border-brand-400 bg-brand-50/40'
                          : 'border-ink-200/70 hover:bg-ink-50',
                      )}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="truncate font-semibold text-ink-900">{ride.customerName}</p>
                          <p className="truncate text-xs text-ink-500">
                            {ride.companyName}
                            {ride.driverName ? ` · ${ride.driverName}` : ''}
                          </p>
                        </div>
                        <RideStatusBadge status={ride.status} />
                      </div>

                      <div className="mt-2 flex items-center justify-between gap-3">
                        <span className="tnum text-xs text-ink-400">
                          {formatDateTime(ride.requestedAt)}
                        </span>
                        <span className="tnum text-sm font-semibold text-ink-900">
                          {formatCurrency(ride.finalAmount)}
                        </span>
                      </div>
                    </button>
                  ))}
                </div>
              ) : (
              <Table>
                <thead>
                  <tr>
                    <Th>{t('ride.requested')}</Th>
                    <Th>{t('partner.passenger')}</Th>
                    <Th>{t('ride.company')}</Th>
                    <Th>{t('ride.status')}</Th>
                    <Th className="text-right">{t('ride.fare')}</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((ride) => (
                    <tr
                      key={ride.rideId}
                      /* The detail panel is beside the table, so the row it belongs to says so. */
                      className={selectedId === ride.rideId ? 'bg-brand-50/50' : undefined}
                    >
                      <Td className="tnum whitespace-nowrap text-xs">
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
                      <Td className="tnum whitespace-nowrap text-right">
                        {formatCurrency(ride.finalAmount)}
                      </Td>
                      <Td>
                        <RowActions>
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => setSelectedId(ride.rideId)}
                          >
                            {t('admin.view')}
                          </Button>
                        </RowActions>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              )}
              <Pagination page={pageData} onPageChange={setPage} />
            </>
          )}
        </Card>

        {/*
          On a phone the placeholder card is just a wall between the list and
          the rest of the page, so it only appears where there is a column for
          it to sit in.
        */}
        {selectedId ? (
          <div>
            <RideDetail rideId={selectedId} onClose={() => setSelectedId(null)} />
          </div>
        ) : (
          !compact && (
            <div>
              <Card>
                <EmptyState
                  title={t('admin.noRideSelected')}
                  description={t('admin.noRideSelectedHint')}
                />
              </Card>
            </div>
          )
        )}
      </div>
    </>
  )
}

function RideDetail({ rideId, onClose }: { rideId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const label = useStatusLabel()
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
            {t('common.close')}
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
          <Row label={t('ride.status')} value={label('rideStatus', ride.status)} />
          <Row label={t('partner.passenger')} value={`${ride.customerName} · ${ride.customerPhone}`} />
          <Row label={t('rating.driver')} value={ride.driverName ?? t('common.none')} />
          <Row label={t('partner.vehicle')} value={ride.vehiclePlateNumber ?? '—'} />
          <Row label={t('ride.fare')} value={formatCurrency(ride.finalAmount)} />
        </dl>

        <div className="border-t border-ink-200 pt-3">
          <p className="mb-1.5 text-xs font-medium text-ink-700">{t('admin.timeline')}</p>
          <dl className="space-y-1 text-xs">
            <Row label={t('ride.requested')} value={formatDateTime(ride.requestedAt)} />
            <Row label={t('admin.accepted')} value={formatDateTime(ride.acceptedAt)} />
            <Row label={t('rideStatus.DECLINED')} value={formatDateTime(ride.declinedAt)} />
            <Row label={t('rideStatus.CANCELLED')} value={formatDateTime(ride.cancelledAt)} />
            <Row label={t('rideStatus.DRIVER_ARRIVING')} value={formatDateTime(ride.driverArrivingAt)} />
            <Row label={t('rideStatus.DRIVER_ARRIVED')} value={formatDateTime(ride.driverArrivedAt)} />
            <Row label={t('ride.steps.started')} value={formatDateTime(ride.startedAt)} />
            <Row label={t('rideStatus.COMPLETED')} value={formatDateTime(ride.completedAt)} />
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
