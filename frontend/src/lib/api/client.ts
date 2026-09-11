import type { ErrorResponse } from './types'

const TOKEN_KEY = 'kalo.token'

/**
 * Thrown for any non-2xx response. `status` lets callers branch on the
 * backend's error semantics (400 validation, 401 auth, 403 role, 404, 409).
 */
export class ApiError extends Error {
  readonly status: number
  readonly body?: ErrorResponse

  constructor(status: number, message: string, body?: ErrorResponse) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

export const tokenStorage = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

/**
 * Notified when the API rejects our token, so the auth layer can drop the
 * session without every caller having to handle 401 itself.
 */
type UnauthorizedHandler = () => void
let onUnauthorized: UnauthorizedHandler = () => {}

export function setUnauthorizedHandler(handler: UnauthorizedHandler) {
  onUnauthorized = handler
}

/**
 * Query parameters are typed as a plain object rather than a Record so that
 * callers can pass their own named param interfaces without each one needing
 * an index signature.
 */
export type QueryParams = object

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  params?: QueryParams
  /** Set for the login/register calls, which must not trigger a session drop. */
  skipAuthRedirect?: boolean
}

function buildUrl(path: string, params?: QueryParams) {
  const url = new URL(path, window.location.origin)

  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    }
  }

  return url.pathname + url.search
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, params, skipAuthRedirect } = options

  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = tokenStorage.get()

  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(buildUrl(path, params), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 204) {
    return undefined as T
  }

  if (!response.ok) {
    let errorBody: ErrorResponse | undefined

    try {
      errorBody = (await response.json()) as ErrorResponse
    } catch {
      // A proxy or gateway can answer with something that is not our JSON.
    }

    if (response.status === 401 && !skipAuthRedirect) {
      onUnauthorized()
    }

    throw new ApiError(
      response.status,
      errorBody?.message ?? `Request failed with status ${response.status}`,
      errorBody,
    )
  }

  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string, params?: QueryParams) => request<T>(path, { method: 'GET', params }),

  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'POST', body }),

  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),

  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),

  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
