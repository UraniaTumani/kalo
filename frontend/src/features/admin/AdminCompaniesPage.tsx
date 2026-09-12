import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { CompanyStatus, VerificationStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, EmptyState, Select, Table, Td, Th } from '@/components/ui'
import { humanise } from '@/lib/utils'

const VERIFICATION: (VerificationStatus | 'ALL')[] = [
  'ALL',
  'DRAFT',
  'PENDING',
  'APPROVED',
  'REJECTED',
]

const COMPANY: (CompanyStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'INACTIVE', 'SUSPENDED']

export function AdminCompaniesPage() {
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
        title="Taxi companies"
        description="Suspending a company removes it from search and blocks new rides. History is kept."
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
                  {value === 'ALL' ? 'All verification' : humanise(value)}
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
                  {value === 'ALL' ? 'All statuses' : humanise(value)}
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
            <Spinner label="Loading companies" />
          </div>
        )}

        {companiesQuery.isError && !companiesQuery.isLoading && (
          <EmptyState
            title="Could not load companies"
            description="The request to the server failed. Check that the backend is running."
          />
        )}

        {!companiesQuery.isLoading && !companiesQuery.isError && isEmpty && (
          <EmptyState title="No companies match this filter" />
        )}

        {rows.length > 0 && (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Company</Th>
                  <Th>NIPT</Th>
                  <Th>Verification</Th>
                  <Th>Status</Th>
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
                      <StatusBadge status={company.verificationStatus} />
                    </Td>
                    <Td>
                      <StatusBadge status={company.companyStatus} />
                    </Td>
                    <Td>
                      {company.companyStatus === 'SUSPENDED' ? (
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => reactivateMutation.mutate(company.companyId)}
                        >
                          Reactivate
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-red-600 hover:bg-red-50"
                          onClick={() => suspendMutation.mutate(company.companyId)}
                        >
                          Suspend
                        </Button>
                      )}
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
          Reactivating only works for companies that are already approved.
        </Alert>
      </div>
    </>
  )
}
