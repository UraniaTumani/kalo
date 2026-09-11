import { Badge } from './ui'
import { humanise } from '@/lib/utils'
import type {
  CompanyStatus,
  DocumentVerificationStatus,
  DriverAvailabilityStatus,
  DriverStatus,
  RideStatus,
  UserStatus,
  VehicleStatus,
  VerificationStatus,
} from '@/lib/api/types'

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

const rideTones: Record<RideStatus, Tone> = {
  REQUESTED: 'warning',
  DRIVER_ASSIGNED: 'info',
  DRIVER_ARRIVING: 'info',
  DRIVER_ARRIVED: 'info',
  IN_PROGRESS: 'info',
  COMPLETED: 'success',
  DECLINED: 'danger',
  CANCELLED: 'neutral',
  NO_RESPONSE: 'danger',
}

const genericTones: Record<string, Tone> = {
  ACTIVE: 'success',
  APPROVED: 'success',
  ONLINE: 'success',
  PENDING: 'warning',
  DRAFT: 'neutral',
  BUSY: 'info',
  INACTIVE: 'neutral',
  OFFLINE: 'neutral',
  SUSPENDED: 'danger',
  DISABLED: 'danger',
  REJECTED: 'danger',
}

export function RideStatusBadge({ status }: { status: RideStatus }) {
  return <Badge tone={rideTones[status]}>{humanise(status)}</Badge>
}

export function StatusBadge({
  status,
}: {
  status:
    | UserStatus
    | CompanyStatus
    | VerificationStatus
    | DriverStatus
    | DriverAvailabilityStatus
    | VehicleStatus
    | DocumentVerificationStatus
}) {
  return <Badge tone={genericTones[status] ?? 'neutral'}>{humanise(status)}</Badge>
}
