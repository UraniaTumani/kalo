import { useTranslation } from 'react-i18next'
import { SUPPORTED_LANGUAGES, setLanguage, type Language } from '@/i18n'
import { cn } from '@/lib/utils'

const SHORT: Record<Language, string> = {
  sq: 'SQ',
  en: 'EN',
}

const FULL: Record<Language, string> = {
  sq: 'Shqip',
  en: 'English',
}

/**
 * Compact SQ / EN toggle. Kept to two short codes so it fits in a sidebar or a
 * header without competing with the actual navigation.
 */
export function LanguageSwitcher({ className }: { className?: string }) {
  const { i18n, t } = useTranslation()
  const active = (i18n.language as Language) ?? 'sq'

  return (
    <div
      role="group"
      aria-label={t('language.label')}
      className={cn('inline-flex rounded-lg bg-ink-100 p-0.5', className)}
    >
      {SUPPORTED_LANGUAGES.map((language) => {
        const selected = active === language

        return (
          <button
            key={language}
            type="button"
            lang={language}
            aria-pressed={selected}
            title={FULL[language]}
            onClick={() => setLanguage(language)}
            className={cn(
              'rounded-md px-2 py-1 text-xs font-semibold transition',
              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500',
              selected
                ? 'bg-white text-ink-900 shadow-sm'
                : 'text-ink-500 hover:text-ink-800',
            )}
          >
            {SHORT[language]}
          </button>
        )
      })}
    </div>
  )
}
