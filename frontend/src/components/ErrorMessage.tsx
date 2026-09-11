import { ApiError } from '@/lib/api/client'
import { Alert } from './ui'

/**
 * The backend already returns a human-readable `message` in its shared error
 * body, so surface that rather than inventing our own copy. Anything else
 * (network failure, proxy error) gets a generic line.
 */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return 'You do not have permission to do that.'
    }
    return error.message
  }

  if (error instanceof Error && error.message) {
    return 'Could not reach the server. Is the backend running?'
  }

  return 'Something went wrong.'
}

export function ErrorMessage({ error, title }: { error: unknown; title?: string }) {
  if (!error) return null

  return (
    <Alert tone="danger" title={title}>
      {errorMessage(error)}
    </Alert>
  )
}
