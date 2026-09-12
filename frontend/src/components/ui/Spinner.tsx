import { useTranslation } from 'react-i18next'

export function Spinner({ label }: { label?: string }) {
  const { t } = useTranslation()

  return (
    <div className="flex items-center gap-2 text-sm text-ink-500">
      <span
        aria-hidden
        className="size-4 animate-spin rounded-full border-2 border-ink-300 border-t-brand-600"
      />
      <span>{label ?? t('common.loading')}</span>
    </div>
  )
}
