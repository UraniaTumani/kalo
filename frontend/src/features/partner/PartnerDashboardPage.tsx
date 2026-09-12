import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader } from '@/components/ui'

export function PartnerDashboardPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const profileQuery = useQuery({
    queryKey: ['partner', 'profile'],
    queryFn: () => partnerApi.profile(),
  })

  const driversQuery = useQuery({
    queryKey: ['partner', 'drivers'],
    queryFn: () => partnerApi.drivers(),
  })

  const settingsQuery = useQuery({
    queryKey: ['partner', 'operational-settings'],
    queryFn: () => partnerApi.operationalSettings(),
    retry: false,
  })

  const openRidesQuery = useQuery({
    queryKey: ['partner', 'rides', 'REQUESTED', 0],
    queryFn: () => partnerApi.rides({ status: 'REQUESTED', page: 0, size: 1 }),
    refetchInterval: 10_000,
    retry: false,
  })

  if (profileQuery.isLoading) return <Spinner />
  if (profileQuery.error) return <ErrorMessage error={profileQuery.error} />

  const company = profileQuery.data!
  const drivers = driversQuery.data ?? []
  const online = drivers.filter((driver) => driver.availabilityStatus === 'ONLINE').length
  const busy = drivers.filter((driver) => driver.availabilityStatus === 'BUSY').length
  const pendingRides = openRidesQuery.data?.totalElements ?? 0

  const approved = company.verificationStatus === 'APPROVED'
  const active = company.status === 'ACTIVE'

  return (
    <>
      <PageHeader
        title={company.displayName}
        description={`${company.legalName} · NIPT ${company.nipt}`}
        action={
          <div className="flex gap-2">
            <StatusBadge status={company.verificationStatus} namespace="verificationStatus" />
            <StatusBadge status={company.status} namespace="companyStatus" />
          </div>
        }
      />

      {!approved && (
        <div className="mb-4">
          <Alert
            tone={company.verificationStatus === 'REJECTED' ? 'danger' : 'warning'}
            title={
              company.verificationStatus === 'DRAFT'
                ? t('dashboard.notSubmitted')
                : company.verificationStatus === 'PENDING'
                  ? t('dashboard.waitingReview')
                  : t('dashboard.wasRejected')
            }
          >
            <p>
              {t('dashboard.onboardingHint')}

            </p>
            <div className="mt-2">
              <Link to="/partner/documents">
                <Button size="sm" variant="secondary">
                  {t('dashboard.goToDocuments')}
                </Button>
              </Link>
            </div>
          </Alert>
        </div>
      )}

      {approved && !active && (
        <div className="mb-4">
          <Alert tone="danger" title={t('dashboard.notActive')}>
            {t('dashboard.notActiveHint')}
          </Alert>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t('dashboard.drivers')} value={drivers.length} />
        <Stat label={t('dashboard.onlineNow')} value={online} tone="success" />
        <Stat label={t('dashboard.onARide')} value={busy} tone="info" />
        <Stat label={t('dashboard.awaitingResponse')} value={pendingRides} tone={pendingRides ? 'warning' : undefined} />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title={t('dashboard.acceptingBookings')} />
          <CardBody className="space-y-2 text-sm">
            {settingsQuery.data ? (
              <>
                <Row
                  label={t('dashboard.bookingEnabled')}
                  value={settingsQuery.data.bookingEnabled ? t('common.yes') : t('common.no')}
                />
                <Row
                  label={t('dashboard.paymentMethods')}
                  value={
                    settingsQuery.data.paymentMethods.length
                      ? settingsQuery.data.paymentMethods.map((m) => label('paymentMethod', m)).join(', ')
                      : t('dashboard.noneConfigured')
                  }
                />
              </>
            ) : (
              <p className="text-ink-500">{t('dashboard.availableOnceApproved')}</p>
            )}
            <Link to="/partner/settings" className="inline-block pt-1">
              <Button size="sm" variant="secondary">
                {t('dashboard.editSettings')}
              </Button>
            </Link>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title={t('dashboard.nextSteps')} />
          <CardBody>
            <ol className="space-y-2 text-sm text-ink-600">
              <li>1. {t('dashboard.step1')}</li>
              <li>2. {t('dashboard.step2')}</li>
              <li>3. {t('dashboard.step3')}</li>
              <li>4. {t('dashboard.step4')}</li>
            </ol>
            <Link to="/partner/rides" className="mt-3 inline-block">
              <Button size="sm">{t('dashboard.openRides')}</Button>
            </Link>
          </CardBody>
        </Card>
      </div>
    </>
  )
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone?: 'success' | 'info' | 'warning'
}) {
  const toneClass =
    tone === 'success'
      ? 'text-emerald-600'
      : tone === 'info'
        ? 'text-brand-600'
        : tone === 'warning'
          ? 'text-amber-600'
          : 'text-ink-900'

  return (
    <Card>
      <CardBody>
        <p className="text-xs font-medium text-ink-500">{label}</p>
        <p className={`mt-1 text-2xl font-semibold ${toneClass}`}>{value}</p>
      </CardBody>
    </Card>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-ink-500">{label}</span>
      <span className="font-medium text-ink-900">{value}</span>
    </div>
  )
}
