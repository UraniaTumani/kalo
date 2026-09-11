import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import type { DayOfWeek, OperatingHoursResponse } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader, Field, Input } from '@/components/ui'
import { humanise } from '@/lib/utils'

const DAYS: DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

const defaultHours = (): OperatingHoursResponse[] =>
  DAYS.map((dayOfWeek) => ({
    dayOfWeek,
    openTime: '08:00',
    closeTime: '22:00',
    closed: false,
  }))

export function PartnerAvailabilityPage() {
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

  /* ------------------------------------------------------- service area */

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

  /* ----------------------------------------------------- operating hours */

  const [hours, setHours] = useState<OperatingHoursResponse[]>(defaultHours)

  useEffect(() => {
    if (!hoursQuery.data || hoursQuery.data.length === 0) return

    // Backfill any day the company has not configured yet.
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

  function updateDay(day: DayOfWeek, patch: Partial<OperatingHoursResponse>) {
    setHours((previous) =>
      previous.map((entry) => (entry.dayOfWeek === day ? { ...entry, ...patch } : entry)),
    )
  }

  if (serviceAreaQuery.isLoading || hoursQuery.isLoading) {
    return <Spinner label="Loading availability" />
  }

  const notApproved =
    serviceAreaQuery.error !== null && serviceAreaQuery.data === undefined

  return (
    <>
      <PageHeader
        title="Availability"
        description="Where you operate and when you are open. Both are checked at search time."
      />

      {notApproved && (
        <div className="mb-4">
          <Alert tone="warning">
            These settings become available once your company is approved.
          </Alert>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader
            title="Service area"
            description="Click the map to set the centre. Pickups outside the radius are not offered."
          />
          <CardBody className="space-y-3">
            {areaMutation.error && <ErrorMessage error={areaMutation.error} />}
            {areaMutation.isSuccess && <Alert tone="success">Service area saved.</Alert>}

            <MapView
              center={center ?? TIRANA}
              onPick={setCenter}
              className="h-56 w-full rounded-lg"
              markers={center ? [{ position: center, label: 'Service centre' }] : []}
            />

            <div className="grid grid-cols-2 gap-3">
              <Field label="Radius (km)" required hint="Between 1 and 100">
                <Input
                  type="number"
                  min="1"
                  max="100"
                  step="1"
                  value={radiusKm}
                  onChange={(event) => setRadiusKm(event.target.value)}
                />
              </Field>
              <Field label="Timezone" required>
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
              Save service area
            </Button>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Operating hours"
            description="A day with no configuration counts as closed."
          />
          <CardBody className="space-y-3">
            {hoursMutation.error && <ErrorMessage error={hoursMutation.error} />}
            {hoursMutation.isSuccess && <Alert tone="success">Operating hours saved.</Alert>}

            <div className="space-y-2">
              {hours.map((entry) => (
                <div
                  key={entry.dayOfWeek}
                  className="flex flex-wrap items-center gap-2 rounded-lg border border-ink-200 px-3 py-2"
                >
                  <span className="w-24 text-sm font-medium text-ink-800">
                    {humanise(entry.dayOfWeek)}
                  </span>

                  <label className="flex items-center gap-1.5 text-xs text-ink-600">
                    <input
                      type="checkbox"
                      className="size-3.5 accent-brand-600"
                      checked={entry.closed}
                      onChange={(event) =>
                        updateDay(entry.dayOfWeek, { closed: event.target.checked })
                      }
                    />
                    Closed
                  </label>

                  <Input
                    type="time"
                    className="w-28"
                    disabled={entry.closed}
                    value={entry.openTime ?? ''}
                    onChange={(event) =>
                      updateDay(entry.dayOfWeek, { openTime: event.target.value })
                    }
                  />
                  <span className="text-xs text-ink-400">to</span>
                  <Input
                    type="time"
                    className="w-28"
                    disabled={entry.closed}
                    value={entry.closeTime ?? ''}
                    onChange={(event) =>
                      updateDay(entry.dayOfWeek, { closeTime: event.target.value })
                    }
                  />
                </div>
              ))}
            </div>

            <p className="text-xs text-ink-500">
              Equal open and close times mean open all day. A close time earlier than the open
              time means overnight, for example 20:00 to 04:00.
            </p>

            <Button loading={hoursMutation.isPending} onClick={() => hoursMutation.mutate()}>
              Save operating hours
            </Button>
          </CardBody>
        </Card>
      </div>
    </>
  )
}
