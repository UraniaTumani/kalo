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
    tokenStorage.clear()
    setUser(null)
    queryClient.clear()
  }, [queryClient])

  // A token can be revoked server-side (suspension) or simply expire, so a
  // 401 on any call ends the session rather than leaving a broken shell.
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
    const { accessToken } = await authApi.login({ phone, password })
    tokenStorage.set(accessToken)

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
