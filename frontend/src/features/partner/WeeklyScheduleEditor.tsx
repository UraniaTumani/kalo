import { useTranslation } from 'react-i18next'
import { Copy, Moon, Sun } from 'lucide-react'
import type { DayOfWeek, OperatingHoursResponse } from '@/lib/api/types'
import { Button, Input } from '@/components/ui'
import { cn } from '@/lib/utils'

export const DAYS: DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

/** Equal open and close means the company is open around the clock. */
export const isAllDay = (entry: OperatingHoursResponse) =>
  !entry.closed && !!entry.openTime && entry.openTime === entry.closeTime

/** A close time earlier than the open time runs past midnight. */
export const isOvernight = (entry: OperatingHoursResponse) =>
  !entry.closed &&
  !!entry.openTime &&
  !!entry.closeTime &&
  entry.closeTime < entry.openTime

/**
 * One row per day, each a self-contained card.
 *
 * The previous version put a checkbox and two bare time inputs on one line for
 * all seven days, which made it hard to tell at a glance when the company was
 * actually open. Here a closed day visibly recedes, and the two cases people
 * get wrong — open around the clock, and closing after midnight — are spelled
 * out in words instead of being inferred from two timestamps.
 */
export function WeeklyScheduleEditor({
  hours,
  onChange,
  disabled = false,
}: {
  hours: OperatingHoursResponse[]
  onChange: (next: OperatingHoursResponse[]) => void
  disabled?: boolean
}) {
  const { t } = useTranslation()

  function update(day: DayOfWeek, patch: Partial<OperatingHoursResponse>) {
    onChange(hours.map((entry) => (entry.dayOfWeek === day ? { ...entry, ...patch } : entry)))
  }

  function copyToAll(source: OperatingHoursResponse) {
    onChange(
      hours.map((entry) => ({
        ...entry,
        closed: source.closed,
        openTime: source.openTime,
        closeTime: source.closeTime,
      })),
    )
  }

  return (
    <div className="space-y-2">
      {DAYS.map((day) => {
        const entry = hours.find((candidate) => candidate.dayOfWeek === day)
        if (!entry) return null

        const open = !entry.closed
        const missingTime = open && (!entry.openTime || !entry.closeTime)

        return (
          <div
            key={day}
            className={cn(
              'rounded-xl border px-3 py-3 transition',
              open ? 'border-ink-200 bg-white' : 'border-ink-200/70 bg-ink-50',
            )}
          >
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span
                className={cn(
                  'w-24 shrink-0 text-sm font-medium',
                  open ? 'text-ink-900' : 'text-ink-400',
                )}
              >
                {t(`availability.days.${day}`)}
              </span>

              {/* Open/closed is the primary decision, so it leads the row. */}
              <label className="inline-flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  className="size-4 accent-brand-600"
                  checked={open}
                  disabled={disabled}
                  onChange={(event) => update(day, { closed: !event.target.checked })}
                  aria-label={`${t(`availability.days.${day}`)} — ${t('availability.open')}`}
                />
                <span
                  className={cn(
                    'text-xs font-medium',
                    open ? 'text-emerald-700' : 'text-ink-400',
                  )}
                >
                  {open ? t('availability.open') : t('availability.closed')}
                </span>
              </label>

              {open && (
                <div className="ml-auto flex flex-wrap items-center gap-2">
                  <label className="flex items-center gap-1.5">
                    <span className="text-xs text-ink-500">{t('availability.opensAt')}</span>
                    <Input
                      type="time"
                      className="w-28"
                      disabled={disabled}
                      value={entry.openTime ?? ''}
                      onChange={(event) => update(day, { openTime: event.target.value })}
                      aria-label={`${t(`availability.days.${day}`)} — ${t('availability.opensAt')}`}
                    />
                  </label>

                  <label className="flex items-center gap-1.5">
                    <span className="text-xs text-ink-500">{t('availability.closesAt')}</span>
                    <Input
                      type="time"
                      className="w-28"
                      disabled={disabled}
                      value={entry.closeTime ?? ''}
                      onChange={(event) => update(day, { closeTime: event.target.value })}
                      aria-label={`${t(`availability.days.${day}`)} — ${t('availability.closesAt')}`}
                    />
                  </label>

                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={disabled}
                    title={t('availability.copyToAll')}
                    aria-label={t('availability.copyToAll')}
                    onClick={() => copyToAll(entry)}
                  >
                    <Copy className="size-3.5" aria-hidden />
                  </Button>
                </div>
              )}
            </div>

            {/* Spell out the two cases that are easy to misread. */}
            {isAllDay(entry) && (
              <p className="mt-2 flex items-center gap-1.5 text-xs text-brand-700">
                <Sun className="size-3.5" aria-hidden />
                {t('availability.allDay')}
              </p>
            )}

            {isOvernight(entry) && (
              <p className="mt-2 flex items-center gap-1.5 text-xs text-amber-700">
                <Moon className="size-3.5" aria-hidden />
                {t('availability.overnight')}
              </p>
            )}

            {missingTime && (
              <p className="mt-2 text-xs text-red-600">{t('validation.required')}</p>
            )}
          </div>
        )
      })}

      <p className="pt-1 text-xs text-ink-500">{t('availability.openAllDayHint')}</p>
    </div>
  )
}
