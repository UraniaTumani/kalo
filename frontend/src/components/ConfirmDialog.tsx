import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui'

/**
 * Asks before an action that cannot be walked back.
 *
 * Every destructive control in the app used to fire on the first click, so a
 * mis-aimed tap in a table row suspended a company or took a driver off the
 * road with no way to undo it. This is the one place that question gets asked,
 * so the wording, the button order and the loading behaviour stay the same
 * wherever it appears.
 *
 * The caller owns the mutation and passes `loading` while it runs; the dialog
 * stays open and inert until the caller closes it, which is what keeps a
 * double click from sending a second request.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel,
  loading = false,
  onConfirm,
  onCancel,
}: {
  open: boolean
  title: string
  /** What will actually happen, in plain words. Shown under the title. */
  description: ReactNode
  /** Names the action ("Suspend company"), never a bare "OK". */
  confirmLabel: string
  cancelLabel?: string
  loading?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const cancelRef = useRef<HTMLButtonElement>(null)

  /**
   * Remembers what opened the dialog, moves focus into it, and puts focus back
   * on the way out.
   *
   * Focus lands on Cancel rather than Confirm: someone who opened this by
   * accident should be one Enter away from backing out, not from going through
   * with it.
   *
   * Capture and move have to happen in this order inside a single effect. Split
   * across two, the effect that focuses Cancel runs first and the capture then
   * records *Cancel* as the thing to restore to; on close that button no longer
   * exists, so focus lands on <body> and the next Tab starts again from the top
   * of the page. Which is exactly the bug this was meant to fix.
   */
  const restoreRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) return

    restoreRef.current = document.activeElement as HTMLElement | null

    cancelRef.current?.focus()

    return () => restoreRef.current?.focus?.()
  }, [open])

  /*
   * Keeps Tab inside the dialog.
   *
   * `aria-modal` tells a screen reader to ignore the page behind, but it does
   * nothing to the tab order: Tab from the last button moved into content the
   * user could no longer see, and on a destructive confirmation that means
   * typing into a form that is hidden behind an overlay. The panel is the only
   * thing on screen, so it should be the only thing reachable.
   */
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return

    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Tab') return

      const panel = panelRef.current
      if (!panel) return

      const focusable = Array.from(
        panel.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => el.offsetParent !== null)

      if (focusable.length === 0) return

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement

      /* Wrap at both ends, and pull focus back in if it has already escaped. */
      if (event.shiftKey && (active === first || !panel.contains(active))) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  useEffect(() => {
    if (!open) {
      return
    }

    function onKeyDown(event: KeyboardEvent) {
      // Escape is a way out, but not once the request is in flight — closing
      // then would hide an action that is still going to happen.
      if (event.key === 'Escape' && !loading) {
        onCancel()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, loading, onCancel])

  // The dialog owns the scroll lock only while it is up.
  useEffect(() => {
    if (!open) {
      return
    }

    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [open])

  if (!open) {
    return null
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      aria-describedby="confirm-dialog-description"
    >
      <div
        className="absolute inset-0 bg-ink-900/40 backdrop-blur-[1px]"
        aria-hidden
        onClick={() => {
          if (!loading) {
            onCancel()
          }
        }}
      />

      <div
        ref={panelRef}
        className="relative w-full max-w-md rounded-xl bg-white p-5 shadow-xl shadow-ink-900/10"
      >
        <h2 id="confirm-dialog-title" className="text-sm font-semibold text-ink-900">
          {title}
        </h2>

        <div id="confirm-dialog-description" className="mt-1.5 text-sm text-ink-600">
          {description}
        </div>

        <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            ref={cancelRef}
            variant="secondary"
            onClick={onCancel}
            disabled={loading}
          >
            {cancelLabel ?? t('common.cancel')}
          </Button>

          <Button variant="danger" loading={loading} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}
