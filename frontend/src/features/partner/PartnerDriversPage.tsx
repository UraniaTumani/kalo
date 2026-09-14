import { useTranslation } from 'react-i18next'
import { useEffect, useState } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { DriverResponse } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Pagination } from '@/components/Pagination'
import { readPage } from '@/lib/api/page'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
import { getCurrentPosition, GeolocationError } from '@/lib/geolocation'
import i18n from '@/i18n'
import { UserX } from 'lucide-react'
import { useDriverTracking } from './useDriverTracking'
import { useIsCompact } from '@/lib/useIsCompact'
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
  RowActions,
  Table,
  Td,
  Th,
} from '@/components/ui'

const driverSchema = z.object({
  firstName: z.string().trim().min(1, 'validation.required').max(100),
  lastName: z.string().trim().min(1, 'validation.required').max(100),
  phone: z
    .string()
    .trim()
    .regex(/^\+?[0-9]{6,19}$/, 'validation.phoneInvalid'),
  licenseNumber: z.string().trim().min(1, 'validation.required').max(100),
  licenseExpiryDate: z.string().min(1, 'validation.required'),
  dateOfBirth: z.string().optional(),
})

type DriverValues = z.infer<typeof driverSchema>

export function PartnerDriversPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const compact = useIsCompact()
  const [locationFor, setLocationFor] = useState<DriverResponse | null>(null)

  const [page, setPage] = useState(0)

  const driversQuery = useQuery({
    queryKey: ['partner', 'drivers', page],
    queryFn: () => partnerApi.drivers({ page, size: 20 }),
  })

  const { rows: drivers, page: pageData, isEmpty } = readPage(driversQuery.data)

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['partner'] })

  const createMutation = useMutation({
    mutationFn: (values: DriverValues) =>
      partnerApi.createDriver({
        ...values,
        dateOfBirth: values.dateOfBirth || null,
      }),
    onSuccess: () => {
      invalidate()
      reset()
    },
  })

  const tracking = useDriverTracking()
  const [locationError, setLocationError] = useState<string | null>(null)

  /*
   * Going online needs a real position first. The backend accepts a driver as
   * available only while their last fix is fresh, so putting a driver online
   * without one would advertise them to passengers and then drop them from
   * search moments later. Refusing up front, with the reason, is clearer than
   * silently going online and disappearing.
   */
  const availabilityMutation = useMutation({
    mutationFn: async ({ id, online }: { id: number; online: boolean }) => {
      if (online) {
        const position = await getCurrentPosition()

        await partnerApi.updateDriverLocation(id, {
          latitude: position.lat,
          longitude: position.lng,
        })
      }

      const driver = await partnerApi.setAvailability(id, online ? 'ONLINE' : 'OFFLINE')

      if (online) {
        tracking.startTracking(id)
      } else {
        tracking.stopTracking(id)
      }

      return driver
    },
    onMutate: () => setLocationError(null),
    onSuccess: invalidate,
    onError: (error) => {
      if (error instanceof GeolocationError) {
        setLocationError(error.message)
      }
    },
  })

  /*
   * A driver taken offline elsewhere (a completed ride, another device) must
   * not keep this device uploading positions for them.
   *
   * Asked per tracked driver rather than read off the list: once the list is
   * paged, a tracked driver can sit on a page nobody is looking at, and this
   * device would go on reporting a position for someone who is off duty.
   * There are only ever a handful of tracked ids — this device is holding
   * their phones.
   */
  const trackedDriverQueries = useQueries({
    queries: tracking.trackedDriverIds.map((driverId) => ({
      queryKey: ['partner', 'driver', driverId],
      queryFn: () => partnerApi.driver(driverId),
      refetchInterval: 30_000,
    })),
  })

  useEffect(() => {
    for (const query of trackedDriverQueries) {
      const driver = query.data
      if (driver && driver.availabilityStatus === 'OFFLINE') {
        tracking.stopTracking(driver.id)
      }
    }
  }, [trackedDriverQueries, tracking])

  /* Held while the partner confirms; null means no dialog is open. */
  const [pendingDeactivate, setPendingDeactivate] = useState<DriverResponse | null>(null)

  const deactivateMutation = useMutation({
    mutationFn: (id: number) => partnerApi.deactivateDriver(id),
    onSuccess: invalidate,
  })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DriverValues>({ resolver: zodResolver(driverSchema) })

  return (
    <>
      <PageHeader
        title={t('partner.driversTitle')}
        description={t('partner.driversSubtitle')}
      />

      <div className="grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="min-w-0 space-y-4">
          {locationError && (
            <Alert tone="danger" title={t('partner.locationRequired')}>
              {locationError}
            </Alert>
          )}

          {tracking.isTracking && (
            <Alert tone="success" title={t('partner.sharingLocation')}>
              <p>
                {tracking.trackedDriverIds.length === 1
                  ? t('partner.oneDriverOnline')
                  : t('partner.manyDriversOnline', { count: tracking.trackedDriverIds.length })}{' '}
                {t('partner.positionInterval')}
                {tracking.lastSentAt &&
                  ' ' + t('partner.lastSentAt', { time: new Date(tracking.lastSentAt).toLocaleTimeString(i18n.language) })}
              </p>
              {tracking.error && <p className="mt-1 text-red-700">{tracking.error}</p>}
            </Alert>
          )}

          {(availabilityMutation.error || deactivateMutation.error) &&
            !(availabilityMutation.error instanceof GeolocationError) && (
              <ErrorMessage error={availabilityMutation.error ?? deactivateMutation.error} />
            )}

          <Card>
            {driversQuery.isLoading && (
              <div className="p-5">
                <Spinner />
              </div>
            )}

            {driversQuery.error && (
              <CardBody>
                <ErrorMessage error={driversQuery.error} />
              </CardBody>
            )}

            {isEmpty && (
              <EmptyState
                title={t('partner.noDrivers')}
                description={t('partner.noDriversHint')}
              />
            )}

            {/*
              Cards on a phone, the same pattern the vehicles and assignments
              lists already use.

              The table did not overflow the page — its container scrolls — but
              at 375px it showed 341 of 544 pixels, which put Availability and
              the go-online control off-screen. That is the column this page
              exists for, so a dispatcher had to scroll sideways to do the one
              thing they came to do.
            */}
            {drivers.length > 0 && compact && (
              <div className="space-y-2 p-4">
                {drivers.map((driver) => (
                  <div key={driver.id} className="rounded-xl border border-ink-200/70 p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate font-semibold text-ink-900">
                          {driver.firstName} {driver.lastName}
                        </p>
                        <p className="tnum truncate text-xs text-ink-500">{driver.phone}</p>
                      </div>
                      <div className="flex shrink-0 flex-col items-end gap-1">
                        <StatusBadge
                          status={driver.availabilityStatus}
                          namespace="driverAvailability"
                        />
                        {driver.status !== 'ACTIVE' && (
                          <StatusBadge status={driver.status} namespace="driverStatus" />
                        )}
                      </div>
                    </div>

                    <p className="tnum mt-2 text-xs text-ink-500">
                      {driver.licenseNumber} ·{' '}
                      {t('partner.expiresOn', { date: driver.licenseExpiryDate })}
                    </p>

                    <div className="mt-3 flex flex-wrap justify-end gap-2">
                      {driver.status === 'ACTIVE' && driver.availabilityStatus !== 'BUSY' && (
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() =>
                            availabilityMutation.mutate({
                              id: driver.id,
                              online: driver.availabilityStatus !== 'ONLINE',
                            })
                          }
                        >
                          {driver.availabilityStatus === 'ONLINE'
                            ? t('partner.goOffline')
                            : t('partner.goOnline')}
                        </Button>
                      )}
                      {driver.status === 'ACTIVE' && (
                        /* Spelled out here: no hover on a phone to reveal a title. */
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-bad-600 hover:bg-bad-50"
                          onClick={() => setPendingDeactivate(driver)}
                        >
                          <UserX className="size-4" aria-hidden />
                          {t('partner.deactivate')}
                        </Button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {drivers.length > 0 && !compact && (
              <Table>
                <thead>
                  <tr>
                    <Th>{t('rating.driver')}</Th>
                    <Th>{t('partner.licence')}</Th>
                    <Th>{t('nav.availability')}</Th>
                    <Th className="text-right" />
                  </tr>
                </thead>
                <tbody>
                  {drivers.map((driver) => (
                    <tr key={driver.id}>
                      <Td>
                        <span className="font-medium">
                          {driver.firstName} {driver.lastName}
                        </span>
                        <span className="block text-xs text-ink-500">{driver.phone}</span>
                      </Td>
                      <Td className="whitespace-nowrap text-xs">
                        <span className="tnum">{driver.licenseNumber}</span>
                        <span className="tnum block text-ink-500">
                          {t('partner.expiresOn', { date: driver.licenseExpiryDate })}
                        </span>
                      </Td>
                      <Td>
                        <StatusBadge
                          status={driver.availabilityStatus}
                          namespace="driverAvailability"
                        />
                        {/* Only worth the room when it is not the normal case. */}
                        {driver.status !== 'ACTIVE' && (
                          <span className="mt-1 block">
                            <StatusBadge status={driver.status} namespace="driverStatus" />
                          </span>
                        )}
                      </Td>
                      <Td>
                        <RowActions>
                          {driver.status === 'ACTIVE' && driver.availabilityStatus !== 'BUSY' && (
                            <Button
                              size="sm"
                              variant="secondary"
                              onClick={() =>
                                availabilityMutation.mutate({
                                  id: driver.id,
                                  online: driver.availabilityStatus !== 'ONLINE',
                                })
                              }
                            >
                              {driver.availabilityStatus === 'ONLINE' ? t('partner.goOffline') : t('partner.goOnline')}
                            </Button>
                          )}
                          {/*
                            Manual positioning is a debug aid only: production
                            positions come from the device GPS when a driver
                            goes online.
                          */}
                          {import.meta.env.DEV && (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => setLocationFor(driver)}
                            >
                              {t('partner.devPositionTool')}
                            </Button>
                          )}
                          {driver.status === 'ACTIVE' && (
                            <Button
                              size="sm"
                              variant="ghost"
                              className="text-ink-400 hover:bg-bad-50 hover:text-bad-600"
                              title={t('partner.deactivate')}
                              aria-label={t('partner.deactivate')}
                              onClick={() => setPendingDeactivate(driver)}
                            >
                              <UserX className="size-4" aria-hidden />
                            </Button>
                          )}
                        </RowActions>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}

            <Pagination page={pageData} onPageChange={setPage} />
          </Card>

          {locationFor && (
            <DriverLocationCard driver={locationFor} onClose={() => setLocationFor(null)} />
          )}
        </div>

        <Card>
          <CardHeader title={t('partner.addDriver')} />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              noValidate
            >
              {createMutation.error && <ErrorMessage error={createMutation.error} />}

              <Field label={t('auth.firstName')} error={errors.firstName ? t(errors.firstName.message!) : undefined} required>
                <Input {...register('firstName')} />
              </Field>
              <Field label={t('auth.lastName')} error={errors.lastName ? t(errors.lastName.message!) : undefined} required>
                <Input {...register('lastName')} />
              </Field>
              <Field label={t('auth.phone')} error={errors.phone ? t(errors.phone.message!) : undefined} required>
                <Input {...register('phone')} type="tel" placeholder={t('auth.phonePlaceholder')} />
              </Field>
              <Field label={t('partner.licenceNumber')} error={errors.licenseNumber ? t(errors.licenseNumber.message!) : undefined} required>
                <Input {...register('licenseNumber')} />
              </Field>
              <Field
                label={t('partner.licenceExpiry')}
                error={errors.licenseExpiryDate ? t(errors.licenseExpiryDate.message!) : undefined}
                required
                hint={t('validation.mustBeFuture')}
              >
                <Input {...register('licenseExpiryDate')} type="date" />
              </Field>
              <Field label={t('partner.dateOfBirth')} error={errors.dateOfBirth ? t(errors.dateOfBirth.message!) : undefined}>
                <Input {...register('dateOfBirth')} type="date" />
              </Field>

              <Button type="submit" className="w-full" loading={createMutation.isPending}>
                {t('partner.addDriver')}
              </Button>
            </form>
          </CardBody>
        </Card>
      </div>

      <ConfirmDialog
        open={pendingDeactivate !== null}
        title={t('confirm.deactivateDriver.title', {
          name: pendingDeactivate && `${pendingDeactivate.firstName} ${pendingDeactivate.lastName}`,
        })}
        description={t('confirm.deactivateDriver.body')}
        confirmLabel={t('confirm.deactivateDriver.action')}
        cancelLabel={t('confirm.keep')}
        loading={deactivateMutation.isPending}
        onCancel={() => setPendingDeactivate(null)}
        onConfirm={() =>
          pendingDeactivate &&
          deactivateMutation.mutate(pendingDeactivate.id, {
            onSuccess: () => setPendingDeactivate(null),
          })
        }
      />
    </>
  )
}

function DriverLocationCard({
  driver,
  onClose,
}: {
  driver: DriverResponse
  onClose: () => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [position, setPosition] = useState<LatLng | null>(null)

  const locationQuery = useQuery({
    queryKey: ['partner', 'driver-location', driver.id],
    queryFn: () => partnerApi.driverLocation(driver.id),
    retry: false,
  })

  const mutation = useMutation({
    mutationFn: () =>
      partnerApi.updateDriverLocation(driver.id, {
        latitude: position!.lat,
        longitude: position!.lng,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['partner', 'driver-location', driver.id] })
    },
  })

  const known = locationQuery.data
    ? { lat: locationQuery.data.latitude, lng: locationQuery.data.longitude }
    : null

  const shown = position ?? known

  return (
    <Card>
      <CardHeader
        title={`Position · ${driver.firstName} ${driver.lastName}`}
        description={t('partner.devToolNotice')}
        action={
          <Button size="sm" variant="ghost" onClick={onClose}>
            {t('common.close')}
          </Button>
        }
      />
      <CardBody className="space-y-3">
        <Alert tone="info">
          {t('partner.devToolNotice')}
        </Alert>

        {mutation.error && <ErrorMessage error={mutation.error} />}

        <MapView
          center={shown ?? TIRANA}
          onPick={setPosition}
          className="h-56 w-full rounded-lg"
          markers={shown ? [{ position: shown, label: 'Driver', tone: 'driver' }] : []}
        />

        <Button
          className="w-full"
          disabled={!position}
          loading={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {t('partner.devPositionTool')}
        </Button>
      </CardBody>
    </Card>
  )
}
