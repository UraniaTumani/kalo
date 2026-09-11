import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { Badge } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
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
        title="Driver – vehicle assignments"
        description="A driver needs an active vehicle before they can go online."
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
                title="No assignments"
                description="Pair a driver with a vehicle to put them on the road."
              />
            )}

            {assignmentsQuery.data && assignmentsQuery.data.length > 0 && (
              <Table>
                <thead>
                  <tr>
                    <Th>Driver</Th>
                    <Th>Vehicle</Th>
                    <Th>From</Th>
                    <Th>State</Th>
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
                          <Badge tone="success">Active</Badge>
                        ) : (
                          <Badge>Ended</Badge>
                        )}
                      </Td>
                      <Td>
                        {assignment.active && (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-red-600 hover:bg-red-50"
                            onClick={() => removeMutation.mutate(assignment.assignmentId)}
                          >
                            Unassign
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
          <CardHeader title="New assignment" />
          <CardBody className="space-y-3">
            {createMutation.error && <ErrorMessage error={createMutation.error} />}

            {availableDrivers.length === 0 && (
              <Alert tone="warning">
                No eligible driver. A driver must be active, offline and not already assigned.
              </Alert>
            )}

            <Field label="Driver" required>
              <Select value={driverId} onChange={(event) => setDriverId(event.target.value)}>
                <option value="">Select a driver…</option>
                {availableDrivers.map((driver) => (
                  <option key={driver.id} value={driver.id}>
                    {driver.firstName} {driver.lastName}
                  </option>
                ))}
              </Select>
            </Field>

            <Field label="Vehicle" required>
              <Select value={vehicleId} onChange={(event) => setVehicleId(event.target.value)}>
                <option value="">Select a vehicle…</option>
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
              Assign vehicle
            </Button>
          </CardBody>
        </Card>
      </div>
    </>
  )
}
