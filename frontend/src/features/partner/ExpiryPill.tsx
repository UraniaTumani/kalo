import { useTranslation } from 'react-i18next'
import { AlertTriangle, CheckCircle2, CircleSlash } from 'lucide-react'
import { Badge } from '@/components/ui'
import { cn, formatDate } from '@/lib/utils'

/** Far enough ahead that a company can still renew without taking a car off the road. */
const SOON_DAYS = 30

export type ExpiryState = 'expired' | 'soon' | 'valid' | 'unset'

export function expiryState(value?: string | null): ExpiryState {
  if (!value) return 'unset'

  const due = new Date(value)
  if (Number.isNaN(due.getTime())) return 'unset'

  const days = Math.floor((due.getTime() - Date.now()) / 86_400_000)

  if (days < 0) return 'expired'
  if (days <= SOON_DAYS) return 'soon'
  return 'valid'
}

/** The worst of several dates — a vehicle is only as roadworthy as its weakest paper. */
export function worstExpiry(values: (string | null | undefined)[]): ExpiryState {
  const states = values.map(expiryState)
  if (states.includes('expired')) return 'expired'
  if (states.includes('soon')) return 'soon'
  if (states.includes('unset')) return 'unset'
  return 'valid'
}

/**
 * One document's expiry, said at the weight it deserves.
 *
 * A date on its own makes the reader do the arithmetic — is 2026-10-02 a problem
 * today or not? Expired and expiring-soon are the only two answers a fleet
 * manager is scanning for, so they get a colour and a word; everything else
 * stays quiet so those two stand out.
 */
export function ExpiryPill({ label, value }: { label: string; value?: string | null }) {
  const { t } = useTranslation()
  const state = expiryState(value)

  const tone =
    state === 'expired' ? 'danger' : state === 'soon' ? 'warning' : state === 'unset' ? 'neutral' : 'neutral'

  return (
    <div className="flex items-center justify-between gap-2 text-xs">
      <span className="text-ink-500">{label}</span>

      {state === 'unset' ? (
        <span className="inline-flex items-center gap-1 text-ink-400">
          <CircleSlash className="size-3" aria-hidden />
          {t('partner.noExpirySet')}
        </span>
      ) : state === 'valid' ? (
        <span className="tnum inline-flex items-center gap-1 text-ink-600">
          <CheckCircle2 className="size-3 text-good-500" aria-hidden />
          {formatDate(value)}
        </span>
      ) : (
        <Badge tone={tone}>
          <AlertTriangle className="size-3" aria-hidden />
          <span className="tnum">
            {t(state === 'expired' ? 'partner.expired' : 'partner.expiringSoon')} ·{' '}
            {formatDate(value)}
          </span>
        </Badge>
      )}
    </div>
  )
}

/** A single summary for a whole vehicle, for the desktop table's narrow column. */
export function PaperworkSummary({ values }: { values: (string | null | undefined)[] }) {
  const { t } = useTranslation()
  const state = worstExpiry(values)

  if (state === 'expired' || state === 'soon') {
    return (
      <Badge tone={state === 'expired' ? 'danger' : 'warning'}>
        <AlertTriangle className="size-3" aria-hidden />
        {t(state === 'expired' ? 'partner.expired' : 'partner.expiringSoon')}
      </Badge>
    )
  }

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-xs',
        state === 'unset' ? 'text-ink-400' : 'text-ink-500',
      )}
    >
      {state === 'unset' ? (
        <CircleSlash className="size-3" aria-hidden />
      ) : (
        <CheckCircle2 className="size-3 text-good-500" aria-hidden />
      )}
      {t(state === 'unset' ? 'partner.noExpirySet' : 'partner.allValid')}
    </span>
  )
}
