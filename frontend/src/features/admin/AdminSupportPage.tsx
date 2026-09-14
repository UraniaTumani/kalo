import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MessageSquare } from 'lucide-react'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { AdminSupportRequestResponse, SupportStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { SupportStatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  FilterBar,
  Select,
} from '@/components/ui'
import { formatDateTime } from '@/lib/utils'

const STATUSES: (SupportStatus | 'ALL')[] = ['ALL', 'OPEN', 'IN_PROGRESS', 'RESOLVED']

/** The next status an admin would plausibly move a request to, in order. */
const NEXT: Record<SupportStatus, SupportStatus[]> = {
  OPEN: ['IN_PROGRESS', 'RESOLVED'],
  IN_PROGRESS: ['RESOLVED'],
  RESOLVED: ['OPEN'],
}

/**
 * The triage queue.
 *
 * A list of cards rather than a table: the message is the point, and a message
 * does not fit in a table cell. Everything an admin needs to answer one — who
 * wrote it, under which role, how to reach them, and what they said — is on the
 * card, so there is no second panel to open.
 */
export function AdminSupportPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<SupportStatus | 'ALL'>('ALL')

  const requestsQuery = useQuery({
    queryKey: ['admin', 'support', status, page],
    queryFn: () =>
      adminApi.supportRequests({
        page,
        size: 10,
        status: status === 'ALL' ? undefined : status,
      }),
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, next }: { id: number; next: SupportStatus }) =>
      adminApi.updateSupportStatus(id, next),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'support'] }),
  })

  const { rows, page: pageData, isEmpty } = readPage(requestsQuery.data)

  return (
    <>
      <PageHeader title={t('admin.supportTitle')} description={t('admin.supportSubtitle')} />

      <FilterBar className="mb-4">
        <Field label={t('admin.filterStatus')}>
          <Select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as SupportStatus | 'ALL')
              setPage(0)
            }}
            className="w-full sm:w-44"
          >
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? t('partner.allStatuses') : t('supportStatus.' + value)}
              </option>
            ))}
          </Select>
        </Field>
      </FilterBar>

      {(requestsQuery.error || updateMutation.error) && (
        <div className="mb-4">
          <ErrorMessage error={requestsQuery.error ?? updateMutation.error} />
        </div>
      )}

      <Card>
        {requestsQuery.isLoading && (
          <div className="p-5">
            <Spinner />
          </div>
        )}

        {requestsQuery.isError && !requestsQuery.isLoading && (
          <EmptyState title={t('errors.loadFailed')} description={t('admin.loadFailedHint')} />
        )}

        {!requestsQuery.isLoading && !requestsQuery.isError && isEmpty && (
          <EmptyState
            icon={<MessageSquare className="size-5" aria-hidden />}
            title={t('admin.noSupportRequests')}
            description={t('admin.noSupportRequestsHint')}
          />
        )}

        {rows.length > 0 && (
          <>
            <div className="space-y-3 p-4">
              {rows.map((request) => (
                <RequestCard
                  key={request.id}
                  request={request}
                  pending={
                    updateMutation.isPending && updateMutation.variables?.id === request.id
                  }
                  onMove={(next) => updateMutation.mutate({ id: request.id, next })}
                />
              ))}
            </div>
            <Pagination page={pageData} onPageChange={setPage} />
          </>
        )}
      </Card>
    </>
  )
}

function RequestCard({
  request,
  pending,
  onMove,
}: {
  request: AdminSupportRequestResponse
  pending: boolean
  onMove: (next: SupportStatus) => void
}) {
  const { t } = useTranslation()

  return (
    /* A request is one piece of content an admin acts on, so it is an article. */
    <article
      aria-label={request.subject}
      className="rounded-xl border border-ink-200/70 p-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold text-ink-900">{request.subject}</p>
          <p className="text-xs text-ink-500">{t('support.category.' + request.category)}</p>
        </div>
        <SupportStatusBadge status={request.status} />
      </div>

      <p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-ink-700">
        {request.message}
      </p>

      {/* Who wrote it, and how to reach them — the queue's whole reason to exist. */}
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-ink-200/70 pt-3 text-xs text-ink-500">
        <Badge tone={request.role === 'PARTNER' ? 'info' : 'neutral'}>
          {t('roles.' + request.role)}
        </Badge>
        <span className="font-medium text-ink-700">
          {request.userFirstName} {request.userLastName}
        </span>
        <span className="tnum">{request.userPhone}</span>
        {request.userEmail && <span className="truncate">{request.userEmail}</span>}
        <span className="tnum ml-auto">{formatDateTime(request.createdAt)}</span>
      </div>

      <div className="mt-3 flex flex-wrap justify-end gap-2">
        {NEXT[request.status].map((next) => (
          <Button
            key={next}
            size="sm"
            variant={next === 'RESOLVED' ? 'primary' : 'secondary'}
            loading={pending}
            onClick={() => onMove(next)}
          >
            {t('admin.moveTo.' + next)}
          </Button>
        ))}
      </div>
    </article>
  )
}
