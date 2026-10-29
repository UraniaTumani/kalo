import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { api } from '@/lib/api/client'

type Ride = {
  rideId: number
  status: 'DRIVER_ASSIGNED' | 'DRIVER_ARRIVING' | 'DRIVER_ARRIVED' | 'IN_PROGRESS'
  pickupAddress: string | null
  pickupLatitude: number
  pickupLongitude: number
  destinationAddress: string | null
  destinationLatitude: number
  destinationLongitude: number
}
type Result = { kind: 'loading' } | { kind: 'empty' } | { kind: 'error' } | { kind: 'ride'; ride: Ride }
type Action = 'driver-arriving' | 'driver-arrived' | 'start'
const nextAction: Partial<Record<Ride['status'], Action>> = {
  DRIVER_ASSIGNED: 'driver-arriving', DRIVER_ARRIVING: 'driver-arrived', DRIVER_ARRIVED: 'start',
}
const activeStatuses = ['DRIVER_ASSIGNED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'IN_PROGRESS']

export function DriverCurrentRideCard() {
  const { t } = useTranslation()
  const [result, setResult] = useState<Result>({ kind: 'loading' })
  const [refresh, setRefresh] = useState(0)
  const [confirmation, setConfirmation] = useState<{ rideId: number; status: Ride['status']; action: Action } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [actionError, setActionError] = useState(false)
  const submittingRef = useRef(false)
  const epoch = useRef(0)
  useEffect(() => {
    let mounted = true
    let pending = false
    async function load() {
      if (pending || submittingRef.current || document.visibilityState !== 'visible') return
      const version = epoch.current
      pending = true
      try {
        const ride = await api.get<Ride | undefined>('/api/v1/driver/me/ride')
        if (!mounted || version !== epoch.current) return
        if (ride === undefined) setResult({ kind: 'empty' })
        else if (Number.isSafeInteger(ride.rideId) && activeStatuses.includes(ride.status)) setResult({ kind: 'ride', ride })
        else setResult({ kind: 'error' })
      } catch {
        // Do not keep displaying an old ride after a failed refresh.
        if (mounted && version === epoch.current) setResult({ kind: 'error' })
      } finally { pending = false }
    }
    void load()
    const timer = window.setInterval(() => { void load() }, 15000)
    const visible = () => { void load() }
    document.addEventListener('visibilitychange', visible)
    return () => { mounted = false; window.clearInterval(timer); document.removeEventListener('visibilitychange', visible) }
  }, [refresh])

  async function confirmAction() {
    if (submittingRef.current || !confirmation) return
    if (result.kind !== 'ride' || result.ride.rideId !== confirmation.rideId || result.ride.status !== confirmation.status) {
      setConfirmation(null)
      setActionError(true)
      return
    }
    submittingRef.current = true
    epoch.current++
    setSubmitting(true)
    setActionError(false)
    try {
      await api.post(`/api/v1/driver/me/rides/${confirmation.rideId}/${confirmation.action}`)
    } catch {
      setActionError(true)
    } finally {
      submittingRef.current = false
      setSubmitting(false)
      setConfirmation(null)
      // Read authoritative state even after failure: the POST may have committed.
      setResult({ kind: 'loading' })
      setRefresh(value => value + 1)
    }
  }

  function point(address: string | null, latitude: number, longitude: number) {
    return address?.trim() || `${latitude}, ${longitude}`
  }
  return <section aria-label={t('driverRide.title')} className="mb-6 space-y-3 rounded-lg border border-ink-200 p-4">
    <h2 className="font-semibold">{t('driverRide.title')}</h2>
    {actionError && <p role="alert">{t('driverRide.actionError')}</p>}
    {result.kind === 'loading' && <p>{t('driverRide.loading')}</p>}
    {result.kind === 'empty' && <p>{t('driverRide.empty')}</p>}
    {result.kind === 'error' && <p role="alert">{t('driverRide.error')}</p>}
    {result.kind === 'ride' && <div className="space-y-3 break-words text-sm">
      <p>{t(`driverRide.${result.ride.status}`)}</p>
      <dl className="space-y-2">
        <div><dt className="font-semibold">{t('driverRide.pickup')}</dt><dd>{point(result.ride.pickupAddress, result.ride.pickupLatitude, result.ride.pickupLongitude)}</dd></div>
        <div><dt className="font-semibold">{t('driverRide.destination')}</dt><dd>{point(result.ride.destinationAddress, result.ride.destinationLatitude, result.ride.destinationLongitude)}</dd></div>
      </dl>
      {nextAction[result.ride.status] && <Button disabled={submitting} onClick={() => {
        const action = nextAction[result.ride.status]
        if (action) setConfirmation({ rideId: result.ride.rideId, status: result.ride.status, action })
      }}>{t(`driverRide.action.${nextAction[result.ride.status]}`)}</Button>}
      <p className="text-ink-600">{t('driverRide.refreshHint')}</p>
    </div>}
    <Button disabled={submitting || result.kind === 'loading'} onClick={() => { setResult({ kind: 'loading' }); setRefresh(value => value + 1) }}>{t('driverRide.refresh')}</Button>
    <ConfirmDialog open={confirmation !== null} title={t('driverRide.confirmTitle')}
      description={t('driverRide.confirmDescription')}
      confirmLabel={confirmation ? t(`driverRide.action.${confirmation.action}`) : ''}
      loading={submitting} onConfirm={() => { void confirmAction() }} onCancel={() => { if (!submittingRef.current) setConfirmation(null) }} />
  </section>
}
