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
  .regex(/^\+?[0-9]{6,19}$/, "Digits only, optionally starting with '+'")

const email = z.union([z.literal(''), z.email('Enter a valid email')]).optional()

const password = z.string().min(8, 'At least 8 characters').max(100)

const customerSchema = z.object({
  firstName: z.string().trim().min(1, 'First name is required').max(100),
  lastName: z.string().trim().min(1, 'Last name is required').max(100),
  phone,
  email,
  password,
})

const partnerSchema = customerSchema.extend({
  legalName: z.string().trim().min(1, 'Legal name is required').max(200),
  displayName: z.string().trim().min(1, 'Display name is required').max(150),
  nipt: z.string().trim().min(1, 'NIPT is required').max(30),
  address: z.string().trim().min(1, 'Address is required').max(500),
})

type CustomerValues = z.infer<typeof customerSchema>
type PartnerValues = z.infer<typeof partnerSchema>

export function RegisterPage() {
  const [tab, setTab] = useState<'customer' | 'partner'>('customer')

  return (
    <AuthShell
      title="Create an account"
      subtitle={
        tab === 'customer'
          ? 'Book taxis from the companies available near you.'
          : 'Register your taxi company and submit it for verification.'
      }
      footer={
        <>
          Already registered?{' '}
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            Sign in
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
            {value === 'customer' ? 'Passenger' : 'Taxi company'}
          </button>
        ))}
      </div>

      {tab === 'customer' ? <CustomerForm /> : <PartnerForm />}
    </AuthShell>
  )
}

function CustomerForm() {
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
        <Field label="First name" error={errors.firstName?.message} required>
          <Input {...register('firstName')} autoComplete="given-name" />
        </Field>
        <Field label="Last name" error={errors.lastName?.message} required>
          <Input {...register('lastName')} autoComplete="family-name" />
        </Field>
      </div>

      <Field label="Phone" error={errors.phone?.message} required hint="Also your username">
        <Input {...register('phone')} type="tel" placeholder="+355690000002" />
      </Field>

      <Field label="Email" error={errors.email?.message}>
        <Input {...register('email')} type="email" autoComplete="email" />
      </Field>

      <Field label="Password" error={errors.password?.message} required>
        <Input {...register('password')} type="password" autoComplete="new-password" />
      </Field>

      <Button type="submit" loading={isSubmitting} className="w-full">
        Create passenger account
      </Button>
    </form>
  )
}

function PartnerForm() {
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
        Your company starts as a draft. Add documents, submit for verification, and an
        administrator reviews it before you can take rides.
      </Alert>

      <div className="grid grid-cols-2 gap-3">
        <Field label="First name" error={errors.firstName?.message} required>
          <Input {...register('firstName')} autoComplete="given-name" />
        </Field>
        <Field label="Last name" error={errors.lastName?.message} required>
          <Input {...register('lastName')} autoComplete="family-name" />
        </Field>
      </div>

      <Field label="Phone" error={errors.phone?.message} required hint="Also your username">
        <Input {...register('phone')} type="tel" placeholder="+355690000003" />
      </Field>

      <Field label="Email" error={errors.email?.message}>
        <Input {...register('email')} type="email" autoComplete="email" />
      </Field>

      <Field label="Password" error={errors.password?.message} required>
        <Input {...register('password')} type="password" autoComplete="new-password" />
      </Field>

      <Field label="Legal name" error={errors.legalName?.message} required>
        <Input {...register('legalName')} placeholder="ABC Taxi SHPK" />
      </Field>

      <Field
        label="Display name"
        error={errors.displayName?.message}
        required
        hint="Shown to passengers in search results"
      >
        <Input {...register('displayName')} placeholder="ABC Taxi" />
      </Field>

      <Field label="NIPT" error={errors.nipt?.message} required>
        <Input {...register('nipt')} placeholder="K12345678A" />
      </Field>

      <Field label="Address" error={errors.address?.message} required>
        <Input {...register('address')} placeholder="Rruga e Durresit 45, Tirane" />
      </Field>

      <Button type="submit" loading={isSubmitting} className="w-full">
        Register taxi company
      </Button>
    </form>
  )
}
