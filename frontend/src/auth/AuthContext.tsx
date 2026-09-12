import { createContext, use, useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { setUnauthorizedHandler, tokenStorage } from '@/lib/api/client'
import { authApi } from '@/lib/api/endpoints'
import type { UserResponse, UserRole } from '@/lib/api/types'

interface AuthContextValue {
  user: UserResponse | null
  role: UserRole | null
  /** True until the stored token has been checked against /me. */
  loading: boolean
  login: (phone: string, password: string) => Promise<UserResponse>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [user, setUser] = useState<UserResponse | null>(null)
  const [loading, setLoading] = useState(true)

  const logout = useCallback(() => {
    const refreshToken = tokenStorage.getRefresh()

    /*
     * Fire-and-forget: the session is over locally either way, and a failed
     * revoke should not keep someone staring at a dashboard they asked to
     * leave. The token expires on its own if this never lands.
     */
    if (refreshToken) {
      void authApi.logout(refreshToken).catch(() => {})
    }

    tokenStorage.clear()
    setUser(null)
    queryClient.clear()
  }, [queryClient])

  // Reached only once a refresh has already failed: the client retries an
  // expired access token transparently, so a 401 arriving here means the
  // session is genuinely over — revoked, suspended, or long past expiry.
  useEffect(() => {
    setUnauthorizedHandler(logout)
  }, [logout])

  useEffect(() => {
    if (!tokenStorage.get()) {
      setLoading(false)
      return
    }

    let cancelled = false

    authApi
      .me()
      .then((me) => {
        if (!cancelled) setUser(me)
      })
      .catch(() => {
        tokenStorage.clear()
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (phone: string, password: string) => {
    const { accessToken, refreshToken } = await authApi.login({ phone, password })
    tokenStorage.set(accessToken, refreshToken)

    const me = await authApi.me()
    setUser(me)
    return me
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ user, role: user?.role ?? null, loading, login, logout }),
    [user, loading, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}

export function useAuth() {
  const context = use(AuthContext)

  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider')
  }

  return context
}

/** Where each role lands after logging in. */
export function homePathFor(role: UserRole | null) {
  switch (role) {
    case 'PARTNER':
      return '/partner'
    case 'ADMIN':
      return '/admin'
    case 'CUSTOMER':
      return '/ride'
    default:
      return '/login'
  }
}
