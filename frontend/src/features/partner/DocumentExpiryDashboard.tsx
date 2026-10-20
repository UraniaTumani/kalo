import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { partnerApi } from '@/lib/api/endpoints'
import type { DocumentResponse, DriverResponse, VehicleResponse, Page } from '@/lib/api/types'
import { Card, CardBody, CardHeader } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'

type Severity = 'expired' | 'urgent' | 'soon'
type Warning = { key: string; title: string; date: string; severity: Severity; href: string; owner?: DriverResponse | VehicleResponse; ownerKind?: 'driver' | 'vehicle' }

function severityOf(value: string | null | undefined, today: string): Severity | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const day = Date.parse(value + 'T00:00:00Z')
  const now = Date.parse(today + 'T00:00:00Z')
  if (!Number.isFinite(day)) return null
  const remaining = Math.round((day - now) / 86_400_000)
  if (remaining < 0) return 'expired'
  if (remaining <= 7) return 'urgent'
  if (remaining <= 30) return 'soon'
  return null
}

async function allPages<T>(load: (page: number) => Promise<Page<T>>): Promise<T[]> {
  const first = await load(0)
  const rows = [...first.content]
  // Paginate instead of silently ignoring records outside the first page.
  for (let page = 1; page < first.totalPages; page++) {
    rows.push(...(await load(page)).content)
  }
  return rows
}

async function expiryWarnings(): Promise<Warning[]> {
  const [profile, companyDocs, drivers, vehicles] = await Promise.all([
    partnerApi.profile(),
    partnerApi.documents(),
    allPages(page => partnerApi.drivers({ page, size: 100 })),
    allPages(page => partnerApi.vehicles({ page, size: 100 })),
  ])
  const today = new Date().toISOString().slice(0, 10)
  const warnings: Warning[] = []
  const add = (key: string, title: string, date: string | null | undefined, href: string, ownerKind?: 'driver' | 'vehicle', owner?: DriverResponse | VehicleResponse) => {
    const severity = severityOf(date, today)
    if (date && severity) warnings.push({ key, title, date, severity, href, ownerKind, owner })
  }
  add('company-licence', 'companyLicence', profile.licenseExpiryDate, '/partner/settings')
  const addDocs = (docs: DocumentResponse[], owner: string, href: string, ownerKind?: 'driver' | 'vehicle', ownerData?: DriverResponse | VehicleResponse) => {
    for (const doc of docs) add(`${owner}-doc-${doc.id}`, `document.${doc.documentType}`, doc.expiresAt, href, ownerKind, ownerData)
  }
  addDocs(companyDocs, 'company', '/partner/documents')
  for (const driver of drivers) {
    add(`driver-${driver.id}-licence`, `driver:${driver.firstName} ${driver.lastName}`, driver.licenseExpiryDate, '/partner/drivers', 'driver', driver)
  }
  for (const vehicle of vehicles) {
    const href = '/partner/vehicles'
    add(`vehicle-${vehicle.id}-registration`, `vehicle:${vehicle.plateNumber}:registration`, vehicle.registrationExpiryDate, href, 'vehicle', vehicle)
    add(`vehicle-${vehicle.id}-insurance`, `vehicle:${vehicle.plateNumber}:insurance`, vehicle.insuranceExpiryDate, href, 'vehicle', vehicle)
    add(`vehicle-${vehicle.id}-inspection`, `vehicle:${vehicle.plateNumber}:inspection`, vehicle.technicalInspectionExpiryDate, href, 'vehicle', vehicle)
  }
  // Documents have their own expiry dates; metadata fields are not automatically synced.
  // Limit concurrent calls to avoid overwhelming the backend for larger fleets.
  const owners: { key: string; href: string; ownerKind: 'driver' | 'vehicle'; ownerData: DriverResponse | VehicleResponse; load: () => Promise<DocumentResponse[]> }[] = [
    ...drivers.map((driver: DriverResponse) => ({ key: `driver-${driver.id}`, href: '/partner/drivers', ownerKind: 'driver' as const, ownerData: driver, load: () => partnerApi.driverDocuments(driver.id) })),
    ...vehicles.map((vehicle: VehicleResponse) => ({ key: `vehicle-${vehicle.id}`, href: '/partner/vehicles', ownerKind: 'vehicle' as const, ownerData: vehicle, load: () => partnerApi.vehicleDocuments(vehicle.id) })),
  ]
  for (let i = 0; i < owners.length; i += 8) {
    const batch = owners.slice(i, i + 8)
    const docs = await Promise.all(batch.map(owner => owner.load()))
    docs.forEach((items, index) => addDocs(items, batch[index].key, batch[index].href, batch[index].ownerKind, batch[index].ownerData))
  }
  const order: Record<Severity, number> = { expired: 0, urgent: 1, soon: 2 }
  return warnings.sort((a, b) => order[a.severity] - order[b.severity] || a.date.localeCompare(b.date))
}

export function DocumentExpiryDashboard() {
  const { t } = useTranslation()
  const { data, isPending, error } = useQuery({
    queryKey: ['partner', 'document-expiry', 'dashboard'],
    queryFn: expiryWarnings,
    staleTime: 60_000,
    refetchOnWindowFocus: true,
  })

  return (
    <Card>
      <CardHeader title={t('expiryDashboard.title')} />
      <CardBody>
        {isPending && <p className="text-sm text-ink-500">{t('common.loading')}</p>}
        {error && <ErrorMessage error={error} />}
        {data && data.length === 0 && <p className="text-sm text-ink-600">{t('expiryDashboard.clear')}</p>}
        {data && data.length > 0 && (
          <div className="space-y-3" data-testid="document-expiry-warnings">
            <p className="text-sm text-ink-600">{t('expiryDashboard.count', { count: data.length })}</p>
            <ul className="space-y-2">
              {data.map(item => (
                <li key={item.key} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-ink-200 p-3">
                  <div className="flex items-start gap-2">
                    <AlertTriangle className={`mt-0.5 size-4 shrink-0 ${item.severity === 'expired' ? 'text-bad-600' : 'text-amber-600'}`} aria-hidden />
                    <div>
                      <p className="text-sm font-medium text-ink-900">
                        {item.title.startsWith('document.') ? t('documentType.' + item.title.substring(9), { defaultValue: item.title.substring(9) })
                          : item.title === 'companyLicence' ? t('expiryDashboard.companyLicence')
                          : item.title.startsWith('driver:') ? t('expiryDashboard.driverLicence', { name: item.title.slice(7) })
                          : t('expiryDashboard.vehicleField', { label: item.title.slice(8).replaceAll(':', ' · ') })}
                      </p>
                      <p className="text-xs text-ink-600">
                        {t(`expiryDashboard.${item.severity}`)} · {item.date}
                      </p>
                    </div>
                  </div>
                  <Link to={item.href} state={item.ownerKind && item.owner ? { expiryDocumentOwner: { kind: item.ownerKind, owner: item.owner } } : undefined} className="text-sm font-semibold text-brand-800 underline underline-offset-4">{t('expiryDashboard.review')}</Link>
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardBody>
    </Card>
  )
}
