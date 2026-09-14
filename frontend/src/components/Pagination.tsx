import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from './ui'
import type { Page } from '@/lib/api/types'

/**
 * A page footer that says where you are, not just that you can move.
 *
 * "Page 2 of 5" leaves the reader to work out which records they are looking
 * at; "Showing 11–20 of 47" answers that directly, which is what an admin
 * scanning a long list actually wants to know. The range is still paired with
 * the page count, because that is what the two buttons move.
 */
export function Pagination<T>({
  page,
  onPageChange,
}: {
  page: Page<T> | undefined
  onPageChange: (next: number) => void
}) {
  const { t } = useTranslation()

  if (!page || page.totalPages <= 1) return null

  const from = page.number * page.size + 1
  const to = Math.min(from + page.content.length - 1, page.totalElements)

  return (
    <div className="flex flex-col gap-3 border-t border-ink-200/70 px-5 py-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="tnum text-xs text-ink-500">
        {t('common.showingRange', { from, to, total: page.totalElements })}
        <span className="ml-1.5 text-ink-400">
          · {t('common.page', { current: page.number + 1, total: page.totalPages })}
        </span>
      </p>

      {/* On a phone the two buttons share the width instead of hugging one edge. */}
      <div className="flex gap-2">
        <Button
          size="sm"
          variant="secondary"
          className="flex-1 sm:flex-none"
          disabled={page.first}
          onClick={() => onPageChange(page.number - 1)}
        >
          <ChevronLeft className="size-4" aria-hidden />
          {t('common.previous')}
        </Button>
        <Button
          size="sm"
          variant="secondary"
          className="flex-1 sm:flex-none"
          disabled={page.last}
          onClick={() => onPageChange(page.number + 1)}
        >
          {t('common.next')}
          <ChevronRight className="size-4" aria-hidden />
        </Button>
      </div>
    </div>
  )
}
