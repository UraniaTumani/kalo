import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui'
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
const activeStatuses = ['DRIVER_ASSIGNED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'IN_PROGRESS']

export function DriverCurrentRideCard() {
  const { t } = useTranslation()
  const [result, setResult] = useState<Result>({ kind: 'loading' })
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let mounted = true
    let pending = false
    async function load() {
      if (pending || document.visibilityState !== 'visible') return
      pending = true
      try {
        const ride = await api.get<Ride | undefined>('/api/v1/driver/me/ride')
        if (!mounted) return
        if (ride === undefined) setResult({ kind: 'empty' })
        else if (Number.isSafeInteger(ride.rideId) && activeStatuses.includes(ride.status)) setResult({ kind: 'ride', ride })
        else setResult({ kind: 'error' })
      } catch {
        // Do not keep displaying an old ride after a failed refresh.
        if (mounted) setResult({ kind: 'error' })
      } finally { pending = false }
    }
    void load()
    const timer = window.setInterval(() => { void load() }, 15000)
    const visible = () => { void load() }
    document.addEventListener('visibilitychange', visible)
    return () => { mounted = false; window.clearInterval(timer); document.removeEventListener('visibilitychange', visible) }
  }, [refresh])

  function point(address: string | null, latitude: number, longitude: number) {
    return address?.trim() || `${latitude}, ${longitude}`
  }
  return <section aria-label={t('driverRide.title')} className="mb-6 space-y-3 rounded-lg border border-ink-200 p-4">
    <h2 className="font-semibold">{t('driverRide.title')}</h2>
    {result.kind === 'loading' && <p>{t('driverRide.loading')}</p>}
    {result.kind === 'empty' && <p>{t('driverRide.empty')}</p>}
    {result.kind === 'error' && <p role="alert">{t('driverRide.error')}</p>}
    {result.kind === 'ride' && <div className="space-y-3 break-words text-sm">
      <p>{t(`driverRide.${result.ride.status}`)}</p>
      <dl className="space-y-2">
        <div><dt className="font-semibold">{t('driverRide.pickup')}</dt><dd>{point(result.ride.pickupAddress, result.ride.pickupLatitude, result.ride.pickupLongitude)}</dd></div>
        <div><dt className="font-semibold">{t('driverRide.destination')}</dt><dd>{point(result.ride.destinationAddress, result.ride.destinationLatitude, result.ride.destinationLongitude)}</dd></div>
      </dl>
      <p className="text-ink-600">{t('driverRide.refreshHint')}</p>
    </div>}
    <Button disabled={result.kind === 'loading'} onClick={() => { setResult({ kind: 'loading' }); setRefresh(value => value + 1) }}>{t('driverRide.refresh')}</Button>
  </section>
}
