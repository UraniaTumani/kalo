import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { DriverResponse } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
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
  firstName: z.string().trim().min(1, 'Required').max(100),
  lastName: z.string().trim().min(1, 'Required').max(100),
  phone: z
    .string()
    .trim()
    .regex(/^\+?[0-9]{6,19}$/, "Digits only, optionally starting with '+'"),
  licenseNumber: z.string().trim().min(1, 'Required').max(100),
  licenseExpiryDate: z.string().min(1, 'Required'),
  dateOfBirth: z.string().optional(),
})

type DriverValues = z.infer<typeof driverSchema>

export function PartnerDriversPage() {
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

  const availabilityMutation = useMutation({
    mutationFn: ({ id, online }: { id: number; online: boolean }) =>
      partnerApi.setAvailability(id, online ? 'ONLINE' : 'OFFLINE'),
    onSuccess: invalidate,
  })

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
      <PageHeader title="Drivers" description="Your drivers and whether they are available." />

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-4">
          {(availabilityMutation.error || deactivateMutation.error) && (
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
                title="No drivers yet"
                description="Add a driver, give them a vehicle, then put them online."
              />
            )}

            {driversQuery.data && driversQuery.data.length > 0 && (
              <Table>
                <thead>
                  <tr>
                    <Th>Driver</Th>
                    <Th>Licence</Th>
                    <Th>Status</Th>
                    <Th>Availability</Th>
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
                          expires {driver.licenseExpiryDate}
                        </span>
                      </Td>
                      <Td>
                        <StatusBadge status={driver.status} />
                      </Td>
                      <Td>
                        <StatusBadge status={driver.availabilityStatus} />
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
                              {driver.availabilityStatus === 'ONLINE' ? 'Go offline' : 'Go online'}
                            </Button>
                          )}
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setLocationFor(driver)}
                          >
                            Location
                          </Button>
                          {driver.status === 'ACTIVE' && (
                            <Button
                              size="sm"
                              variant="ghost"
                              className="text-red-600 hover:bg-red-50"
                              onClick={() => deactivateMutation.mutate(driver.id)}
                            >
                              Deactivate
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
          <CardHeader title="Add a driver" />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              noValidate
            >
              {createMutation.error && <ErrorMessage error={createMutation.error} />}

              <Field label="First name" error={errors.firstName?.message} required>
                <Input {...register('firstName')} />
              </Field>
              <Field label="Last name" error={errors.lastName?.message} required>
                <Input {...register('lastName')} />
              </Field>
              <Field label="Phone" error={errors.phone?.message} required>
                <Input {...register('phone')} type="tel" placeholder="+355691234567" />
              </Field>
              <Field label="Licence number" error={errors.licenseNumber?.message} required>
                <Input {...register('licenseNumber')} />
              </Field>
              <Field
                label="Licence expiry"
                error={errors.licenseExpiryDate?.message}
                required
                hint="Must be in the future"
              >
                <Input {...register('licenseExpiryDate')} type="date" />
              </Field>
              <Field label="Date of birth" error={errors.dateOfBirth?.message}>
                <Input {...register('dateOfBirth')} type="date" />
              </Field>

              <Button type="submit" className="w-full" loading={createMutation.isPending}>
                Add driver
              </Button>
            </form>
          </CardBody>
        </Card>
      </div>
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
        description="Click the map to set where the driver is now."
        action={
          <Button size="sm" variant="ghost" onClick={onClose}>
            Close
          </Button>
        }
      />
      <CardBody className="space-y-3">
        <Alert tone="info">
          Taxi search only counts positions updated in the last two minutes.
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
          Update position
        </Button>
      </CardBody>
    </Card>
  )
}
