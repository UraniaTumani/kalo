import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { authApi } from '@/lib/api/endpoints'
import { Alert, Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { AuthShell } from './AuthShell'

const schema = z.object({
  phone: z.string().min(1, 'required'),
})

type FormValues = z.infer<typeof schema>

/**
 * Step one of recovering an account.
 *
 * The confirmation below is shown for every number that is validly formatted,
 * including numbers belonging to nobody, to suspended accounts, and to people
 * who have already asked three times this hour. That is deliberate: this page
 * is reachable without signing in, so anything it said conditionally would
 * make it a way of asking whether a given number is registered with KALO.
 *
 * It also tells the truth about what happens next. KALO has no way to send a
 * message, so nobody is told to check their phone for one — they are told
 * somebody will ring them, which is what actually happens.
 */
export function ForgotPasswordPage() {
  const { t } = useTranslation()
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  const onSubmit = handleSubmit(async (values) => {
    setError(null)

    try {
      await authApi.forgotPassword({ phone: values.phone.trim() })
      setSubmitted(true)
    } catch (caught) {
      /*
       * Only a malformed number or a rate limit reaches here; the server
       * accepts everything else regardless of whether the account exists.
       */
      setError(caught)
    }
  })

  return (
    <AuthShell
      title={t('auth.forgotTitle')}
      subtitle={submitted ? undefined : t('auth.forgotSubtitle')}
      footer={
        <p>
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            {t('auth.backToSignIn')}
          </Link>
        </p>
      }
    >
      {submitted ? (
        <div className="space-y-4">
          <Alert tone="success">{t('auth.forgotSubmitted')}</Alert>

          <p className="text-sm text-ink-500">{t('auth.forgotSubmittedDetail')}</p>

          <Link
            to="/reset-password"
            className="inline-flex h-10 w-full items-center justify-center rounded-xl bg-white px-4 text-sm font-semibold text-ink-800 ring-1 ring-inset ring-ink-200 transition-colors hover:bg-ink-50 hover:ring-ink-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink-400"
          >
            {t('auth.haveCode')}
          </Link>
        </div>
      ) : (
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          {error ? <ErrorMessage error={error} /> : null}

          <Field
            label={t('auth.phone')}
            error={errors.phone ? t('validation.required') : undefined}
            hint={t('auth.forgotPhoneHint')}
            required
          >
            <Input
              {...register('phone')}
              type="tel"
              autoComplete="username"
              placeholder={t('auth.phonePlaceholder')}
            />
          </Field>

          <Button type="submit" loading={isSubmitting} className="w-full">
            {t('auth.forgotSubmit')}
          </Button>
        </form>
      )}
    </AuthShell>
  )
}
