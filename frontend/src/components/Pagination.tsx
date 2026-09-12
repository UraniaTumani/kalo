import { useTranslation } from 'react-i18next'
import { Button } from './ui'
import type { Page } from '@/lib/api/types'

export function Pagination<T>({
  page,
  onPageChange,
}: {
  page: Page<T> | undefined
  onPageChange: (next: number) => void
}) {
  const { t } = useTranslation()

  if (!page || page.totalPages <= 1) return null

  return (
    <div className="flex items-center justify-between gap-4 border-t border-ink-200/70 px-5 py-3">
      <p className="text-xs text-ink-500">
        {t('common.page', { current: page.number + 1, total: page.totalPages })} · {t('common.totalItems', { count: page.totalElements })}
      </p>
      <div className="flex gap-2">
        <Button
          size="sm"
          variant="secondary"
          disabled={page.first}
          onClick={() => onPageChange(page.number - 1)}
        >
          {t('common.previous')}
        </Button>
        <Button
          size="sm"
          variant="secondary"
          disabled={page.last}
          onClick={() => onPageChange(page.number + 1)}
        >
          {t('common.next')}
        </Button>
      </div>
    </div>
  )
}
