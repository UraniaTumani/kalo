import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth, homePathFor } from '@/auth/AuthContext'
import { Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { AuthShell } from './AuthShell'

const schema = z.object({
  phone: z.string().min(1, 'required'),
  password: z.string().min(1, 'required'),
})

type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const { t } = useTranslation()
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
      title={t('auth.signInTitle')}
      subtitle={t('auth.signInSubtitle')}
      footer={
        <>
          <p>
            {t('auth.noAccount')}{' '}
            <Link to="/register" className="font-medium text-brand-700 hover:underline">
              {t('auth.createOne')}
            </Link>
          </p>
          <p className="mt-1">
            {t('common.or', 'Or')}{' '}
            <Link to="/" className="font-medium text-brand-700 hover:underline">
              {t('auth.browseAsGuest')}
            </Link>
          </p>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error ? <ErrorMessage error={error} /> : null}

        <Field label={t('auth.phone')} error={errors.phone ? t('validation.required') : undefined} required>
          <Input
            {...register('phone')}
            type="tel"
            autoComplete="username"
            placeholder={t('auth.phonePlaceholder')}
          />
        </Field>

        <Field label={t('auth.password')} error={errors.password ? t('validation.required') : undefined} required>
          <Input {...register('password')} type="password" autoComplete="current-password" />
        </Field>

        <Button type="submit" loading={isSubmitting} className="w-full">
          {t('common.signIn')}
        </Button>
      </form>
    </AuthShell>
  )
}
