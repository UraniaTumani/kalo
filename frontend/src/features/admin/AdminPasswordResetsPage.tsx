import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { KeyRound, ShieldAlert } from 'lucide-react'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { IssuedResetCode } from '@/lib/api/types'
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
  Field,
  Input,
  Textarea,
} from '@/components/ui'
import { formatDateTime } from '@/lib/utils'

/** Matches the server's validation, which rejects a shorter note outright. */
const MIN_NOTE_LENGTH = 20

const schema = z.object({
  phone: z.string().min(1, 'required'),
  verificationNote: z.string().min(MIN_NOTE_LENGTH, 'tooShort'),
})

type FormValues = z.infer<typeof schema>

/**
 * The narrow way back in for somebody who has lost the number itself.
 *
 * This page used to be the whole recovery mechanism: a queue of requests and an
 * administrator telephoning each one, because MR TAXI could not send anything.
 * It now sends a one-time code by SMS, so nobody should normally end up here —
 * and the page is written to say so rather than to be convenient. There is no
 * queue any more, because a queue invites working through it. An administrator
 * has to arrive here on purpose, type a number, and write down what they
 * actually checked.
 *
 * The note is not decoration. Twenty characters is the server's own rule, and it
 * exists so the answer cannot be "ok" — a field somebody has to compose a
 * sentence into is a field that prompts them to do the checking, and it is what
 * somebody reviewing this months from now will read.
 *
 * The code appears exactly once, in a dialog, and is never retrievable again.
 */
export function AdminPasswordResetsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const compact = useIsCompact()

  const [page, setPage] = useState(0)
  const [issued, setIssued] = useState<IssuedResetCode | null>(null)

  const {
    register,
    handleSubmit,
    reset: resetForm,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  const query = useQuery({
    queryKey: ['admin', 'password-resets', 'fallback-log', page],
    queryFn: () => adminApi.fallbackResetLog({ page, size: 20 }),
  })

  const issue = useMutation({
    mutationFn: (values: FormValues) =>
      adminApi.issueFallbackResetCode({
        phone: values.phone.trim(),
        verificationNote: values.verificationNote.trim(),
      }),
    onSuccess: (code) => {
      setIssued(code)
      resetForm()
      void queryClient.invalidateQueries({
        queryKey: ['admin', 'password-resets', 'fallback-log'],
      })
    },
  })

  const onSubmit = handleSubmit((values) => issue.mutate(values))

  const { rows, page: pageData, isEmpty } = readPage(query.data)

  return (
    <div className="space-y-5">
      <PageHeader
        title={t('admin.passwordResets.title')}
        description={t('admin.passwordResets.subtitle')}
      />

      {/*
        Warning rather than info, on purpose. Everything else on this page is a
        form that works; the thing an administrator needs to know before using it
        is that they are about to step around the normal check.
      */}
      <Alert tone="warning" title={t('admin.passwordResets.howTitle')}>
        {t('admin.passwordResets.howBody')}
      </Alert>

      <Card>
        <CardHeader title={t('admin.passwordResets.issueTitle')} />
        <CardBody>
          <form onSubmit={onSubmit} className="space-y-4" noValidate>
            {issue.isError ? <ErrorMessage error={issue.error} /> : null}

            <Field
              label={t('auth.phone')}
              hint={t('admin.passwordResets.phoneHint')}
              error={errors.phone ? t('validation.required') : undefined}
              required
            >
              <Input
                {...register('phone')}
                type="tel"
                autoComplete="off"
                placeholder={t('auth.phonePlaceholder')}
              />
            </Field>

            <Field
              label={t('admin.passwordResets.noteLabel')}
              hint={t('admin.passwordResets.noteHint')}
              error={
                errors.verificationNote
                  ? t('admin.passwordResets.noteTooShort', { min: MIN_NOTE_LENGTH })
                  : undefined
              }
              required
            >
              <Textarea
                {...register('verificationNote')}
                rows={3}
                placeholder={t('admin.passwordResets.notePlaceholder')}
              />
            </Field>

            <Button type="submit" loading={issue.isPending}>
              {t('admin.passwordResets.issue')}
            </Button>
          </form>
        </CardBody>
      </Card>

      <Card>
        <CardHeader
          title={t('admin.passwordResets.logTitle')}
          description={t('admin.passwordResets.logSubtitle')}
        />
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
                <li
                  key={item.id}
                  className="rounded-xl p-4 ring-1 ring-inset ring-ink-200"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-semibold text-ink-900">
                        {item.personName}
                      </p>
                      <p className="mt-0.5 font-mono text-sm text-ink-500">
                        {item.personPhone}
                      </p>
                    </div>
                    <Badge tone="neutral">{item.status}</Badge>
                  </div>

                  <p className="mt-2 text-sm text-ink-700">{item.verificationNote}</p>

                  <p className="mt-2 text-xs text-ink-500">
                    {t('admin.passwordResets.issuedBy', {
                      name: item.issuedByName ?? '—',
                      time: formatDateTime(item.issuedAt),
                    })}
                  </p>
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
                    <th className="pb-2 pr-4">{t('admin.passwordResets.noteLabel')}</th>
                    <th className="pb-2 pr-4">
                      {t('admin.passwordResets.issuedByColumn')}
                    </th>
                    <th className="pb-2 pr-4">{t('admin.passwordResets.requested')}</th>
                    <th className="pb-2">{t('admin.passwordResets.outcome')}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((item) => (
                    <tr key={item.id} className="border-b border-ink-100 last:border-0">
                      <td className="py-3 pr-4 font-medium text-ink-900">
                        {item.personName}
                      </td>
                      <td className="py-3 pr-4 font-mono text-ink-600">
                        {item.personPhone}
                      </td>
                      {/*
                        Not truncated. The note is the reason this row exists,
                        and an ellipsis on the one field somebody is reviewing
                        would defeat the audit.
                      */}
                      <td className="max-w-sm py-3 pr-4 text-ink-700">
                        {item.verificationNote}
                      </td>
                      <td className="py-3 pr-4 text-ink-600">
                        {item.issuedByName ?? '—'}
                      </td>
                      <td className="py-3 pr-4 text-ink-600">
                        {formatDateTime(item.issuedAt)}
                      </td>
                      <td className="py-3">
                        <Badge tone="neutral">{item.status}</Badge>
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
        dialog that must be dismissed, rather than a toast that could scroll away
        before it has been read down the telephone.
      */}
      <ConfirmDialog
        open={issued !== null}
        title={t('admin.passwordResets.codeTitle')}
        confirmLabel={t('admin.passwordResets.codeDone')}
        onConfirm={() => setIssued(null)}
        onCancel={() => setIssued(null)}
        description={
          <div className="space-y-3">
            <p className="flex items-start gap-2 text-sm">
              <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-600" aria-hidden />
              <span>{t('admin.passwordResets.codeBody')}</span>
            </p>

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
    </div>
  )
}
