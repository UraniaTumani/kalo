import { useTranslation } from 'react-i18next'
import { ApiError } from '@/lib/api/client'
import { useStatusLabel } from './StatusBadge'

const types = ['DRIVING_LICENSE', 'VEHICLE_REGISTRATION', 'INSURANCE', 'TECHNICAL_INSPECTION']
const reasons = ['MISSING', 'DRAFT', 'PENDING', 'REJECTED', 'UNAPPROVED', 'EXPIRY_MISSING', 'EXPIRED', 'FUTURE_ISSUE_DATE']
type Issue = { ownerType: 'DRIVER' | 'VEHICLE'; documentType: string; reason: string }

export function isFleetDocumentFailure(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 400 && error.body?.code === 'FLEET_DOCUMENTS_REQUIRED'
}

function validIssue(value: unknown): value is Issue {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return typeof item.documentType === 'string' && types.includes(item.documentType)
    && item.ownerType === (item.documentType === 'DRIVING_LICENSE' ? 'DRIVER' : 'VEHICLE')
    && typeof item.reason === 'string' && reasons.includes(item.reason)
}

/** Render only known codes. Never show provider detail, IDs, paths or rejection text. */
export function FleetDocumentBlockers({ error, driver = false }: { error: unknown; driver?: boolean }) {
  const { t } = useTranslation()
  const label = useStatusLabel()
  if (!isFleetDocumentFailure(error)) return null
  const raw = error.body?.documentIssues
  const issues = Array.isArray(raw) && raw.length <= 4 && raw.every(validIssue) ? raw as Issue[] : []
  return <section aria-label={t('fleetBlockers.title')} className="space-y-2 break-words text-sm">
    <p className="font-semibold">{t('fleetBlockers.title')}</p>
    {issues.length > 0 && <ul className="list-disc space-y-2 pl-5">{issues.map((issue, index) => <li key={`${issue.documentType}-${index}`}>
      <span className="font-semibold">{label('documentType', issue.documentType)}</span>: {t(`fleetBlockers.${issue.reason}`)}
    </li>)}</ul>}
    <p>{t(driver ? 'fleetBlockers.driverHelp' : 'fleetBlockers.partnerHelp')}</p>
    <p>{t('fleetBlockers.snapshot')}</p>
  </section>
}
