import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { authApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import { activationToken, clearActivationToken, validActivationPassword } from '@/lib/driverInvitation'
import { Button, Field, Input } from '@/components/ui'
import { AuthShell } from './AuthShell'

export function DriverActivationPage() {
  const { t } = useTranslation()
  const [token, setToken] = useState(activationToken)
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!token || sending.current) return
    if (!validActivationPassword(password)) { setError(t('invitation.passwordRule')); return }
    if (password !== confirmation) { setError(t('invitation.passwordMismatch')); return }
    sending.current = true
    setBusy(true)
    setError('')
    try {
      await authApi.activateDriver({ token, password })
      clearActivationToken()
      setToken(null)
      setPassword('')
      setConfirmation('')
      setDone(true)
    } catch (caught) {
      // Fixed messages: do not render raw exception details containing credentials.
      setError(t(caught instanceof ApiError && caught.status === 429
        ? 'invitation.tooMany' : 'invitation.activationFailed'))
    } finally { sending.current = false; setBusy(false) }
  }

  return <AuthShell title={t('invitation.activateTitle')} subtitle={t('invitation.activateHint')}>
    {done ? <div role="status" className="space-y-4">
      <p>{t('invitation.activated')}</p><Link className="text-brand-700 underline" to="/login">{t('common.signIn')}</Link>
    </div> : !token ? <p role="alert">{t('invitation.invalidLink')}</p> :
      <form onSubmit={submit} className="space-y-4">
        {error && <p role="alert" className="text-sm text-bad-600">{error}</p>}
        <Field label={t('auth.password')} required>
          <Input type="password" autoComplete="new-password" required minLength={8} maxLength={72}
            value={password} onChange={e => setPassword(e.target.value)} disabled={busy} />
        </Field>
        <p className="text-xs text-ink-500">{t('invitation.passwordRule')}</p>
        <Field label={t('invitation.confirmPassword')} required>
          <Input type="password" autoComplete="new-password" required maxLength={72}
            value={confirmation} onChange={e => setConfirmation(e.target.value)} disabled={busy} />
        </Field>
        <Button type="submit" loading={busy} className="w-full">{t('invitation.activate')}</Button>
      </form>}
  </AuthShell>
}
