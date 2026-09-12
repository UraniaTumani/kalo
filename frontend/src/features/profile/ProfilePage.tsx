import { useTranslation } from 'react-i18next'
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { authApi } from '@/lib/api/endpoints'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Badge, Button, Card, CardBody, CardHeader, Field, Input } from '@/components/ui'

const schema = z.object({
  firstName: z.string().trim().min(1, 'validation.required').max(100),
  lastName: z.string().trim().min(1, 'validation.required').max(100),
  email: z.union([z.literal(''), z.email('validation.emailInvalid')]).optional(),
})

type FormValues = z.infer<typeof schema>

export function ProfilePage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [saved, setSaved] = useState(false)

  const profileQuery = useQuery({
    queryKey: ['me'],
    queryFn: () => authApi.me(),
  })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  useEffect(() => {
    if (!profileQuery.data) return

    reset({
      firstName: profileQuery.data.firstName,
      lastName: profileQuery.data.lastName,
      email: profileQuery.data.email ?? '',
    })
  }, [profileQuery.data, reset])

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      authApi.updateMe({
        firstName: values.firstName,
        lastName: values.lastName,
        email: values.email?.trim() || null,
      }),
    onSuccess: (updated) => {
      setSaved(true)
      queryClient.setQueryData(['me'], updated)
      reset({
        firstName: updated.firstName,
        lastName: updated.lastName,
        email: updated.email ?? '',
      })
    },
  })

  if (profileQuery.isLoading) return <Spinner />
  if (profileQuery.error) return <ErrorMessage error={profileQuery.error} />

  const me = profileQuery.data!

  return (
    <>
      <PageHeader title={t('profile.title')} description={t('profile.subtitle')} />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title={t('profile.yourDetails')} />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => {
                setSaved(false)
                mutation.mutate(values)
              })}
              noValidate
            >
              {mutation.error && <ErrorMessage error={mutation.error} />}
              {saved && !isDirty && <Alert tone="success">{t('common.saved')}</Alert>}

              <div className="grid grid-cols-2 gap-3">
                <Field label={t('auth.firstName')} error={errors.firstName ? t(errors.firstName.message!) : undefined} required>
                  <Input {...register('firstName')} autoComplete="given-name" />
                </Field>
                <Field label={t('auth.lastName')} error={errors.lastName ? t(errors.lastName.message!) : undefined} required>
                  <Input {...register('lastName')} autoComplete="family-name" />
                </Field>
              </div>

              <Field label={t('auth.email')} error={errors.email ? t(errors.email.message!) : undefined}>
                <Input {...register('email')} type="email" autoComplete="email" />
              </Field>

              <Field
                label={t('auth.phone')}
                hint={t('profile.phoneReadOnly')}
              >
                <Input value={me.phone} disabled readOnly />
              </Field>

              <Button type="submit" loading={mutation.isPending} disabled={!isDirty}>
                {t('profile.saveChanges')}
              </Button>
            </form>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title={t('profile.account')} />
          <CardBody>
            <dl className="space-y-2.5 text-sm">
              <Row label={t('profile.role')}>
                <Badge tone={me.role === 'ADMIN' ? 'info' : 'neutral'}>{t('roles.' + me.role)}</Badge>
              </Row>
              <Row label={t('profile.status')}>
                <StatusBadge status={me.status} namespace="userStatus" />
              </Row>
              <Row label={t('profile.phoneVerified')}>
                <Badge tone={me.phoneVerified ? 'success' : 'neutral'}>
                  {me.phoneVerified ? t('profile.verified') : t('profile.notVerified')}
                </Badge>
              </Row>
              <Row label={t('profile.userId')}>
                <span className="font-medium text-ink-900">{me.id}</span>
              </Row>
            </dl>

            {!me.phoneVerified && (
              <div className="mt-4">
                <Alert tone="neutral">
                  {t('profile.verificationNotice')}
                </Alert>
              </div>
            )}
          </CardBody>
        </Card>
      </div>
    </>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-ink-500">{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}
