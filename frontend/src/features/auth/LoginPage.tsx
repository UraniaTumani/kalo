import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth, homePathFor } from '@/auth/AuthContext'
import { Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { AuthShell } from './AuthShell'

const schema = z.object({
  phone: z.string().min(1, 'Phone is required'),
  password: z.string().min(1, 'Password is required'),
})

type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  const onSubmit = handleSubmit(async (values) => {
    setError(null)

    try {
      const user = await login(values.phone.trim(), values.password)
      navigate(homePathFor(user.role), { replace: true })
    } catch (caught) {
      setError(caught)
    }
  })

  return (
    <AuthShell
      title="Sign in"
      subtitle="Passengers, taxi companies and administrators sign in here."
      footer={
        <>
          <p>
            No account?{' '}
            <Link to="/register" className="font-medium text-brand-700 hover:underline">
              Create one
            </Link>
          </p>
          <p className="mt-1">
            Or{' '}
            <Link to="/" className="font-medium text-brand-700 hover:underline">
              look around as a guest
            </Link>
          </p>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error ? <ErrorMessage error={error} /> : null}

        <Field label="Phone" error={errors.phone?.message} required>
          <Input
            {...register('phone')}
            type="tel"
            autoComplete="username"
            placeholder="+355690000002"
          />
        </Field>

        <Field label="Password" error={errors.password?.message} required>
          <Input {...register('password')} type="password" autoComplete="current-password" />
        </Field>

        <Button type="submit" loading={isSubmitting} className="w-full">
          Sign in
        </Button>
      </form>
    </AuthShell>
  )
}
