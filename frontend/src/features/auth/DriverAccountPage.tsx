import { useTranslation } from 'react-i18next'
import { useAuth } from '@/auth/AuthContext'
import { Button } from '@/components/ui'
import { AuthShell } from './AuthShell'

/** Account landing only: driver trip and availability controls are future work. */
export function DriverAccountPage() {
  const { t } = useTranslation()
  const { logout } = useAuth()
  return <AuthShell title={t('invitation.driverAccount')}>
    <p className="mb-4 text-sm text-ink-600">{t('invitation.driverAccountHint')}</p>
    <Button onClick={() => { void logout() }}>{t('invitation.signOut')}</Button>
  </AuthShell>
}
