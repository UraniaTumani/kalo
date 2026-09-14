import { clsx, type ClassValue } from 'clsx'
import i18n from '@/i18n'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDateTime(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString(i18n.language, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

/**
 * Short form for a live queue: the day and the time, no year.
 *
 * A dispatcher reads this column to see how long somebody has been waiting, and
 * the year is never the answer — it only made the column wide enough to push the
 * rest of the table out of view.
 */
export function formatQueueTime(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString(i18n.language, {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** A plain calendar date — expiry dates carry no useful time of day. */
export function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString(i18n.language, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
}

export function formatTime(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleTimeString(i18n.language, {
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatCurrency(value?: string | number | null) {
  if (value === null || value === undefined) return '—'
  const amount = typeof value === 'string' ? Number(value) : value
  if (Number.isNaN(amount)) return '—'
  return new Intl.NumberFormat(i18n.language, {
    style: 'currency',
    currency: 'ALL',
    maximumFractionDigits: 2,
  }).format(amount)
}

export function formatDistance(km?: number | null) {
  if (km === null || km === undefined) return '—'
  if (km < 1) return `${Math.round(km * 1000)} m`
  return `${km.toFixed(1)} km`
}

/** Turns REQUESTED / DRIVER_ARRIVING into "Requested" / "Driver arriving". */
export function humanise(value?: string | null) {
  if (!value) return '—'
  const lower = value.replace(/_/g, ' ').toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}
