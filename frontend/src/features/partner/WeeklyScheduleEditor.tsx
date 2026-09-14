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
 * The week as seven aligned rows.
 *
 * A schedule is read down a column — "when do we open on Thursday" — so the day,
 * the toggle and the two times hold the same position on every row rather than
 * flowing to wherever the previous row left off. A closed day recedes instead of
 * disappearing, because the question it answers is still part of the week.
 *
 * The two cases people get wrong are spelled out in words: open around the clock
 * (equal times) and closing after midnight (close before open) both look like
 * mistakes in a pair of timestamps until something says otherwise.
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
    <div className="space-y-1.5">
      {DAYS.map((day) => {
        const entry = hours.find((candidate) => candidate.dayOfWeek === day)
        if (!entry) return null

        const open = !entry.closed
        const missingTime = open && (!entry.openTime || !entry.closeTime)
        const dayName = t(`availability.days.${day}`)

        return (
          <div
            key={day}
            className={cn(
              'rounded-xl border px-3 py-2.5 transition-colors',
              open ? 'border-ink-200/70 bg-white' : 'border-ink-200/40 bg-ink-50/60',
            )}
          >
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2 sm:flex-nowrap">
              <span
                className={cn(
                  'w-[5.5rem] shrink-0 text-sm font-semibold',
                  open ? 'text-ink-900' : 'text-ink-400',
                )}
              >
                {dayName}
              </span>

              {/*
                A switch rather than a tickbox: this is the state of a thing, not
                an item being selected, and the shape should say which. The input
                stays a real checkbox underneath so the keyboard and screen
                readers get the behaviour they already know.
              */}
              <label className="inline-flex shrink-0 cursor-pointer items-center gap-2">
                <span className="relative inline-flex">
                  <input
                    type="checkbox"
                    className="peer sr-only"
                    checked={open}
                    disabled={disabled}
                    onChange={(event) => update(day, { closed: !event.target.checked })}
                    aria-label={`${dayName} — ${t('availability.open')}`}
                  />
                  <span
                    aria-hidden
                    className={cn(
                      'block h-5 w-9 rounded-full bg-ink-300 transition-colors',
                      'peer-checked:bg-good-500',
                      'peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand-600',
                      'peer-disabled:opacity-50',
                    )}
                  />
                  <span
                    aria-hidden
                    className="pointer-events-none absolute left-0.5 top-0.5 size-4 rounded-full bg-white shadow-sm transition-transform peer-checked:translate-x-4"
                  />
                </span>

                <span
                  className={cn(
                    'w-14 text-xs font-semibold',
                    open ? 'text-good-600' : 'text-ink-400',
                  )}
                >
                  {open ? t('availability.open') : t('availability.closed')}
                </span>
              </label>

              {open ? (
                /*
                 * A range, not two labelled fields. "Opens"/"Closes" spelled out
                 * beside each input pushed the row onto three lines in a side
                 * column and made a seven-day week enormous; a dash says the same
                 * thing, and the labels survive for screen readers.
                 */
                <div className="ml-auto flex items-center gap-1.5">
                  <Input
                    type="time"
                    className="tnum w-[6.75rem] px-2"
                    disabled={disabled}
                    value={entry.openTime ?? ''}
                    onChange={(event) => update(day, { openTime: event.target.value })}
                    aria-label={`${dayName} — ${t('availability.opensAt')}`}
                  />

                  <span aria-hidden className="text-xs text-ink-400">
                    –
                  </span>

                  <Input
                    type="time"
                    className="tnum w-[6.75rem] px-2"
                    disabled={disabled}
                    value={entry.closeTime ?? ''}
                    onChange={(event) => update(day, { closeTime: event.target.value })}
                    aria-label={`${dayName} — ${t('availability.closesAt')}`}
                  />

                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    className="shrink-0"
                    disabled={disabled}
                    title={t('availability.copyToAll')}
                    aria-label={t('availability.copyToAll')}
                    onClick={() => copyToAll(entry)}
                  >
                    <Copy className="size-3.5" aria-hidden />
                  </Button>
                </div>
              ) : (
                /*
                 * Holds the row's height steady as days are switched on and off,
                 * so the week does not jump under the cursor mid-edit.
                 */
                <span className="ml-auto hidden h-10 sm:block" aria-hidden />
              )}
            </div>

            {/*
              Both of these states exist in the database — the seeder writes
              equal times straight through the repository — but the API refuses
              to accept either: it requires the closing time to be strictly after
              the opening one. So a partner can open this editor, see a day the
              product created, and be unable to save the form at all until they
              change it.

              Until the backend rule changes, the honest thing is to name the
              state and say plainly that it will not save, rather than describe
              it as a feature the way the old copy did.
            */}
            {isAllDay(entry) && (
              <p className="mt-1.5 flex items-start gap-1.5 pl-[5.5rem] text-xs font-medium text-warn-600">
                <Sun className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                {t('availability.allDayBlocked')}
              </p>
            )}

            {isOvernight(entry) && (
              <p className="mt-1.5 flex items-start gap-1.5 pl-[5.5rem] text-xs font-medium text-warn-600">
                <Moon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                {t('availability.overnightBlocked')}
              </p>
            )}

            {missingTime && (
              <p className="mt-1.5 pl-[5.5rem] text-xs font-medium text-bad-600">
                {t('validation.required')}
              </p>
            )}
          </div>
        )
      })}

      <p className="pt-2 text-xs text-ink-500">{t('availability.openAllDayHint')}</p>
    </div>
  )
}
