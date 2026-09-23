import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { authApi } from '@/lib/api/endpoints'
import { Alert, Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { AuthShell } from './AuthShell'

const schema = z
  .object({
    phone: z.string().min(1, 'required'),
    code: z.string().min(1, 'required'),
    newPassword: z.string().min(8, 'tooShort'),
    confirmPassword: z.string().min(1, 'required'),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'mismatch',
  })

type FormValues = z.infer<typeof schema>

/**
 * Step two: redeem the code an administrator read out.
 *
 * The phone is asked for again rather than carried over from the previous
 * page, because the code is stored as a bcrypt hash and so cannot be looked up
 * by its own value — the server needs to know which account to check it
 * against. It also means somebody can arrive here directly, days later, from a
 * call they took on another device.
 *
 * Every failure comes back as the same message. That is the server's doing,
 * not a simplification here: a wrong code, an expired one, one already used
 * and a number belonging to nobody are deliberately indistinguishable.
 */
export function ResetPasswordPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)
  const [done, setDone] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  const onSubmit = handleSubmit(async (values) => {
    setError(null)

    try {
      await authApi.resetPassword({
        phone: values.phone.trim(),
        code: values.code.trim().toUpperCase(),
        newPassword: values.newPassword,
      })
      setDone(true)
    } catch (caught) {
      setError(caught)
    }
  })

  const passwordError = errors.newPassword
    ? t('auth.passwordTooShort')
    : undefined

  const confirmError = errors.confirmPassword
    ? errors.confirmPassword.message === 'mismatch'
      ? t('auth.passwordMismatch')
      : t('validation.required')
    : undefined

  return (
    <AuthShell
      title={t('auth.resetTitle')}
      subtitle={done ? undefined : t('auth.resetSubtitle')}
      footer={
        <p>
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            {t('auth.backToSignIn')}
          </Link>
        </p>
      }
    >
      {done ? (
        <div className="space-y-4">
          <Alert tone="success" title={t('auth.resetDoneTitle')}>
            {t('auth.resetDoneDetail')}
          </Alert>

          <Button onClick={() => navigate('/login')} className="w-full">
            {t('common.signIn')}
          </Button>
        </div>
      ) : (
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          {error ? <ErrorMessage error={error} /> : null}

          <Field
            label={t('auth.phone')}
            error={errors.phone ? t('validation.required') : undefined}
            required
          >
            <Input
              {...register('phone')}
              type="tel"
              autoComplete="username"
              placeholder={t('auth.phonePlaceholder')}
            />
          </Field>

          <Field
            label={t('auth.resetCode')}
            hint={t('auth.resetCodeHint')}
            error={errors.code ? t('validation.required') : undefined}
            required
          >
            <Input
              {...register('code')}
              autoComplete="one-time-code"
              inputMode="text"
              spellCheck={false}
              className="font-mono uppercase tracking-[0.2em]"
              placeholder="ABCD3F7H"
            />
          </Field>

          <Field
            label={t('auth.newPassword')}
            hint={t('auth.passwordHint')}
            error={passwordError}
            required
          >
            <Input {...register('newPassword')} type="password" autoComplete="new-password" />
          </Field>

          <Field label={t('auth.confirmPassword')} error={confirmError} required>
            <Input
              {...register('confirmPassword')}
              type="password"
              autoComplete="new-password"
            />
          </Field>

          <Button type="submit" loading={isSubmitting} className="w-full">
            {t('auth.resetSubmit')}
          </Button>
        </form>
      )}
    </AuthShell>
  )
}
