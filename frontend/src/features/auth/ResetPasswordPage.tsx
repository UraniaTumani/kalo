import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { authApi } from '@/lib/api/endpoints'
import { Alert, Button, Field, Input } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { AuthShell } from './AuthShell'

/**
 * Matches the server's cooldown. A countdown that finishes before the server
 * will accept another request would hand somebody a button that silently does
 * nothing, which is worse than a button that is visibly not ready yet.
 */
const RESEND_COOLDOWN_SECONDS = 60

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
 * Step two: redeem the code and set the new password.
 *
 * Two visible steps, one request. The code is entered, then the password, and
 * both go to the server together — verifying the code in its own round trip
 * would need a short-lived token between the two steps, which is a second secret
 * to store, expire, single-use and leak. Splitting the form gets the useful half
 * of that (nobody types a password twice before finding out the code was wrong)
 * for none of the cost.
 *
 * The phone arrives in router state from the previous page and is editable here
 * anyway, because a bcrypt hash cannot be looked up by its own value — the server
 * needs to know which account to check the code against. It also means somebody
 * can arrive here directly, from a message they read on another device.
 *
 * Every failure comes back as the same message. That is the server's doing, not a
 * simplification here: a wrong code, an expired one, one already used and a
 * number belonging to nobody are deliberately indistinguishable.
 */
export function ResetPasswordPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()

  /* Set when the previous page has just asked for a code. */
  const arrivedWithPhone = (location.state as { phone?: string } | null)?.phone ?? ''

  const [stage, setStage] = useState<'code' | 'password'>('code')
  const [error, setError] = useState<unknown>(null)
  const [done, setDone] = useState(false)
  const [resent, setResent] = useState(false)

  /*
   * Starts running only if a code was just sent. Somebody arriving at this page
   * directly has not used a send, so making them wait a minute before they can
   * ask for one would be a countdown against nothing.
   */
  const [cooldown, setCooldown] = useState(
    arrivedWithPhone ? RESEND_COOLDOWN_SECONDS : 0,
  )

  const {
    register,
    handleSubmit,
    trigger,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { phone: arrivedWithPhone },
  })

  useEffect(() => {
    if (cooldown <= 0) {
      return
    }

    const timer = window.setTimeout(() => setCooldown((seconds) => seconds - 1), 1000)

    return () => window.clearTimeout(timer)
  }, [cooldown])

  /**
   * Advances to the password fields.
   *
   * Validates only the two fields on this stage. Running the whole schema would
   * mark the password fields as invalid before they have been shown, which puts
   * red text under inputs the person has not reached yet.
   */
  const continueToPassword = async () => {
    const valid = await trigger(['phone', 'code'])

    if (valid) {
      setError(null)
      setResent(false)
      setStage('password')
    }
  }

  /**
   * Asks for another code.
   *
   * The same endpoint as the first request, because to the server it is the same
   * request: it supersedes the previous code rather than adding a second live
   * one. Failures are swallowed on purpose — the endpoint answers identically
   * whatever happens, so there is nothing true to report beyond "we asked
   * again", and the cooldown starts either way so a failure cannot be used to
   * hammer it.
   */
  const resend = async () => {
    const phone = getValues('phone').trim()

    if (!phone || cooldown > 0) {
      return
    }

    setCooldown(RESEND_COOLDOWN_SECONDS)
    setError(null)

    try {
      await authApi.forgotPassword({ phone })
    } finally {
      setResent(true)
    }
  }

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
      /*
       * Back to the code field. The refusal is indistinguishable by design, but
       * a wrong code is overwhelmingly the likeliest cause, and leaving somebody
       * on the password step with a generic error gives them nothing to change.
       */
      setStage('code')
      setError(caught)
    }
  })

  const passwordError = errors.newPassword ? t('auth.passwordTooShort') : undefined

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
          {resent && !error ? (
            <Alert tone="info">{t('auth.resetResent')}</Alert>
          ) : null}

          {/*
            * Both stages stay mounted, with the inactive one hidden. Unmounting
            * the code field on the way to the password step would drop its value
            * out of the form, and the whole point of this shape is that one
            * request carries both.
            */}
          <div className={stage === 'code' ? 'space-y-4' : 'hidden'}>
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
              {/*
                * inputMode="numeric" brings up the digit keypad rather than a
                * full keyboard, and autoComplete="one-time-code" is what lets
                * iOS and Android offer the code straight from the message —
                * which is the difference between two taps and reading a number
                * off a notification and typing it.
                *
                * maxLength is 8 rather than 6 because an administrator-issued
                * fallback code is eight characters, and both are redeemed here.
                */}
              <Input
                {...register('code')}
                inputMode="numeric"
                autoComplete="one-time-code"
                spellCheck={false}
                maxLength={8}
                className="text-center font-mono text-lg uppercase tracking-[0.4em]"
                placeholder="000000"
              />
            </Field>

            <Button type="button" onClick={continueToPassword} className="w-full">
              {t('common.continue')}
            </Button>

            <div className="text-center text-sm">
              {cooldown > 0 ? (
                <span className="text-ink-400">
                  {t('auth.resetResendIn', { seconds: cooldown })}
                </span>
              ) : (
                <button
                  type="button"
                  onClick={resend}
                  className="font-medium text-brand-700 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink-400"
                >
                  {t('auth.resetResend')}
                </button>
              )}
            </div>
          </div>

          <div className={stage === 'password' ? 'space-y-4' : 'hidden'}>
            <Field
              label={t('auth.newPassword')}
              hint={t('auth.passwordHint')}
              error={passwordError}
              required
            >
              <Input
                {...register('newPassword')}
                type="password"
                autoComplete="new-password"
              />
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

            <div className="text-center text-sm">
              <button
                type="button"
                onClick={() => setStage('code')}
                className="font-medium text-ink-500 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink-400"
              >
                {t('auth.resetBackToCode')}
              </button>
            </div>
          </div>
        </form>
      )}
    </AuthShell>
  )
}
