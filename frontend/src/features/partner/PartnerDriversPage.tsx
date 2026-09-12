import { useTranslation } from 'react-i18next'
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { DriverResponse } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
import { getCurrentPosition, GeolocationError } from '@/lib/geolocation'
import i18n from '@/i18n'
import { useDriverTracking } from './useDriverTracking'
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
  const [locationFor, setLocationFor] = useState<DriverResponse | null>(null)

  const driversQuery = useQuery({
    queryKey: ['partner', 'drivers'],
    queryFn: () => partnerApi.drivers(),
  })

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

  // A driver taken offline elsewhere (a completed ride, another device) must
  // not keep this device uploading positions for them.
  useEffect(() => {
    const drivers = driversQuery.data
    if (!drivers) return

    for (const driverId of tracking.trackedDriverIds) {
      const driver = drivers.find((candidate) => candidate.id === driverId)
      if (driver && driver.availabilityStatus === 'OFFLINE') {
        tracking.stopTracking(driverId)
      }
    }
  }, [driversQuery.data, tracking])

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

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-4">
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

            {driversQuery.data?.length === 0 && (
              <EmptyState
                title={t('partner.noDrivers')}
                description={t('partner.noDriversHint')}
              />
            )}

            {driversQuery.data && driversQuery.data.length > 0 && (
              <Table>
                <thead>
                  <tr>
                    <Th>{t('rating.driver')}</Th>
                    <Th>{t('partner.licence')}</Th>
                    <Th>{t('ride.status')}</Th>
                    <Th>{t('nav.availability')}</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody>
                  {driversQuery.data.map((driver) => (
                    <tr key={driver.id}>
                      <Td>
                        <span className="font-medium">
                          {driver.firstName} {driver.lastName}
                        </span>
                        <span className="block text-xs text-ink-500">{driver.phone}</span>
                      </Td>
                      <Td className="text-xs">
                        {driver.licenseNumber}
                        <span className="block text-ink-500">
                          {t('partner.expiresOn', { date: driver.licenseExpiryDate })}
                        </span>
                      </Td>
                      <Td>
                        <StatusBadge status={driver.status} namespace="driverStatus" />
                      </Td>
                      <Td>
                        <StatusBadge status={driver.availabilityStatus} namespace="driverAvailability" />
                      </Td>
                      <Td>
                        <div className="flex flex-wrap gap-1">
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
                              className="text-red-600 hover:bg-red-50"
                              onClick={() => setPendingDeactivate(driver)}
                            >
                              {t('partner.deactivate')}
                            </Button>
                          )}
                        </div>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
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
