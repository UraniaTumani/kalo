import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Bell, Car, CarFront, PowerOff, Radio } from 'lucide-react'
import { Alert, Button, Card, CardBody, CardHeader, StatCard } from '@/components/ui'

export function PartnerDashboardPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const profileQuery = useQuery({
    queryKey: ['partner', 'profile'],
    queryFn: () => partnerApi.profile(),
  })

  /*
   * Counters, not a list: ask for one row and read totalElements, the way the
   * open-rides tile already does. Counting a fetched array would only ever have
   * counted the drivers on the first page.
   */
  const onlineQuery = useQuery({
    queryKey: ['partner', 'drivers', 'count', 'ONLINE'],
    queryFn: () => partnerApi.drivers({ availabilityStatus: 'ONLINE', size: 1 }),
  })

  const busyQuery = useQuery({
    queryKey: ['partner', 'drivers', 'count', 'BUSY'],
    queryFn: () => partnerApi.drivers({ availabilityStatus: 'BUSY', size: 1 }),
  })

  const totalDriversQuery = useQuery({
    queryKey: ['partner', 'drivers', 'count', 'ALL'],
    queryFn: () => partnerApi.drivers({ size: 1 }),
  })

  const vehiclesQuery = useQuery({
    queryKey: ['partner', 'vehicles', 'count'],
    queryFn: () => partnerApi.vehicles({ size: 1 }),
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
  const online = onlineQuery.data?.totalElements ?? 0
  const busy = busyQuery.data?.totalElements ?? 0
  const pendingRides = openRidesQuery.data?.totalElements ?? 0
  const totalDrivers = totalDriversQuery.data?.totalElements ?? 0
  const vehicles = vehiclesQuery.data?.totalElements ?? 0

  /*
   * Derived rather than queried: a driver is online, on a ride, or neither,
   * and asking the server a third time for a number it has already implied
   * would be a request per tile.
   */
  const offline = Math.max(0, totalDrivers - online - busy)

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

      {/*
        Ordered the way a dispatcher asks: what needs me now, who can take it,
        who is busy, who is idle, and how big is the fleet. Awaiting response
        leads because it is the only one that is ever a problem.
      */}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <StatCard
          label={t('dashboard.awaitingResponse')}
          value={pendingRides}
          tone={pendingRides ? 'warn' : 'neutral'}
          icon={<Bell className="size-4" aria-hidden />}
        />
        <StatCard
          label={t('dashboard.onlineNow')}
          value={online}
          tone="good"
          icon={<Radio className="size-4" aria-hidden />}
        />
        <StatCard
          label={t('dashboard.onARide')}
          value={busy}
          tone="brand"
          icon={<Car className="size-4" aria-hidden />}
        />
        <StatCard
          label={t('dashboard.offline')}
          value={offline}
          hint={t('dashboard.fleetHint')}
          icon={<PowerOff className="size-4" aria-hidden />}
        />
        <StatCard
          label={t('dashboard.vehicles')}
          value={vehicles}
          icon={<CarFront className="size-4" aria-hidden />}
        />
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

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-ink-500">{label}</span>
      <span className="font-medium text-ink-900">{value}</span>
    </div>
  )
}
