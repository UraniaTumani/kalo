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

    // A dev-proxy or gateway answers 502/503/504 itself when it cannot reach
    // the API, so these are not the backend talking — the usual cause is that
    // it is not running. Without this they surfaced as "Request failed with
    // status 502", which says nothing about what to do.
    if (error.status === 502 || error.status === 503 || error.status === 504) {
      return 'Could not reach the server. Is the backend running on port 8080?'
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
