import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound, Phone } from 'lucide-react'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { IssuedResetCode, PasswordResetQueueItem } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Pagination } from '@/components/Pagination'
import { Spinner } from '@/components/ui/Spinner'
import { useIsCompact } from '@/lib/useIsCompact'
import {
  Alert,
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
} from '@/components/ui'
import { formatDateTime } from '@/lib/utils'

/**
 * The queue where a password recovery is actually verified.
 *
 * KALO cannot send anything — no SMS provider, no mail provider, and a phone
 * number that was never verified — so the identity check is a person making a
 * telephone call. This page exists to make that call easy to do properly: the
 * number is shown in full and dials on a tap, beside the name and role, so an
 * administrator can ring the holder and ask them something only they would
 * know before handing over a code.
 *
 * The code appears exactly once, in a dialog, and is never retrievable again.
 */
export function AdminPasswordResetsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const compact = useIsCompact()

  const [page, setPage] = useState(0)
  const [issued, setIssued] = useState<IssuedResetCode | null>(null)
  const [rejecting, setRejecting] = useState<PasswordResetQueueItem | null>(null)

  const query = useQuery({
    queryKey: ['admin', 'password-resets', page],
    queryFn: () => adminApi.pendingPasswordResets({ page, size: 20 }),
  })

  const issue = useMutation({
    mutationFn: (requestId: number) => adminApi.issuePasswordResetCode(requestId),
    onSuccess: (code) => {
      setIssued(code)
      void queryClient.invalidateQueries({ queryKey: ['admin', 'password-resets'] })
    },
  })

  const reject = useMutation({
    mutationFn: (requestId: number) => adminApi.rejectPasswordReset(requestId),
    onSuccess: () => {
      setRejecting(null)
      void queryClient.invalidateQueries({ queryKey: ['admin', 'password-resets'] })
    },
  })

  const { rows, page: pageData, isEmpty } = readPage(query.data)

  return (
    <div className="space-y-5">
      <PageHeader
        title={t('admin.passwordResets.title')}
        description={t('admin.passwordResets.subtitle')}
      />

      <Alert tone="info" title={t('admin.passwordResets.howTitle')}>
        {t('admin.passwordResets.howBody')}
      </Alert>

      {issue.isError ? <ErrorMessage error={issue.error} /> : null}
      {reject.isError ? <ErrorMessage error={reject.error} /> : null}

      <Card>
        <CardHeader title={t('admin.passwordResets.pending')} />
        <CardBody>
          {query.isPending ? (
            <div className="flex justify-center py-10">
              <Spinner />
            </div>
          ) : query.isError ? (
            <EmptyState
              title={t('errors.loadFailed')}
              description={t('admin.loadFailedHint')}
            />
          ) : isEmpty ? (
            <EmptyState
              icon={<KeyRound className="size-6 text-ink-400" aria-hidden />}
              title={t('admin.passwordResets.emptyTitle')}
              description={t('admin.passwordResets.emptyBody')}
            />
          ) : compact ? (
            <ul className="space-y-3">
              {rows.map((item) => (
                <li key={item.id} className="rounded-xl p-4 ring-1 ring-inset ring-ink-200">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-semibold text-ink-900">
                        {item.firstName} {item.lastName}
                      </p>
                      <a
                        href={`tel:${item.phone}`}
                        className="mt-0.5 inline-flex items-center gap-1.5 font-mono text-sm text-brand-700 hover:underline"
                      >
                        <Phone className="size-3.5" aria-hidden />
                        {item.phone}
                      </a>
                    </div>
                    <Badge tone="neutral">{item.role}</Badge>
                  </div>

                  <p className="mt-2 text-xs text-ink-500">{formatDateTime(item.requestedAt)}</p>

                  <div className="mt-3 flex gap-2">
                    <Button
                      size="sm"
                      onClick={() => issue.mutate(item.id)}
                      loading={issue.isPending && issue.variables === item.id}
                      className="flex-1"
                    >
                      {t('admin.passwordResets.issue')}
                    </Button>
                    <Button size="sm" variant="secondary" onClick={() => setRejecting(item)}>
                      {t('admin.passwordResets.reject')}
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-ink-200 text-left text-xs font-semibold text-ink-600">
                    <th className="pb-2 pr-4">{t('admin.passwordResets.person')}</th>
                    <th className="pb-2 pr-4">{t('auth.phone')}</th>
                    <th className="pb-2 pr-4">{t('admin.passwordResets.role')}</th>
                    <th className="pb-2 pr-4">{t('admin.passwordResets.requested')}</th>
                    <th className="pb-2 text-right">{t('admin.passwordResets.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((item) => (
                    <tr key={item.id} className="border-b border-ink-100 last:border-0">
                      <td className="py-3 pr-4 font-medium text-ink-900">
                        {item.firstName} {item.lastName}
                      </td>
                      <td className="py-3 pr-4">
                        <a
                          href={`tel:${item.phone}`}
                          className="inline-flex items-center gap-1.5 font-mono text-brand-700 hover:underline"
                        >
                          <Phone className="size-3.5" aria-hidden />
                          {item.phone}
                        </a>
                      </td>
                      <td className="py-3 pr-4">
                        <Badge tone="neutral">{item.role}</Badge>
                      </td>
                      <td className="py-3 pr-4 text-ink-600">
                        {formatDateTime(item.requestedAt)}
                      </td>
                      <td className="py-3 text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            onClick={() => issue.mutate(item.id)}
                            loading={issue.isPending && issue.variables === item.id}
                          >
                            {t('admin.passwordResets.issue')}
                          </Button>
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => setRejecting(item)}
                          >
                            {t('admin.passwordResets.reject')}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <Pagination page={pageData} onPageChange={setPage} />
        </CardBody>
      </Card>

      {/*
        The one moment the code exists outside a bcrypt hash. Deliberately a
        dialog that must be dismissed, rather than a toast that could scroll
        away before it has been read down the telephone.
      */}
      <ConfirmDialog
        open={issued !== null}
        title={t('admin.passwordResets.codeTitle')}
        confirmLabel={t('admin.passwordResets.codeDone')}
        onConfirm={() => setIssued(null)}
        onCancel={() => setIssued(null)}
        description={
          <div className="space-y-3">
            <p>{t('admin.passwordResets.codeBody')}</p>

            <p className="rounded-xl bg-ink-900 px-4 py-3 text-center font-mono text-2xl font-bold tracking-[0.3em] text-white">
              {issued?.code}
            </p>

            <p className="text-xs text-ink-500">
              {t('admin.passwordResets.codeExpires', {
                time: issued ? formatDateTime(issued.expiresAt) : '',
              })}
            </p>
          </div>
        }
      />

      <ConfirmDialog
        open={rejecting !== null}
        title={t('admin.passwordResets.rejectTitle')}
        confirmLabel={t('admin.passwordResets.reject')}
        loading={reject.isPending}
        onConfirm={() => rejecting && reject.mutate(rejecting.id)}
        onCancel={() => setRejecting(null)}
        description={t('admin.passwordResets.rejectBody', {
          name: rejecting ? `${rejecting.firstName} ${rejecting.lastName}` : '',
        })}
      />
    </div>
  )
}
