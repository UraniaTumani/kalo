import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import { useState } from 'react'
import type { VehicleResponse, VehicleType } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Pagination } from '@/components/Pagination'
import { readPage } from '@/lib/api/page'
import { Spinner } from '@/components/ui/Spinner'
import { CarFront } from 'lucide-react'
import { ExpiryPill, PaperworkSummary } from './ExpiryPill'
import { useIsCompact } from '@/lib/useIsCompact'
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Field,
  Input,
  RowActions,
  Select,
  Table,
  Td,
  Th,
} from '@/components/ui'

const VEHICLE_TYPES: VehicleType[] = ['STANDARD', 'PREMIUM', 'ELECTRIC', 'VAN']

const currentYear = new Date().getFullYear()

const vehicleSchema = z.object({
  plateNumber: z.string().trim().min(1, 'validation.required').max(30),
  brand: z.string().trim().min(1, 'validation.required').max(100),
  model: z.string().trim().min(1, 'validation.required').max(100),
  // Registered with valueAsNumber, so these arrive already numeric.
  manufactureYear: z
    .number({ error: 'validation.required' })
    .int()
    .min(1900, 'validation.yearInvalid')
    .max(currentYear + 1, 'validation.yearInvalid'),
  seats: z.number({ error: 'validation.required' }).int().min(2, 'validation.seatsRange').max(20, 'validation.seatsRange'),
  vehicleType: z.enum(['STANDARD', 'PREMIUM', 'ELECTRIC', 'VAN']),
  registrationExpiryDate: z.string().optional(),
  insuranceExpiryDate: z.string().optional(),
  technicalInspectionExpiryDate: z.string().optional(),
})

type VehicleValues = z.infer<typeof vehicleSchema>

export function PartnerVehiclesPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const compact = useIsCompact()
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)

  const vehiclesQuery = useQuery({
    queryKey: ['partner', 'vehicles', page],
    queryFn: () => partnerApi.vehicles({ page, size: 20 }),
  })

  const { rows: vehicles, page: pageData, isEmpty } = readPage(vehiclesQuery.data)

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

  /* Held while the partner confirms; null means no dialog is open. */
  const [pendingDeactivate, setPendingDeactivate] = useState<VehicleResponse | null>(null)

  const deactivateMutation = useMutation({
    mutationFn: (vehicleId: number) => partnerApi.deactivateVehicle(vehicleId),
    onSuccess: invalidate,
  })

  return (
    <>
      <PageHeader title={t('partner.vehiclesTitle')} description={t('partner.vehiclesSubtitle')} />

      <div className="grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-4">
          {deactivateMutation.error && <ErrorMessage error={deactivateMutation.error} />}

          <Card>
            {vehiclesQuery.isLoading && (
              <div className="p-5">
                <Spinner />
              </div>
            )}

            {isEmpty && (
              <EmptyState title={t('partner.noVehicles')} description={t('partner.noVehiclesHint')} />
            )}

            {vehicles.length > 0 && (
              <>
                {/*
                  Cards on a phone, a table from sm up. A vehicle carries a plate,
                  a description, a type, a seat count, a status and three expiry
                  dates — a row nobody can read at 375px, and scrolling it
                  sideways only ever shows half of it.
                */}
                {compact ? (
                <div className="space-y-2 p-4">
                  {vehicles.map((vehicle) => (
                    <div key={vehicle.id} className="rounded-xl border border-ink-200/70 p-3">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="tnum text-base font-bold tracking-tight text-ink-900">
                            {vehicle.plateNumber}
                          </p>
                          <p className="truncate text-xs text-ink-500">
                            {vehicle.brand} {vehicle.model} · {vehicle.manufactureYear}
                          </p>
                        </div>
                        <StatusBadge status={vehicle.status} namespace="vehicleStatus" />
                      </div>

                      <div className="mt-2 flex flex-wrap gap-1.5">
                        <Badge>{label('vehicleType', vehicle.vehicleType)}</Badge>
                        <Badge>
                          {vehicle.seats} · {t('partner.seats')}
                        </Badge>
                      </div>

                      <div className="mt-3 space-y-1.5 rounded-lg bg-ink-50 px-2.5 py-2">
                        <ExpiryPill
                          label={t('partner.registrationShort')}
                          value={vehicle.registrationExpiryDate}
                        />
                        <ExpiryPill
                          label={t('partner.insuranceShort')}
                          value={vehicle.insuranceExpiryDate}
                        />
                        <ExpiryPill
                          label={t('partner.inspectionShort')}
                          value={vehicle.technicalInspectionExpiryDate}
                        />
                      </div>

                      {vehicle.status === 'ACTIVE' && (
                        <div className="mt-3 flex justify-end">
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-bad-600 hover:bg-bad-50"
                            onClick={() => setPendingDeactivate(vehicle)}
                          >
                            {t('partner.deactivate')}
                          </Button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
                ) : (
                <div>
                  <Table>
                    <thead>
                      <tr>
                        <Th>{t('partner.plate')}</Th>
                        <Th>{t('partner.vehicle')}</Th>
                        <Th>{t('partner.type')}</Th>
                        <Th>{t('partner.paperwork')}</Th>
                        <Th>{t('ride.status')}</Th>
                        <Th className="text-right" />
                      </tr>
                    </thead>
                    <tbody>
                      {vehicles.map((vehicle) => (
                        <tr key={vehicle.id}>
                          {/* The plate is how a fleet refers to a car, so it leads. */}
                          <Td className="tnum whitespace-nowrap font-bold text-ink-900">
                            {vehicle.plateNumber}
                          </Td>
                          <Td>
                            <span className="block truncate">
                              {vehicle.brand} {vehicle.model}
                            </span>
                            <span className="tnum block text-xs text-ink-500">
                              {vehicle.manufactureYear} · {vehicle.seats} {t('partner.seats')}
                            </span>
                          </Td>
                          <Td>
                            <Badge>{label('vehicleType', vehicle.vehicleType)}</Badge>
                          </Td>
                          {/*
                            One answer per vehicle, not three dates to compare.
                            The card view spells them out; here the question is
                            only whether this car can legally be on the road.
                          */}
                          <Td>
                            <PaperworkSummary
                              values={[
                                vehicle.registrationExpiryDate,
                                vehicle.insuranceExpiryDate,
                                vehicle.technicalInspectionExpiryDate,
                              ]}
                            />
                          </Td>
                          <Td>
                            <StatusBadge status={vehicle.status} namespace="vehicleStatus" />
                          </Td>
                          <Td>
                            <RowActions>
                              {vehicle.status === 'ACTIVE' && (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  className="text-ink-400 hover:bg-bad-50 hover:text-bad-600"
                                  title={t('partner.deactivate')}
                                  aria-label={t('partner.deactivate')}
                                  onClick={() => setPendingDeactivate(vehicle)}
                                >
                                  <CarFront className="size-4" aria-hidden />
                                </Button>
                              )}
                            </RowActions>
                          </Td>
                        </tr>
                      ))}
                    </tbody>
                  </Table>
                </div>
                )}
              </>
            )}

            <Pagination page={pageData} onPageChange={setPage} />
          </Card>
        </div>

        <Card>
          <CardHeader title={t('partner.addVehicle')} />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              noValidate
            >
              {createMutation.error && <ErrorMessage error={createMutation.error} />}

              <Field label={t('partner.plate')} error={errors.plateNumber ? t(errors.plateNumber.message!) : undefined} required>
                <Input {...register('plateNumber')} placeholder="AA123TR" />
              </Field>
              <Field label={t('partner.brand')} error={errors.brand ? t(errors.brand.message!) : undefined} required>
                <Input {...register('brand')} placeholder="Skoda" />
              </Field>
              <Field label={t('partner.model')} error={errors.model ? t(errors.model.message!) : undefined} required>
                <Input {...register('model')} placeholder="Octavia" />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label={t('partner.year')} error={errors.manufactureYear ? t(errors.manufactureYear.message!) : undefined} required>
                  <Input {...register('manufactureYear', { valueAsNumber: true })} type="number" />
                </Field>
                <Field label={t('partner.seats')} error={errors.seats ? t(errors.seats.message!) : undefined} required>
                  <Input {...register('seats', { valueAsNumber: true })} type="number" />
                </Field>
              </div>
              <Field label={t('partner.type')} error={errors.vehicleType ? t(errors.vehicleType.message!) : undefined} required>
                <Select {...register('vehicleType')}>
                  {VEHICLE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {label('vehicleType', type)}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label={t('partner.registrationExpiry')} error={errors.registrationExpiryDate ? t(errors.registrationExpiryDate.message!) : undefined}>
                <Input {...register('registrationExpiryDate')} type="date" />
              </Field>
              <Field label={t('partner.insuranceExpiry')} error={errors.insuranceExpiryDate ? t(errors.insuranceExpiryDate.message!) : undefined}>
                <Input {...register('insuranceExpiryDate')} type="date" />
              </Field>
              <Field
                label={t('partner.inspectionExpiry')}
                error={errors.technicalInspectionExpiryDate ? t(errors.technicalInspectionExpiryDate.message!) : undefined}
              >
                <Input {...register('technicalInspectionExpiryDate')} type="date" />
              </Field>

              <Button type="submit" className="w-full" loading={createMutation.isPending}>
                {t('partner.addVehicle')}
              </Button>
            </form>
          </CardBody>
        </Card>
      </div>

      <ConfirmDialog
        open={pendingDeactivate !== null}
        title={t('confirm.deactivateVehicle.title', { plate: pendingDeactivate?.plateNumber })}
        description={t('confirm.deactivateVehicle.body')}
        confirmLabel={t('confirm.deactivateVehicle.action')}
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
