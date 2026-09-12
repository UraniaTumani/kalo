import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import sq from './locales/sq.json'

export const SUPPORTED_LANGUAGES = ['sq', 'en'] as const
export type Language = (typeof SUPPORTED_LANGUAGES)[number]

const STORAGE_KEY = 'kalo.language'

/** Albanian is the default: KALO operates in Albania. */
export const DEFAULT_LANGUAGE: Language = 'sq'

function storedLanguage(): Language {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && (SUPPORTED_LANGUAGES as readonly string[]).includes(saved)) {
      return saved as Language
    }
  } catch {
    // Private windows and blocked site data throw on access.
  }

  return DEFAULT_LANGUAGE
}

export function setLanguage(language: Language) {
  try {
    localStorage.setItem(STORAGE_KEY, language)
  } catch {
    // Preference is lost on reload, which is better than failing the switch.
  }

  void i18n.changeLanguage(language)
  document.documentElement.lang = language
}

void i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    sq: { translation: sq },
  },
  lng: storedLanguage(),
  fallbackLng: DEFAULT_LANGUAGE,
  interpolation: {
    // React escapes for us.
    escapeValue: false,
  },
})

document.documentElement.lang = i18n.language

export default i18n
