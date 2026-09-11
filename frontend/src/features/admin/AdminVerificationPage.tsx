import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
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
  Table,
  Td,
  Textarea,
  Th,
} from '@/components/ui'
import { humanise } from '@/lib/utils'

export function AdminVerificationPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const pendingQuery = useQuery({
    queryKey: ['admin', 'partners', 'PENDING'],
    queryFn: () => adminApi.partners({ status: 'PENDING', page: 0, size: 20 }),
  })

  return (
    <>
      <PageHeader
        title="Partner verification"
        description="Companies waiting for review. Approving one activates it immediately."
      />

      {pendingQuery.error && <ErrorMessage error={pendingQuery.error} />}

      <div className="grid gap-4 lg:grid-cols-[1fr_24rem]">
        <Card>
          {pendingQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {pendingQuery.data?.empty && (
            <EmptyState
              title="Nothing to review"
              description="Companies appear here once they submit for verification."
            />
          )}

          {pendingQuery.data && !pendingQuery.data.empty && (
            <Table>
              <thead>
                <tr>
                  <Th>Company</Th>
                  <Th>NIPT</Th>
                  <Th>Contact</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {pendingQuery.data.content.map((partner) => (
                  <tr key={partner.companyId}>
                    <Td>
                      <span className="font-medium">{partner.displayName}</span>
                      <span className="block text-xs text-ink-500">{partner.legalName}</span>
                    </Td>
                    <Td className="text-xs">{partner.nipt}</Td>
                    <Td className="text-xs">
                      {partner.phone}
                      {partner.email && <span className="block text-ink-500">{partner.email}</span>}
                    </Td>
                    <Td>
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => setSelectedId(partner.companyId)}
                      >
                        Review
                      </Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <div>
          {selectedId ? (
            <ReviewPanel companyId={selectedId} onDone={() => setSelectedId(null)} />
          ) : (
            <Card>
              <EmptyState
                title="No company selected"
                description="Pick one from the queue to see its documents."
              />
            </Card>
          )}
        </div>
      </div>
    </>
  )
}

function ReviewPanel({ companyId, onDone }: { companyId: number; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [reason, setReason] = useState('')

  const detailQuery = useQuery({
    queryKey: ['admin', 'partner', companyId],
    queryFn: () => adminApi.partner(companyId),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['admin'] })
    onDone()
  }

  const approveMutation = useMutation({
    mutationFn: () => adminApi.approvePartner(companyId),
    onSuccess: invalidate,
  })

  const rejectMutation = useMutation({
    mutationFn: () => adminApi.rejectPartner(companyId, reason.trim()),
    onSuccess: invalidate,
  })

  if (detailQuery.isLoading) {
    return (
      <Card>
        <CardBody>
          <Spinner />
        </CardBody>
      </Card>
    )
  }

  if (detailQuery.error) {
    return (
      <Card>
        <CardBody>
          <ErrorMessage error={detailQuery.error} />
        </CardBody>
      </Card>
    )
  }

  const company = detailQuery.data!

  return (
    <Card>
      <CardHeader
        title={company.displayName}
        description={`${company.ownerFirstName} ${company.ownerLastName} · ${company.phone}`}
        action={
          <Button size="sm" variant="ghost" onClick={onDone}>
            Close
          </Button>
        }
      />
      <CardBody className="space-y-4">
        <dl className="space-y-1.5 text-sm">
          <Row label="Legal name" value={company.legalName} />
          <Row label="NIPT" value={company.nipt} />
          <Row label="Address" value={company.address} />
          <Row label="Licence" value={company.licenseNumber ?? '—'} />
        </dl>

        <div>
          <p className="mb-2 text-xs font-medium text-ink-700">
            Documents ({company.documents.length})
          </p>

          {company.documents.length === 0 ? (
            <Alert tone="warning">
              No documents. Approval requires at least one pending document.
            </Alert>
          ) : (
            <ul className="space-y-2">
              {company.documents.map((document) => (
                <li key={document.id} className="rounded-lg border border-ink-200 px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-medium text-ink-800">
                      {humanise(document.documentType)}
                    </span>
                    <StatusBadge status={document.verificationStatus} />
                  </div>
                  <a
                    href={document.fileUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-0.5 block truncate text-xs text-brand-700 hover:underline"
                  >
                    {document.fileUrl}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </div>

        {(approveMutation.error || rejectMutation.error) && (
          <ErrorMessage error={approveMutation.error ?? rejectMutation.error} />
        )}

        <Button
          variant="success"
          className="w-full"
          loading={approveMutation.isPending}
          onClick={() => approveMutation.mutate()}
        >
          Approve company
        </Button>

        <div className="space-y-2 border-t border-ink-200 pt-3">
          <Field label="Rejection reason" required>
            <Textarea
              rows={2}
              maxLength={1000}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="What needs to be fixed before resubmitting?"
            />
          </Field>
          <Button
            variant="danger"
            className="w-full"
            disabled={!reason.trim()}
            loading={rejectMutation.isPending}
            onClick={() => rejectMutation.mutate()}
          >
            Reject
          </Button>
          <p className="text-xs text-ink-500">
            The partner keeps access so they can fix the problem and submit again.
          </p>
        </div>
      </CardBody>
    </Card>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-500">{label}</dt>
      <dd className="text-right font-medium text-ink-900">{value}</dd>
    </div>
  )
}
