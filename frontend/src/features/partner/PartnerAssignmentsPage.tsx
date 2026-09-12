import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import type { DriverVehicleAssignmentResponse } from '@/lib/api/types'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { Badge } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Spinner } from '@/components/ui/Spinner'
import {
  Alert,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Field,
  Select,
  Table,
  Td,
  Th,
} from '@/components/ui'
import { formatDateTime } from '@/lib/utils'

export function PartnerAssignmentsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [driverId, setDriverId] = useState('')
  const [vehicleId, setVehicleId] = useState('')

  const assignmentsQuery = useQuery({
    queryKey: ['partner', 'assignments'],
    queryFn: () => partnerApi.assignments(),
  })

  const driversQuery = useQuery({
    queryKey: ['partner', 'drivers'],
    queryFn: () => partnerApi.drivers(),
  })

  const vehiclesQuery = useQuery({
    queryKey: ['partner', 'vehicles'],
    queryFn: () => partnerApi.vehicles(),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['partner'] })

  const createMutation = useMutation({
    mutationFn: () =>
      partnerApi.createAssignment({
        driverId: Number(driverId),
        vehicleId: Number(vehicleId),
      }),
    onSuccess: () => {
      invalidate()
      setDriverId('')
      setVehicleId('')
    },
  })

  /* Held while the partner confirms; null means no dialog is open. */
  const [pendingRemove, setPendingRemove] = useState<DriverVehicleAssignmentResponse | null>(null)

  const removeMutation = useMutation({
    mutationFn: (assignmentId: number) => partnerApi.removeAssignment(assignmentId),
    onSuccess: invalidate,
  })

  // The backend requires the driver to be OFFLINE and ACTIVE, and the vehicle
  // ACTIVE and unassigned, so only offer combinations that can succeed.
  const assignedDriverIds = new Set(
    assignmentsQuery.data?.filter((a) => a.active).map((a) => a.driverId) ?? [],
  )
  const assignedVehicleIds = new Set(
    assignmentsQuery.data?.filter((a) => a.active).map((a) => a.vehicleId) ?? [],
  )

  const availableDrivers =
    driversQuery.data?.filter(
      (driver) =>
        driver.status === 'ACTIVE' &&
        driver.availabilityStatus === 'OFFLINE' &&
        !assignedDriverIds.has(driver.id),
    ) ?? []

  const availableVehicles =
    vehiclesQuery.data?.filter(
      (vehicle) => vehicle.status === 'ACTIVE' && !assignedVehicleIds.has(vehicle.id),
    ) ?? []

  return (
    <>
      <PageHeader
        title={t('partner.assignmentsTitle')}
        description={t('partner.assignmentsSubtitle')}
      />

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-4">
          {removeMutation.error && <ErrorMessage error={removeMutation.error} />}

          <Card>
            {assignmentsQuery.isLoading && (
              <div className="p-5">
                <Spinner />
              </div>
            )}

            {assignmentsQuery.data?.length === 0 && (
              <EmptyState
                title={t('partner.noAssignments')}
                description={t('partner.noAssignmentsHint')}
              />
            )}

            {assignmentsQuery.data && assignmentsQuery.data.length > 0 && (
              <Table>
                <thead>
                  <tr>
                    <Th>{t('rating.driver')}</Th>
                    <Th>{t('partner.vehicle')}</Th>
                    <Th>{t('ride.from')}</Th>
                    <Th>{t('ride.status')}</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody>
                  {assignmentsQuery.data.map((assignment) => (
                    <tr key={assignment.assignmentId}>
                      <Td className="font-medium">{assignment.driverName}</Td>
                      <Td>
                        {assignment.plateNumber}
                        <span className="block text-xs text-ink-500">
                          {assignment.vehicleDescription}
                        </span>
                      </Td>
                      <Td className="text-xs">{formatDateTime(assignment.assignedFrom)}</Td>
                      <Td>
                        {assignment.active ? (
                          <Badge tone="success">{t('companyStatus.ACTIVE')}</Badge>
                        ) : (
                          <Badge>{t('partner.ended')}</Badge>
                        )}
                      </Td>
                      <Td>
                        {assignment.active && (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-red-600 hover:bg-red-50"
                            onClick={() => setPendingRemove(assignment)}
                          >
                            {t('partner.unassign')}
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
          <CardHeader title={t('partner.newAssignment')} />
          <CardBody className="space-y-3">
            {createMutation.error && <ErrorMessage error={createMutation.error} />}

            {availableDrivers.length === 0 && (
              <Alert tone="warning">
                {t('partner.noEligibleDriver')}
              </Alert>
            )}

            <Field label={t('rating.driver')} required>
              <Select value={driverId} onChange={(event) => setDriverId(event.target.value)}>
                <option value="">{t('partner.selectDriver')}</option>
                {availableDrivers.map((driver) => (
                  <option key={driver.id} value={driver.id}>
                    {driver.firstName} {driver.lastName}
                  </option>
                ))}
              </Select>
            </Field>

            <Field label={t('partner.vehicle')} required>
              <Select value={vehicleId} onChange={(event) => setVehicleId(event.target.value)}>
                <option value="">{t('partner.selectVehicle')}</option>
                {availableVehicles.map((vehicle) => (
                  <option key={vehicle.id} value={vehicle.id}>
                    {vehicle.plateNumber} · {vehicle.brand} {vehicle.model}
                  </option>
                ))}
              </Select>
            </Field>

            <Button
              className="w-full"
              disabled={!driverId || !vehicleId}
              loading={createMutation.isPending}
              onClick={() => createMutation.mutate()}
            >
              {t('partner.assignVehicle')}
            </Button>
          </CardBody>
        </Card>
      </div>

      <ConfirmDialog
        open={pendingRemove !== null}
        title={t('confirm.removeAssignment.title', {
          driver: pendingRemove?.driverName,
          plate: pendingRemove?.plateNumber,
        })}
        description={t('confirm.removeAssignment.body')}
        confirmLabel={t('confirm.removeAssignment.action')}
        cancelLabel={t('confirm.keep')}
        loading={removeMutation.isPending}
        onCancel={() => setPendingRemove(null)}
        onConfirm={() =>
          pendingRemove &&
          removeMutation.mutate(pendingRemove.assignmentId, {
            onSuccess: () => setPendingRemove(null),
          })
        }
      />
    </>
  )
}
