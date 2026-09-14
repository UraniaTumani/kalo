import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { ChevronDown, LifeBuoy, MessageSquare } from 'lucide-react'
import { supportApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { SupportCategory } from '@/lib/api/types'
import { useAuth } from '@/auth/AuthContext'
import { PageHeader } from '@/components/AppLayout'
import { SupportStatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Pagination } from '@/components/Pagination'
import { Spinner } from '@/components/ui/Spinner'
import {
  Alert,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Field,
  Input,
  Select,
  Textarea,
} from '@/components/ui'
import { cn, formatDateTime } from '@/lib/utils'
import { faqFor } from './faq'

const CATEGORIES: SupportCategory[] = [
  'RIDE_ISSUE',
  'PAYMENT',
  'ACCOUNT',
  'DRIVER_OR_VEHICLE',
  'TECHNICAL',
  'OTHER',
]

const schema = z.object({
  category: z.enum([
    'RIDE_ISSUE',
    'PAYMENT',
    'ACCOUNT',
    'DRIVER_OR_VEHICLE',
    'TECHNICAL',
    'OTHER',
  ]),
  subject: z.string().trim().min(1, 'validation.required').max(150),
  message: z.string().trim().min(1, 'validation.required').max(4000),
})

type Values = z.infer<typeof schema>

/**
 * One page for customers and partners.
 *
 * The two roles ask different questions but need the same thing: an answer if
 * one already exists, and a way to reach a person if it does not. So the FAQ
 * filters by role and everything else is shared — a second near-identical page
 * would only be two copies of the same form to keep in step.
 */
export function SupportPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)

  const role = user?.role ?? 'CUSTOMER'
  const entries = faqFor(role)

  const requestsQuery = useQuery({
    queryKey: ['support', 'mine', page],
    queryFn: () => supportApi.mine({ page, size: 10 }),
  })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { category: 'RIDE_ISSUE', subject: '', message: '' },
  })

  const createMutation = useMutation({
    mutationFn: (values: Values) => supportApi.create(values),
    onSuccess: () => {
      reset({ category: 'RIDE_ISSUE', subject: '', message: '' })
      queryClient.invalidateQueries({ queryKey: ['support'] })
    },
  })

  const { rows, page: pageData, isEmpty } = readPage(requestsQuery.data)

  return (
    <>
      <PageHeader title={t('support.title')} description={t('support.subtitle')} />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_24rem]">
        <div className="space-y-4">
          <Card>
            <CardHeader
              title={t('support.faqTitle')}
              description={t('support.faqSubtitle')}
            />
            <CardBody className="space-y-1.5">
              {entries.map((entry) => (
                <FaqItem key={entry.id} id={entry.id} />
              ))}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title={t('support.myRequestsTitle')}
              description={t('support.myRequestsSubtitle')}
            />

            {requestsQuery.isLoading && (
              <div className="p-5">
                <Spinner />
              </div>
            )}

            {requestsQuery.isError && !requestsQuery.isLoading && (
              <EmptyState
                title={t('errors.loadFailed')}
                description={t('support.loadFailedHint')}
              />
            )}

            {!requestsQuery.isLoading && !requestsQuery.isError && isEmpty && (
              <EmptyState
                icon={<MessageSquare className="size-5" aria-hidden />}
                title={t('support.noRequests')}
                description={t('support.noRequestsHint')}
              />
            )}

            {rows.length > 0 && (
              <>
                <div className="space-y-2 px-5 pb-4">
                  {/* One request is a self-contained piece of content, not a div. */}
                  {rows.map((request) => (
                    <article
                      key={request.id}
                      aria-label={request.subject}
                      className="rounded-xl border border-ink-200/70 p-3"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="truncate font-semibold text-ink-900">
                            {request.subject}
                          </p>
                          <p className="text-xs text-ink-500">
                            {t('support.category.' + request.category)}
                          </p>
                        </div>
                        <SupportStatusBadge status={request.status} />
                      </div>

                      <p className="mt-2 whitespace-pre-wrap text-sm text-ink-700">
                        {request.message}
                      </p>

                      <p className="tnum mt-2 text-xs text-ink-400">
                        {formatDateTime(request.createdAt)}
                      </p>
                    </article>
                  ))}
                </div>
                <Pagination page={pageData} onPageChange={setPage} />
              </>
            )}
          </Card>
        </div>

        <div>
          <Card>
            <CardHeader
              title={t('support.contactTitle')}
              description={t('support.contactSubtitle')}
            />
            <CardBody>
              {createMutation.isSuccess && (
                <div className="mb-4">
                  <Alert tone="success">{t('support.sent')}</Alert>
                </div>
              )}

              {createMutation.error && (
                <div className="mb-4">
                  <ErrorMessage error={createMutation.error} />
                </div>
              )}

              <form
                className="space-y-3"
                onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              >
                <Field label={t('support.categoryLabel')} required>
                  <Select {...register('category')}>
                    {CATEGORIES.map((value) => (
                      <option key={value} value={value}>
                        {t('support.category.' + value)}
                      </option>
                    ))}
                  </Select>
                </Field>

                <Field
                  label={t('support.subjectLabel')}
                  required
                  error={errors.subject && t(errors.subject.message ?? '')}
                >
                  <Input
                    {...register('subject')}
                    maxLength={150}
                    placeholder={t('support.subjectPlaceholder')}
                  />
                </Field>

                <Field
                  label={t('support.messageLabel')}
                  required
                  hint={t('support.messageHint')}
                  error={errors.message && t(errors.message.message ?? '')}
                >
                  <Textarea
                    {...register('message')}
                    rows={6}
                    maxLength={4000}
                    placeholder={t('support.messagePlaceholder')}
                  />
                </Field>

                <Button
                  type="submit"
                  className="w-full"
                  loading={createMutation.isPending}
                >
                  <LifeBuoy className="size-4" aria-hidden />
                  {t('support.send')}
                </Button>
              </form>

              <p className="mt-3 text-xs leading-relaxed text-ink-500">
                {t('support.responseNote')}
              </p>
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  )
}

/**
 * Closed by default and opened one at a time by the reader.
 *
 * Thirteen answers laid out in full is a wall nobody reads; the questions
 * alone are scannable, which is how somebody finds out they do not need to
 * write to us at all.
 */
function FaqItem({ id }: { id: string }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  return (
    <div className="rounded-xl border border-ink-200/70">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        className="flex w-full items-center justify-between gap-3 px-3.5 py-3 text-left"
      >
        <span className="text-sm font-medium text-ink-900">
          {t('support.faq.' + id + '.q')}
        </span>
        <ChevronDown
          aria-hidden
          className={cn(
            'size-4 shrink-0 text-ink-400 transition-transform',
            open && 'rotate-180',
          )}
        />
      </button>

      {open && (
        <p className="px-3.5 pb-3.5 text-sm leading-relaxed text-ink-600">
          {t('support.faq.' + id + '.a')}
        </p>
      )}
    </div>
  )
}
