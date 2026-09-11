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
import { humanise } from '@/lib/utils'

const schema = z.object({
  firstName: z.string().trim().min(1, 'First name is required').max(100),
  lastName: z.string().trim().min(1, 'Last name is required').max(100),
  email: z.union([z.literal(''), z.email('Enter a valid email')]).optional(),
})

type FormValues = z.infer<typeof schema>

export function ProfilePage() {
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

  if (profileQuery.isLoading) return <Spinner label="Loading your profile" />
  if (profileQuery.error) return <ErrorMessage error={profileQuery.error} />

  const me = profileQuery.data!

  return (
    <>
      <PageHeader title="Profile" description="Your account details." />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Your details" />
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
              {saved && !isDirty && <Alert tone="success">Profile saved.</Alert>}

              <div className="grid grid-cols-2 gap-3">
                <Field label="First name" error={errors.firstName?.message} required>
                  <Input {...register('firstName')} autoComplete="given-name" />
                </Field>
                <Field label="Last name" error={errors.lastName?.message} required>
                  <Input {...register('lastName')} autoComplete="family-name" />
                </Field>
              </div>

              <Field label="Email" error={errors.email?.message}>
                <Input {...register('email')} type="email" autoComplete="email" />
              </Field>

              <Field
                label="Phone"
                hint="Your phone is your username and cannot be changed here."
              >
                <Input value={me.phone} disabled readOnly />
              </Field>

              <Button type="submit" loading={mutation.isPending} disabled={!isDirty}>
                Save changes
              </Button>
            </form>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Account" />
          <CardBody>
            <dl className="space-y-2.5 text-sm">
              <Row label="Role">
                <Badge tone={me.role === 'ADMIN' ? 'info' : 'neutral'}>{humanise(me.role)}</Badge>
              </Row>
              <Row label="Status">
                <StatusBadge status={me.status} />
              </Row>
              <Row label="Phone verified">
                <Badge tone={me.phoneVerified ? 'success' : 'neutral'}>
                  {me.phoneVerified ? 'Verified' : 'Not verified'}
                </Badge>
              </Row>
              <Row label="User ID">
                <span className="font-medium text-ink-900">{me.id}</span>
              </Row>
            </dl>

            {!me.phoneVerified && (
              <div className="mt-4">
                <Alert tone="neutral">
                  Phone verification is not part of the MVP yet, so this stays unverified.
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
