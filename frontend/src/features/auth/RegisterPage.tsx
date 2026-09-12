import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth, homePathFor } from '@/auth/AuthContext'
import { authApi } from '@/lib/api/endpoints'
import { Alert, Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { cn } from '@/lib/utils'
import { AuthShell } from './AuthShell'

// Mirrors ValidationPatterns.PHONE on the backend, so the user is told before
// the request rather than after a 400.
const phone = z
  .string()
  .trim()
  .regex(/^\+?[0-9]{6,19}$/, 'validation.phoneInvalid')

const email = z.union([z.literal(''), z.email('validation.emailInvalid')]).optional()

const password = z.string().min(8, 'validation.passwordTooShort').max(100)

const customerSchema = z.object({
  firstName: z.string().trim().min(1, 'validation.required').max(100),
  lastName: z.string().trim().min(1, 'validation.required').max(100),
  phone,
  email,
  password,
})

const partnerSchema = customerSchema.extend({
  legalName: z.string().trim().min(1, 'validation.required').max(200),
  displayName: z.string().trim().min(1, 'validation.required').max(150),
  nipt: z.string().trim().min(1, 'validation.required').max(30),
  address: z.string().trim().min(1, 'validation.required').max(500),
})

type CustomerValues = z.infer<typeof customerSchema>
type PartnerValues = z.infer<typeof partnerSchema>

export function RegisterPage() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<'customer' | 'partner'>('customer')

  return (
    <AuthShell
      title={t('auth.createTitle')}
      subtitle={
        tab === 'customer'
          ? t('auth.passengerSubtitle')
          : t('auth.partnerSubtitle')
      }
      footer={
        <>
          {t('auth.alreadyRegistered')}{' '}
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            {t('common.signIn')}
          </Link>
        </>
      }
    >
      <div className="mb-5 grid grid-cols-2 gap-1 rounded-lg bg-ink-100 p-1">
        {(['customer', 'partner'] as const).map((value) => (
          <button
            key={value}
            type="button"
            onClick={() => setTab(value)}
            className={cn(
              'rounded-md px-3 py-1.5 text-sm font-medium transition',
              tab === value
                ? 'bg-white text-ink-900 shadow-sm'
                : 'text-ink-500 hover:text-ink-800',
            )}
          >
            {value === 'customer' ? t('auth.passenger') : t('auth.taxiCompany')}
          </button>
        ))}
      </div>

      {tab === 'customer' ? <CustomerForm /> : <PartnerForm />}
    </AuthShell>
  )
}

function CustomerForm() {
  const { t } = useTranslation()
  const { login } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<CustomerValues>({ resolver: zodResolver(customerSchema) })

  const onSubmit = handleSubmit(async (values) => {
    setError(null)

    try {
      await authApi.registerCustomer({
        ...values,
        email: values.email?.trim() || null,
      })

      // Registration does not return a token, so sign in straight away.
      const user = await login(values.phone.trim(), values.password)
      navigate(homePathFor(user.role), { replace: true })
    } catch (caught) {
      setError(caught)
    }
  })

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      {error ? <ErrorMessage error={error} /> : null}

      <div className="grid grid-cols-2 gap-3">
        <Field label={t('auth.firstName')} error={errors.firstName ? t(errors.firstName.message!) : undefined} required>
          <Input {...register('firstName')} autoComplete="given-name" />
        </Field>
        <Field label={t('auth.lastName')} error={errors.lastName ? t(errors.lastName.message!) : undefined} required>
          <Input {...register('lastName')} autoComplete="family-name" />
        </Field>
      </div>

      <Field label={t('auth.phone')} error={errors.phone ? t(errors.phone.message!) : undefined} required hint={t('auth.phoneHint')}>
        <Input {...register('phone')} type="tel" placeholder={t('auth.phonePlaceholder')} />
      </Field>

      <Field label={t('auth.email')} error={errors.email ? t(errors.email.message!) : undefined}>
        <Input {...register('email')} type="email" autoComplete="email" />
      </Field>

      <Field label={t('auth.password')} error={errors.password ? t(errors.password.message!) : undefined} required>
        <Input {...register('password')} type="password" autoComplete="new-password" />
      </Field>

      <Button type="submit" loading={isSubmitting} className="w-full">
        {t('auth.createPassenger')}
      </Button>
    </form>
  )
}

function PartnerForm() {
  const { t } = useTranslation()
  const { login } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<PartnerValues>({ resolver: zodResolver(partnerSchema) })

  const onSubmit = handleSubmit(async (values) => {
    setError(null)

    try {
      await authApi.registerPartner({
        ...values,
        email: values.email?.trim() || null,
      })

      const user = await login(values.phone.trim(), values.password)
      navigate(homePathFor(user.role), { replace: true })
    } catch (caught) {
      setError(caught)
    }
  })

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      {error ? <ErrorMessage error={error} /> : null}

      <Alert tone="info">
        {t('auth.partnerDraftNotice')}
      </Alert>

      <div className="grid grid-cols-2 gap-3">
        <Field label={t('auth.firstName')} error={errors.firstName ? t(errors.firstName.message!) : undefined} required>
          <Input {...register('firstName')} autoComplete="given-name" />
        </Field>
        <Field label={t('auth.lastName')} error={errors.lastName ? t(errors.lastName.message!) : undefined} required>
          <Input {...register('lastName')} autoComplete="family-name" />
        </Field>
      </div>

      <Field label={t('auth.phone')} error={errors.phone ? t(errors.phone.message!) : undefined} required hint={t('auth.phoneHint')}>
        <Input {...register('phone')} type="tel" placeholder={t('auth.phonePlaceholder')} />
      </Field>

      <Field label={t('auth.email')} error={errors.email ? t(errors.email.message!) : undefined}>
        <Input {...register('email')} type="email" autoComplete="email" />
      </Field>

      <Field label={t('auth.password')} error={errors.password ? t(errors.password.message!) : undefined} required>
        <Input {...register('password')} type="password" autoComplete="new-password" />
      </Field>

      <Field label={t('auth.legalName')} error={errors.legalName ? t(errors.legalName.message!) : undefined} required>
        <Input {...register('legalName')} placeholder="ABC Taxi SHPK" />
      </Field>

      <Field
        label={t('auth.displayName')}
        error={errors.displayName ? t(errors.displayName.message!) : undefined}
        required
        hint={t('auth.displayNameHint')}
      >
        <Input {...register('displayName')} placeholder="ABC Taxi" />
      </Field>

      <Field label={t('auth.nipt')} error={errors.nipt ? t(errors.nipt.message!) : undefined} required>
        <Input {...register('nipt')} placeholder="K12345678A" />
      </Field>

      <Field label={t('auth.address')} error={errors.address ? t(errors.address.message!) : undefined} required>
        <Input {...register('address')} placeholder="Rruga e Durresit 45, Tirane" />
      </Field>

      <Button type="submit" loading={isSubmitting} className="w-full">
        {t('auth.registerCompany')}
      </Button>
    </form>
  )
}
