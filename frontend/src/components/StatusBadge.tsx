import { useTranslation } from 'react-i18next'
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
  BUSY: 'warning',
  INACTIVE: 'neutral',
  OFFLINE: 'neutral',
  SUSPENDED: 'danger',
  DISABLED: 'danger',
  REJECTED: 'danger',
}

/**
 * Translates a backend enum for display only. The raw value is what travels
 * back to the API — nothing here is ever sent.
 */
export function useStatusLabel() {
  const { t } = useTranslation()

  return (namespace: string, value: string | null | undefined) => {
    if (!value) return '—'

    const key = `${namespace}.${value}`
    const translated = t(key)

    // i18next echoes the key when there is no entry; fall back to a readable
    // form rather than showing "rideStatus.FOO" to a user.
    return translated === key ? humanise(value) : translated
  }
}

/**
 * Live states carry a dot; settled ones do not. A partner scanning a column of
 * rides is looking for the ones that still need something from them.
 */
const LIVE_RIDE_STATES = new Set<RideStatus>([
  'REQUESTED',
  'DRIVER_ASSIGNED',
  'DRIVER_ARRIVING',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
])

export function RideStatusBadge({ status }: { status: RideStatus }) {
  const label = useStatusLabel()
  return (
    <Badge tone={rideTones[status]} dot={LIVE_RIDE_STATES.has(status)}>
      {label('rideStatus', status)}
    </Badge>
  )
}

export function StatusBadge({
  status,
  namespace,
}: {
  status:
    | UserStatus
    | CompanyStatus
    | VerificationStatus
    | DriverStatus
    | DriverAvailabilityStatus
    | VehicleStatus
    | DocumentVerificationStatus
  /** Which translation group to read, e.g. "companyStatus". */
  namespace?: string
}) {
  const label = useStatusLabel()

  /*
   * ONLINE and BUSY are things happening right now; OFFLINE, INACTIVE and the
   * rest are settled. The dot says which without relying on hue alone, which
   * matters in a column somebody reads at a glance all day.
   */
  const live = status === 'ONLINE' || status === 'BUSY'

  return (
    <Badge tone={genericTones[status] ?? 'neutral'} dot={live}>
      {namespace ? label(namespace, status) : label('companyStatus', status)}
    </Badge>
  )
}
