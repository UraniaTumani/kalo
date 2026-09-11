import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { VehicleType } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import {
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
import { humanise } from '@/lib/utils'

const VEHICLE_TYPES: VehicleType[] = ['STANDARD', 'PREMIUM', 'ELECTRIC', 'VAN']

const currentYear = new Date().getFullYear()

const vehicleSchema = z.object({
  plateNumber: z.string().trim().min(1, 'Required').max(30),
  brand: z.string().trim().min(1, 'Required').max(100),
  model: z.string().trim().min(1, 'Required').max(100),
  // Registered with valueAsNumber, so these arrive already numeric.
  manufactureYear: z
    .number({ error: 'Required' })
    .int()
    .min(1900, 'Not a valid year')
    .max(currentYear + 1, 'Not a valid year'),
  seats: z.number({ error: 'Required' }).int().min(2, 'At least 2').max(20, 'At most 20'),
  vehicleType: z.enum(['STANDARD', 'PREMIUM', 'ELECTRIC', 'VAN']),
  registrationExpiryDate: z.string().optional(),
  insuranceExpiryDate: z.string().optional(),
  technicalInspectionExpiryDate: z.string().optional(),
})

type VehicleValues = z.infer<typeof vehicleSchema>

export function PartnerVehiclesPage() {
  const queryClient = useQueryClient()

  const vehiclesQuery = useQuery({
    queryKey: ['partner', 'vehicles'],
    queryFn: () => partnerApi.vehicles(),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['partner'] })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<VehicleValues>({
    resolver: zodResolver(vehicleSchema),
    defaultValues: { vehicleType: 'STANDARD', seats: 4, manufactureYear: currentYear },
  })

  const createMutation = useMutation({
    mutationFn: (values: VehicleValues) =>
      partnerApi.createVehicle({
        ...values,
        registrationExpiryDate: values.registrationExpiryDate || null,
        insuranceExpiryDate: values.insuranceExpiryDate || null,
        technicalInspectionExpiryDate: values.technicalInspectionExpiryDate || null,
      }),
    onSuccess: () => {
      invalidate()
      reset()
    },
  })

  const deactivateMutation = useMutation({
    mutationFn: (vehicleId: number) => partnerApi.deactivateVehicle(vehicleId),
    onSuccess: invalidate,
  })

  return (
    <>
      <PageHeader title="Vehicles" description="The cars your drivers can be assigned to." />

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-4">
          {deactivateMutation.error && <ErrorMessage error={deactivateMutation.error} />}

          <Card>
            {vehiclesQuery.isLoading && (
              <div className="p-5">
                <Spinner />
              </div>
            )}

            {vehiclesQuery.data?.length === 0 && (
              <EmptyState title="No vehicles yet" description="Add a vehicle to get started." />
            )}

            {vehiclesQuery.data && vehiclesQuery.data.length > 0 && (
              <Table>
                <thead>
                  <tr>
                    <Th>Plate</Th>
                    <Th>Vehicle</Th>
                    <Th>Type</Th>
                    <Th>Seats</Th>
                    <Th>Status</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody>
                  {vehiclesQuery.data.map((vehicle) => (
                    <tr key={vehicle.id}>
                      <Td className="font-medium">{vehicle.plateNumber}</Td>
                      <Td>
                        {vehicle.brand} {vehicle.model}
                        <span className="block text-xs text-ink-500">
                          {vehicle.manufactureYear}
                        </span>
                      </Td>
                      <Td>{humanise(vehicle.vehicleType)}</Td>
                      <Td>{vehicle.seats}</Td>
                      <Td>
                        <StatusBadge status={vehicle.status} />
                      </Td>
                      <Td>
                        {vehicle.status === 'ACTIVE' && (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-red-600 hover:bg-red-50"
                            onClick={() => deactivateMutation.mutate(vehicle.id)}
                          >
                            Deactivate
                          </Button>
                        )}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>
        </div>

        <Card>
          <CardHeader title="Add a vehicle" />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              noValidate
            >
              {createMutation.error && <ErrorMessage error={createMutation.error} />}

              <Field label="Plate number" error={errors.plateNumber?.message} required>
                <Input {...register('plateNumber')} placeholder="AA123TR" />
              </Field>
              <Field label="Brand" error={errors.brand?.message} required>
                <Input {...register('brand')} placeholder="Skoda" />
              </Field>
              <Field label="Model" error={errors.model?.message} required>
                <Input {...register('model')} placeholder="Octavia" />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Year" error={errors.manufactureYear?.message} required>
                  <Input {...register('manufactureYear', { valueAsNumber: true })} type="number" />
                </Field>
                <Field label="Seats" error={errors.seats?.message} required>
                  <Input {...register('seats', { valueAsNumber: true })} type="number" />
                </Field>
              </div>
              <Field label="Type" error={errors.vehicleType?.message} required>
                <Select {...register('vehicleType')}>
                  {VEHICLE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {humanise(type)}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label="Registration expiry" error={errors.registrationExpiryDate?.message}>
                <Input {...register('registrationExpiryDate')} type="date" />
              </Field>
              <Field label="Insurance expiry" error={errors.insuranceExpiryDate?.message}>
                <Input {...register('insuranceExpiryDate')} type="date" />
              </Field>
              <Field
                label="Technical inspection expiry"
                error={errors.technicalInspectionExpiryDate?.message}
              >
                <Input {...register('technicalInspectionExpiryDate')} type="date" />
              </Field>

              <Button type="submit" className="w-full" loading={createMutation.isPending}>
                Add vehicle
              </Button>
            </form>
          </CardBody>
        </Card>
      </div>
    </>
  )
}
