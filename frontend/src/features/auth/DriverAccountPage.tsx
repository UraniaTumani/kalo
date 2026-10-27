import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '@/auth/AuthContext'
import { Button } from '@/components/ui'
import { api } from '@/lib/api/client'
import { watchPosition, type Position } from '@/lib/geolocation'
import { isFreshGpsFix } from '@/lib/gpsFreshness'
import { AuthShell } from './AuthShell'

type State = 'stopped' | 'waiting' | 'sent' | 'error'

export function DriverAccountPage() {
  const { t } = useTranslation()
  const { logout } = useAuth()
  const [tracking, setTracking] = useState(false)
  const [state, setState] = useState<State>('stopped')
  const [lastSent, setLastSent] = useState<number | null>(null)
  const active = useRef(false)
  const busy = useRef(false)

  useEffect(() => {
    if (!tracking) return
    let position: Position | null = null
    let alive = true
    let lastAttempt = 0
    active.current = true
    async function send() {
      if (!alive || !active.current || busy.current || !position) return
      if (!isFreshGpsFix(position.timestamp, Date.now())) { setState('error'); return }
      busy.current = true
      lastAttempt = Date.now()
      try {
        await api.put('/api/v1/driver/me/location', { latitude: position.lat, longitude: position.lng })
        if (alive && active.current) { setLastSent(Date.now()); setState('sent') }
      } catch {
        if (alive && active.current) setState('error')
      } finally { busy.current = false }
    }
    const stop = watchPosition(
      fix => { position = fix; if (Date.now() - lastAttempt >= 25000) void send() },
      () => { position = null; if (alive) setState('error') },
    )
    const timer = window.setInterval(() => { void send() }, 25000)
    return () => { alive = false; active.current = false; position = null; window.clearInterval(timer); stop() }
  }, [tracking])

  function stopTracking() {
    active.current = false
    setTracking(false)
    setState('stopped')
    setLastSent(null)
  }
  function startTracking() {
    if (active.current) return
    active.current = true
    setState('waiting')
    setTracking(true)
  }
  return <AuthShell title={t('invitation.driverAccount')}>
    <p className="mb-4 text-sm text-ink-600">{t('invitation.driverAccountHint')}</p>
    <div className="mb-4 space-y-3">
      <p className="text-sm text-ink-600">{t('driverGps.explanation')}</p>
      <p role="status" className="text-sm text-ink-600">{t(`driverGps.${state}`)}{lastSent !== null && state === 'sent' ? ` ${new Date(lastSent).toLocaleTimeString()}` : ''}</p>
      {tracking ? <Button onClick={stopTracking}>{t('driverGps.stop')}</Button> : <Button onClick={startTracking}>{t('driverGps.start')}</Button>}
      <p className="text-sm text-ink-600">{t('driverGps.browserLimit')}</p>
    </div>
    <Button onClick={() => { stopTracking(); void logout() }}>{t('invitation.signOut')}</Button>
  </AuthShell>
}
