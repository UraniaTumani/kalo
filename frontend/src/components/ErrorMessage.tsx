import { useTranslation } from 'react-i18next'
import { ApiError } from '@/lib/api/client'
import { GeolocationError } from '@/lib/geolocation'
import { UnexpectedResponseError } from '@/lib/api/page'
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
