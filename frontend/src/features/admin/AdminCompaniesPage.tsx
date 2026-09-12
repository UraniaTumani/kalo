import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { AdminPartnerResponse, CompanyStatus, VerificationStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, EmptyState, Select, Table, Td, Th } from '@/components/ui'

const VERIFICATION: (VerificationStatus | 'ALL')[] = [
  'ALL',
  'DRAFT',
  'PENDING',
  'APPROVED',
  'REJECTED',
]

const COMPANY: (CompanyStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'INACTIVE', 'SUSPENDED']

export function AdminCompaniesPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [verification, setVerification] = useState<VerificationStatus | 'ALL'>('ALL')
  const [companyStatus, setCompanyStatus] = useState<CompanyStatus | 'ALL'>('ALL')

  const companiesQuery = useQuery({
    queryKey: ['admin', 'companies', verification, companyStatus, page],
    queryFn: () =>
      adminApi.partners({
        page,
        size: 10,
        status: verification === 'ALL' ? undefined : verification,
        companyStatus: companyStatus === 'ALL' ? undefined : companyStatus,
      }),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['admin'] })

  /* Held while the admin confirms; null means no dialog is open. */
  const [pendingSuspend, setPendingSuspend] = useState<AdminPartnerResponse | null>(null)

  const suspendMutation = useMutation({
    mutationFn: (companyId: number) => adminApi.suspendPartner(companyId),
    onSuccess: invalidate,
  })

  const reactivateMutation = useMutation({
    mutationFn: (companyId: number) => adminApi.reactivatePartner(companyId),
    onSuccess: invalidate,
  })

  const { rows, page: pageData, isEmpty } = readPage(companiesQuery.data)

  return (
    <>
      <PageHeader
        title={t('admin.companiesTitle')}
        description={t('admin.companiesSubtitle')}
        action={
          <div className="flex gap-2">
            <Select
              value={verification}
              onChange={(event) => {
                setVerification(event.target.value as VerificationStatus | 'ALL')
                setPage(0)
              }}
              className="w-40"
            >
              {VERIFICATION.map((value) => (
                <option key={value} value={value}>
                  {value === 'ALL' ? t('admin.allVerification') : label('verificationStatus', value)}
                </option>
              ))}
            </Select>
            <Select
              value={companyStatus}
              onChange={(event) => {
                setCompanyStatus(event.target.value as CompanyStatus | 'ALL')
                setPage(0)
              }}
              className="w-36"
            >
              {COMPANY.map((value) => (
                <option key={value} value={value}>
                  {value === 'ALL' ? t('partner.allStatuses') : label('companyStatus', value)}
                </option>
              ))}
            </Select>
          </div>
        }
      />

      {(companiesQuery.error || suspendMutation.error || reactivateMutation.error) && (
        <div className="mb-4">
          <ErrorMessage
            error={companiesQuery.error ?? suspendMutation.error ?? reactivateMutation.error}
          />
        </div>
      )}

      <Card>
        {companiesQuery.isLoading && (
          <div className="p-5">
            <Spinner />
          </div>
        )}

        {companiesQuery.isError && !companiesQuery.isLoading && (
          <EmptyState
            title={t('errors.loadFailed')}

          />
        )}

        {!companiesQuery.isLoading && !companiesQuery.isError && isEmpty && (
          <EmptyState title={t('admin.noCompanies')} />
        )}

        {rows.length > 0 && (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>{t('ride.company')}</Th>
                  <Th>NIPT</Th>
                  <Th>{t('nav.verification')}</Th>
                  <Th>{t('ride.status')}</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {rows.map((company) => (
                  <tr key={company.companyId}>
                    <Td>
                      <span className="font-medium">{company.displayName}</span>
                      <span className="block text-xs text-ink-500">{company.legalName}</span>
                    </Td>
                    <Td className="text-xs">{company.nipt}</Td>
                    <Td>
                      <StatusBadge status={company.verificationStatus} namespace="verificationStatus" />
                    </Td>
                    <Td>
                      <StatusBadge status={company.companyStatus} namespace="companyStatus" />
                    </Td>
                    <Td>
                      <CompanyActions
                        company={company}
                        onSuspend={() => setPendingSuspend(company)}
                        onReactivate={() => reactivateMutation.mutate(company.companyId)}
                      />
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={pageData} onPageChange={setPage} />
          </>
        )}
      </Card>

      <div className="mt-4">
        <Alert tone="info">
          {t('admin.suspensionNote')}

        </Alert>
      </div>

      <ConfirmDialog
        open={pendingSuspend !== null}
        title={t('confirm.suspendCompany.title', { name: pendingSuspend?.displayName })}
        description={t('confirm.suspendCompany.body')}
        confirmLabel={t('confirm.suspendCompany.action')}
        cancelLabel={t('confirm.keep')}
        loading={suspendMutation.isPending}
        onCancel={() => setPendingSuspend(null)}
        onConfirm={() =>
          pendingSuspend &&
          suspendMutation.mutate(pendingSuspend.companyId, {
            onSuccess: () => setPendingSuspend(null),
          })
        }
      />
    </>
  )
}

/**
 * Mirrors the backend state machine rather than reading companyStatus alone.
 * Suspension means "an approved company has been switched off", so it is only
 * offered for APPROVED companies — previously Suspend was shown for anything
 * not already suspended, which let an admin push a DRAFT company into
 * DRAFT + SUSPENDED, a state nothing could undo.
 */
function CompanyActions({
  company,
  onSuspend,
  onReactivate,
}: {
  company: AdminPartnerResponse
  onSuspend: () => void
  onReactivate: () => void
}) {
  const { t } = useTranslation()

  if (company.verificationStatus !== 'APPROVED') {
    return (
      <span className="text-xs text-ink-400">
        {company.verificationStatus === 'PENDING'
          ? t('admin.awaitingReview')
          : company.verificationStatus === 'REJECTED'
            ? t('admin.rejectedCanResubmit')
            : t('admin.inOnboarding')}
      </span>
    )
  }

  if (company.companyStatus === 'SUSPENDED') {
    return (
      <Button size="sm" variant="secondary" onClick={onReactivate}>
        {t('admin.reactivate')}
      </Button>
    )
  }

  return (
    <Button
      size="sm"
      variant="ghost"
      className="text-red-600 hover:bg-red-50"
      onClick={onSuspend}
    >
      {t('admin.suspend')}
    </Button>
  )
}
