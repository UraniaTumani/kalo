import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { DriverResponse } from '@/lib/api/types'
import { partnerApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import { driverInvitationsEnabled } from '@/lib/driverInvitation'
import { Button } from '@/components/ui'
import { ConfirmDialog } from '@/components/ConfirmDialog'

export function DriverInviteButton({ driver }: { driver: DriverResponse }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const sending = useRef(false)
  if (!driverInvitationsEnabled || driver.status !== 'ACTIVE') return null

  async function invite() {
    if (sending.current) return
    sending.current = true
    setBusy(true)
    setMessage('')
    try {
      await partnerApi.inviteDriver(driver.id)
      setMessage(t('invitation.queued'))
    } catch (caught) {
      setMessage(t(caught instanceof ApiError && caught.status === 400
        ? 'invitation.notEligible' : 'invitation.queueFailed'))
    } finally { sending.current = false; setBusy(false); setOpen(false) }
  }
  return <div className="max-w-xs">
    <Button size="sm" variant="secondary" onClick={() => setOpen(true)} disabled={busy}>{t('invitation.invite')}</Button>
    {message && <p role="status" className="mt-1 text-xs text-ink-600">{message}</p>}
    <ConfirmDialog open={open} title={t('invitation.invite')}
      description={t('invitation.confirmSend', { phone: driver.phone })}
      confirmLabel={t('invitation.invite')} loading={busy}
      onConfirm={() => { void invite() }} onCancel={() => { if (!busy) setOpen(false) }} />
  </div>
}
