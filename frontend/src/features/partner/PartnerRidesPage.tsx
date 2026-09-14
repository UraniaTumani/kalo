import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import type { PartnerRideResponse, RideStatus } from '@/lib/api/types'
import { isActiveRide } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { RideStatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { readPage } from '@/lib/api/page'
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
import { cn, formatQueueTime } from '@/lib/utils'

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
  const { t } = useTranslation()
  const label = useStatusLabel()
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
        title={t('partner.ridesTitle')}
        description={t('partner.ridesSubtitle')}
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
                {value === 'ALL' ? t('partner.allStatuses') : label('rideStatus', value)}
              </option>
            ))}
          </Select>
        }
      />

      {ridesQuery.error && <ErrorMessage error={ridesQuery.error} />}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <Card>
          {ridesQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {ridesQuery.data?.empty && (
            <EmptyState
              title={t('partner.noRides')}
              description={t('partner.noRidesHint')}
            />
          )}

          {ridesQuery.data && !ridesQuery.data.empty && (
            <>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('ride.requested')}</Th>
                    <Th>{t('partner.passenger')}</Th>
                    <Th>{t('ride.route')}</Th>
                    <Th>{t('ride.status')}</Th>
                    <Th className="text-right" />
                  </tr>
                </thead>
                <tbody>
                  {ridesQuery.data.content.map((ride) => {
                    const waiting = ride.status === 'REQUESTED'
                    const live = isActiveRide(ride.status)

                    return (
                    <tr
                      key={ride.rideId}
                      className={cn(
                        waiting && 'bg-warn-50/40',
                        ride.rideId === selected?.rideId && 'bg-brand-50/60',
                      )}
                    >
                      <Td
                        className={cn(
                          'tnum whitespace-nowrap border-l-[3px] text-xs',
                          waiting ? 'border-l-warn-500' : 'border-l-transparent',
                        )}
                      >
                        {formatQueueTime(ride.requestedAt)}
                      </Td>
                      <Td>
                        <span className="font-medium">
                          {ride.customerFirstName} {ride.customerLastName}
                        </span>
                        <span className="block text-xs text-ink-500">{ride.customerPhone}</span>
                      </Td>
                      <Td className="max-w-[10rem] text-xs text-ink-500">
                        <span className="block truncate">
                          {ride.pickupAddress ?? t('ride.mapPoint')}
                        </span>
                        <span className="block truncate text-ink-400">
                          → {ride.destinationAddress ?? t('ride.mapPoint')}
                        </span>
                      </Td>
                      <Td>
                        <RideStatusBadge status={ride.status} />
                      </Td>
                      <Td className="text-right">
                        <Button
                          size="sm"
                          variant={waiting ? 'primary' : live ? 'secondary' : 'ghost'}
                          onClick={() => setSelected(ride)}
                        >
                          {t('partner.manage')}
                        </Button>
                      </Td>
                    </tr>
                    )
                  })}
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
                title={t('partner.noRideSelected')}
                description={t('partner.noRideSelectedHint')}
              />
            </Card>
          )}
        </div>
      </div>
    </>
  )
}

function RideActions({ ride, onDone }: { ride: PartnerRideResponse; onDone: () => void }) {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const queryClient = useQueryClient()
  const [driverId, setDriverId] = useState('')
  const [finalAmount, setFinalAmount] = useState('')

  /*
   * Only ACTIVE, ONLINE drivers can be assigned; the backend rejects anything
   * else. Asked for by filter rather than fetched whole and narrowed here — on
   * a paged endpoint the latter would hide an available driver behind a page
   * boundary and make them unassignable.
   */
  const driversQuery = useQuery({
    queryKey: ['partner', 'drivers', 'assignable-online'],
    queryFn: () =>
      partnerApi.drivers({ status: 'ACTIVE', availabilityStatus: 'ONLINE', size: 100 }),
    enabled: ride.status === 'REQUESTED',
  })

  const assignableDrivers = readPage(driversQuery.data).rows

  function runAction<T>(action: () => Promise<T>) {
    return action().then((result) => {
      queryClient.invalidateQueries({ queryKey: ['partner'] })
      onDone()
      return result
    })
  }

  const [confirmingDecline, setConfirmingDecline] = useState(false)

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
    <>
      <Card>
        <CardHeader
          title={`Ride #${ride.rideId}`}
          description={`${ride.customerFirstName} ${ride.customerLastName} · ${ride.customerPhone}`}
          action={<RideStatusBadge status={ride.status} />}
        />
        <CardBody className="flex flex-col gap-4">
          <MapView
            className="order-last h-44 w-full rounded-xl"
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
                <Alert tone="warning" title={t('partner.noDriverAvailable')}>
                  {t('partner.noDriverAvailableHint')}

                </Alert>
              ) : (
                <Field label={t('partner.assignDriver')} required>
                  <Select value={driverId} onChange={(event) => setDriverId(event.target.value)}>
                    <option value="">{t('partner.selectDriver')}</option>
                    {assignableDrivers.map((driver) => (
                      <option key={driver.id} value={driver.id}>
                        {driver.firstName} {driver.lastName} · {driver.phone}
                      </option>
                    ))}
                  </Select>
                </Field>
              )}

              <Button
                size="lg"
                className="w-full"
                disabled={!driverId}
                loading={accept.isPending}
                onClick={() => accept.mutate()}
              >
                {t('partner.accept')}
              </Button>

              <Button
                variant="ghost"
                size="sm"
                className="w-full text-bad-600 hover:bg-bad-50 hover:text-bad-700"
                loading={decline.isPending}
                onClick={() => setConfirmingDecline(true)}
              >
                {t('partner.decline')}
              </Button>
            </div>
          )}

          {ride.status === 'DRIVER_ASSIGNED' && (
            <Button className="w-full" loading={arriving.isPending} onClick={() => arriving.mutate()}>
              {t('partner.driverOnWay')}
            </Button>
          )}

          {ride.status === 'DRIVER_ARRIVING' && (
            <Button className="w-full" loading={arrived.isPending} onClick={() => arrived.mutate()}>
              {t('partner.driverArrived')}
            </Button>
          )}

          {ride.status === 'DRIVER_ARRIVED' && (
            <Button className="w-full" loading={start.isPending} onClick={() => start.mutate()}>
              {t('partner.startRide')}
            </Button>
          )}

          {ride.status === 'IN_PROGRESS' && (
            <div className="space-y-3">
              <Field
                label={t('partner.taximeterTotal')}
                required
                hint={t('partner.taximeterHint')}
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
                {t('partner.completeRide')}
              </Button>
            </div>
          )}

          {!isActiveRide(ride.status) && (
            <Alert tone="neutral">
              {t('partner.rideFinished', { status: label('rideStatus', ride.status) })}
            </Alert>
          )}
        </CardBody>
      </Card>

      <ConfirmDialog
        open={confirmingDecline}
        title={t('confirm.declineRide.title')}
        description={t('confirm.declineRide.body')}
        confirmLabel={t('confirm.declineRide.action')}
        cancelLabel={t('confirm.keep')}
        loading={decline.isPending}
        onCancel={() => setConfirmingDecline(false)}
        onConfirm={() => decline.mutate()}
      />
    </>
  )
}
