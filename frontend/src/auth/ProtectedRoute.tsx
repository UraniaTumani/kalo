import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth, homePathFor } from './AuthContext'
import type { UserRole } from '@/lib/api/types'
import { Spinner } from '@/components/ui/Spinner'

export function ProtectedRoute({ allow }: { allow: UserRole[] }) {
  const { user, role, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner />
      </div>
    )
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  // Signed in but on the wrong surface: send them to their own, rather than
  // showing a dead end.
  if (role && !allow.includes(role)) {
    return <Navigate to={homePathFor(role)} replace />
  }

  return <Outlet />
}
