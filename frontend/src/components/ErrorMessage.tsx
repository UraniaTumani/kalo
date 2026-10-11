import { useTranslation } from 'react-i18next'
import { ApiError } from '@/lib/api/client'
import { GeolocationError } from '@/lib/geolocation'
import { UnexpectedResponseError } from '@/lib/api/page'
import { backendMessageKey } from '@/lib/api/backendMessages'
import { Alert } from './ui'

/**
 * Turns anything thrown into a translated sentence.
 *
 * Errors carry a translation key rather than a finished sentence, so the text
 * follows the selected language instead of whichever one happened to be active
 * when the error was constructed. The backend's own `message` is the exception:
 * it is already human-readable and no frontend catalogue can cover every rule
 * the server enforces, so it is shown as-is once the cases we can translate
 * have been handled.
 */
export function useErrorMessage() {
  const { t } = useTranslation()

  return (error: unknown): string => {
    if (error instanceof GeolocationError) {
      return t(`geo.${error.kind}`)
    }

    if (error instanceof UnexpectedResponseError) {
      return t(`errors.${error.translationKey}`)
    }

    if (error instanceof ApiError) {
      if (error.status === 403) {
        return t('errors.forbidden')
      }

      // A dev proxy or gateway answers these itself when it cannot reach the
      // API, so they are not the backend talking.
      if (error.status === 502 || error.status === 503 || error.status === 504) {
        return t('errors.unreachable')
      }

      /*
       * F39: the rules a customer can actually hit, in their own language.
       *
       * The fallthrough below stays exactly as it was, and still handles most
       * of the roughly 126 places the backend throws a sentence. What changes
       * is that the subset a customer can reach — searching, choosing a
       * company, cancelling — is looked up first, so an Albanian-speaking
       * customer is not told "Ride request has expired" in English.
       *
       * backendMessages.ts holds the table and the reasoning, including why
       * the match is exact and why partner and admin screens are left on the
       * fallthrough on purpose.
       */
      const translated = backendMessageKey(error.message)
      if (translated) {
        return t(translated)
      }

      if (error.status === 404) {
        return error.message || t('errors.notFound')
      }

      return error.message || t('errors.generic')
    }

    if (error instanceof Error && error.message) {
      return t('errors.unreachable')
    }

    return t('errors.generic')
  }
}

export function ErrorMessage({ error, title }: { error: unknown; title?: string }) {
  const errorMessage = useErrorMessage()

  if (!error) return null

  return (
    <Alert tone="danger" title={title}>
      {errorMessage(error)}
    </Alert>
  )
}
