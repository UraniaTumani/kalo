import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
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
import { useIsCompact } from '@/lib/useIsCompact'
import { cn } from '@/lib/utils'

export function AdminVerificationPage() {
  const { t } = useTranslation()
  const compact = useIsCompact()
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const pendingQuery = useQuery({
    queryKey: ['admin', 'partners', 'PENDING'],
    queryFn: () => adminApi.partners({ status: 'PENDING', page: 0, size: 20 }),
  })

  const { rows, isEmpty } = readPage(pendingQuery.data)

  return (
    <>
      <PageHeader
        title={t('admin.verificationTitle')}
        description={t('admin.verificationSubtitle')}
      />

      {pendingQuery.error && (
        <div className="mb-4">
          <ErrorMessage error={pendingQuery.error} />
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_24rem]">
        <Card>
          {pendingQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {pendingQuery.isError && !pendingQuery.isLoading && (
            <EmptyState
              title={t('errors.loadFailed')}
              description={t('admin.loadFailedHint')}
            />
          )}

          {!pendingQuery.isLoading && !pendingQuery.isError && isEmpty && (
            <EmptyState
              title={t('admin.nothingToReview')}
              description={t('admin.nothingToReviewHint')}
            />
          )}

          {rows.length > 0 && compact && (
            <div className="space-y-2 p-4">
              {rows.map((partner) => (
                <button
                  key={partner.companyId}
                  type="button"
                  onClick={() => setSelectedId(partner.companyId)}
                  className={cn(
                    'block w-full rounded-xl border p-3 text-left transition',
                    selectedId === partner.companyId
                      ? 'border-brand-400 bg-brand-50/40'
                      : 'border-ink-200/70 hover:bg-ink-50',
                  )}
                >
                  <p className="font-semibold text-ink-900">{partner.displayName}</p>
                  <p className="truncate text-xs text-ink-500">{partner.legalName}</p>
                  <p className="tnum mt-1 text-xs text-ink-400">
                    NIPT {partner.nipt} · {partner.phone}
                  </p>
                </button>
              ))}
            </div>
          )}

          {rows.length > 0 && !compact && (
            <Table>
              <thead>
                <tr>
                  <Th>{t('ride.company')}</Th>
                  <Th>{t('auth.nipt')}</Th>
                  <Th>{t('auth.phone')}</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {rows.map((partner) => (
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
                        {t('admin.review')}
                      </Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        {/* The placeholder only earns its space where there is a second column. */}
        {selectedId ? (
          <div>
            <ReviewPanel companyId={selectedId} onDone={() => setSelectedId(null)} />
          </div>
        ) : (
          !compact && (
            <div>
              <Card>
                <EmptyState
                  title={t('admin.noCompanySelected')}
                  description={t('admin.noCompanySelectedHint')}
                />
              </Card>
            </div>
          )
        )}
      </div>
    </>
  )
}

function ReviewPanel({ companyId, onDone }: { companyId: number; onDone: () => void }) {
  const { t } = useTranslation()
  const label = useStatusLabel()
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

  const [confirmingReject, setConfirmingReject] = useState(false)

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
    <>
      <Card>
        <CardHeader
          title={company.displayName}
          description={`${company.ownerFirstName} ${company.ownerLastName} · ${company.phone}`}
          action={
            <Button size="sm" variant="ghost" onClick={onDone}>
              {t('common.close')}
            </Button>
          }
        />
        <CardBody className="space-y-4">
          <dl className="space-y-1.5 text-sm">
            <Row label={t('auth.legalName')} value={company.legalName} />
            <Row label={t('auth.nipt')} value={company.nipt} />
            <Row label={t('auth.address')} value={company.address} />
            <Row label={t('partner.licence')} value={company.licenseNumber ?? '—'} />
          </dl>

          <div>
            <p className="mb-2 text-xs font-medium text-ink-700">
              {t('admin.documentsCount', { count: company.documents.length })}
            </p>

            {company.documents.length === 0 ? (
              <Alert tone="warning">
                {t('admin.noDocuments')}
              </Alert>
            ) : (
              <ul className="space-y-2">
                {company.documents.map((document) => (
                  <li key={document.id} className="rounded-lg border border-ink-200 px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-medium text-ink-800">
                        {label('documentType', document.documentType)}
                      </span>
                      <StatusBadge status={document.verificationStatus} namespace="documentStatus" />
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
            {t('admin.approve')}
          </Button>

          <div className="space-y-2 border-t border-ink-200 pt-3">
            <Field label={t('admin.rejectReason')} required>
              <Textarea
                rows={2}
                maxLength={1000}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder={t('admin.rejectReasonPlaceholder')}
              />
            </Field>
            <Button
              variant="danger"
              className="w-full"
              disabled={!reason.trim()}
              loading={rejectMutation.isPending}
              onClick={() => setConfirmingReject(true)}
            >
              {t('admin.reject')}
            </Button>
            <p className="text-xs text-ink-500">
              {t('admin.rejectHint')}
            </p>
          </div>
        </CardBody>
      </Card>

      <ConfirmDialog
        open={confirmingReject}
        title={t('confirm.rejectVerification.title')}
        description={t('confirm.rejectVerification.body')}
        confirmLabel={t('confirm.rejectVerification.action')}
        cancelLabel={t('confirm.keep')}
        loading={rejectMutation.isPending}
        onCancel={() => setConfirmingReject(false)}
        onConfirm={() =>
          rejectMutation.mutate(undefined, {
            onSuccess: () => setConfirmingReject(false),
          })
        }
      />
    </>
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
