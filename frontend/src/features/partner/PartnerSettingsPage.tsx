import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { PaymentMethod } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader, Field, Input } from '@/components/ui'

const PAYMENT_METHODS: PaymentMethod[] = ['CASH', 'CARD_IN_CAR']

const profileSchema = z.object({
  legalName: z.string().trim().min(1, 'Required').max(200),
  displayName: z.string().trim().min(1, 'Required').max(150),
  phone: z
    .string()
    .trim()
    .regex(/^\+?[0-9]{6,19}$/, "Digits only, optionally starting with '+'"),
  email: z.union([z.literal(''), z.email('Enter a valid email')]).optional(),
  address: z.string().trim().min(1, 'Required').max(500),
  licenseNumber: z.string().trim().max(100).optional(),
  licenseExpiryDate: z.string().optional(),
})

type ProfileValues = z.infer<typeof profileSchema>

export function PartnerSettingsPage() {
  const queryClient = useQueryClient()

  const profileQuery = useQuery({
    queryKey: ['partner', 'profile'],
    queryFn: () => partnerApi.profile(),
  })

  const settingsQuery = useQuery({
    queryKey: ['partner', 'operational-settings'],
    queryFn: () => partnerApi.operationalSettings(),
    retry: false,
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['partner'] })

  const { register, handleSubmit, reset, formState: { errors, isDirty } } = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
  })

  useEffect(() => {
    if (!profileQuery.data) return

    reset({
      legalName: profileQuery.data.legalName,
      displayName: profileQuery.data.displayName,
      phone: profileQuery.data.phone,
      email: profileQuery.data.email ?? '',
      address: profileQuery.data.address,
      licenseNumber: profileQuery.data.licenseNumber ?? '',
      licenseExpiryDate: profileQuery.data.licenseExpiryDate ?? '',
    })
  }, [profileQuery.data, reset])

  const profileMutation = useMutation({
    mutationFn: (values: ProfileValues) =>
      partnerApi.updateProfile({
        ...values,
        email: values.email?.trim() || null,
        licenseNumber: values.licenseNumber?.trim() || null,
        licenseExpiryDate: values.licenseExpiryDate || null,
      }),
    onSuccess: invalidate,
  })

  const settingsMutation = useMutation({
    mutationFn: (body: { bookingEnabled: boolean; paymentMethods: PaymentMethod[] }) =>
      partnerApi.updateOperationalSettings(body),
    onSuccess: invalidate,
  })

  if (profileQuery.isLoading) return <Spinner label="Loading settings" />

  const settings = settingsQuery.data

  function togglePayment(method: PaymentMethod) {
    if (!settings) return

    const next = settings.paymentMethods.includes(method)
      ? settings.paymentMethods.filter((value) => value !== method)
      : [...settings.paymentMethods, method]

    // The backend requires at least one method, so refuse to empty the set.
    if (next.length === 0) return

    settingsMutation.mutate({ bookingEnabled: settings.bookingEnabled, paymentMethods: next })
  }

  return (
    <>
      <PageHeader title="Settings" description="Company details and how you take bookings." />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Company profile" />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => profileMutation.mutate(values))}
              noValidate
            >
              {profileMutation.error && <ErrorMessage error={profileMutation.error} />}
              {profileMutation.isSuccess && !isDirty && (
                <Alert tone="success">Profile saved.</Alert>
              )}

              <Field label="Legal name" error={errors.legalName?.message} required>
                <Input {...register('legalName')} />
              </Field>
              <Field
                label="Display name"
                error={errors.displayName?.message}
                required
                hint="Shown to passengers"
              >
                <Input {...register('displayName')} />
              </Field>
              <Field label="Phone" error={errors.phone?.message} required>
                <Input {...register('phone')} type="tel" />
              </Field>
              <Field label="Email" error={errors.email?.message}>
                <Input {...register('email')} type="email" />
              </Field>
              <Field label="Address" error={errors.address?.message} required>
                <Input {...register('address')} />
              </Field>
              <Field label="Licence number" error={errors.licenseNumber?.message}>
                <Input {...register('licenseNumber')} />
              </Field>
              <Field label="Licence expiry" error={errors.licenseExpiryDate?.message}>
                <Input {...register('licenseExpiryDate')} type="date" />
              </Field>

              <Button type="submit" loading={profileMutation.isPending}>
                Save profile
              </Button>
            </form>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Bookings"
            description="Only approved and active companies appear in search."
          />
          <CardBody className="space-y-4">
            {!settings && (
              <Alert tone="warning">
                Operational settings become available once your company is approved.
              </Alert>
            )}

            {settingsMutation.error && <ErrorMessage error={settingsMutation.error} />}

            {settings && (
              <>
                <label className="flex items-center justify-between gap-3 rounded-lg border border-ink-200 px-3 py-2.5">
                  <span>
                    <span className="block text-sm font-medium text-ink-900">
                      Accepting bookings
                    </span>
                    <span className="block text-xs text-ink-500">
                      Turn off to disappear from passenger search
                    </span>
                  </span>
                  <input
                    type="checkbox"
                    className="size-4 accent-brand-600"
                    checked={settings.bookingEnabled}
                    disabled={settingsMutation.isPending}
                    onChange={(event) =>
                      settingsMutation.mutate({
                        bookingEnabled: event.target.checked,
                        paymentMethods: settings.paymentMethods,
                      })
                    }
                  />
                </label>

                <div>
                  <p className="mb-2 text-xs font-medium text-ink-700">
                    Payment methods (at least one)
                  </p>
                  <div className="space-y-2">
                    {PAYMENT_METHODS.map((method) => (
                      <label
                        key={method}
                        className="flex items-center justify-between gap-3 rounded-lg border border-ink-200 px-3 py-2.5"
                      >
                        <span className="text-sm text-ink-800">
                          {method === 'CASH' ? 'Cash' : 'Card in car'}
                        </span>
                        <input
                          type="checkbox"
                          className="size-4 accent-brand-600"
                          checked={settings.paymentMethods.includes(method)}
                          disabled={settingsMutation.isPending}
                          onChange={() => togglePayment(method)}
                        />
                      </label>
                    ))}
                  </div>
                  <p className="mt-2 text-xs text-ink-500">
                    Passengers pay the driver directly. KALO does not process payments.
                  </p>
                </div>
              </>
            )}
          </CardBody>
        </Card>
      </div>
    </>
  )
}
