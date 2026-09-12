import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import type { OperatingHoursResponse } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader, Field, Input } from '@/components/ui'
import { WeeklyScheduleEditor, DAYS } from './WeeklyScheduleEditor'

const defaultHours = (): OperatingHoursResponse[] =>
  DAYS.map((dayOfWeek) => ({
    dayOfWeek,
    openTime: '08:00',
    closeTime: '22:00',
    closed: false,
  }))

export function PartnerAvailabilityPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const serviceAreaQuery = useQuery({
    queryKey: ['partner', 'service-area'],
    queryFn: () => partnerApi.serviceArea(),
    retry: false,
  })

  const hoursQuery = useQuery({
    queryKey: ['partner', 'operating-hours'],
    queryFn: () => partnerApi.operatingHours(),
    retry: false,
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['partner'] })

  /* --------------------------------------------------------- service area */

  const [center, setCenter] = useState<LatLng | null>(null)
  const [radiusKm, setRadiusKm] = useState('15')
  const [timezone, setTimezone] = useState('Europe/Tirane')

  useEffect(() => {
    const area = serviceAreaQuery.data
    if (!area) return

    if (area.latitude !== null && area.longitude !== null) {
      setCenter({ lat: area.latitude, lng: area.longitude })
    }
    if (area.radiusKm !== null) setRadiusKm(String(area.radiusKm))
    setTimezone(area.timezone)
  }, [serviceAreaQuery.data])

  const areaMutation = useMutation({
    mutationFn: () =>
      partnerApi.updateServiceArea({
        latitude: center!.lat,
        longitude: center!.lng,
        radiusKm: Number(radiusKm),
        timezone,
      }),
    onSuccess: invalidate,
  })

  /* ------------------------------------------------------- operating hours */

  const [hours, setHours] = useState<OperatingHoursResponse[]>(defaultHours)

  useEffect(() => {
    if (!hoursQuery.data || hoursQuery.data.length === 0) return

    // Backfill any day the company has not configured yet, so the editor always
    // shows a full week rather than a partial one.
    setHours(
      DAYS.map(
        (day) =>
          hoursQuery.data.find((entry) => entry.dayOfWeek === day) ?? {
            dayOfWeek: day,
            openTime: '08:00',
            closeTime: '22:00',
            closed: true,
          },
      ),
    )
  }, [hoursQuery.data])

  const hoursMutation = useMutation({
    mutationFn: () => partnerApi.updateOperatingHours(hours),
    onSuccess: invalidate,
  })

  const hoursIncomplete = hours.some(
    (entry) => !entry.closed && (!entry.openTime || !entry.closeTime),
  )

  if (serviceAreaQuery.isLoading || hoursQuery.isLoading) {
    return <Spinner label={t('common.loading')} />
  }

  const notApproved = serviceAreaQuery.error !== null && serviceAreaQuery.data === undefined

  return (
    <>
      <PageHeader title={t('availability.title')} description={t('availability.subtitle')} />

      {notApproved && (
        <div className="mb-4">
          <Alert tone="warning">{t('availability.notApprovedYet')}</Alert>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader
            title={t('availability.serviceArea')}
            description={t('availability.setCentre')}
          />
          <CardBody className="space-y-3">
            {areaMutation.error && <ErrorMessage error={areaMutation.error} />}
            {areaMutation.isSuccess && (
              <Alert tone="success">{t('availability.serviceAreaSaved')}</Alert>
            )}

            <MapView
              center={center ?? TIRANA}
              onPick={setCenter}
              className="h-56 w-full rounded-lg"
              markers={center ? [{ position: center, label: t('availability.serviceArea') }] : []}
            />

            <p className="text-xs text-ink-500">{t('availability.serviceAreaHint')}</p>

            <div className="grid grid-cols-2 gap-3">
              <Field label={t('availability.radius')} required hint={t('availability.radiusHint')}>
                <Input
                  type="number"
                  min="1"
                  max="100"
                  step="1"
                  value={radiusKm}
                  onChange={(event) => setRadiusKm(event.target.value)}
                />
              </Field>
              <Field label={t('availability.timezone')} required>
                <Input
                  value={timezone}
                  onChange={(event) => setTimezone(event.target.value)}
                  placeholder="Europe/Tirane"
                />
              </Field>
            </div>

            <Button
              disabled={!center}
              loading={areaMutation.isPending}
              onClick={() => areaMutation.mutate()}
            >
              {t('availability.saveServiceArea')}
            </Button>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title={t('availability.operatingHours')}
            description={t('availability.operatingHoursHint')}
          />
          <CardBody className="space-y-3">
            {hoursMutation.error && <ErrorMessage error={hoursMutation.error} />}
            {hoursMutation.isSuccess && (
              <Alert tone="success">{t('availability.hoursSaved')}</Alert>
            )}

            <WeeklyScheduleEditor
              hours={hours}
              onChange={setHours}
              disabled={hoursMutation.isPending}
            />

            <Button
              disabled={hoursIncomplete}
              loading={hoursMutation.isPending}
              onClick={() => hoursMutation.mutate()}
            >
              {t('availability.saveHours')}
            </Button>
          </CardBody>
        </Card>
      </div>
    </>
  )
}
