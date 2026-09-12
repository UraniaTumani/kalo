import { useTranslation } from 'react-i18next'
import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { PaymentMethod } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader, Field, Input } from '@/components/ui'

const PAYMENT_METHODS: PaymentMethod[] = ['CASH', 'CARD_IN_CAR']

const profileSchema = z.object({
  legalName: z.string().trim().min(1, 'validation.required').max(200),
  displayName: z.string().trim().min(1, 'validation.required').max(150),
  phone: z
    .string()
    .trim()
    .regex(/^\+?[0-9]{6,19}$/, 'validation.phoneInvalid'),
  email: z.union([z.literal(''), z.email('validation.emailInvalid')]).optional(),
  address: z.string().trim().min(1, 'validation.required').max(500),
  // Optional to save, because a partner fills the profile in over several
  // visits, but both are required before submit-for-verification is accepted.
  licenseNumber: z.string().trim().max(100).optional(),
  licenseExpiryDate: z
    .string()
    .optional()
    .refine(
      (value) => !value || new Date(value) > new Date(),
      'validation.mustBeFuture',
    ),
})

type ProfileValues = z.infer<typeof profileSchema>

export function PartnerSettingsPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
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

  if (profileQuery.isLoading) return <Spinner />

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
      <PageHeader title={t('partner.settingsTitle')} description={t('partner.settingsSubtitle')} />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title={t('partner.companyProfile')} />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => profileMutation.mutate(values))}
              noValidate
            >
              {profileMutation.error && <ErrorMessage error={profileMutation.error} />}
              {profileMutation.isSuccess && !isDirty && (
                <Alert tone="success">{t('common.saved')}</Alert>
              )}

              <Field label={t('auth.legalName')} error={errors.legalName ? t(errors.legalName.message!) : undefined} required>
                <Input {...register('legalName')} />
              </Field>
              <Field
                label={t('auth.displayName')}
                error={errors.displayName ? t(errors.displayName.message!) : undefined}
                required
                hint={t('auth.displayNameHint')}
              >
                <Input {...register('displayName')} />
              </Field>
              <Field label={t('auth.phone')} error={errors.phone ? t(errors.phone.message!) : undefined} required>
                <Input {...register('phone')} type="tel" />
              </Field>
              <Field label={t('auth.email')} error={errors.email ? t(errors.email.message!) : undefined}>
                <Input {...register('email')} type="email" />
              </Field>
              <Field label={t('auth.address')} error={errors.address ? t(errors.address.message!) : undefined} required>
                <Input {...register('address')} />
              </Field>
              <Field
                label={t('partner.licenceNumber')}
                error={errors.licenseNumber ? t(errors.licenseNumber.message!) : undefined}
                hint={t('partner.requiredBeforeSubmit')}
              >
                <Input {...register('licenseNumber')} />
              </Field>
              <Field
                label={t('partner.licenceExpiry')}
                error={errors.licenseExpiryDate ? t(errors.licenseExpiryDate.message!) : undefined}
                hint={t('partner.licenceExpiryHint')}
              >
                <Input {...register('licenseExpiryDate')} type="date" />
              </Field>

              <Button type="submit" loading={profileMutation.isPending}>
                {t('common.save')}
              </Button>
            </form>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title={t('partner.bookings')}
            description={t('partner.bookingsHint')}
          />
          <CardBody className="space-y-4">
            {!settings && (
              <Alert tone="warning">
                {t('availability.notApprovedYet')}
              </Alert>
            )}

            {settingsMutation.error && <ErrorMessage error={settingsMutation.error} />}

            {settings && (
              <>
                <label className="flex items-center justify-between gap-3 rounded-lg border border-ink-200 px-3 py-2.5">
                  <span>
                    <span className="block text-sm font-medium text-ink-900">
                      {t('partner.acceptingBookings')}
                    </span>
                    <span className="block text-xs text-ink-500">
                      {t('partner.acceptingBookingsHint')}
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
                    {t('partner.paymentMethods')}
                  </p>
                  <div className="space-y-2">
                    {PAYMENT_METHODS.map((method) => (
                      <label
                        key={method}
                        className="flex items-center justify-between gap-3 rounded-lg border border-ink-200 px-3 py-2.5"
                      >
                        <span className="text-sm text-ink-800">
                          {label('paymentMethod', method)}
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
                    {t('partner.paymentMethodsHint')}
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
