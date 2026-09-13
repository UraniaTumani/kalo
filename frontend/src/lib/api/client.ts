import type { ErrorResponse } from './types'

const TOKEN_KEY = 'kalo.token'
const REFRESH_KEY = 'kalo.refresh'

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
  getRefresh: () => localStorage.getItem(REFRESH_KEY),

  set: (accessToken: string, refreshToken?: string) => {
    localStorage.setItem(TOKEN_KEY, accessToken)
    if (refreshToken) {
      localStorage.setItem(REFRESH_KEY, refreshToken)
    }
  },

  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
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
 * One refresh at a time, shared by every caller.
 *
 * A dashboard can have a dozen queries in flight when the access token
 * expires, and each would otherwise try to refresh. Since refresh tokens
 * rotate, the first would succeed and the rest would present a token that is
 * already spent — logging the user out for being too busy. Callers past the
 * first wait on the same promise instead.
 */
let refreshInFlight: Promise<boolean> | null = null

async function refreshAccessToken(): Promise<boolean> {
  const refreshToken = tokenStorage.getRefresh()

  if (!refreshToken) {
    return false
  }

  const response = await fetch(buildUrl(REFRESH_PATH), {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  })

  if (!response.ok) {
    return false
  }

  const body = (await response.json()) as { accessToken: string; refreshToken: string }

  tokenStorage.set(body.accessToken, body.refreshToken)

  return true
}

function refreshOnce(): Promise<boolean> {
  refreshInFlight ??= refreshAccessToken()
    .catch(() => false)
    .finally(() => {
      refreshInFlight = null
    })

  return refreshInFlight
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

  /** Set on the replayed request so a refresh loop cannot form. */
  skipRefresh?: boolean
}

/**
 * Empty by default, which keeps every request same-origin — the topology the
 * production nginx image serves, where /api is forwarded to the backend and no
 * CORS is involved.
 *
 * Set VITE_API_URL at build time (it is inlined, not read at runtime) to point
 * at a separately hosted API; the backend then also needs that exact origin in
 * CORS_ALLOWED_ORIGINS.
 */
const API_BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '')

const REFRESH_PATH = '/api/v1/auth/refresh'

function buildUrl(path: string, params?: QueryParams) {
  const url = new URL(API_BASE + path, API_BASE || window.location.origin)

  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    }
  }

  // Same-origin stays a relative path; a configured base keeps its host.
  return API_BASE ? url.toString() : url.pathname + url.search
}

/**
 * Sends the request once, with whatever token is current.
 *
 * `skipRefresh` is set for the retry and for the auth calls themselves, so a
 * failing refresh cannot recurse.
 */
async function performRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, params } = options

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

    throw new ApiError(
      response.status,
      errorBody?.message ?? `Request failed with status ${response.status}`,
      errorBody,
    )
  }

  return (await response.json()) as T
}

/**
 * The access token is short-lived by design, so a 401 usually means "expired",
 * not "signed out". Refresh once and replay the request; only when the refresh
 * itself fails does the session actually end.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await performRequest<T>(path, options)
  } catch (error) {
    const isExpired =
      error instanceof ApiError &&
      error.status === 401 &&
      !options.skipAuthRedirect &&
      !options.skipRefresh

    if (!isExpired) {
      throw error
    }

    const refreshed = await refreshOnce()

    if (!refreshed) {
      onUnauthorized()
      throw error
    }

    try {
      return await performRequest<T>(path, { ...options, skipRefresh: true })
    } catch (retryError) {
      // A 401 on a token minted seconds ago is not expiry — the account was
      // suspended, or the user no longer exists.
      if (retryError instanceof ApiError && retryError.status === 401) {
        onUnauthorized()
      }
      throw retryError
    }
  }
}

export const api = {
  get: <T>(path: string, params?: QueryParams) => request<T>(path, { method: 'GET', params }),

  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'POST', body }),

  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),

  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),

  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
