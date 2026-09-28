import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { authApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import { Alert, Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { AuthShell } from './AuthShell'

const schema = z.object({
  phone: z.string().min(1, 'required'),
})

type FormValues = z.infer<typeof schema>

/**
 * Step one of recovering an account: ask for the code.
 *
 * The confirmation below is shown for every number that is validly formatted,
 * including numbers belonging to nobody, to suspended accounts, to somebody who
 * asked thirty seconds ago and to somebody who has asked five times today. That
 * is deliberate: this page is reachable without signing in, so anything it said
 * conditionally would make it a way of asking whether a given number is
 * registered with MR TAXI.
 *
 * The copy is careful about what it promises for the same reason. "If this
 * number belongs to an account" is not hedging — it is the only honest way to
 * describe an endpoint that will not say either way.
 *
 * The phone is carried to the next step in router state rather than the URL. A
 * phone number in a query string ends up in browser history, in any analytics
 * the page ever gains, and in a link somebody pastes into a chat.
 */
export function ForgotPasswordPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)
  /* The server failed in a way that is not the caller's doing. */
  const [failed, setFailed] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  const onSubmit = handleSubmit(async (values) => {
    setError(null)
    setFailed(false)

    const phone = values.phone.trim()

    try {
      await authApi.forgotPassword({ phone })

      /*
       * Straight on to the code, rather than a confirmation screen with a link.
       * The old flow had to pause here because what happened next was somebody
       * making a telephone call at an unknown time; a message arrives in
       * seconds, so the next thing the person needs is the field to type it in.
       */
      navigate('/reset-password', { state: { phone }, replace: true })
    } catch (caught) {
      /*
       * Only two answers from this endpoint are worth repeating to the person in
       * front of it: the number was malformed, or they have asked too often.
       * Both are about what they just did and neither says anything about whose
       * account it is.
       *
       * Anything else is the server having a problem, and its own words are the
       * wrong thing to show. A backend one deploy behind this frontend answers
       * 404 here, and "Resource not found" then appears under a form asking for
       * a phone number — which reads as a verdict on the number. It is not; it
       * is a verdict on the deployment.
       *
       * Not swallowed into a success either. Sending somebody to wait for a code
       * that was never requested is worse than telling them to try again.
       */
      const status = caught instanceof ApiError ? caught.status : 0

      if (status === 400 || status === 429) {
        setError(caught)
      } else {
        setError(null)
        setFailed(true)
      }
    }
  })

  return (
    <AuthShell
      title={t('auth.forgotTitle')}
      subtitle={t('auth.forgotSubtitle')}
      footer={
        <p>
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            {t('auth.backToSignIn')}
          </Link>
        </p>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error ? <ErrorMessage error={error} /> : null}
        {failed ? <Alert tone="danger">{t('auth.forgotFailed')}</Alert> : null}

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

        <p className="text-sm text-ink-500">{t('auth.forgotNoPhone')}</p>
      </form>
    </AuthShell>
  )
}
